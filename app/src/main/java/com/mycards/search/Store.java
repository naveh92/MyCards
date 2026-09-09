package com.mycards.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A merchant that accepts some gift card, together with every spelling it might be
 * searched by.
 *
 * <p>The spellings are kept <em>as written</em> and not only as match keys, because the two
 * questions a result row has to answer are different. "Does this match?" needs the canonical
 * form; "why is this on my screen?" needs the text a person wrote. Answering the second one
 * is what turns "מגוון מותגי קבוצת קסטרו הודיס", returned for a query about Carolina Lemke,
 * from a bug into a fact — that entry really does list קרולינה among its names.
 *
 * <p>Three parallel arrays rather than a list of little objects: search walks them on every
 * keystroke across tens of thousands of aliases, and one object header per alias is a cost
 * with nothing to show for it. Where normalizing or folding changes nothing — most Latin
 * names, most single-word Hebrew ones — the arrays hold the <em>same</em> string instance, so
 * the extra two arrays are mostly pointers rather than text.
 *
 * <p>Normalized forms are computed once at construction. Search runs on every keystroke, so
 * normalizing ~40,000 alias strings per keypress would be far too slow; this pushes that
 * cost to index-build time instead.
 */
public final class Store {

    /** The index in {@link #forms} of the merchant's own name. Aliases follow it. */
    public static final int NAME_FORM = 0;

    private final boolean onlineRedeem;

    /** Name first, then aliases as written, deduplicated by canonical form. */
    private final String[] forms;

    /** {@link #forms} normalized. This is what exact matching scans. */
    private final String[] normalized;

    /** {@link #normalized} folded. Scanned only when the exact pass came up empty. */
    private final String[] folded;

    public Store(String name, List<String> aliases, boolean onlineRedeem) {
        this.onlineRedeem = onlineRedeem;

        List<String> written = new ArrayList<>();
        List<String> canonical = new ArrayList<>();

        // The name always takes slot 0, even when it normalizes to nothing at all (a shop
        // named "!!!"), so NAME_FORM means the name on every store without exception.
        String safeName = name == null ? "" : name;
        written.add(safeName);
        canonical.add(SearchNormalizer.normalize(safeName));

        if (aliases != null) {
            for (String alias : aliases) {
                add(written, canonical, alias);
            }
        }

        this.forms = written.toArray(new String[0]);
        this.normalized = canonical.toArray(new String[0]);
        this.folded = new String[this.normalized.length];
        for (int i = 0; i < this.normalized.length; i++) {
            this.folded[i] = HebrewFold.of(this.normalized[i]);
        }
    }

    private static void add(List<String> written, List<String> canonical, String raw) {
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        String n = SearchNormalizer.normalize(trimmed);
        if (n.isEmpty() || canonical.contains(n)) {
            return;
        }
        // Reusing the one instance when normalization changed nothing — true of "קרולינה"
        // and every other single-word lowercase name — keeps a second copy of most of the
        // merchant list off the heap.
        written.add(n.equals(trimmed) ? n : trimmed);
        canonical.add(n);
    }

    public String getName() {
        return forms[NAME_FORM];
    }

    public boolean isOnlineRedeem() {
        return onlineRedeem;
    }

    public int formCount() {
        return forms.length;
    }

    /** One of this merchant's spellings as written; {@link #NAME_FORM} is the name. */
    public String getForm(int index) {
        return forms[index];
    }

    /** Every spelling as written, name first. What the local cache is written from. */
    public List<String> getForms() {
        return Collections.unmodifiableList(java.util.Arrays.asList(forms));
    }

    /**
     * Matches this merchant against a query, reporting which spelling answered.
     *
     * <p>The exact pass runs to completion before the fuzzy one starts, and not merely for
     * ranking: a merchant found literally must never be reported as found by skeleton, or
     * the row would offer a reason weaker than the truth.
     *
     * @return the best match, or null when nothing in this merchant matches
     */
    public StoreMatch match(Query query) {
        int bestScore = MatchScore.NONE;
        int bestForm = -1;

        for (int i = 0; i < normalized.length; i++) {
            int s = tier(normalized[i], query.exact(), false);
            if (s > bestScore) {
                bestScore = s;
                bestForm = i;
                if (bestScore == MatchScore.EXACT && i == NAME_FORM) {
                    break;
                }
            }
        }
        if (bestForm >= 0) {
            return new StoreMatch(this, bestScore, bestForm, false);
        }

        if (!query.hasFuzzy()) {
            return null;
        }
        int bestSlack = Integer.MAX_VALUE;
        for (int i = 0; i < folded.length; i++) {
            int s = tier(folded[i], query.fuzzy(), true);
            if (s == MatchScore.NONE) {
                continue;
            }
            // How far this spelling strayed from what was typed, measured before folding —
            // after it every candidate is the same four letters by definition. See
            // StoreMatch#compare.
            int slack = Math.abs(normalized[i].length() - query.exact().length());
            if (s > bestScore || (s == bestScore && slack < bestSlack)) {
                bestScore = s;
                bestSlack = slack;
                bestForm = i;
            }
        }
        return bestForm < 0
                ? null
                : new StoreMatch(this, bestScore, bestForm, true, bestSlack);
    }

    /** True when any of {@code queries} matches; cheaper than {@link #match} when counting. */
    public boolean matchesAny(List<Query> queries) {
        for (Query q : queries) {
            if (match(q) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Grades one haystack against one already-canonical needle.
     *
     * @param fuzzy whether both sides are skeletons, which drops the result a whole band
     */
    static int tier(String haystack, String needle, boolean fuzzy) {
        if (!SearchNormalizer.containsNormalized(haystack, needle)) {
            return MatchScore.NONE;
        }
        if (haystack.equals(needle)) {
            return fuzzy ? MatchScore.FUZZY_EXACT : MatchScore.EXACT;
        }
        if (haystack.startsWith(needle)) {
            return fuzzy ? MatchScore.FUZZY_PREFIX : MatchScore.PREFIX;
        }
        // A skeleton buried inside a longer skeleton is not evidence of anything. See
        // MatchScore#FUZZY_PREFIX.
        return fuzzy ? MatchScore.NONE : MatchScore.SUBSTRING;
    }

    @Override
    public String toString() {
        return getName();
    }
}
