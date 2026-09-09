package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * The complaint this whole change came from: a shop findable in one language and not the
 * other.
 *
 * <p>An Israeli merchant list is bilingual by accident rather than by design. Whoever wrote
 * it filed each shop under whichever name they had — adidas in Latin, קסטרו in Hebrew — and
 * put the other language in the aliases, or did not. Which half of the list a person can
 * reach should not depend on that.
 *
 * <p><b>The fixtures are written out here on purpose.</b> Reading the shipped lists would
 * make this file a test of today's merchant data: it would go red when a shop closes, and —
 * far worse — go green for the wrong reason when an alias someone happens to have typed into
 * a JSON file papers over a matching rule that does not work. Every store below is
 * constructed in the shape the real data has, so what is under test is the matching.
 */
public class BilingualSearchTest {

    private final SearchEngine engine = new SearchEngine();

    /**
     * Brand names in both scripts, in the two shapes real lists come in.
     *
     * <p>Each row is {latin, hebrew}. They are spelled as Israelis spell them rather than as
     * a transliteration table would, because that is what people type.
     */
    private static final String[][] BRANDS = {
            {"adidas", "אדידס"},
            {"Zara", "זארה"},
            {"Castro", "קסטרו"},
            {"Fox Home", "פוקס הום"},
            {"Hoodies", "הודיס"},
            {"Carolina Lemke", "קרולינה למקה"},
            {"Top Ten", "טופ טן"},
            {"Kiko Milano", "קיקו מילאנו"},
            {"Yves Rocher", "איב רושה"},
            {"Pizza Hut", "פיצה האט"},
            {"Aroma", "ארומה"},
            {"Super-Pharm", "סופר פארם"},
            {"Golf", "גולף"},
            {"Renuar", "רנואר"},
            {"American Eagle", "אמריקן איגל"},
            {"Mango", "מנגו"},
            {"Nike", "נייקי"},
            {"Puma", "פומה"},
            {"Steimatzky", "סטימצקי"},
            {"Max Stock", "מקס סטוק"},
            {"Urbanica", "אורבניקה"},
            {"Tommy Hilfiger", "טומי הילפיגר"},
            {"Cafe Cafe", "קפה קפה"},
            {"Landver", "לנדוור"},
            {"Bug", "באג"},
            {"KSP", "קיי אס פי"},
            {"Ivory", "אייבורי"},
            {"Delta", "דלתא"},
            {"Fox", "פוקס"},
            {"Naaman", "נעמן"},
    };

    /** A card listing every brand under its Latin name, with the Hebrew as an alias. */
    private static CardTypeIndex latinNamed() {
        List<Store> stores = new ArrayList<>();
        for (String[] brand : BRANDS) {
            stores.add(new Store(brand[0], Arrays.asList(brand[1]), false));
        }
        return card("latin_list", stores);
    }

    /** The same list written the other way round, which real lists also are. */
    private static CardTypeIndex hebrewNamed() {
        List<Store> stores = new ArrayList<>();
        for (String[] brand : BRANDS) {
            stores.add(new Store(brand[1], Arrays.asList(brand[0]), false));
        }
        return card("hebrew_list", stores);
    }

    private static CardTypeIndex card(String id, List<Store> stores) {
        return new CardTypeIndex(id, "Test Card", Collections.<String>emptyList(),
                stores, 0L, "test");
    }

    /** The shops a query turns up on one card, in the order the screen would show them. */
    private List<String> shopsFor(String query, CardTypeIndex index) {
        List<String> names = new ArrayList<>();
        for (StoreMatch hit : engine.matchingStores(query, index.getStores())) {
            names.add(hit.getName());
        }
        return names;
    }

    // --- both languages reach every shop, whichever way the list was written ---

    @Test
    public void everyBrandIsFoundByItsLatinName() {
        for (String[] brand : BRANDS) {
            assertTrue(brand[0] + " on a Latin-named list",
                    shopsFor(brand[0], latinNamed()).contains(brand[0]));
            assertTrue(brand[0] + " on a Hebrew-named list",
                    shopsFor(brand[0], hebrewNamed()).contains(brand[1]));
        }
    }

