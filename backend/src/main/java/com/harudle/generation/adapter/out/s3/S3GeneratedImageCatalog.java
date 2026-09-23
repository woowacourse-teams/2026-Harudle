package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.GeneratedImageCatalog;
import java.util.ArrayList;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

public final class S3GeneratedImageCatalog implements GeneratedImageCatalog {

    private final S3Client s3Client;
    private final String bucket;
    private final ImageObjectKeyFactory objectKeyFactory;
    private final S3FailureReporter failureReporter;

    public S3GeneratedImageCatalog(S3Client s3Client, S3StorageProperties properties,
            ImageObjectKeyFactory objectKeyFactory, S3FailureReporter failureReporter) {
        this.s3Client = s3Client;
        this.bucket = properties.bucket();
        this.objectKeyFactory = objectKeyFactory;
        this.failureReporter = failureReporter;
    }

    @Override
    public Page list(String continuationToken) {
        try {
            var response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(objectKeyFactory.listPrefix())
                    .maxKeys(100)
                    .continuationToken(continuationToken)
                    .build());
            var images = new ArrayList<Image>();
            for (var object : response.contents()) {
                if (object.lastModified() != null) {
                    objectKeyFactory.generationId(object.key()).ifPresent(generationId ->
                            images.add(new Image(generationId, object.key(), object.lastModified())));
                }
            }
            return new Page(images, Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken() : null);
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(
                    "list_objects_v2", "목록 조회", objectKeyFactory.listPrefix(), false, exception);
        }
    }
}
