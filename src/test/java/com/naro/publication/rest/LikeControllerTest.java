package com.naro.publication.rest;

import com.naro.exception.ConflictException;
import com.naro.exception.NotFoundException;
import com.naro.publication.application.dto.LikeResponse;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.service.LikeService;
import com.naro.security.SecurityConfig;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.naro.support.Fixtures.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LikeController.class)
@Import(SecurityConfig.class)
class LikeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LikeService likeService;

    @MockitoBean(name = "currentUserService")
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final User currentUser = user("leonardo");

    private final Channel currentChannel = com.naro.support.Fixtures.persisted(com.naro.support.Fixtures.channel(currentUser, "leonardo"));

    @MockitoBean
    private ChannelService channelService;

    private final UUID id = UUID.randomUUID();


    @BeforeEach
    void setUp() {
        when(currentUserService.resolve(any())).thenReturn(currentUser);
        when(channelService.getOwnedChannel(currentChannel.getId(), currentUser)).thenReturn(currentChannel);
    }


    /* ---------------------------------------------------------------
     * Publicação
     * --------------------------------------------------------------- */

    @Test
    void likePublicationAsTheAuthenticatedUser() throws Exception {

        when(likeService.likePublication(id, currentChannel)).thenReturn(new LikeSummaryResponse(5, true));

        mockMvc.perform(post("/api/v1/publications/{publicationId}/likes", id).header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(5))
                .andExpect(jsonPath("$.likedByCurrentChannel").value(true));
    }


    @Test
    void unlikePublicationReturns204() throws Exception {

        mockMvc.perform(delete("/api/v1/publications/{publicationId}/likes/me", id).header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isNoContent());

        verify(likeService).unlikePublication(id, currentChannel);
    }


    @Test
    void publicationLikeSummary() throws Exception {

        when(likeService.getPublicationLikeSummary(id, currentChannel)).thenReturn(new LikeSummaryResponse(100, false));

        mockMvc.perform(get("/api/v1/publications/{publicationId}/likes/summary", id).header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(100))
                .andExpect(jsonPath("$.likedByCurrentChannel").value(false));
    }


    @Test
    void listsWhoLikedThePublication() throws Exception {

        when(likeService.getPublicationLikes(id, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(new LikeResponse(UUID.randomUUID(), "maria", Instant.now()))));

        mockMvc.perform(get("/api/v1/publications/{publicationId}/likes", id).header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value("maria"));
    }


    @Test
    void likeOnNonexistentPublicationReturns404() throws Exception {

        when(likeService.likePublication(id, currentChannel)).thenThrow(new NotFoundException("Publication not found"));

        mockMvc.perform(post("/api/v1/publications/{publicationId}/likes", id).header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isNotFound());
    }


    /* ---------------------------------------------------------------
     * Comentário
     * --------------------------------------------------------------- */

    @Test
    void likeCommentAsTheAuthenticatedUser() throws Exception {

        when(likeService.likeComment(id, currentChannel)).thenReturn(new LikeSummaryResponse(1, true));

        mockMvc.perform(post("/api/v1/comments/{commentId}/likes", id).header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByCurrentChannel").value(true));

        verify(likeService).likeComment(id, currentChannel);
    }


    @Test
    void unlikeCommentReturns204() throws Exception {

        mockMvc.perform(delete("/api/v1/comments/{commentId}/likes/me", id).header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isNoContent());

        verify(likeService).unlikeComment(id, currentChannel);
    }


    @Test
    void commentLikeSummary() throws Exception {

        when(likeService.getCommentLikeSummary(id, currentChannel)).thenReturn(new LikeSummaryResponse(2, true));

        mockMvc.perform(get("/api/v1/comments/{commentId}/likes/summary", id).header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(2));
    }


    @Test
    void listsWhoLikedTheCommentWithClampedPageSize() throws Exception {

        when(likeService.getCommentLikes(id, PageRequest.of(0, 100)))
                .thenReturn(new PageImpl<>(List.of(new LikeResponse(UUID.randomUUID(), "maria", Instant.now()))));

        mockMvc.perform(get("/api/v1/comments/{commentId}/likes", id).param("size", "5000").header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value("maria"));
    }


    @Test
    void concurrentDuplicateLikeReturns409() throws Exception {

        when(likeService.likeComment(id, currentChannel)).thenThrow(new ConflictException("Already liked"));

        mockMvc.perform(post("/api/v1/comments/{commentId}/likes", id).header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isConflict());
    }


    /* ---------------------------------------------------------------
     * Segurança e entrada
     * --------------------------------------------------------------- */

    @Test
    void withoutTokenReturns401() throws Exception {

        mockMvc.perform(post("/api/v1/publications/{publicationId}/likes", id))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/comments/{commentId}/likes", id))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(likeService);
    }


    @Test
    void userIdInTheRequestIsIgnored() throws Exception {

        when(likeService.likeComment(id, currentChannel)).thenReturn(new LikeSummaryResponse(1, true));

        mockMvc.perform(post("/api/v1/comments/{commentId}/likes", id)
                        .param("userId", UUID.randomUUID().toString())
                        .header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isOk());

        verify(likeService).likeComment(id, currentChannel);
    }


    @Test
    void invalidIdReturns400() throws Exception {

        mockMvc.perform(post("/api/v1/comments/not-a-uuid/likes").header("X-Channel-Id", currentChannel.getId()).with(jwt()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(likeService);
    }
}
