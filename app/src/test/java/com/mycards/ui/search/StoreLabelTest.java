package com.mycards.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mycards.search.Query;
import com.mycards.search.SearchEngine;
import com.mycards.search.Store;
import com.mycards.search.StoreMatch;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * What one result row says, and which of its words stand out.
 *
 * <p>Reconstructs the screenshot that started this: searching <em>קרולינה</em> against a
 * BuyMe card returned two rows — "CAROLINA LEMKE", named in the other alphabet, and "מגוון
 * מותגי קבוצת קסטרו הודיס", which contains not one letter of the query. Both are right. Both
 * read as a broken filter until the row is allowed to say what it read.
 *
 * <p>The format string is passed in rather than resolved from resources, which is what keeps
 * this on the JVM; it is the same pattern {@code R.string.store_listed_as} holds.
 */
public class StoreLabelTest {

    /** As {@code values/strings.xml} defines it. */
    private static final String LISTED_AS = "%1$s (%2$s)";

    private final SearchEngine engine = new SearchEngine();

    private StoreLabel label(String query, Store... stores) {
        List<Query> variants = SearchEngine.queryVariants(query);
        List<StoreMatch> hits = engine.matchingStores(query, Arrays.asList(stores));
        return StoreLabel.of(hits.get(0), variants, LISTED_AS);
    }

    /** The stretch the row would bold and tint. */
    private static String highlighted(StoreLabel label) {
        return label.hasHighlight()
                ? label.text.substring(label.highlightStart, label.highlightEnd)
                : null;
    }

    // --- a shop found by its own name says only its own name ---

    @Test
    public void aNameHitShowsTheNameAlone() {
        StoreLabel label = label("zara", new Store("Zara", Arrays.asList("זארה"), false));
        assertEquals("Zara", label.text);
        assertEquals("Zara", highlighted(label));
    }

    @Test
    public void aNameHitInHebrewShowsTheNameAlone() {
        StoreLabel label = label("קסטרו", new Store("קסטרו", Arrays.asList("Castro"), false));
        assertEquals("קסטרו", label.text);
        assertEquals("קסטרו", highlighted(label));
    }

    @Test
    public void aPartialNameHitPointsAtThePartThatMatched() {
        StoreLabel label = label("pizz", new Store("Pizza Hut", Collections.<String>emptyList(), false));
        assertEquals("Pizza Hut", label.text);
        assertEquals("Pizz", highlighted(label));
    }

    // --- a shop found by another spelling shows both ---

    @Test
    public void theOtherLanguageIsShownBesideTheName() {
        // The screenshot's first row. The shop is filed in Latin and the query was Hebrew.
        StoreLabel label = label("קרולינה",
                new Store("CAROLINA LEMKE", Arrays.asList("קרולינה למקה", "משקפיים"), false));
        assertEquals("CAROLINA LEMKE (קרולינה למקה)", label.text);
        assertEquals("the typed spelling is what stands out", "קרולינה", highlighted(label));
    }

    @Test
    public void aGroupEntryExplainsWhichBrandPutItOnScreen() {
        // The screenshot's real puzzle: an entry whose name shares no letter with the query,
        // on the list because the group it names covers that brand.
        StoreLabel label = label("קרולינה",
                new Store("מגוון מותגי קבוצת קסטרו הודיס",
                        Arrays.asList("קסטרו", "הודיס", "קרולינה", "אורבניקה"), false));
        assertEquals("מגוון מותגי קבוצת קסטרו הודיס (קרולינה)", label.text);
        assertEquals("קרולינה", highlighted(label));
    }

    @Test
    public void theLatinSpellingIsShownWhenTheListIsHebrew() {
        StoreLabel label = label("carolina",
                new Store("קרולינה למקה", Arrays.asList("Carolina Lemke"), false));
        assertEquals("קרולינה למקה (Carolina Lemke)", label.text);
        assertEquals("Carolina", highlighted(label));
    }

    @Test
    public void theHighlightLandsInTheSpellingAndNotInTheName() {
        // The two halves can share letters. Bolding inside the name here would point at the
        // wrong half of the row.
        StoreLabel label = label("קפה",
                new Store("Cafe Cafe", Arrays.asList("קפה קפה"), false));
        assertTrue(label.highlightStart > label.text.indexOf('('));
        assertEquals("קפה", highlighted(label));
    }

    // --- spelling variants are shown as the list spells them ---

    @Test
    public void aSkeletonMatchStillPointsAtRealWords() {
        // Typed "ארוקה", listed as "אירוקה". Nothing matched literally, and the row still
        // has to underline the word rather than nothing.
        StoreLabel label = label("ארוקה",
                new Store("Erroca by Super-Pharm", Arrays.asList("אירוקה"), false));
        assertEquals("Erroca by Super-Pharm (אירוקה)", label.text);
        assertEquals("אירוקה", highlighted(label));
    }

    // --- edge cases the adapter must not have to think about ---

    @Test
    public void aRowWithNothingToPointAtSimplyHasNoHighlight() {
        StoreLabel label = new StoreLabel("Zara", -1, -1);
        assertFalse(label.hasHighlight());
    }

    @Test
    public void anOutOfRangeSpanIsNotDrawn() {
        // Defence in depth: the adapter draws whatever this reports, so a span that could
        // not be drawn must report itself as absent rather than throwing in a view holder.
        assertFalse(new StoreLabel("Zara", 0, 99).hasHighlight());
        assertFalse(new StoreLabel("Zara", 3, 1).hasHighlight());
    }

    @Test
    public void labelsCompareByTextAndHighlight() {
        // DiffUtil leans on this to decide whether a row already on screen needs redrawing
        // when the query moves within a name it is already showing.
        assertEquals(new StoreLabel("Zara", 0, 2), new StoreLabel("Zara", 0, 2));
        assertFalse(new StoreLabel("Zara", 0, 2).equals(new StoreLabel("Zara", 1, 3)));
        assertFalse(new StoreLabel("Zara", 0, 2).equals(new StoreLabel("Zaru", 0, 2)));
    }

    @Test
    public void aFormatMissingItsSecondPlaceholderFallsBackToTheName() {
        List<Query> variants = SearchEngine.queryVariants("קרולינה");
        StoreMatch hit = engine.matchingStores("קרולינה",
                Arrays.asList(new Store("CAROLINA LEMKE", Arrays.asList("קרולינה"), false)))
                .get(0);
        StoreLabel label = StoreLabel.of(hit, variants, "%1$s");
        assertEquals("CAROLINA LEMKE", label.text);
    }
}
