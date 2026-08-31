import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates the exact 16x16-per-frame Minecraft animation atlas for the rainbow item. */
public final class GenerateRainbowTexture {
    private static final int SIZE = 16;
    private static final int[] COLORS = {
            0xF04444, 0xF28B35, 0xF3D84A,
            0x62D85B, 0x43D7C6, 0x4A9FF2,
            0x6758E8, 0xA35BE5, 0xE65BAE
    };

    private GenerateRainbowTexture() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Expected output PNG path");
        BufferedImage atlas = new BufferedImage(SIZE, SIZE * COLORS.length, BufferedImage.TYPE_INT_ARGB);
        for (int frame = 0; frame < COLORS.length; frame++) drawFrame(atlas, frame, COLORS[frame]);
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        if (!ImageIO.write(atlas, "png", output.toFile())) throw new IOException("PNG writer unavailable");
    }

    private static void drawFrame(BufferedImage atlas, int frame, int rgb) {
        Color base = new Color(rgb);
        int offsetY = frame * SIZE;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean edge = x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1;
                boolean innerEdge = x == 1 || y == 1 || x == SIZE - 2 || y == SIZE - 2;
                float brightness = 0.78f + (((x * 5 + y * 3) & 3) * 0.035f);
                int alpha = 112;
                if (edge) {
                    brightness = (x == 0 || y == 0) ? 1.18f : 0.52f;
                    alpha = 238;
                } else if (innerEdge) {
                    brightness = (x <= 1 || y <= 1) ? 1.08f : 0.68f;
                    alpha = 178;
                }
                if ((x == 3 && y >= 3 && y <= 7)
                        || (y == 3 && x >= 3 && x <= 8)
                        || (x == 5 && y == 5)
                        || (x == 11 && y >= 9 && y <= 12)
                        || (y == 11 && x >= 9 && x <= 12)) {
                    brightness = 1.38f;
                    alpha = 218;
                }
                int red = clamp(Math.round(base.getRed() * brightness));
                int green = clamp(Math.round(base.getGreen() * brightness));
                int blue = clamp(Math.round(base.getBlue() * brightness));
                atlas.setRGB(x, offsetY + y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
