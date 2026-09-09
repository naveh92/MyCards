package com.mycards.data.source;

import com.mycards.search.Store;

import java.util.List;

/**
 * Serialises merchants back into the compact snapshot format for the local cache.
 *
 * <p>What gets written is each store's aliases <em>as written</em>. That is a change: this
 * used to write the normalized match keys instead, on the grounds that normalization is
 * idempotent and re-reading them rebuilds an identical index. It does — but an index is not
 * all the cache is read for. A row explaining that it matched "carolinalemke" has told the
 * reader nothing except that the app mangles text, and the two screens that name the alias a
 * query hit had to be built around that. Round-tripping the original costs a few characters
 * per alias and gives both screens something a person wrote.
 */
public final class StoreListWriter {

    /**
     * Bumped when what the cache holds changes shape, so a cache written by an older build
     * can be recognised and re-seeded rather than read as if it were current.
     *
     * <p>Version 1 is the unmarked original, whose aliases are normalized run-ons.
     */
    public static final int FORMAT_VERSION = 2;

    private StoreListWriter() {
    }

    public static String toCompactJson(String cardTypeId, String sourceType, List<Store> stores) {
        StringBuilder sb = new StringBuilder(stores.size() * 64);
        sb.append("{\"cardTypeId\":").append(quote(cardTypeId));
        sb.append(",\"v\":").append(FORMAT_VERSION);
        sb.append(",\"source\":").append(quote(sourceType));
        sb.append(",\"stores\":[");

        for (int i = 0; i < stores.size(); i++) {
            Store s = stores.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"n\":").append(quote(s.getName()));

            // Slot 0 is the name, already written above as "n".
            if (s.formCount() > 1) {
                sb.append(",\"a\":[");
                for (int j = 1; j < s.formCount(); j++) {
                    if (j > 1) {
                        sb.append(',');
                    }
                    sb.append(quote(s.getForm(j)));
                }
                sb.append(']');
            }
            if (s.isOnlineRedeem()) {
                sb.append(",\"o\":true");
            }
            sb.append('}');
        }

        return sb.append("]}").toString();
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }
}
