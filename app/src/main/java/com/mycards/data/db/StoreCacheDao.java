package com.mycards.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface StoreCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(StoreCacheEntity entry);

    @Query("SELECT * FROM store_cache")
    List<StoreCacheEntity> getAll();

    @Query("SELECT * FROM store_cache WHERE cardTypeId = :cardTypeId")
    StoreCacheEntity getByCardType(String cardTypeId);

    /** Oldest successful fetch across all cached types; 0 when the cache is empty. */
    @Query("SELECT COALESCE(MIN(fetchedAt), 0) FROM store_cache")
    long getOldestFetchedAt();

    @Query("DELETE FROM store_cache WHERE cardTypeId = :cardTypeId")
    void deleteByCardType(String cardTypeId);
}
