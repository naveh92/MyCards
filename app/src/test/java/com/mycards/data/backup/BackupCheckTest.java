package com.mycards.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mycards.data.db.CardEntity;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Covers the backup check — the answer to "does my backup actually work?".
 *
 * <p>The question cannot be answered by restoring: onto the phone the file came from every
 * card is already newer, so nothing happens, and anywhere else needs a spare phone. It is
 * answered by measuring the file against the wallet, which is what these pin down. A file
 * that parses is not a working backup; a file that contains every card you hold is.
 */
public class BackupCheckTest {

    private static CardEntity onPhone(String uuid, String label, long updatedAt) {
        CardEntity card = new CardEntity();
        card.uuid = uuid;
        card.label = label;
        card.cardTypeId = "buyme_all";
        card.updatedAt = updatedAt;
        return card;
    }

    private static BackupPayload.Card inFile(String uuid, long updatedAt) {
        BackupPayload.Card card = new BackupPayload.Card();
        card.uuid = uuid;
        card.updatedAt = updatedAt;
        return card;
    }

    private static BackupPayload payloadOf(BackupPayload.Card... cards) {
        BackupPayload payload = new BackupPayload();
        payload.cards.addAll(Arrays.asList(cards));
        return payload;
    }

    @Test
    public void aBackupHoldingEveryCardPasses() {
        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(inFile("a", 100L), inFile("b", 100L)),
                Arrays.asList(onPhone("a", "LOVE", 100L), onPhone("b", "Max", 100L)));

