package com.mycards.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.mycards.data.db.CardEntity;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Pins down that putting a card away by hand survives a backup, in both directions and
 * across the version of the app that did not have an archive at all.
 *
 * <p>{@code archivedAt} is the one retirement state that is stored rather than worked out:
 * "used up" and "expired" follow from the balance and the expiry and come back on their own,
 * but a decision to retire a card exists nowhere else. If a restore drops it, every card the
 * user has deliberately put away climbs back into the wallet, and there is nothing left to
 * recover it from.
 *
 * <p>The field was added to a format that was already in the wild <em>without</em> raising
 * {@code backupVersion}, so the cross-version behaviour is a deliberate choice and not an
 * accident — which is exactly the kind of thing that needs a test rather than a comment.
 */
public class BackupArchiveTest {

    private static final char[] PASS = "correct horse battery".toCharArray();

    /** Reads back whatever it is handed; this file is not about secrets. */
    private static final BackupManager.SecretReader PLAIN = new BackupManager.SecretReader() {
        @Override
        public String secret(String stored) {
            return stored;
        }

        @Override
        public String data(String stored) {
            return stored;
        }
    };

    private static CardEntity card(String uuid, long archivedAt) {
        CardEntity card = new CardEntity();
        card.uuid = uuid;
        card.cardTypeId = "buyme_all";
        card.label = "Birthday";
        card.initialAmount = 250.0;
        card.currency = "ILS";
        card.createdAt = 1_000L;
        card.updatedAt = 2_000L;
        card.archivedAt = archivedAt;
        return card;
    }

    // --- out ---

    @Test
    public void anArchivedCardIsExportedAsArchived() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Collections.singletonList(card("a", 1_700_000_000_000L)),
                Collections.emptyList(),
                PLAIN);

        assertEquals(1_700_000_000_000L, snapshot.payload.cards.get(0).archivedAt);
    }

    /** An archive is not a deletion: retired cards are in the file like any other. */
    @Test
    public void archivedCardsAreExportedAlongsideTheRestOfTheWallet() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Arrays.asList(card("in-use", 0L), card("retired", 1_700_000_000_000L)),
                Collections.emptyList(),
                PLAIN);

        assertEquals(2, snapshot.payload.cards.size());
        assertEquals(0L, snapshot.payload.cards.get(0).archivedAt);
        assertEquals(1_700_000_000_000L, snapshot.payload.cards.get(1).archivedAt);
    }

    // --- back in ---

    @Test
    public void restoringAnArchivedCardPutsItBackInTheArchive() {
        BackupPayload.Card inFile = new BackupPayload.Card();
        inFile.uuid = "a";
        inFile.archivedAt = 1_700_000_000_000L;

        CardEntity restored = new CardEntity();
        BackupManager.copyPlainFields(inFile, restored);

        assertEquals(1_700_000_000_000L, restored.archivedAt);
        assertTrue("a card put away by hand comes back put away", restored.isArchived());
    }

    @Test
    public void restoringACardThatWasInUseLeavesItInUse() {
        BackupPayload.Card inFile = new BackupPayload.Card();
        inFile.uuid = "a";
        inFile.archivedAt = 0L;

        CardEntity restored = new CardEntity();
        BackupManager.copyPlainFields(inFile, restored);

        assertFalse(restored.isArchived());
    }

    /**
     * The newer record wins this field like any other. A card retired on this phone and then
     * overwritten by a genuinely newer copy that was still in use comes back out of the
     * archive — which is the merge rule working, not the archive being lost.
     */
    @Test
    public void aNewerRecordDecidesWhetherTheCardIsArchived() {
        CardEntity onPhone = card("a", 1_700_000_000_000L);
        onPhone.updatedAt = 2_000L;

        BackupPayload.Card newerInFile = new BackupPayload.Card();
        newerInFile.uuid = "a";
        newerInFile.updatedAt = 9_000L;
        newerInFile.archivedAt = 0L;

        assertEquals(BackupManager.CardAction.UPDATE,
                BackupManager.planFor(newerInFile, onPhone));

        BackupManager.copyPlainFields(newerInFile, onPhone);
        assertFalse(onPhone.isArchived());
    }

    // --- the whole way round ---

    @Test
    public void archivedSurvivesTheJsonAndTheEncryption() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Collections.singletonList(card("a", 1_700_000_000_000L)),
                Collections.emptyList(),
                PLAIN);

        byte[] blob = BackupCodec.encrypt(new Gson().toJson(snapshot.payload), PASS.clone());
        BackupPayload back = BackupManager.parse(blob, PASS.clone());

        CardEntity restored = new CardEntity();
        BackupManager.copyPlainFields(back.cards.get(0), restored);

        assertEquals(1_700_000_000_000L, restored.archivedAt);
        assertTrue(restored.isArchived());
    }

    // --- across versions ---

    /**
     * A file written by 1.2, before the archive existed. It has no {@code archivedAt} at all
     * and still declares {@code backupVersion 1}, because raising the version would have made
     * every backup already on people's phones unreadable to buy nothing.
     *
     * <p>Written out as literal JSON rather than built from a payload object: the point is a
     * file this build did not produce, and a payload with the field left at its default would
     * serialise the field and prove nothing.
     */
    private static final String WRITTEN_BY_1_2 = "{"
            + "\"backupVersion\":1,"
            + "\"exportedAt\":\"2026-01-01T00:00:00Z\","
            + "\"cards\":[{"
            + "\"uuid\":\"a\","
            + "\"cardTypeId\":\"buyme_all\","
            + "\"label\":\"Birthday\","
            + "\"initialAmount\":250.0,"
            + "\"currency\":\"ILS\","
            + "\"createdAt\":1000,"
            + "\"updatedAt\":2000}],"
            + "\"spends\":[]}";

    @Test
    public void aBackupWrittenBeforeTheArchiveExistedStillOpens() throws Exception {
        BackupPayload back = BackupManager.parse(
                BackupCodec.encrypt(WRITTEN_BY_1_2, PASS.clone()), PASS.clone());

        assertEquals(1, back.cards.size());
        assertEquals("Birthday", back.cards.get(0).label);
    }

    @Test
    public void aCardFromBeforeTheArchiveRestoresAsInUse() throws Exception {
        BackupPayload back = BackupManager.parse(
                BackupCodec.encrypt(WRITTEN_BY_1_2, PASS.clone()), PASS.clone());

        CardEntity restored = new CardEntity();
        BackupManager.copyPlainFields(back.cards.get(0), restored);

        assertEquals("a missing field is 0, which is exactly \"in use\"", 0L, restored.archivedAt);
        assertFalse(restored.isArchived());
    }
}
