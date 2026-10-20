package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.harudle.auth.infrastructure.CwebpProfileImageEncoder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProfileImageProcessorTest {

    private final CwebpProfileImageEncoder imageEncoder = mock(CwebpProfileImageEncoder.class);
    private final ProfileImageProcessor imageProcessor = new ProfileImageProcessor(imageEncoder);

    @TempDir
    Path directory;

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("파일이 없거나 비어 있으면 변환하지 않는다")
    void rejectsEmptyFile(byte[] bytes) {
        assertRejected(bytes, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("원본 파일이 5MiB를 넘으면 변환하지 않는다")
    void rejectsOversizedFile() {
        assertRejected(new byte[5 * 1024 * 1024 + 1], HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("JPEG와 PNG 외의 실제 이미지 형식은 거절한다")
    void rejectsGif() throws IOException {
        assertRejected(imageBytes("gif", 10, 10), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("이미지가 아닌 파일은 거절한다")
    void rejectsNonImage() {
        assertRejected("not an image".getBytes(StandardCharsets.UTF_8), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("PNG로 시작하지만 손상된 파일은 변환하지 않는다")
    void rejectsCorruptedPng() throws IOException {
        assertRejected(Arrays.copyOf(imageBytes("png", 10, 10), 20), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("2500만 픽셀을 넘는 사진은 전체 내용을 읽기 전에 거절한다")
    void rejectsExcessivePixelsBeforeDecoding() throws IOException {
        byte[] bytes = imageBytes("png", 10, 10);
        ByteBuffer.wrap(bytes).putInt(16, 5001).putInt(20, 5000);
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) checksum.getValue());

        assertRejected(bytes, HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({
            "jpeg, 1600, 800, 512, 512",
            "png, 800, 1600, 512, 512",
            "png, 200, 100, 100, 100",
            "png, 1024, 1024, 512, 512",
            "png, 1, 10000, 1, 1",
            "png, 2000, 300, 300, 300"
    })
    @DisplayName("정사각형으로 자른 뒤 최대 512픽셀의 WebP로 저장하고 작은 사진은 확대하지 않는다")
    void createsWebpAtExpectedDimensions(
            String format,
            int width,
            int height,
            int expectedWidth,
            int expectedHeight
    ) throws IOException, InterruptedException {
        requireWebpTools();
        ProfileImageProcessor processor = new ProfileImageProcessor(new CwebpProfileImageEncoder());

        byte[] webp = processor.process(imageBytes(format, width, height));
        BufferedImage decoded = decodeWebp(webp);

        assertThat(new String(webp, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(webp, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
        assertThat(decoded.getWidth()).isEqualTo(expectedWidth);
        assertThat(decoded.getHeight()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @CsvSource({"8, 3", "3, 8"})
    @DisplayName("가로와 세로 사진 모두 가운데 내용을 늘리거나 줄이지 않고 정사각형으로 자른다")
    void cropsCenterWithoutDistortion(int width, int height) throws IOException {
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                source.setRGB(x, y, (x * 20 << 16) | y * 20);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(source, "png", output)).isTrue();

        imageProcessor.process(output.toByteArray());

        ArgumentCaptor<BufferedImage> captor = ArgumentCaptor.forClass(BufferedImage.class);
        verify(imageEncoder).encode(captor.capture());
        BufferedImage cropped = captor.getValue();

        assertThat(cropped.getWidth()).isEqualTo(3);
        assertThat(cropped.getHeight()).isEqualTo(3);
        assertThat(cropped.getRGB(0, 0)).isEqualTo(source.getRGB((width - 3) / 2, (height - 3) / 2));
        assertThat(cropped.getRGB(2, 2)).isEqualTo(source.getRGB((width - 3) / 2 + 2, (height - 3) / 2 + 2));
    }

    @Test
    @DisplayName("PNG의 투명도를 WebP 변환 후에도 유지한다")
    void preservesTransparency() throws IOException, InterruptedException {
        requireWebpTools();
        BufferedImage source = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(50, 0, 0x80FF0000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(source, "png", output)).isTrue();
        ProfileImageProcessor processor = new ProfileImageProcessor(new CwebpProfileImageEncoder());

        BufferedImage decoded = decodeWebp(processor.process(output.toByteArray()));

        assertThat(decoded.getRGB(0, 0) >>> 24).isEqualTo(128);
        assertThat(decoded.getRGB(1, 1) >>> 24).isZero();
    }

    private void assertRejected(byte[] bytes, HttpStatus status) {
        ResponseStatusException exception = catchThrowableOfType(
                () -> imageProcessor.process(bytes), ResponseStatusException.class
        );

        assertThat(exception).isNotNull();
        assertThat(exception.getStatusCode()).isEqualTo(status);
        verifyNoInteractions(imageEncoder);
    }

    private byte[] imageBytes(String format, int width, int height) throws IOException {
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(source, format, output)).isTrue();
        return output.toByteArray();
    }

    private BufferedImage decodeWebp(byte[] bytes) throws IOException, InterruptedException {
        Path webp = directory.resolve("profile.webp");
        Path png = directory.resolve("decoded.png");
        Files.write(webp, bytes);
        Process process = new ProcessBuilder("dwebp", webp.toString(), "-o", png.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            BufferedImage decoded = ImageIO.read(png.toFile());
            assertThat(decoded).isNotNull();
            return decoded;
        } finally {
            process.destroyForcibly();
        }
    }

    private void requireWebpTools() throws IOException, InterruptedException {
        assumeTrue(commandAvailable("cwebp"));
        assumeTrue(commandAvailable("dwebp"));
    }

    private boolean commandAvailable(String command) throws IOException, InterruptedException {
        Process process;

        try {
            process = new ProcessBuilder(command, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException exception) {
            return false;
        }

        try {
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } finally {
            process.destroyForcibly();
        }
    }
}
