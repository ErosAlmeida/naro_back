package com.naro.exception;

import org.springframework.http.HttpStatus;

/**
 * Erros de domínio da criptografia ponta a ponta.
 *
 * O código vai no ProblemDetail (propriedade "code") e na fila
 * /user/queue/errors, para o cliente reagir sem ler texto.
 */
public enum E2eeError {

    /** Mensagem privada nova sem criptografia quando o E2EE é obrigatório. */
    REQUIRED(HttpStatus.BAD_REQUEST, "E2EE_REQUIRED",
            "Private messages must be end-to-end encrypted"),

    /** Funcionalidade desligada (naro.security.e2ee.available=false). */
    UNAVAILABLE(HttpStatus.NOT_FOUND, "E2EE_UNAVAILABLE",
            "End-to-end encryption is not available"),

    INVALID_PAYLOAD(HttpStatus.BAD_REQUEST, "E2EE_INVALID_PAYLOAD",
            "Invalid encrypted payload"),

    SETUP_REQUIRED(HttpStatus.CONFLICT, "E2EE_SETUP_REQUIRED",
            "End-to-end encryption has not been set up for this user"),

    ALREADY_SET_UP(HttpStatus.CONFLICT, "E2EE_ALREADY_SET_UP",
            "End-to-end encryption is already set up for this user"),

    KEY_PROOF_INVALID(HttpStatus.FORBIDDEN, "E2EE_KEY_PROOF_INVALID",
            "Proof of the master key is missing or invalid"),

    KEY_EXISTS(HttpStatus.CONFLICT, "E2EE_KEY_EXISTS",
            "This passkey is already registered"),

    KEY_LIMIT(HttpStatus.CONFLICT, "E2EE_KEY_LIMIT",
            "Too many passkeys registered"),

    /** Sem nenhuma passkey a Master Key (e o histórico) ficaria inacessível. */
    LAST_KEY(HttpStatus.CONFLICT, "E2EE_LAST_KEY",
            "The last passkey cannot be revoked");

    private final HttpStatus status;

    private final String code;

    private final String detail;

    E2eeError(HttpStatus status, String code, String detail) {
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
