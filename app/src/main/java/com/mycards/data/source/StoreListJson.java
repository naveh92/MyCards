package com.mycards.data.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mycards.search.Store;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Streaming parsers for the two merchant-list formats the app consumes.
 *
 * <p>Streaming is not an optimisation here, it is a requirement: the BuyMe ALL payload is
 * ~5.8 MB, and materialising that as a Gson object tree on a low-end phone risks an OOM.
 * {@link JsonReader} lets every irrelevant field be skipped without ever being allocated.
 */
public final class StoreListJson {

    private StoreListJson() {
    }

    /**
     * Parses BuyMe's {@code /siteapi/brands/{id}} response.
     *
     * <p>Reads only {@code brands[].title}, {@code searchTerms} and {@code online_redeem};
     * logos, addresses, opening hours and the rest are skipped.
     */
    public static List<Store> parseBuyMeBrands(InputStream in) throws IOException {
        List<Store> stores = new ArrayList<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                if ("brands".equals(r.nextName())) {
                    readBuyMeBrandArray(r, stores);
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        return stores;
    }

    private static void readBuyMeBrandArray(JsonReader r, List<Store> out) throws IOException {
        r.beginArray();
        while (r.hasNext()) {
            String title = null;
            String rawTerms = null;
            boolean online = false;

            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "title":
                        title = nextStringOrNull(r);
                        break;
                    case "searchTerms":
                        rawTerms = nextStringOrNull(r);
                        break;
                    case "online_redeem":
                        online = nextBooleanLenient(r);
                        break;
                    default:
                        r.skipValue();
                }
            }
            r.endObject();

            if (title == null || title.trim().isEmpty()) {
                continue;
            }
            title = title.trim();
            out.add(new Store(title, parseEmbeddedAliasArray(rawTerms, title), online));
        }
        r.endArray();
    }

    /**
     * Parses the compact snapshot format used by bundled assets and hosted static lists:
     * {@code {"stores":[{"n":"Zara","a":["זארה"],"o":true}]}}.
     */
    public static List<Store> parseCompactList(InputStream in) throws IOException {
        List<Store> stores = new ArrayList<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                if ("stores".equals(r.nextName())) {
                    readCompactArray(r, stores);
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        return stores;
    }

    private static void readCompactArray(JsonReader r, List<Store> out) throws IOException {
        r.beginArray();
        while (r.hasNext()) {
            String name = null;
            List<String> aliases = new ArrayList<>();
            boolean online = false;

            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "n":
                        name = nextStringOrNull(r);
                        break;
                    case "a":
                        if (r.peek() == JsonToken.BEGIN_ARRAY) {
                            r.beginArray();
                            while (r.hasNext()) {
                                String a = nextStringOrNull(r);
                                if (a != null && !a.trim().isEmpty()) {
                                    aliases.add(a.trim());
                                }
                            }
                            r.endArray();
                        } else {
                            r.skipValue();
                        }
                        break;
                    case "o":
                        online = nextBooleanLenient(r);
                        break;
                    default:
                        r.skipValue();
                }
            }
            r.endObject();

            if (name != null && !name.trim().isEmpty()) {
                out.add(new Store(name.trim(), aliases, online));
            }
        }
        r.endArray();
    }

    /**
     * BuyMe double-encodes aliases: {@code searchTerms} is a JSON <em>string</em> whose
     * contents are a JSON array. A malformed value degrades to "no aliases" rather than
     * discarding the merchant, which would silently shrink the store list.
     */
    public static List<String> parseEmbeddedAliasArray(String raw, String name) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }

        Set<String> seen = new LinkedHashSet<>();
        seen.add(name.toLowerCase(Locale.ROOT));

        try {
            JsonElement el = JsonParser.parseString(raw);
            if (!el.isJsonArray()) {
                return out;
            }
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonPrimitive()) {
                    continue;
                }
                String term = e.getAsString().trim();
                // "#casualedge#" and friends are campaign tags, not names anyone searches.
                if (term.isEmpty() || term.startsWith("#")) {
                    continue;
                }
                if (seen.add(term.toLowerCase(Locale.ROOT))) {
                    out.add(term);
                }
            }
        } catch (RuntimeException malformed) {
            return out;
        }
        return out;
    }

    private static String nextStringOrNull(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) {
            r.nextNull();
            return null;
        }
        return r.nextString();
    }

    /** Accepts {@code true}, {@code 1} and {@code "true"} alike. */
    private static boolean nextBooleanLenient(JsonReader r) throws IOException {
        switch (r.peek()) {
            case BOOLEAN:
                return r.nextBoolean();
            case NUMBER:
                return r.nextInt() != 0;
            case STRING:
                return "true".equalsIgnoreCase(r.nextString());
            default:
                r.skipValue();
                return false;
        }
    }
}
