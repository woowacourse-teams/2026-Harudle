package com.harudle.admin.service;

import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

final class RecoveryImageUpload {
    private RecoveryImageUpload() { }

    static GeneratedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지 파일이 비어 있습니다.");
        }
        if (bytes.length > 20 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "이미지는 20MiB 이하여야 합니다.");
        }
        // 파일명과 클라이언트 Content-Type 대신 파일 내용을 검사한다.
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unsupported();
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName();
                MediaType type;
                if (format.equalsIgnoreCase("png")) {
                    type = MediaType.IMAGE_PNG;
                } else if (format.equalsIgnoreCase("jpeg") || format.equalsIgnoreCase("jpg")) {
                    type = MediaType.IMAGE_JPEG;
                } else {
                    throw unsupported();
                }
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 25_000_000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지는 2500만 픽셀 이하여야 합니다.");
                }
                if (reader.read(0) == null) {
                    throw unsupported();
                }
                return new GeneratedImage(new ByteArrayResource(bytes), type);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지 파일을 읽을 수 없습니다.", exception);
        }
    }

    private static ResponseStatusException unsupported() {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "복구 업로드는 PNG 또는 JPEG 파일만 지원합니다.");
    }
}
