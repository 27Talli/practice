import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Dependency-free host companion for OPPOSBK1. Passphrase is read from stdin, never command arguments. */
public final class BackupCrypto {
    static final int CHUNK = 65536, ITERATIONS = 600000;
    static final long MAX_BYTES = 128L * 1024 * 1024;
    static final byte[] MAGIC = "OPPOSBK1".getBytes(StandardCharsets.US_ASCII);
    static byte[] key(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    static Cipher cipher(int mode, byte[] key, byte[] nonce, int index, int size, byte[] header) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, ByteBuffer.allocate(12).put(nonce).putInt(index).array()));
        cipher.updateAAD(header); cipher.updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(size).array()); return cipher;
    }
    static void encrypt(InputStream input, OutputStream output, char[] password) throws Exception {
        SecureRandom random = new SecureRandom(); byte[] salt = new byte[16], nonce = new byte[8]; random.nextBytes(salt); random.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(36).put(MAGIC).putInt(ITERATIONS).put(salt).put(nonce).array(); byte[] key = key(password,salt);
        try {
            DataOutputStream out = new DataOutputStream(output); out.write(header); byte[] buffer = new byte[CHUNK]; int index = 0; long total = 0;
            while (true) {
                int size = input.readNBytes(buffer,0,CHUNK); total += size; if (total > MAX_BYTES) throw new IOException("Backup exceeds 128 MiB");
                out.writeInt(size); out.write(cipher(Cipher.ENCRYPT_MODE,key,nonce,index++,size,header).doFinal(buffer,0,size)); if (size == 0) break;
            }
            Arrays.fill(buffer,(byte)0); out.flush();
        } finally { Arrays.fill(key,(byte)0); }
    }
    static void decrypt(InputStream input, OutputStream output, char[] password) throws Exception {
        DataInputStream in = new DataInputStream(input); byte[] header = new byte[36]; in.readFully(header); ByteBuffer bytes = ByteBuffer.wrap(header);
        byte[] magic = new byte[8], salt = new byte[16], nonce = new byte[8]; bytes.get(magic);
        if (!Arrays.equals(magic,MAGIC) || bytes.getInt() != ITERATIONS) throw new IOException("Unsupported backup format");
        bytes.get(salt); bytes.get(nonce); byte[] key = key(password,salt);
        try {
            int index = 0; long total = 0;
            while (true) {
                int size = in.readInt(); if (size < 0 || size > CHUNK || (total += size) > MAX_BYTES) throw new IOException("Invalid backup length");
                byte[] encrypted = new byte[size + 16]; in.readFully(encrypted);
                byte[] clear = cipher(Cipher.DECRYPT_MODE,key,nonce,index++,size,header).doFinal(encrypted);
                if (size == 0) { if (in.read() != -1) throw new IOException("Trailing backup data"); break; }
                output.write(clear); Arrays.fill(clear,(byte)0);
            }
            output.flush();
        } finally { Arrays.fill(key,(byte)0); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("encrypt") || args[0].equals("decrypt"))) throw new IllegalArgumentException("Usage: java BackupCrypto.java encrypt|decrypt INPUT OUTPUT (passphrase on stdin)");
        char[] password = new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine().toCharArray();
        if (password.length < 10 || password.length > 1024) throw new IllegalArgumentException("Use a 10–1024 character passphrase");
        Path destination = Path.of(args[2]); boolean complete = false, created = false;
        try {
            OutputStream raw = Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW); created = true;
            try (OutputStream output = new BufferedOutputStream(raw); InputStream input = new BufferedInputStream(Files.newInputStream(Path.of(args[1])))) {
                if (args[0].equals("encrypt")) encrypt(input,output,password); else decrypt(input,output,password);
            }
            complete = true;
        } finally { Arrays.fill(password,'\0'); if (!complete && created) Files.deleteIfExists(destination); }
    }
}
