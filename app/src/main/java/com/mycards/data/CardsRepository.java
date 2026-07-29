package com.mycards.data;

import android.content.Context;

import com.mycards.data.crypto.SecretVault;
import com.mycards.data.db.AppDatabase;
import com.mycards.data.db.CardDao;
import com.mycards.data.db.CardEntity;
import com.mycards.data.db.SpendDao;
import com.mycards.data.db.SpendEntity;

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
}
