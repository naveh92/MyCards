package com.mycards.ui.history;

import com.mycards.data.db.SpendEntity;
import com.mycards.search.HebrewFold;
import com.mycards.search.Query;
import com.mycards.search.SearchNormalizer;

import java.util.List;

/** A logged purchase with the card it belongs to, and its searchable text worked out once. */
public final class Purchase {

    public final SpendEntity spend;

    /** The card's own label, or its type name — whichever the wallet shows for it. */
    public final String cardName;

    /** The catalog id of the card that paid, which is what picks the row's colour. */
    public final String cardTypeId;

    /** The colour that card was given by hand, if any, which outranks its type's. */
    public final Integer faceColor;

    public final String currency;

    /**
     * The three things worth searching, normalized once and kept apart.
     *
     * <p>Searching all three is what makes the field feel like it understood the question:
     * "castro" finds the purchase whether Castro was the shop, the description, or the name
     * of the card that paid for it.
     *
     * <p>Kept as separate strings rather than joined into one, because
     * {@link SearchNormalizer#normalize} discards spaces along with the rest of the
     * punctuation — so "Shoes" bought at "Castro" concatenated becomes {@code shoescastro},
     * and a search for "escas" would match a purchase containing no such word. Three fields
     * tested in turn cannot straddle a boundary that no longer exists.
     */
    private final String title;
    private final String store;
    private final String card;

    /** The same three as {@link HebrewFold} skeletons, so a Hebrew spelling variant matches. */
    private final String titleFolded;
    private final String storeFolded;
    private final String cardFolded;

    public Purchase(SpendEntity spend, String cardName, String cardTypeId, String currency) {
        this(spend, cardName, cardTypeId, null, currency);
    }

    public Purchase(SpendEntity spend, String cardName, String cardTypeId, Integer faceColor,
                    String currency) {
        this.spend = spend;
        this.cardName = cardName;
        this.cardTypeId = cardTypeId;
        this.faceColor = faceColor;
        this.currency = currency;
        this.title = SearchNormalizer.normalize(spend.title);
        this.store = SearchNormalizer.normalize(spend.storeName);
        this.card = SearchNormalizer.normalize(cardName);
        this.titleFolded = HebrewFold.of(this.title);
        this.storeFolded = HebrewFold.of(this.store);
        this.cardFolded = HebrewFold.of(this.card);
    }

    /**
     * @param variants normalized query spellings from
     *                 {@code SearchEngine.queryVariants}, which is what carries the
     *                 wrong-keyboard-layout matching over to this screen for free
     */
    public boolean matches(List<Query> variants) {
        for (Query variant : variants) {
            if (variant.hits(title, titleFolded)
                    || variant.hits(store, storeFolded)
                    || variant.hits(card, cardFolded)) {
                return true;
            }
        }
        return false;
    }
}
