package com.mycards.search;

/**
 * Collapses Hebrew spelling variants that {@link SearchNormalizer} deliberately keeps apart.
 *
 * <p>Normalization gets case, punctuation, niqqud and final letters out of the way, and then
 * matching is exact substring — which is the right default, because it never invents a hit.
 * What it cannot do is bridge the two ways Hebrew is legitimately spelled. Nobody agrees
 * where the optional yod and vav go: Erroca ships as <em>אירוקה</em>, and someone standing in
 * the shop types <em>ארוקה</em>. Neither is a typo, neither contains the other, and today the
 * card that works there simply does not come up.
 *
 * <p>Three rules, each one folding a distinction Hebrew readers do not hear:
 *
 * <ul>
 *   <li><b>Matres lectionis.</b> A yod or vav standing in for a vowel is optional in Hebrew
 *       orthography (<em>כתיב מלא</em> vs <em>כתיב חסר</em>), so both are dropped unless the
 *       word opens with one — <em>אירוקה</em> and <em>ארוקה</em> both become <em>ארקה</em>.
 *   <li><b>Homophones.</b> ק/כ are both /k/ and ט/ת are both /t/. Which letter a transliterated
 *       brand uses is a coin toss, which is why the shipped merchant data already carries
 *       hand-generated variants — <em>כרולינה למקה</em>, <em>קרולינה למכה</em>, <em>מגוון
 *       מוטגי כבוצת קסטרו</em>. This does the same job for every name, including the ones
 *       nobody thought to spell out.
 *       <p>א/ע are homophones too, and are deliberately <em>not</em> folded. They are two of
 *       the commonest letters in the language, and merging them collides far more often than
 *       it rescues: it made <em>ארוקה</em> match <em>ערכה</em>, the ordinary word for a kit,
 *       and handed the top of the Erroca search to a toy shop.
 *   <li><b>Doubled letters.</b> A run of the same letter collapses, which covers Hebrew
 *       <em>וו</em>/<em>יי</em> and, incidentally, Latin doubles: "eroca" reaches "erroca".
 *       Only letters already adjacent before folding, though — two consonants left touching
 *       by a dropped vowel are not a double, and treating them as one reduced
 *       <em>אדידס</em> to <em>אדס</em>, which is also where <em>הודיס</em> lands.
 * </ul>
 *
 * <p>The result is a <em>skeleton</em>, not a name — many words share one, so a hit here is
 * weaker evidence than an exact one and scores in {@link MatchScore}'s fuzzy band, below
 * every literal match. It never replaces exact matching; it only runs when that found
 * nothing.
 *
 * <p>Both folding loops in this package go through {@link Folder}, one character at a time,
 * so the plain form and the offset-preserving one cannot drift apart.
 */
public final class HebrewFold {

    private HebrewFold() {
    }

    /**
     * Below this many characters a skeleton stops being evidence.
     *
     * <p>"ארוקה" folds to four characters and "מותגי" to three, both of which still name
     * something. Two does not: with the vowels gone, a two-letter skeleton is inside a
     * sizeable share of any merchant list, and offering those as answers would spend the
     * precision the exact tier was built for.
     */
    public static final int MIN_QUERY_LENGTH = 3;

    /**
     * Folds an already-normalized string.
     *
     * @param normalized output of {@link SearchNormalizer#normalize}
     * @return the skeleton; the same instance when nothing folded, so callers can compare
     *         by reference to find out whether folding changed anything
     */
    public static String of(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(normalized.length());
        Folder folder = new Folder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = folder.next(normalized.charAt(i));
            if (c != Folder.DROP) {
                out.append(c);
            }
        }
        // Identical output is the common case for Latin names, and returning the original
        // lets Store keep one string where it would otherwise keep two.
        return out.length() == normalized.length() && contentEquals(out, normalized)
                ? normalized
                : out.toString();
    }

    private static boolean contentEquals(StringBuilder a, String b) {
        for (int i = 0; i < b.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The folding rules as a one-character-at-a-time state machine.
     *
     * <p>Stateful because two of the three rules need context: a yod survives only at the
     * start, and a double is only recognisable against the character before it. Shared rather
     * than duplicated so {@link HebrewFold#of} and
     * {@link SearchNormalizer.Normalized#folded()} — which must agree exactly, one being the
     * other plus an offset map — cannot answer differently.
     */
    static final class Folder {

        /** Returned in place of a character the rules discard. */
        static final char DROP = '\0';

        /**
         * The previous character <em>seen</em>, homophones folded — not the previous one
         * emitted.
         *
         * <p>The distinction is the whole doubled-letter rule. Comparing against the last
         * emitted character means two consonants separated by a dropped vowel look like a
         * double: אדידס loses its second dalet to become אדס, and so meets הודיס coming the
         * other way. Against the last character seen, the yod between them keeps them apart.
         */
        private char previous = DROP;

        /** Whether anything has been emitted yet, which is what "start of the word" means. */
        private boolean started;

        /** Consumes one normalized character. @return its folded form, or {@link #DROP} */
        char next(char c) {
            switch (c) {
                case 'ק': c = 'כ'; break;
                case 'ת': c = 'ט'; break;
                default: break;
            }

            char before = previous;
            previous = c;

            // A word may genuinely open with vav or yod ("ויקטוריה", "יוחננוף"); it is only
            // the ones inside a word that are optional spelling.
            if ((c == 'ו' || c == 'י') && started) {
                return DROP;
            }
            if (c == before) {
                return DROP;
            }
            started = true;
            return c;
        }
    }
}
