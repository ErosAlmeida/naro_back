package com.naro.publication;

import com.jayway.jsonpath.JsonPath;
import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;
import com.naro.publication.domain.entity.Publication;
import com.naro.publication.domain.repository.PublicationRepository;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.repository.ChannelRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    private ChannelRepository channelRepository;

    @Autowired
    private PublicationRepository publicationRepository;

    private Channel leonardo;

    private Channel maria;

    private Publication publication;


    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        leonardo = createChannel("leonardo" + suffix);
        maria = createChannel("maria" + suffix);
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
                .andExpect(jsonPath("$.content[0].likedByCurrentChannel").value(true))
                .andExpect(jsonPath("$.content[0].replyCount").value(1))
                .andExpect(jsonPath("$.content[0].replies").doesNotExist());

        // Like no comentário aninhado, visto por quem curtiu e por quem não curtiu.
        mockMvc.perform(get("/api/v1/comments/{id}/comments", b).with(as(maria)))
                .andExpect(jsonPath("$.content[0].likeCount").value(1))
                .andExpect(jsonPath("$.content[0].likedByCurrentChannel").value(true));
        mockMvc.perform(get("/api/v1/comments/{id}/comments", b).with(as(leonardo)))
                .andExpect(jsonPath("$.content[0].likedByCurrentChannel").value(false));

        // A publicação conta comentários de todos os níveis e só os próprios likes.
        mockMvc.perform(get("/api/v1/publications/{id}", publication.getId()).with(as(leonardo)))
                .andExpect(jsonPath("$.commentCount").value(3))
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByCurrentChannel").value(false));

        // Remover o próprio like.
        mockMvc.perform(delete("/api/v1/comments/{id}/likes/me", c).with(as(maria)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/comments/{id}/likes/summary", c).with(as(maria)))
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.likedByCurrentChannel").value(false));

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


    @Test
    void channelsOfSameOwnerHaveIndependentLikesAndComments() throws Exception {
        Channel second = channelRepository.save(com.naro.support.Fixtures.channel(leonardo.getOwner(), "second-" + UUID.randomUUID()));
        likePublication(publication.getId(), leonardo).andExpect(jsonPath("$.likeCount").value(1));
        mockMvc.perform(get("/api/v1/publications/{id}/likes/summary", publication.getId()).with(as(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedByCurrentChannel").value(false));
        likePublication(publication.getId(), second).andExpect(jsonPath("$.likeCount").value(2));
        likePublication(publication.getId(), second).andExpect(jsonPath("$.likeCount").value(2));

        UUID comment = commentOnPublication(publication.getId(), "second channel", second);
        mockMvc.perform(delete("/api/v1/publications/{p}/comments/{c}", publication.getId(), comment).with(as(leonardo)))
                .andExpect(status().isForbidden());
        likeComment(comment, leonardo).andExpect(jsonPath("$.likeCount").value(1));
        likeComment(comment, second).andExpect(jsonPath("$.likeCount").value(2));
        likeComment(comment, second).andExpect(jsonPath("$.likeCount").value(2));
        mockMvc.perform(delete("/api/v1/publications/{id}/likes/me", publication.getId()).with(as(second)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/publications/{id}/likes/summary", publication.getId()).with(as(leonardo)))
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByCurrentChannel").value(true));
    }

    @Test
    void cannotActAsAnotherOwnersChannel() throws Exception {
        RequestPostProcessor forgedChannel = request -> {
            as(leonardo).postProcessRequest(request);
            request.removeHeader("X-Channel-Id");
            request.addHeader("X-Channel-Id", maria.getId());
            return request;
        };
        mockMvc.perform(post("/api/v1/publications/{id}/likes", publication.getId()).with(forgedChannel))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/publications/{id}/likes/me", publication.getId()).with(forgedChannel))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/publications/{id}/comments", publication.getId()).with(forgedChannel)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"forged\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/v1/publications").file(image()).param("content", "forged").with(forgedChannel))
                .andExpect(status().isForbidden());
    }

    @Test
    void readsDoNotRequireChannelButWritesDo() throws Exception {
        RequestPostProcessor withoutChannel = request -> {
            as(leonardo).postProcessRequest(request);
            request.removeHeader("X-Channel-Id");
            return request;
        };
        likePublication(publication.getId(), leonardo);
        mockMvc.perform(get("/api/v1/publications/{id}", publication.getId()).with(withoutChannel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByCurrentChannel").value(false));
        mockMvc.perform(get("/api/v1/publications/{id}/likes/summary", publication.getId()).with(withoutChannel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedByCurrentChannel").value(false));
        mockMvc.perform(post("/api/v1/publications/{id}/likes", publication.getId()).with(withoutChannel))
                .andExpect(status().isBadRequest());
    }

    @Test
    void channelCanPublishMultiplePublicationsAndFilterByChannelAuthor() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(multipart("/api/v1/publications").file(image()).param("content", "post " + i).with(as(leonardo)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.author.id").value(leonardo.getId().toString()))
                    .andExpect(jsonPath("$.author.name").value(leonardo.getName()))
                    .andExpect(jsonPath("$.author.slug").value(leonardo.getSlug()))
                    .andExpect(jsonPath("$.authorUsername").doesNotExist());
        }
        mockMvc.perform(get("/api/v1/publications").param("authorId", leonardo.getId().toString()).with(as(leonardo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].author.id").value(leonardo.getId().toString()))
                .andExpect(jsonPath("$.content[1].author.id").value(leonardo.getId().toString()));
    }

    @Test
    void createReadAndUpdateChannelWithOwnershipAndSlugValidation() throws Exception {
        String slug = "new-" + UUID.randomUUID();
        String body = mockMvc.perform(multipart("/api/v1/channels").param("name", "New channel").param("slug", slug).with(as(leonardo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(leonardo.getOwner().getId().toString()))
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mockMvc.perform(get("/api/v1/channels/{id}", id).with(as(leonardo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(slug));
        mockMvc.perform(get("/api/v1/channels/slug/{slug}", slug).with(as(leonardo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/channels/{id}", id)
                        .param("name", "Renamed").param("slug", slug + "-updated").with(as(maria)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/channels/{id}", id)
                        .param("name", "Renamed").param("slug", maria.getSlug()).with(as(leonardo)))
                .andExpect(status().isConflict());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/channels/{id}", id)
                        .param("name", "Renamed").param("slug", slug + "-updated").with(as(leonardo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));
        mockMvc.perform(get("/api/v1/channels/slug/{slug}", slug).with(as(leonardo)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/channels/slug/{slug}", slug + "-updated").with(as(leonardo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void feedAndCommentsCarryTheAuthorChannelPhoto() throws Exception {
        // Canal com foto: publicação e comentário precisam trazer a URL dela.
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/channels/{id}", leonardo.getId())
                        .file(new MockMultipartFile("profile_image", "avatar.png", "image/png", new byte[]{1, 2, 3}))
                        .param("name", leonardo.getName()).param("slug", leonardo.getSlug()).with(as(leonardo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").isNotEmpty());

        mockMvc.perform(multipart("/api/v1/publications").file(image()).param("content", "com foto").with(as(leonardo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.profileImageUrl").isNotEmpty());

        mockMvc.perform(get("/api/v1/publications").param("authorId", leonardo.getId().toString()).with(as(leonardo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].author.profileImageUrl").isNotEmpty());

        commentOnPublication(publication.getId(), "oi", leonardo);
        mockMvc.perform(get("/api/v1/publications/{id}/comments", publication.getId()).with(as(leonardo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].author.profileImageUrl").isNotEmpty());

        // Canal sem foto continua respondendo autoria, só sem URL.
        mockMvc.perform(get("/api/v1/publications/{id}", publication.getId()).with(as(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author.slug").value(maria.getSlug()))
                .andExpect(jsonPath("$.author.profileImageUrl").doesNotExist());
    }

    @Test
    void myChannelsAnswersWhetherTheUserAlreadyHasAChannel() throws Exception {
        // Quem ainda não criou nada recebe uma lista vazia, não 404.
        User newcomer = userRepository.save(new User("kc-newcomer-" + UUID.randomUUID(), "newcomer", null, null, null));
        mockMvc.perform(get("/api/v1/channels/me").with(asUser(newcomer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/v1/channels/me").with(as(leonardo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(leonardo.getId().toString()))
                .andExpect(jsonPath("$[0].ownerId").value(leonardo.getOwner().getId().toString()))
                .andExpect(jsonPath("$[0].slug").value(leonardo.getSlug()))
                // Canais de outro dono nunca aparecem aqui.
                .andExpect(jsonPath("$[?(@.id == '" + maria.getId() + "')]").isEmpty());

        // Um segundo canal entra na lista, do mais antigo para o mais novo.
        String slug = "segundo-" + UUID.randomUUID();
        mockMvc.perform(multipart("/api/v1/channels").param("name", "Segundo").param("slug", slug).with(as(leonardo)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/channels/me").with(as(leonardo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(leonardo.getId().toString()))
                .andExpect(jsonPath("$[1].slug").value(slug));
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "image.png", "image/png", new byte[]{1, 2, 3});
    }

    private UUID commentOnPublication(UUID publicationId, String content, Channel author) throws Exception {
        return createComment("/api/v1/publications/{id}/comments", publicationId, content, author);
    }


    private UUID reply(UUID parentCommentId, String content, Channel author) throws Exception {
        return createComment("/api/v1/comments/{id}/comments", parentCommentId, content, author);
    }


    private UUID createComment(String url, UUID id, String content, Channel author) throws Exception {

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


    private ResultActions likePublication(UUID publicationId, Channel channel) throws Exception {
        return mockMvc.perform(post("/api/v1/publications/{id}/likes", publicationId).with(as(channel))).andExpect(status().isOk());
    }


    private ResultActions likeComment(UUID commentId, Channel channel) throws Exception {
        return mockMvc.perform(post("/api/v1/comments/{id}/likes", commentId).with(as(channel))).andExpect(status().isOk());
    }


    /** JWT do Keycloak com o subject do usuário: a identidade vem do token. */
    private Channel createChannel(String slug) {
        User owner = userRepository.save(new User("kc-" + slug, slug, null, null, null));
        return channelRepository.save(com.naro.support.Fixtures.channel(owner, slug));
    }

    /** Token sem canal selecionado: é o estado de quem ainda não criou nenhum. */
    private static RequestPostProcessor asUser(User user) {
        return jwt().jwt(token -> token
                .subject(user.getKeycloakId())
                .claim("preferred_username", user.getUsername()));
    }

    private static RequestPostProcessor as(Channel channel) {
        return request -> {
            request.addHeader("X-Channel-Id", channel.getId());
            return jwt().jwt(token -> token
                    .subject(channel.getOwner().getKeycloakId())
                    .claim("preferred_username", channel.getOwner().getUsername())).postProcessRequest(request);
        };
    }
}
