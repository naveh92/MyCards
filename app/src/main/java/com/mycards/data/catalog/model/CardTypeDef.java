package com.mycards.data.catalog.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A gift-card type as described by the catalog. */
public class CardTypeDef {

    public String id;

    /** Display names keyed by language tag, currently {@code en} and {@code he}. */
    public Map<String, String> names;

    public List<String> aliases;

    public String issuer;

    /** Ordered fallback chain for the merchant list. */
    public List<SourceDef> storeSources;

    /** Ordered fallback chain for an unauthenticated balance lookup; may be empty. */
    public List<SourceDef> balanceSources;

    /**
     * @param languageTag preferred language, {@code he} or {@code en}
     * @return the name in that language, falling back to the other, then to the id
     */
    public String displayName(String languageTag) {
        if (names != null) {
            String preferred = names.get(languageTag);
            if (preferred != null && !preferred.trim().isEmpty()) {
                return preferred;
            }
            for (String any : names.values()) {
                if (any != null && !any.trim().isEmpty()) {
                    return any;
                }
            }
        }
        return id;
    }

    /** Every name and alias, so a card is findable in either language. */
    public List<String> allSearchableNames() {
        List<String> out = new ArrayList<>();
        if (names != null) {
            for (String n : names.values()) {
                if (n != null && !n.trim().isEmpty()) {
                    out.add(n);
                }
            }
        }
        if (aliases != null) {
            out.addAll(aliases);
        }
        return out;
    }

    public List<SourceDef> storeSourcesOrEmpty() {
        return storeSources == null ? new ArrayList<SourceDef>() : storeSources;
    }

    public List<SourceDef> balanceSourcesOrEmpty() {
        return balanceSources == null ? new ArrayList<SourceDef>() : balanceSources;
    }
}
