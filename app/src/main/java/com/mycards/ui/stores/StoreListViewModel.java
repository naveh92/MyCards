package com.mycards.ui.stores;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.db.AppDatabase;
import com.mycards.data.db.CardEntity;
import com.mycards.data.db.StoreCacheEntity;
import com.mycards.data.source.StoreListJson;
import com.mycards.search.MatchSpan;
import com.mycards.search.Query;
import com.mycards.search.SearchEngine;
import com.mycards.search.Store;
import com.mycards.search.StoreMatch;
import com.mycards.ui.AppExecutors;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Holds one card's merchant list and answers queries against it.
 *
 * <p>The mirror image of {@code SearchViewModel}: that one asks which card works in a given
 * shop, this one asks which shops a given card works in. Both run the same matching, so a
 * name typed on the wrong keyboard layout is found either way.
 */
public class StoreListViewModel extends AndroidViewModel {

    private static final String TAG = "StoreListViewModel";

    /** What the screen's chrome needs, settled once when the card is loaded. */
    public static final class CardInfo {

        /** The card's own name — the label the user gave it, or its type. */
        public final String title;

        public final int totalStores;
        public final long updatedAt;
        public final String sourceType;
        public final boolean partialList;

        /** Whether an "online only" filter would have anything to offer. */
        public final boolean hasOnlineStores;

        CardInfo(String title, int totalStores, long updatedAt, String sourceType,
                 boolean partialList, boolean hasOnlineStores) {
            this.title = title;
            this.totalStores = totalStores;
            this.updatedAt = updatedAt;
            this.sourceType = sourceType;
            this.partialList = partialList;
            this.hasOnlineStores = hasOnlineStores;
        }
    }

    private final CardsRepository cardsRepo;
    private final CatalogRepository catalogRepo;
    private final SearchEngine engine = new SearchEngine();

    private final MutableLiveData<CardInfo> info = new MutableLiveData<>();
    private final MutableLiveData<List<StoreRow>> rows = new MutableLiveData<>();

    /** Every merchant on the card, already in the reader's alphabetical order. */
    private List<Store> allStores = Collections.emptyList();

    private String query = "";
    private boolean onlineOnly;

    /**
     * Guards against a slow filter landing after a faster later one.
     *
     * <p>Each keystroke starts its own pass on the IO executor, and nothing orders them.
     * Without this, pausing mid-word can leave the list showing results for a prefix of
     * what is in the field.
     */
    private int generation;

    /**
     * Whether the merchant list has already been read out of the cache.
     *
     * <p>A rotation re-runs {@link #load}, and re-parsing half a megabyte of JSON to arrive
     * at the list already in memory is pure waste. Nothing can have changed in between: the
     * cache is only ever rewritten by the sync worker, and the screen is looking at a
     * snapshot of it either way.
     */
    private boolean loaded;

    public StoreListViewModel(@NonNull Application application) {
        super(application);
        this.cardsRepo = new CardsRepository(application);
        this.catalogRepo = new CatalogRepository(application);
    }

    public LiveData<CardInfo> info() {
        return info;
    }

    public LiveData<List<StoreRow>> rows() {
        return rows;
    }

    public String currentQuery() {
        return query;
    }

    public boolean isOnlineOnly() {
        return onlineOnly;
    }

