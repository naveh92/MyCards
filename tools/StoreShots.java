import javax.imageio.ImageIO;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Builds the Play Store phone screenshots: a full-bleed app capture with a wavy branded
 * banner across the top carrying one line of promo copy.
 *
 * <p>This replaces {@link ShotFramer}'s caption-above-a-shrunken-device layout. That layout
 * spent roughly half the canvas on background and shrank the screen to the point where none
 * of the app's own text survived the store's thumbnail. Play shows these images small and
 * side by side; the only things that read at that size are the promo line and the overall
 * shape of the screen, so the screen gets the whole frame and the promo line gets a band it
 * cannot be lost against.
 *
 * <p><b>This is a port of the {@code game-infra} skill's {@code make_store_assets.py}</b>
 * (see {@code references/store-listing-art.md} in that skill). The numbers in here are not
 * guesses -- they were measured on a real submission, and the two that matter most are the
 * alpha threshold in {@link #prepareBar} and the shadow weight in {@link #banner}. The port
 * exists because this machine has no Python and the rest of {@code tools/} is Java; the
 * behaviour is meant to match the script, so fix bugs in both.
 *
 * <p>Usage: {@code java StoreShots.java <bars-dir> <shots-dir> <out-dir> [bar] [font.ttf]}
 */
public final class StoreShots {

    // --- PER APP ---------------------------------------------------------------------------

    /**
     * The banner colourway. {@code blue_original} is a #2A8DFC sky blue -- the same hue family
     * as the app's own #1B5E9C toolbar, one step brighter. At 1080 wide the bar lands ~302px
     * tall, which is almost exactly the height of the status bar plus the toolbar in a
     * 1080x2400 capture, so the banner sits where the app's own blue chrome would be and reads
     * as an extension of it rather than a sticker over it.
     *
     * <p>The other five colourways were rejected on message, not on looks: the app already
     * spends orange on "expiring soon" and green on "online", so a bar in either colour turns
     * a status colour into the brand colour. {@code cosmic-navy}'s starfield says space game.
     */
    static final String DEFAULT_BAR = "blue_original";

    /**
     * The promo copy's outline colour -- a navy darker than any point of the blue bar's
     * gradient, so the white fill keeps its edge at the pale end of the wave. Deliberately
     * the app's own {@code brand_on_primary_container} rather than pure black.
     */
    static final Color STROKE = new Color(0x0B2A45);

    /**
     * The eight screenshots, in the order Play shows them.
     *
     * <p>Ordering is the whole game: Play surfaces the first two or three in search results
     * and most people never scroll past them, so the two halves of the app's one idea --
     * shop-to-card and card-to-shop -- go first, and the reassurance goes last.
     *
     * <p>{@code anchor} is where the 9:16 window sits in the taller capture: 0.0 keeps the
     * top, 1.0 the bottom. A 1080x2400 capture has to lose 480 rows and which 480 is a
     * per-screen question. These are list screens whose dead space is at the bottom, so they
     * sit near the top -- but the banner covers the first ~302px, so the anchors are nudged
     * down far enough that the search field clears it.
     */
    static final Shot[] SHOTS = {
        new Shot("search-store", 0.06, 1.00, "WHICH CARD WORKS\nIN THIS SHOP?"),
        new Shot("store-list",   0.06, 1.00, "AND WHERE DOES\nTHIS CARD WORK?"),
        new Shot("hebrew",       0.06, 1.00, "WRONG KEYBOARD?\nFINDS IT ANYWAY."),
        // 2,179 unique shop names across the 32 lists in docs/stores, counted rather than
        // remembered -- the widest single card is 1,303 (buyme_all). Do not round this up:
        // an inflated number in a listing is the one kind of copy a reviewer can check.
        new Shot("card-types",   0.06, 1.00, "32 CARD TYPES.\n2,100+ SHOPS."),
        new Shot("detail",       0.06, 1.00, "SPEND IT BEFORE\nIT EXPIRES."),
        new Shot("wallet",       0.06, 1.00, "EVERY CARD, SOONEST\nTO EXPIRE FIRST."),
        // ⚠️ ANCHOR 1.0 ON PURPOSE. Settings is one short page that does not scroll far, so
        // the freshness line sits two thirds down it. Anchored to the top this shot is a
        // screenful of language radio buttons under a caption about shop lists -- the caption
        // and the screen disagreeing is worse than either being dull. Anchored to the bottom
        // it lands on "Where store lists come from", and the backup blurb below it says
        // "your cards live only on this phone" for free.
        new Shot("refresh",      1.00, 1.00, "SHOP LISTS REFRESH\nTHEMSELVES."),
        new Shot("dark",         0.06, 1.00, "HEBREW AND ENGLISH.\nLIGHT AND DARK."),
    };

    /**
     * Rendered but not numbered, so they do not eat one of Play's eight slots.
     *
     * <p>The spending-history screen is a real candidate -- it is the only screen that totals
     * across cards, and it reads well -- but shot 5 already shows a card's purchases inline,
     * and eight is a hard cap. This gets built as {@code alt-history.png} so swapping it in is
     * a rename rather than a re-shoot: drop it over whichever numbered file it replaces.
     */
    static final Shot[] ALTERNATES = {
        new Shot("history",      0.06, 1.00, "EVERY PURCHASE,\nMONTH BY MONTH."),
    };

    // --- END PER APP -----------------------------------------------------------------------

    private static final int W = 1080;
    private static final int H = 1920;

    /** One screenshot: the capture to use, how to crop it, and what to say over it. */
    record Shot(String name, double anchor, double zoom, String promo) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                    "usage: java StoreShots.java <bars-dir> <shots-dir> <out-dir> [bar] [font.ttf]");
            System.exit(2);
        }
        File barsDir = new File(args[0]);
        File shotsDir = new File(args[1]);
        File outDir = new File(args[2]);
        String barName = args.length > 3 ? args[3] : DEFAULT_BAR;
        File fontFile = args.length > 4 ? new File(args[4]) : null;
        outDir.mkdirs();

        BufferedImage bar = prepareBar(new File(barsDir, "top_bar_" + barName + ".png"));
        Font font = loadFont(fontFile);
        System.out.println("font " + font.getFontName());

        int n = 0;
        for (Shot s : SHOTS) {
            File in = new File(shotsDir, s.name() + ".png");
            if (!in.exists()) {
                System.out.println("MISSING " + in + "  -- capture it first");
                continue;
            }
            n++;
            File out = new File(outDir, String.format("%02d-%s.png", n, s.name()));
            BufferedImage frame = cropToPhone(ImageIO.read(in), s.anchor(), s.zoom());
            ImageIO.write(banner(frame, bar, s.promo(), font), "png", out);
            System.out.printf("%-34s %dx%d  %d KB%n", out.getName(), W, H, out.length() / 1024);
        }
        for (Shot s : ALTERNATES) {
            File in = new File(shotsDir, s.name() + ".png");
            if (!in.exists()) {
                continue;
            }
            File out = new File(outDir, "alt-" + s.name() + ".png");
            BufferedImage frame = cropToPhone(ImageIO.read(in), s.anchor(), s.zoom());
            ImageIO.write(banner(frame, bar, s.promo(), font), "png", out);
            System.out.printf("%-34s %dx%d  %d KB  (alternate, not uploaded)%n",
                    out.getName(), W, H, out.length() / 1024);
        }

        if (n < 4) {
            System.out.println("\nWARNING only " + n + " screenshots -- Play wants at least 4 to be"
                    + " eligible for promotion.");
        }
        if (n > 8) {
            System.out.println("\nWARNING " + n + " screenshots -- Play accepts at most 8.");
        }
    }

    /**
     * Load a top bar, crop it to its real edges, and rebuild its alpha as a clean mask.
     *
     * <p><b>The alpha that ships with these files cannot be trusted, and the two failures look
     * different.</b> {@code blue_original} carries an invisible halo at alpha 1-2 reaching the
     * full canvas edge; a bounding box of non-zero alpha therefore keeps the entire canvas and
     * the bar composites 16% too tall -- which does not look like a bug, it looks like the
     * banner was drawn chunkier than the other five, and it silently stops the colourways
     * being interchangeable. The other five have no partial alpha at all, so their curve
     * stair-steps once it is scaled down.
     *
     * <p>So: threshold first, take the bounding box <i>of the thresholded channel</i> -- never
     * of the raw alpha -- then blur half a pixel to put the antialiasing back. That fixes both
     * at once and is idempotent. Do not skip it because a file looks clean at 100% zoom: an
     * alpha-1 halo is invisible by definition.
     */
    static BufferedImage prepareBar(File path) throws Exception {
        if (!path.exists()) {
            throw new IllegalArgumentException("no such bar: " + path);
        }
        BufferedImage raw = toArgb(ImageIO.read(path));
        int w = raw.getWidth();
        int h = raw.getHeight();

        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((raw.getRGB(x, y) >>> 24) > 200) {
                    if (x < minX) { minX = x; }
                    if (y < minY) { minY = y; }
                    if (x > maxX) { maxX = x; }
                    if (y > maxY) { maxY = y; }
                }
            }
        }
        if (maxX < 0) {
            throw new IllegalStateException("bar is fully transparent above the threshold: " + path);
        }

        int cw = maxX - minX + 1;
        int ch = maxY - minY + 1;
        BufferedImage bar = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int argb = raw.getRGB(minX + x, minY + y);
                int a = (argb >>> 24) > 200 ? 255 : 0;
                bar.setRGB(x, y, (a << 24) | (argb & 0x00FFFFFF));
            }
        }
        setAlpha(bar, blur(alphaOf(bar), cw, ch, 0.5));

        System.out.printf("bar %-24s %dx%d  (from %dx%d, alpha rebuilt)  aspect %.3f%n",
                path.getName(), cw, ch, w, h, (double) cw / ch);
        return bar;
    }

    /**
     * Lay the bar across the top of the frame and set one line of promo copy on it.
     *
     * <p><b>The shadow goes under the wave, not under a rectangle.</b> The bar's lower edge is
     * a curve, so a box shadow floats a straight dark line across the screenshot wherever the
     * curve rises away from it, and reads as unmistakably pasted on. Reusing the bar's own
     * alpha as the shadow's shape -- offset down and blurred -- is what makes it sit on the
     * art instead of hovering over it.
     *
     * <p><b>And it wants to be lighter than the first guess.</b> The skill's first pass was
     * alpha 150 / offset 14 / blur 11, and at that weight the dark band under the wave was the
     * first thing the eye went to. These are the shipped values: a lift, not a drop shadow.
     */
    static BufferedImage banner(BufferedImage frame, BufferedImage bar, String text, Font base) {
        int barH = (int) Math.round((double) bar.getHeight() * W / bar.getWidth());
        BufferedImage b = scale(bar, W, barH);

        // Shadow: the bar's own alpha, dropped 9px, blurred 8, at 78/255 -- then the bar on top.
        float[] mask = new float[W * H];
        float[] barAlpha = alphaOf(b);
        for (int y = 0; y < barH; y++) {
            int dy = y + 9;
            if (dy >= H) {
                break;
            }
            for (int x = 0; x < W; x++) {
                mask[dy * W + x] = barAlpha[y * W + x] * (78f / 255f);
            }
        }
        mask = blur(mask, W, H, 8);

        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        hints(g);
        g.drawImage(frame, 0, 0, null);

        BufferedImage shadow = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < mask.length; i++) {
            int a = Math.min(255, Math.round(mask[i]));
            if (a > 0) {
                shadow.setRGB(i % W, i / W, (a << 24) | 0x0A1628);
            }
        }
        g.drawImage(shadow, 0, 0, null);
        g.drawImage(b, 0, 0, null);

        // The type sits in the bar's flat upper 70%; below that the wave eats into it.
        double flat = barH * 0.70;
        String[] lines = text.split("\n");
        Font font = fitText(g, lines, base, W * 0.86);
        g.setFont(font);
        double lh = font.getSize() * 1.06;
        double y = (flat - lh * lines.length) / 2 + g.getFontMetrics().getAscent();
        int stroke = Math.max(3, font.getSize() / 14);

        for (String line : lines) {
            TextLayout tl = new TextLayout(line, font, g.getFontRenderContext());
            double x = (W - tl.getAdvance()) / 2;
            Shape outline = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
            // Stroke first, then fill: BasicStroke straddles the path, so a double-width
            // stroke underneath leaves exactly `stroke` px proud of the glyph.
            g.setColor(STROKE);
            g.setStroke(new BasicStroke(stroke * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(outline);
            g.setColor(Color.WHITE);
            g.fill(outline);
            y += lh;
        }
        g.dispose();
        return out;
    }

    /**
     * Scale to 1080 wide (times {@code zoom}), then take a 1920-tall window set by {@code anchor}.
     *
     * <p>Letterboxing is deliberately not done here. A capture taller than 9:16 is cropped and
     * one shorter is scaled to fit; squashing to the aspect is the one option guaranteed to
     * look wrong, and Play rejects outright the 9:20 a modern phone actually captures.
     */
    static BufferedImage cropToPhone(BufferedImage im, double anchor, double zoom) {
        int w = (int) Math.round(W * zoom);
        int h = (int) Math.round((double) im.getHeight() * w / im.getWidth());
        BufferedImage scaled = scale(im, w, h);
        if (h < H) {
            return scale(scaled, W, H);
        }
        int left = (w - W) / 2;
        int top = (int) Math.round((h - H) * anchor);
        return scaled.getSubimage(left, top, W, H);
    }

    /** Largest size at which every line fits the width. Lines are pre-split by the caller. */
    private static Font fitText(Graphics2D g, String[] lines, Font base, double boxW) {
        for (int size = 92; size > 20; size -= 2) {
            Font f = base.deriveFont(Font.PLAIN, (float) size);
            boolean fits = true;
            for (String line : lines) {
                if (new TextLayout(line, f, g.getFontRenderContext()).getAdvance() > boxW) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                return f;
            }
        }
        return base.deriveFont(Font.PLAIN, 22f);
    }

    /**
     * The app's own display face where one is supplied, Segoe UI Black otherwise.
     *
     * <p>A generic store font is the fastest way to make a listing look like an asset flip, so
     * the caller passes the emulator's Roboto -- the face the screenshots underneath are
     * already set in.
     */
    private static Font loadFont(File file) throws Exception {
        if (file != null && file.exists()) {
            return Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(Font.PLAIN, 64f);
        }
        File segoe = new File("C:/Windows/Fonts/seguibl.ttf");
        if (segoe.exists()) {
            return Font.createFont(Font.TRUETYPE_FONT, segoe).deriveFont(Font.PLAIN, 64f);
        }
        return new Font("SansSerif", Font.BOLD, 64);
    }

    // --- pixels ------------------------------------------------------------------------------

    private static float[] alphaOf(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        float[] a = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                a[y * w + x] = img.getRGB(x, y) >>> 24;
            }
        }
        return a;
    }

    private static void setAlpha(BufferedImage img, float[] a) {
        int w = img.getWidth();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < w; x++) {
                int v = Math.max(0, Math.min(255, Math.round(a[y * w + x])));
                img.setRGB(x, y, (v << 24) | (img.getRGB(x, y) & 0x00FFFFFF));
            }
        }
    }

    /** Separable Gaussian, edges clamped. Java2D has no blur of its own worth the name. */
    private static float[] blur(float[] src, int w, int h, double sigma) {
        int r = (int) Math.ceil(sigma * 3);
        float[] k = new float[2 * r + 1];
        double sum = 0;
        for (int i = -r; i <= r; i++) {
            k[i + r] = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
            sum += k[i + r];
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= (float) sum;
        }

        float[] tmp = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = 0;
                for (int i = -r; i <= r; i++) {
                    v += k[i + r] * src[y * w + Math.max(0, Math.min(w - 1, x + i))];
                }
                tmp[y * w + x] = v;
            }
        }
        float[] dst = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = 0;
                for (int i = -r; i <= r; i++) {
                    v += k[i + r] * tmp[Math.max(0, Math.min(h - 1, y + i)) * w + x];
                }
                dst[y * w + x] = v;
            }
        }
        return dst;
    }

    /**
     * Halve repeatedly, then land the last step bilinear.
     *
     * <p>A single bilinear pass from 2136 to 1080 samples two of every four source pixels and
     * drops the rest, which is exactly where the wave's edge picks up the stair-stepping the
     * alpha rebuild just removed.
     */
    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        int cw = src.getWidth();
        int ch = src.getHeight();
        boolean alpha = src.getColorModel().hasAlpha();
        while (cw / 2 > w && ch / 2 > h) {
            cw /= 2;
            ch /= 2;
            cur = redraw(cur, cw, ch, alpha);
        }
        return redraw(cur, w, h, alpha);
    }

    private static BufferedImage redraw(BufferedImage src, int w, int h, boolean alpha) {
        BufferedImage out = new BufferedImage(w, h,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        hints(g);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private StoreShots() {
    }
}
