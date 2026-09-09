package com.mycards.ui.search;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mycards.R;
import com.mycards.cards.CardStatus;
import com.mycards.cards.RetiredAt;
import com.mycards.cards.RetirementNotice;
import com.mycards.cards.WalletTotal;
import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.db.CardEntity;
import com.mycards.search.CardLabel;
import com.mycards.search.CardMatch;
import com.mycards.search.CardTypeIndex;
import com.mycards.search.MatchScore;
import com.mycards.search.Query;
import com.mycards.search.SearchEngine;
import com.mycards.search.StoreMatch;
import com.mycards.sync.SyncScheduler;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.Formats;

import java.util.ArrayList;
import java.util.Collections;
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
    private final MutableLiveData<RetiredNotice> retiredNotice = new MutableLiveData<>();
    private final MutableLiveData<WalletTotal> total = new MutableLiveData<>();

    /** Shared with SearchActivity, which keeps its own flags in the same file. */
    private static final String PREFS = "mycards_ui";

    /** Ids of the cards already announced as having left the wallet on their own. */
    private static final String KEY_ANNOUNCED_RETIRED = "announced_retired";

    /** Whether the archive was left open last time; see {@link #archiveExpanded}. */
    private static final String KEY_ARCHIVE_EXPANDED = "archive_expanded";

    private Catalog catalog;
    private List<CardTypeIndex> indexes = new ArrayList<>();
    private List<CardEntity> cards = new ArrayList<>();
    private Map<Long, Double> balances = new HashMap<>();

    /** Newest purchase per card, for working out when an emptied card ran out. */
    private Map<Long, Long> lastSpendTimes = new HashMap<>();

    private String currentQuery = "";

    /**
     * Whether the archive group is showing its cards.
     *
     * <p>Remembered between launches. It used to be shut every time on the argument that this
     * is the checkout screen and owes you spendable cards on arrival — which is a fair
     * default and a poor rule: someone who opens the archive on most visits was being made to
     * open it again on every visit, and the app had been told the answer each time. The
     * default for a wallet that has never been asked is still shut.
     */
    private boolean archiveExpanded;

    /**
     * The last matched rows, before grouping.
     *
     * <p>Kept so that opening and closing the archive is only a regroup. It used to rebuild
     * from scratch, and the comment here claimed that was cheap — it was not: rebuilding runs
     * the whole match again, scanning every merchant list in the wallet, on the main thread,
     * to answer a question whose answer had not changed.
     */
    private List<CardRow> matchedRows = new ArrayList<>();

    public SearchViewModel(@NonNull Application application) {
        super(application);
        this.catalogRepo = new CatalogRepository(application);
        this.cardsRepo = new CardsRepository(application);
        this.archiveExpanded = application
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ARCHIVE_EXPANDED, false);
    }

    public LiveData<List<CardRow>> rows() {
        return rows;
    }

    /**
     * What the wallet is worth, independent of whatever is typed in the search box.
     *
     * <p>Published from {@link #reload()} rather than derived from {@link #rows()}, because
     * the rows are the query's answer: totalling them would make the figure fall as a
     * search narrowed, which reads as money disappearing.
     */
    public LiveData<WalletTotal> total() {
        return total;
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

    /** True when there is not a single card on the phone — not merely nothing matching. */
    public boolean walletIsEmpty() {
        return cards.isEmpty();
    }

    public LiveData<RetiredNotice> retiredNotice() {
        return retiredNotice;
    }

    /** Called once the notice has been shown, so a rotation does not show it again. */
    public void noticeShown() {
        retiredNotice.setValue(null);
    }

    /** Opens the archive group, for the notice's "Show" action. */
    public void expandArchive() {
        if (!archiveExpanded) {
            setArchiveExpanded(true);
        }
    }

    /**
     * Works out which cards have gone quiet since the user last looked, and remembers the
     * answer so it is only said once.
     *
     * <p>Runs on the load rather than per keystroke: this is about what changed while nobody
     * was watching, and a search does not change it.
     */
    private RetiredNotice findNewlyRetired(Map<String, CardTypeIndex> indexByType) {
        Set<Long> retiredNow = new LinkedHashSet<>();
        Map<Long, CardEntity> byId = new HashMap<>();
        for (CardEntity card : cards) {
            Double remaining = balances.get(card.id);
            CardStatus status = CardStatus.of(
                    remaining == null ? card.initialAmount : remaining,
                    Formats.daysUntil(card.expiryDate), card.archivedAt);
            // Archiving is deliberate and already says so at the moment it happens; only the
            // two that occur without anyone asking are news.
            if (status == CardStatus.EMPTY || status == CardStatus.EXPIRED) {
                retiredNow.add(card.id);
                byId.put(card.id, card);
            }
        }

        SharedPreferences prefs =
                getApplication().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> stored = prefs.contains(KEY_ANNOUNCED_RETIRED)
                ? prefs.getStringSet(KEY_ANNOUNCED_RETIRED, null)
                : null;

        Set<Long> alreadyTold = null;
        if (stored != null) {
            alreadyTold = new LinkedHashSet<>();
            for (String id : stored) {
                try {
                    alreadyTold.add(Long.parseLong(id));
                } catch (NumberFormatException ignored) {
                    // A value this app did not write. Dropping it is the whole repair needed.
                }
            }
        }

        Set<Long> fresh = RetirementNotice.newlyRetired(retiredNow, alreadyTold);

        Set<String> toStore = new LinkedHashSet<>();
        for (Long id : RetirementNotice.remember(retiredNow)) {
            toStore.add(String.valueOf(id));
        }
        prefs.edit().putStringSet(KEY_ANNOUNCED_RETIRED, toStore).apply();

        if (fresh.isEmpty()) {
            return null;
        }
        if (fresh.size() > 1) {
            return new RetiredNotice(null, null, fresh.size());
        }

        CardEntity only = byId.get(fresh.iterator().next());
        Double remaining = balances.get(only.id);
        CardStatus status = CardStatus.of(
                remaining == null ? only.initialAmount : remaining,
                Formats.daysUntil(only.expiryDate), only.archivedAt);
        return new RetiredNotice(displayName(only, indexByType), status, 1);
    }

    private String displayName(CardEntity card, Map<String, CardTypeIndex> indexByType) {
        if (card.label != null && !card.label.trim().isEmpty()) {
            return card.label.trim();
        }
        CardTypeIndex index = indexByType.get(card.cardTypeId);
        return index != null ? index.getDisplayName() : card.cardTypeId;
    }

    /** One card that quietly left the wallet, or a count when several did at once. */
    public static final class RetiredNotice {
        /** Null when {@link #count} is above one. */
        public final String cardName;
        /** Null when {@link #count} is above one. */
        public final CardStatus status;
        public final int count;

        RetiredNotice(String cardName, CardStatus status, int count) {
            this.cardName = cardName;
            this.status = status;
            this.count = count;
        }
    }

    /** Opens or shuts the archive group and republishes the list. */
    public void toggleArchive() {
        setArchiveExpanded(!archiveExpanded);
    }

    /**
     * Records the choice and redraws, without matching anything again.
     *
     * <p>This is the part that has to be instant: it is the direct response to a tap, so it
     * runs on the main thread and must therefore do almost nothing. Regrouping a list already
     * in memory qualifies; rebuilding it, which is what this used to do, does not.
     */
    private void setArchiveExpanded(boolean expanded) {
        archiveExpanded = expanded;
        getApplication().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ARCHIVE_EXPANDED, expanded).apply();
        rows.setValue(group(matchedRows));
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
            lastSpendTimes = cardsRepo.lastSpendTimes();

            // Only a few card types ship a snapshot inside the APK, so adding a card of any
            // other type leaves it with no merchants at all. Waiting for the weekly worker
            // would mean the card reads "no store list available" for days; fetch now.
            for (CardTypeIndex index : indexes) {
                if (index.getStores().isEmpty()) {
                    SyncScheduler.syncNow(getApplication());
                    break;
                }
            }

            Map<String, CardTypeIndex> indexByType = new HashMap<>();
            for (CardTypeIndex index : indexes) {
                indexByType.put(index.getCardTypeId(), index);
            }
            RetiredNotice notice = findNewlyRetired(indexByType);

            // Over every card the wallet holds, not over the rows about to be published:
            // see total().
            WalletTotal walletTotal = new WalletTotal();
            for (CardEntity card : cards) {
                Double left = balances.get(card.id);
                double amount = left == null ? card.initialAmount : left;
                walletTotal.add(amount, CardStatus.of(
                        amount, Formats.daysUntil(card.expiryDate), card.archivedAt));
            }

            List<CardRow> result = buildRows(currentQuery);
            AppExecutors.main(() -> {
                rows.setValue(result);
                total.setValue(walletTotal);
                loading.setValue(false);
                if (notice != null) {
                    retiredNotice.setValue(notice);
                }
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
        // Computed once for the whole pass rather than per row: every card on screen is
        // being explained against the same query.
        List<Query> variants = SearchEngine.queryVariants(query);

        Map<String, CardMatch> byType = new HashMap<>();
        for (CardMatch m : matches) {
            byType.put(m.getCardTypeId(), m);
        }

        // Every card type the user holds has an index whether or not it matched, so a card
        // found by its own label can still report how many shops it covers.
        Map<String, CardTypeIndex> indexByType = new HashMap<>();
        for (CardTypeIndex index : indexes) {
            indexByType.put(index.getCardTypeId(), index);
        }

        List<CardRow> out = new ArrayList<>();
        for (CardEntity card : cards) {
            CardMatch match = byType.get(card.cardTypeId);

            // Normalized per keystroke rather than once at load, unlike the merchant lists.
            // A wallet holds a handful of cards where a card type holds thousands of shops,
            // so this costs nothing measurable — and it cannot drift out of step with the
            // cards it describes, which a cached parallel map eventually would.
            int labelScore = CardLabel.of(card.label).score(variants);

            if (match == null && labelScore == MatchScore.NONE) {
                // Neither this card's type, its merchants, nor its own name matched.
                continue;
            }

            CardTypeIndex index = match != null
                    ? match.getCardType()
                    : indexByType.get(card.cardTypeId);
            if (index == null) {
                // No index for a type the user holds should be impossible, but a row built
                // without one would crash rather than merely be wrong.
                continue;
            }

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

            if (match != null) {
                row.matchedStores = label(match, variants);
                row.matchedByCardName = match.isMatchedByCardName();
                row.matchedByCardProperName = match.isMatchedByCardProperName();
                row.hasOnlineMatch = match.hasOnlineMatch();
                row.totalMatchingStores = row.matchedStores.isEmpty()
                        ? 0
                        : engine.countMatchingStores(query, index);
            }

            if (labelScore > MatchScore.NONE) {
                // Treated exactly as a hit on the card type's own name, because it is the
                // same kind of answer: the row reports what the card covers rather than
                // naming a merchant. Someone typing the name they gave a card is asking for
                // the card, and answering "Accepted at ANNIVERSARY FLOWERS" would be the app
                // hearing a different question from the one asked.
                row.matchedByCardName = true;
                row.matchedByCardProperName = true;
                row.matchedStores = Collections.emptyList();
                row.totalMatchingStores = 0;
                row.hasOnlineMatch = false;
            }

            row.storeCount = index.getStores().size();
            row.partialStoreList = index.isPartialList();
            row.storesUpdatedAt = index.getStoresUpdatedAt();
            row.storeSource = index.getSourceLabel();
            row.hasUnreconciledMismatch = card.hasUnreconciledMismatch;
            row.score = Math.max(match == null ? MatchScore.NONE : match.getScore(), labelScore);
            // Status first: the retirement date is chosen by which state the card is in, so
            // reading it before it is assigned quietly dates every card to zero and leaves
            // the archive sorted by the alphabetical tiebreak instead.
            row.status = CardStatus.of(row.remaining, row.daysUntilExpiry, card.archivedAt);

            Long lastSpent = lastSpendTimes.get(card.id);
            row.retiredAt = RetiredAt.of(row.status, card.archivedAt,
                    Formats.expiryEndMillis(card.expiryDate),
                    lastSpent == null ? 0L : lastSpent, card.createdAt);

            out.add(row);
        }

        // Kept so that opening or closing the archive can regroup these instead of matching
        // the whole wallet a second time to reach the same answer.
        matchedRows = out;
        return group(out);
    }

    /**
     * Arranges already-matched rows into the live list and the archive behind it.
     *
     * <p>Split out from the matching so that a tap on the group header costs only this.
     */
    private List<CardRow> group(List<CardRow> rows) {
        // Two labels because the group means two different things. Browsing, it is the
        // wallet's back drawer; mid-search it is an answer of a kind.
        return WalletGrouping.group(rows, !currentQuery.trim().isEmpty(), archiveExpanded,
                new WalletGrouping.Labels() {
                    @Override
                    public String archive(int count) {
                        return getApplication().getString(R.string.archive_group, count);
                    }

                    @Override
                    public String alsoInArchive(int count) {
                        return getApplication()
                                .getString(R.string.archive_group_matched, count);
                    }
                });
    }

    /**
     * Turns the engine's merchant matches into the text and highlight the row will draw.
     *
     * <p>Here rather than in the adapter because it depends on the query, and a view holder
     * rebinding during a scroll should not be re-running matching rules to find out what to
     * bold.
     */
    private List<StoreLabel> label(CardMatch match, List<Query> variants) {
        List<StoreMatch> hits = match.getMatchedStores();
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }
        String format = getApplication().getString(R.string.store_listed_as);
        List<StoreLabel> labels = new ArrayList<>(hits.size());
        for (StoreMatch hit : hits) {
            labels.add(StoreLabel.of(hit, variants, format));
        }
        return labels;
    }
}
