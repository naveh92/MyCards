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

    /**
     * Normalized display names only — the English and Hebrew names of the card itself,
     * e.g. "buymeall", "לאבגיפטקארד".
     *
     * <p>Kept apart from the aliases because the two mean different things. A hit here says
     * the user named the card; a hit in the aliases might only mean they named something the
     * card is associated with. See {@link #scoreProperName}.
     */
    private final Haystacks nameHaystacks;

    /** Normalized aliases: other spellings, marketing strings, issuer names. */
    private final Haystacks aliasHaystacks;

    /** When this merchant list was last refreshed, epoch millis; 0 when never fetched. */
    private final long storesUpdatedAt;

    /** Where the list came from, surfaced in the UI so stale/curated data is visible. */
    private final String sourceLabel;

    /** True when the merchant list is known to be incomplete. See {@code CardTypeDef}. */
    private final boolean partialList;

    /** Treats every supplied string as one of the card's names. */
    public CardTypeIndex(String cardTypeId,
                         String displayName,
                         List<String> otherNames,
                         List<Store> stores,
                         long storesUpdatedAt,
                         String sourceLabel) {
        this(cardTypeId, displayName, otherNames, Collections.<String>emptyList(),
                stores, storesUpdatedAt, sourceLabel);
    }

    /**
     * @param otherNames   the card's names in other languages — still names
     * @param extraAliases everything else it can be found by
     */
    public CardTypeIndex(String cardTypeId,
                         String displayName,
                         List<String> otherNames,
                         List<String> extraAliases,
                         List<Store> stores,
                         long storesUpdatedAt,
                         String sourceLabel) {
        this(cardTypeId, displayName, otherNames, extraAliases, stores, storesUpdatedAt,
                sourceLabel, false);
    }

    /** @param partialList true when the merchant list is knowingly incomplete */
    public CardTypeIndex(String cardTypeId,
                         String displayName,
                         List<String> otherNames,
                         List<String> extraAliases,
                         List<Store> stores,
                         long storesUpdatedAt,
                         String sourceLabel,
                         boolean partialList) {
        this.partialList = partialList;
        this.cardTypeId = cardTypeId;
        this.displayName = displayName == null ? "" : displayName;
        this.stores = stores == null ? Collections.<Store>emptyList() : stores;
        this.storesUpdatedAt = storesUpdatedAt;
        this.sourceLabel = sourceLabel;

        List<String> names = new ArrayList<>();
        addNormalized(names, this.displayName);
        if (otherNames != null) {
            for (String name : otherNames) {
                addNormalized(names, name);
            }
        }
        this.nameHaystacks = new Haystacks(names);

        List<String> aliases = new ArrayList<>();
        if (extraAliases != null) {
            for (String alias : extraAliases) {
                String n = SearchNormalizer.normalize(alias);
                // An alias that merely restates the name adds nothing and would blur the
                // distinction the two arrays exist to draw.
                if (!n.isEmpty() && !names.contains(n) && !aliases.contains(n)) {
                    aliases.add(n);
                }
            }
        }
        this.aliasHaystacks = new Haystacks(aliases);
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

    public boolean isPartialList() {
        return partialList;
    }

    /** Scores the card's own names and aliases together; this is what ranking uses. */
    int scoreName(Query query) {
        return Math.max(scoreProperName(query), scoreIn(aliasHaystacks, query));
    }

    /**
     * Scores against the card's names only, ignoring its aliases.
     *
     * <p>This is the difference between "the user named this card" and "the user typed
     * something this card is merely associated with". Typing "buyme" prefixes the name
     * "BuyMe All" and means <em>show me my BuyMe card</em>. Typing "castro" hits the LOVE
     * card only through an alias carried because Castro Model issues it — the user is far
     * more likely to be standing in the shop. The row's wording turns on exactly this.
     */
    int scoreProperName(Query query) {
        return scoreIn(nameHaystacks, query);
    }

    /**
     * Best tier across a set of haystacks, exact matching first and skeletons only if that
     * found nothing — the same two-pass shape {@link Store#match} uses, for the same reason:
     * a card found literally must not be reported as found by spelling skeleton.
     */
    private static int scoreIn(Haystacks haystacks, Query query) {
        int best = MatchScore.NONE;
        for (String hay : haystacks.exact) {
            best = Math.max(best, Store.tier(hay, query.exact(), false));
        }
        if (best > MatchScore.NONE || !query.hasFuzzy()) {
            return best;
        }
        for (String hay : haystacks.folded) {
            best = Math.max(best, Store.tier(hay, query.fuzzy(), true));
        }
        return best;
    }

    /** A set of normalized strings and their {@link HebrewFold} skeletons, folded once. */
    private static final class Haystacks {

        final String[] exact;
        final String[] folded;

        Haystacks(List<String> normalized) {
            this.exact = normalized.toArray(new String[0]);
            this.folded = new String[this.exact.length];
            for (int i = 0; i < this.exact.length; i++) {
                this.folded[i] = HebrewFold.of(this.exact[i]);
            }
        }
    }
}
