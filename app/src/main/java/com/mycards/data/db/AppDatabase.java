package com.mycards.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {CardEntity.class, SpendEntity.class, StoreCacheEntity.class},
        version = 1,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {

    public abstract CardDao cardDao();

    public abstract SpendDao spendDao();

    public abstract StoreCacheDao storeCacheDao();

    private static volatile AppDatabase instance;

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "mycards.db")
                            // No destructive fallback: these rows are hand-entered card
                            // balances and spend history that cannot be re-downloaded.
                            //
                            // Schema version stays at 1 until the first public release.
                            // After that, every schema change MUST bump the version and
                            // ship a Migration — Room will otherwise refuse to open an
                            // existing database and the app will not start.
                            .build();
                }
            }
        }
        return instance;
    }
}
