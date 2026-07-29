package com.mycards.ui.search;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.mycards.R;
import com.mycards.ui.EdgeToEdge;
import com.mycards.ui.detail.CardDetailActivity;
import com.mycards.ui.edit.AddEditCardActivity;
import com.mycards.ui.settings.SettingsActivity;

/**
 * The checkout screen: type part of a shop's name, see which of your cards works there.
 *
 * <p>Everything here is tuned for the twenty seconds you have at a till — the search field
 * takes focus immediately, results update as you type, and each row states the merchant that
 * matched so you can tell at a glance whether the app understood you.
 */
public class SearchActivity extends AppCompatActivity {

    /** Long enough to avoid re-querying mid-word, short enough to feel instant. */
    private static final long SEARCH_DEBOUNCE_MS = 120L;

    private SearchViewModel viewModel;
    private CardRowAdapter adapter;
    private TextInputEditText searchInput;
    private TextView emptyState;

    private final Handler debounce = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        EdgeToEdge.apply(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        emptyState = findViewById(R.id.emptyState);
        searchInput = findViewById(R.id.searchInput);

        RecyclerView results = findViewById(R.id.results);
        results.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CardRowAdapter(this::openCard);
        results.setAdapter(adapter);

        FloatingActionButton addCard = findViewById(R.id.addCard);
        addCard.setOnClickListener(v ->
                startActivity(new Intent(this, AddEditCardActivity.class)));

        viewModel = new ViewModelProvider(this).get(SearchViewModel.class);
        viewModel.rows().observe(this, rows -> {
            adapter.submitList(rows);
            updateEmptyState(rows == null || rows.isEmpty());
        });

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

        requestNotificationPermissionIfNeeded();
    }

    private void scheduleSearch(String query) {
        if (pendingSearch != null) {
            debounce.removeCallbacks(pendingSearch);
        }
        pendingSearch = () -> viewModel.search(query);
        debounce.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private void updateEmptyState(boolean empty) {
        if (!empty) {
            emptyState.setVisibility(View.GONE);
            return;
        }
        String query = viewModel.currentQuery();
        if (query.trim().isEmpty()) {
            // Distinguish "you own nothing" from "nothing matched" — different next steps.
            emptyState.setText(getString(R.string.no_cards_yet));
        } else {
            emptyState.setText(getString(R.string.no_results, query));
        }
        emptyState.setVisibility(View.VISIBLE);
    }

    private void openCard(CardRow row) {
        Intent intent = new Intent(this, CardDetailActivity.class);
        intent.putExtra(CardDetailActivity.EXTRA_CARD_ID, row.cardId);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Cards, balances and merchant lists may all have changed on another screen.
        viewModel.reload();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_search, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Needed from API 33 so the unlogged-transaction alert can actually be shown. */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            // Declining is fine: the mismatch still shows as a warning on the card row.
        }).launch("android.permission.POST_NOTIFICATIONS");
    }
}
