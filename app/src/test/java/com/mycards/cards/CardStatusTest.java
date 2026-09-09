package com.mycards.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CardStatusTest {

    /** As Formats.daysUntil reports a card with no expiry recorded. */
    private static final long NO_EXPIRY = Long.MAX_VALUE;

    @Test
    public void cardWithMoneyAndTimeIsActive() {
        assertEquals(CardStatus.ACTIVE, CardStatus.of(120d, 400L, 0L));
    }

    @Test
    public void cardWithNoExpiryIsActive() {
        assertEquals(CardStatus.ACTIVE, CardStatus.of(120d, NO_EXPIRY, 0L));
    }

    @Test
    public void anEmptiedCardIsEmpty() {
        assertEquals(CardStatus.EMPTY, CardStatus.of(0d, 400L, 0L));
    }

    /**
     * Entries made before the balance cap existed can leave a card in the red. It is still empty, not some fourth state.
     */
    @Test
    public void anOverdrawnCardIsEmpty() {
        assertEquals(CardStatus.EMPTY, CardStatus.of(-30d, 400L, 0L));
    }

    /**
     * The reason a tolerance exists: a card spent exactly to zero rarely arrives as 0.0,
     * because the balance is a sum of doubles.
     */
    @Test
    public void floatingPointDustStillCountsAsEmpty() {
        assertEquals(CardStatus.EMPTY, CardStatus.of(0.0000001d, 400L, 0L));
        assertEquals(CardStatus.EMPTY, CardStatus.of(-0.0000001d, 400L, 0L));
    }

    /** The whole argument against a small-balance threshold, pinned down. */
    @Test
    public void smallButRealBalanceStaysActive() {
        assertEquals(CardStatus.ACTIVE, CardStatus.of(4d, 400L, 0L));
        assertEquals(CardStatus.ACTIVE, CardStatus.of(0.5d, 400L, 0L));
        assertEquals(CardStatus.ACTIVE, CardStatus.of(0.01d, 400L, 0L));
    }

    @Test
    public void pastExpiryIsExpired() {
        assertEquals(CardStatus.EXPIRED, CardStatus.of(120d, -1L, 0L));
    }

    /**
     * The last day of the expiry month reports 0 days left, not -1. A card good until the
     * end of today must not be filed away this morning.
     */
    @Test
    public void lastDayOfTheExpiryMonthIsStillActive() {
        assertEquals(CardStatus.ACTIVE, CardStatus.of(120d, 0L, 0L));
    }

    @Test
    public void archivedByHandBeatsEverythingElse() {
        assertEquals(CardStatus.ARCHIVED, CardStatus.of(120d, 400L, 1_700_000_000_000L));
        assertEquals(CardStatus.ARCHIVED, CardStatus.of(0d, -400L, 1_700_000_000_000L));
    }

    /**
     * A card can be empty and lapsed at once. "Empty" is the more useful of the two: the
     * money was spent rather than lost, and saying "expired" would invent a regret.
     */
    @Test
    public void emptyIsReportedAheadOfExpired() {
        assertEquals(CardStatus.EMPTY, CardStatus.of(0d, -90L, 0L));
    }

    @Test
    public void onlyActiveIsNotRetired() {
        assertFalse(CardStatus.ACTIVE.isRetired());
        assertTrue(CardStatus.ARCHIVED.isRetired());
        assertTrue(CardStatus.EMPTY.isRetired());
        assertTrue(CardStatus.EXPIRED.isRetired());
    }
}
