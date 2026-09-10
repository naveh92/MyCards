package com.mycards.ui.history;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a flat run of purchases into the month-by-month list the history screen shows.
 *
 * <p>Free of Android types so the bucketing and the arithmetic can be tested directly. The
 * month <em>labels</em> are not built here — those need a locale and belong to the adapter,
 * which is bound to a context that knows the current one even after the user has changed the
 * app's language.
 */
final class HistoryGrouping {

    private HistoryGrouping() {
    }

    /**
     * Buckets purchases into months, newest first, each behind its own heading.
     *
     * <p>Months rather than days, because this log is sparse — a gift card sees a handful of
     * purchases in its whole life — and a heading per day would leave a list that was mostly
     * headings. A month is also the unit people recall a purchase in: "sometime in July",
     * not the 14th.
     *
     * @param purchases newest first, as the query returns them; the order within a month is
     *                  preserved exactly as given
     */
    static List<HistoryRow> group(List<Purchase> purchases) {
        if (purchases.isEmpty()) {
            return Collections.emptyList();
        }

        // Insertion-ordered, and the purchases arrive newest first, so the months come out in
        // that order too without a second sort.
        Map<Long, List<Purchase>> byMonth = new LinkedHashMap<>();
        for (Purchase purchase : purchases) {
            long monthStart = startOfMonth(purchase.spend.spentAt);
            List<Purchase> bucket = byMonth.get(monthStart);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byMonth.put(monthStart, bucket);
            }
            bucket.add(purchase);
        }

        List<HistoryRow> out = new ArrayList<>();
        for (Map.Entry<Long, List<Purchase>> month : byMonth.entrySet()) {
            Totals totals = total(month.getValue());
            out.add(HistoryRow.month(month.getKey(), totals.amount, totals.currency));
            for (Purchase purchase : month.getValue()) {
                out.add(HistoryRow.purchase(purchase.spend, purchase.cardName,
                        purchase.cardTypeId, purchase.faceColor, purchase.currency));
            }
        }
        return out;
    }

    /** Midnight on the first of the month containing {@code millis}, in the device's zone. */
    static long startOfMonth(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /**
     * Adds up a set of purchases.
     *
     * <p>The currency comes back null the moment two of them disagree. Every card is in
     * shekels today, but the field exists, and a wallet holding one euro card would otherwise
     * be shown a single number adding euros to shekels — which is not a rounding error, it is
     * a wrong answer stated confidently. Better to show the count alone.
     */
    static Totals total(List<Purchase> of) {
        double amount = 0d;
        String currency = null;
        boolean mixed = false;
        for (Purchase purchase : of) {
            amount += purchase.spend.amount;
            String entryCurrency = purchase.currency == null ? "ILS" : purchase.currency;
            if (currency == null) {
                currency = entryCurrency;
            } else if (!currency.equals(entryCurrency)) {
                mixed = true;
            }
        }
        return new Totals(amount, mixed ? null : currency);
    }

    static final class Totals {
        final double amount;
        /** Null when the purchases do not share one currency. */
        final String currency;

        Totals(double amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }
    }
}
