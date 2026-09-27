package com.naro.chat.presentation.controller;

import com.naro.chat.application.dto.ChatErrorResponse;
import com.naro.chat.application.dto.ChatTypingRequest;
import com.naro.chat.application.dto.SendChatMessageRequest;
import com.naro.chat.application.service.ChatService;
import com.naro.exception.ForbiddenException;
import com.naro.exception.ChatFriendshipRequiredException;
import com.naro.exception.E2eeError;
import com.naro.exception.E2eeException;
import com.naro.exception.NotFoundException;
import com.naro.security.RealtimePrincipal;
import com.naro.user.application.service.CurrentUserService;
import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O cliente STOMP deve continuar recebendo apenas a mensagem
 * de erro, mesmo com o service lançando exceptions HTTP.
 */
@ExtendWith(MockitoExtension.class)
class ChatWebSocketControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private CurrentUserService currentUserService;

    private ChatWebSocketController controller;


    @BeforeEach
    void setUp() {
        controller = new ChatWebSocketController(chatService, currentUserService);
    }


    @Test
    void errorResponseExceptionIsSentWithOnlyTheDetailMessage() {

        ChatErrorResponse response = controller.handleErrorResponse(
                new NotFoundException("Conversation not found")
        );

        assertThat(response.code()).isEqualTo("CHAT_ERROR");
        assertThat(response.message()).isEqualTo("Conversation not found");
        assertThat(response.timestamp()).isNotNull();
    }


    @Test
    void forbiddenIsSentWithOnlyTheDetailMessage() {

        ChatErrorResponse response = controller.handleErrorResponse(
                new ForbiddenException("You are not a participant of this conversation")
        );

        assertThat(response.message())
                .isEqualTo("You are not a participant of this conversation");
    }

    @Test
    void friendshipRequiredCorrelatesOnlyTheRejectedMessage() {
        UUID conversationId = UUID.randomUUID();
        UUID clientMessageId = UUID.randomUUID();

        ChatErrorResponse response = controller.handleErrorResponse(
                new ChatFriendshipRequiredException(conversationId, clientMessageId));

        assertThat(response.code()).isEqualTo("FRIENDSHIP_REQUIRED");
        assertThat(response.message()).isEqualTo("You can only message your friends");
        assertThat(response.conversationId()).isEqualTo(conversationId);
        assertThat(response.clientMessageId()).isEqualTo(clientMessageId);
    }

    @Test
    void typingFriendshipErrorHasConversationWithoutMessageCorrelation() {
        UUID conversationId = UUID.randomUUID();
        ChatErrorResponse response = controller.handleErrorResponse(
                new ChatFriendshipRequiredException(conversationId, null));

        assertThat(response.code()).isEqualTo("FRIENDSHIP_REQUIRED");
        assertThat(response.conversationId()).isEqualTo(conversationId);
        assertThat(response.clientMessageId()).isNull();
    }


    @Test
    void e2eeRequiredCorrelatesOnlyTheRejectedMessage() {
        UUID conversationId = UUID.randomUUID();
        UUID clientMessageId = UUID.randomUUID();

        ChatErrorResponse response = controller.handleErrorResponse(
                new E2eeException(E2eeError.REQUIRED, conversationId, clientMessageId));

        assertThat(response.code()).isEqualTo("E2EE_REQUIRED");
        assertThat(response.message()).isEqualTo("Private messages must be end-to-end encrypted");
        assertThat(response.conversationId()).isEqualTo(conversationId);
        assertThat(response.clientMessageId()).isEqualTo(clientMessageId);
    }


    /*
     * O remetente é o usuário do RealtimePrincipal criado no CONNECT.
     * ChatTypingRequest nem possui campo de identidade.
     */
    @Test
    void typingUsesUserFromAuthenticatedConnection() {

        User leonardo = user();
        RealtimePrincipal principal = new RealtimePrincipal(leonardo.getId(), leonardo.getKeycloakId());
        ChatTypingRequest request = new ChatTypingRequest(UUID.randomUUID(), true);

        when(currentUserService.resolveRealtime(principal)).thenReturn(leonardo);

        controller.typing(request, principal);

        verify(chatService).notifyTyping(request, leonardo);
    }


    @Test
    void sendMessageUsesUserFromAuthenticatedConnection() {

        User leonardo = user();
        RealtimePrincipal principal = new RealtimePrincipal(leonardo.getId(), leonardo.getKeycloakId());
        SendChatMessageRequest request = new SendChatMessageRequest(UUID.randomUUID(), "Oi", null);

        when(currentUserService.resolveRealtime(principal)).thenReturn(leonardo);

        controller.sendMessage(request, principal);

        verify(chatService).sendMessage(request, leonardo);
    }


    private static User user() {

        User user = new User(UUID.randomUUID().toString(), "leonardo", null, null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }
}
