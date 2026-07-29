package com.mycards.data.catalog.model;

/**
 * One entry in a card type's fallback chain, describing <em>how</em> to obtain data.
 *
 * <p>Fields are a deliberately loose union: each provider reads only the ones it needs and
 * ignores the rest. That keeps the catalog format open enough to add a new source type by
 * editing JSON rather than by changing this class.
 */
public class SourceDef {

    /** Provider key, e.g. {@code buyme_brands}, {@code static_list}, {@code bundled_asset}. */
    public String type;

    /** Absolute URL, or one containing the {@code {base}} placeholder for the catalog host. */
    public String url;

    /** Path inside the APK's assets/ folder, for {@code bundled_asset}. */
    public String asset;

    /** BuyMe supplier id identifying which card variant's merchant list to pull. */
    public String supplierId;

    // --- generic_json: lets a brand-new issuer be wired up from the catalog alone ---

    /**
     * For {@code embedded_json}: the identifier the page assigns its data to, e.g.
     * {@code business_arr}. Configurable so a renamed variable is a catalog fix, not a
     * code change.
     */
    public String varName;

    /** Dotted path to the array of merchants, e.g. {@code data.brands}. */
    public String itemsPath;

    /** Field within an item holding the display name. */
    public String namePath;

    /** Field holding aliases: a JSON array, or a string containing one. */
    public String aliasesPath;

    /** Field holding an online-redemption boolean. */
    public String onlinePath;

    public boolean hasType() {
        return type != null && !type.trim().isEmpty();
    }

    /** Substitutes the {@code {base}} placeholder with the configured catalog host. */
    public String resolveUrl(String baseUrl) {
        if (url == null) {
            return null;
        }
        return url.replace("{base}", baseUrl);
    }
}
