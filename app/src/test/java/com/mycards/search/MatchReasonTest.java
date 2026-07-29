package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Pins down <em>why</em> a card matched, which decides what the result row says.
 *
 * <p>Two real queries pull in opposite directions, and both are reproduced here with the
 * actual catalog and merchant data that causes them:
 *
 * <ul>
 *   <li><b>"buyme"</b> — the user wants their BuyMe card. Two merchants in that card's own
 *       list happen to carry "buyme" among their aliases, having tagged themselves with the
 *       voucher brand they accept. Naming them ("Accepted at MIMI VAZA") would be noise.
 *   <li><b>"castro"</b> — the user is standing in Castro. The LOVE card carries "castro" as
 *       an alias because Castro Model issues it, so the query hits the card's alias list as
 *       well as its merchants. Answering "8 stores" here withholds the one fact that
 *       matters, while the other cards on screen name the shop.
 * </ul>
 *
 * <p>Both look identical to a matcher that only asks "did the query hit the card's name or
 * aliases?" — in each case the answer is yes, and in each case some merchants matched too.
 * The distinction is that "buyme" is a prefix of the card's actual <em>name</em>, whereas
 * "castro" appears nowhere in "Love Gift Card" and rides in purely on an extra alias. Aliases
 * carry issuer trivia and marketing strings; the name is what the card is called.
 */
public class MatchReasonTest {

    private SearchEngine engine;
    private CardTypeIndex buyMeAll;
    private CardTypeIndex loveGiftCard;

    @Before
    public void setUp() {
        engine = new SearchEngine();

        // Mirrors app/src/main/assets/catalog.json: the card has no "buyme" alias at all —
        // the query matches because "buyme" prefixes the normalized name "buymeall".
        buyMeAll = new CardTypeIndex(
                "buyme_all",
                "BuyMe All",
                Collections.<String>emptyList(),
                Arrays.asList("BUYME ALL - מגוון אדיר במתנה אחת", "BUYME ALL", "ALL"),
                Arrays.asList(
                        // Both of these really do list "buyme" as an alias in
                        // assets/stores/buyme_all.json.
                        new Store("MIMI VAZA", Arrays.asList("מימי", "buyme", "פרחים"), false),
                        new Store("חברת אופקים תיירות נופש", Arrays.asList("buyme", "טיול"), false),
                        new Store("adidas", Arrays.asList("אדידס"), true)),
                0L,
                "buyme_brands");

        // Mirrors the real love_gift_card entry, issuer aliases included.
        loveGiftCard = new CardTypeIndex(
                "love_gift_card",
                "Love Gift Card",
                Arrays.asList("לאב גיפט קארד"),
                Arrays.asList("love", "love card", "לאב", "קסטרו", "castro"),
                Arrays.asList(
                        new Store("קסטרו", Arrays.asList("Castro", "אופנה"), true),
                        new Store("קסטרו HOME", Arrays.asList("Castro Home"), false),
                        new Store("הודיס", Arrays.asList("Hoodies"), false)),
                0L,
                "static_list");
    }

    private CardMatch matchFor(String query, CardTypeIndex index) {
        List<CardMatch> results = engine.search(query, Collections.singletonList(index));
        assertFalse("expected a match for: " + query, results.isEmpty());
        return results.get(0);
    }

    // --- the card-brand case: merchants must stay unnamed ---

    @Test
    public void buymeMatchesTheCardsOwnName() {
        CardMatch match = matchFor("buyme", buyMeAll);
        assertTrue(match.isMatchedByCardName());
        assertTrue("\"buyme\" prefixes the name \"BuyMe All\", so it identifies the card",
                match.isMatchedByCardProperName());
    }

    @Test
    public void buymeStillMatchesMerchantsThatTaggedThemselves() {
        // The merchant hits are real — the point is that the UI should not lead with them.
        CardMatch match = matchFor("buyme", buyMeAll);
        assertFalse("the self-tagged merchants do match", match.getMatchedStores().isEmpty());
    }

    // --- the shop-name case: merchants must be named ---

    @Test
    public void castroDoesNotMatchTheLoveCardsName() {
        CardMatch match = matchFor("castro", loveGiftCard);
        assertTrue("it does hit the alias list, so the card is still a result",
                match.isMatchedByCardName());
        assertFalse("\"castro\" appears nowhere in \"Love Gift Card\" — it is issuer trivia",
                match.isMatchedByCardProperName());
    }

    @Test
    public void castroNamesTheShopsThatMatched() {
        CardMatch match = matchFor("castro", loveGiftCard);
        List<Store> stores = match.getMatchedStores();
        assertEquals(2, stores.size());
        assertTrue(stores.get(0).getName().startsWith("קסטרו"));
    }

    // --- the card's real name still behaves as a card query ---

    @Test
    public void loveMatchesTheCardsOwnName() {
        CardMatch match = matchFor("love", loveGiftCard);
        assertTrue(match.isMatchedByCardProperName());
    }

    @Test
    public void hebrewNameAlsoCountsAsAProperName() {
        // The Hebrew display name is a name, not an alias, so it must not be demoted.
        CardMatch match = matchFor("לאב", loveGiftCard);
        assertTrue(match.isMatchedByCardProperName());
    }

    @Test
    public void aShopQueryThatMissesTheCardNameEntirelyIsUnaffected() {
        CardMatch match = matchFor("hoodies", loveGiftCard);
        assertFalse(match.isMatchedByCardName());
        assertFalse(match.isMatchedByCardProperName());
        assertEquals("הודיס", match.getMatchedStores().get(0).getName());
    }
}
