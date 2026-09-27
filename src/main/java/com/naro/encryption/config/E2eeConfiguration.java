package com.naro.encryption.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(E2eeProperties.class)
public class E2eeConfiguration {
}
