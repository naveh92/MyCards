package com.mycards.data.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class BackupCodecTest {

    private static final String PAYLOAD =
            "{\"cards\":[{\"uuid\":\"abc\",\"pan\":\"4580123412341234\",\"cvv\":\"123\"}]}";

    @Test
    public void roundTripsThroughAPassphrase() throws Exception {
        byte[] blob = BackupCodec.encrypt(PAYLOAD, "correct horse battery".toCharArray());
        assertEquals(PAYLOAD, BackupCodec.decrypt(blob, "correct horse battery".toCharArray()));
    }

    @Test
    public void wrongPassphraseIsRejected() throws Exception {
        byte[] blob = BackupCodec.encrypt(PAYLOAD, "right".toCharArray());
        try {
            BackupCodec.decrypt(blob, "wrong".toCharArray());
            fail("a wrong passphrase must not decrypt");
        } catch (BackupCodec.InvalidPassphraseException expected) {
            // The only correct outcome.
        }
    }

    @Test
    public void tamperingWithTheCiphertextIsDetected() throws Exception {
        byte[] blob = BackupCodec.encrypt(PAYLOAD, "pass".toCharArray());
        blob[blob.length - 5] ^= 0x01;
        try {
            BackupCodec.decrypt(blob, "pass".toCharArray());
            fail("a modified backup must not decrypt");
        } catch (BackupCodec.InvalidPassphraseException expected) {
            // GCM's tag check catches it.
        }
    }

    @Test
    public void tamperingWithTheHeaderIsDetected() throws Exception {
        byte[] blob = BackupCodec.encrypt(PAYLOAD, "pass".toCharArray());
        // Flip a byte of the salt. It is authenticated as AAD, so this must fail rather
        // than silently deriving a different key.
        blob[MAGIC_LEN + 1 + 4] ^= 0x01;
        try {
            BackupCodec.decrypt(blob, "pass".toCharArray());
            fail("a modified header must not decrypt");
        } catch (BackupCodec.InvalidPassphraseException expected) {
            // Expected.
        }
    }

    private static final int MAGIC_LEN = 8;

    @Test
    public void refusesAFileThatIsNotABackup() {
        try {
            BackupCodec.decrypt("just some text file".getBytes(StandardCharsets.UTF_8),
                    "pass".toCharArray());
            fail("an unrelated file must be rejected");
        } catch (Exception e) {
            // Must be a format complaint, not a passphrase complaint — the user picked
            // the wrong file and telling them "wrong passphrase" would send them hunting
            // for the wrong problem.
            org.junit.Assert.assertTrue(e instanceof BackupCodec.BackupFormatException);
        }
    }

    @Test
    public void refusesAnAbsurdIterationCount() throws Exception {
        byte[] blob = BackupCodec.encrypt(PAYLOAD, "pass".toCharArray());
        // A hostile file could otherwise demand a billion PBKDF2 rounds and hang the app.
        blob[MAGIC_LEN + 1] = 0x7F;
        blob[MAGIC_LEN + 2] = (byte) 0xFF;
        try {
            BackupCodec.decrypt(blob, "pass".toCharArray());
            fail("an implausible iteration count must be rejected");
        } catch (BackupCodec.BackupFormatException expected) {
            // Expected, and importantly it fails fast rather than grinding.
        }
    }

    @Test
    public void ciphertextDiffersEachTimeForTheSameInput() throws Exception {
        byte[] a = BackupCodec.encrypt(PAYLOAD, "pass".toCharArray());
        byte[] b = BackupCodec.encrypt(PAYLOAD, "pass".toCharArray());
        // Fresh salt and IV per export, so two backups of identical data are not
        // byte-identical and cannot be compared to infer that nothing changed.
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    public void plaintextIsNotRecoverableFromTheBlob() throws Exception {
        byte[] blob = BackupCodec.encrypt(PAYLOAD, "pass".toCharArray());
        String asText = new String(blob, StandardCharsets.ISO_8859_1);
        assertFalse("the card number must not survive in the clear",
                asText.contains("4580123412341234"));
    }

    @Test
    public void handlesUnicodeAndEmptyPayloads() throws Exception {
        String hebrew = "{\"label\":\"מתנת חג 2026 — ₪250\"}";
        byte[] blob = BackupCodec.encrypt(hebrew, "סיסמה".toCharArray());
        assertEquals(hebrew, BackupCodec.decrypt(blob, "סיסמה".toCharArray()));

        byte[] empty = BackupCodec.encrypt("", "pass".toCharArray());
        assertEquals("", BackupCodec.decrypt(empty, "pass".toCharArray()));
    }

    @Test
    public void magicBytesIdentifyTheFile() throws Exception {
        byte[] blob = BackupCodec.encrypt(PAYLOAD, "pass".toCharArray());
        assertArrayEquals("MYCARDS1".getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOfRange(blob, 0, MAGIC_LEN));
    }
}
