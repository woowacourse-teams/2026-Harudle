package com.harudle.feed.infrastructure;

import com.harudle.feed.service.port.FeedPreviewImageRenderer;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public final class JpegFeedPreviewImageRenderer implements FeedPreviewImageRenderer {

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 630;
    private static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 30_000_000;
    private static final String CONVERSION_FAILED = "공유 미리보기 이미지를 변환하지 못했습니다.";
    private static final String DIMENSIONS_EXCEEDED = "공유 미리보기 이미지 크기가 제한을 초과했습니다.";

    @Override
    public ReferenceImage render(ReferenceImage source) {
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw failed();
            }
            String format = source.mediaType().getSubtype();
            if (!format.equals("png") && !format.equals("jpeg") && !format.equals("webp")) {
                throw failed();
            }
            byte[] bytes = readBounded(source);
            BufferedImage image = format.equals("webp")
                    ? decodeWebp(bytes) : decode(bytes, format);
            return encodeJpeg(image);
        } catch (ImageStorageException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failed();
        } catch (IOException | RuntimeException exception) {
            // Resource/codec errors can contain paths or storage addresses. Do not retain those causes.
            throw failed();
        }
    }

    private static byte[] readBounded(ReferenceImage source) throws IOException {
        try (InputStream input = source.resource().getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_INPUT_BYTES) {
                throw new ImageStorageException("공유 미리보기 원본은 20MiB 이하여야 합니다.");
            }
            return bytes;
        }
    }

    private static BufferedImage decode(byte[] bytes, String expectedFormat) throws IOException {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw failed();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!reader.getFormatName().equalsIgnoreCase(expectedFormat)) {
                    throw failed();
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                dimensions(width, height);
                ImageReadParam parameters = reader.getDefaultReadParam();
                int sampling = Math.max(1, (int) Math.max(width / (double) WIDTH, height / (double) HEIGHT));
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                BufferedImage image = reader.read(0, parameters);
                if (image == null) {
                    throw failed();
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage decodeWebp(byte[] bytes) throws IOException, InterruptedException {
        // Check the canvas AND bitmap headers before the native decoder allocates image memory.
        Dimensions source = webpDimensions(bytes);
        Dimensions target = fitted(source.width(), source.height());
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("harudle-feed-preview-");
            Path input = directory.resolve("source.webp");
            Path output = directory.resolve("decoded.png");
            Files.write(input, bytes);
            process = new ProcessBuilder("dwebp", input.toString(), "-quiet", "-resize",
                    Integer.toString(target.width()), Integer.toString(target.height()), "-o", output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw failed();
            }
            // Native output is already fitted, so it must also satisfy the bounded input contract.
            try (InputStream decoded = Files.newInputStream(output)) {
                byte[] png = decoded.readNBytes(MAX_INPUT_BYTES + 1);
                if (png.length == 0 || png.length > MAX_INPUT_BYTES) {
                    throw failed();
                }
                return decode(png, "png");
            }
        } finally {
            stop(process);
            if (directory != null) {
                delete(directory.resolve("decoded.png"));
                delete(directory.resolve("source.webp"));
                delete(directory);
            }
        }
    }

    private static Dimensions webpDimensions(byte[] bytes) {
        if (bytes.length < 20 || !matches(bytes, 0, "RIFF") || !matches(bytes, 8, "WEBP")
                || little(bytes, 4, 4) + 8 != bytes.length) {
            throw failed();
        }
        Dimensions canvas = null;
        Dimensions bitmap = null;
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            long length = little(bytes, offset + 4, 4);
            int data = offset + 8;
            long next = data + length + (length & 1);
            if (next > bytes.length) {
                throw failed();
            }
            if (matches(bytes, offset, "VP8X")) {
                if (canvas != null || bitmap != null || length != 10 || (bytes[data] & 0x02) != 0) {
                    throw failed();
                }
                canvas = dimensions((int) little(bytes, data + 4, 3) + 1,
                        (int) little(bytes, data + 7, 3) + 1);
            } else if (matches(bytes, offset, "VP8 ")) {
                if (bitmap != null || length < 10 || (bytes[data] & 1) != 0
                        || (bytes[data + 3] & 0xff) != 0x9d
                        || bytes[data + 4] != 0x01 || bytes[data + 5] != 0x2a) {
                    throw failed();
                }
                bitmap = dimensions((int) little(bytes, data + 6, 2) & 0x3fff,
                        (int) little(bytes, data + 8, 2) & 0x3fff);
            } else if (matches(bytes, offset, "VP8L")) {
                if (bitmap != null || length < 5 || (bytes[data] & 0xff) != 0x2f) {
                    throw failed();
                }
                long packed = little(bytes, data + 1, 4);
                bitmap = dimensions((int) (packed & 0x3fff) + 1,
                        (int) ((packed >>> 14) & 0x3fff) + 1);
            } else if (matches(bytes, offset, "ANIM") || matches(bytes, offset, "ANMF")) {
                throw failed();
            }
            offset = (int) next;
        }
        if (offset != bytes.length || bitmap == null || (canvas != null && !canvas.equals(bitmap))) {
            throw failed();
        }
        return bitmap;
    }

    private static long little(byte[] bytes, int offset, int count) {
        long result = 0;
        for (int index = 0; index < count; index++) {
            result |= (long) (bytes[offset + index] & 0xff) << (index * 8);
        }
        return result;
    }

    private static boolean matches(byte[] bytes, int offset, String value) {
        if (offset + value.length() > bytes.length) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if ((bytes[offset + index] & 0xff) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static Dimensions dimensions(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
            throw new ImageStorageException(DIMENSIONS_EXCEEDED);
        }
        return new Dimensions(width, height);
    }

    private static Dimensions fitted(int width, int height) {
        double scale = Math.min(WIDTH / (double) width, HEIGHT / (double) height);
        return new Dimensions(Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)));
    }

    private static ReferenceImage encodeJpeg(BufferedImage image) throws IOException {
        BufferedImage preview = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = preview.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            Dimensions target = fitted(image.getWidth(), image.getHeight());
            graphics.drawImage(image, (WIDTH - target.width()) / 2, (HEIGHT - target.height()) / 2,
                    target.width(), target.height(), null);
        } finally {
            graphics.dispose();
            image.flush();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(preview, "jpeg", output)) {
                throw failed();
            }
            return new ReferenceImage(new ByteArrayResource(output.toByteArray()), MediaType.IMAGE_JPEG);
        } finally {
            preview.flush();
        }
    }

    private static void stop(@Nullable Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Attempt every cleanup without exposing paths or replacing the original failure.
        }
    }

    private static ImageStorageException failed() {
        return new ImageStorageException(CONVERSION_FAILED);
    }

    private record Dimensions(int width, int height) {
    }
}
