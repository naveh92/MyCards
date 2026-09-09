package com.mycards.data.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {CardEntity.class, SpendEntity.class, StoreCacheEntity.class},
        version = 3,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {

    public abstract CardDao cardDao();

    public abstract SpendDao spendDao();

    public abstract StoreCacheDao storeCacheDao();

    private static volatile AppDatabase instance;

    /**
     * Adds {@link CardEntity#archivedAt}, the one piece of "this card is retired" that has to
     * be stored rather than worked out.
     *
     * <p>{@code NOT NULL DEFAULT 0} matches what Room generates for a primitive {@code long},
     * and 0 is exactly the value that means "still in use" — so every card already on a phone
     * lands in the right state without a second statement.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE cards ADD COLUMN archivedAt INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** Every migration this build knows, in order. Shared with the migration test. */
    /**
     * Adds {@link CardEntity#giftUrlFingerprint} and its index, so a gift link already in the
     * wallet can be recognised before it is added a second time.
     *
     * <p>Nullable with no default, and left null for every existing row: the value is a hash
     * of the <em>decrypted</em> link, and a migration has no vault to decrypt with. The rows
     * are filled in afterwards by {@code CardsRepository#backfillGiftFingerprints}, which
     * runs where the vault is available.
     *
     * <p>The index name is the one Room generates for {@code @Index("giftUrlFingerprint")}.
     * It has to match exactly, or the schema validation Room runs on open fails and the app
     * will not start.
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE cards ADD COLUMN giftUrlFingerprint TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cards_giftUrlFingerprint "
                    + "ON cards (giftUrlFingerprint)");
        }
    };

    /** Every migration this build knows, in order. Shared with the migration test. */
    public static Migration[] migrations() {
        return new Migration[]{MIGRATION_1_2, MIGRATION_2_3};
    }

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
                            // 1.2 shipped at version 1, so from here on every schema change
                            // MUST bump the version and add a Migration below — Room will
                            // otherwise refuse to open an existing database and the app will
                            // not start at all on a phone that already has one.
                            .addMigrations(migrations())
                            .build();
                }
            }
        }
        return instance;
    }
}
