package com.harudle.generation.adapter.out.gemini.client;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;

public interface GeminiClientFactory {

    default Client create(HttpOptions httpOptions) {
        return create(httpOptions, ClientOptions.builder().build());
    }

    Client create(HttpOptions httpOptions, ClientOptions clientOptions);
}