    @Test
    public void everyBrandIsFoundByItsHebrewName() {
        for (String[] brand : BRANDS) {
            assertTrue(brand[1] + " on a Hebrew-named list",
                    shopsFor(brand[1], hebrewNamed()).contains(brand[1]));
            assertTrue(brand[1] + " on a Latin-named list",
                    shopsFor(brand[1], latinNamed()).contains(brand[0]));
        }
    }

    @Test
    public void everyBrandIsFoundFromAPrefixInEitherLanguage() {
        // Nobody types a whole name at a checkout counter.
        for (String[] brand : BRANDS) {
            String latin = prefix(brand[0]);
            String hebrew = prefix(brand[1]);
            assertTrue(latin + " should reach " + brand[0],
                    shopsFor(latin, latinNamed()).contains(brand[0]));
            assertTrue(hebrew + " should reach " + brand[1],
                    shopsFor(hebrew, hebrewNamed()).contains(brand[1]));
        }
    }

    /** Enough of a name to be worth offering: four letters, or the whole of a shorter one. */
    private static String prefix(String name) {
        String tight = name.replace(" ", "").replace("-", "");
        return tight.length() <= 4 ? tight : tight.substring(0, 4);
    }

    // --- the card list, not just the shop list ---

    @Test
    public void bothLanguagesSurfaceTheCardThatCoversTheShop() {
        List<CardTypeIndex> wallet = Arrays.asList(latinNamed(), hebrewNamed());
        for (String[] brand : BRANDS) {
            for (String query : new String[] {brand[0], brand[1]}) {
                List<CardMatch> matches = engine.search(query, wallet);
                assertEquals(query + " should reach both cards", 2, matches.size());
            }
        }
    }

    // --- the row has to be able to say what it read ---

    @Test
    public void aHebrewQueryAgainstALatinListReportsTheHebrewSpelling() {
        // The screenshot case. The shop is filed as "Carolina Lemke"; the query was Hebrew.
        // Showing the Latin name alone is what made the row look like a mistake.
        StoreMatch hit = engine.matchingStores("קרולינה", latinNamed().getStores()).get(0);
        assertEquals("Carolina Lemke", hit.getName());
        assertFalse("the name is Latin, so the name is not what matched", hit.isByName());
        assertEquals("קרולינה למקה", hit.getMatchedForm());
    }

    @Test
    public void aLatinQueryAgainstAHebrewListReportsTheLatinSpelling() {
        StoreMatch hit = engine.matchingStores("carolina", hebrewNamed().getStores()).get(0);
        assertEquals("קרולינה למקה", hit.getName());
        assertFalse(hit.isByName());
        assertEquals("Carolina Lemke", hit.getMatchedForm());
    }

    @Test
    public void aQueryInTheListsOwnLanguageIsReportedAsANameHit() {
        // Nothing to explain here, and a row that explained anyway would be noise.
        StoreMatch hit = engine.matchingStores("adidas", latinNamed().getStores()).get(0);
        assertTrue(hit.isByName());
        assertEquals("adidas", hit.getMatchedForm());
    }

    // --- the wrong keyboard layout, in both directions ---

    @Test
    public void theWrongKeyboardLayoutStillFindsTheShop() {
        // "אדידס" typed with the layout stuck in English, and "adidas" typed with it stuck
        // in Hebrew. Both are the single most common way an Israeli query goes wrong.
        assertTrue(shopsFor("tshsx", latinNamed()).contains("adidas"));
        assertTrue(shopsFor("שגןגשד", latinNamed()).contains("adidas"));
        assertTrue(shopsFor("tshsx", hebrewNamed()).contains("אדידס"));
    }

    // --- and it still has to say no ---

    @Test
    public void aShopThatIsNotOnTheListIsNotInvented() {
        // The one answer this app must never give. Both scripts, both list shapes.
        for (String query : Arrays.asList("Decathlon", "דקטלון", "IKEA", "איקאה")) {
            assertTrue(query + " is on neither list", shopsFor(query, latinNamed()).isEmpty());
            assertTrue(query + " is on neither list", shopsFor(query, hebrewNamed()).isEmpty());
        }
    }
}
