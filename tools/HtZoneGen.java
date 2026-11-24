import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Extracts the merchant list for an HTZone "Zone" voucher.
 *
 * <p>Unlike BuyMe there is no JSON endpoint here, but the voucher-zone page is a Next.js page
 * whose server-rendered data (the "flight" payload, pushed in {@code self.__next_f.push} script
 * chunks) carries the whole list, which is just as good and needs no browser to read. Next to
 * {@code businesses} sit {@code categories} and {@code areas} tables; each business points
 * into them by id.
 *
 * <p>Until November 2026 the page embedded a {@code business_arr} literal instead, one row per
 * filter a shop matched. The site was rebuilt on Next.js and that literal went away.
 *
 * <p>Usage: {@code HtZoneGen <outFile> <slug> <pageFile>}
 */
public class HtZoneGen {

    public static void main(String[] args) throws Exception {
        String outFile = args[0], slug = args[1], pageFile = args[2];

        String html = new String(Files.readAllBytes(Paths.get(pageFile)), StandardCharsets.UTF_8);
        String flight = flightData(html);
        JsonArray businesses = arrayAfter(flight, "businesses", flight.length());
        if (businesses == null) {
            System.err.println("SKIP " + slug + ": no businesses list in the page data");
            return;
        }
        // The lookup tables come just before the list they describe. Searching backwards from
        // it avoids picking up an unrelated "categories" array elsewhere on the page, such as
        // the site's own navigation.
        int listAt = flight.indexOf("\"businesses\":[");
        Map<String, String> categories = labels(arrayAfter(flight, "categories", listAt));
        Map<String, String> areas = labels(arrayAfter(flight, "areas", listAt));
        Map<String, String> tags = labels(arrayAfter(flight, "tags", listAt));

        // Keyed by business id, in case a shop is ever listed twice.
        Map<String, JsonObject> unique = new LinkedHashMap<>();
        // Category, region and tag labels ("מסעדות", "מרכז", "כשר"): a shop should be findable
        // by any of them, since someone may well type the category rather than the brand.
        // Some ids point at entries the page's tables do not have; those are just skipped.
        Map<String, Set<String>> filterLabels = new LinkedHashMap<>();

        for (JsonElement el : businesses) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id");
            if (id == null || str(o, "name") == null) continue;
            if (unique.putIfAbsent(id, o) != null) continue;

            Set<String> into = filterLabels.computeIfAbsent(id, k -> new LinkedHashSet<>());
            addLabels(into, o.get("categoryIds"), categories);
            addLabels(into, o.get("areaIds"), areas);
            addLabels(into, o.get("tagIds"), tags);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"cardTypeId\": ").append(q(slug)).append(",\n");
        sb.append("  \"source\": \"htzone_voucher_zone\",\n");
        sb.append("  \"schema\": \"n=name, a=aliases, o=online redeemable\",\n");
        sb.append("  \"stores\": [\n");

        int i = 0, online = 0;
        for (Map.Entry<String, JsonObject> entry : unique.entrySet()) {
            JsonObject o = entry.getValue();
            String name = str(o, "name").trim();
            if (name.isEmpty()) continue;

            List<String> aliases = new ArrayList<>();
            addAlias(aliases, str(o, "englishName"), name);
            for (String label : filterLabels.getOrDefault(entry.getKey(), Collections.emptySet())) {
                addAlias(aliases, label, name);
            }

            JsonElement honor = o.get("honorOnline");
            boolean isOnline = honor != null && honor.isJsonPrimitive()
                    && honor.getAsJsonPrimitive().isBoolean() && honor.getAsBoolean();
            if (isOnline) online++;

            if (i++ > 0) sb.append(",\n");
            sb.append("    {\"n\":").append(q(name));
            if (!aliases.isEmpty()) {
                sb.append(",\"a\":[");
                for (int j = 0; j < aliases.size(); j++) {
                    if (j > 0) sb.append(',');
                    sb.append(q(aliases.get(j)));
                }
                sb.append(']');
            }
            if (isOnline) sb.append(",\"o\":true");
            sb.append('}');
        }
        sb.append("\n  ]\n}\n");

        Files.createDirectories(Paths.get(outFile).getParent());
        Files.write(Paths.get(outFile), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.err.printf("%-16s %5d stores  %4d online  %6d KB%n",
                slug, i, online, Files.size(Paths.get(outFile)) / 1024);
    }

    private static void addAlias(List<String> into, String value, String name) {
        if (value == null) return;
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase(name)) return;
        for (String existing : into) {
            if (existing.equalsIgnoreCase(v)) return;
        }
        into.add(v);
    }

    /**
     * Joins the string chunks Next.js streams into the page as
     * {@code self.__next_f.push([1,"..."])}. Each chunk is a JSON string literal, and the data
     * is split across them at arbitrary points, so it can only be searched once they are
     * decoded and joined.
     */
    static String flightData(String html) {
        String marker = "self.__next_f.push([1,\"";
        StringBuilder out = new StringBuilder();
        int at = 0;
        while ((at = html.indexOf(marker, at)) >= 0) {
            int start = at + marker.length() - 1;   // the opening quote
            int end = start + 1;
            for (boolean escaped = false; end < html.length(); end++) {
                char c = html.charAt(end);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') break;
            }
            if (end >= html.length()) break;
            try {
                out.append(JsonParser.parseString(html.substring(start, end + 1)).getAsString());
            } catch (RuntimeException malformed) {
                // One unreadable chunk need not sink the rest; if it held the list, the
                // caller finds no list and says so.
            }
            at = end + 1;
        }
        return out.toString();
    }

    /**
     * Parses the JSON array that follows the last {@code "key":[} before {@code before}.
     * The array ends wherever its brackets balance; a JSON reader stops there by itself, so
     * nothing after it needs to be valid.
     */
    static JsonArray arrayAfter(String flight, String key, int before) {
        String needle = "\"" + key + "\":[";
        int at = flight.lastIndexOf(needle, before);
        if (at < 0) return null;
        try {
            JsonElement e = JsonParser.parseReader(new com.google.gson.stream.JsonReader(
                    new StringReader(flight.substring(at + needle.length() - 1))));
            return e.isJsonArray() ? e.getAsJsonArray() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Turns a {@code [{"id":84,"name":"..."}]} table into id → name. */
    static Map<String, String> labels(JsonArray table) {
        Map<String, String> byId = new HashMap<>();
        if (table == null) return byId;
        for (JsonElement el : table) {
            if (!el.isJsonObject()) continue;
            String id = str(el.getAsJsonObject(), "id");
            String name = str(el.getAsJsonObject(), "name");
            if (id != null && name != null && !name.trim().isEmpty()) byId.put(id, name.trim());
        }
        return byId;
    }

    static void addLabels(Set<String> into, JsonElement ids, Map<String, String> table) {
        if (ids == null || !ids.isJsonArray()) return;
        for (JsonElement id : ids.getAsJsonArray()) {
            String label = id.isJsonPrimitive() ? table.get(id.getAsString()) : null;
            if (label != null) into.add(label);
        }
    }

    /**
     * Reads a string field, undoing the flight payload's encoding: an absent value is written
     * {@code "$undefined"}, other {@code $}-prefixed values are references rather than text,
     * and a literal leading {@code $} is escaped as {@code $$}.
     */
    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        if (s.startsWith("$$")) return s.substring(1);
        if (s.startsWith("$")) return null;
        return s;
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
