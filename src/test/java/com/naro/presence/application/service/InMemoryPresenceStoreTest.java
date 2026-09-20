package com.naro.presence.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPresenceStoreTest {

    private final InMemoryPresenceStore store = new InMemoryPresenceStore();

    private final UUID leonardo = UUID.randomUUID();


    @Test
    void firstConnectionMakesUserOnline() {

        boolean becameOnline = store.addSession(leonardo, "chrome");

        assertThat(becameOnline).isTrue();
        assertThat(store.isOnline(leonardo)).isTrue();
    }


    @Test
    void secondConnectionKeepsUserOnlineWithoutNewTransition() {

        store.addSession(leonardo, "chrome");

        boolean becameOnline = store.addSession(leonardo, "celular");

        assertThat(becameOnline).isFalse();
        assertThat(store.isOnline(leonardo)).isTrue();
    }


    @Test
    void closingOneOfTwoConnectionsKeepsUserOnline() {

        store.addSession(leonardo, "chrome");
        store.addSession(leonardo, "celular");

        boolean becameOffline = store.removeSession(leonardo, "chrome");

        assertThat(becameOffline).isFalse();
        assertThat(store.isOnline(leonardo)).isTrue();
    }


    @Test
    void closingLastConnectionMakesUserOffline() {

        store.addSession(leonardo, "chrome");
        store.addSession(leonardo, "celular");
        store.removeSession(leonardo, "chrome");

        boolean becameOffline = store.removeSession(leonardo, "celular");

        assertThat(becameOffline).isTrue();
        assertThat(store.isOnline(leonardo)).isFalse();
    }


    @Test
    void disconnectEventReceivedTwiceForSameSessionIsIdempotent() {

        store.addSession(leonardo, "chrome");

        assertThat(store.removeSession(leonardo, "chrome")).isTrue();
        assertThat(store.removeSession(leonardo, "chrome")).isFalse();
        assertThat(store.isOnline(leonardo)).isFalse();
    }


    @Test
    void removingUnknownSessionDoesNotMakeUserOffline() {

        store.addSession(leonardo, "chrome");

        assertThat(store.removeSession(leonardo, "sessao-desconhecida")).isFalse();
        assertThat(store.isOnline(leonardo)).isTrue();
    }


    @Test
    void onlineAmongReturnsOnlyConnectedUsers() {

        UUID maria = UUID.randomUUID();
        UUID joao = UUID.randomUUID();

        store.addSession(leonardo, "a");
        store.addSession(maria, "b");

        assertThat(store.onlineAmong(List.of(leonardo, maria, joao)))
                .containsExactlyInAnyOrder(leonardo, maria);
    }


    /*
     * Muitas abas abrindo e fechando ao mesmo tempo:
     * exatamente uma transição para ONLINE e uma para OFFLINE.
     */
    @Test
    void concurrentConnectionsProduceExactlyOneOnlineAndOneOfflineTransition() throws Exception {

        int sessions = 200;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        AtomicInteger onlineTransitions = new AtomicInteger();
        AtomicInteger offlineTransitions = new AtomicInteger();

        try {

            CountDownLatch connected = new CountDownLatch(sessions);
            for (int i = 0; i < sessions; i++) {
                String sessionId = "session-" + i;
                executor.execute(() -> {
                    if (store.addSession(leonardo, sessionId)) {
                        onlineTransitions.incrementAndGet();
                    }
                    connected.countDown();
                });
            }
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch disconnected = new CountDownLatch(sessions);
            for (int i = 0; i < sessions; i++) {
                String sessionId = "session-" + i;
                executor.execute(() -> {
                    if (store.removeSession(leonardo, sessionId)) {
                        offlineTransitions.incrementAndGet();
                    }
                    disconnected.countDown();
                });
            }
            assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();

        } finally {
            executor.shutdownNow();
        }

        assertThat(onlineTransitions).hasValue(1);
        assertThat(offlineTransitions).hasValue(1);
        assertThat(store.isOnline(leonardo)).isFalse();
    }
}
