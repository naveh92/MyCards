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

    /**
     * The upgrade a phone still on 1.2 will actually take, which is 1 to 4 in one go rather
     * than any step on its own.
     *
     * <p>Worth its own test even though both steps are covered: Room applies migrations in
     * sequence, and a chain that works pairwise can still fail as a chain — most obviously
     * if a later migration assumes a column an earlier one named differently.
     */
    @Test
    public void migratesAllTheWayFromVersionOne() throws IOException {
        SupportSQLiteDatabase v1 = helper.createDatabase(DB, 1);
        v1.execSQL("INSERT INTO cards ("
                + "id, uuid, updatedAt, cardTypeId, label, expiryDate, initialAmount, currency,"
                + " notes, createdAt, lastBalanceCheckAt, hasUnreconciledMismatch) VALUES ("
                + "1, 'card-uuid-1', 1700000000000, 'buyme_all', 'Holiday gift', '2027-03',"
                + " 400.0, 'ILS', 'from Dana', 1690000000000, 0, 0)");
        v1.close();

        SupportSQLiteDatabase v4 =
                helper.runMigrationsAndValidate(DB, 4, true, AppDatabase.migrations());

        Cursor cards = v4.query("SELECT label, initialAmount, archivedAt, giftUrlFingerprint,"
                + " faceColor FROM cards WHERE id = 1");
        assertTrue("the card did not survive the migrations", cards.moveToFirst());
        assertEquals("Holiday gift", cards.getString(0));
        assertEquals(400.0d, cards.getDouble(1), 0.0001d);
        assertEquals(0L, cards.getLong(2));
        // Null, and deliberately so: the value is a hash of the decrypted link and a
        // migration has no vault to decrypt with. CardsRepository fills these in later.
        assertTrue("the fingerprint should start out unknown", cards.isNull(3));
        // Likewise: a card that predates hand-picked colours has not picked one.
        assertTrue("the colour should start out unchosen", cards.isNull(4));
        cards.close();
    }

    /**
     * The fingerprint column has to be writable and searchable, since finding a duplicate is
     * a lookup on it and a column that exists but is not indexed would work and be slow.
     */
    @Test
    public void theFingerprintColumnStoresAndFindsALink() throws IOException {
        helper.createDatabase(DB, 2).close();
        SupportSQLiteDatabase v3 =
                helper.runMigrationsAndValidate(DB, 3, true, AppDatabase.migrations());

        v3.execSQL("INSERT INTO cards ("
                + "id, uuid, updatedAt, cardTypeId, initialAmount, currency, createdAt,"
                + " lastBalanceCheckAt, hasUnreconciledMismatch, archivedAt,"
                + " giftUrlFingerprint) VALUES ("
                + "3, 'card-uuid-3', 1700000000000, 'buyme_all', 50.0, 'ILS', 1690000000000,"
                + " 0, 0, 0, 'abc123')");

        Cursor found = v3.query(
                "SELECT id FROM cards WHERE giftUrlFingerprint = 'abc123'");
        assertTrue("the fingerprint could not be looked up", found.moveToFirst());
        assertEquals(3L, found.getLong(0));
        found.close();

        Cursor index = v3.query(
                "SELECT name FROM sqlite_master WHERE type = 'index'"
                        + " AND name = 'index_cards_giftUrlFingerprint'");
        assertTrue("the migration did not create the index Room expects", index.moveToFirst());
        index.close();
    }

    /**
     * The colour column has to accept a colour and give it back, and has to be null for a
     * card that predates it — which is what makes such a card keep taking its colour from
     * its card type instead of arriving black.
     */
    @Test
    public void theColourColumnStartsUnsetAndStoresAColour() throws IOException {
        SupportSQLiteDatabase v3 = helper.createDatabase(DB, 3);
        v3.execSQL("INSERT INTO cards ("
                + "id, uuid, updatedAt, cardTypeId, initialAmount, currency, createdAt,"
                + " lastBalanceCheckAt, hasUnreconciledMismatch, archivedAt) VALUES ("
                + "4, 'card-uuid-4', 1700000000000, 'buyme_all', 50.0, 'ILS', 1690000000000,"
                + " 0, 0, 0)");
        v3.close();

        SupportSQLiteDatabase v4 =
                helper.runMigrationsAndValidate(DB, 4, true, AppDatabase.migrations());

        Cursor existing = v4.query("SELECT faceColor FROM cards WHERE id = 4");
        assertTrue("the card did not survive the migration", existing.moveToFirst());
        assertTrue("a card from before the column should have made no choice",
                existing.isNull(0));
        existing.close();

        // Opaque and above 0x7FFFFFFF, which is the case a narrower column type would ruin:
        // every real face colour has its alpha byte set and so reads as a negative int.
        v4.execSQL("UPDATE cards SET faceColor = " + 0xFF3B5BC0 + " WHERE id = 4");
        Cursor coloured = v4.query("SELECT faceColor FROM cards WHERE id = 4");
        assertTrue(coloured.moveToFirst());
        assertEquals(0xFF3B5BC0, coloured.getInt(0));
        coloured.close();
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
