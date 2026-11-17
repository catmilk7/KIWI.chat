package com.vgu.client.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AvatarImageTest {

    static byte[] image(int width, int height, int type, String format) throws Exception {
        return image(width, height, type, format, new Color(200, 30, 60));
    }

    /** A picture of random pixels, like a photo: it does not compress the way a flat colour does. */
    static byte[] noisy(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(1);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static byte[] image(int width, int height, int type, String format, Color color) throws Exception {
        BufferedImage img = new BufferedImage(width, height, type);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), type == BufferedImage.TYPE_INT_ARGB ? 128 : 255));
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void aBigWidePhotoBecomesASmallSquare() throws Exception {
        byte[] photo = noisy(1200, 600);

        byte[] avatar = AvatarImage.shrink(photo);

        BufferedImage result = decode(avatar);
        assertEquals(AvatarImage.SIZE, result.getWidth());
        assertEquals(AvatarImage.SIZE, result.getHeight());
        assertTrue(avatar.length < photo.length / 10, "should be far smaller than the original");
    }

    @Test
    void aSmallPictureIsNotBlownUp() throws Exception {
        BufferedImage result = decode(AvatarImage.shrink(image(100, 60, BufferedImage.TYPE_INT_RGB, "png")));
        assertEquals(60, result.getWidth()); // the centred square of a 100x60 picture
        assertEquals(60, result.getHeight());
    }

    @Test
    void transparencyIsKept() throws Exception {
        BufferedImage result = decode(AvatarImage.shrink(image(500, 500, BufferedImage.TYPE_INT_ARGB, "png")));
        assertTrue(result.getColorModel().hasAlpha());
    }

    @Test
    void somethingThatIsNotAnImageIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> AvatarImage.shrink("just text".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> AvatarImage.shrink(new byte[0]));
    }

    @Test
    void whatOtherPeopleSendUsMustBeAReasonablePicture() throws Exception {
        assertTrue(AvatarImage.isAcceptable(AvatarImage.shrink(image(800, 800, BufferedImage.TYPE_INT_RGB, "png"))));
        assertFalse(AvatarImage.isAcceptable("not an image".getBytes()));
        assertFalse(AvatarImage.isAcceptable(null));
        assertFalse(AvatarImage.isAcceptable(new byte[AvatarImage.MAX_RECEIVED_BYTES + 1]));
        assertFalse(AvatarImage.isAcceptable(image(AvatarImage.MAX_RECEIVED_SIDE + 1, 8, BufferedImage.TYPE_BYTE_GRAY, "png")));
    }
}
