package com.mycards.data.source;

import android.util.Log;

import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.data.catalog.model.SourceDef;
import com.mycards.search.Store;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a card type's ordered fallback chain and returns the first source that yields data.
 *
 * <p>The chain exists because every source here is fragile in a different way: the BuyMe
 * endpoint is undocumented, hosted lists depend on a repo staying up, and the bundled
 * snapshot ages. Trying them in order means one broken link degrades the data's freshness
 * rather than emptying the screen.
 */
public final class StoreFetcher {

    private static final String TAG = "StoreFetcher";

    private final ProviderRegistry registry;

    public StoreFetcher(ProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * A result smaller than this fraction of the expected size is treated as damaged and
     * the chain keeps looking. Deliberately generous — merchant lists really do shrink,
     * and rejecting a genuine change would be worse than accepting a small one.
     */
    private static final double PLAUSIBLE_FRACTION = 0.5d;

    public FetchOutcome fetch(CardTypeDef cardType, SourceEnv env) {
        return fetch(cardType, env, 0);
    }

    /**
     * @param expectedCount roughly how many merchants this card should have (from the
     *                      manifest or the existing cache); 0 when unknown
     */
    public FetchOutcome fetch(CardTypeDef cardType, SourceEnv env, int expectedCount) {
        List<String> failures = new ArrayList<>();

        // Best result that parsed but looked too small. Held in reserve rather than
        // discarded: if every source agrees the list shrank, then it genuinely shrank,
        // and refusing them all would freeze this card on stale data forever.
        List<Store> suspect = null;
        String suspectSource = null;

        for (SourceDef source : cardType.storeSourcesOrEmpty()) {
            if (source == null || !source.hasType()) {
                failures.add("skipped a source with no type");
                continue;
            }

            StoreSourceProvider provider = registry.find(source.type);
            if (provider == null) {
                // Published by a newer catalog than this build understands — not an error.
                failures.add(source.type + ": unsupported by this app version");
                continue;
            }

            try {
                List<Store> stores = provider.fetchStores(source, env);
                if (stores == null || stores.isEmpty()) {
                    // A source that responds but yields nothing almost always means the
                    // format moved, not that the card genuinely covers no shops.
                    failures.add(source.type + ": returned no stores");
                    continue;
                }

                // Parsing cleanly is not the same as being correct. A half-written
                // publish or a truncated download can produce a valid-looking file with a
                // handful of merchants, which would otherwise overwrite good cached data.
                if (expectedCount > 0 && stores.size() < expectedCount * PLAUSIBLE_FRACTION) {
                    failures.add(source.type + ": only " + stores.size()
                            + " stores, expected around " + expectedCount);
                    if (suspect == null || stores.size() > suspect.size()) {
                        suspect = stores;
                        suspectSource = source.type;
                    }
                    continue;
                }

                Log.i(TAG, "card=" + cardType.id + " source=" + source.type
                        + " stores=" + stores.size());
                return FetchOutcome.success(stores, source.type, failures);
            } catch (Exception e) {
                failures.add(source.type + ": " + e.getClass().getSimpleName()
                        + " " + String.valueOf(e.getMessage()));
                Log.w(TAG, "source " + source.type + " failed for " + cardType.id, e);
            }
        }

        if (suspect != null) {
            // Every source that worked looked small, so the shrink is probably real.
            Log.w(TAG, "accepting a smaller-than-expected list for " + cardType.id
                    + " from " + suspectSource + " (" + suspect.size() + " stores)");
            failures.add("accepted " + suspectSource + " despite its size");
            return FetchOutcome.success(suspect, suspectSource, failures);
        }

        Log.w(TAG, "all sources failed for " + cardType.id + " -> " + failures);
        return FetchOutcome.failure(failures);
    }
}
