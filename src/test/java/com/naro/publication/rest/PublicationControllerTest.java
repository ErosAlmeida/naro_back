package com.naro.publication.rest;

import com.naro.exception.BadRequestException;
import com.naro.exception.NotFoundException;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.publication.application.service.PublicationService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicationController.class)
@Import(SecurityConfig.class)
class PublicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicationService publicationService;

    @MockitoBean(name = "currentUserService")
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final User currentUser = user();

    private final MockMultipartFile image =
            new MockMultipartFile("image", "photo.png", "image/png", new byte[]{1, 2, 3});


    @BeforeEach
    void setUp() {
        when(currentUserService.resolve(any())).thenReturn(currentUser);
    }


    // ---------------------------------------------------------------
    // Criação (regressão do endpoint que já existia)
    // ---------------------------------------------------------------

    @Test
    void createReturns201WithLocation() throws Exception {

        PublicationResponse response = response(UUID.randomUUID(), 0, 0, false);

        when(publicationService.create(any(), eq(currentUser))).thenReturn(response);

        mockMvc.perform(multipart("/api/v1/publications")
                        .file(image)
                        .param("content", "Minha publicação")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/publications/" + response.id()))
                .andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.commentCount").value(0))
                .andExpect(jsonPath("$.likedByCurrentUser").value(false));
    }


    @Test
    void createWithoutImageReturns400() throws Exception {

        mockMvc.perform(multipart("/api/v1/publications")
                        .param("content", "Minha publicação")
                        .with(jwt()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(publicationService);
    }


    @Test
    void createWithBlankContentReturns400() throws Exception {

        mockMvc.perform(multipart("/api/v1/publications")
                        .file(image)
                        .param("content", "   ")
                        .with(jwt()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(publicationService);
    }


    @Test
    void createWithUnsupportedImageReturns400() throws Exception {

        when(publicationService.create(any(), eq(currentUser)))
                .thenThrow(new BadRequestException("Only JPEG, PNG and WEBP images are allowed"));

        mockMvc.perform(multipart("/api/v1/publications")
                        .file(new MockMultipartFile("image", "anim.gif", "image/gif", new byte[]{1}))
                        .param("content", "Minha publicação")
                        .with(jwt()))
                .andExpect(status().isBadRequest());
    }


    @Test
    void createWithoutTokenReturns401() throws Exception {

        mockMvc.perform(multipart("/api/v1/publications")
                        .file(image)
                        .param("content", "Minha publicação"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(publicationService);
    }


    // ---------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------

    @Test
    void getByIdReturnsPublicationWithEngagement() throws Exception {

        UUID publicationId = UUID.randomUUID();

        when(publicationService.getById(publicationId, currentUser))
                .thenReturn(response(publicationId, 124, 17, true));

        mockMvc.perform(get("/api/v1/publications/{publicationId}", publicationId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(publicationId.toString()))
                .andExpect(jsonPath("$.likeCount").value(124))
                .andExpect(jsonPath("$.commentCount").value(17))
                .andExpect(jsonPath("$.likedByCurrentUser").value(true));
    }


    @Test
    void getByIdOfNonexistentPublicationReturns404() throws Exception {

        UUID publicationId = UUID.randomUUID();

        when(publicationService.getById(publicationId, currentUser))
                .thenThrow(new NotFoundException("Publication not found"));

        mockMvc.perform(get("/api/v1/publications/{publicationId}", publicationId)
                        .with(jwt()))
                .andExpect(status().isNotFound());
    }


    @Test
    void getByIdWithoutTokenReturns401() throws Exception {

        mockMvc.perform(get("/api/v1/publications/{publicationId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(publicationService);
    }


    @Test
    void getPublicationsPassesAuthorFilterAndClampsPageSize() throws Exception {

        UUID authorId = UUID.randomUUID();

        when(publicationService.getPublications(any(), any(), any()))
                .thenReturn(new PageImpl<>(
                        List.of(response(UUID.randomUUID(), 1, 0, false)),
                        PageRequest.of(1, 100), 201));

        mockMvc.perform(get("/api/v1/publications")
                        .param("authorId", authorId.toString())
                        .param("page", "1")
                        .param("size", "500")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].likeCount").value(1))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(100))
                .andExpect(jsonPath("$.page.totalElements").value(201))
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist());

        verify(publicationService)
                .getPublications(authorId, currentUser, PageRequest.of(1, 100));
    }


    @Test
    void getPublicationsWithoutAuthorListsAll() throws Exception {

        when(publicationService.getPublications(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/publications")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));

        verify(publicationService)
                .getPublications(null, currentUser, PageRequest.of(0, 20));
    }


    private PublicationResponse response(
            UUID id,
            long likeCount,
            long commentCount,
            boolean likedByCurrentUser
    ) {

        return new PublicationResponse(
                id,
                currentUser.getId(),
                currentUser.getUsername(),
                "Minha publicação",
                "http://minio/image.png",
                Instant.now(),
                likeCount,
                commentCount,
                likedByCurrentUser
        );
    }


    private static User user() {

        User user = new User(UUID.randomUUID().toString(), "leonardo", null, null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }
}
