package com.naro.storage.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@Profile("(local | test) & !prod")
public class S3BucketInitializer implements ApplicationRunner {

    private final S3Client s3Client;
    private final S3StorageProperties properties;

    public S3BucketInitializer(
            S3Client s3Client,
            S3StorageProperties properties
    ) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {

        if (bucketExists()) {
            return;
        }

        s3Client.createBucket(
                CreateBucketRequest.builder()
                        .bucket(properties.bucket())
                        .build()
        );
    }


    private boolean bucketExists() {

        try {

            s3Client.headBucket(
                    HeadBucketRequest.builder()
                            .bucket(properties.bucket())
                            .build()
            );

            return true;

        } catch (S3Exception exception) {

            if (exception.statusCode() == 404) {
                return false;
            }

            throw exception;
        }
    }
}
