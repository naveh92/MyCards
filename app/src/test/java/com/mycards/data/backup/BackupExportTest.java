package com.mycards.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.mycards.data.crypto.SecretVault;
import com.mycards.data.db.CardEntity;
import com.mycards.data.db.SpendEntity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Covers what an export does when the Keystore will not give a secret back.
 *
 * <p>Written after a real loss: an export produced a zero-byte file, which was only
 * discovered when the data it should have held was gone. The rules being pinned here are
 * that a secret nobody can ever read again costs its card's secrets and nothing more, while
 * one that a fresh unlock <em>would</em> read must stop the export outright rather than
 * silently write a file with every card number missing from it.
 */
public class BackupExportTest {

    /** Returns whatever it is given, minus a marker prefix — enough to prove routing. */
    private static class PlainReader implements BackupManager.SecretReader {
        @Override
        public String secret(String stored) {
            return stored == null ? null : "clear:" + stored;
        }

        @Override
        public String data(String stored) {
            return stored == null ? null : "clear:" + stored;
        }
    }

    private static CardEntity card(String uuid, String pan) {
        CardEntity card = new CardEntity();
        card.uuid = uuid;
        card.cardTypeId = "buyme_all";
        card.label = "Birthday";
        card.initialAmount = 250.0;
        card.currency = "ILS";
        card.encPan = pan;
        card.encCvv = pan == null ? null : "cvv-" + pan;
        card.encCardExpiry = pan == null ? null : "exp-" + pan;
        card.encGiftUrl = pan == null ? null : "url-" + pan;
        return card;
    }

    private static SpendEntity spend(String uuid, String cardUuid, double amount) {
        SpendEntity spend = new SpendEntity();
        spend.uuid = uuid;
        spend.cardUuid = cardUuid;
        spend.title = "Coffee";
        spend.amount = amount;
        return spend;
    }

    @Test
    public void carriesEveryCardAndPurchaseThrough() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Arrays.asList(card("a", "1111"), card("b", "2222")),
                Collections.singletonList(spend("s1", "a", 30.0)),
                new PlainReader());

        assertEquals(2, snapshot.payload.cards.size());
        assertEquals(1, snapshot.payload.spends.size());
        assertEquals(0, snapshot.cardsMissingSecrets);
        assertEquals("clear:1111", snapshot.payload.cards.get(0).pan);
        assertEquals("clear:url-2222", snapshot.payload.cards.get(1).giftUrl);
        assertNotNull(snapshot.payload.exportedAt);
    }

    @Test
    public void aCardWithNoSecretsIsNotCountedAsAnOmission() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Collections.singletonList(card("a", null)),
                Collections.<SpendEntity>emptyList(),
                new PlainReader());

        assertEquals(1, snapshot.payload.cards.size());
        assertEquals(0, snapshot.cardsMissingSecrets);
        assertNull(snapshot.payload.cards.get(0).pan);
    }

    /**
     * Changing the screen lock destroys the auth-bound key for good. Everything else about
     * the card is still readable, and dropping the whole export would throw it all away.
     */
    @Test
    public void anUnreadableSecretCostsThatCardsSecretsAndNothingElse() throws Exception {
        BackupManager.SecretReader keyGone = new BackupManager.SecretReader() {
            @Override
            public String secret(String stored) throws SecretVault.VaultException {
                if (stored == null) {
                    return null;
                }
                throw new SecretVault.VaultException("key permanently invalidated", null);
            }

            @Override
            public String data(String stored) throws SecretVault.VaultException {
                if (stored == null) {
                    return null;
                }
                throw new SecretVault.VaultException("key permanently invalidated", null);
            }
        };

        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Arrays.asList(card("a", "1111"), card("b", null)),
                Collections.singletonList(spend("s1", "a", 30.0)),
                keyGone);

        // The wallet still round-trips: labels, amounts, expiries and the spend log.
        assertEquals(2, snapshot.payload.cards.size());
        assertEquals(1, snapshot.payload.spends.size());
        assertEquals("Birthday", snapshot.payload.cards.get(0).label);
        assertEquals(250.0, snapshot.payload.cards.get(0).initialAmount, 0.0001);

        // Only the unreadable card is reported, and only its secrets are absent.
        assertEquals(1, snapshot.cardsMissingSecrets);
        assertNull(snapshot.payload.cards.get(0).pan);
        assertNull(snapshot.payload.cards.get(0).cvv);
        assertNull(snapshot.payload.cards.get(0).cardExpiry);
        assertNull(snapshot.payload.cards.get(0).giftUrl);
    }

    /**
     * The opposite case, and the one that actually bit: a stale unlock is fixable by asking
     * again. Swallowing it would write a file that looks complete and holds no card numbers.
     */
    @Test
    public void aStaleUnlockStopsTheExportInsteadOfDroppingSecrets() {
        BackupManager.SecretReader needsUnlock = new BackupManager.SecretReader() {
            @Override
            public String secret(String stored) throws SecretVault.AuthRequiredException {
                throw new SecretVault.AuthRequiredException(null);
            }

            @Override
            public String data(String stored) {
                return stored;
            }
        };

        try {
            BackupManager.snapshot(
                    Collections.singletonList(card("a", "1111")),
                    Collections.<SpendEntity>emptyList(),
                    needsUnlock);
            fail("a stale unlock must abort the export, not quietly omit the card numbers");
        } catch (SecretVault.AuthRequiredException expected) {
            // The caller re-prompts and retries.
        }
    }

    /** Whatever else changes, an encrypted backup can never be an empty file. */
    @Test
    public void anEmptyWalletStillProducesRealBytes() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                new ArrayList<CardEntity>(),
                new ArrayList<SpendEntity>(),
                new PlainReader());

        byte[] blob = BackupCodec.encrypt(
                "{\"backupVersion\":1,\"cards\":[],\"spends\":[]}", "passphrase1".toCharArray());

        assertEquals(0, snapshot.payload.cards.size());
        assertTrue("a backup is never zero bytes; that only ever means it was not written",
                blob.length > 40);
    }

    /** A restore must survive a backup whose secrets were omitted by the export above. */
    @Test
    public void aBackupMissingItsSecretsIsStillReadable() throws Exception {
        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Collections.singletonList(card("a", null)),
                Collections.singletonList(spend("s1", "a", 30.0)),
                new PlainReader());

        char[] passphrase = "passphrase1".toCharArray();
        String json = new com.google.gson.Gson().toJson(snapshot.payload);
        byte[] blob = BackupCodec.encrypt(json, passphrase.clone());

        BackupPayload back = new com.google.gson.Gson()
                .fromJson(BackupCodec.decrypt(blob, passphrase), BackupPayload.class);

        assertTrue(back.isUsable(1));
        assertEquals(1, back.cards.size());
        assertEquals("a", back.cards.get(0).uuid);
        assertNull(back.cards.get(0).pan);
        assertEquals(1, back.spends.size());
    }

    @Test
    public void purchasesKeepTheirLinkToTheirCard() throws Exception {
        List<SpendEntity> spends = Arrays.asList(
                spend("s1", "a", 30.0), spend("s2", "a", 12.5), spend("s3", "b", 8.0));

        BackupManager.Snapshot snapshot = BackupManager.snapshot(
                Arrays.asList(card("a", "1111"), card("b", "2222")), spends, new PlainReader());

        assertEquals(3, snapshot.payload.spends.size());
        assertEquals("a", snapshot.payload.spends.get(0).cardUuid);
        assertEquals("b", snapshot.payload.spends.get(2).cardUuid);
        assertEquals(12.5, snapshot.payload.spends.get(1).amount, 0.0001);
    }
}
