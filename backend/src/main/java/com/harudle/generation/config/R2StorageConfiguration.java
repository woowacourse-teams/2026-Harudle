package com.harudle.generation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "harudle.generation.storage.r2",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(R2StorageProperties.class)
public class R2StorageConfiguration {

    private static final Region REGION = Region.of("auto");

    @Bean(destroyMethod = "close")
    public S3Client r2S3Client(R2StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(REGION)
                .credentialsProvider(credentialsProvider(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner r2S3Presigner(R2StorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(properties.endpoint())
                .region(REGION)
                .credentialsProvider(credentialsProvider(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private StaticCredentialsProvider credentialsProvider(R2StorageProperties properties) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.accessKeyId(), properties.secretAccessKey()
        ));
    }
}
