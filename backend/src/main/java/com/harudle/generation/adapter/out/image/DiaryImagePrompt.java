package com.harudle.generation.adapter.out.image;

import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;

/** The same rendered storyboard and reference instruction are used by every image provider. */
public final class DiaryImagePrompt {

    public static final String REFERENCE_INSTRUCTION = """
            [The Only Style Reference]
            The following image is the single binding style and canonical protagonist reference described by \
            the system instruction. Apply only its visual style and protagonist construction. Do not copy its \
            story, panel count, composition, props, poses, or text, and do not assume another reference asset.
            """.strip();

    private DiaryImagePrompt() {
    }

    public static String finalTask(DiaryImageGenerationRequest request, DiaryImagePromptRenderer renderer) {
        return "[Final Task]\n" + renderer.render(request.storyboard());
    }
}
