package com.mycards.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class CardLabelTest {

    private static int score(String label, String typed) {
        List<Query> variants = SearchEngine.queryVariants(typed);
        return CardLabel.of(label).score(variants);
    }

    private static void assertMatches(String label, String typed) {
        assertTrue("\"" + typed + "\" should find a card labelled \"" + label + "\"",
                score(label, typed) > MatchScore.NONE);
    }

    private static void assertNoMatch(String label, String typed) {
        assertEquals("\"" + typed + "\" should not find a card labelled \"" + label + "\"",
                MatchScore.NONE, score(label, typed));
    }

    /**
     * The bug this class exists to fix: the card is on screen with its label as the heading,
     * you type the word you are looking at, and the wallet says no card of yours is accepted
     * there.
     */
    @Test
    public void aCardIsFoundByTheNameItsOwnerGaveIt() {
        assertMatches("Anniversary meal", "Anniversary");
        assertMatches("Holiday gift 2026", "holiday");
        assertMatches("Old birthday card", "birthday");
    }

    @Test
    public void anUnlabelledCardMatchesNothing() {
        assertEquals(MatchScore.NONE, score(null, "anything"));
        assertEquals(MatchScore.NONE, score("", "anything"));
        assertEquals(MatchScore.NONE, score("   ", "anything"));
        assertTrue(CardLabel.of(null).isEmpty());
        assertTrue(CardLabel.of("  ").isEmpty());
        assertFalse(CardLabel.of("Holiday gift").isEmpty());
    }

    @Test
    public void anUnrelatedQueryDoesNotMatch() {
        assertNoMatch("Holiday gift 2026", "zara");
        assertNoMatch("Anniversary meal", "castro");
    }

    /**
     * A label hit has to beat any merchant hit, or naming your own card would rank below a
     * shop that happens to share a word with it. {@link MatchScore#EXACT} is the best a
     * merchant can score.
     */
    @Test
    public void namingYourOwnCardOutranksAnyMerchantHit() {
        assertTrue(score("Anniversary meal", "anniversary") > MatchScore.EXACT);
        assertTrue("even a mid-word fragment of a label beats a perfect merchant match",
                score("Holiday gift 2026", "lida") > MatchScore.EXACT);
    }

    /** Whole name, then leading fragment, then buried fragment — as everywhere else. */
    @Test
    public void wholeNameOutranksAPrefixWhichOutranksAFragment() {
        int whole = score("Holiday", "holiday");
        int prefix = score("Holiday gift", "holiday");
        int buried = score("My holiday gift", "holiday");

        assertTrue(whole > prefix);
        assertTrue(prefix > buried);
        assertTrue(buried > MatchScore.NONE);
    }

    /** The normalizer runs on both sides, so case and punctuation cannot get in the way. */
    @Test
    public void caseAndPunctuationAreIgnored() {
        assertMatches("Holiday gift 2026", "HOLIDAY");
        assertMatches("Holiday gift 2026", "holiday gift");
        // "&" and the apostrophe are discarded rather than spelled out, so the label reads
        // as "mumdadsgift" — "mum and dad" would be looking for letters that are not there.
        assertMatches("Mum & Dad's gift", "mum dad");
        assertMatches("Mum & Dad's gift", "dads");
        assertNoMatch("Mum & Dad's gift", "mum and dad");
    }

    /** Spaces are dropped by the normalizer, so a label reads as one run of characters. */
    @Test
    public void aQuerySpanningTheSpaceStillMatches() {
        assertMatches("Holiday gift", "holidaygift");
    }

    @Test
    public void hebrewLabelsAreFound() {
        assertMatches("מתנה לחג", "מתנה");
        assertMatches("מתנה לחג", "לחג");
    }

    /** Hebrew final letters fold, so a fragment taken from mid-word still matches. */
    @Test
    public void hebrewFinalLettersFold() {
        assertMatches("כרטיס מתנה", "מתנ");
    }

    /**
     * The wrong-keyboard-layout trick the rest of the app has, carried to labels for free:
     * "tshsx" is what the keys under א-ד-י-ד-ס produce on a US layout.
     */
    @Test
    public void typingHebrewOnALatinKeyboardStillFindsTheCard() {
        assertMatches("אדידס", "tshsx");
    }

    /**
     * A card labelled with one Hebrew spelling is reachable by the other, through the same
     * skeleton fallback the merchant lists use.
     */
    @Test
    public void hebrewSpellingVariantsReachTheLabel() {
        assertMatches("אירוקה", "ארוקה");
    }

    /**
     * A fuzzy label hit still outranks a merchant, matching how the engine already treats a
     * fuzzy hit on the card type's own name — the bonus is what says "this is the card you
     * asked for", and it applies whichever spelling got there.
     */
    @Test
    public void aFuzzyLabelHitStillCarriesTheCardNameBonus() {
        int fuzzy = score("אירוקה", "ארוקה");
        assertTrue(fuzzy > MatchScore.EXACT);
        assertTrue("but it ranks below a literal hit on the same label",
                score("אירוקה", "אירוקה") > fuzzy);
    }

    /** An empty query has no spellings, so nothing is claimed to match it. */
    @Test
    public void anEmptyQueryMatchesNothing() {
        assertEquals(MatchScore.NONE, score("Holiday gift", ""));
        assertEquals(MatchScore.NONE, score("Holiday gift", "   "));
        assertEquals(MatchScore.NONE, CardLabel.of("Holiday gift").score(null));
    }
}
