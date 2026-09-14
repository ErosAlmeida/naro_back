package com.naro.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.storage.s3")
public record S3StorageProperties(

        URI endpoint,

        String region,

        String accessKey,

        String secretKey,

        String bucket,

        Duration presignedUrlDuration

) {
}