package com.mycards.data.source.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * A wrong balance here produces a false "unlogged transaction" alert and pushes the user to
 * record a purchase that never happened, so these tests are mostly about the provider
 * refusing to answer when it is not certain.
 */
public class GiftPageBalanceProviderTest {

    @Test
    public void readsASingleUnambiguousBalance() {
        assertEquals(Double.valueOf(250d),
                GiftPageBalanceProvider.extractBalance("<div>יתרה בכרטיס: ₪250</div>"));
        assertEquals(Double.valueOf(250d),
                GiftPageBalanceProvider.extractBalance("<div>Remaining balance: 250 ₪</div>"));
        assertEquals(Double.valueOf(1250.5d),
                GiftPageBalanceProvider.extractBalance("<p>יתרה: ₪1,250.50</p>"));
    }

    @Test
    public void refusesWhenSeveralAmountsAreOnThePage() {
        // Prices or denominations alongside the balance make any pick a guess.
        assertNull(GiftPageBalanceProvider.extractBalance(
                "<div>יתרה: ₪250</div><div>מחיר: ₪99</div>"));
    }

    @Test
    public void refusesWithoutBalanceWording() {
        // A marketing or login page that merely happens to show a price.
        assertNull(GiftPageBalanceProvider.extractBalance("<div>קנה עכשיו ב-₪250</div>"));
    }

    @Test
    public void refusesOnEmptyOrIrrelevantPages() {
        assertNull(GiftPageBalanceProvider.extractBalance(null));
        assertNull(GiftPageBalanceProvider.extractBalance(""));
        assertNull(GiftPageBalanceProvider.extractBalance("<html><body>Please log in</body></html>"));
        // Balance wording present but no readable figure.
        assertNull(GiftPageBalanceProvider.extractBalance("<div>יתרה: לא זמין</div>"));
    }

    @Test
    public void repeatedIdenticalAmountsStillCount() {
        // The same figure rendered twice (mobile and desktop markup) is not ambiguity.
        assertEquals(Double.valueOf(250d), GiftPageBalanceProvider.extractBalance(
                "<span>יתרה ₪250</span><span class=\"mobile\">₪250</span>"));
    }
}
