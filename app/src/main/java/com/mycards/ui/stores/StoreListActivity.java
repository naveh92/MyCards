package com.mycards.ui.stores;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.textfield.TextInputEditText;
import com.mycards.R;
import com.mycards.ui.EdgeToEdge;
import com.mycards.ui.Formats;
import com.mycards.ui.search.SearchActivity;

import java.util.List;

/**
 * Every shop one card is accepted in.
 *
 * <p>The inverse of the search screen, and deliberately built to look like it: same toolbar,
 * same field in the same place, same live filtering. One gesture answers "which of my cards
 * works here?" and the same gesture answers "where does this card work?".
 *
 * <p>One difference is deliberate. The search screen takes focus and raises the keyboard on
 * launch, because you arrived there to type. Here you arrived to look — the first question is
 * usually "what can I do with this card?" rather than "is Zara on it?" — and a keyboard
 * covering half the list on arrival would hide the very thing being asked for. The field
 * stays one tap away instead.
 */
public class StoreListActivity extends AppCompatActivity {

    public static final String EXTRA_CARD_ID = "card_id";

    /** Matches the search screen, so the two feel like one control. */
    private static final long SEARCH_DEBOUNCE_MS = 120L;

    private StoreListViewModel viewModel;
    private StoreRowAdapter adapter;

    private RecyclerView list;
    private TextView resultCount;
    private TextView partialNote;
    private View emptyBlock;
    private TextView emptyText;
    private MaterialButton searchAllCards;
    private Chip onlineChip;
    private TextInputEditText searchInput;

    private StoreListViewModel.CardInfo info;

    private final Handler debounce = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_store_list);
        EdgeToEdge.apply(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        resultCount = findViewById(R.id.resultCount);
        partialNote = findViewById(R.id.partialNote);
        emptyBlock = findViewById(R.id.emptyBlock);
        emptyText = findViewById(R.id.emptyText);
        searchAllCards = findViewById(R.id.searchAllCards);
        onlineChip = findViewById(R.id.onlineChip);
        searchInput = findViewById(R.id.searchInput);

        list = findViewById(R.id.storeList);
        list.setLayoutManager(new LinearLayoutManager(this));
        // Names alone run together into a wall of text; a rule between them gives the eye
        // something to count down.
        list.addItemDecoration(new MaterialDividerItemDecoration(this, LinearLayoutManager.VERTICAL));

        adapter = new StoreRowAdapter(
                MaterialColors.getColor(list, androidx.appcompat.R.attr.colorPrimary),
                this::copyStoreName);
        list.setAdapter(adapter);

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

        // The list is already filtered by the time this key is pressed, so there is nothing
        // to submit — but the keyboard should still get out of the way of the results it
        // just produced, which is what pressing it means.
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

        onlineChip.setOnCheckedChangeListener((v, checked) -> viewModel.setOnlineOnly(checked));
        searchAllCards.setOnClickListener(v -> searchWholeWallet());

        viewModel = new ViewModelProvider(this).get(StoreListViewModel.class);
        viewModel.info().observe(this, this::renderChrome);
        viewModel.rows().observe(this, this::renderRows);
        viewModel.load(getIntent().getLongExtra(EXTRA_CARD_ID, 0L));
    }

    private void scheduleSearch(String query) {
        if (pendingSearch != null) {
            debounce.removeCallbacks(pendingSearch);
        }
        pendingSearch = () -> viewModel.setQuery(query);
        debounce.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private void renderChrome(StoreListViewModel.CardInfo loaded) {
        info = loaded;
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(loaded.title);
        }

        // A filter that can only ever return everything is a control that does nothing, so
        // it only appears once there is something online to filter down to.
        onlineChip.setVisibility(loaded.hasOnlineStores ? View.VISIBLE : View.GONE);

        // Stated up front rather than behind the information button. A reader who does not
        // know the list has gaps will read a missing shop as "not accepted", which is the
        // one wrong answer this app must never give.
        partialNote.setVisibility(loaded.partialList ? View.VISIBLE : View.GONE);
    }

    private void renderRows(List<StoreRow> rows) {
        adapter.submit(rows);
        // A filtered list is a new list, and leaving it scrolled to wherever the last one
        // was means the best match can land off-screen above.
        list.scrollToPosition(0);

        int total = info == null ? 0 : info.totalStores;
        boolean filtered = !viewModel.currentQuery().trim().isEmpty() || viewModel.isOnlineOnly();

        resultCount.setText(filtered
                ? getString(R.string.store_count_filtered, rows.size(), total)
                : getResources().getQuantityString(R.plurals.store_count, total, total));
        resultCount.setVisibility(total == 0 ? View.GONE : View.VISIBLE);

        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        emptyBlock.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (rows.isEmpty()) {
            renderEmptyState(total);
        }
    }

    private void renderEmptyState(int total) {
        if (total == 0) {
            // No list was ever fetched for this card type; nothing was filtered away.
            emptyText.setText(R.string.store_list_unavailable);
            searchAllCards.setVisibility(View.GONE);
            return;
        }

        String query = viewModel.currentQuery().trim();
        if (query.isEmpty()) {
            // Only the online filter is on, and it cleared the list.
            emptyText.setText(R.string.store_no_online);
            searchAllCards.setVisibility(View.GONE);
            return;
        }

        // With a knowingly incomplete list, "not on this card" claims more than is known.
        emptyText.setText(info != null && info.partialList
                ? getString(R.string.store_no_match_partial, query)
                : getString(R.string.store_no_match, query));

        // The way out of a dead end: this card does not cover the shop, so ask the wallet.
        searchAllCards.setVisibility(View.VISIBLE);
        searchAllCards.setText(getString(R.string.store_search_all_cards, query));
    }

    /**
     * Hands the query to the wallet-wide search, which answers the question this screen
     * just failed to: if not this card, then which one?
     *
     * <p>Cleared back to the existing search screen rather than stacked on top of it, so the
     * back button returns to the wallet instead of walking back through a card the user has
     * already been told is no use here.
     */
    private void searchWholeWallet() {
        Intent intent = new Intent(this, SearchActivity.class);
        intent.putExtra(SearchActivity.EXTRA_QUERY, viewModel.currentQuery().trim());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void copyStoreName(String name) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(name, name));
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }

    /** Where this card's merchant list came from and how old it is. */
    private void showProvenance() {
        String message;
        if (info == null || info.totalStores == 0) {
            message = getString(R.string.store_list_unavailable_explain);
        } else {
            StringBuilder sb = new StringBuilder()
                    .append(getResources().getQuantityString(
                            R.plurals.store_count, info.totalStores, info.totalStores))
                    .append('\n')
                    .append(Formats.updatedAgo(this, info.updatedAt));
            if (info.sourceType != null) {
                sb.append('\n').append(getString(R.string.store_list_source, info.sourceType));
            }
            if (info.partialList) {
                sb.append("\n\n").append(getString(R.string.store_list_partial));
            }
            message = sb.toString();
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.store_list_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_store_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_about_list) {
            showProvenance();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingSearch != null) {
            debounce.removeCallbacks(pendingSearch);
        }
    }
}
