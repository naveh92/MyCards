package com.mycards.ui.search;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

import androidx.activity.result.ActivityResultLauncher;
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
import com.mycards.data.db.AppDatabase;
import com.mycards.ui.AppExecutors;
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

    private static final String PREFS = "mycards_ui";
    private static final String KEY_ASKED_NOTIFICATIONS = "asked_notifications";

    private SearchViewModel viewModel;
    private CardRowAdapter adapter;
    private TextInputEditText searchInput;
    private TextView emptyState;

    private final Handler debounce = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    // Registered as a field rather than inside onCreate: the request is now fired from a
    // background check that lands after the activity is RESUMED, and registering that late
    // throws. Field initialisers run during construction, which is the supported point.
    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Declining is fine: the mismatch still shows as a warning on the card row.
            });

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
        // Placed here rather than in onCreate so that adding a card with a gift link brings
        // up the prompt on the way back to the list, when the reason for it is fresh.
        requestNotificationPermissionWhenUseful();
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

    /**
     * Asks for notification permission only once there is something to notify about.
     *
     * <p>This app raises exactly one alert: a card holds less money than its spend log
     * accounts for. That check can only run against a card carrying an auth-free gift link,
     * so for anyone who never stores one the permission is worth nothing. Prompting on first
     * launch — before a single card exists — asks people to grant something for a feature
     * they have not met and may never trigger, which is how a dialog gets dismissed on
     * reflex and the permission lost for good.
     *
     * <p>So the prompt waits for the balance checker to actually have a card to watch. Asked
     * at that point it has an obvious answer.
     */
    private void requestNotificationPermissionWhenUseful() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) {
            // Android stops showing the dialog after two refusals anyway; re-launching it
            // every time the list is opened would just be a silent no-op on a loop.
            return;
        }

        AppExecutors.io(() -> {
            boolean worthAsking =
                    !AppDatabase.get(this).cardDao().getCardsWithGiftUrl().isEmpty();
            if (!worthAsking) {
                return;
            }
            AppExecutors.main(() -> {
                prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply();
                notificationPermission.launch("android.permission.POST_NOTIFICATIONS");
            });
        });
    }
}
