package com.mycards.ui.search;

import com.mycards.search.Store;

import java.util.Collections;
import java.util.List;

/** One row on the search screen: an owned card, plus why it matched the query. */
public class CardRow {

    public long cardId;
    public String cardTypeId;

    /** The user's label if they gave one, otherwise the card type's name. */
    public String title;

    /** Card type name, shown as a subtitle when the user's own label is displayed above. */
    public String subtitle;

    public double remaining;
    public double initialAmount;
    public String currency;

    public String expiryDate;
    public long daysUntilExpiry;

    public List<Store> matchedStores = Collections.emptyList();
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
