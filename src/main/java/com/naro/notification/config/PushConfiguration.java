package com.naro.notification.config;

import com.naro.notification.webpush.PushEndpointPolicy;
import com.naro.notification.webpush.VapidCredentials;
import com.naro.notification.webpush.WebPushClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfiguration {

    /** Nome do executor que entrega os pushes, fora da thread do chat. */
    public static final String PUSH_EXECUTOR = "pushNotificationExecutor";

    private static final Logger log = LoggerFactory.getLogger(PushConfiguration.class);

    @Bean
    PushEndpointPolicy pushEndpointPolicy(PushProperties properties) {
        return new PushEndpointPolicy(properties.allowedEndpointHosts());
    }

    /**
     * Configuração ausente ou inválida desliga só o Web Push: o chat, o
     * WebSocket e o resto da API sobem normalmente. O erro nunca mostra
     * o valor das chaves.
     */
    @Bean
    WebPushClient webPushClient(PushProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Um push service nunca redireciona; seguir abriria outra porta para SSRF.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        PushProperties.Vapid vapid = properties.vapid();

        if (vapid.isBlank()) {
            log.info("Web Push disabled: NARO_PUSH_VAPID_* not configured");
            return new WebPushClient(httpClient, null);
        }

        try {
            VapidCredentials credentials = VapidCredentials.of(vapid.publicKey(), vapid.privateKey(), vapid.subject());
            log.info("Web Push enabled (VAPID subject {})", vapid.subject());
            return new WebPushClient(httpClient, credentials);
        } catch (IllegalArgumentException exception) {
            log.error("Web Push disabled: invalid VAPID configuration ({})", exception.getMessage());
            return new WebPushClient(httpClient, null);
        }
    }

    /**
     * Fila limitada: se lotar, o push é descartado (com log) e o chat segue.
     * Enviar mensagem nunca espera por um push service.
     */
    @Bean(name = PUSH_EXECUTOR)
    ThreadPoolTaskExecutor pushNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("push-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1_000);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
