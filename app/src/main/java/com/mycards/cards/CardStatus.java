package com.mycards.cards;

/**
 * Whether a card is still worth carrying, and if not, why.
 *
 * <p>Only one of these states is written down. {@link #EMPTY} and {@link #EXPIRED} are
 * worked out from the balance and the expiry every time they are asked for, so a card comes
 * back on its own the moment the reason goes away — delete the purchase that emptied it, or
 * correct an expiry typed in wrong, and it is simply active again. Storing them would mean
 * every one of those edits had to remember to also fix a flag, and the day one of them
 * forgot, the wallet would be lying about which cards can be spent.
 *
 * <p>{@link #ARCHIVED} cannot be derived from anything, because it is not a fact about the
 * card — it is a decision about it. That one is stored.
 *
 * <p>Deliberately absent: any rule about small balances. A card with ₪4 on it holds ₪4, which
 * is spendable at any counter that will take part-payment, and an app that quietly filed it
 * away would be hiding its owner's money. Whether ₪4 is worth carrying is a judgement only
 * the owner can make — which is what {@link #ARCHIVED} is for.
 */
public enum CardStatus {

    /** In use, and offered as an answer at a checkout. */
    ACTIVE,

    /** The user put it away by hand. */
    ARCHIVED,

    /** Nothing left to spend. */
    EMPTY,

    /** Past the end of its expiry month. */
    EXPIRED;

    /**
     * Below this many units of currency a card counts as empty.
     *
     * <p>Balances are doubles summed from a list of purchases, so a card spent exactly to
     * zero routinely lands on 0.000000001 or -0.000000002 instead. Half an agora is far below
     * anything that can be entered or displayed, and well above the error a few dozen
     * additions can accumulate.
     */
    private static final double NOTHING_LEFT = 0.005d;

    /**
     * Works out a card's state.
     *
     * <p>The order of the tests is the order of the answers' usefulness, not their severity.
     * A card can easily be all three at once — put away, empty, and long expired — and the
     * one worth showing is the one that explains what happened to it. "Empty" says the money
     * was spent; "Expired" says it was lost. Reporting expiry for a card that was emptied
     * months before it lapsed would invent a regret that never happened.
     *
     * @param remaining        what is left on the card
     * @param daysUntilExpiry  as {@code Formats.daysUntil} reports it, so
     *                         {@link Long#MAX_VALUE} means the card has no expiry at all
     * @param archivedAt       epoch millis the user archived it, or 0
     */
    public static CardStatus of(double remaining, long daysUntilExpiry, long archivedAt) {
        if (archivedAt > 0L) {
            return ARCHIVED;
        }
        if (remaining < NOTHING_LEFT) {
            return EMPTY;
        }
        if (daysUntilExpiry != Long.MAX_VALUE && daysUntilExpiry < 0L) {
            return EXPIRED;
        }
        return ACTIVE;
    }

    /** True for every state that takes a card out of the main wallet list. */
    public boolean isRetired() {
        return this != ACTIVE;
    }
}
