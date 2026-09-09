package com.mycards.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GiftLinkTest {

    private static final String LINK = "https://buyme.co.il/voucher?token=abc123";

    @Test
    public void theSameLinkFingerprintsTheSameWayEveryTime() {
        assertEquals(GiftLink.fingerprint(LINK), GiftLink.fingerprint(LINK));
    }

    /** The case that prompted this: the same link pasted twice, once with stray whitespace. */
    @Test
    public void surroundingWhitespaceIsNotADifferentCard() {
        assertEquals(GiftLink.fingerprint(LINK), GiftLink.fingerprint("  " + LINK + "\n"));
    }

    @Test
    public void schemeAndHostCaseAreNotADifferentCard() {
        assertEquals(GiftLink.fingerprint(LINK),
                GiftLink.fingerprint("HTTPS://BuyMe.co.il/voucher?token=abc123"));
    }

    /**
     * Issuers redirect http to https, so the same link copied before and after the redirect
     * has to land on the same card.
     */
    @Test
    public void httpAndHttpsAreTheSameCard() {
        assertEquals(GiftLink.fingerprint(LINK),
                GiftLink.fingerprint("http://buyme.co.il/voucher?token=abc123"));
    }

    @Test
    public void aDefaultPortIsTheSameAddress() {
        assertEquals(GiftLink.fingerprint("https://buyme.co.il/v"),
                GiftLink.fingerprint("https://buyme.co.il:443/v"));
    }

    @Test
    public void aLoneTrailingSlashIsTheSamePage() {
        assertEquals(GiftLink.fingerprint("https://buyme.co.il"),
                GiftLink.fingerprint("https://buyme.co.il/"));
    }

    // --- the far more important direction: never merge two real cards ---

    /**
     * The token is the card. Two vouchers from the same issuer differ only here, so this is
     * the one comparison that must never be loosened.
     */
    @Test
    public void differentTokensAreDifferentCards() {
        assertNotEquals(GiftLink.fingerprint(LINK),
                GiftLink.fingerprint("https://buyme.co.il/voucher?token=xyz789"));
    }

    /**
     * Some issuers carry the token after the hash, where a "strip the fragment" tidy-up
     * would erase the only thing telling two cards apart.
     */
    @Test
    public void differentFragmentsAreDifferentCards() {
        assertNotEquals(GiftLink.fingerprint("https://issuer.example/gift#abc123"),
                GiftLink.fingerprint("https://issuer.example/gift#xyz789"));
    }

    @Test
    public void differentPathsAreDifferentCards() {
        assertNotEquals(GiftLink.fingerprint("https://buyme.co.il/a"),
                GiftLink.fingerprint("https://buyme.co.il/b"));
    }

    @Test
    public void aDeepTrailingSlashIsNotStripped() {
        assertNotEquals(GiftLink.fingerprint("https://buyme.co.il/a"),
                GiftLink.fingerprint("https://buyme.co.il/a/"));
    }

    @Test
    public void differentHostsAreDifferentCards() {
        assertNotEquals(GiftLink.fingerprint("https://buyme.co.il/v?t=1"),
                GiftLink.fingerprint("https://max.co.il/v?t=1"));
    }

    /** Query order is not something we can reason about, so it is left alone. */
    @Test
    public void reorderedQueryParametersAreLeftAlone() {
        assertNotEquals(GiftLink.fingerprint("https://x.example/g?a=1&b=2"),
                GiftLink.fingerprint("https://x.example/g?b=2&a=1"));
    }

    // --- nothing to compare ---

    /**
     * Most cards have no link at all. If those all hashed alike, every one of them would
     * report every other as a duplicate.
     */
    @Test
    public void aCardWithNoLinkHasNoFingerprint() {
        assertNull(GiftLink.fingerprint(null));
        assertNull(GiftLink.fingerprint(""));
        assertNull(GiftLink.fingerprint("   "));
    }

    @Test
    public void somethingWithNoHostHasNoFingerprint() {
        assertNull(GiftLink.fingerprint("https://"));
    }

    /**
     * A link mangled by a copy-paste still has to be comparable with itself, so parsing
     * never throws — it just does less.
     */
    @Test
    public void anUnparseableLinkStillFingerprints() {
        String messy = "not a url at all, really";
        assertEquals(GiftLink.fingerprint(messy), GiftLink.fingerprint(messy));
        assertNotEquals(GiftLink.fingerprint(messy), GiftLink.fingerprint("something else"));
    }

    @Test
    public void aFingerprintIsAHexSha256() {
        assertEquals(64, GiftLink.fingerprint(LINK).length());
        assertEquals(GiftLink.fingerprint(LINK).toLowerCase(), GiftLink.fingerprint(LINK));
    }
}
