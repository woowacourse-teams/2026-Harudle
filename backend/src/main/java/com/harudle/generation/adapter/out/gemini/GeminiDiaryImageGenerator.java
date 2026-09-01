package com.harudle.generation.adapter.out.gemini;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.genai.Models;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.ImageConfig;
import com.google.genai.types.Part;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public final class GeminiDiaryImageGenerator implements DiaryImageGenerator {

    private static final String OPERATION = "image_generation";
    private static final String TRANSLATION_OPERATION = "그림일기 이미지 생성";
    private static final String REQUEST_PREPARATION_ERROR = "REQUEST_PREPARATION_ERROR";
    private static final String RESPONSE_PROCESSING_ERROR = "RESPONSE_PROCESSING_ERROR";
    private static final String FINAL_TASK_HEADER = "[Final Task]";
    private static final String REFERENCE_IMAGE_INSTRUCTION = """
            [The Only Style Reference]
            STYLE FIDELITY IS THE HIGHEST PRIORITY. Treat the following single image as the binding canonical \
            specification for the artist's marker stroke, organic wobble, black-interior outline construction, \
            naive character anatomy, hair marks, face, proportions, negative space, and Korean handwriting. \
            The result must look drawn by the same hand with the same white marker, not merely inspired by it. \
            Do not clean up or convert it into generic vector, icon, infographic, or editorial line art. Do not \
            copy its story, panel count, composition, props, or text. Do not use or assume any other reference \
            image or asset.
            """.strip();
    private static final long INLINE_REQUEST_LIMIT_BYTES = 20L * 1024 * 1024;
    private static final long INLINE_REQUEST_OVERHEAD_BYTES = 8192L;

    private final Models models;
    private final GeminiGenerationProperties properties;
    private final DiaryImagePromptRenderer promptRenderer;
    private final GeminiFailureReporter failureReporter;

    public GeminiDiaryImageGenerator(
            Models models,
            GeminiGenerationProperties properties,
            DiaryImagePromptRenderer promptRenderer,
            GeminiFailureReporter failureReporter
    ) {
        this.models = models;
        this.properties = properties;
        this.promptRenderer = promptRenderer;
        this.failureReporter = failureReporter;
    }

    @Override
    public GeneratedImage generate(DiaryImageGenerationRequest request) {
        Content content;
        GenerateContentConfig config;
        try {
            String finalTask = createFinalTask(request);
            byte[] referenceImageBytes = readReferenceImage(request.referenceImage(), finalTask);
            content = createContent(request.referenceImage(), referenceImageBytes, finalTask);
            config = createGenerateContentConfig();
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    REQUEST_PREPARATION_ERROR,
                    exception
            );
        }

        GenerateContentResponse response;
        try {
            response = models.generateContent(
                    properties.imageModel(),
                    content,
                    config
            );
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(OPERATION, TRANSLATION_OPERATION, exception);
        }

        try {
            return extractGeneratedImage(response);
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    RESPONSE_PROCESSING_ERROR,
                    exception
            );
        }
    }

    private String createFinalTask(DiaryImageGenerationRequest request) {
        String storyPrompt = promptRenderer.render(request.storyboard());
        return FINAL_TASK_HEADER
                + "\n"
                + request.imageStylePromptText()
                + "\n\n"
                + storyPrompt;
    }

    private static byte[] readReferenceImage(
            ReferenceImage referenceImage,
            String finalTask
    ) throws IOException {
        Resource resource = referenceImage.resource();
        validateInlineRequestSize(finalTask, resource.contentLength());

        byte[] referenceImageBytes = resource.getContentAsByteArray();
        validateInlineRequestSize(finalTask, referenceImageBytes.length);
        return referenceImageBytes;
    }

    private static void validateInlineRequestSize(String finalTask, long referenceImageSize) {
        long encodedReferenceImageSize = 4L * ((referenceImageSize + 2L) / 3L);
        long estimatedRequestSize = finalTask.getBytes(UTF_8).length
                + INLINE_REQUEST_OVERHEAD_BYTES
                + encodedReferenceImageSize;
        if (estimatedRequestSize >= INLINE_REQUEST_LIMIT_BYTES) {
            throw new IllegalArgumentException("Gemini inline 요청은 20MiB 미만이어야 합니다.");
        }
    }

    private static Content createContent(
            ReferenceImage referenceImage,
            byte[] referenceImageBytes,
            String finalTask
    ) {
        return Content.fromParts(
                Part.fromText(REFERENCE_IMAGE_INSTRUCTION),
                Part.fromBytes(referenceImageBytes, referenceImage.mediaType().toString()),
                Part.fromText(finalTask)
        );
    }

    private GenerateContentConfig createGenerateContentConfig() {
        ImageConfig imageConfig = ImageConfig.builder()
                .aspectRatio(properties.imageAspectRatio())
                .build();

        return GenerateContentConfig.builder()
                .responseModalities("TEXT", "IMAGE")
                .imageConfig(imageConfig)
                .build();
    }

    private static GeneratedImage extractGeneratedImage(GenerateContentResponse response) {
        if (response == null) {
            throw new IllegalStateException("Gemini 이미지 응답이 없습니다.");
        }

        for (Part part : Objects.requireNonNull(response.parts())) {
            Optional<GeneratedImage> generatedImage = convertGeneratedImage(part);
            if (generatedImage.isPresent()) {
                return generatedImage.get();
            }
        }
        throw new IllegalStateException("Gemini 응답에 사용할 수 있는 이미지가 없습니다.");
    }

    private static Optional<GeneratedImage> convertGeneratedImage(Part part) {
        Optional<Blob> inlineData = part.inlineData();
        if (inlineData.isEmpty()) {
            return Optional.empty();
        }

        Blob imageData = inlineData.get();
        Optional<byte[]> imageBytes = imageData.data();
        Optional<String> imageMimeType = imageData.mimeType();
        if (imageBytes.isEmpty() || imageMimeType.isEmpty()) {
            return Optional.empty();
        }

        byte[] bytes = imageBytes.get();
        if (bytes.length == 0) {
            return Optional.empty();
        }

        MediaType mediaType = MediaType.parseMediaType(imageMimeType.get());
        if (!mediaType.getType().equals("image") || mediaType.isWildcardSubtype()) {
            return Optional.empty();
        }

        return Optional.of(new GeneratedImage(new ByteArrayResource(bytes), mediaType));
    }
}
