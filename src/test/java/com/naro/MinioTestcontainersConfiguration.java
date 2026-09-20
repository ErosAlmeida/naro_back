package com.naro;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MinIO real para testes que sobem o contexto completo.
 *
 * O S3BucketInitializer roda no startup e precisa de um
 * endpoint S3 acessível.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MinioTestcontainersConfiguration {

    @Bean
    MinIOContainer minioContainer() {
        return new MinIOContainer(
                DockerImageName
                        .parse("quay.io/minio/minio:latest")
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
