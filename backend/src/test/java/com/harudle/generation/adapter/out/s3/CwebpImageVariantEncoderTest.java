package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class CwebpImageVariantEncoderTest {

    @Test
    void createsTwoWebpVariantsFromPng() throws IOException {
        assumeTrue(commandAvailable("cwebp"));
        BufferedImage source = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 1024, 1024);
        graphics.setColor(Color.BLACK);
        graphics.drawLine(0, 0, 1023, 1023);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(source, "png", output);

        CwebpImageVariantEncoder encoder = new CwebpImageVariantEncoder();
        var variants = encoder.encode(new GeneratedImage(
                new ByteArrayResource(output.toByteArray()), MediaType.IMAGE_PNG
        ));

        assertThat(variants.detail().mediaType().toString()).isEqualTo("image/webp");
        assertThat(variants.thumbnail().mediaType().toString()).isEqualTo("image/webp");
        assertThat(variants.detail().resource().contentLength()).isPositive();
        assertThat(variants.thumbnail().resource().contentLength())
                .isPositive().isLessThan(variants.detail().resource().contentLength());
    }

    private static boolean commandAvailable(String name) {
        try {
            return new ProcessBuilder(name, "-version").start().waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
