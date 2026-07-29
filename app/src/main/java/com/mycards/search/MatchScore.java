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
