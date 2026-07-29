package com.mycards.ui.detail;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.mycards.R;
import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.crypto.SecretVault;
import com.mycards.data.db.CardEntity;
import com.mycards.data.db.StoreCacheEntity;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.BiometricGate;
import com.mycards.ui.EdgeToEdge;
import com.mycards.ui.Formats;
import com.mycards.ui.edit.AddEditCardActivity;
import com.mycards.ui.reconcile.ReconcileActivity;

import java.util.Locale;

/**
 * Everything about one card: balance, expiry, spend history, and — behind an unlock — the
 * payment details needed to actually pay with it.
 */
public class CardDetailActivity extends AppCompatActivity {

    public static final String EXTRA_CARD_ID = "card_id";

    /** Revealed numbers re-hide themselves, in case the phone is put down still unlocked. */
    private static final long AUTO_HIDE_MS = 60_000L;

    private CardsRepository cardsRepo;
    private CatalogRepository catalogRepo;
    private long cardId;
    private CardEntity card;
    private CardTypeDef cardType;

    private SpendAdapter spendAdapter;
    private View secretBlock;
    private MaterialButton revealButton;
    private final Handler autoHide = new Handler(Looper.getMainLooper());
    private Runnable hideTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_detail);
        EdgeToEdge.apply(this);

        cardsRepo = new CardsRepository(this);
        catalogRepo = new CatalogRepository(this);
        cardId = getIntent().getLongExtra(EXTRA_CARD_ID, 0L);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        // Registering the toolbar is what makes the overflow menu ("Edit card") reachable.
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.card_details);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        secretBlock = findViewById(R.id.secretBlock);
        revealButton = findViewById(R.id.revealButton);
        revealButton.setOnClickListener(v -> toggleSecrets());

        RecyclerView spendList = findViewById(R.id.spendList);
        spendList.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.addSpend).setOnClickListener(v -> showAddSpendDialog());
        findViewById(R.id.reconcileButton).setOnClickListener(v -> openReconcile());
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSecrets();
        load();
    }

    private void load() {
        AppExecutors.io(() -> {
            card = cardsRepo.cards().getById(cardId);
            if (card == null) {
                AppExecutors.main(this::finish);
                return;
            }
            Catalog catalog = catalogRepo.loadCatalog();
            cardType = catalog.findById(card.cardTypeId);

            double remaining = cardsRepo.remainingBalance(cardId);
            StoreCacheEntity cache = com.mycards.data.db.AppDatabase.get(this)
                    .storeCacheDao().getByCardType(card.cardTypeId);
            java.util.List<com.mycards.data.db.SpendEntity> spends =
                    cardsRepo.spends().getForCard(cardId);

            AppExecutors.main(() -> render(remaining, cache, spends));
        });
    }

    private void render(double remaining,
                        StoreCacheEntity cache,
                        java.util.List<com.mycards.data.db.SpendEntity> spends) {
        String lang = Locale.getDefault().getLanguage();
        String tag = ("he".equals(lang) || "iw".equals(lang)) ? "he" : "en";
        String typeName = cardType != null ? cardType.displayName(tag) : card.cardTypeId;

        boolean hasLabel = card.label != null && !card.label.trim().isEmpty();
        ((TextView) findViewById(R.id.cardTitle)).setText(hasLabel ? card.label : typeName);
        TextView subtitle = findViewById(R.id.cardSubtitle);
        subtitle.setText(typeName);
        subtitle.setVisibility(hasLabel ? View.VISIBLE : View.GONE);

        ((TextView) findViewById(R.id.balance)).setText(Formats.money(remaining, card.currency));
        ((TextView) findViewById(R.id.balanceMeta)).setText(
                getString(R.string.of_initial, Formats.money(card.initialAmount, card.currency)));

        TextView expiry = findViewById(R.id.expiry);
        long days = Formats.daysUntil(card.expiryDate);
        if (days == Long.MAX_VALUE) {
            // No expiry recorded, so drop the line rather than announcing an absence.
            expiry.setVisibility(View.GONE);
        } else if (days < 0) {
            expiry.setVisibility(View.VISIBLE);
            expiry.setText(R.string.expired);
            expiry.setTextColor(getColor(R.color.expiry_expired));
        } else if (days <= 30) {
            expiry.setText(getString(R.string.expires_soon, (int) days));
            expiry.setTextColor(getColor(R.color.expiry_warning));
        } else {
            expiry.setText(getString(R.string.expires_on,
                    Formats.expiryToDisplay(card.expiryDate)));
        }

        // Say plainly how fresh the merchant list is — a stale list quietly presented as
        // current is what leaves someone stuck at a till.
        TextView storeInfo = findViewById(R.id.storeInfo);
        if (cache == null || cache.storeCount == 0) {
            storeInfo.setText(getString(R.string.store_list_unavailable) + "\n"
                    + getString(R.string.store_list_unavailable_explain));
        } else {
            StringBuilder sb = new StringBuilder()
                    .append(getString(R.string.card_count_stores, cache.storeCount))
                    .append(" · ")
                    .append(Formats.updatedAgo(this, cache.fetchedAt));
            if (cache.sourceType != null) {
                sb.append("\n").append(getString(R.string.store_list_source, cache.sourceType));
            }
            storeInfo.setText(sb.toString());
        }

        MaterialCardView mismatch = findViewById(R.id.mismatchCard);
        mismatch.setVisibility(card.hasUnreconciledMismatch ? View.VISIBLE : View.GONE);

        revealButton.setVisibility(card.hasSensitiveData() ? View.VISIBLE : View.GONE);

        MaterialButton giftLink = findViewById(R.id.giftLinkButton);
        giftLink.setVisibility(card.hasGiftUrl() ? View.VISIBLE : View.GONE);
        giftLink.setOnClickListener(v -> openGiftLink());

        spendAdapter = new SpendAdapter(card.currency, this::confirmDeleteSpend);
        RecyclerView spendList = findViewById(R.id.spendList);
        spendList.setAdapter(spendAdapter);
        spendAdapter.submitList(spends);

        boolean noSpends = spends.isEmpty();
        findViewById(R.id.noSpends).setVisibility(noSpends ? View.VISIBLE : View.GONE);
        // An empty list still reserves its padding and draws as a stray grey block.
        spendList.setVisibility(noSpends ? View.GONE : View.VISIBLE);
    }

    // --- sensitive values ---

    private void toggleSecrets() {
        if (secretBlock.getVisibility() == View.VISIBLE) {
            hideSecrets();
            return;
        }
        BiometricGate.authenticate(this,
                getString(R.string.biometric_title),
                getString(R.string.biometric_subtitle),
                new BiometricGate.Callback() {
                    @Override
                    public void onSuccess() {
                        revealSecrets();
                    }

                    @Override
                    public void onFailure() {
                        Toast.makeText(CardDetailActivity.this,
                                R.string.biometric_failed, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void revealSecrets() {
        AppExecutors.io(() -> {
            try {
                SecretVault vault = cardsRepo.vault();
                String pan = vault.decryptSecret(card.encPan);
                String cvv = vault.decryptSecret(card.encCvv);
                String exp = vault.decryptSecret(card.encCardExpiry);

                AppExecutors.main(() -> {
                    ((TextView) findViewById(R.id.panValue)).setText(
                            pan == null ? "" : getString(R.string.card_number) + "  " + pan);
                    ((TextView) findViewById(R.id.cvvValue)).setText(
                            cvv == null ? "" : getString(R.string.cvv) + "  " + cvv);
                    ((TextView) findViewById(R.id.cardExpiryValue)).setText(
                            exp == null ? "" : getString(R.string.card_expiry) + "  " + exp);

                    secretBlock.setVisibility(View.VISIBLE);
                    revealButton.setText(R.string.hide_card_number);

                    scheduleAutoHide();
                });
            } catch (Exception e) {
                AppExecutors.main(() -> Toast.makeText(this,
                        R.string.biometric_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void scheduleAutoHide() {
        if (hideTask != null) {
            autoHide.removeCallbacks(hideTask);
        }
        hideTask = () -> {
            if (secretBlock.getVisibility() == View.VISIBLE) {
                hideSecrets();
                Toast.makeText(this, R.string.auto_hidden, Toast.LENGTH_SHORT).show();
            }
        };
        autoHide.postDelayed(hideTask, AUTO_HIDE_MS);
    }

    private void hideSecrets() {
        if (hideTask != null) {
            autoHide.removeCallbacks(hideTask);
        }
        if (secretBlock != null) {
            secretBlock.setVisibility(View.GONE);
            // Clear the views so the plaintext is not left sitting in the view hierarchy.
            ((TextView) findViewById(R.id.panValue)).setText("");
            ((TextView) findViewById(R.id.cvvValue)).setText("");
            ((TextView) findViewById(R.id.cardExpiryValue)).setText("");
        }
        if (revealButton != null) {
            revealButton.setText(R.string.show_card_number);
        }
    }

    private void openGiftLink() {
        AppExecutors.io(() -> {
            try {
                String url = cardsRepo.vault().decryptData(card.encGiftUrl);
                if (url == null || url.trim().isEmpty()) {
                    return;
                }
                AppExecutors.main(() -> startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))));
            } catch (Exception e) {
                AppExecutors.main(() -> Toast.makeText(this,
                        R.string.biometric_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    // --- spending ---

    private void showAddSpendDialog() {
        AddSpendDialog.show(this, card.currency, (title, amount, storeName, spentAt) ->
                AppExecutors.io(() -> {
                    cardsRepo.addSpend(cardId, title, amount, storeName, spentAt,
                            com.mycards.data.db.SpendEntity.SOURCE_MANUAL);
                    AppExecutors.main(this::load);
                }));
    }

    private void confirmDeleteSpend(com.mycards.data.db.SpendEntity spend) {
        new AlertDialog.Builder(this)
                .setTitle(spend.title)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_spend, (d, w) -> AppExecutors.io(() -> {
                    cardsRepo.spends().delete(spend);
                    AppExecutors.main(this::load);
                }))
                .show();
    }

    private void openReconcile() {
        Intent intent = new Intent(this, ReconcileActivity.class);
        intent.putExtra(ReconcileActivity.EXTRA_CARD_ID, cardId);
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_detail, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_edit) {
            Intent intent = new Intent(this, AddEditCardActivity.class);
            intent.putExtra(AddEditCardActivity.EXTRA_CARD_ID, cardId);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the screen must not leave a card number on display behind you.
        hideSecrets();
    }
}
