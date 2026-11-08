package com.harudle.auth.infrastructure;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class CwebpProfileImageEncoder {

    private static final String CWEBP_COMMAND = "cwebp";
    private static final int WEBP_QUALITY = 80;
    private static final int COMPRESSION_METHOD = 6;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    public byte[] encode(BufferedImage image) {
        Path directory = null;

        try {
            directory = Files.createTempDirectory("harudle-profile-");

            Path input = directory.resolve("input.png");
            Path output = directory.resolve("profile.webp");

            if (!ImageIO.write(image, "png", input.toFile())) {
                throw new IOException("변환할 프로필 이미지를 준비할 수 없습니다.");
            }

            convert(input, output);
            byte[] bytes = Files.readAllBytes(output);
            validateWebp(bytes);

            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException("프로필 이미지 WebP 변환에 실패했습니다.", exception);
        } finally {
            if (directory != null) {
                deleteIfExists(directory.resolve("input.png"));
                deleteIfExists(directory.resolve("profile.webp"));
                deleteIfExists(directory);
            }
        }
    }

    private void convert(Path input, Path output) throws IOException {
        Process process = new ProcessBuilder(
                CWEBP_COMMAND,
                "-quiet",
                "-q", Integer.toString(WEBP_QUALITY),
                "-m", Integer.toString(COMPRESSION_METHOD),
                "-metadata", "none",
                input.toString(),
                "-o", output.toString()
        )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        try {
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("프로필 이미지 변환 제한 시간을 초과했습니다.");
            }

            if (process.exitValue() != 0) {
                throw new IOException("cwebp 변환에 실패했습니다. 종료 코드: " + process.exitValue());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("프로필 이미지 변환이 중단됐습니다.", exception);
        } finally {
            process.destroyForcibly();
        }
    }

    private void validateWebp(byte[] bytes) throws IOException {
        if (bytes.length < 12
                || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') {
            throw new IOException("변환 결과가 올바른 WebP 파일이 아닙니다.");
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 임시 파일 정리 실패는 원래 변환 오류를 가리지 않는다.
        }
    }
}
