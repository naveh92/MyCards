package com.mycards.search;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The expected values here are not invented — they are real aliases shipped in BuyMe's own
 * {@code searchTerms} field for adidas and Reebok, which is strong evidence the layout
 * table matches what Israeli users actually mistype.
 */
public class HebrewKeyboardMapperTest {

    @Test
    public void hebrewWordTypedOnEnglishLayout() {
        // אדידס (adidas) typed while the keyboard is still in English.
        assertEquals("tshsx", HebrewKeyboardMapper.heToEn("אדידס"));
        // ריבוק (Reebok), likewise.
        assertEquals("rhcue", HebrewKeyboardMapper.heToEn("ריבוק"));
    }

    @Test
    public void englishWordTypedOnHebrewLayout() {
        assertEquals("שגןגשד", HebrewKeyboardMapper.enToHe("adidas"));
        assertEquals("זשרש", HebrewKeyboardMapper.enToHe("zara"));
    }

    @Test
    public void roundTripsBetweenLayouts() {
        assertEquals("adidas", HebrewKeyboardMapper.heToEn(HebrewKeyboardMapper.enToHe("adidas")));
    }

    @Test
    public void returnsEmptyWhenNothingIsMappable() {
        // Digits alone carry no layout information, so no alternative reading exists.
        assertEquals("", HebrewKeyboardMapper.enToHe("123"));
        assertEquals("", HebrewKeyboardMapper.heToEn("123"));
        assertEquals("", HebrewKeyboardMapper.enToHe(null));
    }
}
