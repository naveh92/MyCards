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

    @Test
    public void sourceTrackingProducesTheSameCanonicalForm() {
        // The two implementations exist for different reasons and must never disagree:
        // matching is decided by one and explained by the other.
        String[] corpus = {
                "Buy Me All", "BUY-ME-ALL", "  buy me all  ", "זָארָה", "מנחם",
                "מסעדת MOSHIK& של השף מושיק רוט", "Fox Home", "Café Noir", "טרקלין חשמל",
                "adidas & reebok", "", "!!!", "עסקים 24/7",
        };
        for (String raw : corpus) {
            assertEquals("normalizing \"" + raw + "\"",
                    SearchNormalizer.normalize(raw),
                    SearchNormalizer.normalizeWithSource(raw).text);
        }
    }

    @Test
    public void sourceOffsetsPointBackThroughDroppedCharacters() {
        // "meall" matches at normalized offset 3, which is "Me All" in the original — five
        // characters further along than the naive offset, because a space was dropped
        // before it and another inside it.
        SearchNormalizer.Normalized n = SearchNormalizer.normalizeWithSource("Buy Me All");
        String needle = "meall";
        int at = n.text.indexOf(needle);
        assertEquals(3, at);
        assertEquals("Me All",
                "Buy Me All".substring(n.sourceStart(at), n.sourceEnd(at + needle.length() - 1)));
    }

    @Test
    public void sourceOffsetsSurviveNiqqudAndFinalLetters() {
        // Both of the transformations that change length: a vowel point that vanishes, and
        // a final letter that folds to its medial form without moving.
        // The span starts at the letter, not at the vowel point dropped before it, and runs
        // to the end of the last letter — carrying the point inside it along the way.
        String pointedName = "זָארָה";
        SearchNormalizer.Normalized pointed = SearchNormalizer.normalizeWithSource(pointedName);
        String needle = SearchNormalizer.normalize("ארה");
        int at = pointed.text.indexOf(needle);
        assertEquals(1, at);
        assertEquals("ארָה", pointedName.substring(
                pointed.sourceStart(at), pointed.sourceEnd(at + needle.length() - 1)));

        SearchNormalizer.Normalized folded = SearchNormalizer.normalizeWithSource("מנחם");
        int mem = folded.text.indexOf("מ", 1);
        assertEquals(3, mem);
        assertEquals("ם", "מנחם".substring(folded.sourceStart(mem), folded.sourceEnd(mem)));
    }

    @Test
    public void sourceTrackingHandlesNothing() {
        assertEquals("", SearchNormalizer.normalizeWithSource(null).text);
        assertEquals("", SearchNormalizer.normalizeWithSource("").text);
        assertEquals("", SearchNormalizer.normalizeWithSource("---").text);
    }
}
