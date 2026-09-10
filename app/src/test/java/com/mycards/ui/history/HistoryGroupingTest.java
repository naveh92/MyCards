package com.mycards.ui.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mycards.data.db.SpendEntity;
import com.mycards.search.SearchEngine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

public class HistoryGroupingTest {

    private static long at(int year, int month, int day) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month - 1, day, 12, 0, 0);
        return cal.getTimeInMillis();
    }

    private static Purchase purchase(String title, double amount, long when) {
        return purchase(title, amount, when, "Some card", "ILS", null);
    }

    private static Purchase purchase(String title, double amount, long when, String cardName,
                                     String currency, String store) {
        SpendEntity spend = new SpendEntity();
        spend.id = Math.abs((title + when).hashCode()) + 1L;
        spend.title = title;
        spend.amount = amount;
        spend.spentAt = when;
        spend.storeName = store;
        return new Purchase(spend, cardName, "buyme_all", currency);
    }

    private static List<String> shape(List<HistoryRow> rows) {
        List<String> out = new ArrayList<>();
        for (HistoryRow row : rows) {
            out.add(row.type == HistoryRow.TYPE_MONTH
                    ? "[month " + row.monthTotal + "]"
                    : row.spend.title);
        }
        return out;
    }

    @Test
    public void anEmptyLogProducesNoRowsAtAll() {
        assertTrue(HistoryGrouping.group(new ArrayList<Purchase>()).isEmpty());
    }

    @Test
    public void purchasesInOneMonthSitUnderOneHeading() {
        List<HistoryRow> rows = HistoryGrouping.group(Arrays.asList(
                purchase("Shoes", 120d, at(2026, 7, 20)),
                purchase("Coffee", 30d, at(2026, 7, 3))));

        assertEquals(Arrays.asList("[month 150.0]", "Shoes", "Coffee"), shape(rows));
    }

    @Test
    public void eachMonthGetsItsOwnHeadingAndItsOwnTotal() {
        List<HistoryRow> rows = HistoryGrouping.group(Arrays.asList(
                purchase("Aug", 50d, at(2026, 8, 9)),
                purchase("JulA", 120d, at(2026, 7, 20)),
                purchase("JulB", 30d, at(2026, 7, 3))));

        assertEquals(Arrays.asList("[month 50.0]", "Aug", "[month 150.0]", "JulA", "JulB"),
                shape(rows));
    }

    /** The months must come out in the order they arrived, newest first. */
    @Test
    public void monthsKeepTheOrderTheQueryReturnedThemIn() {
        List<HistoryRow> rows = HistoryGrouping.group(Arrays.asList(
                purchase("Newest", 10d, at(2026, 9, 1)),
                purchase("Middle", 10d, at(2026, 6, 1)),
                purchase("Oldest", 10d, at(2025, 12, 1))));

        List<Long> months = new ArrayList<>();
        for (HistoryRow row : rows) {
            if (row.type == HistoryRow.TYPE_MONTH) {
                months.add(row.monthStart);
            }
        }
        assertEquals(3, months.size());
        assertTrue(months.get(0) > months.get(1));
        assertTrue(months.get(1) > months.get(2));
    }

    /** The same month a year apart is not the same bucket. */
    @Test
    public void theSameMonthInDifferentYearsIsTwoBuckets() {
        List<HistoryRow> rows = HistoryGrouping.group(Arrays.asList(
                purchase("ThisJuly", 10d, at(2026, 7, 5)),
                purchase("LastJuly", 20d, at(2025, 7, 5))));

        assertEquals(Arrays.asList("[month 10.0]", "ThisJuly", "[month 20.0]", "LastJuly"),
                shape(rows));
    }

    @Test
    public void aMonthStartsAtMidnightOnTheFirst() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(HistoryGrouping.startOfMonth(at(2026, 7, 23)));

        assertEquals(2026, cal.get(Calendar.YEAR));
        assertEquals(Calendar.JULY, cal.get(Calendar.MONTH));
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH));
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, cal.get(Calendar.MINUTE));
        assertEquals(0, cal.get(Calendar.MILLISECOND));
    }

    /** A month heading and a purchase must never be mistaken for one another by DiffUtil. */
    @Test
    public void headingIdsCannotCollideWithPurchaseIds() {
        List<HistoryRow> rows = HistoryGrouping.group(Arrays.asList(
                purchase("One", 10d, at(2026, 7, 5))));

        assertTrue("a month id must be negative", rows.get(0).id < 0);
        assertTrue("a purchase id must be positive", rows.get(1).id > 0);
        assertNotEquals(rows.get(0).id, rows.get(1).id);
    }

    // --- totals ---

    @Test
    public void oneCurrencyThroughoutGivesATotal() {
        HistoryGrouping.Totals totals = HistoryGrouping.total(Arrays.asList(
                purchase("A", 10.5d, at(2026, 7, 1), "Card", "ILS", null),
                purchase("B", 4.5d, at(2026, 7, 2), "Card", "ILS", null)));

        assertEquals(15d, totals.amount, 0.0001d);
        assertEquals("ILS", totals.currency);
    }

    /** Adding euros to shekels and printing the result would be a confident wrong answer. */
    @Test
    public void mixedCurrenciesRefuseToProduceATotal() {
        HistoryGrouping.Totals totals = HistoryGrouping.total(Arrays.asList(
                purchase("A", 10d, at(2026, 7, 1), "Card", "ILS", null),
                purchase("B", 10d, at(2026, 7, 2), "Card", "EUR", null)));

        assertNull(totals.currency);
    }

    @Test
    public void aMissingCurrencyIsTreatedAsShekels() {
        HistoryGrouping.Totals totals = HistoryGrouping.total(Arrays.asList(
                purchase("A", 10d, at(2026, 7, 1), "Card", null, null),
                purchase("B", 10d, at(2026, 7, 2), "Card", "ILS", null)));

        assertEquals("ILS", totals.currency);
        assertEquals(20d, totals.amount, 0.0001d);
    }

    @Test
    public void aMonthOfMixedCurrenciesShowsNoTotalOnItsHeading() {
        List<HistoryRow> rows = HistoryGrouping.group(Arrays.asList(
                purchase("A", 10d, at(2026, 7, 1), "Card", "ILS", null),
                purchase("B", 10d, at(2026, 7, 2), "Card", "EUR", null)));

        assertNull(rows.get(0).monthCurrency);
    }

    // --- searching ---

    @Test
    public void aPurchaseIsFoundByItsDescriptionShopOrCard() {
        Purchase p = purchase("Shoes", 120d, at(2026, 7, 1), "Holiday gift", "ILS", "Castro");

        assertTrue(p.matches(SearchEngine.queryVariants("shoe")));
        assertTrue(p.matches(SearchEngine.queryVariants("castro")));
        assertTrue(p.matches(SearchEngine.queryVariants("holiday")));
        assertTrue("matching is infix, as everywhere else in this app",
                p.matches(SearchEngine.queryVariants("astr")));
    }

    /**
     * normalize() strips spaces, so a single joined haystack would read "shoescastro" and
     * match a fragment spanning the join. Three separate fields cannot.
     */
    @Test
    public void aQueryCannotMatchAcrossTwoDifferentFields() {
        Purchase p = purchase("Shoes", 120d, at(2026, 7, 1), "Holiday gift", "ILS", "Castro");

        assertTrue(p.matches(SearchEngine.queryVariants("shoes")));
        assertTrue(p.matches(SearchEngine.queryVariants("castro")));
        assertTrue("\"escas\" spans the end of one field and the start of another",
                !p.matches(SearchEngine.queryVariants("escas")));
    }

    @Test
    public void aPurchaseWithNoShopRecordedIsStillSearchable() {
        Purchase p = purchase("Bed linen", 200d, at(2026, 7, 1), "Fox card", "ILS", null);

        assertTrue(p.matches(SearchEngine.queryVariants("linen")));
        assertTrue(!p.matches(SearchEngine.queryVariants("castro")));
    }

    /** The wrong-keyboard-layout trick the rest of the app has, carried over for free. */
    @Test
    public void typingHebrewOnALatinKeyboardStillFindsThePurchase() {
        Purchase p = purchase("נעליים", 120d, at(2026, 7, 1), "מתנה", "ILS", "אדידס");

        // "tshsx" is what the keys under א-ד-י-ד-ס produce on a US layout.
        assertTrue(p.matches(SearchEngine.queryVariants("tshsx")));
    }

    /** Hebrew final letters fold, so a fragment from mid-word still matches. */
    @Test
    public void hebrewFinalLettersFold() {
        Purchase p = purchase("ביגוד", 90d, at(2026, 7, 1), "כרטיס", "ILS", "קסטרו");

        assertTrue(p.matches(SearchEngine.queryVariants("קסטרו")));
        assertTrue(p.matches(SearchEngine.queryVariants("ביגוד")));
    }
}
