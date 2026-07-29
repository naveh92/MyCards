package com.mycards;

import android.app.Application;

import com.mycards.notify.Notifications;
import com.mycards.sync.SyncScheduler;
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
    }
}
