package com.mycards.data.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.catalog.model.SourceDef;
import com.mycards.data.source.StoreListJson;
import com.mycards.search.CardMatch;
import com.mycards.search.CardTypeIndex;
import com.mycards.search.SearchEngine;
import com.mycards.search.Store;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the catalog and merchant lists the app actually ships and puts them through the same
 * parser and search the app uses.
 *
 * <p>These files are hand-written JSON and are also served to phones over the network, where a
 * mistake is not caught by the compiler and reaches everyone at once. The checks here are the
 * ones whose failure would be invisible: a card type pointing at a merchant file that is not
 * there yields a card with no shops and no error, and a list that parses but matches nothing
 * yields a card that is silently never the answer to any search.
 */
public class ShippedCatalogTest {

    private static final File ASSETS = new File("src/main/assets");

    private static Catalog catalog() throws Exception {
        try (InputStream in = new FileInputStream(new File(ASSETS, "catalog.json"))) {
            return new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), Catalog.class);
        }
    }

    private static List<Store> stores(String asset) throws Exception {
        try (InputStream in = new FileInputStream(new File(ASSETS, asset))) {
            return StoreListJson.parseCompactList(in);
        }
    }

    private static CardTypeIndex indexFor(String id) throws Exception {
        CardTypeDef def = catalog().findById(id);
        assertNotNull("card type " + id + " is missing from the catalog", def);
        return new CardTypeIndex(id, def.displayName("he"), def.properNames(),
                def.aliasesOrEmpty(), stores("stores/" + id + ".json"), 0L, "test",
                def.partialList);
    }

    private static boolean matches(CardTypeIndex index, String query) {
        List<CardMatch> hits = new SearchEngine().search(query, Collections.singletonList(index));
        return !hits.isEmpty() && !hits.get(0).getMatchedStores().isEmpty();
    }

    @Test
    public void theShippedCatalogParses() throws Exception {
        Catalog catalog = catalog();
        assertNotNull(catalog);
        assertTrue("the catalog must declare a usable schema", catalog.isUsable(1));
        assertFalse(catalog.cardTypes.isEmpty());
    }

    /**
     * A catalog entry naming a bundled file that does not exist produces a card type with no
     * merchants at all, and nothing anywhere reports it — the card simply never matches.
     */
    @Test
    public void everyBundledMerchantFileNamedByTheCatalogExists() throws Exception {
        List<String> missing = new ArrayList<>();
        for (CardTypeDef def : catalog().cardTypes) {
            for (SourceDef source : def.storeSourcesOrEmpty()) {
                if ("bundled_asset".equals(source.type) && source.asset != null
                        && !new File(ASSETS, source.asset).exists()) {
                    missing.add(def.id + " -> " + source.asset);
                }
            }
        }
        assertEquals("catalog entries point at merchant files that are not shipped: " + missing,
                Collections.<String>emptyList(), missing);
    }

    @Test
    public void everyBundledMerchantFileParsesAndHoldsShops() throws Exception {
        List<String> broken = new ArrayList<>();
        for (CardTypeDef def : catalog().cardTypes) {
            for (SourceDef source : def.storeSourcesOrEmpty()) {
                if (!"bundled_asset".equals(source.type) || source.asset == null) {
                    continue;
                }
                try {
                    if (stores(source.asset).isEmpty()) {
                        broken.add(source.asset + " (no shops)");
                    }
                } catch (Exception e) {
                    broken.add(source.asset + " (" + e + ")");
                }
            }
        }
        assertEquals(Collections.<String>emptyList(), broken);
    }

    // --- the Israeli vouchers added on 2026-08-01 ---

    @Test
    public void tavHazahavIsFoundByItsOwnNameInBothLanguages() throws Exception {
        CardTypeDef def = catalog().findById("tav_hazahav");
        assertNotNull(def);
        assertEquals("תו הזהב", def.displayName("he"));
        assertEquals("Tav HaZahav", def.displayName("en"));
    }

    /**
     * The assumption worth guarding: תו הזהב is Shufersal's, but it is not Shufersal-only.
     * If this ever narrows to the supermarket, the app starts telling people at a Castro
     * counter that their card is refused.
     */
    @Test
    public void tavHazahavCoversTheChainsBeyondShufersal() throws Exception {
        CardTypeIndex index = indexFor("tav_hazahav");

        assertTrue("shufersal", matches(index, "שופרסל"));
        assertTrue("castro in Hebrew", matches(index, "קסטרו"));
        assertTrue("castro in English", matches(index, "castro"));
        assertTrue("fox", matches(index, "fox"));
        assertTrue("golf", matches(index, "גולף"));
        assertTrue("adidas", matches(index, "adidas"));
        assertTrue("mango", matches(index, "מנגו"));
        assertTrue("steimatzky", matches(index, "סטימצקי"));
        assertTrue("ace", matches(index, "ace"));
        assertTrue("home center", matches(index, "הום סנטר"));
    }

    @Test
    public void tavHazahavIsLargeEnoughToBeWorthShipping() throws Exception {
        assertTrue("the issuer claims 90+ chains; a much shorter list means something was lost",
                stores("stores/tav_hazahav.json").size() >= 90);
    }

    @Test
    public void theNewListsAreAllFlaggedPartial() throws Exception {
        // None of the three comes from its issuer, so none may let the app say "not accepted".
        for (String id : new String[]{"tav_hazahav", "hatav_hamale", "tav_plus"}) {
            assertTrue(id + " must be marked partial", indexFor(id).isPartialList());
        }
    }

    @Test
    public void theRamiLeviAndCarrefourVouchersFindTheirOwnSupermarkets() throws Exception {
        assertTrue(matches(indexFor("hatav_hamale"), "רמי לוי"));
        assertTrue(matches(indexFor("hatav_hamale"), "ישראייר"));
        assertTrue(matches(indexFor("tav_plus"), "קרפור"));
        assertTrue(matches(indexFor("tav_plus"), "יינות ביתן"));
    }

    /** Typing the card's own name has to find it, not just the shops inside it. */
    @Test
    public void theVouchersAreFoundByCardNameIncludingWrongKeyboardLayout() throws Exception {
        assertFalse(new SearchEngine()
                .search("תו הזהב", Collections.singletonList(indexFor("tav_hazahav"))).isEmpty());
        assertFalse(new SearchEngine()
                .search("tav hazahav", Collections.singletonList(indexFor("tav_hazahav"))).isEmpty());
        assertFalse(new SearchEngine()
                .search("התו המלא", Collections.singletonList(indexFor("hatav_hamale"))).isEmpty());
        assertFalse(new SearchEngine()
                .search("תו פלוס", Collections.singletonList(indexFor("tav_plus"))).isEmpty());
    }
}
