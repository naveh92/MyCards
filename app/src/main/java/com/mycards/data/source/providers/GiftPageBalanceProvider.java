package com.mycards.data.source.providers;

import com.mycards.data.catalog.model.SourceDef;
import com.mycards.data.source.BalanceSourceProvider;
import com.mycards.data.source.SourceEnv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reads a remaining balance from a public gift-card page.
 *
 * <p>Only works where the link itself carries the voucher token and the page needs no login.
 * Many issuers render the balance with JavaScript or sit behind a code entry form, in which
 * case nothing is found and {@code null} is returned.
 *
 * <p>Deliberately conservative: a balance is reported only when the page contains exactly
 * one distinct plausible amount near balance wording. Any ambiguity yields {@code null},
 * because a wrong number here produces a false "unlogged transaction" alert.
 */
public class GiftPageBalanceProvider implements BalanceSourceProvider {

    public static final String TYPE = "buyme_gift_page";

    /** Balance wording in Hebrew and English; the page must mention one of these. */
    private static final Pattern BALANCE_CONTEXT = Pattern.compile(
            "יתרה|יתרת|נותר|balance|remaining", Pattern.CASE_INSENSITIVE);

    /** Shekel amounts, with the symbol on either side and optional thousands separators. */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:₪|ש\"ח|שח|ILS)\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"
                    + "|([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*(?:₪|ש\"ח|שח|ILS)");

    private static final int MAX_PAGE_CHARS = 500_000;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Double fetchBalance(SourceDef def, String giftUrl, SourceEnv env) throws Exception {
        if (giftUrl == null || !giftUrl.trim().toLowerCase().startsWith("http")) {
            return null;
        }

        Request request = new Request.Builder()
                .url(giftUrl.trim())
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .get()
                .build();

        String html;
        try (Response response = env.http().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("gift page HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            html = body.string();
        }

        if (html.length() > MAX_PAGE_CHARS) {
            html = html.substring(0, MAX_PAGE_CHARS);
        }
        return extractBalance(html);
    }

    /** Package-visible so the parsing rules can be exercised without a network call. */
    static Double extractBalance(String html) {
        if (html == null || !BALANCE_CONTEXT.matcher(html).find()) {
            // No balance wording at all — almost certainly a login or marketing page.
            return null;
        }

        List<Double> candidates = new ArrayList<>();
        Matcher m = AMOUNT.matcher(html);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw == null) {
                continue;
            }
            try {
                double value = Double.parseDouble(raw.replace(",", ""));
                if (value >= 0 && value <= 100_000 && !candidates.contains(value)) {
                    candidates.add(value);
                }
            } catch (NumberFormatException ignored) {
                // Not a usable number; skip it.
            }
        }

        // Exactly one distinct amount is the only case we can trust. Several amounts
        // usually means prices or denominations are on the page alongside the balance,
        // and picking one would be a guess.
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
}
