package com.mycards.data.backup;

import static org.junit.Assert.assertEquals;

import com.mycards.data.db.CardEntity;

import org.junit.Test;

/**
 * Pins down what an import does to cards that are already here.
 *
 * <p>A restore merges rather than replaces: it never deletes, it matches on the identity a
 * card was created with rather than on its name, and where both sides hold the same card the
 * newer record wins outright. Ties skip, which is what makes re-importing a file onto the
 * phone it came from do nothing whatsoever.
 */
public class BackupMergeTest {

    private static CardEntity stored(long updatedAt) {
        CardEntity card = new CardEntity();
        card.uuid = "a";
        card.updatedAt = updatedAt;
        return card;
    }

    private static BackupPayload.Card fromFile(String uuid, long updatedAt) {
        BackupPayload.Card card = new BackupPayload.Card();
        card.uuid = uuid;
        card.updatedAt = updatedAt;
        return card;
    }

    @Test
    public void aCardThisPhoneHasNeverSeenIsAdded() {
        assertEquals(BackupManager.CardAction.INSERT,
                BackupManager.planFor(fromFile("a", 100L), null));
    }

    @Test
    public void aNewerRecordInTheFileWins() {
        assertEquals(BackupManager.CardAction.UPDATE,
                BackupManager.planFor(fromFile("a", 500L), stored(100L)));
    }

    /** Edits made since the backup are not thrown away by restoring it. */
    @Test
    public void anOlderRecordInTheFileIsSkipped() {
        assertEquals(BackupManager.CardAction.SKIP,
                BackupManager.planFor(fromFile("a", 100L), stored(500L)));
    }

    /** Why re-importing the same file onto the same phone changes nothing at all. */
    @Test
    public void anIdenticalTimestampSkips() {
        assertEquals(BackupManager.CardAction.SKIP,
                BackupManager.planFor(fromFile("a", 100L), stored(100L)));
    }

    @Test
    public void aCardWithNoIdentifierIsNeverInsertedBlind() {
        assertEquals(BackupManager.CardAction.UNIDENTIFIED,
                BackupManager.planFor(fromFile(null, 100L), null));
        assertEquals(BackupManager.CardAction.UNIDENTIFIED,
                BackupManager.planFor(fromFile("   ", 100L), null));
    }

    /**
     * The newer record wins every ordinary field, but a card number is never replaced by the
     * absence of one. A partial export keeps the card's original timestamp, so it can be
     * genuinely newer than a copy held elsewhere and still arrive with nothing in it.
     */
    @Test
    public void aSecretTheFileDoesNotCarryIsKeptRatherThanErased() {
        assertEquals("the backup has no card number; the stored one survives",
                true, BackupManager.keepsStored(null, "stored-ciphertext"));
    }

    @Test
    public void aSecretTheFileCarriesReplacesWhatIsStored() {
        assertEquals(false, BackupManager.keepsStored("4580458045804580", "stored-ciphertext"));
    }

    @Test
    public void nothingOnEitherSideIsNotAKeep() {
        assertEquals(false, BackupManager.keepsStored(null, null));
    }

    @Test
    public void aSecretArrivingForACardThatHadNoneIsNotAKeep() {
        assertEquals(false, BackupManager.keepsStored("4580458045804580", null));
    }

    /**
     * Matching is on the card's own identity, not its label, so two different cards that
     * happen to share a name never collide — and the same card restored twice never doubles.
     */
    @Test
    public void identityIsTheUuidRatherThanTheName() {
        CardEntity onPhone = stored(100L);
        onPhone.label = "Holiday gift";

        BackupPayload.Card differentCardSameName = fromFile("b", 100L);
        differentCardSameName.label = "Holiday gift";

        // Looked up by uuid, so a different card is simply absent and gets added.
        assertEquals(BackupManager.CardAction.INSERT,
                BackupManager.planFor(differentCardSameName, null));
        // The same uuid at the same age is the same card, already here.
        assertEquals(BackupManager.CardAction.SKIP,
                BackupManager.planFor(fromFile("a", 100L), onPhone));
    }
}
