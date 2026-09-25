package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.domain.ImageVariant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class CwebpImageVariantEncoderTest {

    @TempDir
    Path directory;

    @Test
    void createsTwoWebpVariantsFromPng() throws IOException {
        assumeTrue(commandAvailable("cwebp"));

        var variants = new CwebpImageVariantEncoder().encode(sourceImage());

        assertThat(variants).containsOnlyKeys(ImageVariant.DETAIL, ImageVariant.THUMBNAIL);
        assertThat(variants.get(ImageVariant.DETAIL).mediaType().toString()).isEqualTo("image/webp");
        assertThat(variants.get(ImageVariant.THUMBNAIL).mediaType().toString()).isEqualTo("image/webp");
        assertThat(variants.get(ImageVariant.DETAIL).resource().contentLength()).isPositive();
        assertThat(variants.get(ImageVariant.THUMBNAIL).resource().contentLength())
                .isPositive().isLessThan(variants.get(ImageVariant.DETAIL).resource().contentLength());
    }

    @Test
    void createsWebpVariantsAtExpectedDimensions() throws IOException, InterruptedException {
        assumeTrue(commandAvailable("cwebp"));
        assumeTrue(commandAvailable("dwebp"));

        var variants = new CwebpImageVariantEncoder().encode(sourceImage());

        assertDimensions(variants.get(ImageVariant.DETAIL), 960);
        assertDimensions(variants.get(ImageVariant.THUMBNAIL), 240);
    }

    @Test
    void includesBoundedCwebpErrorWhenInputIsInvalid() {
        assumeTrue(commandAvailable("cwebp"));

        GeneratedImage invalidImage = new GeneratedImage(
                new ByteArrayResource("not an image".getBytes(StandardCharsets.UTF_8)), MediaType.IMAGE_PNG
        );

        IllegalStateException exception = catchThrowableOfType(
                () -> new CwebpImageVariantEncoder().encode(invalidImage), IllegalStateException.class
        );

        assertThat(exception.getCause()).isInstanceOf(CwebpConversionException.class);
        assertThat(exception.getCause().getMessage())
                .contains("Cannot read input picture file")
                .contains("<input>")
                .doesNotContain("harudle-cwebp-");
    }

    private static GeneratedImage sourceImage() throws IOException {
        BufferedImage source = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 1024, 1024);
        graphics.setColor(Color.BLACK);
        graphics.drawLine(0, 0, 1023, 1023);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(source, "png", output);

        return new GeneratedImage(
                new ByteArrayResource(output.toByteArray()), MediaType.IMAGE_PNG
        );
    }

    private void assertDimensions(GeneratedImage image, int expectedSize) throws IOException, InterruptedException {
        Path webp = directory.resolve(expectedSize + ".webp");
        Path png = directory.resolve(expectedSize + ".png");
        Files.write(webp, image.resource().getContentAsByteArray());
        Process process = new ProcessBuilder("dwebp", webp.toString(), "-o", png.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            BufferedImage decoded = ImageIO.read(png.toFile());
            assertThat(decoded.getWidth()).isEqualTo(expectedSize);
            assertThat(decoded.getHeight()).isEqualTo(expectedSize);
        } finally {
            process.destroyForcibly();
        }
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
