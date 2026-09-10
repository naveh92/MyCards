package com.mycards.cards;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RetiredAtTest {

    private static final long NO_EXPIRY = Long.MAX_VALUE;
    private static final long ARCHIVED_ON = 1_780_000_000_000L;
    private static final long EXPIRED_ON = 1_760_000_000_000L;
    private static final long LAST_SPENT = 1_770_000_000_000L;
    private static final long CREATED = 1_700_000_000_000L;

    @Test
    public void anArchivedCardUsesTheMomentItWasArchived() {
        assertEquals(ARCHIVED_ON, RetiredAt.of(CardStatus.ARCHIVED,
                ARCHIVED_ON, EXPIRED_ON, LAST_SPENT, CREATED));
    }

    @Test
    public void anExpiredCardUsesTheEndOfItsExpiryMonth() {
        assertEquals(EXPIRED_ON, RetiredAt.of(CardStatus.EXPIRED,
                0L, EXPIRED_ON, LAST_SPENT, CREATED));
    }

    @Test
    public void anEmptyCardUsesThePurchaseThatFinishedIt() {
        assertEquals(LAST_SPENT, RetiredAt.of(CardStatus.EMPTY,
                0L, NO_EXPIRY, LAST_SPENT, CREATED));
    }

    /** A card added with a balance of zero has no purchase to point at. */
    @Test
    public void anEmptyCardNeverSpentOnFallsBackToWhenItWasAdded() {
        assertEquals(CREATED, RetiredAt.of(CardStatus.EMPTY,
                0L, NO_EXPIRY, 0L, CREATED));
    }

    /**
     * MAX_VALUE means "no expiry", which an expired card cannot have — but sorting on it
     * would pin that row to the top of the archive for ever.
     */
    @Test
    public void anExpiredCardWithNoExpiryDateDoesNotSortToInfinity() {
        assertEquals(CREATED, RetiredAt.of(CardStatus.EXPIRED,
                0L, NO_EXPIRY, LAST_SPENT, CREATED));
    }

    @Test
    public void aCardStillInTheWalletHasNoRetirementDate() {
        assertEquals(0L, RetiredAt.of(CardStatus.ACTIVE,
                0L, NO_EXPIRY, LAST_SPENT, CREATED));
    }

    /**
     * Archiving wins over the other two in {@link CardStatus}, so the date follows the state
     * rather than being picked from whichever timestamp happens to be largest.
     */
    @Test
    public void theDateFollowsTheStateNotTheBiggestTimestamp() {
        // Archived most recently but also long expired: the archive date is the answer.
        assertEquals(ARCHIVED_ON, RetiredAt.of(CardStatus.ARCHIVED,
                ARCHIVED_ON, EXPIRED_ON, LAST_SPENT, CREATED));
        // Empty and also expired: CardStatus reports EMPTY, so the last purchase is used.
        assertEquals(LAST_SPENT, RetiredAt.of(CardStatus.EMPTY,
                0L, EXPIRED_ON, LAST_SPENT, CREATED));
    }
}
