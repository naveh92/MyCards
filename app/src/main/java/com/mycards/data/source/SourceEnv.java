package com.mycards.data.source;

import okhttp3.OkHttpClient;

/** The I/O capabilities a provider is allowed to use. */
public final class SourceEnv {

    private final OkHttpClient http;
    private final AssetLoader assets;
    private final String catalogBaseUrl;

    public SourceEnv(OkHttpClient http, AssetLoader assets, String catalogBaseUrl) {
        this.http = http;
        this.assets = assets;
        this.catalogBaseUrl = catalogBaseUrl;
    }

    public OkHttpClient http() {
        return http;
    }

    public AssetLoader assets() {
        return assets;
    }

    public String catalogBaseUrl() {
        return catalogBaseUrl;
    }
}
