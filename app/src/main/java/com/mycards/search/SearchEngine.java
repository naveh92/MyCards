package com.mycards.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
     */
    public static List<String> queryVariants(String rawQuery) {
        Set<String> variants = new LinkedHashSet<>();

        String direct = SearchNormalizer.normalize(rawQuery);
        if (!direct.isEmpty()) {
            variants.add(direct);
        }

        String asHebrew = SearchNormalizer.normalize(HebrewKeyboardMapper.enToHe(rawQuery));
        if (!asHebrew.isEmpty()) {
            variants.add(asHebrew);
        }

        String asEnglish = SearchNormalizer.normalize(HebrewKeyboardMapper.heToEn(rawQuery));
        if (!asEnglish.isEmpty()) {
            variants.add(asEnglish);
        }

        return new ArrayList<>(variants);
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

        List<String> variants = queryVariants(rawQuery);
        List<CardMatch> results = new ArrayList<>();

        // No usable query: show everything, so the launcher screen doubles as the wallet.
        if (variants.isEmpty()) {
            for (CardTypeIndex index : indexes) {
                results.add(new CardMatch(index, MatchScore.NONE, false, false,
                        Collections.<Store>emptyList()));
            }
            return results;
        }

        for (CardTypeIndex index : indexes) {
            int nameScore = MatchScore.NONE;
            int properNameScore = MatchScore.NONE;
            for (String variant : variants) {
                nameScore = Math.max(nameScore, index.scoreName(variant));
                properNameScore = Math.max(properNameScore, index.scoreProperName(variant));
            }

            List<ScoredStore> hits = new ArrayList<>();
            for (Store store : index.getStores()) {
                int storeScore = MatchScore.NONE;
                for (String variant : variants) {
                    storeScore = Math.max(storeScore, store.score(variant));
                }
                if (storeScore > MatchScore.NONE) {
                    hits.add(new ScoredStore(store, storeScore));
                }
            }

            if (nameScore == MatchScore.NONE && hits.isEmpty()) {
                continue;
            }

            Collections.sort(hits, new Comparator<ScoredStore>() {
                @Override
                public int compare(ScoredStore a, ScoredStore b) {
                    if (a.score != b.score) {
                        return Integer.compare(b.score, a.score);
                    }
                    return a.store.getName().compareToIgnoreCase(b.store.getName());
                }
            });

            int bestStoreScore = hits.isEmpty() ? MatchScore.NONE : hits.get(0).score;
            boolean byName = nameScore > MatchScore.NONE;

            // A card-name hit dominates a merchant hit — typing "buyme" means "my BuyMe
            // card", not "every card covering a shop with 'buyme' in its name".
            int total = byName
                    ? nameScore + MatchScore.CARD_NAME_BONUS
                    : bestStoreScore;

            List<Store> topStores = new ArrayList<>();
            for (int i = 0; i < hits.size() && i < maxStoresPerCard; i++) {
                topStores.add(hits.get(i).store);
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
        List<String> variants = queryVariants(rawQuery);
        if (variants.isEmpty()) {
            return index.getStores().size();
        }
        int count = 0;
        for (Store store : index.getStores()) {
            for (String variant : variants) {
                if (store.score(variant) > MatchScore.NONE) {
                    count++;
                    break;
                }
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
     * @return matching merchants; the list unchanged when the query has nothing to match on
     */
    public List<Store> matchingStores(String rawQuery, List<Store> stores) {
        if (stores == null || stores.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> variants = queryVariants(rawQuery);
        if (variants.isEmpty()) {
            return new ArrayList<>(stores);
        }

        List<NamedHit> hits = new ArrayList<>();
        for (Store store : stores) {
            int best = MatchScore.NONE;
            for (String variant : variants) {
                best = Math.max(best, store.score(variant));
            }
            if (best == MatchScore.NONE) {
                continue;
            }
            int nameScore = scoreName(store.getName(), variants);
            hits.add(new NamedHit(store, nameScore > MatchScore.NONE ? nameScore : best,
                    nameScore > MatchScore.NONE));
        }

        // A hit on the shop's own name comes before a hit on one of its search terms, the
        // same distinction CardTypeIndex draws between a card's name and its aliases.
        // Searching "cafe" otherwise puts three restaurants tagged with the word above the
        // one actually called Cafe Mayer — every row correct, and the list still wrong.
        //
        // Stable, so equal ranks keep the caller's ordering rather than being reshuffled.
        Collections.sort(hits, new Comparator<NamedHit>() {
            @Override
            public int compare(NamedHit a, NamedHit b) {
                if (a.byName != b.byName) {
                    return a.byName ? -1 : 1;
                }
                return Integer.compare(b.rank, a.rank);
            }
        });

        List<Store> out = new ArrayList<>(hits.size());
        for (NamedHit hit : hits) {
            out.add(hit.store);
        }
        return out;
    }

    /**
     * Scores a merchant's name on its own, ignoring everything it is also findable by.
     *
     * <p>{@link Store#score} cannot answer this: it scans the name and the aliases together
     * and reports the best of them, which is the right answer for "does this match?" and the
     * wrong one for "is the name why?".
     */
    private static int scoreName(String name, List<String> variants) {
        String hay = SearchNormalizer.normalize(name);
        int best = MatchScore.NONE;
        for (String variant : variants) {
            if (!SearchNormalizer.containsNormalized(hay, variant)) {
                continue;
            }
            int score;
            if (hay.equals(variant)) {
                score = MatchScore.EXACT;
            } else if (hay.startsWith(variant)) {
                score = MatchScore.PREFIX;
            } else {
                score = MatchScore.SUBSTRING;
            }
            best = Math.max(best, score);
        }
        return best;
    }

    /** A matching merchant, and whether its own name is the reason. */
    private static final class NamedHit {
        final Store store;
        final int rank;
        final boolean byName;

        NamedHit(Store store, int rank, boolean byName) {
            this.store = store;
            this.rank = rank;
            this.byName = byName;
        }
    }
    private static final class ScoredStore {
        final Store store;
        final int score;

        ScoredStore(Store store, int score) {
            this.store = store;
            this.score = score;
        }
    }
}
