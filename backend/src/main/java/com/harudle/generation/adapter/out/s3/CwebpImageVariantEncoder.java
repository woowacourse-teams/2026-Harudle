package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.domain.ImageVariant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

public final class CwebpImageVariantEncoder implements ImageVariantEncoder {

    private static final MediaType WEBP = MediaType.parseMediaType("image/webp");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int WEBP_QUALITY = 80;
    private static final int COMPRESSION_METHOD = 6;

    @Override
    public Map<ImageVariant, GeneratedImage> encode(GeneratedImage image) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("harudle-cwebp-");
            Path input = directory.resolve("input");
            Files.write(input, image.resource().getContentAsByteArray());
            Map<ImageVariant, GeneratedImage> images = new EnumMap<>(ImageVariant.class);
            for (ImageVariant variant : ImageVariant.values()) {
                images.put(variant, convert(input, directory.resolve(variant.filename()), variant.width()));
            }
            return Map.copyOf(images);
        } catch (IOException exception) {
            throw new IllegalStateException("생성 이미지 WebP 변환에 실패했습니다.", exception);
        } finally {
            if (directory != null) {
                deleteIfExists(directory.resolve("input"));
                for (ImageVariant variant : ImageVariant.values()) {
                    deleteIfExists(directory.resolve(variant.filename()));
                }
                deleteIfExists(directory);
            }
        }
    }

    private static GeneratedImage convert(Path input, Path output, int size) throws IOException {
        Process process = new ProcessBuilder(
                "cwebp", "-quiet", "-q", Integer.toString(WEBP_QUALITY),
                "-m", Integer.toString(COMPRESSION_METHOD), "-resize", Integer.toString(size), "0",
                input.toString(), "-o", output.toString()
        ).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("cwebp 변환 제한 시간을 초과했습니다.");
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("cwebp 변환이 중단됐습니다.", exception);
        }
        if (process.exitValue() != 0) {
            throw new IOException("cwebp 변환 프로세스가 실패했습니다 (exit=" + process.exitValue() + ").");
        }
        byte[] bytes = Files.readAllBytes(output);
        if (bytes.length < 12 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F'
                || bytes[3] != 'F' || bytes[8] != 'W' || bytes[9] != 'E'
                || bytes[10] != 'B' || bytes[11] != 'P') {
            throw new IOException("cwebp 출력이 유효한 WebP가 아닙니다.");
        }
        return new GeneratedImage(new ByteArrayResource(bytes), WEBP);
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 임시 파일 정리 실패는 원래 변환 오류를 가리지 않는다.
        }
    }
}
