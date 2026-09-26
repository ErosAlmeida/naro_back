package com.naro.storage.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class S3StorageConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(S3StorageConfig.class, S3BucketInitializer.class)
            .withPropertyValues(
                    "app.storage.s3.region=us-east-1",
                    "app.storage.s3.bucket=naro-test-publications",
                    "app.storage.s3.presigned-url-duration=30m");

    @Test
    void productionUsesAwsCredentialsChainAndNeverInitializesABucket() {
        contextRunner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(S3Client.class).hasSingleBean(S3Presigner.class);
            assertThat(context).doesNotHaveBean(S3BucketInitializer.class);
            assertThat(context.getBean(S3Client.class).serviceClientConfiguration().credentialsProvider())
                    .isInstanceOf(DefaultCredentialsProvider.class);
        });
    }

    @Test
    void localMinioKeepsPathStyleSigningAndBucketInitialization() {
        contextRunner.withPropertyValues(
                "spring.profiles.active=local",
                "app.storage.s3.endpoint=http://localhost:9000",
                "app.storage.s3.access-key=local-test-user",
                "app.storage.s3.secret-key=local-test-secret").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(S3BucketInitializer.class);
            var url = context.getBean(S3Presigner.class).presignGetObject(request -> request
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(object -> object.bucket("naro-test-publications").key("images/photo.jpg")))
                    .url();
            assertThat(url.toString()).startsWith("http://localhost:9000/naro-test-publications/images/photo.jpg?");
            assertThat(url.getQuery()).contains("local-test-user");
        });
    }

    @Test
    void productionNeverCreatesBucketsEvenIfLocalWasAccidentallyEnabled() {
        contextRunner.withPropertyValues("spring.profiles.active=prod,local").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(S3BucketInitializer.class);
        });
    }
}