        assertTrue(result.coversEverything());
        assertEquals(2, result.cardsInFile);
        assertEquals(2, result.cardsOnPhone);
        assertTrue(result.missingFromFile.isEmpty());
    }

    /** The failure that matters: the file is fine, it is just older than the wallet. */
    @Test
    public void aCardAddedAfterTheBackupIsReportedAsMissing() {
        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(inFile("a", 100L)),
                Arrays.asList(onPhone("a", "LOVE", 100L), onPhone("b", "Hanukkah gift", 100L)));

        assertFalse("a wallet the file cannot restore must not pass", result.coversEverything());
        assertEquals(1, result.missingFromFile.size());
        assertEquals("Hanukkah gift", result.missingFromFile.get(0));
    }

    /** A card with no label is still identifiable in the report. */
    @Test
    public void anUnlabelledMissingCardIsNamedByItsType() {
        CardEntity unlabelled = onPhone("b", "   ", 100L);
        unlabelled.cardTypeId = "max_gift";

        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(inFile("a", 100L)),
                Arrays.asList(onPhone("a", "LOVE", 100L), unlabelled));

        assertEquals(Collections.singletonList("max_gift"), result.missingFromFile);
    }

    /**
     * The partial export leaves this behind, and a card count alone cannot see it: every card
     * is present, but the ones that matter have been stripped of what makes them usable.
     */
    @Test
    public void aCardBackedUpWithoutItsSecretsFailsTheCheck() {
        CardEntity withPan = onPhone("a", "LOVE", 100L);
        withPan.encPan = "ciphertext";

        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(inFile("a", 100L)), Collections.singletonList(withPan));

        assertFalse(result.coversEverything());
        assertEquals(1, result.secretsMissingFromFile);
        assertTrue("the card itself is present, only its secrets are gone",
                result.missingFromFile.isEmpty());
    }

    @Test
    public void aCardWhoseSecretsWereSavedPasses() {
        CardEntity withPan = onPhone("a", "LOVE", 100L);
        withPan.encPan = "ciphertext";

        BackupPayload.Card backed = inFile("a", 100L);
        backed.pan = "4580458045804580";

        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(backed), Collections.singletonList(withPan));

        assertTrue(result.coversEverything());
        assertEquals(0, result.secretsMissingFromFile);
        assertEquals(1, result.cardsWithSecrets);
    }

    /** A gift link alone is enough to be worth backing up — it is spendable on its own. */
    @Test
    public void aGiftLinkCountsAsSomethingWorthLosing() {
        CardEntity linkOnly = onPhone("a", "LOVE", 100L);
        linkOnly.encGiftUrl = "ciphertext";

        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(inFile("a", 100L)), Collections.singletonList(linkOnly));

        assertFalse(result.coversEverything());
        assertEquals(1, result.secretsMissingFromFile);
    }

    /**
     * A stored card expiry is as much a secret as the number it goes with — saving it needs
     * an unlock and revealing it decrypts it — so a backup that dropped it has lost something.
     * It was being overlooked, which also cost such a card its reveal button entirely.
     */
    @Test
    public void aStoredCardExpiryCountsAsASecret() {
        CardEntity expiryOnly = onPhone("a", "LOVE", 100L);
        expiryOnly.encCardExpiry = "ciphertext";

        assertTrue("every field behind the auth-bound key counts",
                expiryOnly.hasSensitiveData());

        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(inFile("a", 100L)), Collections.singletonList(expiryOnly));

        assertFalse(result.coversEverything());
        assertEquals(1, result.secretsMissingFromFile);
    }

    /**
     * The case a coarser check waved through. A partial export drops whatever the auth-bound
     * key can no longer read and keeps the gift link, which lives under a different key and
     * survives — so asking merely whether the file holds "some secret" for a card returns
     * true for exactly the cards that have lost the most.
     */
    @Test
    public void aCardThatKeptItsGiftLinkButLostItsNumberStillFails() {
        CardEntity full = onPhone("a", "Rosh Hashana", 100L);
        full.encPan = "ciphertext";
        full.encCvv = "ciphertext";
        full.encGiftUrl = "ciphertext";

        BackupPayload.Card partial = inFile("a", 100L);
        partial.giftUrl = "https://buyme.co.il/giftcard/x";
        // pan and cvv could not be read at export time, so they are absent.

        BackupManager.CheckResult result =
                BackupManager.compare(payloadOf(partial), Collections.singletonList(full));

        assertFalse("the card number is gone; holding the gift link does not make up for it",
                result.coversEverything());
        assertEquals(1, result.secretsMissingFromFile);
    }

    @Test
    public void everySecretPresentInBothPasses() {
        CardEntity full = onPhone("a", "Rosh Hashana", 100L);
        full.encPan = "ciphertext";
        full.encCvv = "ciphertext";
        full.encCardExpiry = "ciphertext";
        full.encGiftUrl = "ciphertext";

        BackupPayload.Card backed = inFile("a", 100L);
        backed.pan = "4580458045804580";
        backed.cvv = "123";
        backed.cardExpiry = "2027-03";
        backed.giftUrl = "https://buyme.co.il/giftcard/x";

        BackupManager.CheckResult result =
                BackupManager.compare(payloadOf(backed), Collections.singletonList(full));

        assertTrue(result.coversEverything());
        assertEquals(0, result.secretsMissingFromFile);
    }

    @Test
    public void aCardHoldingNothingSensitiveIsNotFlagged() {
        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(inFile("a", 100L)),
                Collections.singletonList(onPhone("a", "LOVE", 100L)));

        assertTrue(result.coversEverything());
        assertEquals(0, result.secretsMissingFromFile);
    }

    @Test
    public void editsMadeSinceTheBackupAreReportedWithoutFailingIt() {
        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(inFile("a", 100L)),
                Collections.singletonList(onPhone("a", "LOVE", 500L)));

        assertEquals(1, result.olderInFile);
        // Stale details are worth knowing about but the card would still come back, so this
        // is reported rather than treated as a failure.
        assertTrue(result.coversEverything());
    }

    @Test
    public void aBackupOfAWalletThatIsNowEmptyStillReadsCleanly() {
        BackupManager.CheckResult result = BackupManager.compare(
                payloadOf(inFile("a", 100L)), Collections.<CardEntity>emptyList());

        assertEquals(0, result.cardsOnPhone);
        assertEquals(1, result.cardsInFile);
        assertTrue("nothing on the phone is missing from the file", result.coversEverything());
    }

    @Test
    public void theCheckReadsTheFilesOwnExportDate() {
        BackupPayload payload = payloadOf(inFile("a", 100L));
        payload.exportedAt = "2026-08-01T09:00:00Z";
        payload.spends.add(new BackupPayload.Spend());

        BackupManager.CheckResult result =
                BackupManager.compare(payload, Collections.singletonList(onPhone("a", "LOVE", 100L)));

        assertEquals("2026-08-01T09:00:00Z", result.exportedAt);
        assertEquals(1, result.spendsInFile);
    }
}
