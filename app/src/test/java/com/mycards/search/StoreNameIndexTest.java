package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class StoreNameIndexTest {

    private StoreNameIndex index;

    @Before
    public void setUp() {
        index = StoreNameIndex.of(Arrays.asList(
                "Aroma", "Aroma Espresso Bar", "Cafe Aroma", "Zara", "adidas",
                "אדידס", "פיצה האט"));
    }

    @Test
    public void saysNothingUntilThereIsEnoughToGoOn() {
        // One character narrows nothing, and a row of shops appearing at the first keystroke
        // is the overbearing behaviour this is meant to avoid.
        assertTrue(index.suggest("", 3).isEmpty());
        assertTrue(index.suggest("a", 3).isEmpty());
        assertTrue(index.suggest("   ", 3).isEmpty());
        assertTrue(index.suggest(null, 3).isEmpty());
        assertEquals(3, index.suggest("ar", 3).size());
    }

    @Test
    public void offersNameStartsBeforeMidWordHits() {
        List<String> hits = index.suggest("aroma", 3);
        assertEquals(Arrays.asList("Aroma", "Aroma Espresso Bar", "Cafe Aroma"), hits);
    }

    @Test
    public void neverOffersBackWhatIsAlreadyTyped() {
        // Once "Aroma" is in the field there is nothing left to offer for it, and a chip
        // that would change nothing is noise.
        List<String> hits = index.suggest("Aroma", 3);
        assertEquals(Arrays.asList("Aroma Espresso Bar", "Cafe Aroma"), hits);
    }

    @Test
    public void stillOffersACorrectlySpelledName() {
        // Typing it in lower case is not the same as having finished typing it: the chip
        // is how the log ends up with the shop's own spelling.
        assertEquals(Collections.singletonList("Zara"), index.suggest("zara", 3));
    }

    @Test
    public void honoursTheLimit() {
        assertEquals(1, index.suggest("aroma", 1).size());
        assertTrue(index.suggest("aroma", 0).isEmpty());
    }

    @Test
    public void survivesTheWrongKeyboardLayout() {
        // "tshsx" is what comes out of typing "אדידס" with the layout still in English,
        // and it has to reach the shop of that name exactly as the wallet search does.
        //
        // Note which shop it finds. Only the shop's own name is searched by an index built
        // from names alone, so the Hebrew-named shop matches and the Latin "adidas" does
        // not — see the alias-aware section below for the index the app actually builds.
        assertEquals(Collections.singletonList("אדידס"), index.suggest("tshsx", 3));
    }

    @Test
    public void matchesHebrewMidWord() {
        assertEquals(Collections.singletonList("פיצה האט"), index.suggest("צה", 3));
    }

    @Test
    public void saysNothingForAShopItHasNeverHeardOf() {
        // The whole point of keeping free text: an unlisted shop is typed and nothing
        // objects to it.
        assertTrue(index.suggest("Some Corner Shop", 3).isEmpty());
    }

    @Test
    public void handlesHavingNoListAtAll() {
        assertTrue(StoreNameIndex.empty().isEmpty());
        assertTrue(StoreNameIndex.empty().suggest("aroma", 3).isEmpty());
        assertTrue(StoreNameIndex.of(null).suggest("aroma", 3).isEmpty());
        assertTrue(StoreNameIndex.of(Arrays.asList("", "   ", "!!!")).isEmpty());
    }

    // --- the alias-aware index, which is the one the app builds ---

    /**
     * A merchant list written the way real ones are: half the shops filed under a Latin
     * name with the Hebrew in the aliases, half the other way round.
     */
    private static StoreNameIndex bilingual() {
        return StoreNameIndex.ofStores(Arrays.asList(
                new Store("adidas", Arrays.asList("אדידס", "ריבוק"), false),
                new Store("Zara", Arrays.asList("זארה"), false),
                new Store("קסטרו", Arrays.asList("Castro"), false),
                new Store("פיצה האט", Arrays.asList("Pizza Hut"), false)));
    }

    @Test
    public void findsALatinNamedShopByItsHebrewSpelling() {
        // The reported bug in the purchase-entry field: adidas was reachable by typing
        // "adid" and not by typing "אדידס", purely because of how the list was written.
        assertEquals(Collections.singletonList("adidas"), bilingual().suggest("אדידס", 3));
        assertEquals(Collections.singletonList("Zara"), bilingual().suggest("זארה", 3));
    }

    @Test
    public void findsAHebrewNamedShopByItsLatinSpelling() {
        assertEquals(Collections.singletonList("קסטרו"), bilingual().suggest("Castro", 3));
        assertEquals(Collections.singletonList("פיצה האט"), bilingual().suggest("Pizza", 3));
    }

    @Test
    public void alwaysOffersTheShopsOwnNameWhateverWasTyped() {
        // The suggestion is what gets logged. "adidas" is the right thing to have in the
        // purchase record whichever language reached it, and offering back the alias would
        // fill the log with two spellings of one shop.
        assertEquals(Collections.singletonList("adidas"), bilingual().suggest("ריבוק", 3));
    }

    @Test
    public void prefersTheShopNamedForTheQueryOverOneMerelyTaggedWithIt() {
        StoreNameIndex index = StoreNameIndex.ofStores(Arrays.asList(
                new Store("סילו תרבות", Arrays.asList("cafe"), false),
                new Store("Cafe Mayer", Collections.<String>emptyList(), false)));
        assertEquals(Arrays.asList("Cafe Mayer", "סילו תרבות"), index.suggest("cafe", 5));
    }

    @Test
    public void offersOneShopOnceEvenWhenTheListRepeatsIt() {
        // The Zone lists carry a shop once per category it sits in, and two identical chips
        // would spend both of the slots on offer saying the same thing.
        StoreNameIndex index = StoreNameIndex.ofStores(Arrays.asList(
                new Store("Aroma", Arrays.asList("קפה"), false),
                new Store("Aroma", Arrays.asList("מסעדות"), false)));
        assertEquals(Collections.singletonList("Aroma"), index.suggest("arom", 5));
    }

    @Test
    public void theAliasAwareIndexStillSaysNothingForAnUnlistedShop() {
        assertTrue(bilingual().suggest("Decathlon", 3).isEmpty());
        assertTrue(bilingual().suggest("דקטלון", 3).isEmpty());
    }

    @Test
    public void handlesHavingNoStoresAtAll() {
        assertTrue(StoreNameIndex.ofStores(null).isEmpty());
        assertTrue(StoreNameIndex.ofStores(Collections.<Store>emptyList()).isEmpty());
    }
}
