package com.mycards.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public class RetirementNoticeTest {

    private static Set<Long> ids(long... values) {
        Set<Long> out = new LinkedHashSet<>();
        for (long v : values) {
            out.add(v);
        }
        return out;
    }

    /**
     * The case this exists for: a card ran out or lapsed since the wallet was last opened,
     * and vanishing without a word is what makes an app look like it lost something.
     */
    @Test
    public void aCardThatRetiredSinceLastTimeIsAnnounced() {
        assertEquals(ids(2L), RetirementNotice.newlyRetired(ids(1L, 2L), ids(1L)));
    }

    /**
     * Nothing is announced on the very first run. Everything already retired retired before
     * anyone was watching, and calling a card that lapsed last year "news" would misdate it.
     */
    @Test
    public void thefirstRunSeedsSilently() {
        assertTrue(RetirementNotice.newlyRetired(ids(1L, 2L, 3L), null).isEmpty());
    }

    @Test
    public void nothingIsSaidTwice() {
        Set<Long> retired = ids(1L, 2L);
        Set<Long> told = RetirementNotice.remember(retired);
        assertTrue(RetirementNotice.newlyRetired(retired, told).isEmpty());
    }

    @Test
    public void aWalletWithNothingRetiredAnnouncesNothing() {
        assertTrue(RetirementNotice.newlyRetired(Collections.<Long>emptySet(), ids(1L))
                .isEmpty());
        assertTrue(RetirementNotice.newlyRetired(null, ids(1L)).isEmpty());
    }

    @Test
    public void severalCardsCanRetireAtOnce() {
        assertEquals(ids(2L, 3L), RetirementNotice.newlyRetired(ids(1L, 2L, 3L), ids(1L)));
    }

    /**
     * A revived card drops out of what is remembered, so if it ever retires again that is a
     * new event and gets said again. This is why the whole set is stored rather than a flag
     * per card that something would have to remember to clear.
     */
    @Test
    public void aCardThatComesBackCanBeAnnouncedAgainLater() {
        Set<Long> told = RetirementNotice.remember(ids(1L, 2L));

        // The purchase that emptied card 2 is deleted, so it is spendable again.
        Set<Long> afterRevival = ids(1L);
        assertTrue(RetirementNotice.newlyRetired(afterRevival, told).isEmpty());
        Set<Long> toldNow = RetirementNotice.remember(afterRevival);
        assertEquals(ids(1L), toldNow);

        // It runs out a second time; that is news again.
        assertEquals(ids(2L), RetirementNotice.newlyRetired(ids(1L, 2L), toldNow));
    }

    @Test
    public void rememberCopiesRatherThanAliasing() {
        Set<Long> retired = new HashSet<>(Arrays.asList(1L, 2L));
        Set<Long> remembered = RetirementNotice.remember(retired);
        retired.add(3L);

        assertEquals(2, remembered.size());
    }

    @Test
    public void rememberingNothingIsAnEmptySetNotNull() {
        assertTrue(RetirementNotice.remember(null).isEmpty());
    }
}
