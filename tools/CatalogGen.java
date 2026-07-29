import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Builds catalog card-type entries (and optional bundled snapshots) straight from the live
 * BuyMe endpoint, so names and merchant counts are real rather than hand-typed.
 *
 * Args: <assetsDir> then one or more "id:slug:bundle"
 */
public class CatalogGen {

    static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    static class Brand {
        String name;
        List<String> aliases = new ArrayList<>();
        boolean online;
    }

    public static void main(String[] args) throws Exception {
        String assetsDir = args[0];
        // Payloads are pre-downloaded with curl: this machine sits behind a TLS-inspecting
        // proxy whose CA the JDK does not trust, so direct HTTPS from Java fails.
        String rawDir = args[1];
        StringBuilder catalog = new StringBuilder();

        for (int i = 2; i < args.length; i++) {
            String[] parts = args[i].split(":");
            String id = parts[0], slug = parts[1];
            boolean bundle = parts.length > 2 && "true".equals(parts[2]);

            byte[] payload = Files.readAllBytes(Paths.get(rawDir, id + ".json"));

            String supplierName = readSupplierName(payload);
            List<Brand> brands = readBrands(payload);

            if (brands.isEmpty()) {
                System.err.println("SKIP " + slug + " (" + id + "): no brands");
                continue;
            }

            // Names arrive as "BUYME CHEF - מגוון מסעדות שף": the part before the dash is
            // the card's actual name, the rest is a tagline worth keeping as an alias.
            String shortName = supplierName;
            String tagline = null;
            // Split only on a dash with whitespace on at least one side, and accept the
            // en/em dashes the data actually uses. A bare hyphen inside a word must survive,
            // or "BUYME RAMAT-GAN" would be truncated to "BUYME RAMAT".
            String[] halves = supplierName.split("\\s+[-\u2013\u2014]\\s*|\\s*[-\u2013\u2014]\\s+", 2);
            if (halves.length == 2 && !halves[0].trim().isEmpty()) {
                shortName = halves[0].trim();
                tagline = halves[1].trim();
            }

            String display = titleCase(shortName);

            Set<String> aliases = new LinkedHashSet<>();
            aliases.add(supplierName);
            aliases.add(shortName);
            if (tagline != null && !tagline.isEmpty()) aliases.add(tagline);
            // The distinguishing word, so "chef" alone narrows to the right card.
            String[] words = shortName.split("\\s+");
            if (words.length > 1) {
                aliases.add(words[words.length - 1]);
            }
            aliases.remove(display);

            if (bundle) {
                writeSnapshot(Paths.get(assetsDir, slug + ".json"), slug, brands);
            }

            catalog.append(entry(slug, display, tagline, aliases, id, bundle));
            System.err.printf("%-22s %-24s %5d brands%s%n",
                    slug, display, brands.size(), bundle ? "  [bundled]" : "");
        }

        System.out.println(catalog);
    }

    static String entry(String slug, String display, String tagline,
                        Set<String> aliases, String id, boolean bundle) {
        StringBuilder b = new StringBuilder();
        b.append("    {\n");
        b.append("      \"id\": ").append(q(slug)).append(",\n");
        b.append("      \"names\": { \"en\": ").append(q(display)).append(", \"he\": ")
                .append(q(tagline == null || tagline.isEmpty() ? display : display)).append(" },\n");
        b.append("      \"aliases\": [");
        int i = 0;
        for (String a : aliases) {
            if (a == null || a.trim().isEmpty()) continue;
            if (i++ > 0) b.append(", ");
            b.append(q(a.trim()));
        }
        b.append("],\n");
        b.append("      \"issuer\": \"BuyMe\",\n");
        // Order matters: the published GitHub copy serves every install, the bundled
        // snapshot covers offline and first launch, and the issuer endpoint is a last
        // resort so a few hundred phones never hammer BuyMe directly.
        b.append("      \"storeSources\": [\n");
        b.append("        { \"type\": \"static_list\", \"url\": \"{base}/stores/").append(slug).append(".json\" },\n");
        if (bundle) {
            b.append("        { \"type\": \"bundled_asset\", \"asset\": \"stores/").append(slug).append(".json\" },\n");
        }
        b.append("        { \"type\": \"buyme_brands\", \"supplierId\": ").append(q(id)).append(" }");
        b.append("\n      ],\n");
        b.append("      \"balanceSources\": [ { \"type\": \"buyme_gift_page\" } ]\n");
        b.append("    },\n");
        return b.toString();
    }

