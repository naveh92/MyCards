package com.mycards.data.source.providers;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mycards.data.catalog.model.SourceDef;
import com.mycards.data.source.JsonExtract;
import com.mycards.data.source.SourceEnv;
import com.mycards.data.source.StoreSourceProvider;
import com.mycards.search.Store;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reads an arbitrary JSON feed using dotted paths supplied by the catalog.
 *
 * <p>The point of this provider is that a newly discovered issuer endpoint can be wired up
 * by editing the catalog alone — no new provider class, no app release. It loads the whole
 * document into a tree, so it is meant for modest feeds; bulk payloads should get a
 * dedicated streaming provider instead.
 */
public class GenericJsonProvider implements StoreSourceProvider {

    public static final String TYPE = "generic_json";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Store> fetchStores(SourceDef def, SourceEnv env) throws Exception {
        String url = def.resolveUrl(env.catalogBaseUrl());
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("generic_json source is missing a url");
        }
        if (def.namePath == null || def.namePath.trim().isEmpty()) {
            throw new IllegalArgumentException("generic_json source is missing namePath");
        }

        Request request = new Request.Builder().url(url.trim()).get().build();
        try (Response response = env.http().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("generic_json HTTP " + response.code() + " for " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("generic_json returned an empty body");
            }

            JsonElement root = JsonParser.parseReader(
                    new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));

            List<Store> stores = JsonExtract.toStores(root, def);
            if (stores.isEmpty()) {
                // Distinguishes "the paths no longer match" from "the card covers nothing",
                // so the chain falls through instead of caching an empty list.
                throw new IOException("generic_json: no items at itemsPath=" + def.itemsPath);
            }
            return stores;
        }
    }
}
