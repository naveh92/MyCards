package com.mycards.search;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Collapses text to a canonical form so that human-typed queries match stored names
 * regardless of case, punctuation, spacing, Hebrew vowel points or final-letter forms.
 *
 * <p>The guarantee this class exists to provide: {@code "Buy Me All"}, {@code "buy-me"},
 * {@code "buyme"} and {@code "BUY ME ALL"} all reduce to forms where the shorter is a
 * substring of the longer, so a partial query finds the card.
 *
 * <p>Pure JDK on purpose — no Android imports — so the matching rules can be unit tested
 * on a plain JVM.
 */
public final class SearchNormalizer {

    private SearchNormalizer() {
    }

    /**
     * Hebrew letters that take a different glyph at the end of a word. A user typing a
     * fragment mid-word ("מנ" for "מנחם") would otherwise miss, so both forms fold together.
     */
    private static char foldHebrewFinal(char c) {
        switch (c) {
            case 'ך': return 'כ';
            case 'ם': return 'מ';
            case 'ן': return 'נ';
            case 'ף': return 'פ';
            case 'ץ': return 'צ';
            default:  return c;
        }
    }

    /**
     * Reduces a string to lowercase alphanumerics only, with diacritics and Hebrew
     * niqqud/cantillation removed and Hebrew final letters folded.
     *
     * @return the canonical form, or an empty string for null/blank input
     */
    public static String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        // NFD splits "é" into "e" + combining accent, and detaches Hebrew points from
        // their base letter, so both can be dropped as non-spacing marks below.
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);

        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);

            // Drops Latin accents plus the whole Hebrew niqqud/te'amim range (U+0591..U+05BD,
            // U+05C1..U+05C7), all of which are classified as non-spacing marks.
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }

            c = foldHebrewFinal(c);

            // Everything else that is not a letter or digit — spaces, hyphens, maqaf,
            // geresh, apostrophes, ampersands, punctuation — is discarded outright.
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }

        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** True when {@code haystack} is non-empty and already-normalized text contains the needle. */
    public static boolean containsNormalized(String normalizedHaystack, String normalizedNeedle) {
        return !normalizedNeedle.isEmpty()
                && !normalizedHaystack.isEmpty()
                && normalizedHaystack.contains(normalizedNeedle);
    }

    /**
     * True when a character could gain or lose characters under canonical decomposition.
     *
     * <p>Nothing below U+00C0 has a canonical decomposition, and neither does any Hebrew
     * letter or point. Between them that covers virtually every character in this app's
     * data, which lets {@link #normalizeWithSource} skip decomposing them one at a time.
     * Hebrew presentation forms (U+FB1D and up) do decompose and sit above the range, so
     * they still take the slow path.
     */
    private static boolean mayDecompose(char c) {
        return c >= 0x00C0 && !(c >= 0x0590 && c <= 0x05FF);
    }

    /**
     * A normalized string that still knows where each of its characters came from.
     *
     * <p>{@link #normalize} discards that, which is all matching needs — but not what
     * showing someone <em>which part</em> of a shop's name they just typed needs. Every
     * offset shifts during normalization: "מסעדת Moshik&amp;" loses a space and an ampersand
     * on the way, so a hit at normalized position 5 cannot be bolded without a way back.
     */
    public static final class Normalized {

        /** The canonical form, as {@link #normalize} would produce it. */
        public final String text;

        /** For each character of {@link #text}, the index it came from in the source. */
        private final int[] source;

        private Normalized(String text, int[] source) {
            this.text = text;
            this.source = source;
        }

        /** Where the normalized character at {@code index} started in the source string. */
        public int sourceStart(int index) {
            return source[index];
        }

        /**
         * Where the normalized character at {@code index} ended in the source string.
         *
         * <p>One past its own source index rather than the start of the next character:
         * punctuation dropped in between belongs to neither, and stretching the span across
         * it would bold a trailing space or hyphen.
         */
        public int sourceEnd(int index) {
            return source[index] + 1;
        }
    }

    /**
     * Normalizes as {@link #normalize} does, while recording where every surviving
     * character came from.
     *
     * <p>Deliberately a second implementation rather than the one everything shares:
     * {@link #normalize} runs against every alias of every merchant whenever an index is
     * built, and should not be paying for an {@code int[]} nothing there reads. This one is
     * called only for the handful of rows actually on screen.
     *
     * <p>Decomposition happens per source character rather than over the whole string, so
     * that an index survives it. That is sound: canonical decomposition is defined character
     * by character, and the one thing whole-string normalization adds — putting combining
     * marks into canonical order — cannot change a result that drops every combining mark
     * regardless.
     */
    public static Normalized normalizeWithSource(String input) {
        if (input == null || input.isEmpty()) {
            return new Normalized("", new int[0]);
        }

        StringBuilder out = new StringBuilder(input.length());
        int[] source = new int[input.length()];
        int kept = 0;

        for (int i = 0; i < input.length(); i++) {
            char raw = input.charAt(i);
            String expanded = mayDecompose(raw)
                    ? Normalizer.normalize(String.valueOf(raw), Normalizer.Form.NFD)
                    : null;
            int width = expanded == null ? 1 : expanded.length();

            for (int j = 0; j < width; j++) {
                char c = expanded == null ? raw : expanded.charAt(j);
                if (Character.getType(c) == Character.NON_SPACING_MARK) {
                    continue;
                }
                c = foldHebrewFinal(c);
                if (!Character.isLetterOrDigit(c)) {
                    continue;
                }
                if (kept == source.length) {
                    source = Arrays.copyOf(source, source.length * 2);
                }
                out.append(Character.toLowerCase(c));
                source[kept++] = i;
            }
        }

        return new Normalized(out.toString(), Arrays.copyOf(source, kept));
    }
}
