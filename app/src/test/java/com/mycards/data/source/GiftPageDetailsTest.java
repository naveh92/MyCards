package com.mycards.data.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ⚠️ Every page in here is written by hand. No real issuer page has ever been run through
 * this parser: buyme.co.il answers this machine with a Cloudflare 403, and a real gift link
 * is spendable money that should not be pasted into a test fixture.
 *
 * <p>So these tests say the rules do what they were meant to do. They do not say the rules
 * match the markup issuers actually serve — only a real link on a real phone can say that,
 * and until one has, treat the feature as unproven against reality rather than working.
 */
public class GiftPageDetailsTest {

    @Test
    public void readsABalanceAnExpiryANumberAndACvv() {
        String html = "<div>יתרה: 250 ₪</div>"
                + "<div>בתוקף עד 04/2027</div>"
                + "<div>מספר כרטיס 4580 1234 5678 9012</div>"
                + "<div>CVV: 123</div>";
        GiftPageDetails d = GiftPageDetails.parse(html);
        assertEquals(250.0d, d.amount, 0.0001d);
        assertEquals("2027-04", d.expiryMonth);
        assertEquals("4580123456789012", d.pan);
        assertEquals("123", d.cvv);
        assertEquals(4, d.count());
    }

    /** Half a form filled is most of the work saved, so fields are independent. */
    @Test
    public void aPageWithOnlyABalanceStillFillsThatIn() {
        GiftPageDetails d = GiftPageDetails.parse("<p>Remaining balance: ILS 80</p>");
        assertEquals(80.0d, d.amount, 0.0001d);
        assertNull(d.expiryMonth);
        assertNull(d.pan);
        assertEquals(1, d.count());
    }

    @Test
    public void aLoginPageYieldsNothing() {
        GiftPageDetails d = GiftPageDetails.parse(
                "<form><input name='password'><button>Sign in</button></form>");
        assertTrue(d.isEmpty());
        assertEquals(0, d.count());
    }

    @Test
    public void nothingAtAllIsNotACrash() {
        assertTrue(GiftPageDetails.parse(null).isEmpty());
        assertTrue(GiftPageDetails.parse("").isEmpty());
    }

    // --- ambiguity always loses ---

    /**
     * The case this rule exists for: a page listing what you could buy, alongside what you
     * hold. Picking one of those would be a guess.
     */
    @Test
    public void severalAmountsMeanNoAmount() {
        assertNull(GiftPageDetails.parse(
                "<p>יתרה</p><li>50 ₪</li><li>100 ₪</li><li>200 ₪</li>").amount);
    }

    @Test
    public void anAmountWithNoBalanceWordingIsNotABalance() {
        // A price on a marketing page is not this card's balance.
        assertNull(GiftPageDetails.parse("<p>Gift cards from 50 ₪</p>").amount);
    }

    @Test
    public void twoDifferentExpiriesMeanNoExpiry() {
        assertNull(GiftPageDetails.parse(
                "בתוקף עד 04/2027 ... תוקף 09/2028").expiryMonth);
    }

    @Test
    public void twoCardNumbersMeanNoCardNumber() {
        assertNull(GiftPageDetails.parse(
                "4580123456789012 and 4580999988887777").pan);
    }

    /** The same number written twice is still one card. */
    @Test
    public void theSameNumberRepeatedIsStillOneCard() {
        assertEquals("4580123456789012", GiftPageDetails.parse(
                "<span>4580 1234 5678 9012</span><meta content='4580123456789012'>").pan);
    }

    // --- shapes that must not be mistaken for data ---

    @Test
    public void aDateOfIssueIsNotAnExpiry() {
        // No expiry wording anywhere near it, so it is not read as one.
        assertNull(GiftPageDetails.parse("<p>Issued 03/2024</p>").expiryMonth);
    }

    @Test
    public void aThirteenthMonthIsNotAnExpiry() {
        // "בתוקף עד 25/12" is a day and a month the other way round, not month 25.
        assertNull(GiftPageDetails.parse("בתוקף עד 25/12").expiryMonth);
    }

    @Test
    public void aTwoDigitYearIsThisCentury() {
        assertEquals("2027-04", GiftPageDetails.parse("בתוקף עד 04/27").expiryMonth);
    }

    @Test
    public void aMaskedNumberIsNotACardNumber() {
        // Masked digits do not survive the digits-only length check.
        assertNull(GiftPageDetails.parse("**** **** **** 9012").pan);
    }

    @Test
    public void aRunOfOneDigitIsAPlaceholderNotACard() {
        assertNull(GiftPageDetails.parse("0000000000000000").pan);
    }

    @Test
    public void aPhoneNumberIsTooShortToBeACard() {
        assertNull(GiftPageDetails.parse("call 03-1234567").pan);
    }

    @Test
    public void aBareThreeDigitsIsNotACvv() {
        // Only digits the page actually labels as a security code are taken.
        assertNull(GiftPageDetails.parse("<p>123 shops accept this card</p>").cvv);
    }

    @Test
    public void hebrewSecurityCodeWordingIsUnderstood() {
        assertEquals("456", GiftPageDetails.parse("קוד אבטחה: 456").cvv);
    }

    @Test
    public void englishExpiryWordingIsUnderstood() {
        assertEquals("2028-11", GiftPageDetails.parse("Valid until 11/2028").expiryMonth);
        assertEquals("2029-01", GiftPageDetails.parse("Expires 01/29").expiryMonth);
    }

    @Test
    public void aHugeAmountIsOutOfRangeForAGiftCard() {
        assertNull(GiftPageDetails.parse("יתרה 999,999,999 ₪").amount);
    }
}
