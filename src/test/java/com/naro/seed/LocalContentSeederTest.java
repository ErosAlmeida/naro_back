package com.naro.seed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalContentSeederTest {

    @Test
    void onlyLocalhostDatabasesAreSeeded() {
        assertThat(LocalContentSeeder.isLocalJdbcUrl("jdbc:postgresql://localhost:5432/naro")).isTrue();
        assertThat(LocalContentSeeder.isLocalJdbcUrl("jdbc:postgresql://127.0.0.1:55248/test?loggerLevel=OFF")).isTrue();

        assertThat(LocalContentSeeder.isLocalJdbcUrl("jdbc:postgresql://naro.abc123.us-east-1.rds.amazonaws.com:5432/naro")).isFalse();
        assertThat(LocalContentSeeder.isLocalJdbcUrl("jdbc:postgresql://postgres:5432/naro")).isFalse();
        assertThat(LocalContentSeeder.isLocalJdbcUrl("postgresql://localhost:5432/naro")).isFalse();
        assertThat(LocalContentSeeder.isLocalJdbcUrl("jdbc:not a url")).isFalse();
        assertThat(LocalContentSeeder.isLocalJdbcUrl(null)).isFalse();
    }

    @Test
    void onlyLocalhostStorageIsSeeded() {
        assertThat(LocalContentSeeder.isLocalHost("localhost")).isTrue();
        assertThat(LocalContentSeeder.isLocalHost("LOCALHOST")).isTrue();
        assertThat(LocalContentSeeder.isLocalHost("[::1]")).isTrue();

        assertThat(LocalContentSeeder.isLocalHost("s3.us-east-1.amazonaws.com")).isFalse();
        assertThat(LocalContentSeeder.isLocalHost(null)).isFalse();
    }
}
