package com.mycards.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SpendDao {

    @Insert
    long insert(SpendEntity spend);

    @androidx.room.Update
    void update(SpendEntity spend);

    @Delete
    void delete(SpendEntity spend);

    @Query("SELECT * FROM spends WHERE cardId = :cardId ORDER BY spentAt DESC")
    LiveData<List<SpendEntity>> observeForCard(long cardId);

    @Query("SELECT * FROM spends WHERE cardId = :cardId ORDER BY spentAt DESC")
    List<SpendEntity> getForCard(long cardId);

    @Query("SELECT * FROM spends")
    List<SpendEntity> getAll();

    /**
     * Every purchase across every card, newest first — the whole spending history.
     *
     * <p>Separate from {@link #getAll()} rather than replacing it: that one feeds the backup,
     * where the order carries no meaning and imposing one would only cost a sort.
     *
     * <p>The tiebreak on {@code id} matters more than it looks. Several purchases logged on
     * the same day share a {@code spentAt} to the millisecond only by accident, but a date
     * picked from the calendar is stored at midnight — so a day's worth of entries genuinely
     * do collide, and without a second key SQLite is free to return them in any order it
     * likes, differently each time the screen is opened.
     */
    @Query("SELECT * FROM spends ORDER BY spentAt DESC, id DESC")
    List<SpendEntity> getAllNewestFirst();

    /** Lookup by the stable cross-device identity, used when merging a backup. */
    @Query("SELECT * FROM spends WHERE uuid = :uuid LIMIT 1")
    SpendEntity getByUuid(String uuid);

    /** COALESCE keeps a card with no spends at 0 rather than null. */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM spends WHERE cardId = :cardId")
    double getTotalSpent(long cardId);

    @Query("SELECT cardId, COALESCE(SUM(amount), 0) AS total FROM spends GROUP BY cardId")
    LiveData<List<SpendTotal>> observeTotals();

    @Query("SELECT cardId, COALESCE(SUM(amount), 0) AS total FROM spends GROUP BY cardId")
    List<SpendTotal> getTotals();

    /**
     * When each card was last spent on.
     *
     * <p>Stands in for "when did this card become empty", which nothing records: a card runs
     * out at the moment of the purchase that finished it, and that purchase is the newest one
     * against it. Wrong only for a card that was emptied and then had a later purchase
     * deleted, which leaves it active again and out of the archive anyway.
     */
    @Query("SELECT cardId, MAX(spentAt) AS lastSpentAt FROM spends GROUP BY cardId")
    List<LastSpend> getLastSpendTimes();

    /** Projection of a card's total spend, used to compute remaining balances in bulk. */
    class SpendTotal {
        public long cardId;
        public double total;
    }

    /** Projection of a card's most recent purchase; see {@link #getLastSpendTimes()}. */
    class LastSpend {
        public long cardId;
        public long lastSpentAt;
    }
}
