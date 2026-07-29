package com.mycards.data.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mycards.data.catalog.model.SourceDef;
import com.mycards.search.Store;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an arbitrary JSON tree into merchants using dotted paths from the catalog.
 *
 * <p>Shared by every config-driven provider so that adapting to a new feed shape is a
 * catalog edit rather than a code change. Adding a provider that fetches JSON from a new
 * place means writing only the fetching part.
 */
public final class JsonExtract {

    private JsonExtract() {
    }

    public static List<Store> toStores(JsonElement root, SourceDef def) {
        List<Store> stores = new ArrayList<>();

        JsonElement items = resolvePath(root, def.itemsPath);
        if (items == null || !items.isJsonArray()) {
            return stores;
        }

        for (JsonElement item : items.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            String name = asStringOrNull(resolvePath(item, def.namePath));
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            stores.add(new Store(
                    name.trim(),
                    readAliases(item, def.aliasesPath, name.trim()),
                    readBoolean(resolvePath(item, def.onlinePath))));
        }
        return stores;
    }

    /** Aliases may be a real array or, as BuyMe does, a string containing a JSON array. */
    public static List<String> readAliases(JsonElement item, String path, String name) {
        List<String> out = new ArrayList<>();
        if (path == null || path.trim().isEmpty()) {
            return out;
        }
        JsonElement el = resolvePath(item, path);
        if (el == null || el.isJsonNull()) {
            return out;
        }
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    String s = e.getAsString().trim();
                    if (!s.isEmpty() && !s.startsWith("#")) {
                        out.add(s);
                    }
                }
            }
            return out;
        }
        if (el.isJsonPrimitive()) {
            return StoreListJson.parseEmbeddedAliasArray(el.getAsString(), name);
        }
        return out;
    }

    /** Walks a dotted path such as {@code data.brands}; a null/blank path returns the root. */
    public static JsonElement resolvePath(JsonElement root, String path) {
        if (root == null) {
            return null;
        }
        if (path == null || path.trim().isEmpty()) {
            return root;
        }
        JsonElement current = root;
        for (String segment : path.trim().split("\\.")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (current instanceof JsonObject) {
                current = ((JsonObject) current).get(segment);
            } else if (current instanceof JsonArray) {
                try {
                    current = ((JsonArray) current).get(Integer.parseInt(segment));
                } catch (RuntimeException notAnIndex) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public static String asStringOrNull(JsonElement el) {
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    /**
     * Reads a flag that issuers encode inconsistently as {@code true}, {@code 1} or
     * {@code "1"}.
     *
     * <p>Each case is handled explicitly rather than leaning on {@code getAsBoolean()}:
     * Gson resolves the <em>string</em> "1" to {@code false} without raising anything, so a
     * try/catch around it silently loses every online-redemption flag.
     */
    public static boolean readBoolean(JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return false;
        }
        com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            return p.getAsInt() != 0;
        }
        String s = p.getAsString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }
}
