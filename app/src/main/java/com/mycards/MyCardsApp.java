package com.mycards;

import android.app.Application;

import com.mycards.data.CardsRepository;
import com.mycards.notify.Notifications;
import com.mycards.sync.SyncScheduler;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.ThemePrefs;

public class MyCardsApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Applied before any activity inflates, so there is no light-to-dark flash.
        ThemePrefs.apply(this);
        Notifications.ensureChannels(this);
        SyncScheduler.schedulePeriodic(this);
        // Catches up if the device was off long enough to miss the periodic window.
        SyncScheduler.syncIfStale(this);

        // Fills in gift-link fingerprints for cards added before that column existed, so the
        // duplicate check can see them. Off the main thread — it opens the database — and
        // cheap after the first run, because it only selects rows still missing one.
        AppExecutors.io(() -> new CardsRepository(this).backfillGiftFingerprints());
    }
}
