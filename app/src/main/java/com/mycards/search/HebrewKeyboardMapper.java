package com.mycards.search;

/**
 * Translates text between the Hebrew and US-QWERTY keyboard layouts.
 *
 * <p>Israeli users switch layouts constantly and frequently type a word in the wrong one:
 * reaching for "adidas" while the keyboard is still in Hebrew produces "שגןגשד", and typing
 * "אדידס" on an English layout produces "tshsx". Both should still find adidas.
 *
 * <p>The mapping below was verified against BuyMe's own {@code searchTerms} data, which
 * ships exactly these mangled spellings as real aliases — "tshsx" (אדידס) and "rhcue"
 * (ריבוק) both round-trip correctly through this table.
 */
public final class HebrewKeyboardMapper {

    private HebrewKeyboardMapper() {
    }

    /** US-QWERTY keys in the order their Hebrew counterparts appear below. */
    private static final String EN_KEYS = "qwertyuiopasdfghjkl;'zxcvbnm,./";

    /** The Hebrew letter produced by each key at the same index in {@link #EN_KEYS}. */
    private static final String HE_KEYS = "/'קראטוןםפשדגכעיחלךף,זסבהנמצתץ.";

    /** Converts a string typed on an English layout into the Hebrew letters it would produce. */
    public static String enToHe(String input) {
        return map(input, EN_KEYS, HE_KEYS);
    }

    /** Converts a string typed on a Hebrew layout into the English letters it would produce. */
    public static String heToEn(String input) {
        return map(input, HE_KEYS, EN_KEYS);
    }

    private static String map(String input, String from, String to) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        boolean changedAny = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int idx = from.indexOf(c);
            if (idx >= 0) {
                out.append(to.charAt(idx));
                changedAny = true;
            } else {
                // Digits and unmapped characters sit in the same place on both layouts.
                out.append(c);
            }
        }
        // Nothing was in the source alphabet, so this transliteration is meaningless.
        return changedAny ? out.toString() : "";
    }
}
