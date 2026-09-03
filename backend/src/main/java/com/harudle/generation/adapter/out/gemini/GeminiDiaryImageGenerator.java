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
            The following image is the single binding style and canonical protagonist reference described by \
            the system instruction. Apply only its visual style and protagonist construction. Do not copy its \
            story, panel count, composition, props, poses, or text, and do not assume another reference asset.
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
            String systemInstruction = request.imageStylePromptText();
            byte[] referenceImageBytes = readReferenceImage(
                    request.referenceImage(),
                    systemInstruction,
                    finalTask
            );
            content = createContent(request.referenceImage(), referenceImageBytes, finalTask);
            config = createGenerateContentConfig(systemInstruction);
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
                + storyPrompt;
    }

    private static byte[] readReferenceImage(
            ReferenceImage referenceImage,
            String systemInstruction,
            String finalTask
    ) throws IOException {
        Resource resource = referenceImage.resource();
        validateInlineRequestSize(systemInstruction, finalTask, resource.contentLength());

        byte[] referenceImageBytes = resource.getContentAsByteArray();
        validateInlineRequestSize(systemInstruction, finalTask, referenceImageBytes.length);
        return referenceImageBytes;
    }

    private static void validateInlineRequestSize(
            String systemInstruction,
            String finalTask,
            long referenceImageSize
    ) {
        long encodedReferenceImageSize = 4L * ((referenceImageSize + 2L) / 3L);
        long estimatedRequestSize = systemInstruction.getBytes(UTF_8).length
                + finalTask.getBytes(UTF_8).length
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

    private GenerateContentConfig createGenerateContentConfig(String systemInstruction) {
        ImageConfig imageConfig = ImageConfig.builder()
                .aspectRatio(properties.imageAspectRatio())
                .build();

        return GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
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
