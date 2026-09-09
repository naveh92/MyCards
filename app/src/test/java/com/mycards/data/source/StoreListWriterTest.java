package com.mycards.data.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mycards.search.Store;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * What survives a trip through the local cache.
 *
 * <p>The cache used to keep each merchant's normalized match keys, which round-trip into an
 * identical index and into an unreadable explanation: a row telling someone it matched
 * "carolinalemke" has said nothing except that the app mangles text. Keeping the aliases as
 * written costs a few characters each and is what lets a row name the spelling it read.
 */
public class StoreListWriterTest {

    private static List<Store> reread(String json) throws Exception {
        try (InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            return StoreListJson.parseCompactList(in);
        }
    }

    private static String write(Store... stores) {
        return StoreListWriter.toCompactJson("test_card", "bundled_asset", Arrays.asList(stores));
    }

    @Test
    public void aliasesComeBackAsTheyWereWritten() throws Exception {
        Store carolina = new Store("CAROLINA LEMKE",
                Arrays.asList("קרולינה למקה", "משקפי שמש"), false);

        List<Store> back = reread(write(carolina));
        assertEquals(1, back.size());
        assertEquals(Arrays.asList("CAROLINA LEMKE", "קרולינה למקה", "משקפי שמש"),
                back.get(0).getForms());
    }

    @Test
    public void anAliasThatOnlyRestatesTheNameIsDropped() {
        // "Carolina Lemke" and "CAROLINA LEMKE" are one spelling as far as matching is
        // concerned, and keeping both would put a second copy of half the merchant list in
        // the cache to say nothing new.
        Store carolina = new Store("CAROLINA LEMKE",
                Arrays.asList("Carolina Lemke", "carolina-lemke", "קרולינה למקה"), false);
        assertEquals(Arrays.asList("CAROLINA LEMKE", "קרולינה למקה"), carolina.getForms());
    }

    @Test
    public void theNameIsNotRepeatedAmongTheAliases() {
        // It is already written as "n"; writing it again would grow every entry in a
        // megabyte-scale document for nothing.
        String json = write(new Store("Zara", Arrays.asList("זארה"), false));
        assertEquals(1, countOf(json, "Zara"));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    public void aRoundTripPreservesEverythingMatchingNeeds() throws Exception {
        Store original = new Store("מסעדת MOSHIK& של השף מושיק רוט",
                Arrays.asList("מושיק רוט", "chef", "שף תל אביב"), true);

        Store back = reread(write(original)).get(0);
        assertEquals(original.getName(), back.getName());
        assertEquals(original.getForms(), back.getForms());
        assertTrue(back.isOnlineRedeem());
    }

    @Test
    public void writingIsStableAcrossRoundTrips() throws Exception {
        // The cache is rewritten from what was read out of it on every sync, so a format
        // that drifted a little each time would drift a long way over a year.
        Store original = new Store("Fox Home", Arrays.asList("פוקס הום", "לבית"), false);
        String once = write(original);
        String twice = write(reread(once).toArray(new Store[0]));
        assertEquals(once, twice);
    }

    @Test
    public void theSnapshotDeclaresItsFormat() throws Exception {
        // How an install carrying a cache from an older build is recognised, so it can be
        // re-seeded rather than read as if it were current.
        String json = write(new Store("Zara", Collections.<String>emptyList(), false));
        try (InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            assertEquals(StoreListWriter.FORMAT_VERSION, StoreListJson.readFormatVersion(in));
        }
    }

    @Test
    public void aSnapshotWithNoMarkerReadsAsVersionZero() throws Exception {
        String legacy = "{\"cardTypeId\":\"test\",\"source\":\"bundled_asset\","
                + "\"stores\":[{\"n\":\"Zara\",\"a\":[\"זארה\"]}]}";
        try (InputStream in = new ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8))) {
            assertEquals(0, StoreListJson.readFormatVersion(in));
        }
        // And still parses, because it is the data on every phone that has the app today.
        assertEquals("Zara", reread(legacy).get(0).getName());
    }

    @Test
    public void aMalformedSnapshotReadsAsVersionZeroRatherThanThrowing() throws Exception {
        try (InputStream in = new ByteArrayInputStream("not json".getBytes(StandardCharsets.UTF_8))) {
            assertEquals(0, StoreListJson.readFormatVersion(in));
        }
    }

    @Test
    public void aShopWithNoAliasesWritesNoAliasArray() throws Exception {
        Store bare = new Store("Zara", Collections.<String>emptyList(), false);
        Store back = reread(write(bare)).get(0);
        assertEquals(Collections.singletonList("Zara"), back.getForms());
    }
}
