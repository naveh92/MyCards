package com.mycards.ui;

import android.content.Context;

import com.mycards.R;

import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Money, date and staleness formatting shared across the screens. */
public final class Formats {

    private Formats() {
    }

    /** How an expiry is stored: year and month only, e.g. {@code 2027-03}. */
    public static final String ISO_MONTH = "yyyy-MM";

    public static String money(double amount, String currencyCode) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("he", "IL"));
        try {
            nf.setCurrency(Currency.getInstance(
                    currencyCode == null || currencyCode.isEmpty() ? "ILS" : currencyCode));
        } catch (IllegalArgumentException unknownCurrency) {
            nf.setCurrency(Currency.getInstance("ILS"));
        }
        // Whole shekels are the common case; only show agorot when they matter.
        boolean whole = Math.abs(amount - Math.rint(amount)) < 0.005d;
        nf.setMaximumFractionDigits(whole ? 0 : 2);
        nf.setMinimumFractionDigits(whole ? 0 : 2);
        return tidy(nf.format(amount));
    }

    /**
     * Strips the invisible padding the Hebrew currency format leaves behind.
     *
     * <p>{@code NumberFormat} for he-IL wraps its output in bidi marks and separates the
     * symbol with a non-breaking space. Dropped into a sentence like "Originally %s", those
     * render as a conspicuous gap. Removing them is safe: the direction of the surrounding
     * text is resolved by the view's own bidi handling, not by these characters.
     */
    private static String tidy(String formatted) {
        return formatted
                // LRM, RLM and Arabic letter mark.
                .replaceAll("[‎‏؜]", "")
                .replace(' ', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * The amount as a bare number for an input field — no symbol, no grouping separators,
     * and no trailing ".0" on whole shekels.
     */
    public static String plainAmount(double amount) {
        if (Math.abs(amount - Math.rint(amount)) < 0.005d) {
            return String.valueOf((long) Math.rint(amount));
        }
        return String.format(Locale.US, "%.2f", amount);
    }

    // --- expiry: month and year only ---

    /**
     * Resolves a stored {@code yyyy-MM} expiry to the last instant of that month.
     *
     * <p>A card marked 03/27 is good through the whole of March, so treating it as expiring
     * on the 1st would grey out a perfectly spendable card for a month.
     *
     * @return epoch millis, or {@link Long#MAX_VALUE} when absent or unparseable
     */
    public static long expiryEndMillis(String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return Long.MAX_VALUE;
        }
        String value = stored.trim();
        // Tolerate a full ISO date, in case older data or a hand-edited catalog supplies one.
        if (value.length() > 7) {
            value = value.substring(0, 7);
        }
        try {
            Date parsed = new SimpleDateFormat(ISO_MONTH, Locale.US).parse(value);
            if (parsed == null) {
                return Long.MAX_VALUE;
            }
            Calendar cal = Calendar.getInstance();
            cal.setTime(parsed);
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
            return cal.getTimeInMillis();
        } catch (ParseException e) {
            return Long.MAX_VALUE;
        }
    }

    /** {@code 2027-03} to the {@code 03/27} people actually read off a card. */
    public static String expiryToDisplay(String stored) {
        long millis = expiryEndMillis(stored);
        if (millis == Long.MAX_VALUE) {
            return "";
        }
        return new SimpleDateFormat("MM/yy", Locale.US).format(new Date(millis));
    }

    /**
     * {@code 03/27} back to {@code 2027-03}.
     *
     * @return null when the text is not a valid month/year
     */
    public static String displayToStored(String display) {
        if (display == null) {
            return null;
        }
        String digits = display.replaceAll("[^0-9]", "");
        if (digits.length() != 4) {
            return null;
        }
        int month = Integer.parseInt(digits.substring(0, 2));
        if (month < 1 || month > 12) {
            return null;
        }
        // Two-digit years are this century: a gift card is not expiring in 1927.
        int year = 2000 + Integer.parseInt(digits.substring(2, 4));
        return String.format(Locale.US, "%04d-%02d", year, month);
    }

    public static boolean isValidExpiryDisplay(String display) {
        return display == null || display.trim().isEmpty() || displayToStored(display) != null;
    }

    /** Whole days until the expiry month ends; negative once it has passed. */
    public static long daysUntil(String stored) {
        long millis = expiryEndMillis(stored);
        if (millis == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return TimeUnit.MILLISECONDS.toDays(millis - System.currentTimeMillis());
    }

    // --- ordinary dates, used by the spend log ---

    public static String prettyDate(Context context, long millis) {
        return android.text.format.DateFormat.getDateFormat(context).format(new Date(millis));
    }

    /**
     * Renders the timestamp a backup file records for itself.
     *
     * <p>It is stored as ISO-8601 because the file format has to be stable and machine
     * readable. Shown to someone deciding whether a backup is recent enough to rely on,
     * "2026-08-01T12:36:38Z" is a string to decode rather than a date to read — and in Hebrew
     * it is a run of Latin characters and punctuation in the middle of a sentence.
     *
     * @return the date and time in the reader's own format, or the raw value when it cannot
     *         be parsed — a file written by some other version still deserves to say when
     */
    public static String prettyTimestamp(Context context, String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return null;
        }
        try {
            Date when = new SimpleDateFormat(BACKUP_TIMESTAMP, Locale.US).parse(iso.trim());
            if (when == null) {
                return iso;
            }
            return android.text.format.DateFormat.getDateFormat(context).format(when)
                    + " " + android.text.format.DateFormat.getTimeFormat(context).format(when);
        } catch (ParseException unparseable) {
            return iso;
        }
    }

    /** How {@code BackupPayload.exportedAt} is written; part of the file format. */
    private static final String BACKUP_TIMESTAMP = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    /**
     * Describes how old the store data is. Age matters more than the exact timestamp: what
     * the user needs to judge is whether to trust the list at the checkout counter.
     */
    public static String updatedAgo(Context context, long fetchedAt) {
        if (fetchedAt <= 0L) {
            // Zero is not "we have never managed to fetch this" — it is the marker
            // seedCacheIfEmpty writes for a list that shipped inside the APK. Reporting a
            // failure for data that arrived with the app reads as a warning about a list
            // that is in fact exactly as published on the day of release.
            return context.getString(R.string.store_list_shipped);
        }
        long days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - fetchedAt);
        String when;
        if (days <= 0) {
            when = context.getString(R.string.updated_today);
        } else if (days == 1) {
            when = context.getString(R.string.updated_yesterday);
        } else {
            when = context.getString(R.string.updated_days_ago, days);
        }
        return context.getString(R.string.store_list_updated, when);
    }
}
