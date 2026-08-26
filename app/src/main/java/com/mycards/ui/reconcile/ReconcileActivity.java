package com.mycards.ui.reconcile;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.mycards.R;
import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.db.CardEntity;
import com.mycards.data.db.SpendEntity;
import com.mycards.search.StoreNameIndex;
import com.mycards.ui.AppExecutors;
import com.mycards.ui.Formats;
import com.mycards.ui.detail.AddSpendDialog;

import java.util.Locale;

/**
 * Shown when the issuer reports less money than the spend log accounts for.
 *
 * <p>The gap is presented as a suggestion, never applied automatically: the app knows an
 * amount is missing but has no idea what it was spent on, and only the user can supply the
 * description that makes the entry worth having.
 */
public class ReconcileActivity extends AppCompatActivity {

    public static final String EXTRA_CARD_ID = "card_id";

    private CardsRepository cardsRepo;
    private long cardId;
    private CardEntity card;
    private double difference;
    private StoreNameIndex storeSuggestions = StoreNameIndex.empty();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reconcile);
        com.mycards.ui.EdgeToEdge.apply(this);

        cardsRepo = new CardsRepository(this);
        cardId = getIntent().getLongExtra(EXTRA_CARD_ID, 0L);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        findViewById(R.id.addMissingButton).setOnClickListener(v -> addMissingSpend());
        findViewById(R.id.dismissButton).setOnClickListener(v -> dismiss());

        load();
    }

    private void load() {
        AppExecutors.io(() -> {
            card = cardsRepo.cards().getById(cardId);
            if (card == null) {
                AppExecutors.main(this::finish);
                return;
            }
            double expected = cardsRepo.remainingBalance(cardId);
            double actual = card.lastFetchedBalance == null ? expected : card.lastFetchedBalance;
            difference = Math.max(0d, expected - actual);

            CatalogRepository catalogRepo = new CatalogRepository(this);
            Catalog catalog = catalogRepo.loadCatalog();
            CardTypeDef def = catalog.findById(card.cardTypeId);
            String lang = Locale.getDefault().getLanguage();
            String tag = ("he".equals(lang) || "iw".equals(lang)) ? "he" : "en";
            String name = card.label != null && !card.label.trim().isEmpty()
                    ? card.label
                    : (def != null ? def.displayName(tag) : card.cardTypeId);

            // Read here, on the thread that is already doing the reading, so the shop
            // names are ready by the time the purchase dialog opens.
            StoreNameIndex names = StoreNameIndex.of(
                    catalogRepo.loadStoreNames(card.cardTypeId));

            AppExecutors.main(() -> {
                storeSuggestions = names;
                ((TextView) findViewById(R.id.cardName)).setText(name);
                ((TextView) findViewById(R.id.expectedValue))
                        .setText(Formats.money(expected, card.currency));
                ((TextView) findViewById(R.id.actualValue))
                        .setText(Formats.money(actual, card.currency));
                ((TextView) findViewById(R.id.differenceValue))
                        .setText(Formats.money(difference, card.currency));
            });
        });
    }

    private void addMissingSpend() {
        // The gap can never exceed what the log says is left, so the balance is the cap.
        AddSpendDialog.show(this, card.currency, cardsRepo.remainingBalance(cardId),
                null, difference, storeSuggestions,
                (title, amount, storeName, spentAt) -> AppExecutors.io(() -> {
                    // Recorded as RECONCILIATION so the log stays honest about which entries
                    // were observed and which were inferred from a balance gap.
                    cardsRepo.addSpend(cardId, title, amount, storeName, spentAt,
                            SpendEntity.SOURCE_RECONCILIATION);
                    AppExecutors.main(this::finish);
                }));
    }

    private void dismiss() {
        AppExecutors.io(() -> {
            cardsRepo.cards().clearMismatch(cardId);
            AppExecutors.main(this::finish);
        });
    }
}
