package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class SearchEngineTest {

    private SearchEngine engine;
    private List<CardTypeIndex> wallet;

    @Before
    public void setUp() {
        engine = new SearchEngine();

        CardTypeIndex buyMeAll = new CardTypeIndex(
                "buyme_all",
                "BuyMe All",
                Arrays.asList("buyme", "ביימי אול"),
                Arrays.asList(
                        new Store("Zara", Arrays.asList("זארה"), false),
                        new Store("adidas", Arrays.asList("אדידס", "tshsx", "ריבוק"), true),
                        new Store("Fox Home", Arrays.asList("פוקס הום"), false)),
                1_700_000_000_000L,
                "buyme_brands");

        CardTypeIndex allInZone = new CardTypeIndex(
                "all_in_zone",
                "All-inZone",
                Arrays.asList("אול אין זון", "allinzone"),
                Arrays.asList(
                        new Store("Castro", Arrays.asList("קסטרו"), false),
                        new Store("Pizza Hut", Arrays.asList("פיצה האט"), false)),
                1_700_000_000_000L,
                "static_list");

        wallet = Arrays.asList(buyMeAll, allInZone);
    }

    private List<String> idsFor(String query) {
        List<String> ids = new ArrayList<>();
        for (CardMatch m : engine.search(query, wallet)) {
            ids.add(m.getCardTypeId());
        }
        return ids;
    }

    @Test
    public void partialStoreNameFindsTheCardThatCoversIt() {
        // The core scenario: standing in Zara, typing two letters.
        List<CardMatch> results = engine.search("za", wallet);
        assertFalse(results.isEmpty());
        assertEquals("buyme_all", results.get(0).getCardTypeId());
        assertEquals("Zara", results.get(0).getMatchedStores().get(0).getName());
        assertFalse(results.get(0).isMatchedByCardName());
    }

    @Test
    public void cardNameVariantsAllFindTheCard() {
        for (String query : Arrays.asList("buyme", "buy-me", "Buy Me All", "BUYME", "all")) {
            assertTrue("expected buyme_all for query: " + query,
                    idsFor(query).contains("buyme_all"));
        }
    }

    @Test
    public void cardNameMatchOutranksStoreMatch() {
        // "all" is both a word in "BuyMe All" and part of "All-inZone"; both should appear,
        // and both are name matches rather than merchant matches.
        List<CardMatch> results = engine.search("all", wallet);
        assertEquals(2, results.size());
        assertTrue(results.get(0).isMatchedByCardName());
    }

    @Test
    public void hebrewQueryFindsHebrewAlias() {
        assertTrue(idsFor("קסטרו").contains("all_in_zone"));
        assertTrue(idsFor("זארה").contains("buyme_all"));
    }

    @Test
    public void wrongKeyboardLayoutStillMatches() {
        // "zara" typed with the keyboard left in Hebrew.
        assertTrue(idsFor("זשרש").contains("buyme_all"));
        // "אדידס" typed with the keyboard left in English.
        assertTrue(idsFor("tshsx").contains("buyme_all"));
    }

    @Test
    public void onlineRedemptionIsSurfaced() {
        List<CardMatch> results = engine.search("adidas", wallet);
        assertTrue(results.get(0).hasOnlineMatch());

        assertFalse(engine.search("castro", wallet).get(0).hasOnlineMatch());
    }

    @Test
    public void emptyQueryReturnsWholeWallet() {
        assertEquals(2, engine.search("", wallet).size());
        assertEquals(2, engine.search("   ", wallet).size());
        assertEquals(2, engine.search(null, wallet).size());
    }

    @Test
    public void unknownStoreMatchesNothing() {
        assertTrue(engine.search("wolt", wallet).isEmpty());
    }

    @Test
    public void handlesEmptyWallet() {
        assertTrue(engine.search("za", Collections.<CardTypeIndex>emptyList()).isEmpty());
    }

    @Test
    public void matchedStoreListIsCapped() {
        List<Store> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new Store("Store " + i, Collections.<String>emptyList(), false));
        }
        CardTypeIndex big = new CardTypeIndex("big", "Big", null, many, 0L, "test");

        List<CardMatch> results = engine.search("store", Collections.singletonList(big));
        assertEquals(SearchEngine.DEFAULT_MAX_STORES_PER_CARD,
                results.get(0).getMatchedStores().size());
        // The cap is display-only; the true count stays available for "+17 more".
        assertEquals(20, engine.countMatchingStores("store", big));
    }

    // --- the reverse lookup: which shops does one card work in? ---

    private static List<Store> oneCardsStores() {
        // Deliberately not in alphabetical order, so a test that expects ordering is
        // testing the sort rather than the fixture.
        return Arrays.asList(
                new Store("Zara", Arrays.asList("זארה"), false),
                new Store("adidas", Arrays.asList("אדידס", "ריבוק"), true),
                new Store("Pizza Hut", Arrays.asList("פיצה האט"), false),
                new Store("Aroma", Arrays.asList("ארומה", "בתי קפה"), false));
    }

    @Test
    public void matchingStoresReturnsEverythingForAnEmptyQuery() {
        // The screen opens with no query, and it has to show the whole list rather than
        // nothing at all.
        assertEquals(4, engine.matchingStores("", oneCardsStores()).size());
        assertEquals(4, engine.matchingStores("   ", oneCardsStores()).size());
    }

    @Test
    public void matchingStoresRanksWholeNamesAboveFragments() {
        // "za" is the whole of nothing, the start of "Zara" and buried inside "Pizza Hut".
        List<Store> hits = engine.matchingStores("za", oneCardsStores());
        assertEquals(2, hits.size());
        assertEquals("Zara", hits.get(0).getName());
        assertEquals("Pizza Hut", hits.get(1).getName());
    }

    @Test
    public void matchingStoresKeepsTheGivenOrderWithinOneRelevanceBand() {
        // The screen sorts once, alphabetically, and relies on this to hold that ordering
        // inside each band. Both of these match only as a substring.
        List<Store> alphabetical = Arrays.asList(
                new Store("Aroma", Collections.<String>emptyList(), false),
                new Store("Zaroma", Collections.<String>emptyList(), false));
        List<Store> hits = engine.matchingStores("rom", alphabetical);
        assertEquals(2, hits.size());
        assertEquals("Aroma", hits.get(0).getName());
        assertEquals("Zaroma", hits.get(1).getName());
    }

    @Test
    public void matchingStoresFindsAShopThroughItsAlias() {
        List<Store> hits = engine.matchingStores("ריבוק", oneCardsStores());
        assertEquals(1, hits.size());
        assertEquals("adidas", hits.get(0).getName());
    }

    @Test
    public void matchingStoresSurvivesTheWrongKeyboardLayout() {
        // The same forgiveness the wallet search gives. "tshsx" is "אדידס" typed with the
        // layout in the wrong language, and it has to find adidas here too.
        List<Store> hits = engine.matchingStores("tshsx", oneCardsStores());
        assertEquals(1, hits.size());
        assertEquals("adidas", hits.get(0).getName());
    }

    @Test
    public void matchingStoresReturnsNothingRatherThanEverythingWhenNothingMatches() {
        // The empty-query shortcut must not swallow a real query that simply misses.
        assertTrue(engine.matchingStores("nosuchshop", oneCardsStores()).isEmpty());
        assertTrue(engine.matchingStores("za", Collections.<Store>emptyList()).isEmpty());
    }

    @Test
    public void matchingStoresPutsANameHitAboveAnAliasHit() {
        // "cafe" is the whole of one shop's search terms and only part of another's name.
        // The higher raw score belongs to the search term, but the shop actually called
        // Cafe Mayer is the one a person scanning the list expects to see first.
        List<Store> stores = Arrays.asList(
                new Store("סילו תרבות", Arrays.asList("cafe"), false),
                new Store("CAFE MAYER", Collections.<String>emptyList(), false));

        List<Store> hits = engine.matchingStores("cafe", stores);
        assertEquals(2, hits.size());
        assertEquals("CAFE MAYER", hits.get(0).getName());
        assertEquals("סילו תרבות", hits.get(1).getName());
    }

    @Test
    public void matchingStoresStillRanksNameHitsAmongThemselves() {
        // Within the shops named for the query, the ordinary tiers still apply.
        List<Store> stores = Arrays.asList(
                new Store("Coffee Cafe Bar", Collections.<String>emptyList(), false),
                new Store("Cafe Mayer", Collections.<String>emptyList(), false),
                new Store("Tagged Only", Arrays.asList("cafe"), false));

        List<Store> hits = engine.matchingStores("cafe", stores);
        assertEquals(3, hits.size());
        assertEquals("Cafe Mayer", hits.get(0).getName());
        assertEquals("Coffee Cafe Bar", hits.get(1).getName());
        assertEquals("Tagged Only", hits.get(2).getName());
    }
}
