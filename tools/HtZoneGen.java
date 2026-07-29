import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Extracts the merchant list for an HTZone "Zone" voucher.
 *
 * <p>Unlike BuyMe there is no JSON endpoint here, but the voucher-zone page embeds the whole
 * list as a {@code business_arr = {...}} literal in its markup, which is just as good and
 * needs no browser to read. Rows repeat once per category and region filter, so entries are
 * deduplicated by business id.
 *
 * <p>Usage: {@code HtZoneGen <outFile> <slug> <pageFile>}
 */
public class HtZoneGen {

    public static void main(String[] args) throws Exception {
        String outFile = args[0], slug = args[1], pageFile = args[2];

        String html = new String(Files.readAllBytes(Paths.get(pageFile)), StandardCharsets.UTF_8);
        JsonObject root = extractBusinessArr(html);
        if (root == null || !root.has("business")) {
            System.err.println("SKIP " + slug + ": no business_arr found");
            return;
        }

        // Keyed by business id: the same shop appears once per filter it matches.
        Map<String, JsonObject> unique = new LinkedHashMap<>();
        // Each duplicate row carries a different filter label — one its category, another
        // its region. Merging them all makes a shop findable by any of them, rather than
        // by whichever happened to come first.
        Map<String, Set<String>> filterLabels = new LinkedHashMap<>();

        for (JsonElement el : root.getAsJsonArray("business")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id");
            if (id == null || str(o, "name") == null) continue;

            unique.putIfAbsent(id, o);

            String label = str(o, "text");
            if (label != null && !label.trim().isEmpty()) {
                filterLabels.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(label.trim());
            }
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
            addAlias(aliases, str(o, "eng_name"), name);
            // Category and region labels ("מסעדות", "אופנה", "מרכז") are legitimate ways
            // to search: someone may well type the category rather than the brand.
            for (String label : filterLabels.getOrDefault(entry.getKey(), Collections.emptySet())) {
                addAlias(aliases, label, name);
            }

            boolean isOnline = "1".equals(str(o, "is_honored_online"));
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
     * Pulls the {@code business_arr} object out of the page by counting braces from the
     * first one after the assignment. A regex cannot do this reliably — the value contains
     * braces inside quoted strings.
     */
    static JsonObject extractBusinessArr(String html) {
        int marker = html.indexOf("business_arr");
        if (marker < 0) return null;
        int start = html.indexOf('{', marker);
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false, escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                try {
                    return JsonParser.parseString(html.substring(start, i + 1)).getAsJsonObject();
                } catch (RuntimeException malformed) {
                    return null;
                }
            }
        }
        return null;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
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
