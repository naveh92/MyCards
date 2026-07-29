package com.mycards.data;

/**
 * Where the app looks for catalog updates.
 *
 * <p>Point {@link #CATALOG_BASE_URL} at any static host you control — a GitHub repo served
 * over raw.githubusercontent.com costs nothing and needs no server. Editing the catalog
 * there adds card types or repairs a broken endpoint without shipping a new APK.
 *
 * <p>Until it is changed, every remote fetch simply fails and the app falls through to the
 * snapshots bundled in {@code assets/}, which is a fully working offline state.
 */
public final class RemoteConfig {

    private RemoteConfig() {
    }

    /**
     * Where the published lists live. Point this at the GitHub Pages site produced by
     * .github/workflows/refresh-store-lists.yml, e.g.
     * {@code https://your-user.github.io/MyCards}.
     *
     * <p>The app only ever issues anonymous GETs against it. There is deliberately no code
     * path anywhere in the app that can write here — publishing happens in CI, so no
     * credential is shipped inside the APK where it could be decompiled out.
     */
    public static final String CATALOG_BASE_URL =
            "https://naveh92.github.io/MyCards";

    /** Catalog document listing every known card type and how to fetch its merchants. */
    public static final String CATALOG_URL = CATALOG_BASE_URL + "/catalog.json";

    /** Hash index consulted before downloading anything, so unchanged lists are skipped. */
    public static final String MANIFEST_URL = CATALOG_BASE_URL + "/manifest.json";

    /** Manifest schema this build understands. */
    public static final int MAX_MANIFEST_VERSION = 1;

    /** Seed copies shipped in the APK. */
    public static final String BUNDLED_CATALOG_ASSET = "catalog.json";

    /** How long merchant data stays fresh before a background refresh is triggered. */
    public static final long STALE_AFTER_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /**
     * buyme.co.il sits behind Cloudflare, which rejects requests without a plausible
     * browser User-Agent — a default OkHttp agent gets a 403. Verified: UA alone is
     * sufficient, no cookie or JS challenge is required.
     */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    /**
     * False while the URL is still a placeholder, in which case the app skips remote
     * fetches entirely and runs from its bundled snapshots.
     */
    public static boolean isCatalogUrlConfigured() {
        return !CATALOG_BASE_URL.contains("REPLACE-ME");
    }
}
