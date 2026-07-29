package com.mycards.ui;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal threading helper: database and network work off the main thread, UI back on it. */
public final class AppExecutors {

    private AppExecutors() {
    }

    private static final ExecutorService DISK = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void io(Runnable task) {
        DISK.execute(task);
    }

    public static void main(Runnable task) {
        MAIN.post(task);
    }
}
