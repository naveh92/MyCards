package com.mycards.cards;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Works out which cards have quietly left the wallet since the user was last looking.
 *
 * <p>Archiving a card announces itself — the user did it, and a snackbar says so. Running out
 * and expiring do not: a card empties inside the dialog that logs the purchase, and a card
 * expires while the app is not even running. Without this the card is simply gone from the
 * wallet next time it is opened, which is the difference between an app that tidied up and an
 * app that lost something.
 *
 * <p>Deliberately not a stored flag on the card. The states it reports are derived, so a card
 * can come back — delete the purchase that emptied it and it is spendable again — and a flag
 * would have to be cleared by every edit that could revive one. Instead the whole set of
 * currently-retired cards is remembered, and what gets announced is the difference. A revived
 * card drops out of the set on its own and is eligible to be announced again if it retires a
 * second time.
 */
public final class RetirementNotice {

    private RetirementNotice() {
    }

    /**
     * The cards to tell the user about.
     *
     * @param retiredNow   ids of every card currently empty or expired — <em>not</em>
     *                     archived, which announces itself at the moment it happens
     * @param alreadyTold  what {@link #remember} returned last time, or null on the very
     *                     first run
     * @return ids never announced before, in the order given; empty on the first run
     */
    public static Set<Long> newlyRetired(Set<Long> retiredNow, Set<Long> alreadyTold) {
        if (retiredNow == null || retiredNow.isEmpty()) {
            return Collections.emptySet();
        }
        if (alreadyTold == null) {
            // First run: everything already retired retired before anyone was watching, and
            // announcing a card that lapsed last year as news would be a lie about when it
            // happened. Seed silently and report only what moves from here.
            return Collections.emptySet();
        }
        Set<Long> fresh = new LinkedHashSet<>();
        for (Long id : retiredNow) {
            if (!alreadyTold.contains(id)) {
                fresh.add(id);
            }
        }
        return fresh;
    }

    /**
     * What to store until next time.
     *
     * <p>The current set exactly, so a card that came back is forgotten and can be announced
     * again if it retires once more.
     */
    public static Set<Long> remember(Set<Long> retiredNow) {
        return retiredNow == null ? new HashSet<Long>() : new HashSet<>(retiredNow);
    }
}
