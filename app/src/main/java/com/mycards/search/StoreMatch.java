package com.mycards.search;

/**
 * A merchant that matched, and which of its spellings did the matching.
 *
 * <p>The spelling is the whole point. A merchant list carries a shop under one name and
 * finds it under a dozen — Carolina Lemke is on the BuyMe list as "CAROLINA LEMKE" and again
 * inside "מגוון מותגי קבוצת קסטרו הודיס", which lists קרולינה among the brands it stands for.
 * Both are correct answers to a query for קרולינה and only one of them looks like one, until
 * the row is allowed to say which words it read.
 */
public final class StoreMatch {

    private final Store store;
    private final int score;
    private final int formIndex;
    private final boolean fuzzy;
    private final int slack;

    StoreMatch(Store store, int score, int formIndex, boolean fuzzy, int slack) {
        this.store = store;
        this.score = score;
        this.formIndex = formIndex;
        this.fuzzy = fuzzy;
        this.slack = slack;
    }

    StoreMatch(Store store, int score, int formIndex, boolean fuzzy) {
        this(store, score, formIndex, fuzzy, 0);
    }

    public Store getStore() {
        return store;
    }

    public String getName() {
        return store.getName();
    }

    public int getScore() {
        return score;
    }

    /** True when the merchant's own name is what matched, rather than one of its aliases. */
    public boolean isByName() {
        return formIndex == Store.NAME_FORM;
    }

    /**
     * The spelling that matched, as written.
     *
     * <p>Equal to {@link #getName()} when {@link #isByName()}; otherwise the alias, which is
     * what a row shows to explain itself.
     */
    public String getMatchedForm() {
        return store.getForm(formIndex);
    }

    /** True when only the {@link HebrewFold} skeletons matched, not the text itself. */
    public boolean isFuzzy() {
        return fuzzy;
    }

    /**
     * Ranks two matches of the same merchant list.
     *
     * <p>A hit on the shop's own name comes before a hit on one of its search terms, ahead of
     * the score — searching "cafe" otherwise puts three restaurants tagged with the word above
     * the one actually called Cafe Mayer, every row correct and the list still wrong.
     */
    public static int compare(StoreMatch a, StoreMatch b) {
        // Which band the match is in comes before everything, name hits included. A shop
        // whose own name merely resembles the query is not a better answer than one whose
        // alias literally is it — that ordering put "ג'קו סטריט" above CASTRO for a search
        // for קסטרו, on the strength of a skeleton.
        if (a.fuzzy != b.fuzzy) {
            return a.fuzzy ? 1 : -1;
        }
        if (a.isByName() != b.isByName()) {
            return a.isByName() ? -1 : 1;
        }
        if (a.score != b.score) {
            return Integer.compare(b.score, a.score);
        }
        // Inside the fuzzy band the score has run out of things to say: skeletons are equal
        // by construction, so every candidate ties. Searching "ארוקה" reaches אירוקה, אירוכה
        // and אאוריקה, all of which fold to the same four letters. What separates them is how
        // far each strayed from what was actually typed, counted in edits — one inserted yod
        // for אירוקה, that plus a kuf/kaf swap for אירוכה — so the closest spelling leads.
        if (a.fuzzy && b.fuzzy) {
            return Integer.compare(a.slack, b.slack);
        }
        return 0;
    }
}
