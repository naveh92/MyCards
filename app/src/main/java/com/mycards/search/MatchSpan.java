package com.mycards.search;

import java.util.List;

/**
 * Where in a piece of text the query actually landed.
 *
 * <p>A result list that filters without showing its working asks to be trusted. Searching
 * "קרולינה" against a BuyMe card returns "מגוון מותגי קבוצת קסטרו הודיס", which contains not
 * one letter of the query and looks like a broken filter — until the words that did match are
 * picked out, at which point it reads as the useful fact it is. Pointing at them is the
 * cheapest way to turn a list someone distrusts into one they can check.
 *
 * <p>Offsets index the <em>original</em> string, punctuation and spacing included, so a
 * caller can draw over the text it already has. Pure JDK: the rule for what gets highlighted
 * is worth testing without a device, and applying a span is not.
 */
public final class MatchSpan {

    /** Half-open, in characters of the string handed to {@link #find}. */
    public final int start;
    public final int end;

    private MatchSpan(int start, int end) {
        this.start = start;
        this.end = end;
    }

    /**
     * Finds the stretch of {@code text} that best explains why the query matched it.
     *
     * <p>Prefers a whole-word hit to a prefix and a prefix to a fragment, and only falls back
     * to a {@link HebrewFold} skeleton when nothing matched literally — the same order the
     * matching itself uses, so what is underlined is what was actually read.
     *
     * @return the span, or null when nothing in {@code text} matched
     */
    public static MatchSpan find(String text, List<Query> variants) {
        if (text == null || text.isEmpty() || variants == null || variants.isEmpty()) {
            return null;
        }

        SearchNormalizer.Normalized normalized = SearchNormalizer.normalizeWithSource(text);
        MatchSpan best = bestIn(normalized, variants, false);
        return best != null ? best : bestIn(normalized.folded(), variants, true);
    }

    private static MatchSpan bestIn(SearchNormalizer.Normalized haystack,
                                    List<Query> variants,
                                    boolean fuzzy) {
        int bestScore = MatchScore.NONE;
        int bestAt = -1;
        int bestLength = 0;

        for (Query variant : variants) {
            String needle = fuzzy ? variant.fuzzy() : variant.exact();
            if (needle == null) {
                continue;
            }
            int at = haystack.text.indexOf(needle);
            if (at < 0) {
                continue;
            }
            int score = Store.tier(haystack.text, needle, fuzzy);
            if (score > bestScore) {
                bestScore = score;
                bestAt = at;
                bestLength = needle.length();
            }
        }

        if (bestAt < 0) {
            return null;
        }
        // sourceEnd of the last matched character rather than sourceStart of the next one:
        // a space or hyphen dropped in between belongs to neither, and stretching the span
        // across it would underline trailing punctuation.
        return new MatchSpan(haystack.sourceStart(bestAt),
                haystack.sourceEnd(bestAt + bestLength - 1));
    }
}
