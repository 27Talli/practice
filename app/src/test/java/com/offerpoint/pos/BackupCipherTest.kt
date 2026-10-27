package com.offerpoint.pos

import com.offerpoint.pos.data.backup.BackupCipher
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class BackupCipherTest {
    private val password = "a private test passphrase".toCharArray()
    private fun encrypt(bytes: ByteArray) = ByteArrayOutputStream().also { BackupCipher.encrypt(bytes.inputStream(),it,password) }.toByteArray()
    private fun decrypt(bytes: ByteArray, key: CharArray=password) = ByteArrayOutputStream().also { BackupCipher.decrypt(bytes.inputStream(),it,key) }.toByteArray()
    @Test fun multipleChunksRoundTripExactBytesWithoutChangingThePassphrase() {
        val bytes = ByteArray(BackupCipher.CHUNK*2+345) { (it%251).toByte() }
        assertArrayEquals(bytes,decrypt(encrypt(bytes)))
        assertEquals("a private test passphrase",String(password))
    }
    @Test fun randomizedEncryptionDoesNotRevealPlaintext() {
        val bytes="PRIVATE COST KEY BLACKHORSE AND SALES".toByteArray()
        val first=encrypt(bytes);val second=encrypt(bytes)
        assertFalse(first.contentEquals(second));assertFalse(String(first,Charsets.ISO_8859_1).contains("BLACKHORSE"))
        assertArrayEquals(bytes,decrypt(first))
    }
    @Test fun wrongPassphraseAndTamperedHeaderCiphertextOrTagAreRejected() {
        val encrypted=encrypt(ByteArray(800){it.toByte()})
        assertThrows(Exception::class.java){decrypt(encrypted,"different-password".toCharArray())}
        for(index in listOf(0,15,33,43,encrypted.lastIndex)) {
            val changed=encrypted.copyOf();changed[index]=(changed[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java){decrypt(changed)}
        }
    }
    @Test fun truncationMissingFinalChunkAndAppendedDataAreRejected() {
        val encrypted=encrypt(ByteArray(BackupCipher.CHUNK){7})
        for(length in listOf(0,20,encrypted.size-1,encrypted.size-20)) assertThrows(Exception::class.java){decrypt(encrypted.copyOf(length))}
        assertThrows(Exception::class.java){decrypt(encrypted+byteArrayOf(0))}
    }
    @Test fun chunkReorderingAndOversizeLengthsAreRejected() {
        val encrypted=encrypt(ByteArray(BackupCipher.CHUNK*2){(it%241).toByte()})
        val chunkBytes=4+BackupCipher.CHUNK+16
        val changed=encrypted.copyOf()
        encrypted.copyInto(changed,36,36+chunkBytes,36+2*chunkBytes)
        encrypted.copyInto(changed,36+chunkBytes,36,36+chunkBytes)
        assertThrows(Exception::class.java){decrypt(changed)}
        val huge=encrypted.copyOf();ByteBuffer.wrap(huge,36,4).putInt(Int.MAX_VALUE)
        assertThrows(Exception::class.java){decrypt(huge)}
    }
    @Test fun emptyPayloadAuthenticatesAndWeakPassphrasesAreRejected() {
        assertEquals(0,decrypt(encrypt(byteArrayOf())).size)
        for(p in listOf("short","X".repeat(1025),"bad\npassword123")) assertThrows(IllegalArgumentException::class.java){ BackupCipher.validatePassword(p.toCharArray()) }
    }
}
