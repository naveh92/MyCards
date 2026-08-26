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
import com.mycards.ui.stores.StoreListActivity;

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
    private StoreCacheEntity storeCache;
    private double remainingBalance;
    /** Decrypted and scheme-corrected gift link; null when nothing usable is stored. */
    private String giftUrl;
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
        // Rows are otherwise just stacked text and run together visually.
        spendList.addItemDecoration(
                new com.google.android.material.divider.MaterialDividerItemDecoration(
                        this, LinearLayoutManager.VERTICAL));

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

        remainingBalance = remaining;
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

        storeCache = cache;
        renderStoreListRow();

        MaterialCardView mismatch = findViewById(R.id.mismatchCard);
        mismatch.setVisibility(card.hasUnreconciledMismatch ? View.VISIBLE : View.GONE);

        revealButton.setVisibility(card.hasSensitiveData() ? View.VISIBLE : View.GONE);

        // Resolved once here rather than on click. A link that decrypts to nothing usable
        // would otherwise show a button that could only fail, which is how an empty VIEW
        // intent was reaching the system and crashing.
        MaterialButton giftLink = findViewById(R.id.giftLinkButton);
        giftLink.setVisibility(View.GONE);
        if (card.hasGiftUrl()) {
            AppExecutors.io(() -> {
                String resolved = resolveGiftUrl();
                AppExecutors.main(() -> {
                    giftUrl = resolved;
                    giftLink.setVisibility(resolved == null ? View.GONE : View.VISIBLE);
                });
            });
        }
        giftLink.setOnClickListener(v -> openGiftLink());

        spendAdapter = new SpendAdapter(card.currency, new SpendAdapter.OnSpendAction() {
            @Override
            public void onEdit(com.mycards.data.db.SpendEntity spend) {
                editSpend(spend);
            }

            @Override
            public void onDelete(com.mycards.data.db.SpendEntity spend) {
                confirmDeleteSpend(spend);
            }
        });
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

        // With no secure lock screen the vault could not create an auth-bound key, so these
        // values are not behind the OS gate and there is nothing for a prompt to verify.
        // Demanding one anyway just fails and hides data the user is entitled to see.
        if (!cardsRepo.vault().isBiometricProtectionAvailable()) {
            revealSecrets();
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

    /**
     * Decrypts the gift link and makes it openable.
     *
     * @return a URL with a scheme, or null when there is nothing usable stored
     */
    private String resolveGiftUrl() {
        try {
            String url = cardsRepo.vault().decryptData(card.encGiftUrl);
            if (url == null || url.trim().isEmpty()) {
                return null;
            }
            url = url.trim();
            // "buyme.co.il" is a perfectly reasonable thing to paste in, but without a
            // scheme no activity will claim the intent.
            if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
                url = "https://" + url;
            }
            return url;
        } catch (Exception e) {
            android.util.Log.w("CardDetailActivity", "could not decrypt the gift link", e);
            return null;
        }
    }

    /**
     * Opens the issuer's gift page, behind the same unlock as the card number.
     *
     * <p>The link is spendable. An issuer's gift page needs no login — that is the whole
     * reason the balance checker can use it — so it shows the voucher code and balance to
     * anyone who opens it, and anyone who opens it can spend the card. That is exactly the
     * consequence the card number is protected against, yet this was a single tap for anyone
     * holding the phone while the card number sat behind biometrics and auto-hid after a
     * minute. Two actions with the same consequence should not have different protection.
     *
     * <p>The link stays encrypted under the non-auth key, because {@code BalanceCheckWorker}
     * reads it from the background where no prompt can be shown. The gate belongs on the act
     * of opening it, not on the storage.
     */
    private void openGiftLink() {
        if (giftUrl == null) {
            Toast.makeText(this, R.string.gift_link_unusable, Toast.LENGTH_SHORT).show();
            return;
        }
        // No secure lock screen means there is nothing for a prompt to verify, exactly as
        // when revealing the card number.
        if (!cardsRepo.vault().isBiometricProtectionAvailable()) {
            launchGiftLink();
            return;
        }
        BiometricGate.authenticate(this,
                getString(R.string.biometric_title),
                getString(R.string.biometric_gift_link),
                new BiometricGate.Callback() {
                    @Override
                    public void onSuccess() {
                        launchGiftLink();
                    }

                    @Override
                    public void onFailure() {
                        Toast.makeText(CardDetailActivity.this,
                                R.string.biometric_failed, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void launchGiftLink() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(giftUrl)));
        } catch (android.content.ActivityNotFoundException noBrowser) {
            // A device with nothing registered for http(s) is unusual but not impossible.
            Toast.makeText(this, R.string.gift_link_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    // --- spending ---

    /**
     * Draws the way in to "where does this card work?".
     *
     * <p>This used to be a tap on the card's name, with no hint that it was one — reference
     * information hidden behind an affordance nobody could see. Given a row of its own it
     * states the number of shops outright, which is both the label that makes the row
     * obviously tappable and, on its own, the most useful thing the screen can say about
     * what a card is worth.
     *
     * <p>When there is no list at all the row stays put but stops being a door: it explains
     * why instead. A tappable row leading to an empty screen is worse than a plain sentence.
     */
    private void renderStoreListRow() {
        MaterialCardView card = findViewById(R.id.storeListCard);
        TextView title = findViewById(R.id.storeListTitle);
        TextView meta = findViewById(R.id.storeListMeta);
        View chevron = findViewById(R.id.storeListChevron);

        boolean haveList = storeCache != null && storeCache.storeCount > 0;
        if (!haveList) {
            title.setText(R.string.store_list_unavailable);
            meta.setText(R.string.store_list_unavailable_explain);
            chevron.setVisibility(View.GONE);
            card.setClickable(false);
            card.setFocusable(false);
            card.setOnClickListener(null);
            return;
        }

        title.setText(getResources().getQuantityString(
                R.plurals.store_accepted_at, storeCache.storeCount, storeCache.storeCount));
        meta.setText(Formats.updatedAgo(this, storeCache.fetchedAt));
        chevron.setVisibility(View.VISIBLE);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openStoreList());
    }

    private void openStoreList() {
        Intent intent = new Intent(this, StoreListActivity.class);
        intent.putExtra(StoreListActivity.EXTRA_CARD_ID, cardId);
        startActivity(intent);
    }

    private void showAddSpendDialog() {
        // Capped at what is actually left: a gift card cannot go overdrawn, so a spend
        // larger than the balance is always a typo rather than a real purchase. Floored at
        // zero so a card already in the red — from entries made before this check existed —
        // reports "0 left" rather than a nonsensical negative remainder.
        AddSpendDialog.show(this, card.currency, Math.max(0d, remainingBalance),
                (title, amount, storeName, spentAt) ->
                AppExecutors.io(() -> {
                    cardsRepo.addSpend(cardId, title, amount, storeName, spentAt,
                            com.mycards.data.db.SpendEntity.SOURCE_MANUAL);
                    AppExecutors.main(this::load);
                }));
    }

    private void editSpend(com.mycards.data.db.SpendEntity spend) {
        // The cap excludes this entry's own amount, so raising it is checked against the
        // balance it would actually leave rather than a total it is already part of.
        double available = Math.max(0d, remainingBalance + spend.amount);

        AddSpendDialog.showEdit(this, card.currency, available, spend,
                (title, amount, storeName, spentAt) -> AppExecutors.io(() -> {
                    spend.title = title;
                    spend.amount = amount;
                    spend.storeName = storeName;
                    spend.spentAt = spentAt;
                    cardsRepo.spends().update(spend);
                    AppExecutors.main(this::load);
                }));
    }

    private void confirmDeleteCard() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_card)
                .setMessage(R.string.delete_card_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> AppExecutors.io(() -> {
                    cardsRepo.cards().delete(card);
                    AppExecutors.main(this::finish);
                }))
                .show();
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

        // Menu items cannot be tinted from XML, so the destructive one is coloured here.
        // Deleting a card takes its spend history with it and cannot be undone, so it
        // should not look like the neutral action sitting above it.
        MenuItem delete = menu.findItem(R.id.action_delete_card);
        if (delete != null) {
            android.text.SpannableString title =
                    new android.text.SpannableString(delete.getTitle());
            title.setSpan(new android.text.style.ForegroundColorSpan(
                            getColor(R.color.expiry_expired)),
                    0, title.length(), 0);
            delete.setTitle(title);
        }
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
        if (item.getItemId() == R.id.action_delete_card) {
            confirmDeleteCard();
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
