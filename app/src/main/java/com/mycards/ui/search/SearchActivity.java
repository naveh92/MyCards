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

import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.mycards.R;
import com.mycards.cards.CardStatus;
import com.mycards.data.db.AppDatabase;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.EdgeToEdge;
import com.mycards.ui.detail.CardDetailActivity;
import com.mycards.ui.edit.AddEditCardActivity;
import com.mycards.ui.history.HistoryActivity;
import com.mycards.ui.settings.SettingsActivity;

/**
 * The checkout screen: type part of a shop's name, see which of your cards works there.
 *
 * <p>Everything here is tuned for the twenty seconds you have at a checkout counter — the search field
 * takes focus immediately, results update as you type, and each row states the merchant that
 * matched so you can tell at a glance whether the app understood you.
 */
public class SearchActivity extends AppCompatActivity {

    /**
     * A query handed over by another screen.
     *
     * <p>Sent by the store list when a card turns out not to cover the shop someone was
     * looking for: the next question is always "then which of my cards does?", and this is
     * what carries it here instead of making them type it twice.
     */
    public static final String EXTRA_QUERY = "query";

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
        // Resolved from the theme rather than named as a colour, so the matched words stay
        // legible against both the light and the dark surface.
        adapter = new CardRowAdapter(
                MaterialColors.getColor(results, androidx.appcompat.R.attr.colorPrimary),
                this::openCard,
                () -> viewModel.toggleArchive());
        results.setAdapter(adapter);

        FloatingActionButton addCard = findViewById(R.id.addCard);
        addCard.setOnClickListener(v ->
                startActivity(new Intent(this, AddEditCardActivity.class)));

        viewModel = new ViewModelProvider(this).get(SearchViewModel.class);
        viewModel.rows().observe(this, rows -> {
            adapter.submitList(rows);
            updateEmptyState(rows);
        });
        viewModel.retiredNotice().observe(this, this::showRetiredNotice);

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

        applyIncomingQuery(getIntent());
    }

    /**
     * Adopts a query passed in by another screen.
     *
     * <p>Removed from the intent once used. It is a one-time handover, and leaving it in
     * place would have a rotation — or a return from the card screen — silently retype it
     * over whatever has been searched for since.
     *
     * <p>The view model is told directly as well as through the field. The text watcher gets
     * there eventually, but only after the debounce, and {@code onResume} rebuilds the list
     * in between — from the query the model still thinks is current.
     */
    private void applyIncomingQuery(Intent intent) {
        if (intent == null) {
            return;
        }
        String query = intent.getStringExtra(EXTRA_QUERY);
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        intent.removeExtra(EXTRA_QUERY);
        searchInput.setText(query);
        searchInput.setSelection(query.length());
        viewModel.search(query);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // This activity is singleTop and the handover clears back to it, so it arrives here
        // rather than as a second wallet stacked on top of the first.
        setIntent(intent);
        applyIncomingQuery(intent);
    }

    private void scheduleSearch(String query) {
        if (pendingSearch != null) {
            debounce.removeCallbacks(pendingSearch);
        }
        pendingSearch = () -> viewModel.search(query);
        debounce.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    /**
     * Explains an empty result, where "empty" means no card you can actually spend.
     *
     * <p>The archive group does not count as an answer. A search that turns up nothing but an
     * expired card still needs to say so out loud — the group header alone reads as a result,
     * and at a checkout counter that is the difference between putting a card on the counter
     * and knowing not to. Both are shown together: the sentence above, the group below it.
     */
    private void updateEmptyState(java.util.List<CardRow> rows) {
        if (hasSpendableCard(rows)) {
            emptyState.setVisibility(View.GONE);
            return;
        }
        String query = viewModel.currentQuery();
        if (query.trim().isEmpty()) {
            // Three different situations that all look like an empty screen, and three
            // different next steps: add a card, go and unarchive one, or nothing at all.
            emptyState.setText(getString(viewModel.walletIsEmpty()
                    ? R.string.no_cards_yet
                    : R.string.all_cards_retired));
        } else if (viewModel.anyPartialStoreList()) {
            // One of the wallet's lists is known to have gaps, so "not accepted" would be
            // asserting more than is known.
            emptyState.setText(getString(R.string.no_results_partial, query));
        } else {
            emptyState.setText(getString(R.string.no_results, query));
        }
        emptyState.setVisibility(View.VISIBLE);
    }

    /** True when the list holds at least one card that is neither a header nor retired. */
    private boolean hasSpendableCard(java.util.List<CardRow> rows) {
        if (rows == null) {
            return false;
        }
        for (CardRow row : rows) {
            if (!row.isHeader() && !row.status.isRetired()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Says so when a card has left the wallet on its own.
     *
     * <p>Archiving announces itself, because someone chose it. These two do not: a card
     * empties inside the dialog that logs the purchase, and a card expires overnight with the
     * app closed. Without a word here the card is simply missing next time the wallet is
     * opened, which is the difference between an app that tidied up after you and an app that
     * lost one of your cards.
     *
     * <p>Carries a way to look, because the natural next thought is "wait, which one?".
     */
    private void showRetiredNotice(SearchViewModel.RetiredNotice notice) {
        if (notice == null) {
            return;
        }
        String message;
        if (notice.count > 1) {
            message = getResources().getQuantityString(
                    R.plurals.cards_moved_to_archive, notice.count, notice.count);
        } else if (notice.status == CardStatus.EXPIRED) {
            message = getString(R.string.card_now_expired, notice.cardName);
        } else {
            message = getString(R.string.card_now_empty, notice.cardName);
        }

        Snackbar.make(findViewById(R.id.searchRoot), message, Snackbar.LENGTH_LONG)
                .setAction(R.string.show_archive, v -> viewModel.expandArchive())
                .show();
        // Consumed, so a rotation does not replay it.
        viewModel.noticeShown();
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
        if (item.getItemId() == R.id.action_history) {
            startActivity(new Intent(this, HistoryActivity.class));
            return true;
        }
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
