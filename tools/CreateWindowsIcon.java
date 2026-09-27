import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds a multi-resolution Windows ICO containing PNG-compressed images from a source PNG. */
public final class CreateWindowsIcon {
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private CreateWindowsIcon() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: CreateWindowsIcon <source.png> <target.ico>");
        }

        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        if (source == null) {
            throw new IOException("The application icon is not a readable image: " + args[0]);
        }

        List<byte[]> images = new ArrayList<>(SIZES.length);
        for (int size : SIZES) {
            images.add(createPng(source, size));
        }

        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            writeShortLittleEndian(output, 0);
            writeShortLittleEndian(output, 1);
            writeShortLittleEndian(output, images.size());

            int imageOffset = 6 + images.size() * 16;
            for (int index = 0; index < SIZES.length; index++) {
                int size = SIZES[index];
                byte[] image = images.get(index);
                output.writeByte(size == 256 ? 0 : size);
                output.writeByte(size == 256 ? 0 : size);
                output.writeByte(0);
                output.writeByte(0);
                writeShortLittleEndian(output, 1);
                writeShortLittleEndian(output, 32);
                writeIntLittleEndian(output, image.length);
                writeIntLittleEndian(output, imageOffset);
                imageOffset += image.length;
            }

            for (byte[] image : images) {
                output.write(image);
            }
        }
    }

    private static byte[] createPng(BufferedImage source, int size) throws IOException {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, size, size, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, "png", bytes)) {
            throw new IOException("No PNG writer is available.");
        }
        return bytes.toByteArray();
    }

    private static void writeShortLittleEndian(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
    }

    private static void writeIntLittleEndian(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
        output.writeByte((value >>> 16) & 0xff);
        output.writeByte((value >>> 24) & 0xff);
    }
}
