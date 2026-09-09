package com.mycards.search;

/**
 * One spelling of what the user typed, canonical and ready to scan with.
 *
 * <p>A single keystroke produces several of these — the text as typed, plus each
 * wrong-keyboard-layout reading of it (see {@link HebrewKeyboardMapper}) — and every one is
 * tried against every name. Precomputing them once per query rather than per merchant is the
 * difference between normalizing a handful of strings and normalizing tens of thousands.
 *
 * <p>Carries its {@link HebrewFold} skeleton alongside the exact form so that the two passes
 * a match needs never recompute it, and so that {@link #hasFuzzy()} can settle in one place
 * whether the fuzzy pass is worth running at all.
 */
public final class Query {

    private final String exact;
    private final String fuzzy;

    private Query(String exact, String fuzzy) {
        this.exact = exact;
        this.fuzzy = fuzzy;
    }

    /**
     * Builds a query from raw typed text.
     *
     * @return null when the text has nothing left to match on once normalized
     */
    public static Query of(String raw) {
        String exact = SearchNormalizer.normalize(raw);
        return exact.isEmpty() ? null : ofNormalized(exact);
    }

    /** As {@link #of}, for text that has already been through the normalizer. */
    static Query ofNormalized(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        String folded = HebrewFold.of(normalized);
        return new Query(normalized,
                folded.length() >= HebrewFold.MIN_QUERY_LENGTH ? folded : null);
    }

    /** The query as typed, normalized. */
    public String exact() {
        return exact;
    }

    /**
     * Whether a fuzzy pass is worth running for this query.
     *
     * <p>False for anything whose skeleton is too short to be evidence — see
     * {@link HebrewFold#MIN_QUERY_LENGTH}. Note that a skeleton identical to the exact form
     * is <em>not</em> a reason to skip it: "eroca" folds to itself and still has to reach
     * "erroca", whose own skeleton is what moved.
     */
    public boolean hasFuzzy() {
        return fuzzy != null;
    }

    /** The spelling skeleton, or null when {@link #hasFuzzy()} is false. */
    public String fuzzy() {
        return fuzzy;
    }

    /**
     * True when this query is anywhere in a haystack, literally or by skeleton.
     *
     * <p>For callers that only need a yes or no — filtering a purchase history, narrowing a
     * card-type menu — and so have no use for {@link Store#match}'s answer about <em>which</em>
     * spelling replied or how strongly.
     *
     * @param normalizedHaystack text through {@link SearchNormalizer#normalize}
     * @param foldedHaystack     the same text through {@link HebrewFold}, folded once by the
     *                           caller rather than on every keystroke
     */
    public boolean hits(String normalizedHaystack, String foldedHaystack) {
        // Through the same grading everything else uses, rather than a bare contains: the
        // fuzzy tier refuses a skeleton buried mid-word, and a screen that quietly did not
        // would fill a purchase history with matches the search screen would never show.
        return Store.tier(normalizedHaystack, exact, false) > MatchScore.NONE
                || (fuzzy != null
                        && Store.tier(foldedHaystack, fuzzy, true) > MatchScore.NONE);
    }

    @Override
    public String toString() {
        return exact;
    }
}
