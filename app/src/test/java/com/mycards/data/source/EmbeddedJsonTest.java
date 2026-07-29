package com.mycards.data.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonElement;
import com.mycards.data.catalog.model.SourceDef;
import com.mycards.search.Store;

import org.junit.Test;

import java.util.List;

public class EmbeddedJsonTest {

    /** Mirrors HTZone: data assigned to a variable inside a script block. */
    private static final String PAGE =
            "<html><head><script>var config={a:1};</script></head><body>"
                    + "<script>\n  business_arr = {\"business\":["
                    + "{\"id\":\"7808\",\"name\":\"מסעדת Moshik&\",\"eng_name\":\"\","
                    + "\"is_honored_online\":\"0\",\"text\":\"מסעדות\"},"
                    + "{\"id\":\"5\",\"name\":\"אדידס\",\"eng_name\":\"adidas\","
                    + "\"is_honored_online\":\"1\",\"text\":\"אופנה\"}"
                    + "]};\n</script></body></html>";

    private static SourceDef def() {
        SourceDef d = new SourceDef();
        d.type = "embedded_json";
        d.varName = "business_arr";
        d.itemsPath = "business";
        d.namePath = "name";
        d.aliasesPath = "eng_name";
        d.onlinePath = "is_honored_online";
        return d;
    }

    @Test
    public void extractsTheAssignedObject() {
        JsonElement el = EmbeddedJson.extract(PAGE, "business_arr");
        assertNotNull(el);
        assertEquals(2, el.getAsJsonObject().getAsJsonArray("business").size());
    }

    @Test
    public void mapsToStoresUsingCatalogPaths() {
        List<Store> stores = JsonExtract.toStores(EmbeddedJson.extract(PAGE, "business_arr"), def());

        assertEquals(2, stores.size());
        assertEquals("מסעדת Moshik&", stores.get(0).getName());
        assertEquals("אדידס", stores.get(1).getName());
        // "1" as a string still means online — issuers are inconsistent about this.
        org.junit.Assert.assertTrue(stores.get(1).isOnlineRedeem());
        org.junit.Assert.assertFalse(stores.get(0).isOnlineRedeem());
    }

    @Test
    public void bracesInsideStringsDoNotEndTheObject() {
        // Addresses and terms really do contain braces; a regex would stop at the first one.
        String tricky = "x = {\"business\":[{\"name\":\"Caf\\\"e } {\",\"id\":\"1\"}]};";
        JsonElement el = EmbeddedJson.extract(tricky, "x");
        assertNotNull("brace matching must ignore braces inside quoted strings", el);
        assertEquals(1, el.getAsJsonObject().getAsJsonArray("business").size());
    }

    @Test
    public void handlesAnArrayLiteralAsWellAsAnObject() {
        JsonElement el = EmbeddedJson.extract("window.items = [{\"n\":1},{\"n\":2}];", "window.items");
        assertNotNull(el);
        assertEquals(2, el.getAsJsonArray().size());
    }

    @Test
    public void missingVariableYieldsNull() {
        // The variable was renamed — the provider must fall through the chain, not
        // silently report an empty merchant list as a successful refresh.
        assertNull(EmbeddedJson.extract(PAGE, "renamed_arr"));
        assertNull(EmbeddedJson.extract(null, "business_arr"));
        assertNull(EmbeddedJson.extract(PAGE, ""));
    }

    @Test
    public void truncatedPageYieldsNull() {
        assertNull(EmbeddedJson.extract("business_arr = {\"business\":[{\"name\":\"x\"", "business_arr"));
    }

    @Test
    public void malformedJsonYieldsNull() {
        assertNull(EmbeddedJson.extract("business_arr = {not valid, json};", "business_arr"));
    }

    @Test
    public void unknownPathsYieldNoStoresRatherThanCrashing() {
        SourceDef d = def();
        d.itemsPath = "nope.missing";
        assertEquals(0, JsonExtract.toStores(EmbeddedJson.extract(PAGE, "business_arr"), d).size());
    }
}
