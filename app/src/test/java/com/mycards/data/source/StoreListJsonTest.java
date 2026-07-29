package com.mycards.data.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mycards.search.SearchEngine;
import com.mycards.search.CardTypeIndex;
import com.mycards.search.Store;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public class StoreListJsonTest {

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Mirrors the real shape of BuyMe's payload, including its double-encoded aliases. */
    private static final String BUYME_SAMPLE =
            "{\"supplier\":{\"name\":\"BUYME ALL\"},"
                    + "\"brands\":["
                    + "{\"id\":1,\"title\":\"adidas\",\"logo\":\"x.jpg\","
                    + "\"searchTerms\":\"[\\\"אדידס\\\", \\\"tshsx\\\", \\\"#casualedge#\\\"]\","
                    + "\"online_redeem\":true},"
                    + "{\"id\":2,\"title\":\"  Zara  \",\"searchTerms\":null,"
                    + "\"online_redeem\":false},"
                    + "{\"id\":3,\"title\":\"\",\"searchTerms\":\"[]\"},"
                    + "{\"id\":4,\"title\":\"Castro\",\"searchTerms\":\"not valid json\"}"
                    + "],\"categories\":[],\"regions\":[]}";

    @Test
    public void parsesBrandsAndSkipsIrrelevantFields() throws Exception {
        List<Store> stores = StoreListJson.parseBuyMeBrands(stream(BUYME_SAMPLE));

        // The blank-titled brand is dropped; the other three survive.
        assertEquals(3, stores.size());
        assertEquals("adidas", stores.get(0).getName());
        assertTrue(stores.get(0).isOnlineRedeem());
        // Titles are trimmed so " Zara " does not index with stray whitespace.
        assertEquals("Zara", stores.get(1).getName());
        assertFalse(stores.get(1).isOnlineRedeem());
    }

    @Test
    public void decodesDoubleEncodedAliasesAndDropsHashtags() {
        List<String> aliases = StoreListJson.parseEmbeddedAliasArray(
                "[\"אדידס\", \"tshsx\", \"#casualedge#\", \"adidas\"]", "adidas");

        assertTrue(aliases.contains("אדידס"));
        assertTrue(aliases.contains("tshsx"));
        // Campaign tags are noise, not names anybody types at a till.
        assertFalse(aliases.contains("#casualedge#"));
        // The brand's own name is already indexed; no need to duplicate it.
        assertFalse(aliases.contains("adidas"));
    }

    @Test
    public void malformedAliasesDoNotDropTheStore() throws Exception {
        List<Store> stores = StoreListJson.parseBuyMeBrands(stream(BUYME_SAMPLE));

        // "Castro" has unparseable searchTerms but must still be findable by name —
        // silently shrinking the merchant list is the worst possible failure here.
        boolean found = false;
        for (Store s : stores) {
            if ("Castro".equals(s.getName())) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    public void compactRoundTripPreservesMatching() throws Exception {
        List<Store> original = StoreListJson.parseBuyMeBrands(stream(BUYME_SAMPLE));

        String json = StoreListWriter.toCompactJson("buyme_all", "buyme_brands", original);
        List<Store> restored = StoreListJson.parseCompactList(stream(json));

        assertEquals(original.size(), restored.size());

        // The point of the round trip is that search behaves identically afterwards,
        // which is what lets the cache and the bundled assets share one format.
        SearchEngine engine = new SearchEngine();
        CardTypeIndex before = new CardTypeIndex("x", "X", null, original, 0L, "a");
        CardTypeIndex after = new CardTypeIndex("x", "X", null, restored, 0L, "b");

        for (String query : new String[]{"adidas", "אדידס", "tshsx", "zara", "castro"}) {
            assertEquals("query: " + query,
                    engine.countMatchingStores(query, before),
                    engine.countMatchingStores(query, after));
            assertEquals("query: " + query,
                    1, engine.search(query, Collections.singletonList(after)).size());
        }
    }

    @Test
    public void toleratesBooleanEncodedAsNumber() throws Exception {
        String json = "{\"brands\":[{\"title\":\"A\",\"online_redeem\":1}]}";
        List<Store> stores = StoreListJson.parseBuyMeBrands(stream(json));
        assertTrue(stores.get(0).isOnlineRedeem());
    }

    @Test
    public void handlesEmptyBrandArray() throws Exception {
        List<Store> stores = StoreListJson.parseBuyMeBrands(stream("{\"brands\":[]}"));
        assertTrue(stores.isEmpty());
    }
}
