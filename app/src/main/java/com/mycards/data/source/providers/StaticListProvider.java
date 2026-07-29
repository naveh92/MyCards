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
 * Fetches a hand-maintained merchant list in the compact snapshot format.
 *
 * <p>This is how issuers with no machine-readable source of their own (Max, All-inZone,
 * LOVE) get covered: publish a curated list next to the catalog and the app picks it up on
 * its weekly refresh, with no APK change.
 */
public class StaticListProvider implements StoreSourceProvider {

    public static final String TYPE = "static_list";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Store> fetchStores(SourceDef def, SourceEnv env) throws Exception {
        String url = def.resolveUrl(env.catalogBaseUrl());
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("static_list source is missing a url");
        }

        Request request = new Request.Builder().url(url.trim()).get().build();
        try (Response response = env.http().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("static_list HTTP " + response.code() + " for " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("static_list returned an empty body");
            }
            return StoreListJson.parseCompactList(body.byteStream());
        }
    }
}
