package com.mycards.data;

import android.content.Context;
import android.util.Log;

import com.mycards.cards.GiftLink;
import com.mycards.data.crypto.SecretVault;
import com.mycards.data.db.AppDatabase;
import com.mycards.data.db.CardDao;
import com.mycards.data.db.CardEntity;
import com.mycards.data.db.SpendDao;
import com.mycards.data.db.SpendEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cards and their spending history.
 *
 * <p>Balances are always derived — {@code initialAmount - sum(spends)} — never stored as a
 * mutable running total. A stored total drifts the moment an entry is edited or deleted;
 * deriving it means the log and the balance can never disagree.
 */
public class CardsRepository {

    private static final String TAG = "CardsRepository";

    private final CardDao cardDao;
    private final SpendDao spendDao;
    private final SecretVault vault;

    public CardsRepository(Context context) {
        AppDatabase db = AppDatabase.get(context);
        this.cardDao = db.cardDao();
        this.spendDao = db.spendDao();
        this.vault = new SecretVault(context);
    }

    public SecretVault vault() {
        return vault;
    }

    /**
     * The cards already holding this gift link, so a duplicate can be spotted before it is
     * saved.
     *
     * @param exceptId the card being edited, excluded so it does not report itself; 0 when
     *                 adding a new one
     * @return the matching cards, newest id last; empty when the link is new or absent
     */
    public List<CardEntity> cardsSharingGiftLink(String giftUrl, long exceptId) {
        String fingerprint = GiftLink.fingerprint(giftUrl);
        if (fingerprint == null) {
            // No link is not a match with every other card that also has no link.
            return Collections.emptyList();
        }
        return cardDao.findByGiftFingerprint(fingerprint, exceptId);
    }

    /**
     * Fills in fingerprints for cards added before the column existed.
     *
     * <p>The migration could not do this: the value is a hash of the decrypted link, and a
     * migration has no vault. Here there is one — and gift links are held under the non-auth
     * key precisely so they can be read with nobody present, which is what makes an
     * unattended pass possible at all.
     *
     * <p>A card whose link will not decrypt is skipped rather than retried for ever. It
     * keeps a null fingerprint, which costs only the duplicate warning for that one card.
     */
    public void backfillGiftFingerprints() {
        for (CardEntity card : cardDao.getCardsMissingGiftFingerprint()) {
            try {
                String fingerprint = GiftLink.fingerprint(vault.decryptData(card.encGiftUrl));
                if (fingerprint != null) {
                    cardDao.setGiftFingerprint(card.id, fingerprint);
                }
            } catch (Exception e) {
                Log.w(TAG, "could not fingerprint the gift link for card " + card.id, e);
            }
        }
    }

    public CardDao cards() {
        return cardDao;
    }

    public SpendDao spends() {
        return spendDao;
    }

    public double remainingBalance(long cardId) {
        CardEntity card = cardDao.getById(cardId);
        if (card == null) {
            return 0d;
        }
        return card.initialAmount - spendDao.getTotalSpent(cardId);
    }

    /** Remaining balance for every card in one pass, for the search list. */
    public Map<Long, Double> remainingBalances() {
        Map<Long, Double> spentByCard = new HashMap<>();
        for (SpendDao.SpendTotal t : spendDao.getTotals()) {
            spentByCard.put(t.cardId, t.total);
        }

        Map<Long, Double> remaining = new HashMap<>();
        for (CardEntity card : cardDao.getAll()) {
            Double spent = spentByCard.get(card.id);
            remaining.put(card.id, card.initialAmount - (spent == null ? 0d : spent));
        }
        return remaining;
    }

    public long addSpend(long cardId, String title, double amount, String storeName,
                         long spentAt, String source) {
        SpendEntity spend = new SpendEntity();
        spend.cardId = cardId;
        // Recorded so the entry survives an export/import onto a different device, where
        // the numeric card id will be different.
        CardEntity owner = cardDao.getById(cardId);
        spend.cardUuid = owner == null ? null : owner.uuid;
        spend.title = title;
        spend.amount = amount;
        spend.storeName = storeName;
        spend.spentAt = spentAt;
        spend.source = source;
        spend.createdAt = System.currentTimeMillis();
        long id = spendDao.insert(spend);

        // Logging the missing purchase is what resolves a reported mismatch.
        if (SpendEntity.SOURCE_RECONCILIATION.equals(source)) {
            cardDao.clearMismatch(cardId);
        }
        return id;
    }

    public List<CardEntity> allCards() {
        return cardDao.getAll();
    }

    /**
     * The date of each card's most recent purchase, for ordering the archive.
     *
     * <p>A card with no purchases against it is simply absent from the map rather than
     * present with a zero, so a caller can tell "never spent on" from "spent on at the epoch".
     */
    public Map<Long, Long> lastSpendTimes() {
        Map<Long, Long> out = new HashMap<>();
        for (SpendDao.LastSpend row : spendDao.getLastSpendTimes()) {
            out.put(row.cardId, row.lastSpentAt);
        }
        return out;
    }
}
