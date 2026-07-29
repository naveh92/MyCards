package com.mycards.data.source;

import com.mycards.data.RemoteConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Shared OkHttp client configured for the quirks of the sites this app reads. */
public final class Http {

    private Http() {
    }

    private static volatile OkHttpClient shared;

    public static OkHttpClient client() {
        if (shared == null) {
            synchronized (Http.class) {
                if (shared == null) {
                    shared = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(45, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .addInterceptor(new BrowserHeadersInterceptor())
                            .build();
                }
            }
        }
        return shared;
    }

    /**
     * Cloudflare fronts buyme.co.il and rejects a default OkHttp User-Agent outright with a
     * 403. Presenting a normal mobile-Chrome agent is enough to be served; no cookie jar or
     * JS challenge solving is involved.
     */
    private static final class BrowserHeadersInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request original = chain.request();
            Request.Builder b = original.newBuilder();
            if (original.header("User-Agent") == null) {
                b.header("User-Agent", RemoteConfig.USER_AGENT);
            }
            if (original.header("Accept") == null) {
                b.header("Accept", "application/json, text/plain, */*");
            }
            if (original.header("Accept-Language") == null) {
                b.header("Accept-Language", "he-IL,he;q=0.9,en;q=0.8");
            }
            return chain.proceed(b.build());
        }
    }
}
