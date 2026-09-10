import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Builds every icon the app and the listing need, from one piece of source artwork.
 *
 * <p>Replaces the icon half of {@link StoreArtGen}, which drew the old two-card mark as Java2D
 * geometry mirroring {@code res/drawable/ic_launcher_foreground.xml}. That worked while the
 * mark was simple enough to express as a dozen rounded rectangles. The current artwork is a
 * rendered image with gradients, a glow and a ribbon, so it is the source of truth now and
 * everything else is derived from it -- which is the point: the launcher icon, the 512 Play
 * icon and the feature graphic cannot drift apart if they are all cut from the same file.
 *
 * <p>Usage: {@code java IconGen.java <artwork.png> <repo-root>}
 */
public final class IconGen {

    /**
     * Android adaptive icons are 108dp square, of which only the central 72dp is ever shown
     * and only the central 66dp circle is guaranteed by every launcher mask. The artwork is
     * therefore scaled to sit inside that circle rather than filling the layer.
     */
    private static final int VIEWPORT_DP = 108;
    private static final double SAFE_FRACTION = 60.0 / VIEWPORT_DP;

    /** mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi -- 108dp at 1x, 1.5x, 2x, 3x, 4x. */
    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final int[] SIZES = {108, 162, 216, 324, 432};

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java IconGen.java <artwork.png> <repo-root>");
            System.exit(2);
        }
        BufferedImage art = ImageIO.read(new File(args[0]));
        File repo = new File(args[1]);

        BufferedImage fg = cutOutBackground(art);
        int[] box = alphaBounds(fg);
        System.out.printf("artwork %dx%d, mark at x %d..%d y %d..%d%n",
                art.getWidth(), art.getHeight(), box[0], box[2], box[1], box[3]);

        File res = new File(repo, "app/src/main/res");
        for (int i = 0; i < DENSITIES.length; i++) {
            File dir = new File(res, "mipmap-" + DENSITIES[i]);
            dir.mkdirs();
            int px = SIZES[i];
            write(layer(fg, box, px), new File(dir, "ic_launcher_foreground.png"));
            write(background(art, px), new File(dir, "ic_launcher_background.png"));
            write(monochrome(fg, box, px), new File(dir, "ic_launcher_monochrome.png"));
        }
        System.out.println("wrote foreground/background/monochrome at " + DENSITIES.length
                + " densities");

        // ⚠️ Play refuses an alpha channel on the 512, and dropping alpha rather than
        // compositing it leaves a fringe on every antialiased edge. Flatten onto the artwork's
        // own corner colour so the edges stay clean.
        write(flatten(scale(art, 512, 512)), new File(repo, "play/icon-512.png"));
        System.out.println("wrote play/icon-512.png (flattened, no alpha)");

        // ⚠️ NOT play/feature-graphic-1024x500.png. THE SHIPPED BANNER IS HAND-PICKED.
        //
        // This used to write straight over it, which would have silently replaced the artwork
        // actually live on the Play listing the next time anyone regenerated an icon. What
        // ships is a hand-tuned variant of what this produces -- flatter field, different text
        // placement -- so the generated one goes to its own path as a starting point, and
        // swapping it in is a deliberate copy.
        write(flatten(featureGraphic(art)),
                new File(repo, "play/feature-graphic-generated.png"));
        System.out.println("wrote play/feature-graphic-generated.png"
                + "  (a starting point -- the shipped banner is hand-picked, see STORE-LISTING.md)");
    }

    /**
     * Make the background transparent, leaving the mark.
     *
     * <p><b>Flood fill from the corners, never a colour key.</b> The front card is blue and so
     * is the background; keying "everything blue" punches holes straight through the card. A
     * fill that only spreads through pixels connected to the edge cannot reach inside the
     * mark, whatever colour the mark happens to be.
     */
    static BufferedImage cutOutBackground(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);

        int seed = src.getRGB(0, 0);
        boolean[] bg = new boolean[w * h];
        Deque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            queue.add(new int[]{x, 0});
            queue.add(new int[]{x, h - 1});
        }
        for (int y = 0; y < h; y++) {
            queue.add(new int[]{0, y});
            queue.add(new int[]{w - 1, y});
        }

        // ⚠️ TIGHT, BECAUSE THIS TOLERANCE IS PER STEP, NOT AGAINST THE SEED. Each pixel is
        // compared with an already-accepted neighbour so the fill can follow the field's
        // gradient and glow across the whole canvas. That also means a loose tolerance creeps:
        // at 120 it stepped through the antialiased pixels of the white outline (~135 per pixel
        // there) and then spread freely inside the cards, whose own gradients are smooth --
        // erasing the entire mark and leaving a transparent layer that still looked plausible
        // in a file listing. The field itself only steps 1-3 per pixel, so 30 follows it
        // everywhere and cannot cross the outline.
        final int tolerance = 30;
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int x = p[0];
            int y = p[1];
            if (x < 0 || y < 0 || x >= w || y >= h || bg[y * w + x]) {
                continue;
            }
            if (distance(src.getRGB(x, y), neighbourSeed(src, bg, w, h, x, y, seed)) > tolerance) {
                continue;
            }
            bg[y * w + x] = true;
            queue.add(new int[]{x + 1, y});
            queue.add(new int[]{x - 1, y});
            queue.add(new int[]{x, y + 1});
            queue.add(new int[]{x, y - 1});
        }
        for (int i = 0; i < bg.length; i++) {
            if (bg[i]) {
                out.setRGB(i % w, i / w, 0x00000000);
            }
        }
        return out;
    }

    /** Compare against an already-accepted neighbour so the fill can track a gradient. */
    private static int neighbourSeed(BufferedImage src, boolean[] bg, int w, int h,
                                     int x, int y, int fallback) {
        int[][] around = {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}};
        for (int[] n : around) {
            if (n[0] >= 0 && n[1] >= 0 && n[0] < w && n[1] < h && bg[n[1] * w + n[0]]) {
                return src.getRGB(n[0], n[1]);
            }
        }
        return fallback;
    }

    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    static int[] alphaBounds(BufferedImage img) {
        int minX = img.getWidth();
        int minY = img.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) > 24) {
                    if (x < minX) { minX = x; }
                    if (y < minY) { minY = y; }
                    if (x > maxX) { maxX = x; }
                    if (y > maxY) { maxY = y; }
                }
            }
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    /** The mark, centred in a 108dp layer and scaled to sit inside the safe circle. */
    private static BufferedImage layer(BufferedImage fg, int[] box, int px) {
        int bw = box[2] - box[0] + 1;
        int bh = box[3] - box[1] + 1;
        double scale = SAFE_FRACTION * px / Math.max(bw, bh);
        int w = (int) Math.round(bw * scale);
        int h = (int) Math.round(bh * scale);

        BufferedImage out = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        hints(g);
        g.drawImage(fg.getSubimage(box[0], box[1], bw, bh),
                (px - w) / 2, (px - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    /**
     * The field, full bleed.
     *
     * <p>Sampled from the artwork's own background rather than reproduced as a gradient
     * drawable: the field carries a soft glow behind the mark that a two-stop gradient cannot
     * express, and the whole point of this tool is that there is one source.
     */
    private static BufferedImage background(BufferedImage art, int px) {
        return scale(art, px, px);
    }

    /**
     * The themed-icon layer: a flat silhouette of the mark.
     *
     * <p>⚠️ NOT the colour artwork. Android tints whatever it finds here to a single colour
     * against the user's wallpaper palette, so handing it a gradient image produces a flat
     * blob with the card edges invisible. The silhouette is drawn from the alpha channel, and
     * it is the union of the three cards, which is the shape a monochrome icon wants.
     */
    private static BufferedImage monochrome(BufferedImage fg, int[] box, int px) {
        BufferedImage placed = layer(fg, box, px);
        BufferedImage out = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int a = placed.getRGB(x, y) >>> 24;
                out.setRGB(x, y, (a << 24) | 0x00FFFFFF);
            }
        }
        return out;
    }

    /**
     * The 1024x500 banner: the field stretched wide, the mark left of centre.
     *
     * <p>⚠️ Play drops a play button DEAD CENTRE whenever a promo video is attached, and this
     * listing has one. Anything centred is covered by it.
     */
    private static BufferedImage featureGraphic(BufferedImage art) throws Exception {
        int w = 1024;
        int h = 500;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        hints(g);

        // ⚠️ THE FIELD ONLY, NOT THE WHOLE ARTWORK. Cover-cropping the artwork here paints the
        // card stack into the background, and then the icon tile below lands on top of it --
        // two marks on one banner, one of them stretched. A column from outside the mark's
        // bounding box is pure field, so stretching that keeps the vertical gradient and
        // brings nothing else with it.
        BufferedImage strip = art.getSubimage(4, 0, 24, art.getHeight());
        g.drawImage(strip, 0, 0, w, h, null);

        // The icon as an icon: a rounded tile, the way it appears on a home screen. Square
        // would read as a pasted rectangle of blue on a blue banner.
        int tile = 232;
        int tileX = 64;
        int tileY = (h - tile) / 2;
        RoundRectangle2D.Float shape =
                new RoundRectangle2D.Float(tileX, tileY, tile, tile, tile * 0.44f, tile * 0.44f);
        java.awt.Shape clip = g.getClip();
        g.setClip(shape);
        g.drawImage(art, tileX, tileY, tile, tile, null);
        g.setClip(clip);

        // ⚠️ THE TILE NEEDS AN EDGE. Its own field is the same blue as the banner, so without
        // one the rounded corner is invisible and the icon reads as a soft glow with cards
        // floating in it rather than as an app icon. A hairline in the cards' own white ties
        // it to the mark instead of looking like a keyline.
        g.setStroke(new java.awt.BasicStroke(3f));
        g.setColor(new Color(255, 255, 255, 150));
        g.draw(shape);

        // ⚠️ EVERYTHING STAYS LEFT OF CENTRE. Play lays a play button dead centre whenever a
        // promo video is attached, and this listing has one, so the name cannot live in the
        // middle. Tile plus text end well before x=512.
        Font black = face("C:/Windows/Fonts/seguibl.ttf", Font.BOLD, 62f);
        Font semi = face("C:/Windows/Fonts/seguisb.ttf", Font.PLAIN, 27f);
        int textX = tileX + tile + 40;

        g.setFont(black);
        g.setColor(Color.WHITE);
        g.drawString("MyGiftCards", textX, 246);

        g.setColor(new Color(0xD7E3F8));
        g.setFont(semi);
        g.drawString("Which gift card works here?", textX + 3, 296);

        g.setColor(new Color(255, 255, 255, 70));
        g.fillRect(textX + 3, 322, 250, 3);

        g.dispose();
        return out;
    }

    private static Font face(String path, int style, float size) throws Exception {
        File f = new File(path);
        if (f.exists()) {
            return Font.createFont(Font.TRUETYPE_FONT, f).deriveFont(Font.PLAIN, size);
        }
        return new Font("SansSerif", style, (int) size);
    }

    private static BufferedImage flatten(BufferedImage src) {
        BufferedImage out = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(src.getRGB(0, 0)));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Halve repeatedly, then land the last step bilinear -- see StoreShots.scale. */
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

    private static void write(BufferedImage img, File f) throws Exception {
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private IconGen() {
    }
}
