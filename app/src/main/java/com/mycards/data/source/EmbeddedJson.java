package com.mycards.data.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Pulls a JSON literal out of an HTML page by the name it is assigned to.
 *
 * <p>Some sites ship their whole dataset inside the markup rather than behind an endpoint —
 * HTZone embeds every merchant as {@code business_arr = {...}} in a script block. Reading it
 * needs no browser and no API, but it does need brace matching: a regex fails the moment a
 * value contains a brace inside a quoted string, which addresses and terms routinely do.
 */
public final class EmbeddedJson {

    private EmbeddedJson() {
    }

    /**
     * @param variableName the identifier the JSON is assigned to, e.g. {@code business_arr}
     * @return the parsed value, or null if absent or malformed
     */
    public static JsonElement extract(String html, String variableName) {
        if (html == null || variableName == null || variableName.isEmpty()) {
            return null;
        }

        int marker = html.indexOf(variableName);
        if (marker < 0) {
            return null;
        }

        // Whichever structure opens first after the name is the value being assigned.
        int objectStart = html.indexOf('{', marker);
        int arrayStart = html.indexOf('[', marker);
        int start;
        char open, close;
        if (objectStart >= 0 && (arrayStart < 0 || objectStart < arrayStart)) {
            start = objectStart;
            open = '{';
            close = '}';
        } else if (arrayStart >= 0) {
            start = arrayStart;
            open = '[';
            close = ']';
        } else {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            // Braces inside strings are data, not structure.
            if (inString) {
                continue;
            }

            if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                try {
                    return JsonParser.parseString(html.substring(start, i + 1));
                } catch (RuntimeException malformed) {
                    return null;
                }
            }
        }
        // Ran off the end without closing: truncated page.
        return null;
    }
}
