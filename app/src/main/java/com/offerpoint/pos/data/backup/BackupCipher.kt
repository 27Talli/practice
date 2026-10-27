package com.offerpoint.pos.data.backup

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Version 1: bounded-memory AES-256-GCM chunks, authenticated order/length and final empty chunk. */
object BackupCipher {
    const val MAX_BYTES = 128L * 1024 * 1024
    const val CHUNK = 65_536
    const val ITERATIONS = 600_000
    private val magic = "OPPOSBK1".toByteArray(Charsets.US_ASCII)

    fun validatePassword(password: CharArray) {
        require(password.size in 10..1024 && password.none { it == '\n' || it == '\r' }) { "Use a passphrase of 10–1024 characters without line breaks." }
    }

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        validatePassword(password)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(8).also(random::nextBytes)
        val header = ByteBuffer.allocate(36).put(magic).putInt(ITERATIONS).put(salt).put(nonce).array()
        val key = key(password, salt)
        try {
            val out = DataOutputStream(output); out.write(header)
            val buffer = ByteArray(CHUNK)
            var index = 0; var total = 0L
            while (true) {
                var count = 0
                while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; if (n == 0) continue; count += n }
                total += count; require(total <= MAX_BYTES) { "Backup exceeds the 128 MiB limit." }
                val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, index++, count, header)
                val encrypted = cipher.doFinal(buffer, 0, count)
                out.writeInt(count); out.write(encrypted)
                if (count == 0) break
            }
            buffer.fill(0); out.flush()
        } finally { key.fill(0) }
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        validatePassword(password)
        val source = DataInputStream(input)
        val header = ByteArray(36); source.readFully(header)
        val bytes = ByteBuffer.wrap(header)
        val actual = ByteArray(8).also { bytes.get(it) }
        require(actual.contentEquals(magic) && bytes.int == ITERATIONS) { "Unsupported backup format." }
        val salt = ByteArray(16).also { bytes.get(it) }; val nonce = ByteArray(8).also { bytes.get(it) }
        val key = key(password, salt)
        try {
            var index = 0; var total = 0L
            while (true) {
                val size = source.readInt(); require(size in 0..CHUNK) { "Invalid backup chunk." }
                total += size; require(total <= MAX_BYTES) { "Backup exceeds the 128 MiB limit." }
                val encrypted = ByteArray(size + 16); source.readFully(encrypted)
                val clear = cipher(Cipher.DECRYPT_MODE, key, nonce, index++, size, header).doFinal(encrypted)
                if (size == 0) { require(source.read() == -1) { "Unexpected data after the backup." }; break }
                output.write(clear); clear.fill(0)
            }
            output.flush()
        } finally { key.fill(0) }
    }

    private fun key(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, index: Int, size: Int, header: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).put(nonce).putInt(index).array()))
            updateAAD(header); updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(size).array())
        }
}
