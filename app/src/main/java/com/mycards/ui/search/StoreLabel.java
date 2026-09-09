package com.mycards.ui.search;

import com.mycards.search.MatchSpan;
import com.mycards.search.Query;
import com.mycards.search.StoreMatch;

import java.util.List;

/**
 * One matched merchant, already worded and with the query's position in it settled.
 *
 * <p>Settled here rather than in the adapter for the same reason {@code StoreRow} settles it:
 * the wording depends on the query, and a view holder rebinding mid-scroll has no business
 * re-running the matching rules. It also keeps the rules on the JVM side of the fence, where
 * they can be tested.
 */
public final class StoreLabel {

    /**
     * Stands in for the matched spelling while the sentence is being assembled, so that its
     * position can be read off rather than guessed at.
     *
     * <p>U+FFFF is permanently unassigned, so no merchant name and no translation can contain
     * one. Measuring from the end of the shop's name instead would misplace the highlight for
     * any translation that words the two halves in a different order.
     */
    static final String MARKER = "￿";

    /** What the row shows: the shop's name, or its name plus the spelling that matched. */
    public final String text;

    /** The stretch of {@link #text} the query landed on, or -1 for neither. */
    public final int highlightStart;
    public final int highlightEnd;

    public StoreLabel(String text, int highlightStart, int highlightEnd) {
        this.text = text;
        this.highlightStart = highlightStart;
        this.highlightEnd = highlightEnd;
    }

    /**
     * Words one matched merchant.
     *
     * <p>A shop found by its own name is shown by its own name — anything else would be the
     * app talking about itself. A shop found by one of its other spellings is shown as both,
     * because either half alone is a worse answer: the name on its own is the row that left
     * someone searching for Carolina Lemke wondering what a Castro-group entry had to do with
     * it, and the spelling on its own would claim the list holds a shop under a name it does
     * not.
     *
     * @param listedAsFormat a two-argument pattern, name first — {@code R.string.store_listed_as}
     */
    public static StoreLabel of(StoreMatch match, List<Query> variants, String listedAsFormat) {
        String name = match.getName();
        if (match.isByName()) {
            return spanning(name, MatchSpan.find(name, variants), 0);
        }

        String alias = match.getMatchedForm();
        String scaffold = String.format(listedAsFormat, name, MARKER);
        int at = scaffold.indexOf(MARKER);
        if (at < 0) {
            // A translation that dropped the second placeholder. Nothing left to point at,
            // but the shop's name is still the answer.
            return spanning(name, MatchSpan.find(name, variants), 0);
        }
        String text = scaffold.substring(0, at) + alias + scaffold.substring(at + MARKER.length());
        return spanning(text, MatchSpan.find(alias, variants), at);
    }

    private static StoreLabel spanning(String text, MatchSpan span, int offset) {
        return span == null
                ? new StoreLabel(text, -1, -1)
                : new StoreLabel(text, span.start + offset, span.end + offset);
    }

    public boolean hasHighlight() {
        return highlightStart >= 0
                && highlightEnd > highlightStart
                && highlightEnd <= text.length();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof StoreLabel)) {
            return false;
        }
        StoreLabel o = (StoreLabel) other;
        return highlightStart == o.highlightStart
                && highlightEnd == o.highlightEnd
                && text.equals(o.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode() * 31 + highlightStart;
    }

    @Override
    public String toString() {
        return text;
    }
}
