package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * The spelling-skeleton rules, stated one at a time.
 *
 * <p>Nothing here touches the shipped merchant lists. The rules are about Hebrew, not about
 * which shops happen to be on a card this month, and a test that reached for the real data
 * would start failing the next time a list is refreshed for reasons that have nothing to do
 * with folding.
 */
public class HebrewFoldTest {

    private static String fold(String raw) {
        return HebrewFold.of(SearchNormalizer.normalize(raw));
    }

    /** Asserts that two spellings of the same word reach the same skeleton. */
    private static void same(String a, String b) {
        assertEquals(a + " and " + b + " should fold together", fold(a), fold(b));
    }

    // --- matres lectionis: the optional yod and vav ---

    @Test
    public void theOptionalYodFoldsAway() {
        // Ktiv male vs ktiv haser. Neither spelling is wrong and neither contains the other.
        same("אירוקה", "ארוקה");
        same("ויקטוריה", "ויקטריה");
        same("סטודיו", "סטדיו");
    }

    @Test
    public void theOptionalVavFoldsAway() {
        same("מוסך", "מסך");
        same("גולף", "גלף");
    }

    @Test
    public void aWordMayStillOpenWithYodOrVav() {
        // Only the ones inside a word are optional spelling. Dropping a leading yod would
        // merge "יוחננוף" into a different word entirely.
        assertTrue(fold("יוחננוף").startsWith("י"));
        assertTrue(fold("ויקטוריה").startsWith("ו"));
    }

    // --- homophones ---

    @Test
    public void kufAndKafFoldTogether() {
        same("קרולינה", "כרולינה");
        same("קסטרו", "כסטרו");
        same("למקה", "למכה");
    }

    @Test
    public void tetAndTavFoldTogether() {
        same("מותגי", "מוטגי");
        same("קבוצת", "קבוצט");
    }

    /**
     * א and ע sound the same and are still kept apart, which is a decision rather than an
     * omission.
     *
     * <p>They are two of the commonest letters in Hebrew, so folding them merges an enormous
     * number of unrelated words. Measured against the shipped merchant list it cost far more
     * than it bought: with א/ע folded, a search for <em>ארוקה</em> (Erroca) also returned
     * <em>ערכה</em>, the ordinary word for a kit, and the top of the list went to a toy shop.
     */
    @Test
    public void alefAndAyinAreDeliberatelyNotFolded() {
        assertNotEquals(fold("אירוקה"), fold("עירוקה"));
        assertNotEquals(fold("ארוקה"), fold("ערכה"));
    }

    // --- doubled letters ---

    @Test
    public void aDoubledLetterCollapses() {
        same("וולנטינו", "ולנטינו");
        // Latin too, which is free and occasionally useful: one r or two is a coin toss for
        // anyone spelling a brand they have only ever seen on a shopfront.
        same("erroca", "eroca");
        same("bugatti", "bugati");
    }

    /**
     * Two consonants left touching by a dropped vowel are not a double.
     *
     * <p>Collapsing them reduced <em>אדידס</em> to <em>אדס</em> — which is also where
     * <em>הודיס</em> lands — and put Hoodies in the results for a search for adidas.
     */
    @Test
    public void aVowelBetweenTwoIdenticalLettersKeepsThemApart() {
        assertEquals("אדדס", fold("אדידס"));
        assertEquals("הדס", fold("הודיס"));
        assertNotEquals(fold("אדידס"), fold("הודיס"));
    }

    // --- what must NOT fold ---

    @Test
    public void unrelatedWordsKeepDifferentSkeletons() {
        // The whole risk of a fuzzy tier is that it turns everything into everything else.
        // These are ordinary Hebrew shop words that must stay apart.
        assertNotEquals(fold("זארה"), fold("סברה"));
        assertNotEquals(fold("פוקס"), fold("פיצה"));
        assertNotEquals(fold("קסטרו"), fold("הודיס"));
        assertNotEquals(fold("אדידס"), fold("אופנה"));
    }

    @Test
    public void latinTextIsLeftAloneApartFromDoubles() {
        assertEquals("zara", fold("Zara"));
        assertEquals("adidas", fold("adidas"));
        // The doubled-letter rule is not Hebrew-only, so "pizza" loses one z. Harmless: both
        // sides of a comparison go through this, and the exact tier answers first anyway.
        assertEquals("pizahut", fold("Pizza Hut"));
    }

    // --- shape of the function itself ---

    @Test
    public void foldingIsIdempotent() {
        // Matching folds the query once and the haystack once; if a second pass moved, the
        // two sides could disagree about how folded is folded enough.
        for (String raw : Arrays.asList("אירוקה", "קרולינה למקה", "adidas", "וולנטינו", "")) {
            String once = fold(raw);
            assertEquals(raw, once, HebrewFold.of(once));
        }
    }

    @Test
    public void unchangedTextComesBackAsTheSameInstance() {
        // Store keeps one string instead of two for every name that folds to itself, which
        // is most of them; that saving depends on this identity.
        String normalized = SearchNormalizer.normalize("Zara");
        assertSame(normalized, HebrewFold.of(normalized));
    }

    @Test
    public void emptyAndNullAreEmpty() {
        assertEquals("", HebrewFold.of(null));
        assertEquals("", HebrewFold.of(""));
    }

    /**
     * The offset-preserving fold has to agree with the plain one character for character.
     *
     * <p>They are two loops over one rule set precisely so a highlight can be drawn where a
     * match was found. If they ever disagreed, the bolded stretch would drift off the word.
     */
    @Test
    public void theOffsetPreservingFoldAgreesWithThePlainOne() {
        List<String> names = Arrays.asList(
                "אירוקה by Super-Pharm",
                "מגוון מותגי קבוצת קסטרו הודיס",
                "Carolina Lemke",
                "מסעדת MOSHIK& של השף מושיק רוט",
                "וולנטינו",
                "יוחננוף",
                "");
        for (String name : names) {
            assertEquals(name,
                    fold(name),
                    SearchNormalizer.normalizeWithSource(name).folded().text);
        }
    }
}
