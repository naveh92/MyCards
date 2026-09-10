package com.mycards.ui.search;

import com.mycards.cards.CardStatus;

import java.util.Collections;
import java.util.List;

/**
 * One row on the search screen: an owned card, plus why it matched the query.
 *
 * <p>Or the header of the archive group, which is the same list's other kind of row. Two
 * classes would be tidier in a language with sum types; in this one it would mean a second
 * adapter, a second diff callback and a wrapper type over both, to distinguish one row from
 * every other. {@link #isHeader()} is the discriminator, and the fields it governs are
 * grouped together below so the split stays obvious.
 */
public class CardRow {

    /**
     * The id every header row carries.
     *
     * <p>Negative because row ids come from an autoincrement primary key and are always
     * positive, so this cannot collide with a real card — which matters, because DiffUtil
     * identifies rows by this field alone.
     */
    public static final long HEADER_ID = -1L;

    public long cardId;
    public String cardTypeId;

    /**
     * The colour this card was given by hand, ARGB, or null to take the card type's own.
     *
     * <p>Carried on the row rather than looked up at bind time so the list stays a value
     * object: a row knows everything needed to draw it, and the adapter never reaches back
     * into the database from the main thread.
     */
    public Integer faceColor;

    /** The user's label if they gave one, otherwise the card type's name. */
    public String title;

    /** Card type name, shown as a subtitle when the user's own label is displayed above. */
    public String subtitle;

    public double remaining;
    public double initialAmount;
    public String currency;

    public String expiryDate;
    public long daysUntilExpiry;

    /**
     * The merchants that matched, as the row will show them — name, and the other spelling
     * that matched when the name alone does not explain the row.
     */
    public List<StoreLabel> matchedStores = Collections.emptyList();
    public int totalMatchingStores;
    public boolean matchedByCardName;
    /** Narrower than the above: the query hit the card's name, not just an alias. */
    public boolean matchedByCardProperName;
    public boolean hasOnlineMatch;

    public int storeCount;
    /** True when this card's merchant list is knowingly incomplete. */
    public boolean partialStoreList;
    public long storesUpdatedAt;
    public String storeSource;

    public boolean hasUnreconciledMismatch;

    /** Relevance from the search engine; 0 when no query is active. */
    public int score;

    /** Empty, expired, archived — or active, which is every card in the main list. */
    public CardStatus status = CardStatus.ACTIVE;

    /**
     * When this card left the wallet, epoch millis; 0 while it is still in use.
     *
     * <p>Reconstructed per state by {@code RetiredAt}, and the archive's sort key.
     */
    public long retiredAt;

    // --- header rows ---

    /** Non-null only on the archive group's header; see {@link #header}. */
    public String headerLabel;

    /** Whether the group this header opens is currently showing its cards. */
    public boolean headerExpanded;

    public boolean isHeader() {
        return headerLabel != null;
    }

    /** The one row in the list that is not a card. */
    public static CardRow header(String label, boolean expanded) {
        CardRow row = new CardRow();
        row.cardId = HEADER_ID;
        row.headerLabel = label;
        row.headerExpanded = expanded;
        return row;
    }

    public boolean isExpired() {
        return daysUntilExpiry != Long.MAX_VALUE && daysUntilExpiry < 0;
    }

    public boolean isExpiringSoon() {
        return daysUntilExpiry != Long.MAX_VALUE && daysUntilExpiry >= 0 && daysUntilExpiry <= 30;
    }

    public boolean hasStoreList() {
        return storeCount > 0;
    }
}
