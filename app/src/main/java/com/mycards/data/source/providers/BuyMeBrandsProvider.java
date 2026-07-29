package com.mycards.data.source.providers;

import com.mycards.data.catalog.model.SourceDef;
import com.mycards.data.source.SourceEnv;
import com.mycards.data.source.StoreListJson;
import com.mycards.data.source.StoreSourceProvider;
import com.mycards.search.Store;

import java.io.IOException;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reads BuyMe's undocumented {@code /siteapi/brands/{supplierId}} endpoint, which returns
 * a card variant's entire merchant list in one request.
 *
 * <p>This is scraping, not a supported integration: the endpoint is undocumented and could
 * change shape or tighten its bot checks at any time. That is precisely why it sits at the
 * head of a fallback chain rather than being the only way in.
 */
public class BuyMeBrandsProvider implements StoreSourceProvider {

    public static final String TYPE = "buyme_brands";

    private static final String ENDPOINT = "https://buyme.co.il/siteapi/brands/";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Store> fetchStores(SourceDef def, SourceEnv env) throws Exception {
        if (def.supplierId == null || def.supplierId.trim().isEmpty()) {
            throw new IllegalArgumentException("buyme_brands source is missing supplierId");
        }

        Request request = new Request.Builder()
                .url(ENDPOINT + def.supplierId.trim())
                // The site's own pages send this; matching it keeps the request unremarkable.
                .header("Referer", "https://buyme.co.il/brands/" + def.supplierId.trim())
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build();

        try (Response response = env.http().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("buyme_brands HTTP " + response.code()
                        + " for supplier " + def.supplierId);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("buyme_brands returned an empty body");
            }
            // Streamed straight from the socket — the ALL payload is ~5.8 MB.
            return StoreListJson.parseBuyMeBrands(body.byteStream());
        }
    }
}
