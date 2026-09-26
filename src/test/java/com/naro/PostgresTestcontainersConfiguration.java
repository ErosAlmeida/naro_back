package com.naro;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL real para testes de persistência.
 *
 * Mesma imagem do docker-compose.dev.yml, para que as migrations
 * do Flyway e as constraints sejam testadas no banco de verdade.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    /**
     * Imagem oficial via espelho ECR Public: o CodeBuild compartilha IPs e
     * esgota o limite de pulls anônimos do Docker Hub.
     */
    public static final DockerImageName IMAGE = DockerImageName
            .parse("public.ecr.aws/docker/library/postgres:17-alpine")
            .asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(IMAGE);
    }
}
