package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Hebrew spelled two ways, which is Hebrew spelled normally.
 *
 * <p>The reported bug: a card that works at Erroca could not be found by typing
 * <em>ארוקה</em>, because the list spells it <em>אירוקה</em>. Neither is a typo. The same
 * shape recurs everywhere — קרולינה/כרולינה, מותגי/מוטגי, אירוקה/עירוקה — and the shipped
 * data works around it by carrying hand-written variants, which only covers the ones somebody
 * thought of.
 *
 * <p>Fixtures are built here rather than read from the merchant lists, so this stays a test
 * of the matching rules and not of whether a particular alias is still in a particular JSON
 * file. The counter-tests matter as much as the matches: a fuzzy tier that finds everything
 * has not fixed search, it has broken it.
 */
public class SpellingVariantSearchTest {

    private final SearchEngine engine = new SearchEngine();

    private static Store store(String name, String... aliases) {
        return new Store(name, Arrays.asList(aliases), false);
    }

    private List<String> shopsFor(String query, Store... stores) {
        List<String> names = new ArrayList<>();
        for (StoreMatch hit : engine.matchingStores(query, Arrays.asList(stores))) {
            names.add(hit.getName());
        }
        return names;
    }

    // --- the reported bug ---

    @Test
    public void theOptionalYodDoesNotDecideWhetherAShopIsFound() {
        Store erroca = store("Erroca by Super-Pharm", "אירוקה", "משקפיים");
        for (String query : Arrays.asList("אירוקה", "ארוקה", "אירוכה", "ארוכה")) {
            assertTrue(query + " should find Erroca",
                    shopsFor(query, erroca).contains("Erroca by Super-Pharm"));
        }
    }

    @Test
    public void alefAndAyinStayApart() {
        // Deliberate, and the one homophone not folded: א and ע are common enough that
        // merging them buries the shop that was asked for under words that merely rhyme
        // with it. See HebrewFoldTest for the measurement.
        Store erroca = store("Erroca by Super-Pharm", "אירוקה");
        assertTrue(shopsFor("עירוקה", erroca).isEmpty());
    }

    @Test
    public void itWorksWhicheverSpellingTheListHappensToUse() {
        // The list is as likely to hold the short spelling as the long one, and a rule that
        // only bridged one direction would just move the bug.
        Store shortSpelling = store("ארוקה");
        Store longSpelling = store("אירוקה");
        assertTrue(shopsFor("אירוקה", shortSpelling).contains("ארוקה"));
        assertTrue(shopsFor("ארוקה", longSpelling).contains("אירוקה"));
    }

    // --- homophones ---

    @Test
    public void kufAndKafAreNotAGuessingGame() {
        Store carolina = store("קרולינה למקה");
        for (String query : Arrays.asList("קרולינה", "כרולינה", "קרולינה למכה", "כרולינה למכה")) {
            assertTrue(query + " should find Carolina Lemke",
                    shopsFor(query, carolina).contains("קרולינה למקה"));
        }
    }

    @Test
    public void tetAndTavAreNotAGuessingGame() {
        Store group = store("מגוון מותגי קבוצת קסטרו הודיס", "מותגי", "קבוצת", "קסטרו");
        for (String query : Arrays.asList("מותגי", "מוטגי", "קבוצת", "קבוצט", "כסטרו")) {
            assertTrue(query + " should find the group entry",
                    shopsFor(query, group).contains("מגוון מותגי קבוצת קסטרו הודיס"));
        }
    }

    // --- precision: the fuzzy tier must stay a fallback ---

    @Test
    public void anExactMatchOutranksASpellingSkeletonMatch() {
        // Someone who typed a name exactly gets the shop with that name first, always.
        Store exact = store("קסטרו");
        Store fuzzy = store("כסתרו");
        List<String> hits = shopsFor("קסטרו", fuzzy, exact);
        assertEquals(Arrays.asList("קסטרו", "כסתרו"), hits);
    }

    @Test
    public void aLiteralSubstringStillBeatsASkeleton() {
        // Even the weakest exact tier — a fragment buried mid-word — is better evidence than
        // the strongest fuzzy one.
        Store buried = store("בית קסטרו הישן");
        Store skeleton = store("כסתרו");
        assertEquals(Arrays.asList("בית קסטרו הישן", "כסתרו"),
                shopsFor("קסטרו", skeleton, buried));
    }

