import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw device screenshots into captioned Play Store artwork.
 *
 * <p>Two reasons this exists rather than uploading the screenshots directly:
 *
 * <ol>
 *   <li>Google Play rejects a phone screenshot whose long side is more than twice its short
 *       side. A modern handset is 1080x2400, which is 2.22:1, so every raw capture is
 *       refused. Compositing onto a 1080x1920 canvas (16:9) fixes that without cropping away
 *       any of the screen.
 *   <li>The store shows these at thumbnail size, where the app's own text is illegible. A
 *       caption is the only thing a browsing user actually reads.
 * </ol>
 *
 * <p>Usage: {@code java ShotFramer <out-dir> <in.png> <caption> [<in.png> <caption> ...]}
 */
public final class ShotFramer {

    /** 16:9 — comfortably inside Play's 2:1 limit, and the shape the store previews assume. */
    private static final int W = 1080;
    private static final int H = 1920;

    private static final Color BACKGROUND = new Color(0x0F1419);
    private static final Color CAPTION = new Color(0xFFFFFF);
    private static final Color ACCENT = new Color(0x4A9BE0);

    private static final int CAPTION_TOP = 96;
    private static final int SHOT_TOP = 286;
    private static final int SHOT_BOTTOM_MARGIN = 40;

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || (args.length - 1) % 2 != 0) {
            System.err.println("usage: ShotFramer <out-dir> <in.png> <caption> [...]");
            System.exit(2);
        }
        File outDir = new File(args[0]);
        outDir.mkdirs();

        int index = 1;
        for (int i = 1; i < args.length; i += 2) {
            File in = new File(args[i]);
            String caption = args[i + 1];
            File out = new File(outDir, String.format("%02d-%s", index,
                    in.getName().replaceFirst("^\\d+-", "")));
            frame(in, caption, out);
            System.out.println("wrote " + out.getName() + "  \"" + caption + "\"");
            index++;
        }
    }

    private static void frame(File in, String caption, File out) throws Exception {
        BufferedImage shot = ImageIO.read(in);
        BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(BACKGROUND);
        g.fillRect(0, 0, W, H);

        int captionBottom = drawCaption(g, caption);

        // Positioned from where the text actually ended rather than a fixed offset, so a
        // caption that wraps to two lines does not get struck through by the rule.
        g.setColor(ACCENT);
        g.fillRoundRect((W - 96) / 2, captionBottom + 26, 96, 6, 3, 3);

        // Scale to whatever height is left, so the whole screen stays visible. Cropping the
        // screenshot to fit would cut off the very rows that make the point.
        int available = H - SHOT_TOP - SHOT_BOTTOM_MARGIN;
        double scale = (double) available / shot.getHeight();
        int w = (int) Math.round(shot.getWidth() * scale);
        int x = (W - w) / 2;

        // Rounded corners, so it reads as a phone rather than a pasted rectangle.
        g.setClip(new RoundRectangle2D.Float(x, SHOT_TOP, w, available, 36, 36));
        g.drawImage(shot, x, SHOT_TOP, w, available, null);
        g.setClip(null);

        g.setColor(new Color(0xFF, 0xFF, 0xFF, 40));
        g.drawRoundRect(x, SHOT_TOP, w, available, 36, 36);

        g.dispose();
        ImageIO.write(canvas, "png", out);
    }

    /** @return the y of the lowest pixel the caption occupies */
    private static int drawCaption(Graphics2D g, String caption) {
        g.setFont(new Font("SansSerif", Font.BOLD, 54));

        List<String> lines = wrap(g, caption, W - 140);
        int y = CAPTION_TOP + g.getFontMetrics().getAscent();
        int bottom = y;
        for (String line : lines) {
            int lineWidth = g.getFontMetrics().stringWidth(line);
            g.setColor(CAPTION);
            g.drawString(line, (W - lineWidth) / 2, y);
            bottom = y + g.getFontMetrics().getDescent();
            y += g.getFontMetrics().getHeight();
        }
        return bottom;
    }

    private static List<String> wrap(Graphics2D g, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (g.getFontMetrics().stringWidth(candidate) > maxWidth && line.length() > 0) {
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

    private ShotFramer() {
    }
}
