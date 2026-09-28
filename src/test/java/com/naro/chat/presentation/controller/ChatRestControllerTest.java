package com.naro.chat.presentation.controller;

import com.naro.chat.application.dto.ChatDeliveredRequest;
import com.naro.chat.application.dto.ConversationResponse;
import com.naro.chat.application.dto.UnreadChatsResponse;
import com.naro.chat.application.service.ChatService;
import com.naro.chat.domain.enums.ConversationType;
import com.naro.exception.BadRequestException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.security.SecurityConfig;
import com.naro.user.application.service.CurrentUserService;
import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatRestController.class)
@Import({SecurityConfig.class, ConversationModelAssembler.class})
class ChatRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean(name = "currentUserService")
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final User currentUser = user();


    @BeforeEach
    void setUp() {
        when(currentUserService.resolve(any())).thenReturn(currentUser);
    }


    @Test
    void createDirectConversationReturns201WithLocation() throws Exception {

        UUID targetUserId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        when(chatService.createDirectConversation(targetUserId, currentUser))
                .thenReturn(new ConversationResponse(
                        conversationId,
                        ConversationType.DIRECT,
                        Set.of(),
                        Instant.now(),
                        Instant.now(),
                        null
                ));

        mockMvc.perform(post("/api/v1/chats/direct/{userId}", targetUserId)
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/chats/" + conversationId))
                .andExpect(jsonPath("$.id").value(conversationId.toString()))
                .andExpect(jsonPath("$._links.messages.href")
                        .value("http://localhost/api/v1/chats/" + conversationId + "/messages?page=0&size=50"));
    }


    @Test
    void createDirectConversationWithYourselfReturns400() throws Exception {

        when(chatService.createDirectConversation(currentUser.getId(), currentUser))
                .thenThrow(new BadRequestException("You cannot create a conversation with yourself"));

        mockMvc.perform(post("/api/v1/chats/direct/{userId}", currentUser.getId())
                        .with(jwt()))
                .andExpect(status().isBadRequest());
    }


    @Test
    void createDirectConversationWithUnknownUserReturns404() throws Exception {

        UUID unknownUserId = UUID.randomUUID();

        when(chatService.createDirectConversation(unknownUserId, currentUser))
                .thenThrow(new NotFoundException("Target user not found"));

        mockMvc.perform(post("/api/v1/chats/direct/{userId}", unknownUserId)
                        .with(jwt()))
                .andExpect(status().isNotFound());
    }


    @Test
    void getMessagesAsNonParticipantReturns403() throws Exception {

        UUID conversationId = UUID.randomUUID();

        when(chatService.getMessages(conversationId, currentUser, PageRequest.of(0, 50)))
                .thenThrow(new ForbiddenException("You are not a participant of this conversation"));

        mockMvc.perform(get("/api/v1/chats/{conversationId}/messages", conversationId)
                        .with(jwt()))
                .andExpect(status().isForbidden());
    }


    @Test
    void getMessagesClampsPageSize() throws Exception {

        UUID conversationId = UUID.randomUUID();

        when(chatService.getMessages(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/chats/{conversationId}/messages", conversationId)
                        .param("size", "1000")
                        .with(jwt()))
                .andExpect(status().isOk());

        verify(chatService).getMessages(conversationId, currentUser, PageRequest.of(0, 100));
    }


    @Test
    void getConversationsWithoutTokenReturns401() throws Exception {

        mockMvc.perform(get("/api/v1/chats"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }


    @Test
    void unreadSummaryIsOnlyForTheAuthenticatedUser() throws Exception {

        when(chatService.getUnreadSummary(currentUser)).thenReturn(new UnreadChatsResponse(2, 5));

        mockMvc.perform(get("/api/v1/chats/unread").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations").value(2))
                .andExpect(jsonPath("$.messages").value(5));

        mockMvc.perform(get("/api/v1/chats/unread"))
                .andExpect(status().isUnauthorized());
    }


    @Test
    void deliveryIsConfirmedForTheAuthenticatedUserWithOrWithoutAMessageId() throws Exception {

        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/chats/{id}/delivered", conversationId)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + messageId + "\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent());

        verify(chatService).markDelivered(new ChatDeliveredRequest(conversationId, messageId), currentUser);

        mockMvc.perform(post("/api/v1/chats/{id}/delivered", conversationId).with(jwt()))
                .andExpect(status().isNoContent());

        verify(chatService).markDelivered(new ChatDeliveredRequest(conversationId, null), currentUser);

        mockMvc.perform(post("/api/v1/chats/{id}/delivered", conversationId))
                .andExpect(status().isUnauthorized());
    }


    private static User user() {

        User user = new User(UUID.randomUUID().toString(), "leonardo", null, null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }
}
