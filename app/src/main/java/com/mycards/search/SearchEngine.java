package com.mycards.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers the question this whole app exists for: <em>"I am standing in this shop — which
 * of my cards works here?"</em>
 *
 * <p>Matching is deliberately infix ("za" finds "Zara", "meall" finds "Buy Me All"), which
 * rules out a token-based inverted index: no token map can answer arbitrary mid-word
 * fragments. Instead every name and alias is normalized once at index-build time and the
 * query is scanned against those precomputed strings. For a realistic wallet — a handful of
 * card types, ~1,300 merchants each, ~30 aliases apiece — that is a few tens of thousands of
 * short {@code String.contains} calls, comfortably inside a keystroke's budget.
 *
 * <p>Every match carries the spelling that produced it, not just the merchant. Two rows for
 * Carolina Lemke — one listed under that name, one buried in a Castro-group entry that names
 * קרולינה among its brands — are both right, and both look like noise until the row can say
 * which words it read.
 */
public final class SearchEngine {

    /** How many matching merchants to attach to a result row before truncating. */
    public static final int DEFAULT_MAX_STORES_PER_CARD = 3;

    /**
     * Expands a raw query into every spelling worth trying: as typed, plus both
     * wrong-keyboard-layout readings.
     *
     * <p>The layout transliteration runs on the <em>raw</em> input rather than the
     * normalized form, so Hebrew final letters (ך, ף) still map back to the keys that
     * actually produce them.
     *
     * <p>Deduplicated by normalized text, which is what silently drops the two layout
     * readings of a query that has nothing to transliterate.
     */
    public static List<Query> queryVariants(String rawQuery) {
        Map<String, Query> variants = new LinkedHashMap<>();
        add(variants, SearchNormalizer.normalize(rawQuery));
        add(variants, SearchNormalizer.normalize(HebrewKeyboardMapper.enToHe(rawQuery)));
        add(variants, SearchNormalizer.normalize(HebrewKeyboardMapper.heToEn(rawQuery)));
        return new ArrayList<>(variants.values());
    }

    private static void add(Map<String, Query> into, String normalized) {
        if (!normalized.isEmpty() && !into.containsKey(normalized)) {
            into.put(normalized, Query.ofNormalized(normalized));
        }
    }

    /**
     * Finds every card type matching the query.
     *
     * @param rawQuery exactly what the user typed, untrimmed and unnormalized
     * @param indexes  the card types to search
     * @return matches ordered by relevance, then by card name; an empty query returns all
     *         card types unfiltered so opening the app shows the whole wallet
     */
    public List<CardMatch> search(String rawQuery, List<CardTypeIndex> indexes) {
        return search(rawQuery, indexes, DEFAULT_MAX_STORES_PER_CARD);
    }

