package com.harudle.generation.adapter.out.gemini.client;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

public interface GeminiClientFactory {

    Client create(HttpOptions httpOptions);
}
