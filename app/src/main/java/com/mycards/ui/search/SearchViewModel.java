package com.mycards.ui.search;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.db.CardEntity;
import com.mycards.search.CardMatch;
import com.mycards.search.CardTypeIndex;
import com.mycards.search.SearchEngine;
import com.mycards.sync.SyncScheduler;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.Formats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Holds the loaded wallet and answers queries against it.
 *
 * <p>Indexes are built once when the screen opens and then reused, so typing only costs the
 * matching pass. Rebuilding them per keystroke would re-parse hundreds of kilobytes of
 * cached JSON and make the search visibly laggy.
 */
public class SearchViewModel extends AndroidViewModel {

    private final CatalogRepository catalogRepo;
    private final CardsRepository cardsRepo;
    private final SearchEngine engine = new SearchEngine();

    private final MutableLiveData<List<CardRow>> rows = new MutableLiveData<>();
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);

    private Catalog catalog;
    private List<CardTypeIndex> indexes = new ArrayList<>();
    private List<CardEntity> cards = new ArrayList<>();
    private Map<Long, Double> balances = new HashMap<>();

    private String currentQuery = "";

    public SearchViewModel(@NonNull Application application) {
        super(application);
        this.catalogRepo = new CatalogRepository(application);
        this.cardsRepo = new CardsRepository(application);
    }

    public LiveData<List<CardRow>> rows() {
        return rows;
    }

    public LiveData<Boolean> loading() {
        return loading;
    }

    /**
     * True when any card the user holds has a knowingly incomplete merchant list.
     *
     * <p>Changes what "no results" is allowed to claim. With complete lists, nothing matching
     * means no card works there. With a partial list in the wallet it only means the app has
     * not been told — and telling someone at a checkout counter that their card is refused, when it might
     * not be, is the one wrong answer this app must not give.
     */
    public boolean anyPartialStoreList() {
        for (CardTypeIndex index : indexes) {
            if (index.isPartialList()) {
                return true;
            }
        }
        return false;
    }

    public String currentQuery() {
        return currentQuery;
    }

    /** Reloads cards and merchant indexes. Call from onResume so edits elsewhere show up. */
    public void reload() {
        loading.setValue(true);
        AppExecutors.io(() -> {
            catalog = catalogRepo.loadCatalog();
            cards = cardsRepo.allCards();

            // Only index types the user actually holds.
            Set<String> typeIds = new LinkedHashSet<>();
            for (CardEntity c : cards) {
                typeIds.add(c.cardTypeId);
            }

            List<String> ids = new ArrayList<>(typeIds);
            catalogRepo.seedCacheIfEmpty(catalog, ids);

            String lang = Locale.getDefault().getLanguage();
            // Android still reports Hebrew as the legacy code "iw" in some places.
            String tag = ("he".equals(lang) || "iw".equals(lang)) ? "he" : "en";

            indexes = catalogRepo.buildIndexes(catalog, ids, tag);
            balances = cardsRepo.remainingBalances();

            // Only a few card types ship a snapshot inside the APK, so adding a card of any
            // other type leaves it with no merchants at all. Waiting for the weekly worker
            // would mean the card reads "no store list available" for days; fetch now.
            for (CardTypeIndex index : indexes) {
                if (index.getStores().isEmpty()) {
                    SyncScheduler.syncNow(getApplication());
                    break;
                }
            }

            List<CardRow> result = buildRows(currentQuery);
            AppExecutors.main(() -> {
                rows.setValue(result);
                loading.setValue(false);
            });
        });
    }

    public void search(String query) {
        currentQuery = query == null ? "" : query;
        // Matching is in-memory and cheap, but stays off the main thread so a large wallet
        // can never stutter the keyboard.
        AppExecutors.io(() -> {
            List<CardRow> result = buildRows(currentQuery);
            AppExecutors.main(() -> rows.setValue(result));
        });
    }

    private List<CardRow> buildRows(String query) {
        if (cards.isEmpty()) {
            return Collections.emptyList();
        }

        List<CardMatch> matches = engine.search(query, indexes);
        Map<String, CardMatch> byType = new HashMap<>();
        for (CardMatch m : matches) {
            byType.put(m.getCardTypeId(), m);
        }

        List<CardRow> out = new ArrayList<>();
        for (CardEntity card : cards) {
            CardMatch match = byType.get(card.cardTypeId);
            if (match == null) {
                // This card's type did not match the query.
                continue;
            }

            CardTypeIndex index = match.getCardType();
            CardRow row = new CardRow();
            row.cardId = card.id;
            row.cardTypeId = card.cardTypeId;

            boolean hasLabel = card.label != null && !card.label.trim().isEmpty();
            row.title = hasLabel ? card.label.trim() : index.getDisplayName();
            row.subtitle = hasLabel ? index.getDisplayName() : null;

            Double remaining = balances.get(card.id);
            row.remaining = remaining == null ? card.initialAmount : remaining;
            row.initialAmount = card.initialAmount;
            row.currency = card.currency;

            row.expiryDate = card.expiryDate;
            row.daysUntilExpiry = Formats.daysUntil(card.expiryDate);

            row.matchedStores = match.getMatchedStores();
            row.matchedByCardName = match.isMatchedByCardName();
            row.matchedByCardProperName = match.isMatchedByCardProperName();
            row.hasOnlineMatch = match.hasOnlineMatch();
            row.totalMatchingStores = row.matchedStores.isEmpty()
                    ? 0
                    : engine.countMatchingStores(query, index);

            row.storeCount = index.getStores().size();
            row.partialStoreList = index.isPartialList();
            row.storesUpdatedAt = index.getStoresUpdatedAt();
            row.storeSource = index.getSourceLabel();
            row.hasUnreconciledMismatch = card.hasUnreconciledMismatch;
            row.score = match.getScore();

            out.add(row);
        }

        Collections.sort(out, new Comparator<CardRow>() {
            @Override
            public int compare(CardRow a, CardRow b) {
                // Relevance first so an explicit query is honoured...
                if (a.score != b.score) {
                    return Integer.compare(b.score, a.score);
                }
                // ...then soonest-to-expire, which with no query becomes the whole ordering
                // and nudges dying cards to be spent before they lapse.
                if (a.daysUntilExpiry != b.daysUntilExpiry) {
                    return Long.compare(a.daysUntilExpiry, b.daysUntilExpiry);
                }
                return Double.compare(b.remaining, a.remaining);
            }
        });

        return out;
    }
}
