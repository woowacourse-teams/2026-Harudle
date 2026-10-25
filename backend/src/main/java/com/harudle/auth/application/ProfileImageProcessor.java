package com.harudle.auth.application;

import com.harudle.auth.infrastructure.CwebpProfileImageEncoder;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ProfileImageProcessor {
    private static final int MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 25_000_000;
    private static final int MAX_IMAGE_SIZE = 512;

    private final Semaphore processingPermit = new Semaphore(1);
    private final CwebpProfileImageEncoder imageEncoder;

    public ProfileImageProcessor(CwebpProfileImageEncoder imageEncoder) {
        this.imageEncoder = imageEncoder;
    }

    public byte[] process(byte[] bytes) {
        validateFileSize(bytes);

        if (!processingPermit.tryAcquire()) {
            throw new ProfileImageCapacityExceededException();
        }
        try {
            return imageEncoder.encode(resize(decode(bytes)));
        } finally {
            processingPermit.release();
        }
    }

    private void validateFileSize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로필 이미지 파일은 필수입니다.");
        }

        if (bytes.length > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "프로필 이미지는 5MiB 이하여야 합니다.");
        }
    }

    private BufferedImage decode(byte[] bytes) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);

            if (!readers.hasNext()) {
                throw unsupportedImage();
            }

            return readImage(readers.next(), input);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로필 이미지 파일을 읽을 수 없습니다.", exception);
        }
    }

    private BufferedImage readImage(ImageReader reader, ImageInputStream input) throws IOException {
        try {
            reader.setInput(input, true, true);
            validateFormat(reader.getFormatName());
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            validateDimensions(width, height);
            BufferedImage image = reader.read(0, centeredReadParam(reader, width, height));

            if (image == null) {
                throw unsupportedImage();
            }

            return image;
        } finally {
            reader.dispose();
        }
    }

    private void validateFormat(String format) {
        if (format.equalsIgnoreCase("jpeg")
                || format.equalsIgnoreCase("jpg")
                || format.equalsIgnoreCase("png")) {
            return;
        }

        throw unsupportedImage();
    }

    private void validateDimensions(int width, int height) {
        long pixels = (long) width * height;

        if (width <= 0 || height <= 0 || pixels > MAX_IMAGE_PIXELS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로필 이미지는 2500만 픽셀 이하여야 합니다.");
        }
    }

    private ImageReadParam centeredReadParam(ImageReader reader, int width, int height) {
        int size = Math.min(width, height);
        int sampling = Math.max(1, size / MAX_IMAGE_SIZE);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(new Rectangle((width - size) / 2, (height - size) / 2, size, size));
        param.setSourceSubsampling(sampling, sampling, 0, 0);
        return param;
    }

    private BufferedImage resize(BufferedImage source) {
        int size = source.getWidth();
        if (size <= MAX_IMAGE_SIZE) {
            return source;
        }

        BufferedImage resized = new BufferedImage(MAX_IMAGE_SIZE, MAX_IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();

        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE, null);
        } finally {
            graphics.dispose();
        }

        return resized;
    }

    private ResponseStatusException unsupportedImage() {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "프로필 이미지는 JPEG 또는 PNG만 지원합니다.");
    }
}
