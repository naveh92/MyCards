package com.mycards.data.source.providers;

import com.mycards.data.catalog.model.SourceDef;
import com.mycards.data.source.SourceEnv;
import com.mycards.data.source.StoreListJson;
import com.mycards.data.source.StoreSourceProvider;
import com.mycards.search.Store;

import java.io.InputStream;
import java.util.List;

/**
 * Last link in the chain: the snapshot shipped inside the APK.
 *
 * <p>Guarantees the app is useful on first launch, on a plane, and on the day BuyMe changes
 * its endpoint — stale data beats an empty screen when there is a queue behind you. The UI
 * shows the snapshot's age so it is never mistaken for live data.
 */
public class BundledAssetProvider implements StoreSourceProvider {

    public static final String TYPE = "bundled_asset";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Store> fetchStores(SourceDef def, SourceEnv env) throws Exception {
        if (def.asset == null || def.asset.trim().isEmpty()) {
            throw new IllegalArgumentException("bundled_asset source is missing an asset path");
        }
        try (InputStream in = env.assets().open(def.asset.trim())) {
            return StoreListJson.parseCompactList(in);
        }
    }
}
