package com.mycards.ui.edit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.search.SearchEngine;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Covers finding a card type in the "Add card" picker by typing its name.
 *
 * <p>A separate path from the main search — the picker filters a dropdown rather than
 * searching merchants — so it can regress on its own. The case that matters is a Hebrew name
 * on a phone running in English: the picker shows one label, chosen by the phone's language,
 * and would find nothing if it only indexed the label it displays.
 */
public class CardTypePickerTest {

    private static CardTypeDef def(String id) throws Exception {
        try (InputStream in = new FileInputStream(new File("src/main/assets/catalog.json"))) {
            Catalog catalog = new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), Catalog.class);
            return catalog.findById(id);
        }
    }

    /** Built exactly as the picker builds it for a phone running in English. */
    private static CardTypeAdapter.Option englishOption(String id) throws Exception {
        CardTypeDef def = def(id);
        return new CardTypeAdapter.Option(def, def.displayName("en"));
    }

    private static boolean typing(CardTypeAdapter.Option option, String typed) {
        List<String> variants = SearchEngine.queryVariants(typed);
        return option.matches(variants);
    }

    @Test
    public void theHebrewNameFindsTheCardTypeOnAnEnglishPhone() throws Exception {
        CardTypeAdapter.Option option = englishOption("tav_hazahav");

        assertTrue("תו הזהב", typing(option, "תו הזהב"));
        assertTrue("תו", typing(option, "תו"));
        assertTrue("הזהב", typing(option, "הזהב"));
    }

    @Test
    public void theEnglishNameStillFindsIt() throws Exception {
        CardTypeAdapter.Option option = englishOption("tav_hazahav");

        assertTrue("tav", typing(option, "tav"));
        assertTrue("zahav", typing(option, "zahav"));
        assertTrue("hazahav", typing(option, "hazahav"));
    }

    /** Typing the issuer is a reasonable thing to try when you cannot recall the card's name. */
    @Test
    public void theIssuerFindsIt() throws Exception {
        assertTrue(typing(englishOption("tav_hazahav"), "shufersal"));
        assertTrue(typing(englishOption("tav_hazahav"), "שופרסל"));
        assertTrue(typing(englishOption("hatav_hamale"), "רמי לוי"));
        assertTrue(typing(englishOption("tav_plus"), "carrefour"));
    }

    @Test
    public void theOtherTwoVouchersAreFoundInHebrewToo() throws Exception {
        assertTrue(typing(englishOption("hatav_hamale"), "התו המלא"));
        assertTrue(typing(englishOption("tav_plus"), "תו פלוס"));
    }

    /** The picker still has to discriminate, or every query would show all 32 types. */
    @Test
    public void anUnrelatedQueryDoesNotMatch() throws Exception {
        assertFalse(typing(englishOption("tav_hazahav"), "buyme"));
        assertFalse(typing(englishOption("tav_plus"), "azrieli"));
    }
}
