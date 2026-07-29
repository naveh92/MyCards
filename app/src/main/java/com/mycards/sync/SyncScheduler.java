package com.mycards.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.mycards.data.RemoteConfig;

import java.util.concurrent.TimeUnit;

/**
 * Schedules the weekly refresh, plus the on-launch staleness check.
 *
 * <p>Two mechanisms on purpose: WorkManager handles the background cadence, but a phone that
 * has been off or Doze-throttled for a fortnight may never have run it. Checking staleness at
 * launch means opening the app is always enough to start catching up.
 */
public final class SyncScheduler {

    private SyncScheduler() {
    }

    private static final String PREFS = "mycards_sync";
    private static final String KEY_LAST_SYNC = "last_sync_at";

    private static final String WORK_PERIODIC_CATALOG = "mycards_periodic_catalog";
    private static final String WORK_ONESHOT_CATALOG = "mycards_oneshot_catalog";
    private static final String WORK_PERIODIC_BALANCE = "mycards_periodic_balance";

    public static void schedulePeriodic(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest catalog = new PeriodicWorkRequest.Builder(
                CatalogSyncWorker.class, 7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_PERIODIC_CATALOG, ExistingPeriodicWorkPolicy.KEEP, catalog);

        // Balances change far more often than merchant lists, so this runs daily.
        PeriodicWorkRequest balance = new PeriodicWorkRequest.Builder(
                BalanceCheckWorker.class, 1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_PERIODIC_BALANCE, ExistingPeriodicWorkPolicy.KEEP, balance);
    }

    /** Runs a refresh now if the data has aged past the staleness window. */
    public static void syncIfStale(Context context) {
        if (System.currentTimeMillis() - lastSyncAt(context) > RemoteConfig.STALE_AFTER_MILLIS) {
            syncNow(context);
        }
    }

    public static void syncNow(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CatalogSyncWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ONESHOT_CATALOG, ExistingWorkPolicy.KEEP, request);
    }

    public static long lastSyncAt(Context context) {
        return prefs(context).getLong(KEY_LAST_SYNC, 0L);
    }

    public static void recordSyncTime(Context context) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
