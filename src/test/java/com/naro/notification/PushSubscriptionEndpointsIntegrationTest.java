package com.naro.notification;

import com.jayway.jsonpath.JsonPath;
import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;
import com.naro.notification.domain.repository.PushSubscriptionRepository;
import com.naro.notification.webpush.WebPushTestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cadastro de aparelhos com chaves VAPID de verdade (geradas por execução).
 * O dono é sempre o do JWT; nada aqui aceita o id de outra conta.
 */
@SpringBootTest(properties = {"app.seed.enabled=false", "naro.push.max-subscriptions-per-user=3"})
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class PushSubscriptionEndpointsIntegrationTest {

    private static final String[] VAPID = WebPushTestSupport.vapidKeys();

    @DynamicPropertySource
    static void vapid(DynamicPropertyRegistry registry) {
        registry.add("naro.push.vapid.public-key", () -> VAPID[0]);
        registry.add("naro.push.vapid.private-key", () -> VAPID[1]);
        registry.add("naro.push.vapid.subject", () -> "mailto:ops@naro.test");
    }

    @Autowired private MockMvc mvc;
    @Autowired private PushSubscriptionRepository subscriptions;

    @Test
    void everyEndpointRequiresAnAuthenticatedAccount() throws Exception {
        mvc.perform(get("/api/v1/push/vapid-public-key")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/push/subscriptions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/push/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body(endpoint())))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/push/subscriptions/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyThePublicVapidKeyReachesTheBrowser() throws Exception {
        String response = mvc.perform(get("/api/v1/push/vapid-public-key").with(account("vapid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.publicKey").value(VAPID[0]))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(VAPID[1]);
    }

    @Test
    void theSameBrowserRegisteringAgainUpdatesInsteadOfDuplicating() throws Exception {
        var alice = account("alice");
        String endpoint = endpoint();

        String id = register(alice, endpoint, WebPushTestSupport.browser(), 201);

        WebPushTestSupport.Browser rotated = WebPushTestSupport.browser();
        assertThat(register(alice, endpoint, rotated, 200)).isEqualTo(id);

        var stored = subscriptions.findByEndpoint(endpoint).orElseThrow();
        assertThat(stored.getId()).hasToString(id);
        assertThat(stored.getP256dh()).isEqualTo(rotated.p256dh());
        assertThat(stored.getAuth()).isEqualTo(rotated.auth());

        mvc.perform(get("/api/v1/push/subscriptions").with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.pushSubscriptions", hasSize(1)))
                .andExpect(jsonPath("$._embedded.pushSubscriptions[0].id").value(id))
                // O endpoint e as chaves nunca voltam para o cliente.
                .andExpect(jsonPath("$._embedded.pushSubscriptions[0].endpoint").doesNotExist())
                .andExpect(jsonPath("$._embedded.pushSubscriptions[0].p256dh").doesNotExist());
    }

    @Test
    void eachDeviceKeepsItsOwnSubscription() throws Exception {
        var alice = account("devices");

        String phone = register(alice, endpoint(), WebPushTestSupport.browser(), 201);
        String laptop = register(alice, "https://web.push.apple.com/" + UUID.randomUUID(), WebPushTestSupport.browser(), 201);

        assertThat(phone).isNotEqualTo(laptop);
        mvc.perform(get("/api/v1/push/subscriptions").with(alice))
                .andExpect(jsonPath("$._embedded.pushSubscriptions", hasSize(2)));
    }

    @Test
    void theOwnerComesFromTheTokenNeverFromTheBody() throws Exception {
        var alice = account("owner");
        var bob = account("intruder");
        UUID aliceId = me(alice);
        String endpoint = endpoint();
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();

        mvc.perform(post("/api/v1/push/subscriptions").with(bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpoint":"%s","keys":{"p256dh":"%s","auth":"%s"},"userId":"%s","user":{"id":"%s"}}
                                """.formatted(endpoint, browser.p256dh(), browser.auth(), aliceId, aliceId)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/push/subscriptions").with(alice))
                .andExpect(jsonPath("$._embedded").doesNotExist());
        mvc.perform(get("/api/v1/push/subscriptions").with(bob))
                .andExpect(jsonPath("$._embedded.pushSubscriptions", hasSize(1)));
    }

    @Test
    void anotherAccountCanNeitherSeeNorDeleteMyDevices() throws Exception {
        var alice = account("mine");
        var bob = account("theirs");
        String id = register(alice, endpoint(), WebPushTestSupport.browser(), 201);

        mvc.perform(get("/api/v1/push/subscriptions").with(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist());

        // 404, igual a um id inexistente: não revela que o aparelho existe.
        mvc.perform(delete("/api/v1/push/subscriptions/{id}", id).with(bob)).andExpect(status().isNotFound());
        assertThat(subscriptions.findById(UUID.fromString(id))).isPresent();

        mvc.perform(delete("/api/v1/push/subscriptions/{id}", id).with(alice)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/push/subscriptions/{id}", id).with(alice)).andExpect(status().isNotFound());
        assertThat(subscriptions.findById(UUID.fromString(id))).isEmpty();
    }

    @Test
    void aBrowserThatChangesAccountsStopsNotifyingThePreviousOne() throws Exception {
        var alice = account("before");
        var bob = account("after");
        String endpoint = endpoint();

        String aliceId = register(alice, endpoint, WebPushTestSupport.browser(), 201);
        String bobId = register(bob, endpoint, WebPushTestSupport.browser(), 201);

        assertThat(bobId).isNotEqualTo(aliceId);
        mvc.perform(get("/api/v1/push/subscriptions").with(alice)).andExpect(jsonPath("$._embedded").doesNotExist());
        mvc.perform(get("/api/v1/push/subscriptions").with(bob)).andExpect(jsonPath("$._embedded.pushSubscriptions", hasSize(1)));
        // O id antigo não serve mais para ninguém.
        mvc.perform(delete("/api/v1/push/subscriptions/{id}", aliceId).with(alice)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "https://169.254.169.254/latest/meta-data/",
            "https://localhost:8080/api/v1/users",
            "http://fcm.googleapis.com/fcm/send/x",
            "https://push.attacker.test/x"
    })
    void refusesEndpointsOutsideTheKnownPushServices(String endpoint) throws Exception {
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();

        mvc.perform(post("/api/v1/push/subscriptions").with(account("ssrf"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(endpoint, browser.p256dh(), browser.auth())))
                .andExpect(status().isBadRequest());

        assertThat(subscriptions.findByEndpoint(endpoint)).isEmpty();
    }

    @Test
    void refusesKeysThatCannotBeUsedForEncryption() throws Exception {
        var user = account("keys");
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();
        String notAPoint = browser.p256dh().substring(0, browser.p256dh().length() - 2) + "AA";

        for (String json : new String[]{
                body(endpoint(), notAPoint, browser.auth()),
                body(endpoint(), browser.p256dh(), "c2hvcnQ"),
                body(endpoint(), "!!!", browser.auth()),
                "{\"endpoint\":\"" + endpoint() + "\"}"
        }) {
            mvc.perform(post("/api/v1/push/subscriptions").with(user)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void keepsOnlyTheMostRecentDevicesPerAccount() throws Exception {
        var user = account("many");
        String oldest = register(user, endpoint(), WebPushTestSupport.browser(), 201);
        register(user, endpoint(), WebPushTestSupport.browser(), 201);
        register(user, endpoint(), WebPushTestSupport.browser(), 201);
        register(user, endpoint(), WebPushTestSupport.browser(), 201);

        mvc.perform(get("/api/v1/push/subscriptions").with(user))
                .andExpect(jsonPath("$._embedded.pushSubscriptions", hasSize(3)));
        assertThat(subscriptions.findById(UUID.fromString(oldest))).isEmpty();
    }

    @Test
    void storesTheDeviceLabelAndBrowserForDisplayOnly() throws Exception {
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();

        mvc.perform(post("/api/v1/push/subscriptions").with(account("label"))
                        .header("User-Agent", "Mozilla/5.0 (iPhone) " + "x".repeat(600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpoint":"%s","keys":{"p256dh":"%s","auth":"%s"},"deviceName":"  Safari\\nno iPhone  "}
                                """.formatted(endpoint(), browser.p256dh(), browser.auth())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceName").value("Safari no iPhone"))
                .andExpect(jsonPath("$.userAgent").value(("Mozilla/5.0 (iPhone) " + "x".repeat(600)).substring(0, 512)))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    private String register(RequestPostProcessor account, String endpoint, WebPushTestSupport.Browser browser, int expectedStatus)
            throws Exception {
        String response = mvc.perform(post("/api/v1/push/subscriptions").with(account)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(endpoint, browser.p256dh(), browser.auth())))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private UUID me(RequestPostProcessor account) throws Exception {
        String body = mvc.perform(get("/api/users/me").with(account))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static String body(String endpoint) {
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();
        return body(endpoint, browser.p256dh(), browser.auth());
    }

    private static String body(String endpoint, String p256dh, String auth) {
        return """
                {"endpoint":"%s","expirationTime":null,"keys":{"p256dh":"%s","auth":"%s"}}
                """.formatted(endpoint, p256dh, auth);
    }

    private static String endpoint() {
        return "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
    }

    private static RequestPostProcessor account(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(token -> token.subject(subject).claim("preferred_username", name + "-" + suffix));
    }
}
