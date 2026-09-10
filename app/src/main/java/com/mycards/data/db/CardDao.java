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

    /**
     * Cards already holding this gift link.
     *
     * <p>A list rather than a single row: duplicates are warned about, not blocked, so the
     * wallet can legitimately end up with more than one. Excludes the card being edited, or
     * saving an unchanged card would report it as its own duplicate. Pass 0 when adding.
     */
    @Query("SELECT * FROM cards WHERE giftUrlFingerprint = :fingerprint AND id != :exceptId")
    List<CardEntity> findByGiftFingerprint(String fingerprint, long exceptId);

    /**
     * Cards that have a link but no fingerprint for it yet — everything added before the
     * column existed. Filled in by CardsRepository#backfillGiftFingerprints.
     */
    @Query("SELECT * FROM cards WHERE enc_gift_url IS NOT NULL AND giftUrlFingerprint IS NULL")
    List<CardEntity> getCardsMissingGiftFingerprint();

    @Query("UPDATE cards SET giftUrlFingerprint = :fingerprint WHERE id = :cardId")
    void setGiftFingerprint(long cardId, String fingerprint);

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

    /**
     * Puts a card away by hand, or brings it back — {@code archivedAt} of 0 means in use.
     *
     * <p>{@code updatedAt} moves with it. A backup merge picks the newer of two copies by
     * that field, so archiving without touching it would let a restore from an older file
     * silently un-archive the card.
     */
    @Query("UPDATE cards SET archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :cardId")
    void setArchived(long cardId, long archivedAt, long updatedAt);
}
