package com.mycards.ui.edit;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mycards.R;
import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.crypto.SecretVault;
import com.mycards.data.db.CardEntity;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.BiometricGate;
import com.mycards.ui.ExpiryTextWatcher;
import com.mycards.ui.Formats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Adds or edits a card, including the optional encrypted payment details. */
public class AddEditCardActivity extends AppCompatActivity {

    public static final String EXTRA_CARD_ID = "card_id";

    private CardsRepository cardsRepo;
    private CatalogRepository catalogRepo;

    private MaterialAutoCompleteTextView cardTypeInput;
    private TextInputLayout cardTypeLayout;
    private TextInputLayout amountLayout;
    private TextInputLayout expiryLayout;
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
        labelInput = findViewById(R.id.labelInput);
        amountInput = findViewById(R.id.amountInput);
        expiryInput = findViewById(R.id.expiryInput);
        panInput = findViewById(R.id.panInput);
        cvvInput = findViewById(R.id.cvvInput);
        cardExpiryInput = findViewById(R.id.cardExpiryInput);
        giftUrlInput = findViewById(R.id.giftUrlInput);
        notesInput = findViewById(R.id.notesInput);

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
                    CardTypeAdapter.Option picked = adapter.getItem(position);
                    if (picked != null) {
                        selectedType = picked.def;
                        cardTypeLayout.setError(null);
                    }
                });

                // Tapping the field should offer the whole list, not filter by whatever
                // name is already sitting in it.
                cardTypeInput.setOnClickListener(v -> {
                    selectedType = null;
                    // The 'false' suppresses filtering, which stops the adapter from
                    // immediately re-narrowing to the name already in the field...
                    cardTypeInput.setText("", false);
                    // ...but it also leaves the adapter holding whatever it was last
                    // filtered to, so the field would look empty while the list still
                    // showed only the previous matches. Reset it explicitly.
                    adapter.getFilter().filter(null, count ->
                            // Posted rather than called inline: opening the dropdown while
                            // the tap is still being handled gets it dismissed again by the
                            // framework's own toggle.
                            cardTypeInput.post(cardTypeInput::showDropDown));
                });

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
        new AlertDialog.Builder(this)
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
