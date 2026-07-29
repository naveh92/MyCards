package com.mycards.data;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.catalog.model.SyncManifest;
import com.mycards.data.db.AppDatabase;
import com.mycards.data.db.StoreCacheDao;
import com.mycards.data.db.StoreCacheEntity;
import com.mycards.data.source.FetchOutcome;
import com.mycards.data.source.Http;
import com.mycards.data.source.ProviderRegistry;
import com.mycards.data.source.SourceEnv;
import com.mycards.data.source.StoreFetcher;
import com.mycards.data.source.StoreListJson;
import com.mycards.data.source.StoreListWriter;
import com.mycards.search.CardTypeIndex;
import com.mycards.search.Store;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Owns the card catalog and the merchant lists derived from it.
 *
 * <p>Reading and refreshing are deliberately separate. Reads always succeed by falling back
 * through cache to the bundled snapshot, so the search screen is never empty; refreshes are
 * best-effort and never destroy known-good data when they fail.
 */
public class CatalogRepository {

    private static final String TAG = "CatalogRepository";

    /** Catalog schema this build understands; a higher one is ignored. */
    private static final int MAX_SCHEMA_VERSION = 1;

    private static final String CACHED_CATALOG_FILE = "catalog-cache.json";

    private final Context appContext;
    private final AppDatabase db;
    private final StoreFetcher fetcher;
    private final SourceEnv env;
    private final Gson gson = new Gson();

