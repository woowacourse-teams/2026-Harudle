package com.harudle.generation.adapter.out.gemini.client;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

public final class VertexGeminiClientFactory implements GeminiClientFactory {

    private final String projectId;
    private final String location;
    private final GoogleCredentials credentials;

    public VertexGeminiClientFactory(String projectId, String location, GoogleCredentials credentials) {
        this.projectId = projectId;
        this.location = location;
        this.credentials = credentials.createScoped("https://www.googleapis.com/auth/cloud-platform");
    }

    @Override
    public Client create(HttpOptions httpOptions) {
        return Client.builder()
                .vertexAI(true)
                .project(projectId)
                .location(location)
                .credentials(credentials)
                .httpOptions(httpOptions)
                .build();
    }
}
