package com.mycards.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface CardDao {

    @Insert
    long insert(CardEntity card);

    @Update
    void update(CardEntity card);

    @Delete
    void delete(CardEntity card);

    @Query("SELECT * FROM cards ORDER BY createdAt DESC")
    LiveData<List<CardEntity>> observeAll();

    @Query("SELECT * FROM cards ORDER BY createdAt DESC")
    List<CardEntity> getAll();

    @Query("SELECT * FROM cards WHERE id = :id")
    CardEntity getById(long id);

    /** Lookup by the stable cross-device identity, used when merging a backup. */
    @Query("SELECT * FROM cards WHERE uuid = :uuid LIMIT 1")
    CardEntity getByUuid(String uuid);

    @Query("SELECT * FROM cards WHERE id = :id")
    LiveData<CardEntity> observeById(long id);

    /** Cards eligible for an unattended balance check. */
    @Query("SELECT * FROM cards WHERE enc_gift_url IS NOT NULL")
    List<CardEntity> getCardsWithGiftUrl();

    @Query("SELECT DISTINCT cardTypeId FROM cards")
    List<String> getUsedCardTypeIds();

    @Query("UPDATE cards SET lastBalanceCheckAt = :checkedAt, lastFetchedBalance = :balance, "
            + "hasUnreconciledMismatch = :mismatch WHERE id = :cardId")
    void recordBalanceCheck(long cardId, long checkedAt, Double balance, boolean mismatch);

    @Query("UPDATE cards SET hasUnreconciledMismatch = 0 WHERE id = :cardId")
    void clearMismatch(long cardId);
}
