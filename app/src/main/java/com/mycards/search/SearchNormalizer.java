package com.mycards.search;

import java.text.Normalizer;
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
}
