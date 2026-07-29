import javax.imageio.ImageIO;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Renders the Play Store icon and feature graphic from the app's own launcher artwork.
 *
 * <p>The launcher icon is an adaptive icon: a vector foreground over a solid background, sized
 * in a 108x108 viewport. Play wants a flat 512x512 PNG and a 1024x500 banner, neither of which
 * Android will produce for you. Redrawing the same shapes here keeps the store and the home
 * screen showing the same mark — a screenshot of the launcher would be the wrong size, and
 * tracing it by hand would drift.
 *
 * <p>The geometry below mirrors {@code res/drawable/ic_launcher_foreground.xml} exactly; the
 * 108-unit viewport is scaled to whatever the target size is.
 *
 * <p>Usage: {@code java StoreArtGen <out-dir>}
 */
public final class StoreArtGen {

    private static final Color BRAND = new Color(0x1B5E9C);
    private static final Color BRAND_DEEP = new Color(0x123F68);
    private static final Color WHITE = Color.WHITE;

    /** The adaptive icon's design viewport. */
    private static final double VIEWPORT = 108d;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: StoreArtGen <out-dir>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        dir.mkdirs();

        writeIcon(new File(dir, "icon-512.png"), 512);
        writeFeatureGraphic(new File(dir, "feature-graphic-1024x500.png"));
        System.out.println("wrote icon-512.png and feature-graphic-1024x500.png to " + dir);
    }

    // --- store icon ---

    private static void writeIcon(File out, int size) throws Exception {
        // No alpha: Play rejects a store icon with transparency and rounds the corners itself.
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = quality(img.createGraphics());

        g.setColor(BRAND);
        g.fillRect(0, 0, size, size);

        double scale = size / VIEWPORT;
        g.transform(AffineTransform.getScaleInstance(scale, scale));
        drawCards(g);

        g.dispose();
        ImageIO.write(img, "png", out);
    }

    /**
     * Two overlapping gift cards — the whole idea of the app in one mark.
     *
     * <p>Coordinates are in the 108-unit viewport, matching the vector drawable.
     */
    private static void drawCards(Graphics2D g) {
        // The card behind, offset up and left, at partial opacity.
        g.setColor(new Color(255, 255, 255, (int) Math.round(0.55 * 255)));
        g.fill(new RoundRectangle2D.Double(31, 38, 54, 34, 10, 10));

        // The card in front.
        g.setColor(WHITE);
        g.fill(new RoundRectangle2D.Double(23, 40, 54, 34, 10, 10));

        // Magnetic stripe and the suggestion of an embossed number.
        g.setColor(BRAND);
        g.fill(new RoundRectangle2D.Double(23, 50, 54, 7, 0, 0));
        g.fill(new RoundRectangle2D.Double(30, 63, 18, 4, 4, 4));
    }

    // --- feature graphic ---

    private static void writeFeatureGraphic(File out) throws Exception {
        int w = 1024;
        int h = 500;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = quality(img.createGraphics());

        g.setPaint(new GradientPaint(0, 0, BRAND_DEEP, w, h, BRAND));
        g.fillRect(0, 0, w, h);

        // The mark, sized to the banner and set on the left.
        Graphics2D mark = (Graphics2D) g.create();
        double scale = 300d / VIEWPORT;
        mark.translate(90, h / 2d - 150);
        mark.scale(scale, scale);
        drawCards(mark);
        mark.dispose();

        g.setColor(WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 86));
        g.drawString("MyCards", 420, 232);

        g.setColor(new Color(255, 255, 255, 215));
        g.setFont(new Font("SansSerif", Font.PLAIN, 40));
        g.drawString("Which gift card works here?", 424, 296);

        // A rule to anchor the type block rather than leaving it floating.
        g.setColor(new Color(255, 255, 255, 90));
        g.setStroke(new BasicStroke(3));
        g.drawLine(424, 330, 424 + 300, 330);

        g.dispose();
        ImageIO.write(img, "png", out);
    }

    private static Graphics2D quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private StoreArtGen() {
    }
}
