package com.mycards.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One card's merchant names, ready to offer while someone types a shop into the spend log.
 *
 * <p>Names only, and deliberately so. Matching aliases as well would find more, but a
 * suggestion is only worth offering if it is the text you would want left in the field —
 * and by the time a merchant list has been through the local cache its aliases are
 * normalized run-ons rather than anything a person wrote. See {@code StoreListWriter}.
 *
 * <p>Pure JDK, like the rest of this package, so the rules can be tested without a device.
 */
public final class StoreNameIndex {

    /** Nothing is offered until the query is at least this long. */
    public static final int MIN_QUERY_LENGTH = 2;

    private static final StoreNameIndex EMPTY =
            new StoreNameIndex(new String[0], new String[0]);

    private final String[] names;

    /** {@link #names} in canonical form, computed once so typing costs only the scan. */
    private final String[] normalized;

    private StoreNameIndex(String[] names, String[] normalized) {
        this.names = names;
        this.normalized = normalized;
    }

    public static StoreNameIndex empty() {
        return EMPTY;
    }

    public static StoreNameIndex of(List<String> storeNames) {
        if (storeNames == null || storeNames.isEmpty()) {
            return EMPTY;
        }
        List<String> kept = new ArrayList<>(storeNames.size());
        List<String> canonical = new ArrayList<>(storeNames.size());
        for (String name : storeNames) {
            if (name == null) {
                continue;
            }
            String trimmed = name.trim();
            String form = SearchNormalizer.normalize(trimmed);
            if (form.isEmpty()) {
                continue;
            }
            kept.add(trimmed);
            canonical.add(form);
        }
        if (kept.isEmpty()) {
            return EMPTY;
        }
        return new StoreNameIndex(kept.toArray(new String[0]),
                canonical.toArray(new String[0]));
    }

    public boolean isEmpty() {
        return names.length == 0;
    }

    public int size() {
        return names.length;
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

        List<String> variants = SearchEngine.queryVariants(raw);
        if (variants.isEmpty()) {
            return Collections.emptyList();
        }

        List<Candidate> hits = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(raw)) {
                continue;
            }
            int best = MatchScore.NONE;
            for (String variant : variants) {
                if (!SearchNormalizer.containsNormalized(normalized[i], variant)) {
                    continue;
                }
                best = Math.max(best, normalized[i].startsWith(variant)
                        ? MatchScore.PREFIX
                        : MatchScore.SUBSTRING);
            }
            if (best > MatchScore.NONE) {
                hits.add(new Candidate(i, best));
            }
        }

        // Stable, so shops of equal relevance keep the order the list arrived in.
        Collections.sort(hits, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Integer.compare(b.score, a.score);
            }
        });

        List<String> out = new ArrayList<>(Math.min(limit, hits.size()));
        for (int i = 0; i < hits.size() && out.size() < limit; i++) {
            out.add(names[hits.get(i).index]);
        }
        return out;
    }

    private static final class Candidate {
        final int index;
        final int score;

        Candidate(int index, int score) {
            this.index = index;
            this.score = score;
        }
    }
}
