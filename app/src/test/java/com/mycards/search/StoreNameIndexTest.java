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
        // Note which shop it finds. The Hebrew-named one matches; the Latin "adidas" does
        // not, because only the shop's own name is searched here and "אדידס" reaches that
        // one through an alias — which the cache no longer holds as anyone wrote it, and
        // so is not worth putting in front of someone as a suggestion.
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
}
