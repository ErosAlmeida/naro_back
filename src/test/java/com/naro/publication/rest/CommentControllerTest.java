package com.naro.publication.rest;

import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.publication.application.dto.CommentResponse;
import com.naro.publication.application.dto.CreateCommentRequest;
import com.naro.publication.application.service.CommentService;
import com.naro.security.SecurityConfig;
import com.naro.channel.application.dto.ChannelSummaryResponse;
import com.naro.user.application.service.CurrentUserService;
import com.naro.user.domain.entity.User;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.application.service.ChannelService;

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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.naro.support.Fixtures.user;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommentController.class)
@Import({SecurityConfig.class, CommentModelAssembler.class})
class CommentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommentService commentService;

    @MockitoBean(name = "currentUserService")
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final User currentUser = user("leonardo");

    private final Channel currentChannel = com.naro.support.Fixtures.persisted(com.naro.support.Fixtures.channel(currentUser, "leonardo"));

    @MockitoBean
    private ChannelService channelService;

    private final UUID publicationId = UUID.randomUUID();


    @BeforeEach
    void setUp() {
        when(currentUserService.resolve(any())).thenReturn(currentUser);
        when(channelService.getOwnedChannel(currentChannel.getId(), currentUser)).thenReturn(currentChannel);
    }


    @Test
    void commentOnPublicationReturns201() throws Exception {

        CommentResponse response = response(UUID.randomUUID(), null, "Muito boa!");

        when(commentService.commentOnPublication(eq(publicationId), eq(new CreateCommentRequest("Muito boa!")), eq(currentChannel)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/publications/{publicationId}/comments", publicationId)
                        .header("X-Channel-Id", currentChannel.getId()).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "Muito boa!"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/publications/" + publicationId + "/comments/" + response.id()))
                // O HAL mantém os campos null no JSON: o front lê com .nullable().
                .andExpect(jsonPath("$.parentCommentId").value(nullValue()))
                .andExpect(jsonPath("$.author.id").value(currentChannel.getId().toString()))
                .andExpect(jsonPath("$.author.slug").value("leonardo"))
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.replyCount").value(0))
                // Um comentário não tem GET próprio, então não há self.
                .andExpect(jsonPath("$._links.self").doesNotExist())
                .andExpect(jsonPath("$._links.publication.href")
                        .value("http://localhost/api/v1/publications/" + publicationId))
                .andExpect(jsonPath("$._links.author.href")
                        .value("http://localhost/api/v1/channels/" + currentChannel.getId()))
                .andExpect(jsonPath("$._links.replies.href")
                        .value("http://localhost/api/v1/comments/" + response.id() + "/comments?page=0&size=20"))
                .andExpect(jsonPath("$._links.likes.href")
                        .value("http://localhost/api/v1/comments/" + response.id() + "/likes?page=0&size=20"));
    }


    @Test
    void replyReturns201WithParentAndIgnoresIdentityFromPayload() throws Exception {

        UUID parentId = UUID.randomUUID();
        CommentResponse response = response(UUID.randomUUID(), parentId, "Discordo");

        when(commentService.reply(any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/comments/{commentId}/comments", parentId)
                        .header("X-Channel-Id", currentChannel.getId()).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "Discordo", "authorId": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentCommentId").value(parentId.toString()));

        verify(commentService).reply(parentId, new CreateCommentRequest("Discordo"), currentChannel);
    }


    @Test
    void replyToNonexistentCommentReturns404() throws Exception {

        when(commentService.reply(any(), any(), any())).thenThrow(new NotFoundException("Comment not found"));

        mockMvc.perform(post("/api/v1/comments/{commentId}/comments", UUID.randomUUID())
                        .header("X-Channel-Id", currentChannel.getId()).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "Oi"}
                                """))
                .andExpect(status().isNotFound());
    }


    @Test
    void blankCommentReturns400() throws Exception {

        mockMvc.perform(post("/api/v1/comments/{commentId}/comments", UUID.randomUUID())
                        .header("X-Channel-Id", currentChannel.getId()).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "   "}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(commentService);
    }


    @Test
    void withoutTokenReturns401() throws Exception {

        mockMvc.perform(post("/api/v1/publications/{publicationId}/comments", publicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "Oi"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(commentService);
    }


    @Test
    void repliesArePaginated() throws Exception {

        UUID parentId = UUID.randomUUID();

        when(commentService.getReplies(parentId, currentChannel, PageRequest.of(0, 100)))
                .thenReturn(new PageImpl<>(List.of(response(UUID.randomUUID(), parentId, "resposta"))));

        mockMvc.perform(get("/api/v1/comments/{commentId}/comments", parentId)
                        .param("size", "5000")
                        .header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.comments[0].content").value("resposta"));
    }


    @Test
    void deletingSomeoneElsesCommentReturns403() throws Exception {

        UUID commentId = UUID.randomUUID();

        doThrow(new ForbiddenException("You can only delete your own comments"))
                .when(commentService).delete(publicationId, commentId, currentChannel);

        mockMvc.perform(delete("/api/v1/publications/{publicationId}/comments/{commentId}", publicationId, commentId)
                        .header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isForbidden());
    }


    @Test
    void deletingOwnCommentReturns204() throws Exception {

        UUID commentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/publications/{publicationId}/comments/{commentId}", publicationId, commentId)
                        .header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isNoContent());

        verify(commentService).delete(publicationId, commentId, currentChannel);
    }


    private CommentResponse response(UUID id, UUID parentId, String content) {
        ChannelSummaryResponse author = new ChannelSummaryResponse(currentChannel.getId(), "leonardo", "leonardo", "http://minio/avatar.png");

        return new CommentResponse(id, publicationId, parentId, content, author, 0, false, 0, Instant.now());
    }
}
