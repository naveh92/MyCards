package com.mycards.search;

/**
 * Relevance tiers, ordered so that a whole-name hit outranks a mid-word fragment.
 *
 * <p>Searching "all" should surface a card literally named "BuyMe All" above a card that
 * merely accepts a store with "all" buried inside its name.
 */
public final class MatchScore {

    private MatchScore() {
    }

    public static final int NONE = 0;

    /**
     * The fuzzy band: the query matched only after {@link HebrewFold} collapsed both sides
     * to a spelling skeleton.
     *
     * <p>A whole band below {@link #SUBSTRING} rather than a notch, and that is the point.
     * A skeleton hit says "these could be the same word spelled two ways", which is worth
     * showing and never worth showing first — every literal match in the wallet, down to a
     * two-letter fragment buried mid-word, is better evidence than the best fuzzy one.
     *
     * <p>There is deliberately no fuzzy counterpart to {@link #SUBSTRING}. A skeleton is
     * lossy enough that finding one buried inside a longer skeleton means very little:
     * "מארזי אווירה" folds to something containing the fold of "זארה", and answering a search
     * for Zara with it is worse than answering with nothing. The fuzzy tier matches whole
     * skeletons and skeleton prefixes only.
     */
    public static final int FUZZY_PREFIX = 15;

    public static final int FUZZY_EXACT = 20;

    /** The query is buried somewhere inside the name, e.g. "za" in "pizza". */
    public static final int SUBSTRING = 50;

    /** The name begins with the query, e.g. "za" in "zara". */
    public static final int PREFIX = 75;

    /** The query is the whole name. */
    public static final int EXACT = 100;

    /**
     * Matching the card's own name is worth more than matching one of the hundreds of
     * stores it covers — typing "buyme" means "show me my BuyMe card", not "show me every
     * card that happens to include a shop called BuyMe".
     */
    public static final int CARD_NAME_BONUS = 200;
}
