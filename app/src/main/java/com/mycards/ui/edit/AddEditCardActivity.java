package com.mycards.ui.edit;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mycards.R;
import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.cards.GiftLink;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.RemoteConfig;
import com.mycards.data.crypto.SecretVault;
import com.mycards.data.db.CardEntity;
import com.mycards.data.AndroidAssetLoader;
import com.mycards.data.source.GiftPageDetails;
import com.mycards.data.source.GiftPageReader;
import com.mycards.data.source.Http;
import com.mycards.data.source.SourceEnv;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.BiometricGate;
import com.mycards.ui.ExpiryTextWatcher;
import com.mycards.ui.Formats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Adds or edits a card, including the optional encrypted payment details. */
public class AddEditCardActivity extends AppCompatActivity {

    private static final String TAG = "AddEditCardActivity";

    public static final String EXTRA_CARD_ID = "card_id";

    private CardsRepository cardsRepo;
    private CatalogRepository catalogRepo;

    private MaterialAutoCompleteTextView cardTypeInput;
    private TextInputLayout cardTypeLayout;
    private TextInputLayout amountLayout;
    private TextInputLayout expiryLayout;
    private TextInputLayout giftUrlLayout;
    private TextInputEditText labelInput;
    private TextInputEditText amountInput;
    private TextInputEditText expiryInput;
    private TextInputEditText panInput;
    private TextInputEditText cvvInput;
    private TextInputEditText cardExpiryInput;
    private TextInputEditText giftUrlInput;
    private TextInputEditText notesInput;

    private final List<CardTypeDef> cardTypes = new ArrayList<>();
    private CardTypeDef selectedType;

    private ExpiryTextWatcher giftExpiryWatcher;
    private ExpiryTextWatcher cardExpiryWatcher;

