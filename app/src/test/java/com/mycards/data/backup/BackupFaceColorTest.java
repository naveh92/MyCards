package com.mycards.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.mycards.data.db.CardEntity;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Pins down that a colour someone chose by hand survives a backup, both ways.
 *
 * <p>It is the same argument as {@link BackupArchiveTest} makes for the archive: a card's
 * colour is either derived from its type — in which case there is nothing to lose — or it is
 * a decision that exists nowhere else on earth but this column. A restore that dropped it
 * would hand back a wallet where every hand-coloured card had quietly reverted, with nothing
 * left to recover the choice from.
 *
 * <p>The round trip here goes through the real file: encrypted, written as JSON, read back
 * and decrypted. Testing {@code snapshot} and {@code copyPlainFields} against each other
 * would prove the two halves agree while saying nothing about whether the field survives Gson
 * — and a boxed {@code Integer} that no serialiser was told about is exactly the sort of
 * thing that goes missing in between.
 */
public class BackupFaceColorTest {

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

    /** face_indigo_start, opaque — and above 0x7FFFFFFF, so it is a negative int. */
    private static final int INDIGO = 0xFF3B5BC0;

    private static CardEntity card(String uuid, Integer faceColor) {
        CardEntity card = new CardEntity();
        card.uuid = uuid;
        card.cardTypeId = "buyme_all";
        card.label = "Birthday";
        card.initialAmount = 250.0;
        card.currency = "ILS";
        card.createdAt = 1_000L;
        card.updatedAt = 2_000L;
        card.faceColor = faceColor;
        return card;
    }

    // --- out ---

    @Test
    public void aChosenColourIsExported() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Collections.singletonList(card("a", INDIGO)),
                Collections.emptyList(),
                PLAIN);

        assertEquals(Integer.valueOf(INDIGO), snapshot.payload.cards.get(0).faceColor);
    }

    /** No choice is itself the state to carry: the card takes its type's colour. */
    @Test
    public void aCardWithNoChosenColourExportsNone() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Collections.singletonList(card("a", null)),
                Collections.emptyList(),
                PLAIN);

        assertNull(snapshot.payload.cards.get(0).faceColor);
    }

    // --- back in ---

    @Test
    public void restoringACardBringsItsColourBack() {
        BackupPayload.Card inFile = new BackupPayload.Card();
        inFile.uuid = "a";
        inFile.faceColor = INDIGO;

        CardEntity restored = new CardEntity();
        BackupManager.copyPlainFields(inFile, restored);

        assertEquals(Integer.valueOf(INDIGO), restored.faceColor);
    }

    /**
     * A file written before this field existed has no such key, so Gson leaves it null and
     * the card restores taking its colour from its type — which is what it was doing when
     * the file was written. The same path a card with no choice takes today.
     */
    @Test
    public void aCardWithNoColourInTheFileRestoresWithNone() {
        BackupPayload.Card inFile = new BackupPayload.Card();
        inFile.uuid = "a";

        CardEntity restored = new CardEntity();
        restored.faceColor = INDIGO;
        BackupManager.copyPlainFields(inFile, restored);

        assertNull("the file is what a restore applies, not what was here before",
                restored.faceColor);
    }

    // --- the whole way round ---

    /**
     * Export, encrypt, decrypt, restore. The test that would actually have caught the field
     * being left out of the payload class.
     */
    @Test
    public void aColourSurvivesTheRealFile() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Arrays.asList(card("coloured", INDIGO), card("automatic", null)),
                Collections.emptyList(),
                PLAIN);

        byte[] file = BackupCodec.encrypt(new com.google.gson.Gson().toJson(snapshot.payload),
                PASS);
        BackupPayload readBack = new com.google.gson.Gson().fromJson(
                BackupCodec.decrypt(file, PASS), BackupPayload.class);

        assertNotNull(readBack);
        assertEquals(2, readBack.cards.size());

        CardEntity coloured = new CardEntity();
        BackupManager.copyPlainFields(readBack.cards.get(0), coloured);
        assertEquals(Integer.valueOf(INDIGO), coloured.faceColor);

        CardEntity automatic = new CardEntity();
        BackupManager.copyPlainFields(readBack.cards.get(1), automatic);
        assertNull(automatic.faceColor);
    }
}
