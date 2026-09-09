package com.mycards.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

public class CardFaceTest {

    /**
     * Every card type in the shipped catalog, so the distribution assertions below are
     * about the ids the app actually meets rather than invented ones.
     */
    private static final String[] CATALOG_IDS = {
            "buyme_all", "buyme_baby", "buyme_local", "buyme_yng", "buyme_together",
            "buyme_foody", "buyme_fashion_beauty", "buyme_style", "buyme_vacation_spa",
            "buyme_wellness", "azrieli", "buyme_rishon", "buyme_home_design",
            "buyme_ramat_gan", "buyme_chef", "buyme_business", "buyme_brunch",
            "buyme_cheers", "buyme_kosher", "buyme_pets", "buyme_live", "all_in_zone",
            "superzone", "giftzone", "chefzone", "spazone", "love_gift_card", "max_gift",
            "max_super_gift", "tav_hazahav", "hatav_hamale", "tav_plus",
    };

    /**
     * The property the whole idea rests on. A card that changes colour between launches
     * teaches the user not to rely on colour, which is worse than never having used it.
     */
    @Test
    public void theSameIdAlwaysGetsTheSameFace() {
        for (String id : CATALOG_IDS) {
            assertEquals(CardFace.of(id), CardFace.of(id));
        }
    }

    /**
     * Pins the mapping to exact values. Without this the test above passes happily while a
     * refactor silently repaints everyone's wallet — stable-within-a-run is not the same
     * property as stable-across-versions, and it is the second one users notice.
     */
    @Test
    public void theMappingIsPinnedAcrossVersions() {
        assertEquals(CardFace.SLATE, CardFace.of("buyme_all"));
        assertEquals(CardFace.FOREST, CardFace.of("all_in_zone"));
        assertEquals(CardFace.PLUM, CardFace.of("love_gift_card"));
    }

    /**
     * The regression that prompted the avalanche step: plain FNV-1a sent both of these to
     * the same face, and they are the two cards most likely to share a wallet.
     */
    @Test
    public void idsSharingALongPrefixDoNotCollide() {
        assertNotEquals(CardFace.of("buyme_all"), CardFace.of("buyme_foody"));
        assertNotEquals(CardFace.of("max_gift"), CardFace.of("max_super_gift"));
        assertNotEquals(CardFace.of("superzone"), CardFace.of("giftzone"));
    }

    @Test
    public void everyFaceIsUsedByTheRealCatalog() {
        Set<CardFace> seen = EnumSet.noneOf(CardFace.class);
        for (String id : CATALOG_IDS) {
            seen.add(CardFace.of(id));
        }
        assertEquals("every face should earn its place in the palette",
                EnumSet.allOf(CardFace.class), seen);
    }

    /** No id should ever land outside the palette, whatever it contains. */
    @Test
    public void oddIdsStillProduceAFace() {
        String[] odd = {"", "   ", "כרטיס", "!!!", "a",
                "an_extremely_long_identifier_that_nobody_would_ever_actually_write_but_here_it_is"};
        for (String id : odd) {
            assertTrue(id + " produced no face", CardFace.of(id) != null);
        }
    }

    @Test
    public void aMissingCardTypeFallsBackToTheNeutralFace() {
        assertEquals(CardFace.SLATE, CardFace.of(null));
        assertEquals(CardFace.SLATE, CardFace.of(""));
        assertEquals(CardFace.SLATE, CardFace.of("   "));
    }

    @Test
    public void surroundingWhitespaceDoesNotChangeTheFace() {
        assertEquals(CardFace.of("buyme_all"), CardFace.of("  buyme_all  "));
    }

    // --- remainingFraction ---

    @Test
    public void aFullCardIsAFullBar() {
        assertEquals(1f, CardFace.remainingFraction(700d, 700d), 0.0001f);
    }

    @Test
    public void aPartlySpentCardIsTheFractionLeft() {
        assertEquals(0.36f, CardFace.remainingFraction(252d, 700d), 0.0001f);
    }

    @Test
    public void anEmptyCardDrawsNothing() {
        assertEquals(0f, CardFace.remainingFraction(0d, 700d), 0.0001f);
    }

    /**
     * The starting figure can be corrected downward after purchases are already logged,
     * which leaves more on the card than it supposedly began with. A bar drawn past its own
     * end looks broken rather than lucky.
     */
    @Test
    public void moreLeftThanTheCardStartedWithIsClampedToFull() {
        assertEquals(1f, CardFace.remainingFraction(900d, 700d), 0.0001f);
    }

    /** A negative balance is a data fault, not a reason to draw a bar backwards. */
    @Test
    public void aNegativeBalanceDrawsAnEmptyBar() {
        assertEquals(0f, CardFace.remainingFraction(-50d, 700d), 0.0001f);
    }

    /**
     * Cards added without a starting amount are common — you often only know what is left.
     * "Spent nothing of nothing" is the only honest answer, and it draws an empty track.
     */
    @Test
    public void anUnknownStartingAmountDrawsNothing() {
        assertEquals(0f, CardFace.remainingFraction(252d, 0d), 0.0001f);
        assertEquals(0f, CardFace.remainingFraction(252d, -1d), 0.0001f);
    }

    @Test
    public void notANumberDoesNotEscapeAsABarWidth() {
        assertEquals(0f, CardFace.remainingFraction(Double.NaN, 700d), 0.0001f);
        assertEquals(0f, CardFace.remainingFraction(252d, Double.NaN), 0.0001f);
    }
}
