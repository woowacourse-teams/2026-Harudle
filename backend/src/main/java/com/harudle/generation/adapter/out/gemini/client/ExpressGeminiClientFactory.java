package com.harudle.generation.adapter.out.gemini.client;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

public final class ExpressGeminiClientFactory implements GeminiClientFactory {

    private final String apiKey;

    public ExpressGeminiClientFactory(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public Client create(HttpOptions httpOptions) {
        return Client.builder()
                .vertexAI(true)
                .apiKey(apiKey)
                .httpOptions(httpOptions)
                .build();
    }
}
