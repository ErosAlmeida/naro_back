package com.naro.realtime.application.service;

import com.naro.realtime.application.dto.WebSocketTicketResponse;
import com.naro.security.RealtimePrincipal;
import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryWebSocketTicketServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-18T12:00:00Z"));

    private final InMemoryWebSocketTicketService ticketService =
            new InMemoryWebSocketTicketService(clock);

    private final User user = user();


    @Test
    void issuedTicketAuthenticatesTheUserWhoRequestedIt() {

        WebSocketTicketResponse issued = ticketService.issue(user);

        assertThat(issued.ticket()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(issued.expiresAt())
                .isEqualTo(clock.instant().plus(InMemoryWebSocketTicketService.TICKET_TTL));
        assertThat(ticketService.consume(issued.ticket()))
                .contains(new RealtimePrincipal(user.getId(), user.getKeycloakId()));
    }


    @Test
    void ticketCanBeUsedOnlyOnce() {

        String ticket = ticketService.issue(user).ticket();

        assertThat(ticketService.consume(ticket)).isPresent();
        assertThat(ticketService.consume(ticket)).isEmpty();
    }


    @Test
    void expiredTicketIsRejected() {

        String ticket = ticketService.issue(user).ticket();

        clock.advance(InMemoryWebSocketTicketService.TICKET_TTL);

        assertThat(ticketService.consume(ticket)).isEmpty();
    }


    @Test
    void unknownOrBlankTicketIsRejected() {

        assertThat(ticketService.consume("nao-existe")).isEmpty();
        assertThat(ticketService.consume(" ")).isEmpty();
        assertThat(ticketService.consume(null)).isEmpty();
    }


    @Test
    void ticketsAreUnpredictableAndUnique() {

        assertThat(ticketService.issue(user).ticket())
                .isNotEqualTo(ticketService.issue(user).ticket());
    }


    private static User user() {

        User user = new User(UUID.randomUUID().toString(), "leonardo", null, null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }


    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
