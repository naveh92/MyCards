package com.mycards.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mycards.data.CatalogRepository;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.db.AppDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Refreshes the catalog and every merchant list the user's cards depend on.
 *
 * <p>Only card types actually held are refreshed. Pulling all ~1,900 BuyMe merchants for a
 * card the user does not own would waste several megabytes of their mobile data.
 */
public class CatalogSyncWorker extends Worker {

    private static final String TAG = "CatalogSyncWorker";

    public CatalogSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            CatalogRepository repo = new CatalogRepository(getApplicationContext());
            repo.refreshCatalog();

            Catalog catalog = repo.loadCatalog();
            List<String> typeIds = new ArrayList<>(
                    AppDatabase.get(getApplicationContext()).cardDao().getUsedCardTypeIds());
            if (typeIds.isEmpty()) {
                Log.i(TAG, "no cards held; nothing to refresh");
                return Result.success();
            }

            // Consulted first so unchanged lists are skipped without downloading them.
            int updated = repo.refreshStores(catalog, typeIds, repo.fetchManifest());
            Log.i(TAG, "refreshed " + updated + " of " + typeIds.size() + " card types");

            SyncScheduler.recordSyncTime(getApplicationContext());

            // A partial refresh is still progress; the untouched types keep their old data
            // and will be retried on the next run rather than triggering an immediate retry.
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "sync failed", e);
            return Result.retry();
        }
    }
}
