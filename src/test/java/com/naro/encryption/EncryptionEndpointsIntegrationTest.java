package com.naro.encryption;

import com.jayway.jsonpath.JsonPath;
import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;
import com.naro.chat.domain.entity.Conversation;
import com.naro.chat.domain.enums.ConversationType;
import com.naro.chat.domain.repository.ConversationRepository;
import com.naro.encryption.domain.repository.UserEncryptionIdentityRepository;
import com.naro.encryption.domain.repository.UserEncryptionKeyRepository;
import com.naro.user.domain.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT → provisionamento → controller → service → PostgreSQL.
 *
 * O material "criptográfico" daqui são bytes aleatórios do tamanho certo:
 * o servidor só confere formato e nunca decifra nada.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.seed.enabled=false", "naro.security.e2ee.required=true"}
)
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class EncryptionEndpointsIntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ConversationRepository conversations;
    @Autowired private UserEncryptionIdentityRepository identities;
    @Autowired private UserEncryptionKeyRepository keys;

    @Test
    void statusExposesTheFeatureFlags() throws Exception {
        mvc.perform(get("/api/v1/encryption/status").with(account("status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.required").value(true));
    }

    @Test
    void registersIdentityAndFirstPasskeyAndStoresOnlyWrappedMaterial() throws Exception {
        var alice = account("alice");
        String proof = bytes(32);
        String credentialId = credentialId();
        String wrappedMasterKey = bytes(48);

        mvc.perform(get("/api/v1/encryption/identity").with(alice))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/encryption/identity").with(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson(proof, credentialId, wrappedMasterKey)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.algorithm").value("ECDH-P256"))
                .andExpect(jsonPath("$._links.keys.href").exists());

        mvc.perform(get("/api/v1/encryption/keys").with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.encryptionKeys", hasSize(1)))
                .andExpect(jsonPath("$._embedded.encryptionKeys[0].credentialId").value(credentialId))
                .andExpect(jsonPath("$._embedded.encryptionKeys[0].wrappedMasterKey").value(wrappedMasterKey))
                .andExpect(jsonPath("$._embedded.encryptionKeys[0].algorithm").value("AES-GCM"))
                .andExpect(jsonPath("$._embedded.encryptionKeys[0].version").value(1));

        UUID aliceId = me(alice);
        var stored = identities.findById(aliceId).orElseThrow();
        // Só o hash da prova é guardado, nunca a prova.
        assertThat(stored.getMasterKeyProofHash()).isNotEqualTo(proof);

        // A identidade é criada uma única vez.
        mvc.perform(post("/api/v1/encryption/identity").with(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson(proof, credentialId(), bytes(48))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E2EE_ALREADY_SET_UP"));
    }

    @Test
    void keysAreVisibleOnlyToTheirOwner() throws Exception {
        var owner = account("owner");
        var other = account("other");
        String credentialId = credentialId();
        setUp(owner, bytes(32), credentialId);

        mvc.perform(get("/api/v1/encryption/keys").with(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist());

        mvc.perform(get("/api/v1/encryption/identity").with(other))
                .andExpect(status().isNotFound());
    }

    @Test
    void additionalPasskeyRequiresProofOfTheMasterKey() throws Exception {
        var user = account("multi");
        String proof = bytes(32);
        setUp(user, proof, credentialId());
        String second = credentialId();

        mvc.perform(post("/api/v1/encryption/keys").with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyJson(second, bytes(32))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E2EE_KEY_PROOF_INVALID"));

        mvc.perform(post("/api/v1/encryption/keys").with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyJson(second, proof)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.credentialId").value(second));

        mvc.perform(post("/api/v1/encryption/keys").with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyJson(second, proof)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E2EE_KEY_EXISTS"));

        mvc.perform(get("/api/v1/encryption/keys").with(user))
                .andExpect(jsonPath("$._embedded.encryptionKeys", hasSize(2)));
    }

    @Test
    void passkeyCannotBeRegisteredBeforeSetup() throws Exception {
        mvc.perform(post("/api/v1/encryption/keys").with(account("nosetup"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyJson(credentialId(), bytes(32))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E2EE_SETUP_REQUIRED"));
    }

    @Test
    void ownerRevokesOwnPasskeyButNeverTheLastOne() throws Exception {
        var user = account("revoker");
        String proof = bytes(32);
        String first = credentialId();
        setUp(user, proof, first);
        String second = credentialId();
        mvc.perform(post("/api/v1/encryption/keys").with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyJson(second, proof)))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/encryption/keys/{id}", first).with(user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E2EE_KEY_PROOF_INVALID"));

        mvc.perform(delete("/api/v1/encryption/keys/{id}", first).with(user)
                        .header("X-E2EE-Key-Proof", proof))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/v1/encryption/keys/{id}", second).with(user)
                        .header("X-E2EE-Key-Proof", proof))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E2EE_LAST_KEY"));

        mvc.perform(get("/api/v1/encryption/keys").with(user))
                .andExpect(jsonPath("$._embedded.encryptionKeys[*].credentialId", containsInAnyOrder(second)));
    }

    @Test
    void nobodyRevokesSomeoneElsesPasskey() throws Exception {
        var owner = account("victim");
        var attacker = account("attacker");
        String ownerProof = bytes(32);
        String attackerProof = bytes(32);
        String ownerKey = credentialId();
        setUp(owner, ownerProof, ownerKey);
        setUp(attacker, attackerProof, credentialId());

        // Nem com a própria prova, nem com a do dono: a chave "não existe" para o atacante.
        mvc.perform(delete("/api/v1/encryption/keys/{id}", ownerKey).with(attacker)
                        .header("X-E2EE-Key-Proof", attackerProof))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/encryption/keys/{id}", ownerKey).with(attacker)
                        .header("X-E2EE-Key-Proof", ownerProof))
                .andExpect(status().isNotFound());

        UUID ownerId = me(owner);
        assertThat(keys.findAllByUser_IdOrderByCreatedAtAsc(ownerId))
                .extracting(key -> key.getCredentialId())
                .containsExactly(ownerKey);
    }

    @Test
    void publicKeysAreSharedOnlyWithConversationContacts() throws Exception {
        var alice = account("pk-alice");
        var bob = account("pk-bob");
        var stranger = account("pk-stranger");
        setUp(alice, bytes(32), credentialId());
        setUp(bob, bytes(32), credentialId());
        setUp(stranger, bytes(32), credentialId());
        UUID aliceId = me(alice);
        UUID bobId = me(bob);
        UUID strangerId = me(stranger);
        conversations.save(new Conversation(ConversationType.DIRECT,
                Set.of(users.findById(aliceId).orElseThrow(), users.findById(bobId).orElseThrow())));

        mvc.perform(get("/api/v1/encryption/identities").with(alice)
                        .param("userId", bobId.toString(), strangerId.toString(), aliceId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.identities[*].userId",
                        containsInAnyOrder(bobId.toString(), aliceId.toString())))
                .andExpect(jsonPath("$._embedded.identities[0].wrappedPrivateKey").doesNotExist());

        mvc.perform(get("/api/v1/encryption/identities").with(stranger).param("userId", aliceId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist());
    }

    @Test
    void malformedMaterialIsRejected() throws Exception {
        String badWrappedKey = """
                {"publicKey":"%s","wrappedPrivateKey":"%s","privateKeyIv":"%s","algorithm":"ECDH-P256","version":1,
                 "masterKeyProof":"%s","key":{"credentialId":"%s","wrappedMasterKey":"%s","iv":"%s","algorithm":"AES-GCM","version":1}}
                """.formatted(publicKey(), bytes(150), bytes(12), bytes(32), credentialId(), bytes(32), bytes(12));

        mvc.perform(post("/api/v1/encryption/identity").with(account("malformed"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badWrappedKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E2EE_INVALID_PAYLOAD"));
    }

    private void setUp(RequestPostProcessor account, String proof, String credentialId) throws Exception {
        mvc.perform(post("/api/v1/encryption/identity").with(account)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson(proof, credentialId, bytes(48))))
                .andExpect(status().isCreated());
    }

    private UUID me(RequestPostProcessor account) throws Exception {
        String body = mvc.perform(get("/api/users/me").with(account))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static RequestPostProcessor account(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(token -> token.subject(subject).claim("preferred_username", name + "-" + suffix));
    }

    private static String identityJson(String proof, String credentialId, String wrappedMasterKey) {
        return """
                {"publicKey":"%s","wrappedPrivateKey":"%s","privateKeyIv":"%s","algorithm":"ECDH-P256","version":1,
                 "masterKeyProof":"%s","key":{"credentialId":"%s","wrappedMasterKey":"%s","iv":"%s","algorithm":"AES-GCM","version":1}}
                """.formatted(publicKey(), bytes(154), bytes(12), proof, credentialId, wrappedMasterKey, bytes(12));
    }

    private static String keyJson(String credentialId, String proof) {
        return """
                {"credentialId":"%s","wrappedMasterKey":"%s","iv":"%s","algorithm":"AES-GCM","version":1,"masterKeyProof":"%s"}
                """.formatted(credentialId, bytes(48), bytes(12), proof);
    }

    private static String publicKey() {
        byte[] point = new byte[65];
        RANDOM.nextBytes(point);
        point[0] = 0x04;
        return Base64.getEncoder().encodeToString(point);
    }

    private static String credentialId() {
        byte[] id = new byte[32];
        RANDOM.nextBytes(id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id);
    }

    private static String bytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }
}
