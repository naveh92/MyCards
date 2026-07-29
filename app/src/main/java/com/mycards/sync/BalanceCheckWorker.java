package com.mycards.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mycards.data.AndroidAssetLoader;
import com.mycards.data.CardsRepository;
import com.mycards.data.CatalogRepository;
import com.mycards.data.RemoteConfig;
import com.mycards.data.catalog.model.Catalog;
import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.crypto.SecretVault;
import com.mycards.data.db.AppDatabase;
import com.mycards.data.db.CardEntity;
import com.mycards.data.source.BalanceFetcher;
import com.mycards.data.source.Http;
import com.mycards.data.source.SourceEnv;
import com.mycards.notify.Notifications;

import java.util.List;

/**
 * Compares each card's issuer-reported balance against what the spend log accounts for, and
 * flags the difference as a probably-unlogged purchase.
 *
 * <p>Only runs for cards that carry a public gift link. Where no balance can be read the
 * card is left completely untouched — no flag, no notification — because a false alert would
 * push the user to record a purchase that never happened.
 */
public class BalanceCheckWorker extends Worker {

    private static final String TAG = "BalanceCheckWorker";

    /** Ignore sub-agora noise from rounding on the issuer's page. */
    private static final double MISMATCH_THRESHOLD = 0.5d;

    public BalanceCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            CardsRepository cards = new CardsRepository(ctx);
            CatalogRepository catalogRepo = new CatalogRepository(ctx);
            Catalog catalog = catalogRepo.loadCatalog();

            SecretVault vault = cards.vault();
            BalanceFetcher fetcher = new BalanceFetcher();
            SourceEnv env = new SourceEnv(
                    Http.client(),
                    new AndroidAssetLoader(ctx),
                    RemoteConfig.CATALOG_BASE_URL);

            List<CardEntity> candidates =
                    AppDatabase.get(ctx).cardDao().getCardsWithGiftUrl();

            for (CardEntity card : candidates) {
                CardTypeDef def = catalog.findById(card.cardTypeId);
                if (def == null || def.balanceSourcesOrEmpty().isEmpty()) {
                    continue;
                }

                String giftUrl;
                try {
                    // Gift links use the non-auth key precisely so this unattended check is
                    // possible; card numbers and CVVs stay behind the biometric-bound key.
                    giftUrl = vault.decryptData(card.encGiftUrl);
                } catch (Exception e) {
                    Log.w(TAG, "could not decrypt the gift link for card " + card.id, e);
                    continue;
                }

                Double reported = fetcher.fetch(def, giftUrl, env);
                if (reported == null) {
                    continue;
                }

                double expected = card.initialAmount
                        - AppDatabase.get(ctx).spendDao().getTotalSpent(card.id);
                boolean mismatch = expected - reported > MISMATCH_THRESHOLD;

                AppDatabase.get(ctx).cardDao().recordBalanceCheck(
                        card.id, System.currentTimeMillis(), reported, mismatch);

                if (mismatch) {
                    String label = card.label != null && !card.label.trim().isEmpty()
                            ? card.label
                            : def.displayName("en");
                    Notifications.showBalanceMismatch(ctx, card.id, label);
                    Log.i(TAG, "card " + card.id + " expected " + expected
                            + " but the issuer reports " + reported);
                }
            }
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "balance check failed", e);
            return Result.retry();
        }
    }
}
