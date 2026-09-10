package com.mycards.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds the custom-colour maths to the palette that is already shipping.
 *
 * <p>The first test is the load-bearing one. {@link FaceGradient} claims its gradient rule is
 * not an approximation of the eight faces in {@code values/colors.xml} but the rule they were
 * drawn with — so if that is true, feeding it a shipped start stop must reproduce the shipped
 * end stop to the byte, for all eight. If a face is ever restyled without this being updated,
 * this test is what says so.
 */
public class FaceGradientTest {

    /** The eight faces as they appear in values/colors.xml: {start, end}. */
    private static final int[][] SHIPPED_FACES = {
            {0xFF3B5BC0, 0xFF2A428A}, // indigo
            {0xFF0F746E, 0xFF0B544F}, // teal
            {0xFF7A3E96, 0xFF582D6C}, // plum
            {0xFFB4325C, 0xFF822442}, // rose
            {0xFF96590C, 0xFF6C4009}, // amber
            {0xFF2C754B, 0xFF205436}, // forest
            {0xFF4A5A78, 0xFF354156}, // slate
            {0xFF9C2F76, 0xFF702255}, // magenta
    };

    @Test
    public void reproducesEveryShippedFacesEndStopExactly() {
        for (int[] face : SHIPPED_FACES) {
            assertEquals(
                    "end stop for " + hex(face[0]),
                    hex(face[1]),
                    hex(FaceGradient.endStop(face[0])));
        }
    }

    /**
     * A colour picked out of the app's own palette must come back untouched. Nudging a preset
     * by a shade would mean the "indigo" a user chooses is not the indigo the card types use.
     */
    @Test
    public void leavesTheShippedFacesAloneWhenMakingThemLegible() {
        for (int[] face : SHIPPED_FACES) {
            assertEquals(hex(face[0]), hex(FaceGradient.legible(face[0])));
        }
    }

    @Test
    public void darkensAColourTooPaleToCarryWhiteText() {
        int paleYellow = 0xFFFFE066;
        assertTrue("the premise: this colour cannot carry white text",
                FaceGradient.contrastWithWhite(paleYellow) < FaceGradient.MIN_CONTRAST);

        int fixed = FaceGradient.legible(paleYellow);
        assertNotEquals(hex(paleYellow), hex(fixed));
        assertTrue("white must be readable on the result",
                FaceGradient.isLegible(fixed));
    }

    /**
     * Only as far as it has to go. A colour dragged all the way to black would be a picker
     * that ignores the choice rather than one that corrects it.
     */
    @Test
    public void darkensNoFurtherThanItMust() {
        int fixed = FaceGradient.legible(0xFFFFE066);
        // One step back towards the original must fail, or it went too far.
        int lighter = FaceGradient.argb(0xFF,
                (int) Math.round(FaceGradient.red(fixed) / 0.98d),
                (int) Math.round(FaceGradient.green(fixed) / 0.98d),
                (int) Math.round(FaceGradient.blue(fixed) / 0.98d));
        assertTrue("stopped a step short of the lightest legible shade",
                FaceGradient.contrastWithWhite(lighter) < FaceGradient.MIN_CONTRAST);
    }

    /** White itself is the worst case, and the one that would loop forever if it could. */
    @Test
    public void terminatesOnWhite() {
        assertTrue(FaceGradient.isLegible(FaceGradient.legible(0xFFFFFFFF)));
    }

    /** Black is already past the bar and has nowhere left to go. */
    @Test
    public void leavesBlackAlone() {
        assertEquals(hex(0xFF000000), hex(FaceGradient.legible(0xFF000000)));
        assertEquals(hex(0xFF000000), hex(FaceGradient.endStop(0xFF000000)));
    }

    @Test
    public void knowsWhiteOnWhiteIsUnreadable() {
        assertEquals(1.0d, FaceGradient.contrastWithWhite(0xFFFFFFFF), 0.001d);
        assertEquals(21.0d, FaceGradient.contrastWithWhite(0xFF000000), 0.05d);
    }

    private static String hex(int color) {
        return String.format("#%08X", color);
    }
}
