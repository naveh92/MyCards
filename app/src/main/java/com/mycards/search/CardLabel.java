package com.mycards.search;

import java.util.List;

/**
 * The name its owner gave one card, prepared for matching.
 *
 * <p>Everything else the search engine knows about is shared: a card <em>type</em> and the
 * merchants it covers are the same for everyone holding that card. A label is not. "Holiday
 * gift 2026" exists on exactly one card, on one phone, because somebody typed it — which
 * makes it the most specific thing in the wallet and, until now, the only name in the app
 * that searching could not find.
 *
 * <p>That gap was invisible in a way worth recording: the card is on the screen with its
 * label as the heading, you type the word you are looking at, and the app answers "no card of
 * yours is accepted at that". Nothing suggests the field simply never looked there.
 *
 * <p>Labels are matched per card rather than per card type, so this deliberately holds one
 * label and not a collection. Not to be confused with {@code ui.search.StoreLabel}, which is
 * a display type for a merchant's name.
 */
public final class CardLabel {

    /** A card with no label of its own; matches nothing. */
    public static final CardLabel NONE = new CardLabel("", "");

    private final String normalized;
    private final String folded;

    private CardLabel(String normalized, String folded) {
        this.normalized = normalized;
        this.folded = folded;
    }

    /**
     * Prepares a label for matching.
     *
     * @param label the user's own text, or null/blank for a card they never named
     */
    public static CardLabel of(String label) {
        String normalized = SearchNormalizer.normalize(label);
        if (normalized.isEmpty()) {
            return NONE;
        }
        return new CardLabel(normalized, HebrewFold.of(normalized));
    }

    public boolean isEmpty() {
        return normalized.isEmpty();
    }

    /**
     * Grades the label against every spelling of what was typed.
     *
     * <p>The winning tier carries {@link MatchScore#CARD_NAME_BONUS}, for the same reason the
     * card type's own name does: naming a card is a request for <em>that card</em>, not for a
     * shop that happens to share a word with it. A label earns it at least as honestly — the
     * user chose the words themselves, so typing them back is about as unambiguous as intent
     * gets.
     *
     * @param variants the query's spellings, from {@code SearchEngine.queryVariants}
     * @return a score comparable with a {@link CardMatch}'s, or {@link MatchScore#NONE}
     */
    public int score(List<Query> variants) {
        int best = bestTier(variants);
        return best == MatchScore.NONE ? MatchScore.NONE : best + MatchScore.CARD_NAME_BONUS;
    }

    /**
     * True when the query <em>is</em> this card's name, or the start of it.
     *
     * <p>Narrower than {@link #score} on purpose, and the difference decides what a row says
     * about itself. Typing a card's name is a request for that card, so the row answers with
     * what the card covers — "748 stores" — rather than naming a shop nobody asked about.
     * Finding the query buried inside the name is not that request: searching "c" matches
     * "Dinner voucher" through the middle of a word, and answering a one-letter query with a
     * coverage count throws away the fifteen hundred shops that also matched it, on the
     * strength of a letter.
     *
     * <p>A fuzzy hit counts. {@code Store.tier} never reports a buried skeleton — see
     * {@link MatchScore#FUZZY_PREFIX} — so any fuzzy match here is already whole-or-opening,
     * which is the same claim on the card's identity spelled the other way.
     */
    public boolean namesTheCard(List<Query> variants) {
        return MatchScore.names(bestTier(variants));
    }

    /** The best untiered match across every spelling, before the card-name bonus. */
    private int bestTier(List<Query> variants) {
        if (normalized.isEmpty() || variants == null) {
            return MatchScore.NONE;
        }

        int best = MatchScore.NONE;
        for (Query variant : variants) {
            best = Math.max(best, Store.tier(normalized, variant.exact(), false));
            if (variant.hasFuzzy()) {
                // Same fallback the merchants get, so a label typed with the other spelling
                // of a Hebrew name still finds its card.
                best = Math.max(best, Store.tier(folded, variant.fuzzy(), true));
            }
        }
        return best;
    }
}