    static String titleCase(String s) {
        String[] w = s.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String x : w) {
            if (x.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            if (x.equals("buyme")) { b.append("BuyMe"); continue; }
            if (x.equals("&")) { b.append('&'); continue; }
            // Leave Hebrew and other non-Latin words untouched.
            char c0 = x.charAt(0);
            if (c0 >= 'a' && c0 <= 'z') {
                // Capitalise after an internal hyphen too, so "ramat-gan" reads "Ramat-Gan".
                StringBuilder word = new StringBuilder();
                boolean upper = true;
                for (char c : x.toCharArray()) {
                    word.append(upper ? Character.toUpperCase(c) : c);
                    upper = (c == '-');
                }
                b.append(word);
            } else b.append(x);
        }
        return b.toString();
    }

    static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    static String readSupplierName(byte[] payload) throws IOException {
        try (JsonReader r = reader(payload)) {
            r.beginObject();
            while (r.hasNext()) {
                if ("supplier".equals(r.nextName())) {
                    r.beginObject();
                    while (r.hasNext()) {
                        if ("name".equals(r.nextName())) return r.nextString();
                        else r.skipValue();
                    }
                    r.endObject();
                } else r.skipValue();
            }
        }
        return "";
    }

    static List<Brand> readBrands(byte[] payload) throws IOException {
        List<Brand> out = new ArrayList<>();
        try (JsonReader r = reader(payload)) {
            r.beginObject();
            while (r.hasNext()) {
                if (!"brands".equals(r.nextName())) { r.skipValue(); continue; }
                r.beginArray();
                while (r.hasNext()) {
                    Brand br = new Brand();
                    String terms = null;
                    r.beginObject();
                    while (r.hasNext()) {
                        switch (r.nextName()) {
                            case "title": br.name = str(r); break;
                            case "searchTerms": terms = str(r); break;
                            case "online_redeem":
                                if (r.peek() == JsonToken.BOOLEAN) br.online = r.nextBoolean();
                                else if (r.peek() == JsonToken.NUMBER) br.online = r.nextInt() != 0;
                                else r.skipValue();
                                break;
                            default: r.skipValue();
                        }
                    }
                    r.endObject();
                    if (br.name == null || br.name.trim().isEmpty()) continue;
                    br.name = br.name.trim();
                    br.aliases = aliases(terms, br.name);
                    out.add(br);
                }
                r.endArray();
            }
        }
        return out;
    }

    static JsonReader reader(byte[] payload) {
        return new JsonReader(new InputStreamReader(
                new ByteArrayInputStream(payload), StandardCharsets.UTF_8));
    }

    static String str(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return null; }
        return r.nextString();
    }

    static List<String> aliases(String raw, String name) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<>();
        seen.add(name.toLowerCase(Locale.ROOT));
        try {
            JsonElement el = JsonParser.parseString(raw);
            if (!el.isJsonArray()) return out;
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonPrimitive()) continue;
                String t = e.getAsString().trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (seen.add(t.toLowerCase(Locale.ROOT))) out.add(t);
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    static void writeSnapshot(Path path, String slug, List<Brand> brands) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"cardTypeId\": ").append(q(slug)).append(",\n");
        sb.append("  \"source\": \"buyme_brands\",\n");
        sb.append("  \"schema\": \"n=name, a=aliases, o=online redeemable\",\n");
        sb.append("  \"stores\": [\n");
        for (int i = 0; i < brands.size(); i++) {
            Brand s = brands.get(i);
            sb.append("    {\"n\":").append(q(s.name));
            if (!s.aliases.isEmpty()) {
                sb.append(",\"a\":[");
                for (int j = 0; j < s.aliases.size(); j++) {
                    if (j > 0) sb.append(',');
                    sb.append(q(s.aliases.get(j)));
                }
                sb.append(']');
            }
            if (s.online) sb.append(",\"o\":true");
            sb.append('}');
            if (i < brands.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        Files.createDirectories(path.getParent());
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
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
