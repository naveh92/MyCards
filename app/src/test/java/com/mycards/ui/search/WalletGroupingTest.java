package com.mycards.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mycards.cards.CardStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WalletGroupingTest {

    private static final WalletGrouping.Labels LABELS = new WalletGrouping.Labels() {
        @Override
        public String archive(int count) {
            return "Archive (" + count + ")";
        }

        @Override
        public String alsoInArchive(int count) {
            return "Also in the archive (" + count + ")";
        }
    };

    private static CardRow card(String title, CardStatus status, double remaining,
                                long daysUntilExpiry, int score) {
        return card(title, status, remaining, daysUntilExpiry, score, 0L);
    }

    private static CardRow card(String title, CardStatus status, double remaining,
                                long daysUntilExpiry, int score, long retiredAt) {
        CardRow row = new CardRow();
        row.cardId = Math.abs(title.hashCode()) + 1L;
        row.title = title;
        row.status = status;
        row.remaining = remaining;
        row.daysUntilExpiry = daysUntilExpiry;
        row.score = score;
        row.retiredAt = retiredAt;
        return row;
    }

    private static CardRow active(String title, double remaining, long days) {
        return card(title, CardStatus.ACTIVE, remaining, days, 0);
    }

    private static List<String> titles(List<CardRow> rows) {
        List<String> out = new ArrayList<>();
        for (CardRow row : rows) {
            out.add(row.isHeader() ? "[" + row.headerLabel + "]" : row.title);
        }
        return out;
    }

    @Test
    public void aWalletWithNothingRetiredGetsNoHeader() {
        List<CardRow> out = WalletGrouping.group(
                Arrays.asList(active("A", 100d, 90L), active("B", 50d, 30L)),
                false, false, LABELS);

        assertEquals(Arrays.asList("B", "A"), titles(out));
        for (CardRow row : out) {
            assertFalse(row.isHeader());
        }
    }

    /**
     * The regression this whole feature turns on. Sorting ascending by days-until-expiry put
     * the most negative first, so the deadest card in the wallet was the top row.
     */
    @Test
    public void anExpiredCardNeverOutranksASpendableOne() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                card("LongExpired", CardStatus.EXPIRED, 200d, -900L, 0),
                active("Live", 50d, 400L)), false, true, LABELS);

        assertEquals(Arrays.asList("Live", "[Archive (1)]", "LongExpired"), titles(out));
    }

    /** A card with nothing left must not be offered as an answer at a counter. */
    @Test
    public void aUsedUpCardIsFiledBehindTheHeader() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                card("Empty", CardStatus.EMPTY, 0d, 400L, 90),
                active("HasMoney", 50d, 400L)), true, true, LABELS);

        assertEquals(Arrays.asList("HasMoney", "[Also in the archive (1)]", "Empty"),
                titles(out));
    }

    /** Even when the retired card is the far better match for what was typed. */
    @Test
    public void relevanceDoesNotPromoteARetiredCardPastALiveOne() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                card("PerfectButDead", CardStatus.EMPTY, 0d, 400L, 1000),
                card("WeakButAlive", CardStatus.ACTIVE, 50d, 400L, 1)), true, true, LABELS);

        assertEquals("WeakButAlive", out.get(0).title);
        assertTrue(out.get(1).isHeader());
    }

    @Test
    public void collapsedGroupShowsTheHeaderAloneButStillCountsWhatIsInside() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                active("Live", 50d, 400L),
                card("Dead1", CardStatus.EMPTY, 0d, 400L, 0),
                card("Dead2", CardStatus.EXPIRED, 20d, -5L, 0)), false, false, LABELS);

        assertEquals(Arrays.asList("Live", "[Archive (2)]"), titles(out));
        assertFalse(out.get(1).headerExpanded);
    }

    /** The archive reads newest-first: the card that just left is the one being looked for. */
    @Test
    public void expandingRevealsThemNewestFirst() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                card("Oldest", CardStatus.EMPTY, 0d, 400L, 0, 1_000L),
                card("Newest", CardStatus.ARCHIVED, 40d, 400L, 0, 3_000L),
                card("Middle", CardStatus.ARCHIVED, 10d, 400L, 0, 2_000L)), false, true, LABELS);

        assertEquals(Arrays.asList("[Archive (3)]", "Newest", "Middle", "Oldest"),
                titles(out));
        assertTrue(out.get(0).headerExpanded);
    }

    /**
     * A card holding money does not jump the queue. What orders the archive is when a card
     * left, not what is left on it — otherwise a card retired yesterday sits below one that
     * has been empty since last year.
     */
    @Test
    public void whatIsLeftOnACardDoesNotReorderTheArchive() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                card("RichButOld", CardStatus.ARCHIVED, 500d, 400L, 0, 1_000L),
                card("BrokeButRecent", CardStatus.EMPTY, 0d, 400L, 0, 9_000L)),
                false, true, LABELS);

        assertEquals(Arrays.asList("[Archive (2)]", "BrokeButRecent", "RichButOld"),
                titles(out));
    }

    /** The order holds whether or not something is typed, so the group keeps its shape. */
    @Test
    public void aQueryDoesNotReshuffleTheArchive() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                card("OldPerfectMatch", CardStatus.EMPTY, 0d, 400L, 999, 1_000L),
                card("RecentWeakMatch", CardStatus.EXPIRED, 0d, -5L, 1, 5_000L)),
                true, true, LABELS);

        assertEquals(Arrays.asList("[Also in the archive (2)]", "RecentWeakMatch",
                "OldPerfectMatch"), titles(out));
    }

    /** Without a stable last resort, equal rows can swap places between redraws. */
    @Test
    public void equallyDeadCardsAreOrderedByNameNotByChance() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                card("zeta", CardStatus.EMPTY, 0d, 400L, 0),
                card("Alpha", CardStatus.EMPTY, 0d, 400L, 0),
                card("mid", CardStatus.EMPTY, 0d, 400L, 0)), false, true, LABELS);

        assertEquals(Arrays.asList("[Archive (3)]", "Alpha", "mid", "zeta"), titles(out));
    }

    /** Browsing and searching call the same group two different things. */
    @Test
    public void theHeaderIsWordedForWhatTheUserIsDoing() {
        List<CardRow> retired = Arrays.asList(card("D", CardStatus.EXPIRED, 0d, -1L, 0));

        assertEquals("[Archive (1)]",
                titles(WalletGrouping.group(retired, false, false, LABELS)).get(0));
        assertEquals("[Also in the archive (1)]",
                titles(WalletGrouping.group(retired, true, false, LABELS)).get(0));
    }

    /** A wallet whose every card is retired is still allowed to show them. */
    @Test
    public void aWalletOfNothingButRetiredCardsIsAllHeaderAndGroup() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                card("D1", CardStatus.EMPTY, 0d, 400L, 0),
                card("D2", CardStatus.EXPIRED, 0d, -3L, 0)), false, true, LABELS);

        assertEquals(Arrays.asList("[Archive (2)]", "D1", "D2"), titles(out));
    }

    @Test
    public void anEmptyWalletProducesAnEmptyList() {
        assertTrue(WalletGrouping.group(new ArrayList<CardRow>(), false, false, LABELS)
                .isEmpty());
    }

    /** The header must never collide with a real card, or DiffUtil confuses the two. */
    @Test
    public void theHeaderCarriesAnIdNoCardCanHave() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                active("Live", 10d, 5L),
                card("Dead", CardStatus.EMPTY, 0d, 5L, 0)), false, true, LABELS);

        assertEquals(CardRow.HEADER_ID, out.get(1).cardId);
        for (CardRow row : out) {
            if (!row.isHeader()) {
                assertTrue("real cards must have positive ids", row.cardId > 0);
            }
        }
    }

    /** With no query, soonest-to-expire leads — the nudge to spend a card before it lapses. */
    @Test
    public void amongLiveCardsTheOneExpiringSoonestComesFirst() {
        List<CardRow> out = WalletGrouping.group(Arrays.asList(
                active("Later", 500d, 400L),
                active("Sooner", 10d, 12L),
                active("NoExpiry", 900d, Long.MAX_VALUE)), false, false, LABELS);

        assertEquals(Arrays.asList("Sooner", "Later", "NoExpiry"), titles(out));
    }
}
