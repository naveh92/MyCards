package com.mycards.cards;

/**
 * Builds a card face out of any single colour, the way the eight built-in ones are built.
 *
 * <p>A wallet where the colours are assigned by a hash is a wallet whose colours are nobody's
 * choice. This is the other half: a card can be given a colour by hand, and it still has to
 * come out looking like it belongs beside the eight in {@code values/colors.xml} rather than
 * like a swatch someone dropped on the screen.
 *
 * <p>Two rules do that, and neither was invented here — both were read back off the existing
 * palette:
 *
 * <ul>
 *   <li><b>The gradient.</b> Every one of the eight faces has an end stop that is its start
 *       stop with each channel multiplied by {@value #END_STOP_SCALE} and rounded. All eight,
 *       all three channels, to the byte. So a custom face is not "a gradient in roughly the
 *       same spirit" — it is the same construction, and a hand-picked indigo lands on exactly
 *       the drawable the built-in indigo uses.
 *   <li><b>The contrast.</b> Every face carries white text, and the palette's own note records
 *       that the alpha of {@code on_face_variant} was chosen as the softest that still clears
 *       WCAG AA on the darkest of them. That makes the darkest face a floor, not an accident:
 *       a lighter custom colour would quietly break the secondary text on every card wearing
 *       it. So a chosen colour is darkened until it reaches {@value #MIN_CONTRAST}:1 against
 *       white, which is where {@code face_forest_start} sits.
 * </ul>
 *
 * <p>Free of Android types on purpose, like {@link CardFace}: colours are plain ARGB ints, so
 * all of this is testable on the JVM.
 */
public final class FaceGradient {

    /**
     * What each channel of the start stop is multiplied by to reach the end stop.
     *
     * <p>Measured, not chosen. See the class comment: it reproduces all eight shipped faces
     * exactly.
     */
    public static final double END_STOP_SCALE = 0.72d;

    /**
     * The least contrast a face may have against white, which is what it carries.
     *
     * <p>{@code face_forest_start} measures 5.5952:1 — the darkest of the eight, and so the
     * one the white-at-85% secondary text was tuned against. AA for body text is 4.5:1; the
     * extra margin is what that 85% costs, and the bar is set just under forest so that the
     * darkest shipped face passes rather than being corrected against itself.
     */
    public static final double MIN_CONTRAST = 5.59d;

    private FaceGradient() {
    }

    /** The darker stop that anchors the bottom of a face, given its start stop. */
    public static int endStop(int startStop) {
        return argb(
                0xFF,
                scale(red(startStop)),
                scale(green(startStop)),
                scale(blue(startStop)));
    }

    /**
     * The chosen colour, darkened until white text on it is readable.
     *
     * <p>Returned unchanged when it already clears the bar, which every one of the eight
     * built-in faces does — so a preset picked from the palette is passed through untouched
     * rather than being nudged a shade off the colour it is supposed to be.
     *
     * <p>Darkening is a straight multiply on all three channels, which keeps the hue and the
     * saturation the user picked and only takes the brightness they cannot have. Turning a
     * pale yellow down until white reads on it does change it a lot — but the alternative is
     * a card whose balance cannot be read at a counter, which is the one thing this app
     * exists to do.
     */
    public static int legible(int color) {
        int red = red(color);
        int green = green(color);
        int blue = blue(color);

        // Multiplying towards black converges: pure black is 21:1, so this always ends. The
        // step is small enough that the result is within about a percent of the lightest
        // colour that would have passed, and the loop is bounded regardless.
        for (int step = 0; step < 256; step++) {
            if (contrastWithWhite(argb(0xFF, red, green, blue)) >= MIN_CONTRAST) {
                break;
            }
            red = darker(red);
            green = darker(green);
            blue = darker(blue);
        }
        return argb(0xFF, red, green, blue);
    }

    /** True when white text on this colour is readable, by the bar above. */
    public static boolean isLegible(int color) {
        return contrastWithWhite(color) >= MIN_CONTRAST;
    }

    /** WCAG 2.1 contrast ratio between white and an opaque colour. */
    public static double contrastWithWhite(int color) {
        // White's relative luminance is 1, so the general (L1 + 0.05) / (L2 + 0.05) reduces
        // to this. Alpha is ignored: a face is always drawn opaque.
        return 1.05d / (relativeLuminance(color) + 0.05d);
    }

    /** WCAG 2.1 relative luminance, 0 for black and 1 for white. */
    public static double relativeLuminance(int color) {
        return 0.2126d * linear(red(color))
                + 0.7152d * linear(green(color))
                + 0.0722d * linear(blue(color));
    }

    private static double linear(int channel) {
        double v = channel / 255d;
        return v <= 0.03928d ? v / 12.92d : Math.pow((v + 0.055d) / 1.055d, 2.4d);
    }

    private static int scale(int channel) {
        return (int) Math.round(channel * END_STOP_SCALE);
    }

    /**
     * One step towards black.
     *
     * <p>The {@code - 1} floor matters: {@code round(1 * 0.98)} is 1, so a channel already
     * down at 1 or 2 would multiply to itself forever and a colour that is nearly black in
     * one channel would never converge.
     */
    private static int darker(int channel) {
        int next = (int) Math.round(channel * 0.98d);
        return next >= channel ? Math.max(0, channel - 1) : next;
    }

    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    private static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }
}
