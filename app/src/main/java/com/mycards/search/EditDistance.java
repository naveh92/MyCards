package com.mycards.search;

/**
 * How many single-character edits separate two spellings.
 *
 * <p>Used to rank matches inside {@link MatchScore}'s fuzzy band, where the score has nothing
 * left to say: every candidate there folded to the same skeleton by construction, so they all
 * tie. Something has to decide which spelling is the likeliest thing the person meant, and the
 * honest measure is how far it strayed from what they actually typed.
 *
 * <p>Comparing lengths — the first thing this did — is not that measure. Searching
 * <em>ארוקה</em> reaches both <em>אירוקה</em> and <em>אירוכה</em>, which are the same length,
 * so length called them equally good; but the first differs by one inserted yod and the second
 * by that plus a kuf/kaf swap. Counting the edits puts them in the order a reader expects.
 *
 * <p>Plain Levenshtein, two rows rather than a full matrix. It runs only for candidates that
 * already matched, over a query and a merchant name, so the strings are short and there are
 * few of them.
 */
final class EditDistance {

    private EditDistance() {
    }

    /**
     * @param a one string, normalized
     * @param b the other, normalized
     * @return the number of insertions, deletions and substitutions between them
     */
    static int between(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        if (a.isEmpty()) {
            return b.length();
        }
        if (b.isEmpty()) {
            return a.length();
        }

        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (ca == b.charAt(j - 1) ? 0 : 1);
                int delete = previous[j] + 1;
                int insert = current[j - 1] + 1;
                current[j] = Math.min(substitute, Math.min(delete, insert));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
