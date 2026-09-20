package com.naro.publication;

import com.jayway.jsonpath.JsonPath;
import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;
import com.naro.publication.domain.entity.Publication;
import com.naro.publication.domain.repository.PublicationRepository;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP → service → banco, sem mocks:
 *
 *   Publication ❤️
 *    └── A ❤️
 *         └── B
 *              └── C ❤️
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
        PostgresTestcontainersConfiguration.class,
        MinioTestcontainersConfiguration.class
})
@Testcontainers(disabledWithoutDocker = true)
class CommentsAndLikesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PublicationRepository publicationRepository;

    private User leonardo;

    private User maria;

    private Publication publication;


    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        leonardo = userRepository.save(new User("kc-leo-" + suffix, "leonardo" + suffix, null, null, null));
        maria = userRepository.save(new User("kc-maria-" + suffix, "maria" + suffix, null, null, null));
        publication = publicationRepository.save(new Publication(maria, "post", "publications/" + suffix + ".png"));
    }


    @Test
    void commentsNestAtAnyDepthAndEveryLevelCanBeLiked() throws Exception {

        UUID a = commentOnPublication(publication.getId(), "A", leonardo);
        UUID b = reply(a, "B", maria);
        UUID c = reply(b, "C", leonardo);

        // Resposta de resposta continua ligada à publicação original.
        mockMvc.perform(get("/api/v1/comments/{id}/comments", b).with(as(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(c.toString()))
                .andExpect(jsonPath("$.content[0].parentCommentId").value(b.toString()))
                .andExpect(jsonPath("$.content[0].publicationId").value(publication.getId().toString()));

        likePublication(publication.getId(), maria).andExpect(jsonPath("$.likeCount").value(1));
        likeComment(a, maria).andExpect(jsonPath("$.likeCount").value(1));
        likeComment(c, maria).andExpect(jsonPath("$.likeCount").value(1));

        // Like duplicado não conta duas vezes (publicação e comentário).
        likePublication(publication.getId(), maria).andExpect(jsonPath("$.likeCount").value(1));
        likeComment(c, maria).andExpect(jsonPath("$.likeCount").value(1));

        // Só comentários diretos na publicação; respostas vêm pela contagem.
        mockMvc.perform(get("/api/v1/publications/{id}/comments", publication.getId()).with(as(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a.toString()))
                .andExpect(jsonPath("$.content[0].author.id").value(leonardo.getId().toString()))
                .andExpect(jsonPath("$.content[0].likeCount").value(1))
                .andExpect(jsonPath("$.content[0].likedByCurrentUser").value(true))
                .andExpect(jsonPath("$.content[0].replyCount").value(1))
                .andExpect(jsonPath("$.content[0].replies").doesNotExist());

        // Like no comentário aninhado, visto por quem curtiu e por quem não curtiu.
        mockMvc.perform(get("/api/v1/comments/{id}/comments", b).with(as(maria)))
                .andExpect(jsonPath("$.content[0].likeCount").value(1))
                .andExpect(jsonPath("$.content[0].likedByCurrentUser").value(true));
        mockMvc.perform(get("/api/v1/comments/{id}/comments", b).with(as(leonardo)))
                .andExpect(jsonPath("$.content[0].likedByCurrentUser").value(false));

        // A publicação conta comentários de todos os níveis e só os próprios likes.
        mockMvc.perform(get("/api/v1/publications/{id}", publication.getId()).with(as(leonardo)))
                .andExpect(jsonPath("$.commentCount").value(3))
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByCurrentUser").value(false));

        // Remover o próprio like.
        mockMvc.perform(delete("/api/v1/comments/{id}/likes/me", c).with(as(maria)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/comments/{id}/likes/summary", c).with(as(maria)))
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.likedByCurrentUser").value(false));

        mockMvc.perform(delete("/api/v1/publications/{id}/likes/me", publication.getId()).with(as(maria)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/publications/{id}/likes/summary", publication.getId()).with(as(maria)))
                .andExpect(jsonPath("$.likeCount").value(0));

        // Maria não apaga o comentário do Leonardo.
        mockMvc.perform(delete("/api/v1/publications/{p}/comments/{c}", publication.getId(), a).with(as(maria)))
                .andExpect(status().isForbidden());

        // O autor apaga A: a sub-árvore (B, C) vai junto.
        mockMvc.perform(delete("/api/v1/publications/{p}/comments/{c}", publication.getId(), a).with(as(leonardo)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/comments/{id}/comments", b).with(as(leonardo)))
                .andExpect(status().isNotFound());
    }


    @Test
    void authorComesFromTheTokenNotFromThePayload() throws Exception {

        mockMvc.perform(post("/api/v1/publications/{id}/comments", publication.getId())
                        .with(as(leonardo))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Oi\", \"authorId\": \"" + maria.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.id").value(leonardo.getId().toString()));
    }


    @Test
    void replyToMissingCommentAndCommentOnMissingPublicationReturn404() throws Exception {

        mockMvc.perform(post("/api/v1/comments/{id}/comments", UUID.randomUUID())
                        .with(as(leonardo))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Oi\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/publications/{id}/comments", UUID.randomUUID())
                        .with(as(leonardo))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Oi\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/comments/{id}/likes", UUID.randomUUID()).with(as(leonardo)))
                .andExpect(status().isNotFound());
    }


    private UUID commentOnPublication(UUID publicationId, String content, User author) throws Exception {
        return createComment("/api/v1/publications/{id}/comments", publicationId, content, author);
    }


    private UUID reply(UUID parentCommentId, String content, User author) throws Exception {
        return createComment("/api/v1/comments/{id}/comments", parentCommentId, content, author);
    }


    private UUID createComment(String url, UUID id, String content, User author) throws Exception {

        String body = mockMvc.perform(post(url, id)
                        .with(as(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"" + content + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.id").value(author.getId().toString()))
                .andExpect(jsonPath("$.publicationId").value(publication.getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(body, "$.id"));
    }


    private ResultActions likePublication(UUID publicationId, User user) throws Exception {
        return mockMvc.perform(post("/api/v1/publications/{id}/likes", publicationId).with(as(user))).andExpect(status().isOk());
    }


    private ResultActions likeComment(UUID commentId, User user) throws Exception {
        return mockMvc.perform(post("/api/v1/comments/{id}/likes", commentId).with(as(user))).andExpect(status().isOk());
    }


    /** JWT do Keycloak com o subject do usuário: a identidade vem do token. */
    private static RequestPostProcessor as(User user) {
        return jwt().jwt(token -> token
                .subject(user.getKeycloakId())
                .claim("preferred_username", user.getUsername()));
    }
}
