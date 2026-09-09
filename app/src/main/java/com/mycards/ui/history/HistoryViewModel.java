package com.mycards.ui.history;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.db.CardEntity;
import com.mycards.data.db.SpendEntity;
import com.mycards.search.Query;
import com.mycards.search.SearchEngine;
import com.mycards.ui.AppExecutors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every purchase ever logged, across every card, newest first.
 *
 * <p>The whole log is read once and kept, with each entry's searchable text normalized at
 * that point. Filtering then costs a handful of {@code contains} calls per keystroke rather
 * than a database round trip, which is what keeps typing here as responsive as it is on the
 * two search screens.
 *
 * <p>Purchases made on archived cards are in here too. Putting a card away does not unspend
 * what was spent on it, and a history with holes in it would be worse than no history.
 */
public class HistoryViewModel extends AndroidViewModel {

    private final CardsRepository cardsRepo;
    private final CatalogRepository catalogRepo;

    private final MutableLiveData<List<HistoryRow>> rows = new MutableLiveData<>();
    private final MutableLiveData<Summary> summary = new MutableLiveData<>();

    private List<Purchase> purchases = new ArrayList<>();

    /** Remaining balance per card, so an edit can be capped at what the card can bear. */
    private Map<Long, Double> balances = new HashMap<>();

    private String currentQuery = "";

    /**
     * Guards against an out-of-order filter.
     *
     * <p>Typing fires one of these per keystroke onto a shared executor, and nothing promises
     * they finish in order. Without this, a slow pass over "za" can land after "zara" and put
     * the wrong list on screen — where it stays, because no further keystroke is coming.
     */
    private int generation;

    public HistoryViewModel(@NonNull Application application) {
        super(application);
        this.cardsRepo = new CardsRepository(application);
        this.catalogRepo = new CatalogRepository(application);
    }

    public LiveData<List<HistoryRow>> rows() {
        return rows;
    }

    public LiveData<Summary> summary() {
        return summary;
    }

    public String currentQuery() {
        return currentQuery;
    }

    /** True when nothing has ever been logged, as opposed to nothing matching the query. */
    public boolean logIsEmpty() {
        return purchases.isEmpty();
    }

    /** What an edit of this purchase may be raised to without overdrawing its card. */
    public double availableFor(SpendEntity spend) {
        Double remaining = balances.get(spend.cardId);
        // The entry's own amount is added back: it is already part of what the card has
        // spent, so checking a new value against a balance it is inside would refuse any
        // increase at all.
        return Math.max(0d, (remaining == null ? 0d : remaining) + spend.amount);
    }

    /** Reloads the log. Called from onResume, so an edit made elsewhere shows up here. */
    public void reload() {
        int mine = ++generation;
        AppExecutors.io(() -> {
            Catalog catalog = catalogRepo.loadCatalog();
            List<CardEntity> cards = cardsRepo.allCards();
            List<SpendEntity> spends = cardsRepo.spends().getAllNewestFirst();
            Map<Long, Double> remaining = cardsRepo.remainingBalances();

            String lang = Locale.getDefault().getLanguage();
            // Android still reports Hebrew as the legacy code "iw" in some places.
            String tag = ("he".equals(lang) || "iw".equals(lang)) ? "he" : "en";

            Map<Long, CardEntity> byId = new HashMap<>();
            for (CardEntity card : cards) {
                byId.put(card.id, card);
            }

            List<Purchase> built = new ArrayList<>(spends.size());
            for (SpendEntity spend : spends) {
                CardEntity card = byId.get(spend.cardId);
                if (card == null) {
                    // Deleting a card cascades to its purchases, so this should not happen.
                    // If it somehow does, a purchase with no card behind it cannot be shown
                    // or edited, and inventing a name for it would be worse than omitting it.
                    continue;
                }
                built.add(new Purchase(spend, cardName(card, catalog, tag), card.currency));
            }

            AppExecutors.main(() -> {
                if (mine != generation) {
                    return;
                }
                purchases = built;
                balances = remaining;
                publish(filter(currentQuery), mine);
            });
        });
    }

    public void search(String query) {
        currentQuery = query == null ? "" : query;
        int mine = ++generation;
        AppExecutors.io(() -> {
            List<Purchase> matched = filter(currentQuery);
            AppExecutors.main(() -> publish(matched, mine));
        });
    }

    private void publish(List<Purchase> matched, int mine) {
        if (mine != generation) {
            return;
        }
        rows.setValue(HistoryGrouping.group(matched));
        HistoryGrouping.Totals totals = HistoryGrouping.total(matched);
        summary.setValue(new Summary(totals.amount, totals.currency, matched.size()));
    }

    /**
     * The card's name as the rest of the app shows it: the user's own label when they gave
     * one, otherwise the card type.
     */
    private String cardName(CardEntity card, Catalog catalog, String tag) {
        if (card.label != null && !card.label.trim().isEmpty()) {
            return card.label.trim();
        }
        CardTypeDef type = catalog.findById(card.cardTypeId);
        return type != null ? type.displayName(tag) : card.cardTypeId;
    }

    private List<Purchase> filter(String query) {
        List<Query> variants = SearchEngine.queryVariants(query);
        if (variants.isEmpty()) {
            return purchases;
        }
        List<Purchase> out = new ArrayList<>();
        for (Purchase purchase : purchases) {
            if (purchase.matches(variants)) {
                out.add(purchase);
            }
        }
        return out;
    }

    /** What the line above the list reports, for whatever is currently being shown. */
    public static final class Summary {
        public final double total;
        /** Null when the shown purchases do not share one currency. */
        public final String currency;
        public final int count;

        Summary(double total, String currency, int count) {
            this.total = total;
            this.currency = currency;
            this.count = count;
        }
    }
}
