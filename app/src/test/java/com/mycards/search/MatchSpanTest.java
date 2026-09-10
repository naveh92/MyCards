package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * What gets underlined, and whether it is the right letters.
 *
 * <p>The offsets come back through normalization, which drops spaces, punctuation and Hebrew
 * points — so a hit found at normalized position 5 has to be mapped to wherever those
 * letters started in the text the reader is actually looking at. Every case below is the
 * mapping getting a chance to be off by one.
 */
public class MatchSpanTest {

    private static List<Query> variants(String raw) {
        return SearchEngine.queryVariants(raw);
    }

    /** The stretch of {@code text} that would be highlighted, as a plain substring. */
    private static String highlighted(String text, String query) {
        MatchSpan span = MatchSpan.find(text, variants(query));
        return span == null ? null : text.substring(span.start, span.end);
    }

    @Test
    public void pointsAtTheLettersThatMatched() {
        assertEquals("Zara", highlighted("Zara", "zara"));
        assertEquals("Zar", highlighted("Zara", "zar"));
        assertEquals("ara", highlighted("Zara", "ara"));
    }

    @Test
    public void survivesSpacesInsideTheMatch() {
        // "buyme" spans the space in "Buy Me All", which normalization removed.
        assertEquals("Buy Me", highlighted("Buy Me All", "buyme"));
    }

    @Test
    public void survivesPunctuationInsideTheMatch() {
        assertEquals("Super-Pharm", highlighted("Super-Pharm", "superpharm"));
        assertEquals("Fox-Home", highlighted("Fox-Home Ltd.", "foxhome"));
    }

    @Test
    public void doesNotStretchOverTrailingPunctuation() {
        // The span ends at the last matched letter, not at the start of the next one, or a
        // query for "fox" would bold the hyphen after it.
        assertEquals("Fox", highlighted("Fox-Home", "fox"));
    }

    @Test
    public void pointsAtHebrewLetters() {
        assertEquals("קרולינה", highlighted("קרולינה למקה", "קרולינה"));
        assertEquals("למקה", highlighted("קרולינה למקה", "למקה"));
    }

    @Test
    public void survivesNiqqud() {
        // Vowel points are dropped for matching and have to be counted back in, or the
        // highlight slides left by one character per point.
        String pointed = "זָארָה";
        assertEquals(pointed, highlighted(pointed, "זארה"));
    }

    @Test
    public void survivesAFinalLetterFold() {
        // "מנחם" ends in a final mem, which matching folds to a plain one.
        assertEquals("מנחם", highlighted("מנחם", "מנחמ"));
    }

    @Test
    public void pointsAtASkeletonMatchToo() {
        // Nothing in "אירוקה" literally contains "ארוקה", so the span comes from the folded
        // form — and still has to land on the word rather than beside it.
        assertEquals("אירוקה", highlighted("אירוקה", "ארוקה"));
    }

    @Test
    public void aSkeletonSpanCoversTheWholeRealWord() {
        // The skeleton is shorter than the text it came from, so its end offset has to be
        // mapped back rather than added.
        assertEquals("אירוקה", highlighted("אירוקה בסופר פארם", "ארוקה"));
    }

    @Test
    public void prefersTheStrongerReadingWhenTwoVariantsBothMatch() {
        // A query can match as typed and again as a keyboard-layout reading. The whole-name
        // hit is the one worth pointing at.
        assertEquals("Zara", highlighted("Zara", "Zara"));
    }

    @Test
    public void reportsNothingWhenNothingMatched() {
        assertNull(MatchSpan.find("Zara", variants("adidas")));
        assertNull(MatchSpan.find("", variants("zara")));
        assertNull(MatchSpan.find(null, variants("zara")));
        assertNull(MatchSpan.find("Zara", Collections.<Query>emptyList()));
        assertNull(MatchSpan.find("Zara", null));
    }

    @Test
    public void everySpanIsInsideTheStringItCameFrom() {
        // The adapter bounds-checks before drawing, but a span that needed the check would
        // be a bug. Swept over the awkward shapes at once.
        List<String> names = Arrays.asList(
                "Zara", "Buy Me All", "Super-Pharm", "מגוון מותגי קבוצת קסטרו הודיס",
                "מסעדת MOSHIK& של השף מושיק רוט", "קרולינה למקה", "אירוקה", "Fox-Home Ltd.");
        List<String> queries = Arrays.asList(
                "za", "buyme", "pharm", "קסטרו", "מושיק", "קרולינה", "ארוקה", "fox", "המקה");

        for (String name : names) {
            for (String query : queries) {
                MatchSpan span = MatchSpan.find(name, variants(query));
                if (span == null) {
                    continue;
                }
                assertEquals(name + " / " + query + ": span starts inside the name",
                        true, span.start >= 0 && span.start < name.length());
                assertEquals(name + " / " + query + ": span ends inside the name",
                        true, span.end > span.start && span.end <= name.length());
            }
        }
    }

    @Test
    public void findsTheSpanThroughAKeyboardLayoutMistake() {
        // "tshsx" is "אדידס" typed on an English layout. The row still has to be able to
        // point at the Hebrew letters it actually matched.
        MatchSpan span = MatchSpan.find("אדידס", variants("tshsx"));
        assertNotNull(span);
        assertEquals("אדידס", "אדידס".substring(span.start, span.end));
    }
}