    public CatalogRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.db = AppDatabase.get(appContext);
        this.fetcher = new StoreFetcher(new ProviderRegistry());
        this.env = new SourceEnv(
                Http.client(),
                new AndroidAssetLoader(appContext),
                RemoteConfig.CATALOG_BASE_URL);
    }

    // --- reading ---

    /**
     * Loads the best catalog available: the cached remote copy if it parses, otherwise the
     * one bundled in the APK. Never returns null — an unusable catalog would leave the user
     * with no card types to choose from at all.
     */
    public Catalog loadCatalog() {
        File cached = new File(appContext.getFilesDir(), CACHED_CATALOG_FILE);
        if (cached.exists()) {
            try (InputStream in = new java.io.FileInputStream(cached)) {
                Catalog c = parseCatalog(in);
                if (c != null && c.isUsable(MAX_SCHEMA_VERSION)) {
                    return c;
                }
                Log.w(TAG, "cached catalog unusable, falling back to the bundled copy");
            } catch (Exception e) {
                Log.w(TAG, "could not read the cached catalog", e);
            }
        }

        try (InputStream in = appContext.getAssets().open(RemoteConfig.BUNDLED_CATALOG_ASSET)) {
            Catalog c = parseCatalog(in);
            if (c != null && c.isUsable(MAX_SCHEMA_VERSION)) {
                return c;
            }
        } catch (Exception e) {
            Log.e(TAG, "the bundled catalog could not be read", e);
        }

        Catalog empty = new Catalog();
        empty.cardTypes = new ArrayList<>();
        return empty;
    }

    private Catalog parseCatalog(InputStream in) {
        return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Catalog.class);
    }

    /**
     * Builds the searchable index for the given card types.
     *
     * <p>Merchants come from the local cache. A type with no cached list yields an index
     * with zero stores rather than being dropped, so the card still appears in the wallet
     * and the UI can explain that its store list is unavailable.
     */
    public List<CardTypeIndex> buildIndexes(Catalog catalog, List<String> cardTypeIds, String languageTag) {
        StoreCacheDao dao = db.storeCacheDao();
        List<CardTypeIndex> indexes = new ArrayList<>();

        for (String id : cardTypeIds) {
            CardTypeDef def = catalog.findById(id);
            if (def == null) {
                continue;
            }

            List<Store> stores = Collections.emptyList();
            long fetchedAt = 0L;
            String source = null;

            StoreCacheEntity cache = dao.getByCardType(id);
            if (cache != null && cache.storesJson != null) {
                try (InputStream in = new ByteArrayInputStream(
                        cache.storesJson.getBytes(StandardCharsets.UTF_8))) {
                    stores = StoreListJson.parseCompactList(in);
                    fetchedAt = cache.fetchedAt;
                    source = cache.sourceType;
                } catch (Exception e) {
                    Log.w(TAG, "corrupt store cache for " + id + "; treating as empty", e);
                }
            }

            indexes.add(new CardTypeIndex(
                    id,
                    def.displayName(languageTag),
                    def.properNames(),
                    def.aliasesOrEmpty(),
                    stores,
                    fetchedAt,
                    source));
        }
        return indexes;
    }

    // --- refreshing ---

    /**
     * Pulls a newer catalog document, if one is published and parses cleanly.
     *
     * <p>A bad download is discarded rather than written over the working copy: a truncated
     * or malformed catalog would take every card type down with it.
     */
    public boolean refreshCatalog() {
        if (!RemoteConfig.isCatalogUrlConfigured()) {
            Log.i(TAG, "no catalog URL configured; using the bundled catalog only");
            return false;
        }
        try {
            Request request = new Request.Builder().url(RemoteConfig.CATALOG_URL).get().build();
            try (Response response = Http.client().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "catalog fetch returned HTTP " + response.code());
                    return false;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    return false;
                }
                String json = body.string();

                Catalog parsed = gson.fromJson(json, Catalog.class);
                if (parsed == null || !parsed.isUsable(MAX_SCHEMA_VERSION)) {
                    Log.w(TAG, "downloaded catalog rejected (schema or empty card list)");
                    return false;
                }

                File out = new File(appContext.getFilesDir(), CACHED_CATALOG_FILE);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                }
                Log.i(TAG, "catalog updated to version " + parsed.catalogVersion);
                return true;
            }
        } catch (IOException e) {
            Log.w(TAG, "catalog refresh failed", e);
            return false;
        }
    }

    /**
     * Fetches the published hash index.
     *
     * @return the manifest, or null when it is unavailable — in which case the sync simply
     *         falls back to refreshing everything, which is slower but still correct
     */
    public SyncManifest fetchManifest() {
        if (!RemoteConfig.isCatalogUrlConfigured()) {
            return null;
        }
        try {
            Request request = new Request.Builder().url(RemoteConfig.MANIFEST_URL).get().build();
            try (Response response = Http.client().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                SyncManifest manifest = gson.fromJson(response.body().string(), SyncManifest.class);
                if (manifest == null || !manifest.isUsable(RemoteConfig.MAX_MANIFEST_VERSION)) {
                    return null;
                }
                return manifest;
            }
        } catch (Exception e) {
            Log.w(TAG, "manifest fetch failed; refreshing without it", e);
            return null;
        }
    }

    public int refreshStores(Catalog catalog, List<String> cardTypeIds) {
        return refreshStores(catalog, cardTypeIds, null);
    }

    /**
     * Refreshes merchant lists for the given card types.
     *
     * @param manifest published hashes, or null to refresh unconditionally
     * @return how many types were actually re-downloaded
     */
    public int refreshStores(Catalog catalog, List<String> cardTypeIds, SyncManifest manifest) {
        int updated = 0;
        for (String id : cardTypeIds) {
            CardTypeDef def = catalog.findById(id);
            if (def == null) {
                continue;
            }

            StoreCacheEntity cached = db.storeCacheDao().getByCardType(id);

            String publishedHash = manifest == null ? null : manifest.hashFor(id);
            if (publishedHash != null
                    && cached != null
                    && publishedHash.equals(cached.sourceHash)) {
                // Nothing republished since this cache was built, so downloading it again
                // would transfer hundreds of kilobytes to reach the same result.
                Log.i(TAG, "skipping " + id + "; published list unchanged");
                continue;
            }

            // Whichever is larger: what the manifest says should be there, or what we
            // already hold. A source returning far less than that is treated as damaged
            // and the chain moves on to the bundled snapshot, then the issuer.
            int expectedCount = Math.max(
                    manifest == null ? 0 : manifest.countFor(id),
                    cached == null ? 0 : cached.storeCount);

            FetchOutcome outcome = fetcher.fetch(def, env, expectedCount);
            if (!outcome.isSuccess()) {
                // Every source failed. The previous cache stays exactly as it was —
                // stale merchants beat none when someone is waiting at a till.
                Log.w(TAG, "keeping the existing cache for " + id + "; " + outcome.getFailures());
                continue;
            }

            StoreCacheEntity entry = new StoreCacheEntity();
            entry.cardTypeId = id;
            entry.storesJson = StoreListWriter.toCompactJson(
                    id, outcome.getSucceedingSourceType(), outcome.getStores());
            entry.fetchedAt = System.currentTimeMillis();
            entry.sourceType = outcome.getSucceedingSourceType();
            entry.storeCount = outcome.getStores().size();
            // Only record the hash when the data really came from the published list;
            // tagging an issuer or bundled fetch with it would wrongly suppress the next
            // sync, leaving the phone on stale data indefinitely.
            entry.sourceHash = "static_list".equals(outcome.getSucceedingSourceType())
                    ? publishedHash
                    : null;
            db.storeCacheDao().upsert(entry);
            updated++;
        }
        return updated;
    }

    /**
     * Seeds the cache from bundled assets for any type that has none yet, so the very first
     * launch can search offline before a sync has ever run.
     */
    public void seedCacheIfEmpty(Catalog catalog, List<String> cardTypeIds) {
        for (String id : cardTypeIds) {
            if (db.storeCacheDao().getByCardType(id) != null) {
                continue;
            }
            CardTypeDef def = catalog.findById(id);
            if (def == null) {
                continue;
            }
            for (com.mycards.data.catalog.model.SourceDef source : def.storeSourcesOrEmpty()) {
                if (!"bundled_asset".equals(source.type) || source.asset == null) {
                    continue;
                }
                try (InputStream in = appContext.getAssets().open(source.asset)) {
                    List<Store> stores = StoreListJson.parseCompactList(in);
                    if (stores.isEmpty()) {
                        continue;
                    }
                    StoreCacheEntity entry = new StoreCacheEntity();
                    entry.cardTypeId = id;
                    entry.storesJson = StoreListWriter.toCompactJson(id, "bundled_asset", stores);
                    entry.fetchedAt = 0L; // 0 marks "shipped with the app", not "fetched".
                    entry.sourceType = "bundled_asset";
                    entry.storeCount = stores.size();
                    db.storeCacheDao().upsert(entry);
                    Log.i(TAG, "seeded " + stores.size() + " stores for " + id);
                } catch (Exception e) {
                    Log.w(TAG, "could not seed " + id + " from " + source.asset, e);
                }
                break;
            }
        }
    }

    public boolean isStale() {
        long oldest = db.storeCacheDao().getOldestFetchedAt();
        return oldest == 0L
                || System.currentTimeMillis() - oldest > RemoteConfig.STALE_AFTER_MILLIS;
    }
}
