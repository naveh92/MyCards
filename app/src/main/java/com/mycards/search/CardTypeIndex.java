package com.mycards.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One gift-card type ("BuyMe All", "All-inZone") plus the merchant list it covers,
 * pre-normalized and ready to search.
 *
 * <p>Deliberately keyed by card <em>type</em>, not by an owned card: two physical
 * All-inZone cards share one merchant list, and there is no reason to index it twice.
 * The repository layer fans a type match back out to the cards actually held.
 */
public final class CardTypeIndex {

    private final String cardTypeId;
    private final String displayName;
    private final List<Store> stores;

    /** Normalized card name + aliases, e.g. "buymeall", "ביימיאול", "buyme". */
    private final String[] nameHaystacks;

    /** When this merchant list was last refreshed, epoch millis; 0 when never fetched. */
    private final long storesUpdatedAt;

    /** Where the list came from, surfaced in the UI so stale/curated data is visible. */
    private final String sourceLabel;

    public CardTypeIndex(String cardTypeId,
                         String displayName,
                         List<String> nameAliases,
                         List<Store> stores,
                         long storesUpdatedAt,
                         String sourceLabel) {
        this.cardTypeId = cardTypeId;
        this.displayName = displayName == null ? "" : displayName;
        this.stores = stores == null ? Collections.<Store>emptyList() : stores;
        this.storesUpdatedAt = storesUpdatedAt;
        this.sourceLabel = sourceLabel;

        List<String> forms = new ArrayList<>();
        addNormalized(forms, this.displayName);
        if (nameAliases != null) {
            for (String alias : nameAliases) {
                addNormalized(forms, alias);
            }
        }
        this.nameHaystacks = forms.toArray(new String[0]);
    }

    private static void addNormalized(List<String> into, String raw) {
        String n = SearchNormalizer.normalize(raw);
        if (!n.isEmpty() && !into.contains(n)) {
            into.add(n);
        }
    }

    public String getCardTypeId() {
        return cardTypeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<Store> getStores() {
        return stores;
    }

    public long getStoresUpdatedAt() {
        return storesUpdatedAt;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    /** Scores the card's own name/aliases against a normalized query variant. */
    int scoreName(String normalizedQuery) {
        int best = MatchScore.NONE;
        for (String hay : nameHaystacks) {
            if (!SearchNormalizer.containsNormalized(hay, normalizedQuery)) {
                continue;
            }
            int s;
            if (hay.equals(normalizedQuery)) {
                s = MatchScore.EXACT;
            } else if (hay.startsWith(normalizedQuery)) {
                s = MatchScore.PREFIX;
            } else {
                s = MatchScore.SUBSTRING;
            }
            if (s > best) {
                best = s;
            }
        }
        return best;
    }
}
