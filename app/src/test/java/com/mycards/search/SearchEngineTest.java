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
}
