package com.mycards.ui.stores;

/**
 * One merchant as the list draws it: its name, why it is on screen, and how it can be used.
 *
 * <p>"Why" is settled here rather than in the adapter because it depends on the query, and a
 * view holder rebinding during a scroll has no business re-running the matching rules.
 */
final class StoreRow {

    final String name;

    /**
     * The stretch of {@link #name} the query matched, or -1 when the name is not the reason
     * this row is here.
     */
    final int highlightStart;
    final int highlightEnd;

    /**
     * The alias that put this row on screen, when the name alone does not explain it.
     *
     * <p>Typing "מסעדות" against a Chefzone card returns 84 restaurants, not one of which
     * contains the word. Without naming the alias the list looks arbitrary, which is the
     * quickest way to lose someone's trust in it.
     */
    final String matchedAlias;

    final boolean online;

    StoreRow(String name, int highlightStart, int highlightEnd, String matchedAlias,
             boolean online) {
        this.name = name;
        this.highlightStart = highlightStart;
        this.highlightEnd = highlightEnd;
        this.matchedAlias = matchedAlias;
        this.online = online;
    }

    boolean hasHighlight() {
        return highlightStart >= 0 && highlightEnd > highlightStart;
    }
}
