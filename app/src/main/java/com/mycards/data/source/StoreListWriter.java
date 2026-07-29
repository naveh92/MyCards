package com.mycards.data.source;

import com.mycards.search.Store;

import java.util.List;

/**
 * Serialises merchants back into the compact snapshot format for the local cache.
 *
 * <p>What gets written is each store's <em>normalized</em> haystacks rather than its
 * original aliases. Normalization is idempotent, so re-reading through the ordinary
 * {@link Store} constructor reproduces an identical index — which means the cache and the
 * bundled assets can share one format instead of needing two parsers.
 */
public final class StoreListWriter {

    private StoreListWriter() {
    }

    public static String toCompactJson(String cardTypeId, String sourceType, List<Store> stores) {
        StringBuilder sb = new StringBuilder(stores.size() * 64);
        sb.append("{\"cardTypeId\":").append(quote(cardTypeId));
        sb.append(",\"source\":").append(quote(sourceType));
        sb.append(",\"stores\":[");

        for (int i = 0; i < stores.size(); i++) {
            Store s = stores.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"n\":").append(quote(s.getName()));

            List<String> hay = s.getHaystacks();
            if (!hay.isEmpty()) {
                sb.append(",\"a\":[");
                for (int j = 0; j < hay.size(); j++) {
                    if (j > 0) {
                        sb.append(',');
                    }
                    sb.append(quote(hay.get(j)));
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
