package com.mycards.ui.history;

import com.mycards.data.db.SpendEntity;

/**
 * One line of the spending history: either a month's heading or a purchase under it.
 *
 * <p>Nothing here is pre-formatted. Dates and money are turned into text by the adapter, from
 * the context it is bound in, because the view model outlives a configuration change — switch
 * the app to Hebrew in Settings and a list built with English month names would survive the
 * activity that was told about it.
 */
public class HistoryRow {

    public static final int TYPE_MONTH = 0;
    public static final int TYPE_PURCHASE = 1;

    public final int type;

    /**
     * Stable identity for DiffUtil.
     *
     * <p>Purchases use their row id. Months use the negated first-of-the-month, which cannot
     * collide with one: row ids are small positive integers and this is a negative number of
     * milliseconds since 1970.
     */
    public final long id;

    // --- month heading ---

    /** Midnight on the first of the month, for the adapter to format in the reader's locale. */
    public long monthStart;

    public double monthTotal;

    /** Null when the month mixes currencies, in which case a total would be a lie. */
    public String monthCurrency;

    // --- purchase ---

    public SpendEntity spend;

    /** The card's own label, or its type name — whichever the wallet shows for it. */
    public String cardName;

    /**
     * The catalog id of the card that paid.
     *
     * <p>Carried only so the row can be marked in that card's colour, which is what lets
     * you follow one card down a mixed list without reading a name on every line.
     */
    public String cardTypeId;

    /** The colour that card was given by hand, if any, which outranks its type's. */
    public Integer faceColor;

    public String currency;

    private HistoryRow(int type, long id) {
        this.type = type;
        this.id = id;
    }

    public static HistoryRow month(long monthStart, double total, String currency) {
        HistoryRow row = new HistoryRow(TYPE_MONTH, -monthStart);
        row.monthStart = monthStart;
        row.monthTotal = total;
        row.monthCurrency = currency;
        return row;
    }

    public static HistoryRow purchase(SpendEntity spend, String cardName, String cardTypeId,
                                      Integer faceColor, String currency) {
        HistoryRow row = new HistoryRow(TYPE_PURCHASE, spend.id);
        row.spend = spend;
        row.cardName = cardName;
        row.cardTypeId = cardTypeId;
        row.faceColor = faceColor;
        row.currency = currency;
        return row;
    }
}
