package com.mycards.ui.history;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.mycards.R;
import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.data.db.CardEntity;
import com.mycards.search.StoreNameIndex;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.EdgeToEdge;
import com.mycards.ui.Formats;
import com.mycards.ui.detail.AddSpendDialog;
import com.mycards.ui.detail.CardDetailActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything ever spent, across every card.
 *
 * <p>The card screen answers "what happened to <em>this</em> card"; this one answers "where
 * did my gift money go", which is a different question and could not be assembled from the
 * per-card logs without opening every card in turn. Cards that have been archived are in here
 * too — putting a card away does not unspend what was spent on it.
 */
public class HistoryActivity extends AppCompatActivity {

    /** The same figure the wallet search uses: long enough not to re-query mid-word. */
    private static final long SEARCH_DEBOUNCE_MS = 120L;

    private HistoryViewModel viewModel;
    private HistoryAdapter adapter;
    private TextInputEditText searchInput;
    private TextView summary;
    private TextView emptyText;

    private CardsRepository cardsRepo;
    private CatalogRepository catalogRepo;

    /**
     * Shop names per card type, for the suggestions in the edit dialog.
     *
     * <p>Loaded when a purchase is actually opened rather than up front. Building these means
     * parsing a merchant list per card type in the wallet, and a screen someone opened to
     * read their history should not pay for a dialog they may never open.
     */
    private final Map<String, StoreNameIndex> suggestionsByType = new HashMap<>();

    private final Handler debounce = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        EdgeToEdge.apply(this);

        cardsRepo = new CardsRepository(this);
        catalogRepo = new CatalogRepository(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.spending_history);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        summary = findViewById(R.id.summary);
        emptyText = findViewById(R.id.emptyText);
        searchInput = findViewById(R.id.searchInput);

        RecyclerView list = findViewById(R.id.historyList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter(new HistoryAdapter.OnPurchaseAction() {
            @Override
            public void onEdit(HistoryRow row) {
                editPurchase(row);
            }

            @Override
            public void onDelete(HistoryRow row) {
                confirmDelete(row);
            }

            @Override
            public void onOpenCard(HistoryRow row) {
                openCard(row);
            }
        });
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(HistoryViewModel.class);
        viewModel.rows().observe(this, rows -> {
            adapter.submitList(rows);
            renderEmptyState(rows);
        });
        viewModel.summary().observe(this, this::renderSummary);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleSearch(s == null ? "" : s.toString());
            }
        });

        // Enter puts the keyboard away rather than doing nothing: the results are already
        // there, and what is wanted next is to see them.
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
            searchInput.clearFocus();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A purchase may have been logged or edited on a card screen since this was opened.
        viewModel.reload();
    }

    private void scheduleSearch(String query) {
        if (pendingSearch != null) {
            debounce.removeCallbacks(pendingSearch);
        }
        pendingSearch = () -> viewModel.search(query);
        debounce.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingSearch != null) {
            debounce.removeCallbacks(pendingSearch);
        }
    }

    /**
     * The running total for whatever is on screen.
     *
     * <p>It reports the filtered set rather than the whole log on purpose: with a query
     * typed, "₪380 across 3 purchases" is the answer to "how much have I spent at Castro",
     * which is most of why anyone searches a spending log at all.
     */
    private void renderSummary(HistoryViewModel.Summary state) {
        if (state == null || state.count == 0) {
            summary.setVisibility(View.GONE);
            return;
        }
        summary.setVisibility(View.VISIBLE);
        if (state.currency == null) {
            // Purchases in more than one currency: a single total would be arithmetic on
            // incomparable numbers, so only the count is claimed.
            summary.setText(getResources().getQuantityString(
                    R.plurals.history_count, state.count, state.count));
            return;
        }
        summary.setText(getResources().getQuantityString(
                R.plurals.history_summary, state.count,
                Formats.money(state.total, state.currency), state.count));
    }

    private void renderEmptyState(List<HistoryRow> rows) {
        if (rows != null && !rows.isEmpty()) {
            emptyText.setVisibility(View.GONE);
            return;
        }
        // Two different situations behind one blank screen, with two different next steps:
        // go and log something, or change what you searched for.
        String query = viewModel.currentQuery().trim();
        if (viewModel.logIsEmpty()) {
            emptyText.setText(R.string.history_nothing_logged);
        } else {
            emptyText.setText(getString(R.string.history_no_match, query));
        }
        emptyText.setVisibility(View.VISIBLE);
    }

    /**
     * Opens the card a purchase was charged to.
     *
     * <p>The obvious next question after reading a line of history is "what else is on that
     * card, and what is left on it" — and without this the only way there was to remember
     * which card it was, go back, and find it in the wallet, which for an archived card
     * means opening the archive too.
     */
    private void openCard(HistoryRow row) {
        Intent intent = new Intent(this, CardDetailActivity.class);
        intent.putExtra(CardDetailActivity.EXTRA_CARD_ID, row.spend.cardId);
        startActivity(intent);
    }

    private void editPurchase(HistoryRow row) {
        withSuggestions(row.spend.cardId, suggestions ->
                AddSpendDialog.showEdit(this, row.currency, viewModel.availableFor(row.spend),
                        row.spend, suggestions,
                        (title, amount, storeName, spentAt) -> AppExecutors.io(() -> {
                            row.spend.title = title;
                            row.spend.amount = amount;
                            row.spend.storeName = storeName;
                            row.spend.spentAt = spentAt;
                            cardsRepo.spends().update(row.spend);
                            AppExecutors.main(() -> viewModel.reload());
                        })));
    }

    private void confirmDelete(HistoryRow row) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(row.spend.title)
                // Named here and not on the card screen's log, where there is only one card
                // it could belong to. Here there are many, and deleting from the wrong one
                // is a mistake with no undo.
                .setMessage(getString(R.string.history_delete_from, row.cardName))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_spend, (d, w) -> AppExecutors.io(() -> {
                    cardsRepo.spends().delete(row.spend);
                    AppExecutors.main(() -> viewModel.reload());
                }))
                .show();
    }

    private interface SuggestionsReady {
        void onReady(StoreNameIndex suggestions);
    }

    /**
     * Fetches the shop names for a purchase's card, and calls back on the main thread.
     *
     * <p>Cached per card type: several purchases in the list may share one, and the list is
     * hundreds of kilobytes of JSON to parse. Failure is not an error here — the dialog
     * simply offers nothing, which is exactly what it does for a card whose issuer publishes
     * no list at all.
     */
    private void withSuggestions(long cardId, SuggestionsReady ready) {
        AppExecutors.io(() -> {
            CardEntity card = cardsRepo.cards().getById(cardId);
            String typeId = card == null ? null : card.cardTypeId;
            StoreNameIndex cached = typeId == null ? null : suggestionsByType.get(typeId);
            StoreNameIndex index = cached != null
                    ? cached
                    : (typeId == null
                            ? StoreNameIndex.empty()
                            : catalogRepo.loadStoreSuggestions(typeId));
            AppExecutors.main(() -> {
                if (typeId != null) {
                    suggestionsByType.put(typeId, index);
                }
                if (!isFinishing() && !isDestroyed()) {
                    ready.onReady(index);
                }
            });
        });
    }
}
