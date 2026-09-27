package com.naro.exception;

import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.util.UUID;

/**
 * Falha de criptografia ponta a ponta com código de domínio.
 *
 * No envio pelo WebSocket, conversationId e clientMessageId permitem
 * à interface marcar como falha apenas a mensagem recusada. O detalhe
 * é sempre um texto fixo: nada do payload recebido é ecoado.
 */
public class E2eeException extends ErrorResponseException {

    private final E2eeError error;

    private final UUID conversationId;

    private final UUID clientMessageId;

    public E2eeException(E2eeError error) {
        this(error, null, null);
    }

    public E2eeException(E2eeError error, UUID conversationId, UUID clientMessageId) {
        super(error.status(), createProblemDetail(error), null);
        this.error = error;
        this.conversationId = conversationId;
        this.clientMessageId = clientMessageId;
    }

    public E2eeError getError() {
        return error;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }

    private static ProblemDetail createProblemDetail(E2eeError error) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(error.status(), error.detail());
        problemDetail.setTitle("End-to-end encryption");
        problemDetail.setProperty("code", error.code());
        return problemDetail;
    }
}
