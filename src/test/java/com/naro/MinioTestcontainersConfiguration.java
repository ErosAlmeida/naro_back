package com.naro;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;

/**
 * MinIO real para testes que sobem o contexto completo.
 *
 * O S3BucketInitializer roda no startup e precisa de um
 * endpoint S3 acessível.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MinioTestcontainersConfiguration {

    // The upstream public images were removed. Build verified official source once
    // per test JVM; Docker keeps layers for later local test runs and Compose.
    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile(
            "naro-minio:RELEASE.2025-09-07T16-13-09Z", false)
            .withDockerfile(Path.of("infra/minio/Dockerfile").toAbsolutePath());

    @Bean
    MinIOContainer minioContainer() {
        return new MinIOContainer(
                DockerImageName
                        .parse(IMAGE.get())
                        .asCompatibleSubstituteFor("minio/minio")
        );
    }


    @Bean
    DynamicPropertyRegistrar storageProperties(
            MinIOContainer minio
    ) {
        return registry -> {
            registry.add("app.storage.s3.endpoint", minio::getS3URL);
            registry.add("app.storage.s3.access-key", minio::getUserName);
            registry.add("app.storage.s3.secret-key", minio::getPassword);
        };
    }
}
