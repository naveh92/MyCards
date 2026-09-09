package com.mycards.ui.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Splits the wallet's matches into what can be spent and what cannot, and puts the second
 * half behind one collapsed header.
 *
 * <p>Separate from the view model, and free of any Android type, so the ordering rules can be
 * tested rather than eyeballed on a running phone. What is on top of this list is what
 * someone reaches for at a checkout counter, and it went wrong twice before it went right.
 */
final class WalletGrouping {

    private WalletGrouping() {
    }

    /** Supplies the header's wording, which needs resources the algorithm has no business holding. */
    interface Labels {

        /** Browsing the wallet: the back drawer. */
        String archive(int count);

        /** Mid-search: "not among the cards that work, but your query hit these too". */
        String alsoInArchive(int count);
    }

    /**
     * @param matches   every card whose type matched the query, in any order
     * @param searching whether a query is active, which changes what the header means
     * @param expanded  whether the archive group is currently open
     */
    static List<CardRow> group(List<CardRow> matches, boolean searching, boolean expanded,
                               Labels labels) {
        List<CardRow> active = new ArrayList<>();
        List<CardRow> retired = new ArrayList<>();
        for (CardRow row : matches) {
            (row.status.isRetired() ? retired : active).add(row);
        }

        Collections.sort(active, ACTIVE_ORDER);
        Collections.sort(retired, RETIRED_ORDER);

        List<CardRow> out = new ArrayList<>(active);
        if (!retired.isEmpty()) {
            out.add(CardRow.header(searching
                    ? labels.alsoInArchive(retired.size())
                    : labels.archive(retired.size()), expanded));
            if (expanded) {
                out.addAll(retired);
            }
        }
        return out;
    }

    /**
     * Relevance, then urgency, then size.
     *
     * <p>The middle term is the one that matters: with no query typed it becomes the whole
     * ordering, and it puts the card that lapses soonest at the top, where it is a standing
     * nudge to spend the thing before it turns into nothing.
     *
     * <p>That only became safe once expired cards left this list. Sorting ascending by days
     * remaining put the most negative first — so the most thoroughly dead card in the wallet
     * used to be the first thing on the screen.
     */
    static final Comparator<CardRow> ACTIVE_ORDER = new Comparator<CardRow>() {
        @Override
        public int compare(CardRow a, CardRow b) {
            if (a.score != b.score) {
                return Integer.compare(b.score, a.score);
            }
            if (a.daysUntilExpiry != b.daysUntilExpiry) {
                return Long.compare(a.daysUntilExpiry, b.daysUntilExpiry);
            }
            return Double.compare(b.remaining, a.remaining);
        }
    };

    /**
     * The archive reads newest-first, like every other pile of things that accumulate.
     *
     * <p>What someone is looking for in here is nearly always the card that just left — the
     * one they half-remember spending the last of last week — and that is the one an
     * arrival-ordered list puts at the top. Ordering by what is left on the card instead,
     * which this used to do, buried a card that retired yesterday under one that has been
     * sitting empty since last year.
     *
     * <p>Relevance is only a tiebreak, and deliberately so: the order stays the same whether
     * or not something is typed, and a group that reshuffles under a query is a group nobody
     * can learn the shape of. The name is the last resort, so that rows which are equal on
     * everything else do not swap places between redraws.
     */
    static final Comparator<CardRow> RETIRED_ORDER = new Comparator<CardRow>() {
        @Override
        public int compare(CardRow a, CardRow b) {
            if (a.retiredAt != b.retiredAt) {
                return Long.compare(b.retiredAt, a.retiredAt);
            }
            if (a.score != b.score) {
                return Integer.compare(b.score, a.score);
            }
            return String.valueOf(a.title).compareToIgnoreCase(String.valueOf(b.title));
        }
    };
}
