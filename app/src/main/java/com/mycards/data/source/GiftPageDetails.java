package com.mycards.data.source;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything a gift page will give up about the card, so pasting a link can fill the form in.
 *
 * <p>Typing a card in by hand means copying a sixteen-digit number, an expiry and a balance
 * off a page that already has all three. The link is the only thing worth asking a person
 * for.
 *
 * <p><b>Every field is independent and every field may be null.</b> A page that yields a
 * balance and nothing else fills in the balance; the rest stays blank for the user to type.
 * Half a form filled is most of the work saved, and there is no version of this that is
 * reliable enough to be all-or-nothing.
 *
 * <p><b>Ambiguity always loses.</b> Each rule below reports a value only when the page
 * contains exactly one plausible candidate for it. Gift pages carry denominations, terms and
 * marketing copy full of numbers, and a wrong card number is worse than an empty field — an
 * empty field is obvious, a wrong one is discovered at a till.
 *
 * <p>The parsing is deliberately separate from the fetching so it can be tested against saved
 * markup without a network. That matters more than usual here: these are undocumented pages
 * that change without notice, and {@code tools/check-sources.sh} exists because they already
 * have.
 */
public final class GiftPageDetails {

    /** Beyond this a page is marketing, not a voucher, and scanning it all is wasted work. */
    private static final int MAX_PAGE_CHARS = 500_000;

    /** Balance wording in Hebrew and English; the page must mention one of these. */
    private static final Pattern BALANCE_CONTEXT = Pattern.compile(
            "יתרה|יתרת|נותר|balance|remaining", Pattern.CASE_INSENSITIVE);

    /** Shekel amounts, with the symbol on either side and optional thousands separators. */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:₪|ש\"ח|שח|ILS)\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"
                    + "|([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*(?:₪|ש\"ח|שח|ILS)");

    /**
     * An expiry written as a month and a year: 12/27, 12/2027, 12.2027, 12-2027.
     *
     * <p>Anchored on wording rather than found loose, because a bare "12/27" on a gift page
     * is as likely to be a date of issue or a phone number fragment as an expiry.
     */
    private static final Pattern EXPIRY = Pattern.compile(
            "(?:בתוקף עד|בתוקף|תוקף|תפוגה|valid\\s+(?:un)?til|valid\\s+through|expir\\w*)"
                    + "[^0-9]{0,40}?([0-9]{1,2})[/.\\-]([0-9]{2,4})",
            Pattern.CASE_INSENSITIVE);

    /**
     * A payment card number: 13 to 19 digits, optionally in groups of four.
     *
     * <p>The digits are pulled out and length-checked afterwards rather than counted here,
     * because the separators are inconsistent and a pattern strict enough to count through
     * them is a pattern that misses real cards.
     */
    private static final Pattern PAN = Pattern.compile(
            "\\b([0-9][0-9 \\-]{11,22}[0-9])\\b");

    /** A CVV, and only where the page says that is what it is. */
    private static final Pattern CVV = Pattern.compile(
            "(?:cvv|cvc|קוד\\s*אבטחה|שלוש\\s*ספרות)[^0-9]{0,20}?([0-9]{3,4})\\b",
            Pattern.CASE_INSENSITIVE);

    /** Remaining balance in the page's currency, or null when the page did not say. */
    public final Double amount;

    /** Expiry as {@code yyyy-MM} to match {@code CardEntity.expiryDate}, or null. */
    public final String expiryMonth;

    /** Card number, digits only, or null. */
    public final String pan;

    public final String cvv;

    private GiftPageDetails(Double amount, String expiryMonth, String pan, String cvv) {
        this.amount = amount;
        this.expiryMonth = expiryMonth;
        this.pan = pan;
        this.cvv = cvv;
    }

    /** True when the page yielded nothing at all, which is a normal outcome. */
    public boolean isEmpty() {
        return amount == null && expiryMonth == null && pan == null && cvv == null;
    }

    /** How many fields were found, for telling the user what was and was not filled. */
    public int count() {
        int found = 0;
        if (amount != null) found++;
        if (expiryMonth != null) found++;
        if (pan != null) found++;
        if (cvv != null) found++;
        return found;
    }

    public static GiftPageDetails parse(String html) {
        if (html == null || html.isEmpty()) {
            return new GiftPageDetails(null, null, null, null);
        }
        if (html.length() > MAX_PAGE_CHARS) {
            html = html.substring(0, MAX_PAGE_CHARS);
        }
        return new GiftPageDetails(
                extractAmount(html), extractExpiry(html), extractPan(html), extractCvv(html));
    }

    /**
     * The balance, when the page names exactly one.
     *
     * <p>Same rule as the unattended balance check, and deliberately so: two ways of reading
     * one page that disagreed about what it says would be worse than either alone.
     */
    static Double extractAmount(String html) {
        if (!BALANCE_CONTEXT.matcher(html).find()) {
            // No balance wording at all — almost certainly a login or marketing page.
            return null;
        }
        Set<Double> candidates = new LinkedHashSet<>();
        Matcher m = AMOUNT.matcher(html);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw == null) {
                continue;
            }
            try {
                double value = Double.parseDouble(raw.replace(",", ""));
                if (value >= 0 && value <= 100_000) {
                    candidates.add(value);
                }
            } catch (NumberFormatException ignored) {
                // Not a usable number; skip it.
            }
        }
        // Several amounts usually means denominations or prices sit beside the balance, and
        // picking one would be a guess.
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /**
     * The expiry as {@code yyyy-MM}.
     *
     * <p>A two-digit year is read as this century, which is the only reading that makes sense
     * for a voucher: "12/27" on a gift card is 2027, never 1927.
     */
    static String extractExpiry(String html) {
        Set<String> candidates = new LinkedHashSet<>();
        Matcher m = EXPIRY.matcher(html);
        while (m.find()) {
            int month;
            int year;
            try {
                month = Integer.parseInt(m.group(1));
                year = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (month < 1 || month > 12) {
                // Very likely a day/month pair matched the wrong way round; not an expiry.
                continue;
            }
            if (year < 100) {
                year += 2000;
            }
            // A voucher expiry outside this range is a misread, not a very old or very
            // long-lived card.
            if (year < 2000 || year > 2100) {
                continue;
            }
            candidates.add(String.format("%04d-%02d", year, month));
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /** The card number, when the page shows exactly one and it is not obviously masked. */
    static String extractPan(String html) {
        List<String> candidates = new ArrayList<>();
        Matcher m = PAN.matcher(html);
        while (m.find()) {
            String digits = m.group(1).replaceAll("[^0-9]", "");
            if (digits.length() < 13 || digits.length() > 19) {
                continue;
            }
            // A run of one repeated digit is a placeholder, not a card.
            if (digits.chars().distinct().count() < 2) {
                continue;
            }
            if (!candidates.contains(digits)) {
                candidates.add(digits);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    static String extractCvv(String html) {
        Set<String> candidates = new LinkedHashSet<>();
        Matcher m = CVV.matcher(html);
        while (m.find()) {
            candidates.add(m.group(1));
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }
}
