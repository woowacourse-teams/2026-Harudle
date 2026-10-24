package com.harudle.generation.adapter.out.gemini.client;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;

public final class ExpressGeminiClientFactory implements GeminiClientFactory {

    private final String apiKey;

    public ExpressGeminiClientFactory(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public Client create(HttpOptions httpOptions, ClientOptions clientOptions) {
        return Client.builder()
                .vertexAI(true)
                .apiKey(apiKey)
                .httpOptions(httpOptions)
                .clientOptions(clientOptions)
                .build();
    }
}
