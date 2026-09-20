package com.naro.chat.config;

import com.naro.realtime.application.service.WebSocketTicketService;
import com.naro.security.KeycloakJwtAuthenticationConverter;
import com.naro.security.RealtimePrincipal;
import com.naro.user.application.service.CurrentUserService;
import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthenticationInterceptorTest {

    @Mock
    private WebSocketTicketService ticketService;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private CurrentUserService currentUserService;

    private WebSocketAuthenticationInterceptor interceptor;

    private final MessageChannel channel = mock(MessageChannel.class);


    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthenticationInterceptor(
                ticketService,
                jwtDecoder,
                new KeycloakJwtAuthenticationConverter(),
                currentUserService
        );
    }


    @Test
    void connectWithValidTicketAuthenticatesTicketOwner() {

        RealtimePrincipal principal = new RealtimePrincipal(UUID.randomUUID(), "kc-leonardo");

        when(ticketService.consume("ticket-valido")).thenReturn(Optional.of(principal));

        Message<?> result = interceptor.preSend(connect(Map.of("ticket", "ticket-valido")), channel);

        Authentication authentication = authenticationOf(result);
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(principal);
        assertThat(authentication.getName()).isEqualTo("kc-leonardo");
        verifyNoInteractions(jwtDecoder);
    }


    @Test
    void connectWithInvalidOrReusedTicketIsRejected() {

        when(ticketService.consume("ticket-usado")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                interceptor.preSend(connect(Map.of("ticket", "ticket-usado")), channel)
        )
                .isInstanceOf(BadCredentialsException.class);
    }


    @Test
    void connectWithBearerTokenStillWorks() {

        User user = user();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(user.getKeycloakId())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        when(jwtDecoder.decode("token")).thenReturn(jwt);
        when(currentUserService.resolve(jwt)).thenReturn(user);

        Message<?> result = interceptor.preSend(connect(Map.of("Authorization", "Bearer token")), channel);

        assertThat(authenticationOf(result).getPrincipal())
                .isEqualTo(new RealtimePrincipal(user.getId(), user.getKeycloakId()));
    }


    @Test
    void connectWithoutCredentialsIsRejected() {

        assertThatThrownBy(() -> interceptor.preSend(connect(Map.of()), channel))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(ticketService, jwtDecoder);
    }


    @Test
    void nonConnectFramesAreNotReauthenticated() {

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/chat/send");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verifyNoInteractions(ticketService, jwtDecoder, currentUserService);
    }


    private static Message<byte[]> connect(Map<String, String> headers) {

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.forEach(accessor::addNativeHeader);
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }


    private static Authentication authenticationOf(Message<?> message) {

        return (Authentication) StompHeaderAccessor.wrap(message).getUser();
    }


    private static User user() {

        User user = new User(UUID.randomUUID().toString(), "leonardo", null, null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }
}
