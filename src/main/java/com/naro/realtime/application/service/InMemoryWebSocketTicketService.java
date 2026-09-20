package com.naro.realtime.application.service;

import com.naro.realtime.application.dto.WebSocketTicketResponse;
import com.naro.security.RealtimePrincipal;
import com.naro.user.domain.entity.User;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tickets em memória: suficiente para uma instância.
 *
 * Com várias instâncias, o ticket emitido em uma pode ser
 * usado em outra; nesse caso esta implementação deve ser
 * trocada por uma baseada em Redis (SET com TTL + GETDEL).
 */
@Service
public class InMemoryWebSocketTicketService
        implements WebSocketTicketService {

    static final Duration TICKET_TTL = Duration.ofSeconds(30);

    private static final int TICKET_BYTES = 32;


    private final SecureRandom secureRandom = new SecureRandom();

    private final Map<String, IssuedTicket> tickets = new ConcurrentHashMap<>();

    private final Clock clock;


    public InMemoryWebSocketTicketService() {
        this(Clock.systemUTC());
    }


    InMemoryWebSocketTicketService(Clock clock) {
        this.clock = clock;
    }


    @Override
    public WebSocketTicketResponse issue(
            User user
    ) {

        purgeExpired();


        String ticket = newTicket();

        Instant expiresAt = clock.instant().plus(TICKET_TTL);


        tickets.put(
                ticket,
                new IssuedTicket(
                        new RealtimePrincipal(
                                user.getId(),
                                user.getKeycloakId()
                        ),
                        expiresAt
                )
        );


        return new WebSocketTicketResponse(
                ticket,
                expiresAt
        );
    }


    @Override
    public Optional<RealtimePrincipal> consume(
            String ticket
    ) {

        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }


        /*
         * remove() é atômico: duas conexões simultâneas
         * com o mesmo ticket nunca são aceitas.
         */
        IssuedTicket issued = tickets.remove(ticket);


        if (issued == null || issued.isExpiredAt(clock.instant())) {
            return Optional.empty();
        }


        return Optional.of(issued.principal());
    }


    private String newTicket() {

        byte[] bytes = new byte[TICKET_BYTES];

        secureRandom.nextBytes(bytes);

        return Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }


    private void purgeExpired() {

        Instant now = clock.instant();

        tickets
                .values()
                .removeIf(ticket -> ticket.isExpiredAt(now));
    }


    private record IssuedTicket(

            RealtimePrincipal principal,

            Instant expiresAt

    ) {

        boolean isExpiredAt(Instant instant) {
            return !instant.isBefore(expiresAt);
        }
    }
}
