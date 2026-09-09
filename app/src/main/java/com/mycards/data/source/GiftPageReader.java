package com.mycards.data.source;

import java.io.IOException;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches a gift page so {@link GiftPageDetails} can read the card off it.
 *
 * <p>Split from the parsing on purpose: this half needs a network and cannot be unit-tested,
 * the other half is all of the logic and can. Everything interesting lives over there.
 *
 * <p>Plain HTTP, no JavaScript. A page that renders its balance client-side returns a shell
 * here and yields nothing, which is a normal outcome rather than an error — running a real
 * browser engine to scrape a page is a different and much larger thing than this.
 */
public final class GiftPageReader {

    private GiftPageReader() {
    }

    /**
     * Reads the page and pulls out whatever it will give up.
     *
     * @param giftUrl the link the user pasted, already carrying a scheme
     * @return the fields that could be read; possibly {@link GiftPageDetails#isEmpty()}
     * @throws IOException when the page could not be fetched at all, which is worth telling
     *                     the user about — unlike a page that simply had nothing on it
     */
    public static GiftPageDetails read(String giftUrl, SourceEnv env) throws IOException {
        Request request = new Request.Builder()
                .url(giftUrl)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .get()
                .build();

        try (Response response = env.http().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("gift page HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                return GiftPageDetails.parse(null);
            }
            // Read whole and truncated by the parser, exactly as GiftPageBalanceProvider
            // does. The read timeout on the shared client is what bounds a server that
            // will not stop talking.
            return GiftPageDetails.parse(body.string());
        }
    }
}
