package com.mycards.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

/**
 * Replays a real version 1 database through the migration.
 *
 * <p>1.2 shipped at version 1, so every phone that already has the app has one of these
 * files on it, holding hand-entered balances and a spend history that exists nowhere else.
 * There is deliberately no destructive fallback in {@link AppDatabase}: if a migration is
 * wrong, the app does not quietly start with an empty wallet, it refuses to start at all.
 * Either way the only defence is running the thing against the schema that is actually out
 * there rather than reading it and agreeing with oneself.
 */
@RunWith(AndroidJUnit4.class)
public class MigrationTest {

    private static final String DB = "migration-test";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), AppDatabase.class);

    /**
     * The upgrade every existing installation will take: a wallet with a card and a purchase
     * in it, carried across intact, with the new column defaulted to "in use".
     */
    @Test
    public void migratesAVersionOneWalletWithoutLosingAnything() throws IOException {
        SupportSQLiteDatabase v1 = helper.createDatabase(DB, 1);
        v1.execSQL("INSERT INTO cards ("
                + "id, uuid, updatedAt, cardTypeId, label, expiryDate, initialAmount, currency,"
                + " notes, createdAt, lastBalanceCheckAt, hasUnreconciledMismatch) VALUES ("
                + "1, 'card-uuid-1', 1700000000000, 'buyme_all', 'Holiday gift', '2027-03',"
                + " 400.0, 'ILS', 'from Dana', 1690000000000, 0, 0)");
        v1.execSQL("INSERT INTO spends ("
                + "id, uuid, cardId, cardUuid, title, amount, storeName, spentAt, source,"
                + " createdAt) VALUES ("
                + "1, 'spend-uuid-1', 1, 'card-uuid-1', 'Shoes', 120.0, 'Castro',"
                + " 1695000000000, 'MANUAL', 1695000000000)");
        v1.close();

        SupportSQLiteDatabase v2 =
                helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.migrations());

        Cursor cards = v2.query("SELECT uuid, label, initialAmount, expiryDate, archivedAt"
                + " FROM cards WHERE id = 1");
        assertTrue("the card did not survive the migration", cards.moveToFirst());
        assertEquals("card-uuid-1", cards.getString(0));
        assertEquals("Holiday gift", cards.getString(1));
        assertEquals(400.0d, cards.getDouble(2), 0.0001d);
        assertEquals("2027-03", cards.getString(3));
        // The point of the default: a card that existed before archiving did is in use.
        assertEquals(0L, cards.getLong(4));
        cards.close();

        // The spend log is the part that cannot be re-entered from anything, so it is
        // checked rather than assumed.
        Cursor spends = v2.query("SELECT title, amount, storeName FROM spends WHERE id = 1");
        assertTrue("the purchase did not survive the migration", spends.moveToFirst());
        assertEquals("Shoes", spends.getString(0));
        assertEquals(120.0d, spends.getDouble(1), 0.0001d);
        assertEquals("Castro", spends.getString(2));
        spends.close();
    }

    /** An empty wallet is the other real case: installed, never used, then updated. */
    @Test
    public void migratesAnEmptyVersionOneDatabase() throws IOException {
        helper.createDatabase(DB, 1).close();

        SupportSQLiteDatabase v2 =
                helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.migrations());

        assertNotNull(v2);
        Cursor cursor = v2.query("SELECT COUNT(*) FROM cards");
        assertTrue(cursor.moveToFirst());
        assertEquals(0, cursor.getInt(0));
        cursor.close();
    }

    /** The new column has to accept a real archive timestamp, not just its default. */
    @Test
    public void theMigratedColumnStoresAnArchiveTimestamp() throws IOException {
        helper.createDatabase(DB, 1).close();
        SupportSQLiteDatabase v2 =
                helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.migrations());

        v2.execSQL("INSERT INTO cards ("
                + "id, uuid, updatedAt, cardTypeId, initialAmount, currency, createdAt,"
                + " lastBalanceCheckAt, hasUnreconciledMismatch, archivedAt) VALUES ("
                + "2, 'card-uuid-2', 1700000000000, 'buyme_all', 50.0, 'ILS', 1690000000000,"
                + " 0, 0, 1750000000000)");

        Cursor cursor = v2.query("SELECT archivedAt FROM cards WHERE id = 2");
        assertTrue(cursor.moveToFirst());
        assertEquals(1750000000000L, cursor.getLong(0));
        cursor.close();
    }
}
