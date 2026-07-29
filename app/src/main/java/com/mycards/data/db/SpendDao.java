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

    @Delete
    void delete(SpendEntity spend);

    @Query("SELECT * FROM spends WHERE cardId = :cardId ORDER BY spentAt DESC")
    LiveData<List<SpendEntity>> observeForCard(long cardId);

    @Query("SELECT * FROM spends WHERE cardId = :cardId ORDER BY spentAt DESC")
    List<SpendEntity> getForCard(long cardId);

    @Query("SELECT * FROM spends")
    List<SpendEntity> getAll();

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

    /** Projection of a card's total spend, used to compute remaining balances in bulk. */
    class SpendTotal {
        public long cardId;
        public double total;
    }
}
