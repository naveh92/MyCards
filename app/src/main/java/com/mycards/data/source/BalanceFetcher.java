package com.mycards.data.source;

import android.util.Log;

import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.catalog.model.SourceDef;
import com.mycards.data.source.providers.GiftPageBalanceProvider;

import java.util.HashMap;
import java.util.Map;

/** Runs a card type's balance-source chain, stopping at the first confident answer. */
public final class BalanceFetcher {

    private static final String TAG = "BalanceFetcher";

    private final Map<String, BalanceSourceProvider> providers = new HashMap<>();

    public BalanceFetcher() {
        register(new GiftPageBalanceProvider());
    }

    public void register(BalanceSourceProvider provider) {
        providers.put(provider.type(), provider);
    }

    /**
     * @return the balance, or null when no source could determine one — which is the normal
     *         outcome for most issuers and must not be treated as an error
     */
    public Double fetch(CardTypeDef cardType, String giftUrl, SourceEnv env) {
        for (SourceDef source : cardType.balanceSourcesOrEmpty()) {
            if (source == null || !source.hasType()) {
                continue;
            }
            BalanceSourceProvider provider = providers.get(source.type);
            if (provider == null) {
                continue;
            }
            try {
                Double balance = provider.fetchBalance(source, giftUrl, env);
                if (balance != null) {
                    return balance;
                }
            } catch (Exception e) {
                Log.w(TAG, "balance source " + source.type + " failed for " + cardType.id, e);
            }
        }
        return null;
    }
}
