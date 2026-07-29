package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchNormalizerTest {

    @Test
    public void collapsesCaseSpacingAndPunctuation() {
        // The headline requirement: every way a person might type "Buy Me All"
        // has to reduce to a form the others can be found inside.
        String canonical = SearchNormalizer.normalize("Buy Me All");
        assertEquals("buymeall", canonical);
        assertEquals("buymeall", SearchNormalizer.normalize("BUY-ME-ALL"));
        assertEquals("buymeall", SearchNormalizer.normalize("  buy me all  "));
        assertEquals("buyme", SearchNormalizer.normalize("buy-me"));
        assertEquals("buyme", SearchNormalizer.normalize("BuyMe"));

        assertTrue(canonical.contains(SearchNormalizer.normalize("buyme")));
        assertTrue(canonical.contains(SearchNormalizer.normalize("buy-me")));
        assertTrue(canonical.contains(SearchNormalizer.normalize("all")));
    }

    @Test
    public void partialStoreNameIsSubstringOfFullName() {
        assertTrue(SearchNormalizer.normalize("Zara").contains(SearchNormalizer.normalize("za")));
        assertTrue(SearchNormalizer.normalize("Fox Home").contains(SearchNormalizer.normalize("fox")));
    }

    @Test
    public void stripsHebrewNiqqud() {
        // Vowel-pointed and bare spellings of the same word must converge.
        assertEquals(SearchNormalizer.normalize("זארה"), SearchNormalizer.normalize("זָארָה"));
    }

    @Test
    public void foldsHebrewFinalLetters() {
        // "מנ" is a legitimate prefix of "מנחם"; the final mem must not block the match.
        assertEquals("מנחמ", SearchNormalizer.normalize("מנחם"));
        assertTrue(SearchNormalizer.normalize("מנחם").contains(SearchNormalizer.normalize("חם")));
        assertEquals("כספ", SearchNormalizer.normalize("כסף"));
    }

    @Test
    public void stripsHebrewPunctuationAndLatinAccents() {
        assertEquals("צהל", SearchNormalizer.normalize("צה\"ל"));
        assertEquals("cafe", SearchNormalizer.normalize("Café"));
    }

    @Test
    public void handlesNullAndEmpty() {
        assertEquals("", SearchNormalizer.normalize(null));
        assertEquals("", SearchNormalizer.normalize(""));
        assertEquals("", SearchNormalizer.normalize("   -- ??  "));
    }
}
