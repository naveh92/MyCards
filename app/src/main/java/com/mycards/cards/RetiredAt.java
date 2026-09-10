package com.mycards.cards;

/**
 * When a card left the wallet, so the archive can be ordered newest-first.
 *
 * <p>Only one of the three ways out records a date. Archiving writes {@code archivedAt};
 * expiring and running out write nothing at all, because both are worked out from data that
 * was already there. So the moment has to be reconstructed, and each state has a different
 * honest answer to "when did this happen":
 *
 * <ul>
 *   <li><b>Archived</b> — exactly when the user did it.</li>
 *   <li><b>Expired</b> — the last instant of its expiry month, which is the moment it stopped
 *       being spendable.</li>
 *   <li><b>Empty</b> — the purchase that finished it, which is the most recent one against
 *       it.</li>
 * </ul>
 *
 * <p>These are not the same kind of timestamp, and mixing them is the point: what the archive
 * is sorted by is "how recently did this card stop being useful to me", and all three answer
 * that question in the same units.
 */
public final class RetiredAt {

    private RetiredAt() {
    }

    /**
     * @param status         the card's state
     * @param archivedAt     epoch millis the user archived it, or 0
     * @param expiryEndAt    last instant of the expiry month, as
     *                       {@code Formats.expiryEndMillis} reports it, so
     *                       {@link Long#MAX_VALUE} means no expiry
     * @param lastSpentAt    epoch millis of the newest purchase, or 0 when never spent on
     * @param createdAt      when the card was added, the fallback of last resort
     * @return epoch millis to sort by; 0 for a card that has not left the wallet
     */
    public static long of(CardStatus status, long archivedAt, long expiryEndAt,
                          long lastSpentAt, long createdAt) {
        switch (status) {
            case ARCHIVED:
                return archivedAt;
            case EXPIRED:
                // MAX_VALUE means the card has no expiry, which cannot happen for a card
                // that expired — but sorting on it would pin that row to the top forever.
                return expiryEndAt == Long.MAX_VALUE ? createdAt : expiryEndAt;
            case EMPTY:
                // A card can be empty without a single purchase, if it was added with a
                // balance of zero. Then the only date that exists is the day it was added.
                return lastSpentAt > 0L ? lastSpentAt : createdAt;
            default:
                return 0L;
        }
    }
}
