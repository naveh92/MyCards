import javax.imageio.ImageIO;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the still layers of the promo video: one stage per scene, plus the phone bezel that
 * is laid over the screen recording.
 *
 * <p><b>Why the video is landscape when the app is portrait.</b> Play's promo video is a
 * YouTube URL played in a 16:9 slot. A portrait film gets pillarboxed there, throwing away
 * two thirds of the frame; a landscape stage with the phone standing in it uses all of it and
 * leaves room for the line of copy that has to carry the scene, because the video autoplays
 * <b>muted</b> and most people never unmute it. Every claim in this film is on screen as text.
 *
 * <p><b>Why stills at all.</b> ffmpeg composites video onto these: each scene is
 * {@code stage-NN.png} (background, copy, and the shadow the phone casts) with the recording
 * overlaid into {@link #SCREEN} and {@code bezel.png} on top. Splitting it that way keeps the
 * typography in Java2D, where it can be measured, instead of in an ffmpeg drawtext filter,
 * where it cannot.
 *
 * <p>Usage: {@code java VideoStage.java <bars-dir> <out-dir> [icon.png]}
 */
public final class VideoStage {

    // --- PER APP ---------------------------------------------------------------------------

    /**
     * The film, in order. {@code clip} names the recording that plays in the phone; a scene
     * with no clip is a card that stands on its own.
     *
     * <p>The shape is the one the research supports: a hook that states the problem before
     * naming the app, the two halves of the core idea next, the surprising detail third
     * (wrong-keyboard search is the thing people repeat to someone else), breadth and upkeep
     * after that, and the reassurance last. Roughly 30 seconds all told -- Play accepts 30 to
     * 120, only the first 30 autoplay, and a listing video that outstays that is unwatched.
     */
    static final Scene[] SCENES = {
        new Scene(null, "You are at the checkout.",
                "Four gift cards in your bag. One of them works here.", 4.0),
        new Scene("search-store", "Type the shop.",
                "Every card that works there, with what is left on it.", 5.5),
        new Scene("store-list", "Or ask it the other way.",
                "Tap a card and see every shop that takes it.", 5.0),
        new Scene("hebrew", "Typed in the wrong language?",
                "Hebrew, English, wrong keyboard layout, half a word. It still finds it.", 5.0),
        new Scene("card-types", "32 card types. 2,100+ shops.",
                "BuyMe, the Zone family, LOVE, Max, Shufersal, Rami Levi and more.", 4.5),
        new Scene("refresh", "The shop lists keep themselves current.",
                "They refresh in the background. The app still works with no signal.", 4.5),
        new Scene("detail", "Spend it before it expires.",
                "Sorted by what dies first, so the money does not quietly evaporate.", 4.5),
        new Scene("history", "Every purchase, month by month.",
                "What you spent, where, and which card paid for it.", 4.0),
        new Scene(null, "No account. No server. No ads.",
                "Your card numbers never leave your phone.", 4.0),
    };

    private static final Color BG_TOP = new Color(0xFFFFFF);
    private static final Color BG_BOTTOM = new Color(0xE9EFF6);
    private static final Color HEADLINE = new Color(0x0B2A45);
    private static final Color SUBLINE = new Color(0x4A5A6A);
    private static final Color ACCENT = new Color(0x1B5E9C);
    private static final Color BEZEL = new Color(0x11161C);

    /** The banner colourway, matched to the screenshots so the two read as one campaign. */
    static final String BAR = "blue_original";

    // --- END PER APP -----------------------------------------------------------------------

    private static final int W = 1920;
    private static final int H = 1080;

    /** The wavy brand band across the top. Decorative here -- the copy sits below it. */
    private static final int BAND_H = 150;

    /**
     * Where the recording is drawn. A 1080x2400 capture at 360x800 is an exact 1:3 reduction,
     * which keeps the app's own text on whole pixels instead of smearing it across two.
     */
    // 378x840 is exactly 1080x2400 scaled by 0.35, so the capture's pixels land on whole
    // pixels instead of being resampled across two -- which at this size is the difference
    // between the app's own body text being readable and being a grey smear. Keep the ratio
    // if you change the size: 840 down the page leaves 54px under the phone and 36 between it
    // and the wavy band, which is the smallest gap that still reads as a margin.
    static final int SCREEN_X = 1400;
    static final int SCREEN_Y = 186;
    static final int SCREEN_W = 378;
    static final int SCREEN_H = 840;
    private static final int SCREEN_R = 36;

    /** One scene: what plays in the phone, what the copy says, and for how long. */
    record Scene(String clip, String headline, String subline, double seconds) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java VideoStage.java <bars-dir> <out-dir> [icon.png]");
            System.exit(2);
        }
        File barsDir = new File(args[0]);
        File outDir = new File(args[1]);
        File icon = args.length > 2 ? new File(args[2]) : null;
        outDir.mkdirs();

        BufferedImage bar = StoreShots.prepareBar(new File(barsDir, "top_bar_" + BAR + ".png"));
        Font black = face("C:/Windows/Fonts/seguibl.ttf", Font.BOLD);
        Font semi = face("C:/Windows/Fonts/seguisb.ttf", Font.PLAIN);

        for (int i = 0; i < SCENES.length; i++) {
            Scene s = SCENES[i];
            BufferedImage stage = stage(s, bar, black, semi, icon, i == SCENES.length - 1);
            File out = new File(outDir, String.format("stage-%02d.png", i + 1));
            ImageIO.write(stage, "png", out);
            System.out.printf("%-16s %5.1fs  clip=%-14s %s%n",
                    out.getName(), s.seconds(), s.clip() == null ? "-" : s.clip(), s.headline());
        }
        ImageIO.write(bezel(), "png", new File(outDir, "bezel.png"));
        System.out.println("bezel.png");

        // The ffmpeg script reads these rather than repeating them, so the two cannot drift.
        StringBuilder sh = new StringBuilder();
        sh.append("# generated by VideoStage.java -- do not edit\n");
        sh.append("SCREEN_X=").append(SCREEN_X).append('\n');
        sh.append("SCREEN_Y=").append(SCREEN_Y).append('\n');
        sh.append("SCREEN_W=").append(SCREEN_W).append('\n');
        sh.append("SCREEN_H=").append(SCREEN_H).append('\n');
        sh.append("W=").append(W).append("\nH=").append(H).append('\n');
        List<String> clips = new ArrayList<>();
        List<String> secs = new ArrayList<>();
        for (Scene s : SCENES) {
            clips.add(s.clip() == null ? "-" : s.clip());
            secs.add(String.format("%.2f", s.seconds()));
        }
        sh.append("CLIPS=(").append(String.join(" ", clips)).append(")\n");
        sh.append("SECS=(").append(String.join(" ", secs)).append(")\n");
        java.nio.file.Files.writeString(new File(outDir, "stage.env").toPath(), sh.toString());
        System.out.println("stage.env");
    }

    private static BufferedImage stage(Scene s, BufferedImage bar, Font black, Font semi,
                                       File icon, boolean endcard) throws Exception {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        hints(g);

        g.setPaint(new GradientPaint(0, BAND_H, BG_TOP, 0, H, BG_BOTTOM));
        g.fillRect(0, 0, W, H);

        band(g, bar);

        boolean hasPhone = s.clip() != null;
        int textRight = hasPhone ? SCREEN_X - 130 : W - 160;
        int textLeft = hasPhone ? 130 : 160;
        int boxW = textRight - textLeft;

        if (hasPhone) {
            phoneShadow(g);
        }

        // Copy block, optically centred in the space below the band.
        List<String> head = wrap(g, s.headline(), black.deriveFont(84f), boxW);
        List<String> sub = wrap(g, s.subline(), semi.deriveFont(40f), boxW);
        double headLh = 100;
        double subLh = 56;
        double blockH = head.size() * headLh + 34 + sub.size() * subLh;
        double y = BAND_H + (H - BAND_H - blockH) / 2 + 70;

        if (endcard && icon != null && icon.exists()) {
            // The last frame is the one people screenshot or remember, so the mark carries it.
            BufferedImage ic = ImageIO.read(icon);
            int size = 200;
            g.drawImage(ic, textLeft, (int) y - 296, size, size, null);
        }

        g.setFont(black.deriveFont(84f));
        g.setColor(HEADLINE);
        for (String line : head) {
            g.drawString(line, textLeft, (float) y);
            y += headLh;
        }

        // A short accent rule between the two sizes -- it stops the subline reading as an
        // orphaned third line of the headline.
        g.setColor(ACCENT);
        g.fillRoundRect(textLeft, (int) y - 44, 92, 7, 4, 4);
        y += 34;

        g.setFont(semi.deriveFont(40f));
        g.setColor(SUBLINE);
        for (String line : sub) {
            g.drawString(line, textLeft, (float) y);
            y += subLh;
        }

        g.dispose();
        return im;
    }

    /**
     * The wavy brand band, with the same lift under its curve as the store screenshots.
     *
     * <p>Only the bottom of the bar is used: at 1920 wide the whole thing is 540 tall, which
     * would be half the frame. The top of the bar is flat colour, so cropping down to the
     * wave loses nothing but blue.
     */
    private static void band(Graphics2D g, BufferedImage bar) {
        int barW = W;
        int barH = (int) Math.round((double) bar.getHeight() * barW / bar.getWidth());
        BufferedImage scaled = new BufferedImage(barW, barH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = scaled.createGraphics();
        hints(bg);
        bg.drawImage(bar, 0, 0, barW, barH, null);
        bg.dispose();

        BufferedImage crop = scaled.getSubimage(0, barH - BAND_H, barW, BAND_H);

        // Same treatment as the screenshots: the bar's own alpha, dropped and blurred, so the
        // shadow follows the wave instead of ruling a straight line across the frame.
        BufferedImage shadow = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = shadow.createGraphics();
        hints(sg);
        sg.drawImage(crop, 0, 9, null);
        sg.dispose();
        g.drawImage(tint(shadow, 0x0A1628, 78, 8), 0, 0, null);
        g.drawImage(crop, 0, 0, null);
    }

    /**
     * A soft contact shadow so the phone sits on the stage rather than floating over it.
     *
     * <p>Kept deliberately tight. The first pass blurred 22px at alpha 92 and, because the
     * recording only covers the inner rectangle, everything outside the bezel read as a grey
     * blob around the phone rather than as a shadow under it. Most of a real contact shadow
     * is hidden by the object casting it, so the visible part is only ever a rim.
     */
    private static void phoneShadow(Graphics2D g) {
        BufferedImage s = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = s.createGraphics();
        hints(sg);
        sg.setColor(Color.BLACK);
        sg.fill(new RoundRectangle2D.Float(SCREEN_X - 13, SCREEN_Y - 13 + 14,
                SCREEN_W + 26, SCREEN_H + 26, SCREEN_R + 13, SCREEN_R + 13));
        sg.dispose();
        g.drawImage(tint(s, 0x16324F, 58, 12), 0, 0, null);
    }

    /**
     * The bezel laid over the recording.
     *
     * <p>It is a ring, not a plate: the recording is drawn as a hard-cornered rectangle and
     * the ring's inner edge is rounded, so the ring covers exactly the four square corners
     * that would otherwise give the phone away.
     */
    private static BufferedImage bezel() {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        hints(g);

        Area ring = new Area(new RoundRectangle2D.Float(SCREEN_X - 13, SCREEN_Y - 13,
                SCREEN_W + 26, SCREEN_H + 26, SCREEN_R + 13, SCREEN_R + 13));
        ring.subtract(new Area(new RoundRectangle2D.Float(SCREEN_X, SCREEN_Y,
                SCREEN_W, SCREEN_H, SCREEN_R, SCREEN_R)));
        g.setColor(BEZEL);
        g.fill(ring);

        // A hairline highlight down the bezel, which is what stops it reading as a flat
        // rectangle of black at video bitrates.
        g.setColor(new Color(255, 255, 255, 38));
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Float(SCREEN_X - 12, SCREEN_Y - 12,
                SCREEN_W + 24, SCREEN_H + 24, SCREEN_R + 12, SCREEN_R + 12));
        g.dispose();
        return im;
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Recolour a mask to one tint at one alpha, blurred -- the shadow primitive. */
    private static BufferedImage tint(BufferedImage mask, int rgb, int alpha, double sigma) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        float[] a = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                a[y * w + x] = (mask.getRGB(x, y) >>> 24) * (alpha / 255f);
            }
        }
        a = gauss(a, w, h, sigma);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < a.length; i++) {
            int v = Math.max(0, Math.min(255, Math.round(a[i])));
            if (v > 0) {
                out.setRGB(i % w, i / w, (v << 24) | rgb);
            }
        }
        return out;
    }

    private static float[] gauss(float[] src, int w, int h, double sigma) {
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

    private static List<String> wrap(Graphics2D g, String text, Font font, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            double w = new TextLayout(candidate, font, g.getFontRenderContext()).getAdvance();
            if (w > maxWidth && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static Font face(String path, int style) throws Exception {
        File f = new File(path);
        if (f.exists()) {
            return Font.createFont(Font.TRUETYPE_FONT, f).deriveFont(Font.PLAIN, 64f);
        }
        return new Font("SansSerif", style, 64);
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

    private VideoStage() {
    }
}
