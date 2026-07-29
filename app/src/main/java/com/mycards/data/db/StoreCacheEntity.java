package com.mycards.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * The last successfully fetched merchant list for a card type.
 *
 * <p>Cached per <em>type</em> rather than per card, and never cleared on a failed refresh:
 * yesterday's list is far more useful at a checkout counter than no list at all.
 */
@Entity(tableName = "store_cache")
public class StoreCacheEntity {

    @PrimaryKey
    @NonNull
    public String cardTypeId = "";

    /** Merchants in the compact snapshot format. */
    public String storesJson;

    /** Epoch millis of the fetch that produced this data. */
    public long fetchedAt;

    /** Which provider supplied it, surfaced in the UI as provenance. */
    public String sourceType;

    public int storeCount;

    /**
     * The published sha256 this cache was built from, when it came from the hosted list.
     *
     * <p>Compared against the manifest on each sync so an unchanged list is skipped
     * without downloading it. Null when the data came from the issuer endpoint or a
     * bundled asset, neither of which the manifest describes.
     */
    public String sourceHash;
}
