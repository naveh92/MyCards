package com.mycards.data.catalog.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

public class SyncManifestTest {

    private static final String SAMPLE =
            "{\"manifestVersion\":1,\"generatedAt\":\"2026-07-29T09:36:42Z\","
                    + "\"catalog\":{\"sha256\":\"aaa\",\"bytes\":11819},"
                    + "\"stores\":{"
                    + "\"buyme_all\":{\"sha256\":\"bbb\",\"bytes\":503329,\"count\":1276},"
                    + "\"buyme_chef\":{\"sha256\":\"ccc\",\"bytes\":34118,\"count\":84}"
                    + "}}";

    @Test
    public void parsesPublishedHashes() {
        SyncManifest m = new Gson().fromJson(SAMPLE, SyncManifest.class);

        assertTrue(m.isUsable(1));
        assertEquals("bbb", m.hashFor("buyme_all"));
        assertEquals("ccc", m.hashFor("buyme_chef"));
        assertEquals(1276, m.stores.get("buyme_all").count);
    }

    @Test
    public void unpublishedCardTypeHasNoHash() {
        SyncManifest m = new Gson().fromJson(SAMPLE, SyncManifest.class);
        // A card type with no published list must fall through to the normal chain
        // rather than be treated as "unchanged" and skipped forever.
        assertNull(m.hashFor("all_in_zone"));
    }

    @Test
    public void rejectsANewerSchemaThanThisBuildUnderstands() {
        SyncManifest m = new Gson().fromJson(
                SAMPLE.replace("\"manifestVersion\":1", "\"manifestVersion\":9"),
                SyncManifest.class);
        assertFalse(m.isUsable(1));
    }

    @Test
    public void rejectsAnEmptyOrMalformedManifest() {
        assertFalse(new Gson().fromJson("{\"manifestVersion\":1}", SyncManifest.class).isUsable(1));
        assertFalse(new Gson().fromJson(
                "{\"manifestVersion\":1,\"stores\":{}}", SyncManifest.class).isUsable(1));
    }

    @Test
    public void missingStoresMapIsSafeToQuery() {
        SyncManifest m = new SyncManifest();
        assertNull(m.hashFor("buyme_all"));
        assertFalse(m.isUsable(1));
    }
}
