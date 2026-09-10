package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * The query object every screen matches against, and the one shortcut it offers.
 *
 * <p>{@link Query#hits} exists for the screens that only need a yes or no — the purchase
 * history, the card-type menu. It has to answer exactly what the search screen would answer,
 * or the app would quietly hold two different opinions about what matches.
 */
public class QueryTest {

    private static boolean hits(String query, String text) {
        Query q = Query.of(query);
        String normalized = SearchNormalizer.normalize(text);
        return q != null && q.hits(normalized, HebrewFold.of(normalized));
    }

    @Test
    public void carriesTheNormalizedFormOfWhatWasTyped() {
        assertEquals("buymeall", Query.of("Buy Me All!").exact());
    }

    @Test
    public void isNullWhenThereIsNothingLeftToMatchOn() {
        assertNull(Query.of(null));
        assertNull(Query.of(""));
        assertNull(Query.of("   "));
        assertNull(Query.of("!!!---"));
    }

    @Test
    public void carriesASkeletonOnlyWhenItIsLongEnoughToMeanSomething() {
        assertTrue(Query.of("ארוקה").hasFuzzy());
        assertFalse("two letters name too much to be evidence", Query.of("כת").hasFuzzy());
        assertNull(Query.of("כת").fuzzy());
    }

    @Test
    public void aSkeletonEqualToTheQueryIsStillWorthHaving() {
        // "eroca" folds to itself, and still has to reach "erroca", whose skeleton is what
        // moved. Skipping the pass whenever the query did not change would miss that.
        Query q = Query.of("eroca");
        assertEquals(q.exact(), q.fuzzy());
        assertTrue(q.hasFuzzy());
        assertTrue(hits("eroca", "Erroca"));
    }

    @Test
    public void hitsAgreesWithTheSearchScreenOnWhatMatches() {
        assertTrue(hits("castro", "Castro"));
        assertTrue(hits("astr", "Castro"));
        assertTrue(hits("ארוקה", "אירוקה"));
        assertFalse(hits("zara", "Castro"));
    }

    @Test
    public void hitsRefusesASkeletonBuriedMidWord() {
        // The same rule the merchant search applies, and for the same reason: a history
        // filled with matches the search screen would never show is worse than an empty one.
        assertFalse(hits("זארה", "מארזי אווירה"));
        assertFalse(hits("ארומה", "שווארמה"));
    }

    @Test
    public void everyVariantOfARealQueryCarriesTheSameShape() {
        // queryVariants builds one of these per keyboard-layout reading; none may come back
        // half-built.
        List<Query> variants = SearchEngine.queryVariants("אדידס");
        assertFalse(variants.isEmpty());
        for (Query q : variants) {
            assertFalse(q.exact().isEmpty());
        }
    }

    @Test
    public void variantsAreDeduplicatedByTheirNormalizedText() {
        // A query with nothing to transliterate produces one variant, not three identical
        // ones — every duplicate would be another full pass over the merchant list.
        assertEquals(1, SearchEngine.queryVariants("123").size());
    }

    @Test
    public void anEmptyQueryProducesNoVariantsAtAll() {
        for (String empty : Arrays.asList("", "   ", null, "!!!")) {
            assertTrue(String.valueOf(empty),
                    SearchEngine.queryVariants(empty).isEmpty());
        }
    }
}
