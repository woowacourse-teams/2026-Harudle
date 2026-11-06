package com.harudle.generation.config;

import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.s3.CwebpImageVariantEncoder;
import com.harudle.generation.adapter.out.s3.ImageObjectKeyFactory;
import com.harudle.generation.adapter.out.s3.ImageUploadPreparer;
import com.harudle.generation.adapter.out.s3.ImageVariantEncoder;
import com.harudle.generation.adapter.out.s3.ObservedImageStorage;
import com.harudle.generation.adapter.out.s3.S3ExceptionTranslator;
import com.harudle.generation.adapter.out.s3.S3FailureReporter;
import com.harudle.generation.adapter.out.s3.S3ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Storage-only beans can run without AI providers or diary source-text keys. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "harudle.generation.adapters", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(S3StorageProperties.class)
public class ImageStorageConfiguration {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(S3StorageProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    public ImageObjectKeyFactory imageObjectKeyFactory(S3StorageProperties properties) {
        return new ImageObjectKeyFactory(properties);
    }

    @Bean
    public S3ExceptionTranslator s3ExceptionTranslator() {
        return new S3ExceptionTranslator();
    }

    @Bean
    public S3FailureReporter s3FailureReporter(
            S3ExceptionTranslator exceptionTranslator,
            ExternalApiLogger externalApiLogger
    ) {
        return new S3FailureReporter(exceptionTranslator, externalApiLogger);
    }

    @Bean
    public ImageVariantEncoder imageVariantEncoder() {
        CwebpImageVariantEncoder encoder = new CwebpImageVariantEncoder();
        encoder.verifyAvailable();
        return encoder;
    }

    @Bean
    public ImageUploadPreparer imageUploadPreparer(
            ImageObjectKeyFactory objectKeyFactory,
            ImageVariantEncoder variantEncoder
    ) {
        return new ImageUploadPreparer(objectKeyFactory, variantEncoder);
    }

    @Bean
    public ImageStorage imageStorage(
            @Qualifier("s3Client") S3Client s3Client,
            S3StorageProperties properties,
            ImageUploadPreparer uploadPreparer,
            S3FailureReporter failureReporter,
            MeterRegistry meterRegistry
    ) {
        ImageStorage storage = new S3ImageStorage(
                s3Client,
                properties,
                uploadPreparer,
                failureReporter
        );
        return new ObservedImageStorage(storage, meterRegistry);
    }

}
