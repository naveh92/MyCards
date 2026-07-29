package com.mycards.data.source.providers;

import com.google.gson.JsonElement;
import com.mycards.data.catalog.model.SourceDef;
import com.mycards.data.source.EmbeddedJson;
import com.mycards.data.source.JsonExtract;
import com.mycards.data.source.SourceEnv;
import com.mycards.data.source.StoreSourceProvider;
import com.mycards.search.Store;

import java.io.IOException;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reads a merchant list that a site embeds in its own HTML rather than serving from an API.
 *
 * <p>This exists to widen what can be repaired without shipping an app update. HTZone puts
 * every merchant in a {@code business_arr} literal in the page; if it renames that variable
 * or moves the fields around, the fix is editing {@code varName} and the paths in the
 * catalog, not writing a new provider and waiting for a release.
 */
public class EmbeddedJsonProvider implements StoreSourceProvider {

    public static final String TYPE = "embedded_json";

    /** Guards against a redirect to something enormous being parsed as a page. */
    private static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Store> fetchStores(SourceDef def, SourceEnv env) throws Exception {
        String url = def.resolveUrl(env.catalogBaseUrl());
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("embedded_json source is missing a url");
        }
        if (def.varName == null || def.varName.trim().isEmpty()) {
            throw new IllegalArgumentException("embedded_json source is missing varName");
        }
        if (def.namePath == null || def.namePath.trim().isEmpty()) {
            throw new IllegalArgumentException("embedded_json source is missing namePath");
        }

        Request request = new Request.Builder()
                .url(url.trim())
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .get()
                .build();

        String html;
        try (Response response = env.http().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("embedded_json HTTP " + response.code() + " for " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("embedded_json returned an empty body");
            }
            if (body.contentLength() > MAX_PAGE_BYTES) {
                throw new IOException("embedded_json page is implausibly large");
            }
            html = body.string();
        }

        JsonElement root = EmbeddedJson.extract(html, def.varName.trim());
        if (root == null) {
            // The variable moved or the page shape changed; let the chain fall through
            // rather than reporting an empty merchant list as success.
            throw new IOException("embedded_json: no '" + def.varName + "' found in " + url);
        }

        return JsonExtract.toStores(root, def);
    }
}
