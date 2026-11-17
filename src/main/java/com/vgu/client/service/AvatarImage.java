package com.vgu.client.service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Avatars are small square pictures. Whatever the user picks is cropped to a square from the middle
 * and scaled down before it is encrypted and uploaded, so every avatar is a few tens of kilobytes
 * instead of the full photo, and other people never download more than they need.
 */
public final class AvatarImage {

    /** The side of the picture that is stored and shared, in pixels. */
    public static final int SIZE = 256;

    /** Largest avatar we accept from somebody else, in bytes and in pixels per side. */
    public static final int MAX_RECEIVED_BYTES = 512 * 1024;
    public static final int MAX_RECEIVED_SIDE = 2048;

    private AvatarImage() {
    }

    /**
     * Crops to a centred square and scales to at most {@link #SIZE} pixels. Pictures with transparency stay
     * PNG, everything else becomes JPEG.
     *
     * @throws IllegalArgumentException if {@code raw} is not an image we can read
     */
    public static byte[] shrink(byte[] raw) {
        BufferedImage source = read(raw);
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage square = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);

        int target = Math.min(SIZE, side);
        boolean transparent = source.getColorModel().hasAlpha();
        BufferedImage scaled = new BufferedImage(target, target,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(square, 0, 0, target, target, null);
        g.dispose();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(scaled, transparent ? "png" : "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not process that image.", e);
        }
    }

    /** True if {@code image}, received from another person, is a readable picture of a sensible size. */
    public static boolean isAcceptable(byte[] image) {
        if (image == null || image.length == 0 || image.length > MAX_RECEIVED_BYTES) {
            return false;
        }
        try {
            BufferedImage decoded = read(image);
            return decoded.getWidth() <= MAX_RECEIVED_SIDE && decoded.getHeight() <= MAX_RECEIVED_SIDE;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static BufferedImage read(byte[] raw) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                throw new IllegalArgumentException("That file is not a readable image.");
            }
            return image;
        } catch (IOException e) {
            throw new IllegalArgumentException("That file is not a readable image.", e);
        }
    }
}
