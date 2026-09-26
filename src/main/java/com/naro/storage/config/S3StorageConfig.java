package com.naro.storage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfig {

    @Bean
    public S3Client s3Client(
            S3StorageProperties properties
    ) {

        var builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentialsProvider(properties));

        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint()).forcePathStyle(true);
        }

        return builder.build();
    }


    @Bean
    public S3Presigner s3Presigner(
            S3StorageProperties properties
    ) {

        var s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.endpoint() != null)
                .checksumValidationEnabled(false)
                .build();

        var builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentialsProvider(properties))
                .serviceConfiguration(s3Configuration);

        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
        }

        return builder.build();
    }

    private AwsCredentialsProvider credentialsProvider(S3StorageProperties properties) {
        if (properties.endpoint() == null) {
            return DefaultCredentialsProvider.builder().build();
        }
        // Credenciais fixas somente para o endpoint S3-compatible local (MinIO).
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.accessKey(), properties.secretKey()));
    }
}
