package com.harudle.generation.service;

public interface ImageStorage {

    ReferenceImage load(String imageObjectKey);

    String store(GeneratedImage generatedImage);
}
