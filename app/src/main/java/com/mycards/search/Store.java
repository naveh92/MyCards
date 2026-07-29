package com.mycards.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A merchant that accepts some gift card, together with every spelling it might be
 * searched by.
 *
 * <p>Normalized forms are computed once at construction. Search runs on every keystroke,
 * so normalizing ~40,000 alias strings per keypress would be far too slow; this pushes
 * that cost to index-build time instead.
 */
public final class Store {

    private final String name;
    private final boolean onlineRedeem;

    /** Normalized name + aliases, deduplicated. This is what matching actually scans. */
    private final String[] haystacks;

    public Store(String name, List<String> aliases, boolean onlineRedeem) {
        this.name = name == null ? "" : name;
        this.onlineRedeem = onlineRedeem;

        List<String> forms = new ArrayList<>();
        addNormalized(forms, this.name);
        if (aliases != null) {
            for (String alias : aliases) {
                addNormalized(forms, alias);
            }
        }
        this.haystacks = forms.toArray(new String[0]);
    }

    private static void addNormalized(List<String> into, String raw) {
        String n = SearchNormalizer.normalize(raw);
        if (!n.isEmpty() && !into.contains(n)) {
            into.add(n);
        }
    }

    public String getName() {
        return name;
    }

    public boolean isOnlineRedeem() {
        return onlineRedeem;
    }

    public List<String> getHaystacks() {
        return Collections.unmodifiableList(java.util.Arrays.asList(haystacks));
    }

    /**
     * Scores this store against an already-normalized query variant.
     *
     * @return {@link MatchScore#NONE} when nothing matches, otherwise the strongest match found
     */
    int score(String normalizedQuery) {
        int best = MatchScore.NONE;
        for (String hay : haystacks) {
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
                if (best == MatchScore.EXACT) {
                    break;
                }
            }
        }
        return best;
    }

    @Override
    public String toString() {
        return name;
    }
}