    public List<CardMatch> search(String rawQuery, List<CardTypeIndex> indexes, int maxStoresPerCard) {
        if (indexes == null || indexes.isEmpty()) {
            return Collections.emptyList();
        }

        List<Query> variants = queryVariants(rawQuery);
        List<CardMatch> results = new ArrayList<>();

        // No usable query: show everything, so the launcher screen doubles as the wallet.
        if (variants.isEmpty()) {
            for (CardTypeIndex index : indexes) {
                results.add(new CardMatch(index, MatchScore.NONE, false, false,
                        Collections.<StoreMatch>emptyList()));
            }
            return results;
        }

        for (CardTypeIndex index : indexes) {
            int nameScore = MatchScore.NONE;
            int properNameScore = MatchScore.NONE;
            for (Query variant : variants) {
                nameScore = Math.max(nameScore, index.scoreName(variant));
                properNameScore = Math.max(properNameScore, index.scoreProperName(variant));
            }

            List<StoreMatch> hits = matchStores(variants, index.getStores());

            if (nameScore == MatchScore.NONE && hits.isEmpty()) {
                continue;
            }

            Collections.sort(hits, new Comparator<StoreMatch>() {
                @Override
                public int compare(StoreMatch a, StoreMatch b) {
                    int byRank = StoreMatch.compare(a, b);
                    return byRank != 0
                            ? byRank
                            : a.getName().compareToIgnoreCase(b.getName());
                }
            });

            int bestStoreScore = hits.isEmpty() ? MatchScore.NONE : hits.get(0).getScore();
            boolean byName = nameScore > MatchScore.NONE;

            // A card-name hit dominates a merchant hit — typing "buyme" means "my BuyMe
            // card", not "every card covering a shop with 'buyme' in its name".
            int total = byName
                    ? nameScore + MatchScore.CARD_NAME_BONUS
                    : bestStoreScore;

            List<StoreMatch> topStores = new ArrayList<>();
            for (int i = 0; i < hits.size() && i < maxStoresPerCard; i++) {
                topStores.add(hits.get(i));
            }

            results.add(new CardMatch(index, total, byName,
                    properNameScore > MatchScore.NONE, topStores));
        }

        Collections.sort(results, new Comparator<CardMatch>() {
            @Override
            public int compare(CardMatch a, CardMatch b) {
                if (a.getScore() != b.getScore()) {
                    return Integer.compare(b.getScore(), a.getScore());
                }
                return a.getCardType().getDisplayName()
                        .compareToIgnoreCase(b.getCardType().getDisplayName());
            }
        });

        return results;
    }

    /** Counts every merchant matching the query for one card type, ignoring the display cap. */
    public int countMatchingStores(String rawQuery, CardTypeIndex index) {
        List<Query> variants = queryVariants(rawQuery);
        if (variants.isEmpty()) {
            return index.getStores().size();
        }
        int count = 0;
        for (Store store : index.getStores()) {
            if (store.matchesAny(variants)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Every merchant on one card that matches the query, best match first.
     *
     * <p>The inverse of {@link #search}: that one asks "which of my cards works in this
     * shop?", this one asks "which shops does this card work in?". Both run the same
     * scoring, so a wrong-keyboard-layout query finds the same merchant either way.
     *
     * <p>Ties are left in the order they arrived, so a caller that hands over an
     * alphabetically sorted list gets alphabetical order back within each relevance band.
     *
     * @return matching merchants; every one of them when the query has nothing to match on
     */
    public List<StoreMatch> matchingStores(String rawQuery, List<Store> stores) {
        if (stores == null || stores.isEmpty()) {
            return Collections.emptyList();
        }

        List<Query> variants = queryVariants(rawQuery);
        if (variants.isEmpty()) {
            List<StoreMatch> all = new ArrayList<>(stores.size());
            for (Store store : stores) {
                all.add(new StoreMatch(store, MatchScore.NONE, Store.NAME_FORM, false));
            }
            return all;
        }

        List<StoreMatch> hits = matchStores(variants, stores);
        // Stable, so equal ranks keep the caller's ordering rather than being reshuffled.
        Collections.sort(hits, new Comparator<StoreMatch>() {
            @Override
            public int compare(StoreMatch a, StoreMatch b) {
                return StoreMatch.compare(a, b);
            }
        });
        return hits;
    }

    /**
     * Runs every query variant against every merchant, keeping the best answer per merchant.
     *
     * <p>Best across variants and not merely first: a query that transliterates to something
     * real in two layouts should be reported by whichever reading explains the row better.
     */
    private static List<StoreMatch> matchStores(List<Query> variants, List<Store> stores) {
        List<StoreMatch> hits = new ArrayList<>();
        for (Store store : stores) {
            StoreMatch best = null;
            for (Query variant : variants) {
                StoreMatch candidate = store.match(variant);
                if (candidate != null
                        && (best == null || StoreMatch.compare(candidate, best) < 0)) {
                    best = candidate;
                }
            }
            if (best != null) {
                hits.add(best);
            }
        }
        return hits;
    }
}
