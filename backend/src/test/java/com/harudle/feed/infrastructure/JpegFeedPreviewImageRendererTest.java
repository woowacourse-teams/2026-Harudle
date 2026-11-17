package com.harudle.feed.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class JpegFeedPreviewImageRendererTest {

    private final JpegFeedPreviewImageRenderer renderer = new JpegFeedPreviewImageRenderer();

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({
            "png,400,400,285,0,630,630",
            "jpeg,400,400,285,0,630,630",
            "png,800,200,0,165,1200,300",
            "jpeg,800,200,0,165,1200,300",
            "png,200,800,521,0,158,630",
            "jpeg,200,800,521,0,158,630"
    })
    void fitsWholeImageIncludingAllFourCornersWithoutCropping(String format, int width, int height,
            int x, int y, int renderedWidth, int renderedHeight) throws IOException {
        ReferenceImage source = source(cornerMarkers(width, height, false), format);

        ReferenceImage result = renderer.render(source);

        BufferedImage preview = jpeg(result);
        assertCorners(preview, x, y, renderedWidth, renderedHeight);
        if (x > 0) {
            assertColor(preview, 0, 315, Color.WHITE);
            assertColor(preview, 1199, 315, Color.WHITE);
        }
        if (y > 0) {
            assertColor(preview, 600, 0, Color.WHITE);
            assertColor(preview, 600, 629, Color.WHITE);
        }
    }

    @Test
    void flattensTransparentPngOntoWhite() throws IOException {
        BufferedImage transparent = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);

        BufferedImage preview = jpeg(renderer.render(source(transparent, "png")));

        assertColor(preview, 600, 315, Color.WHITE);
        assertColor(preview, 0, 315, Color.WHITE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lossy", "lossless", "alpha"})
    void convertsRealWebpWithLossyLosslessOrExtendedAlphaHeader(String variant) throws Exception {
        assumeTrue(commandAvailable("cwebp") && commandAvailable("dwebp"),
                "실제 WebP 검증에는 cwebp와 dwebp가 필요합니다.");
        Path png = directory.resolve("source.png");
        Path webp = directory.resolve("source.webp");
        assertThat(ImageIO.write(cornerMarkers(400, 400, variant.equals("alpha")), "png", png.toFile()))
                .isTrue();
        List<String> command = new ArrayList<>(List.of("cwebp", "-quiet"));
        if (variant.equals("lossless")) {
            command.add("-lossless");
        }
        command.addAll(List.of(png.toString(), "-o", webp.toString()));
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            process.destroyForcibly();
        }

        ReferenceImage result = renderer.render(new ReferenceImage(
                new ByteArrayResource(Files.readAllBytes(webp)), MediaType.parseMediaType("image/webp")));

        BufferedImage preview = jpeg(result);
        assertCorners(preview, 285, 0, 630, 630);
        assertColor(preview, 0, 315, Color.WHITE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "image/jpeg", "image/webp"})
    void rejectsBrokenPayloadWithoutLeakingItsContents(String mediaType) {
        ReferenceImage source = new ReferenceImage(new ByteArrayResource(
                "not an image; private-storage-address".getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType(mediaType));

        assertThatThrownBy(() -> renderer.render(source))
                .isInstanceOf(ImageStorageException.class)
                .hasMessage("공유 미리보기 이미지를 변환하지 못했습니다.")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "image/gif"})
    void rejectsUnsupportedImageTypesEvenWhenPayloadIsPng(String mediaType) throws IOException {
        byte[] png = source(cornerMarkers(20, 20, false), "png").resource().getContentAsByteArray();

        assertThatThrownBy(() -> renderer.render(new ReferenceImage(new ByteArrayResource(png),
                MediaType.parseMediaType(mediaType))))
                .isInstanceOf(ImageStorageException.class)
                .hasNoCause();
    }

    @Test
    void rejectsVideoPayloadPretendingToBePng() {
        byte[] mp4Header = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};

        assertThatThrownBy(() -> renderer.render(new ReferenceImage(
                new ByteArrayResource(mp4Header), MediaType.IMAGE_PNG)))
                .isInstanceOf(ImageStorageException.class)
                .hasNoCause();
    }

    @Test
    void rejectsMismatchBetweenDeclaredFormatAndImageBytes() throws IOException {
        byte[] png = source(cornerMarkers(20, 20, false), "png").resource().getContentAsByteArray();

        assertThatThrownBy(() -> renderer.render(new ReferenceImage(
                new ByteArrayResource(png), MediaType.IMAGE_JPEG)))
                .isInstanceOf(ImageStorageException.class);
    }

    @Test
    void rejectsInputLargerThanTwentyMebibytes() {
        byte[] oversized = new byte[20 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> renderer.render(new ReferenceImage(
                new ByteArrayResource(oversized), MediaType.IMAGE_PNG)))
                .isInstanceOf(ImageStorageException.class)
                .hasMessage("공유 미리보기 원본은 20MiB 이하여야 합니다.");
    }

    @Test
    void rejectsOversizedPngDimensionsBeforeReadingPixelData() throws IOException {
        byte[] png = source(cornerMarkers(20, 20, false), "png").resource().getContentAsByteArray();
        ByteBuffer.wrap(png, 16, 8).putInt(6000).putInt(6000);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());

        assertThatThrownBy(() -> renderer.render(new ReferenceImage(
                new ByteArrayResource(png), MediaType.IMAGE_PNG)))
                .isInstanceOf(ImageStorageException.class)
                .hasMessage("공유 미리보기 이미지 크기가 제한을 초과했습니다.");
    }

    @Test
    void rejectsOversizedWebpCanvasBeforeStartingNativeDecoder() {
        ByteBuffer webp = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        webp.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22)
                .put("WEBPVP8X".getBytes(StandardCharsets.US_ASCII)).putInt(10)
                .putInt(0).put(new byte[]{0x6f, 0x17, 0, 0x6f, 0x17, 0}); // 6000 x 6000.

        assertThatThrownBy(() -> renderer.render(new ReferenceImage(new ByteArrayResource(webp.array()),
                MediaType.parseMediaType("image/webp"))))
                .isInstanceOf(ImageStorageException.class)
                .hasMessage("공유 미리보기 이미지 크기가 제한을 초과했습니다.");
    }

    @Test
    void hidesResourceFailureDetails() {
        AbstractResource unreadable = new AbstractResource() {
            @Override
            public String getDescription() {
                return "private-storage-address";
            }

            @Override
            public boolean isReadable() {
                return true;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("private-storage-address?secret=must-not-leak");
            }
        };

        assertThatThrownBy(() -> renderer.render(new ReferenceImage(unreadable, MediaType.IMAGE_PNG)))
                .isInstanceOf(ImageStorageException.class)
                .hasMessage("공유 미리보기 이미지를 변환하지 못했습니다.")
                .hasNoCause();
    }

    @Test
    void preservesThreadInterruption() throws IOException {
        ReferenceImage source = source(cornerMarkers(20, 20, false), "png");
        boolean previouslyInterrupted = Thread.currentThread().isInterrupted();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> renderer.render(source)).isInstanceOf(ImageStorageException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            if (previouslyInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ReferenceImage source(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return new ReferenceImage(new ByteArrayResource(output.toByteArray()),
                format.equals("png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG);
    }

    private static BufferedImage cornerMarkers(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            int markerWidth = width / 10;
            int markerHeight = height / 10;
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, markerWidth, markerHeight);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(width - markerWidth, 0, markerWidth, markerHeight);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, height - markerHeight, markerWidth, markerHeight);
            graphics.setColor(Color.YELLOW);
            graphics.fillRect(width - markerWidth, height - markerHeight, markerWidth, markerHeight);
        } finally {
            graphics.dispose();
        }
        if (alpha) {
            image.setRGB(width / 2, height / 2, 0);
        }
        return image;
    }

    private static BufferedImage jpeg(ReferenceImage result) throws IOException {
        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE_JPEG);
        byte[] bytes = result.resource().getContentAsByteArray();
        assertThat(bytes[0] & 0xff).isEqualTo(0xff);
        assertThat(bytes[1] & 0xff).isEqualTo(0xd8);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(1200);
        assertThat(decoded.getHeight()).isEqualTo(630);
        return decoded;
    }

    private static void assertCorners(BufferedImage image, int x, int y, int width, int height) {
        int insetX = width / 20;
        int insetY = height / 20;
        assertColor(image, x + insetX, y + insetY, Color.RED);
        assertColor(image, x + width - insetX - 1, y + insetY, Color.GREEN);
        assertColor(image, x + insetX, y + height - insetY - 1, Color.BLUE);
        assertColor(image, x + width - insetX - 1, y + height - insetY - 1, Color.YELLOW);
    }

    private static void assertColor(BufferedImage image, int x, int y, Color expected) {
        Color actual = new Color(image.getRGB(x, y));
        assertThat(Math.abs(actual.getRed() - expected.getRed())).isLessThan(35);
        assertThat(Math.abs(actual.getGreen() - expected.getGreen())).isLessThan(35);
        assertThat(Math.abs(actual.getBlue() - expected.getBlue())).isLessThan(35);
    }

    private static boolean commandAvailable(String command) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(command, "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException exception) {
            return false;
        }
        try {
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } finally {
            process.destroyForcibly();
        }
    }
}
