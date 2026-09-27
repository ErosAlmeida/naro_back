package com.naro.chat.application.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationFocusRegistryTest {

    private final ConversationFocusRegistry registry = new ConversationFocusRegistry();

    private final UUID user = UUID.randomUUID();

    private final UUID conversation = UUID.randomUUID();

    @Test
    void aSessionShowingTheConversationMeansTheUserIsViewingIt() {
        assertThat(registry.isViewing(user, conversation)).isFalse();

        registry.focus(user, "phone", conversation);

        assertThat(registry.isViewing(user, conversation)).isTrue();
        assertThat(registry.isViewing(user, UUID.randomUUID())).isFalse();
        assertThat(registry.isViewing(UUID.randomUUID(), conversation)).isFalse();
    }

    @Test
    void eachSessionHasItsOwnFocusAcrossDevices() {
        UUID other = UUID.randomUUID();
        registry.focus(user, "laptop", conversation);
        registry.focus(user, "phone", other);

        registry.clear(user, "laptop");

        assertThat(registry.isViewing(user, conversation)).isFalse();
        assertThat(registry.isViewing(user, other)).isTrue();
    }

    @Test
    void switchingConversationsReplacesTheSessionFocus() {
        UUID next = UUID.randomUUID();
        registry.focus(user, "laptop", conversation);

        registry.focus(user, "laptop", next);

        assertThat(registry.isViewing(user, conversation)).isFalse();
        assertThat(registry.isViewing(user, next)).isTrue();
    }

    @Test
    void clearingIsIdempotent() {
        registry.clear(user, "unknown");
        registry.focus(user, "phone", conversation);
        registry.clear(user, "phone");
        registry.clear(user, "phone");

        assertThat(registry.isViewing(user, conversation)).isFalse();
    }
}
