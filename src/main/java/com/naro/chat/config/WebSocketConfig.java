package com.naro.chat.config;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.context.properties.EnableConfigurationProperties;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import org.springframework.messaging.simp.config.MessageBrokerRegistry;

import org.springframework.scheduling.TaskScheduler;

import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(
        WebSocketProperties.class
)
public class WebSocketConfig
        implements WebSocketMessageBrokerConfigurer {

    private final WebSocketProperties properties;

    private TaskScheduler messageBrokerTaskScheduler;


    public WebSocketConfig(
            WebSocketProperties properties
    ) {
        this.properties = properties;
    }


    @Autowired
    public void setMessageBrokerTaskScheduler(
            @Lazy
            TaskScheduler taskScheduler
    ) {
        this.messageBrokerTaskScheduler =
                taskScheduler;
    }


    @Override
    public void registerStompEndpoints(
            StompEndpointRegistry registry
    ) {

        registry
                .addEndpoint("/ws")
                .setAllowedOrigins(
                        properties
                                .allowedOrigins()
                                .toArray(String[]::new)
                );
    }


    @Override
    public void configureMessageBroker(
            MessageBrokerRegistry registry
    ) {

        /*
         * SEND /app/chat/send
         *
         * vai para @MessageMapping.
         */
        registry.setApplicationDestinationPrefixes(
                "/app"
        );


        /*
         * /user/queue/chat
         */
        registry.setUserDestinationPrefix(
                "/user"
        );


        registry
                .enableSimpleBroker(
                        "/queue"
                )
                .setHeartbeatValue(
                        new long[]{
                                10_000,
                                10_000
                        }
                )
                .setTaskScheduler(
                        messageBrokerTaskScheduler
                );
    }
}