    @Test
    public void aMatchIsReportedAsFuzzyOnlyWhenItReallyIsOne() {
        // The row's wording turns on this: a shop found literally must not be described as
        // found by spelling skeleton.
        assertFalse(engine.matchingStores("קסטרו", Arrays.asList(store("קסטרו")))
                .get(0).isFuzzy());
        assertTrue(engine.matchingStores("כסתרו", Arrays.asList(store("קסטרו")))
                .get(0).isFuzzy());
    }

    /**
     * A literal hit outranks a skeleton hit even when the skeleton is on a shop's own name.
     *
     * <p>Measured against the shipped BuyMe list: the name-before-alias rule, applied across
     * the two bands, put "ג'קו סטריט" — whose name merely folds to something containing the
     * fold of קסטרו — above CASTRO itself, which carries קסטרו as an alias. Twenty-nine
     * results, and the shop the person was standing in was not the first of them.
     */
    @Test
    public void aLiteralAliasHitBeatsASkeletonHitOnAName() {
        Store resembling = store("כסטרוב");
        Store castro = store("CASTRO", "קסטרו");
        assertEquals(Arrays.asList("CASTRO", "כסטרוב"), shopsFor("קסטרו", resembling, castro));
    }

    /**
     * A skeleton buried inside a longer skeleton is not a match at all.
     *
     * <p>The rule that keeps the fuzzy tier honest. "מארזי אווירה" folds to a string that
     * contains the fold of "זארה", and answering a search for Zara with a gift-box shop is
     * worse than answering with nothing — which, on a card that does not carry Zara, is the
     * correct answer.
     */
    @Test
    public void aSkeletonBuriedMidWordIsNotAMatch() {
        assertTrue(shopsFor("זארה", store("מארזי אווירה")).isEmpty());
        assertTrue(shopsFor("קסטרו", store("גקו סטריט")).isEmpty());
        assertTrue(shopsFor("ארומה", store("שווארמה")).isEmpty());
    }

    @Test
    public void butAWholeSkeletonOrAPrefixOfOneStillMatches() {
        // The tier still has to do its job: these are the shapes it exists for.
        assertFalse(shopsFor("ארוקה", store("אירוקה")).isEmpty());
        assertFalse(shopsFor("ארוקה", store("אירוקה בסופר פארם")).isEmpty());
    }

    @Test
    public void aTooShortQueryDoesNotFuzzyMatchEverything() {
        // With vowels and homophones folded away, a two-letter skeleton matches a sizeable
        // share of any merchant list. Below the floor the fuzzy pass does not run at all.
        Store zara = store("זארה");
        Store aroma = store("ארומה");
        // "כת" folds to "כט", which is inside neither literally — and must not be stretched
        // into a match either.
        assertTrue(shopsFor("כת", zara, aroma).isEmpty());
    }

    @Test
    public void unrelatedShopsAreStillUnrelated() {
        Store zara = store("זארה");
        Store fox = store("פוקס");
        Store pizza = store("פיצה האט");
        assertTrue(shopsFor("קרולינה", zara, fox, pizza).isEmpty());
        assertTrue(shopsFor("אירוקה", zara, fox, pizza).isEmpty());
        assertTrue(shopsFor("adidas", zara, fox, pizza).isEmpty());
    }

    // --- the same rules on the card list ---

    @Test
    public void aCardNameToleratesTheSameSpellingVariance() {
        CardTypeIndex love = new CardTypeIndex(
                "love_gift_card",
                "Love Gift Card",
                Arrays.asList("לאב גיפט קארד"),
                Collections.<Store>emptyList(),
                0L,
                "test");
        List<CardTypeIndex> wallet = Arrays.asList(love);

        assertEquals(1, engine.search("לאב גיפט קארד", wallet).size());
        // Same card, spelled with the optional vav dropped.
        assertEquals(1, engine.search("לאב גפט קארד", wallet).size());
        assertTrue(engine.search("לאב גפט קארד", wallet).get(0).isMatchedByCardProperName());
    }

    @Test
    public void aCardIsStillNotFoundByAnUnrelatedQuery() {
        CardTypeIndex love = new CardTypeIndex(
                "love_gift_card", "Love Gift Card", Collections.<String>emptyList(),
                Collections.<Store>emptyList(), 0L, "test");
        assertTrue(engine.search("אדידס", Arrays.asList(love)).isEmpty());
    }
}
