package com.mycards.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;

import org.junit.Test;

/**
 * Covers what a restore is allowed to claim.
 *
 * <p>Written after a backup was verified by restoring it onto the phone it came from. Every
 * card was already present, so the merge stepped over all of them and reported "0 added,
 * 5 unchanged" — read, reasonably, as proof the file was good. It was not proof of anything:
 * the same line appears for a file holding one card, or none, or nonsense. The data was gone
 * by the time that became apparent.
 */
public class BackupImportReportTest {

    private static final char[] PASS = "passphrase1".toCharArray();

    private static byte[] encrypted(BackupPayload payload) throws Exception {
        return BackupCodec.encrypt(new Gson().toJson(payload), PASS.clone());
    }

    private static BackupPayload.Card card(String uuid, String pan) {
        BackupPayload.Card card = new BackupPayload.Card();
        card.uuid = uuid;
        card.cardTypeId = "buyme_all";
        card.label = "Birthday";
        card.initialAmount = 250.0;
        card.pan = pan;
        return card;
    }

    /** The failure that started all this: an export that wrote nothing must not read back
     *  as a successful restore. */
    @Test
    public void anEmptyBackupIsRefusedRatherThanRestoredAsNothing() throws Exception {
        byte[] blob = encrypted(new BackupPayload());

        try {
            BackupManager.parse(blob, PASS.clone());
            fail("an empty backup must be refused, not reported as a clean restore");
        } catch (BackupManager.EmptyBackupException expected) {
            // The user finds out now, while the data still exists.
        }
    }

    @Test
    public void aZeroByteFileIsRefused() {
        try {
            BackupManager.parse(new byte[0], PASS.clone());
            fail("a zero-byte file is not a backup");
        } catch (BackupCodec.BackupFormatException expected) {
            // Too short to hold even the header.
        } catch (Exception e) {
            fail("expected a format error, got " + e);
        }
    }

    @Test
    public void aBackupHoldingOnlyPurchasesIsStillReadable() throws Exception {
        BackupPayload payload = new BackupPayload();
        BackupPayload.Spend spend = new BackupPayload.Spend();
        spend.uuid = "s1";
        spend.cardUuid = "a";
        spend.amount = 30.0;
        payload.spends.add(spend);

        BackupPayload back = BackupManager.parse(encrypted(payload), PASS.clone());

        assertEquals(0, back.cards.size());
        assertEquals(1, back.spends.size());
    }

    @Test
    public void secretsAreDetectedSoTheRestoreCanAskForAnUnlockFirst() throws Exception {
        BackupPayload withSecrets = new BackupPayload();
        withSecrets.cards.add(card("a", "4580458045804580"));

        BackupPayload withoutSecrets = new BackupPayload();
        withoutSecrets.cards.add(card("b", null));

        // Card numbers are rewrapped under the auth-bound key on the way in, and that key
        // refuses to encrypt without a recent unlock — so the restore has to know in advance.
        assertTrue(BackupManager.containsSecrets(withSecrets));
        assertFalse(BackupManager.containsSecrets(withoutSecrets));
    }

    /** The exact report that was read as a passing test of a backup, moments before the
     *  data it should have held was gone. */
    @Test
    public void aRestoreThatPutNothingBackIsAFailureNotASummary() {
        BackupManager.ImportResult noOp = new BackupManager.ImportResult();
        noOp.cardsInFile = 5;
        noOp.spendsInFile = 3;
        noOp.cardsSkipped = 5;

        assertTrue("0 added, 5 unchanged is not a restore and must not be presented as one",
                noOp.changedNothing());

        // The counts that say whether the file is any good survive independently of the
        // counts that say what happened to this phone.
        assertEquals(5, noOp.cardsInFile);
        assertEquals(3, noOp.spendsInFile);
        assertEquals(0, noOp.cardsAdded);
    }

    @Test
    public void aRealRestoreIsNotReportedAsAFailure() {
        BackupManager.ImportResult restored = new BackupManager.ImportResult();
        restored.cardsInFile = 5;
        restored.cardsAdded = 5;
        restored.spendsAdded = 3;

        assertFalse(restored.changedNothing());
        assertFalse("every card in the file landed somewhere", restored.lostSomething());
    }

    @Test
    public void cardsThatCouldNotBeStoredAreCountedRatherThanAbortingTheRest() {
        BackupManager.ImportResult partial = new BackupManager.ImportResult();
        partial.cardsInFile = 5;
        partial.cardsAdded = 4;
        partial.cardsFailed = 1;

        assertFalse(partial.changedNothing());
        assertTrue("a card that did not make it in has to be reported", partial.lostSomething());
        assertEquals("the four that worked are still restored", 4, partial.cardsAdded);
    }

    /**
     * The buckets have to add back up to what the file held. Entries were being dropped by a
     * bare continue that incremented nothing, so cards could vanish between the file and the
     * wallet while the report stayed tidy and reassuring.
     */
    @Test
    public void cardsMissingFromEveryBucketAreTreatedAsLost() {
        BackupManager.ImportResult leaky = new BackupManager.ImportResult();
        leaky.cardsInFile = 5;
        leaky.cardsAdded = 3;
        // Two cards went nowhere and nothing counted them.

        assertTrue("3 of 5 restored, with no account of the other 2, is not a success",
                leaky.lostSomething());
    }

    @Test
    public void droppedPurchasesAreReportedToo() {
        BackupManager.ImportResult result = new BackupManager.ImportResult();
        result.cardsInFile = 2;
        result.cardsAdded = 2;
        result.spendsInFile = 3;
        result.spendsAdded = 0;
        result.spendsDropped = 3;

        assertFalse(result.changedNothing());
        assertTrue("spend history going missing is not a detail", result.lostSomething());
    }

    @Test
    public void theWrongPassphraseIsNotMistakenForAnEmptyBackup() throws Exception {
        BackupPayload payload = new BackupPayload();
        payload.cards.add(card("a", null));

        try {
            BackupManager.parse(encrypted(payload), "someothervalue".toCharArray());
            fail("a wrong passphrase must be reported as such");
        } catch (BackupCodec.InvalidPassphraseException expected) {
            // Distinct from an empty file: one is a typo, the other is lost data.
        }
    }
}
