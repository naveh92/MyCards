package com.mycards.data.source;

import com.mycards.data.source.providers.BundledAssetProvider;
import com.mycards.data.source.providers.BuyMeBrandsProvider;
import com.mycards.data.source.providers.EmbeddedJsonProvider;
import com.mycards.data.source.providers.GenericJsonProvider;
import com.mycards.data.source.providers.StaticListProvider;

import java.util.HashMap;
import java.util.Map;

/** Maps the catalog's {@code type} strings to provider implementations. */
public final class ProviderRegistry {

    private final Map<String, StoreSourceProvider> providers = new HashMap<>();

    public ProviderRegistry() {
        register(new BuyMeBrandsProvider());
        register(new StaticListProvider());
        register(new BundledAssetProvider());
        register(new GenericJsonProvider());
        register(new EmbeddedJsonProvider());
    }

    public void register(StoreSourceProvider provider) {
        providers.put(provider.type(), provider);
    }

    /**
     * @return the provider for this type, or null if this build does not know it
     *
     * <p>A null result is intentionally not an error. A catalog published later may
     * reference a source type that only newer builds implement; older installs must skip it
     * and continue down the chain rather than failing the whole refresh.
     */
    public StoreSourceProvider find(String type) {
        return type == null ? null : providers.get(type);
    }
}
