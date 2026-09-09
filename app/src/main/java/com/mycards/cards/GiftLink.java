package com.mycards.cards;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Recognises when two gift links are the same card.
 *
 * <p>Adding the same link twice produces two rows that look like two cards, and a wallet
 * that claims twice the money it holds. Pasting a link is exactly the operation you repeat
 * without noticing — from a chat thread, an email, and then the chat thread again.
 *
 * <p>The stored link cannot be compared directly. It is encrypted with AES-GCM under a
 * random IV, so the same URL enciphers differently every time and two identical links share
 * no bytes at all. What is stored alongside it instead is this fingerprint: a hash of the
 * normalised URL, which is equal exactly when the links are.
 *
 * <p><b>The normalisation is deliberately timid.</b> Only the parts of a URL that provably
 * carry no meaning are removed — the scheme's case, the host's case, a default port, a
 * trailing slash on an empty path. The query and the fragment are kept verbatim, because
 * for these issuers that is where the voucher token lives, and there is no way to tell a
 * tracking parameter from the thing that identifies the card. Being too clever here would
 * make two different cards collide, and a wallet that refuses to add a real card is worse
 * than one that lets a duplicate through.
 *
 * <p>That timidity is also why a match only ever produces a warning. See
 * {@code AddEditCardActivity}: the user is told which card this looks like and can add it
 * anyway.
 */
public final class GiftLink {

    private GiftLink() {
    }

    /**
     * A stable fingerprint for a gift link, or null when there is no link to fingerprint.
     *
     * <p>Null rather than a hash of the empty string, so that the many cards with no link at
     * all do not all fingerprint alike and report each other as duplicates.
     */
    public static String fingerprint(String giftUrl) {
        String normalized = normalize(giftUrl);
        if (normalized == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java implementation; if it is missing the device
            // has bigger problems than a duplicate card.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The comparable form of a link: same card in, same string out.
     *
     * @return null for a blank link, or one with no host to compare
     */
    static String normalize(String giftUrl) {
        if (giftUrl == null) {
            return null;
        }
        String url = giftUrl.trim();
        if (url.isEmpty()) {
            return null;
        }

        // Split off the scheme by hand rather than through java.net.URI, which throws on the
        // stray spaces and unescaped characters that survive a copy-paste out of a chat app —
        // and a link that cannot be parsed still has to be comparable with itself.
        String scheme = "";
        String rest = url;
        int schemeEnd = url.indexOf("://");
        if (schemeEnd > 0) {
            scheme = url.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            rest = url.substring(schemeEnd + 3);
        }
        // http and https reach the same page, and issuers redirect one to the other, so a
        // link copied before a redirect must match the same link copied after it.
        if ("http".equals(scheme)) {
            scheme = "https";
        }

        // The authority ends at the first of / ? or #.
        int authorityEnd = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = rest.substring(0, authorityEnd).toLowerCase(Locale.ROOT);
        String tail = rest.substring(authorityEnd);

        if (authority.isEmpty()) {
            return null;
        }
        // A default port is the same address written longer.
        if ("https".equals(scheme) && authority.endsWith(":443")) {
            authority = authority.substring(0, authority.length() - 4);
        }
        // "example.com" and "example.com/" are the same page; deeper paths are not, so only
        // a lone trailing slash goes.
        if ("/".equals(tail)) {
            tail = "";
        }

        return (scheme.isEmpty() ? "" : scheme + "://") + authority + tail;
    }
}
