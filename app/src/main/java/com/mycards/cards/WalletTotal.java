package com.mycards.cards;

/**
 * What the wallet is worth right now, and across how many cards.
 *
 * <p>Checking a balance is the commonest reason a wallet app gets opened at all, and until
 * this existed the app could only answer it by making you add the cards up by eye.
 *
 * <p>Two decisions are worth stating, because both are about what the number means rather
 * than how it is computed:
 *
 * <ul>
 *   <li><b>Retired cards are excluded.</b> The figure is money you could spend right now.
 *       Counting an archived or lapsed card would inflate it with money that cannot be
 *       spent, which is the one way a balance can actively mislead — and this app is used
 *       standing at a till.
 *   <li><b>It is not the search result's total.</b> It is built from the whole wallet, so
 *       typing into the search box narrows the list without appearing to drain the wallet.
 * </ul>
 *
 * <p>Deliberately free of Android and Room types: it takes a balance and a status, so it can
 * be tested without a device and reused by anything that holds cards.
 */
public final class WalletTotal {

    private double amount;
    private int cardCount;

    /**
     * Folds one card in.
     *
     * <p>A retired card is skipped whatever is left on it, and so is a negative balance — a
     * card cannot owe money, and letting one subtract would make the wallet total quietly
     * disagree with the cards on screen.
     */
    public void add(double remaining, CardStatus status) {
        if (status != null && status.isRetired()) {
            return;
        }
        if (Double.isNaN(remaining) || remaining <= 0d) {
            return;
        }
        amount += remaining;
        cardCount++;
    }

    public double amount() {
        return amount;
    }

    /** How many cards contributed — not how many the wallet holds. */
    public int cardCount() {
        return cardCount;
    }

    /**
     * True when there is nothing spendable at all.
     *
     * <p>Keyed on the count rather than the amount, so a wallet holding only cards that have
     * run down to nothing still reads as empty rather than as "₪0 across 3 cards".
     */
    public boolean isEmpty() {
        return cardCount == 0;
    }
}
