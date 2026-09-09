package com.mycards.cards;

/**
 * Which of the wallet's colour schemes a card wears.
 *
 * <p>A wallet where every card is the same grey rectangle is a wallet you have to read.
 * Giving each issuer its own face is what lets someone pick the right card out of six while
 * a queue waits behind them — the same reason Google Wallet gives every pass a brand colour
 * and Apple Wallet gives every card a distinct fill.
 *
 * <p>The catalog carries no brand colours, so the face is derived from the card type's id
 * instead. That has two properties worth stating, because both are load-bearing:
 *
 * <ul>
 *   <li><b>Stable.</b> The same id always lands on the same face, on every device and every
 *       launch, with no stored column. A card that changed colour between launches would be
 *       worse than no colour at all — recognition is the entire point.
 *   <li><b>Spread.</b> The hash below is FNV-1a followed by a MurmurHash3 avalanche step.
 *       Both halves are needed and the second one was not obvious: FNV-1a alone put two of
 *       the three demo cards on the same face and left one of the eight unused entirely,
 *       because the catalog's ids are short ASCII strings sharing long prefixes
 *       ("buyme_all", "buyme_foody") and FNV mixes its <em>low</em> bits weakly — which are
 *       exactly the bits a {@code % 8} keeps. That is the case that matters here: cards
 *       likely to sit together in one wallet were the ones likely to come out identical.
 *       Finalising spreads the high bits down and uses all eight faces over the real
 *       catalog.
 * </ul>
 *
 * <p>Faces are held as an enum rather than raw colour ints so the palette lives in
 * resources, stays themeable per night mode, and can be asserted over in a unit test
 * without an Android runtime.
 */
public enum CardFace {

    INDIGO,
    TEAL,
    PLUM,
    ROSE,
    AMBER,
    FOREST,
    SLATE,
    MAGENTA;

    private static final CardFace[] FACES = values();

    /** FNV-1a 32-bit constants. */
    private static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    /**
     * The face for a card type.
     *
     * @param cardTypeId the catalog id; null or blank yields {@link #SLATE}, the most
     *                   neutral face, so a card with no type still looks deliberate
     */
    public static CardFace of(String cardTypeId) {
        if (cardTypeId == null) {
            return SLATE;
        }
        String key = cardTypeId.trim();
        if (key.isEmpty()) {
            return SLATE;
        }

        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= FNV_PRIME;
        }

        // Masked rather than Math.abs: abs(Integer.MIN_VALUE) is still negative, which
        // would throw on the array access for one unlucky id in four billion.
        return FACES[(avalanche(hash) & 0x7FFFFFFF) % FACES.length];
    }

    /**
     * MurmurHash3's fmix32 finaliser: pulls the well-mixed high bits down into the low ones
     * that {@code % FACES.length} actually reads. See the class comment for why FNV-1a on
     * its own is not enough for these particular ids.
     */
    private static int avalanche(int hash) {
        hash ^= hash >>> 16;
        hash *= 0x85EBCA6B;
        hash ^= hash >>> 13;
        hash *= 0xC2B2AE35;
        hash ^= hash >>> 16;
        return hash;
    }

    /**
     * How much of the card is left, as a fraction, for the depletion bar.
     *
     * <p>Clamped to 0..1. A card can read as more than its initial amount when the starting
     * figure was corrected downward after purchases were logged, and a progress bar past
     * full looks broken rather than lucky.
     *
     * @return 0 when the initial amount is unknown or not positive, since "spent nothing of
     *         nothing" is the only honest answer and draws an empty track
     */
    public static float remainingFraction(double remaining, double initialAmount) {
        if (initialAmount <= 0d || Double.isNaN(initialAmount) || Double.isNaN(remaining)) {
            return 0f;
        }
        double fraction = remaining / initialAmount;
        if (fraction <= 0d) {
            return 0f;
        }
        if (fraction >= 1d) {
            return 1f;
        }
        return (float) fraction;
    }
}
