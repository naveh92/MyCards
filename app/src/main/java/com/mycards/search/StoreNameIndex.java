package com.mycards.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One card's merchants, ready to offer while someone types a shop into the spend log.
 *
 * <p>What is <em>offered</em> is always the merchant's name — that is the text you want left
 * in the field, and "adidas" is the right thing to log whether you reached it by typing
 * "adid" or "אדידס". What is <em>searched</em> is every spelling the merchant is listed
 * under, which is the whole difference between this field finding adidas in both languages
 * and finding it in whichever one the list happens to be written in.
 *
 * <p>It used to search names only, on the reasoning that aliases would just widen the net.
 * They do — into the other half of a bilingual merchant list. A shop filed as "adidas" with
 * "אדידס" among its aliases was unreachable in Hebrew, while a shop filed the other way round
 * was unreachable in English, and which one you got was an accident of whoever wrote the
 * source list.
 *
 * <p>Pure JDK, like the rest of this package, so the rules can be tested without a device.
 */
public final class StoreNameIndex {

    /** Nothing is offered until the query is at least this long. */
    public static final int MIN_QUERY_LENGTH = 2;

    private static final StoreNameIndex EMPTY = new StoreNameIndex(new Store[0]);

    private final Store[] stores;

    private StoreNameIndex(Store[] stores) {
        this.stores = stores;
    }

    public static StoreNameIndex empty() {
        return EMPTY;
    }

    /** Builds an index from names alone, for a list that carries nothing else. */
    public static StoreNameIndex of(List<String> storeNames) {
        if (storeNames == null || storeNames.isEmpty()) {
            return EMPTY;
        }
        List<Store> kept = new ArrayList<>(storeNames.size());
        for (String name : storeNames) {
            if (name != null && !SearchNormalizer.normalize(name).isEmpty()) {
                kept.add(new Store(name.trim(), null, false));
            }
        }
        return ofStores(kept);
    }

    /** Builds an index from full merchant records, aliases included. */
    public static StoreNameIndex ofStores(List<Store> stores) {
        if (stores == null || stores.isEmpty()) {
            return EMPTY;
        }
        List<Store> kept = new ArrayList<>(stores.size());
        for (Store store : stores) {
            if (store != null && !store.getName().trim().isEmpty()) {
                kept.add(store);
            }
        }
        return kept.isEmpty() ? EMPTY : new StoreNameIndex(kept.toArray(new Store[0]));
    }

    public boolean isEmpty() {
        return stores.length == 0;
    }

    public int size() {
        return stores.length;
    }

    /**
     * Shops worth offering for what has been typed so far, best first.
     *
     * <p>A name identical to what is already in the field is left out: there is nothing to
     * offer someone who has finished typing it, and a chip that changes nothing is noise.
     * Matching runs the same query variants as everything else, so a shop typed with the
     * keyboard in the wrong language is still found.
     *
     * @param typed exactly what is in the field, untrimmed
     * @param limit how many to return at most
     * @return the suggestions, or an empty list when there is nothing useful to say
     */
    public List<String> suggest(String typed, int limit) {
        if (typed == null || limit <= 0 || isEmpty()) {
            return Collections.emptyList();
        }
        String raw = typed.trim();
        if (raw.length() < MIN_QUERY_LENGTH) {
            return Collections.emptyList();
        }

        List<Query> variants = SearchEngine.queryVariants(raw);
        if (variants.isEmpty()) {
            return Collections.emptyList();
        }

        List<StoreMatch> hits = new ArrayList<>();
        for (Store store : stores) {
            if (store.getName().equals(raw)) {
                continue;
            }
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

        // Stable, so shops of equal relevance keep the order the list arrived in.
        Collections.sort(hits, new Comparator<StoreMatch>() {
            @Override
            public int compare(StoreMatch a, StoreMatch b) {
                return StoreMatch.compare(a, b);
            }
        });

        // A merchant listed twice under the same name — the Zone lists repeat a shop once
        // per category it sits in — must not spend two of the few slots on offer.
        Set<String> out = new LinkedHashSet<>();
        for (StoreMatch hit : hits) {
            out.add(hit.getName());
            if (out.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(out);
    }
}