    /** Loads the card's merchant list. Safe to call again; it simply re-reads the cache. */
    public void load(long cardId) {
        if (loaded) {
            // LiveData replays what it already holds to the new observers, so there is
            // nothing left to do.
            return;
        }
        AppExecutors.io(() -> {
            CardEntity card = cardsRepo.cards().getById(cardId);
            if (card == null) {
                AppExecutors.main(() -> {
                    info.setValue(new CardInfo("", 0, 0L, null, false, false));
                    rows.setValue(Collections.<StoreRow>emptyList());
                });
                return;
            }

            String lang = Locale.getDefault().getLanguage();
            // Android still reports Hebrew as the legacy code "iw" in some places.
            String tag = ("he".equals(lang) || "iw".equals(lang)) ? "he" : "en";

            Catalog catalog = catalogRepo.loadCatalog();
            CardTypeDef def = catalog.findById(card.cardTypeId);
            String typeName = def != null ? def.displayName(tag) : card.cardTypeId;
            boolean hasLabel = card.label != null && !card.label.trim().isEmpty();

            StoreCacheEntity cache = AppDatabase.get(getApplication())
                    .storeCacheDao().getByCardType(card.cardTypeId);

            List<Store> stores = new ArrayList<>();

            if (cache != null && cache.storesJson != null) {
                try (InputStream in = new ByteArrayInputStream(
                        cache.storesJson.getBytes(StandardCharsets.UTF_8))) {
                    stores.addAll(StoreListJson.parseCompactList(in));
                } catch (Exception e) {
                    // Treated exactly as the search index treats it: a corrupt cache is an
                    // empty list, not a crash on a screen someone opened in order to read.
                    Log.w(TAG, "corrupt store cache for " + card.cardTypeId, e);
                }
            }

            boolean anyOnline = false;
            for (Store store : stores) {
                if (store.isOnlineRedeem()) {
                    anyOnline = true;
                    break;
                }
            }

            // Sorted once, here, so every later filter can be a stable sort by relevance and
            // inherit this ordering inside each band for free. Collated rather than compared
            // by code point, because Hebrew ordered by UTF-16 value is not alphabetical
            // order to anyone reading it.
            Collator collator = Collator.getInstance(Locale.getDefault());
            collator.setStrength(Collator.PRIMARY);
            Collections.sort(stores, (a, b) -> collator.compare(a.getName(), b.getName()));

            CardInfo cardInfo = new CardInfo(
                    hasLabel ? card.label.trim() : typeName,
                    stores.size(),
                    cache == null ? 0L : cache.fetchedAt,
                    cache == null ? null : cache.sourceType,
                    def != null && def.partialList,
                    anyOnline);

            AppExecutors.main(() -> {
                allStores = stores;
                loaded = true;
                info.setValue(cardInfo);
                refilter();
            });
        });
    }

    public void setQuery(String raw) {
        query = raw == null ? "" : raw;
        refilter();
    }

    public void setOnlineOnly(boolean value) {
        onlineOnly = value;
        refilter();
    }

    private void refilter() {
        final int mine = ++generation;
        final String forQuery = query;
        final boolean forOnlineOnly = onlineOnly;
        final List<Store> source = allStores;

        AppExecutors.io(() -> {
            List<Store> base = source;
            if (forOnlineOnly) {
                base = new ArrayList<>();
                for (Store store : source) {
                    if (store.isOnlineRedeem()) {
                        base.add(store);
                    }
                }
            }

            List<StoreMatch> matched = engine.matchingStores(forQuery, base);
            List<Query> variants = SearchEngine.queryVariants(forQuery);

            List<StoreRow> out = new ArrayList<>(matched.size());
            for (StoreMatch match : matched) {
                out.add(describe(match, variants));
            }

            AppExecutors.main(() -> {
                if (mine == generation) {
                    rows.setValue(out);
                }
            });
        });
    }

    /**
     * Works out what to tell the reader about why this merchant is on screen.
     *
     * <p>The order is the honest one: point at the name when the name is the reason, and
     * name the spelling that matched when it is not. A row that bolds nothing and explains
     * nothing looks like a bug in the filter.
     */
    private StoreRow describe(StoreMatch match, List<Query> variants) {
        Store store = match.getStore();
        String name = store.getName();
        if (variants.isEmpty()) {
            return new StoreRow(name, -1, -1, null, store.isOnlineRedeem());
        }

        if (match.isByName()) {
            MatchSpan span = MatchSpan.find(name, variants);
            return span == null
                    ? new StoreRow(name, -1, -1, null, store.isOnlineRedeem())
                    : new StoreRow(name, span.start, span.end, null, store.isOnlineRedeem());
        }

        // The name does not contain the query, so say which of its other spellings does.
        return new StoreRow(name, -1, -1, match.getMatchedForm(), store.isOnlineRedeem());
    }
}
