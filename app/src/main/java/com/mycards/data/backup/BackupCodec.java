package com.mycards.data.backup;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts a backup under a passphrase the user knows.
 *
 * <p>This exists because the Keystore key that protects card numbers on the device is
 * <em>non-exportable by design</em> — it never leaves the phone. Copying the database to a
 * new device would therefore restore ciphertext nobody can ever decrypt. A backup has to be
 * re-encrypted under something the user carries in their head instead.
 *
 * <p>Deliberately free of Android imports so the format can be exercised on a plain JVM.
 *
 * <p>Layout: {@code magic(8) | version(1) | iterations(4) | salt(16) | iv(12) | ciphertext+tag}.
 * The salt and iteration count travel with the file so a future build can raise the cost
 * without being unable to read older backups.
 */
public final class BackupCodec {

    private BackupCodec() {
    }

    private static final byte[] MAGIC = "MYCARDS1".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;

    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    /**
     * PBKDF2 cost. High enough that guessing a weak passphrase is expensive, low enough
     * that an export on a mid-range phone still feels immediate.
     */
    private static final int ITERATIONS = 210_000;

    /** The passphrase was wrong, or the file has been altered since it was written. */
    public static class InvalidPassphraseException extends Exception {
        public InvalidPassphraseException(String message) {
            super(message);
        }
    }

    public static class BackupFormatException extends Exception {
        public BackupFormatException(String message) {
            super(message);
        }
    }

    public static byte[] encrypt(String plaintext, char[] passphrase) throws Exception {
        SecureRandom random = new SecureRandom();

        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);

        SecretKey key = deriveKey(passphrase, salt, ITERATIONS);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

        byte[] header = header(ITERATIONS, salt, iv);
        // Authenticating the header means the stored iteration count and salt cannot be
        // tampered with to weaken the file without the tag check failing.
        cipher.updateAAD(header);

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return ByteBuffer.allocate(header.length + ciphertext.length)
                .put(header)
                .put(ciphertext)
                .array();
    }

    public static String decrypt(byte[] blob, char[] passphrase)
            throws Exception {
        int headerLength = MAGIC.length + 1 + 4 + SALT_BYTES + IV_BYTES;
        if (blob == null || blob.length <= headerLength) {
            throw new BackupFormatException("File is too short to be a MyCards backup");
        }

        ByteBuffer in = ByteBuffer.wrap(blob);

        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new BackupFormatException("This is not a MyCards backup file");
        }

        byte version = in.get();
        if (version != VERSION) {
            throw new BackupFormatException("Backup version " + version + " is not supported");
        }

        int iterations = in.getInt();
        if (iterations < 1_000 || iterations > 10_000_000) {
            // A hostile file could otherwise ask for a billion rounds and hang the app.
            throw new BackupFormatException("Backup declares an implausible key strength");
        }

        byte[] salt = new byte[SALT_BYTES];
        in.get(salt);
        byte[] iv = new byte[IV_BYTES];
        in.get(iv);

        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);

        SecretKey key = deriveKey(passphrase, salt, iterations);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(header(iterations, salt, iv));

        try {
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException badTag) {
            // GCM cannot distinguish a wrong key from a modified file, and for the user
            // the actionable message is the same one.
            throw new InvalidPassphraseException(
                    "Wrong passphrase, or the backup file has been damaged");
        }
    }

    private static byte[] header(int iterations, byte[] salt, byte[] iv) {
        return ByteBuffer.allocate(MAGIC.length + 1 + 4 + salt.length + iv.length)
                .put(MAGIC)
                .put(VERSION)
                .putInt(iterations)
                .put(salt)
                .put(iv)
                .array();
    }

    private static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            // Clears the copy PBEKeySpec made; the caller still owns the original array.
            spec.clearPassword();
        }
    }
}