    private long editingCardId = 0L;
    private CardEntity editing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_edit_card);
        com.mycards.ui.EdgeToEdge.apply(this);

        cardsRepo = new CardsRepository(this);
        catalogRepo = new CatalogRepository(this);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        cardTypeLayout = findViewById(R.id.cardTypeLayout);
        cardTypeInput = findViewById(R.id.cardTypeInput);
        amountLayout = findViewById(R.id.amountLayout);
        expiryLayout = findViewById(R.id.expiryLayout);
        giftUrlLayout = findViewById(R.id.giftUrlLayout);
        labelInput = findViewById(R.id.labelInput);
        amountInput = findViewById(R.id.amountInput);
        expiryInput = findViewById(R.id.expiryInput);
        panInput = findViewById(R.id.panInput);
        cvvInput = findViewById(R.id.cvvInput);
        cardExpiryInput = findViewById(R.id.cardExpiryInput);
        giftUrlInput = findViewById(R.id.giftUrlInput);
        notesInput = findViewById(R.id.notesInput);

        wireFillFromLink();

        editingCardId = getIntent().getLongExtra(EXTRA_CARD_ID, 0L);
        toolbar.setTitle(editingCardId > 0 ? R.string.edit_card : R.string.add_card);

        // Without a secure lock screen the sensitive fields cannot be biometric-gated;
        // say so rather than implying a protection that is not there.
        if (!cardsRepo.vault().isBiometricProtectionAvailable()) {
            TextView note = findViewById(R.id.securityNote);
            note.setText(R.string.no_secure_lock_warning);
        }

        // Both expiries are MM/YY and mirror each other until one is edited on purpose.
        giftExpiryWatcher = new ExpiryTextWatcher(expiryInput);
        cardExpiryWatcher = new ExpiryTextWatcher(cardExpiryInput);
        ExpiryTextWatcher.link(giftExpiryWatcher, cardExpiryWatcher);
        expiryInput.addTextChangedListener(giftExpiryWatcher);
        cardExpiryInput.addTextChangedListener(cardExpiryWatcher);

        MaterialButton save = findViewById(R.id.saveButton);
        save.setOnClickListener(v -> save());

        MaterialButton delete = findViewById(R.id.deleteButton);
        if (editingCardId > 0) {
            delete.setVisibility(View.VISIBLE);
            delete.setOnClickListener(v -> confirmDelete());
        }

        loadCardTypes();
    }

    private void loadCardTypes() {
        AppExecutors.io(() -> {
            Catalog catalog = catalogRepo.loadCatalog();
            String lang = Locale.getDefault().getLanguage();
            String tag = ("he".equals(lang) || "iw".equals(lang)) ? "he" : "en";

            List<CardTypeAdapter.Option> options = new ArrayList<>();
            cardTypes.clear();
            for (CardTypeDef def : catalog.cardTypesOrEmpty()) {
                cardTypes.add(def);
                options.add(new CardTypeAdapter.Option(def, def.displayName(tag)));
            }

            CardEntity existing = editingCardId > 0 ? cardsRepo.cards().getById(editingCardId) : null;

            AppExecutors.main(() -> {
                CardTypeAdapter adapter = new CardTypeAdapter(this, options);
                cardTypeInput.setAdapter(adapter);

                // The list is filtered, so position indexes the filtered view, not the
                // catalog — read the option back off the adapter rather than by index.
                cardTypeInput.setOnItemClickListener((parent, view, position, id) -> {
                    // Read the choice before touching the adapter — position indexes the
                    // filtered view, and restoreAll() replaces it.
                    CardTypeAdapter.Option picked = adapter.getItem(position);
                    if (picked != null) {
                        selectedType = picked.def;
                        cardTypeLayout.setError(null);
                    }
                    adapter.restoreAll();
                });

                // No click or touch listener here on purpose. Material's exposed-dropdown
                // sets its own touch handling to toggle the menu, and overriding it was
                // what caused the double-open: its toggle and ours both fired. Showing the
                // full list on reopen is handled inside the adapter's filter instead.

                if (existing != null) {
                    editing = existing;
                    populateFrom(existing, options);
                }
            });
        });
    }

    private void populateFrom(CardEntity card, List<CardTypeAdapter.Option> options) {
        for (CardTypeAdapter.Option option : options) {
            if (option.def.id.equals(card.cardTypeId)) {
                selectedType = option.def;
                cardTypeInput.setText(option.label, false);
                break;
            }
        }
        labelInput.setText(card.label);
        amountInput.setText(String.valueOf(card.initialAmount));
        notesInput.setText(card.notes);

        String expiry = Formats.expiryToDisplay(card.expiryDate);
        if (!expiry.isEmpty()) {
            // A saved value is the user's own, so mirroring must not overwrite it later.
            giftExpiryWatcher.setSilently(expiry);
            giftExpiryWatcher.markEditedByUser();
        }

        // The gift link uses the non-auth key so the background balance check can read it,
        // which also means it can be shown here without a prompt.
        try {
            giftUrlInput.setText(cardsRepo.vault().decryptData(card.encGiftUrl));
        } catch (Exception ignored) {
            // Leave blank; the stored value is untouched unless the user types a new one.
        }

        // PAN and CVV stay hidden until the user authenticates on the detail screen.
        if (card.hasSensitiveData()) {
            panInput.setHint(getString(R.string.card_number));
            panInput.setText("");
        }
    }

    /**
     * Turns a pasted link into a filled-in form.
     *
     * <p>The button is dead until there is something link-shaped in the field, so it never
     * offers to do work it cannot do.
     */
    private void wireFillFromLink() {
        MaterialButton fill = findViewById(R.id.fillFromLink);
        fill.setEnabled(looksLikeALink(text(giftUrlInput)));
        giftUrlInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                fill.setEnabled(looksLikeALink(s == null ? "" : s.toString()));
                giftUrlLayout.setError(null);
            }
        });
        fill.setOnClickListener(v -> fillFromLink(fill));
    }

    /**
     * Loose on purpose: a host with a dot in it is enough.
     *
     * <p>Anything stricter rejects the links people actually paste — copied without a
     * scheme, or with a stray character on the end — and the fetch is a better judge of
     * whether a link works than a pattern is.
     */
    private static boolean looksLikeALink(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > 3 && trimmed.contains(".") && !trimmed.contains(" ");
    }

    /**
     * Reads the issuer's page and fills in whatever it states.
     *
     * <p>Only empty fields are written. Someone who typed an amount and then pasted a link
     * meant the amount they typed, and a page that disagrees is more likely to be showing
     * the face value than the balance. The same rule makes the button safe to press twice.
     *
     * <p>Nothing is saved here — the fields are filled and the user still presses Save. That
     * is deliberate: this is scraping an undocumented page, and every value it produces
     * should be looked at by somebody before it becomes a card.
     */
    private void fillFromLink(MaterialButton fill) {
        String url = text(giftUrlInput).trim();
        if (!looksLikeALink(url)) {
            giftUrlLayout.setError(getString(R.string.gift_link_invalid));
            return;
        }
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            // "buyme.co.il/..." is a reasonable thing to paste; nothing will fetch it.
            url = "https://" + url;
            giftUrlInput.setText(url);
        }

        giftUrlLayout.setError(null);
        fill.setEnabled(false);
        fill.setText(R.string.gift_link_fetching);

        String target = url;
        AppExecutors.io(() -> {
            GiftPageDetails details;
            try {
                details = GiftPageReader.read(target, new SourceEnv(
                        Http.client(), new AndroidAssetLoader(this),
                        RemoteConfig.CATALOG_BASE_URL));
            } catch (Exception e) {
                Log.w(TAG, "could not read the gift page", e);
                AppExecutors.main(() -> {
                    fill.setEnabled(true);
                    fill.setText(R.string.gift_link_fill);
                    giftUrlLayout.setError(getString(R.string.gift_link_unreachable));
                });
                return;
            }
            AppExecutors.main(() -> {
                fill.setEnabled(true);
                fill.setText(R.string.gift_link_fill);
                applyDetails(details);
            });
        });
    }

    /** Writes the fields the page gave up, leaving anything already typed alone. */
    private void applyDetails(GiftPageDetails details) {
        int filled = 0;
        if (details.amount != null && text(amountInput).isEmpty()) {
            amountInput.setText(Formats.plainAmount(details.amount));
            filled++;
        }
        if (details.expiryMonth != null && text(expiryInput).isEmpty()) {
            expiryInput.setText(Formats.expiryToDisplay(details.expiryMonth));
            filled++;
        }
        if (details.pan != null && text(panInput).isEmpty()) {
            panInput.setText(details.pan);
            filled++;
        }
        if (details.cvv != null && text(cvvInput).isEmpty()) {
            cvvInput.setText(details.cvv);
            filled++;
        }

        if (filled == 0) {
            // Either the page said nothing readable, or it said only things already typed.
            // Both mean the same thing to the user: there is nothing more to be had here.
            Toast.makeText(this, R.string.gift_link_nothing_found, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getResources().getQuantityString(
                R.plurals.gift_link_filled, filled, filled), Toast.LENGTH_LONG).show();
    }

    private void save() {
        if (selectedType == null) {
            cardTypeLayout.setError(getString(R.string.error_pick_card_type));
            return;
        }

        String expiryDisplay = text(expiryInput);
        if (!Formats.isValidExpiryDisplay(expiryDisplay)) {
            expiryLayout.setError(getString(R.string.error_invalid_expiry));
            return;
        }
        expiryLayout.setError(null);
        String expiryStored = Formats.displayToStored(expiryDisplay);

        String amountText = text(amountInput);
        if (TextUtils.isEmpty(amountText)) {
            amountLayout.setError(getString(R.string.error_amount_required));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(amountText);
        } catch (NumberFormatException e) {
            amountLayout.setError(getString(R.string.error_amount_required));
            return;
        }
        amountLayout.setError(null);

        String pan = text(panInput);
        String cvv = text(cvvInput);
        String cardExpiry = text(cardExpiryInput);

        // Before anything is written, and before the fingerprint prompt: a card that turns
        // out to be a duplicate should not have cost an unlock first.
        String giftUrl = text(giftUrlInput);
        AppExecutors.io(() -> {
            List<CardEntity> alreadyHave = cardsRepo.cardsSharingGiftLink(
                    giftUrl, editing == null ? 0L : editing.id);
            AppExecutors.main(() -> {
                if (alreadyHave.isEmpty()) {
                    authenticateThenPersist(amount, expiryStored, pan, cvv, cardExpiry);
                } else {
                    warnDuplicateLink(alreadyHave.get(0), () ->
                            authenticateThenPersist(amount, expiryStored, pan, cvv, cardExpiry));
                }
            });
        });
    }

    /**
     * Says that this link is already in the wallet, and lets the user go ahead anyway.
     *
     * <p>A warning rather than a refusal. The check compares a hash of the normalised link,
     * which is a good answer and not a certain one — and being unable to add a card you
     * genuinely hold is a worse failure than holding it twice. What the user needs is to
     * know, and to be told which card it clashes with, which is why the existing card is
     * named rather than merely counted.
     */
    private void warnDuplicateLink(CardEntity existing, Runnable proceed) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.duplicate_link_title)
                .setMessage(getString(R.string.duplicate_link_message, describe(existing)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.duplicate_link_add_anyway,
                        (dialog, which) -> proceed.run())
                .show();
    }

    /** The card's own label, or its type's name when it has none. */
    private String describe(CardEntity card) {
        if (card.label != null && !card.label.trim().isEmpty()) {
            return card.label;
        }
        String lang = Locale.getDefault().getLanguage();
        String tag = ("he".equals(lang) || "iw".equals(lang)) ? "he" : "en";
        for (CardTypeDef def : cardTypes) {
            if (def.id.equals(card.cardTypeId)) {
                return def.displayName(tag);
            }
        }
        return card.cardTypeId;
    }

    private void authenticateThenPersist(double amount, String expiryStored,
                                         String pan, String cvv, String cardExpiry) {
        // Writing to the auth-bound key needs a recent unlock just as reading does.
        boolean needsAuth = !TextUtils.isEmpty(pan) || !TextUtils.isEmpty(cvv)
                || !TextUtils.isEmpty(cardExpiry);

        if (needsAuth && cardsRepo.vault().isBiometricProtectionAvailable()) {
            BiometricGate.authenticate(this, getString(R.string.biometric_title),
                    getString(R.string.optional_payment_explain),
                    new BiometricGate.Callback() {
                        @Override
                        public void onSuccess() {
                            persist(amount, expiryStored, pan, cvv, cardExpiry);
                        }

                        @Override
                        public void onFailure() {
                            Toast.makeText(AddEditCardActivity.this,
                                    R.string.biometric_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            persist(amount, expiryStored, pan, cvv, cardExpiry);
        }
    }

    private void persist(double amount, String expiryStored,
                         String pan, String cvv, String cardExpiry) {
        AppExecutors.io(() -> {
            try {
                CardEntity card = editing != null ? editing : new CardEntity();
                card.cardTypeId = selectedType.id;
                card.label = text(labelInput);
                card.initialAmount = amount;
                card.expiryDate = expiryStored;
                card.notes = text(notesInput);
                card.updatedAt = System.currentTimeMillis();

                SecretVault vault = cardsRepo.vault();
                if (!TextUtils.isEmpty(pan)) {
                    card.encPan = vault.encryptSecret(pan);
                }
                if (!TextUtils.isEmpty(cvv)) {
                    card.encCvv = vault.encryptSecret(cvv);
                }
                if (!TextUtils.isEmpty(cardExpiry)) {
                    card.encCardExpiry = vault.encryptSecret(cardExpiry);
                }

                String giftUrl = text(giftUrlInput);
                card.encGiftUrl = TextUtils.isEmpty(giftUrl) ? null : vault.encryptData(giftUrl);
                // Written beside the encrypted link, because the ciphertext cannot be
                // compared: a random IV means the same URL enciphers differently each time.
                // Set on both branches so clearing a link clears its fingerprint too.
                card.giftUrlFingerprint = GiftLink.fingerprint(giftUrl);

                if (editing != null) {
                    cardsRepo.cards().update(card);
                } else {
                    card.createdAt = System.currentTimeMillis();
                    cardsRepo.cards().insert(card);
                }

                AppExecutors.main(this::finish);
            } catch (Exception e) {
                AppExecutors.main(() -> Toast.makeText(this,
                        getString(R.string.biometric_failed), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.delete_card_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> AppExecutors.io(() -> {
                    if (editing != null) {
                        cardsRepo.cards().delete(editing);
                    }
                    AppExecutors.main(this::finish);
                }))
                .show();
    }

    private static String text(TextInputEditText input) {
        CharSequence cs = input.getText();
        return cs == null ? "" : cs.toString().trim();
    }
}
