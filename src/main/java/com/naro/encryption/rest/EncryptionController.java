package com.naro.encryption.rest;

import com.naro.config.CurrentUser;
import com.naro.encryption.application.dto.CreateEncryptionIdentityRequest;
import com.naro.encryption.application.dto.E2eeStatusResponse;
import com.naro.encryption.application.dto.EncryptionIdentityResponse;
import com.naro.encryption.application.dto.EncryptionKeyResponse;
import com.naro.encryption.application.dto.PublicIdentityResponse;
import com.naro.encryption.application.dto.RegisterEncryptionKeyRequest;
import com.naro.encryption.application.service.EncryptionKeyService;
import com.naro.exception.E2eeException;
import com.naro.user.domain.entity.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Material da criptografia ponta a ponta do chat.
 *
 * O dono é sempre o usuário do JWT: nenhum request carrega userId.
 * Tudo o que chega aqui já foi cifrado no browser; o servidor só
 * guarda e devolve.
 *
 * Chaves e identidades públicas vão sem links por item (como as
 * mensagens do chat): não existe GET de um item isolado.
 */
@Tag(name = "Encryption", description = "Chaves da criptografia ponta a ponta do chat; o servidor só guarda material cifrado")
@RestController
@RequestMapping("/api/v1/encryption")
public class EncryptionController {

    /** Prova de posse da Master Key exigida para revogar uma passkey. */
    static final String KEY_PROOF_HEADER = "X-E2EE-Key-Proof";

    private final EncryptionKeyService encryptionKeyService;

    private final EncryptionIdentityModelAssembler identityAssembler;

    public EncryptionController(
            EncryptionKeyService encryptionKeyService,
            EncryptionIdentityModelAssembler identityAssembler
    ) {
        this.encryptionKeyService = encryptionKeyService;
        this.identityAssembler = identityAssembler;
    }

    @GetMapping("/status")
    @Operation(summary = "Feature flags do E2EE: se está disponível e se o chat privado exige criptografia")
    public E2eeStatusResponse status() {
        return encryptionKeyService.getStatus();
    }

    @GetMapping("/identity")
    @Operation(summary = "Identidade do usuário autenticado (chave privada cifrada); 404 antes da ativação")
    public EntityModel<EncryptionIdentityResponse> identity(@CurrentUser User currentUser) {
        return identityAssembler.toModel(encryptionKeyService.getOwnIdentity(currentUser));
    }

    @PostMapping("/identity")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ativa o E2EE: cria a identidade e cadastra a primeira passkey na mesma transação")
    public ResponseEntity<EntityModel<EncryptionIdentityResponse>> createIdentity(
            @Valid @RequestBody CreateEncryptionIdentityRequest request,
            @CurrentUser User currentUser
    ) {
        EncryptionIdentityResponse identity = encryptionKeyService.createIdentity(request, currentUser);

        return ResponseEntity
                .created(URI.create("/api/v1/encryption/identity"))
                .body(identityAssembler.toModel(identity));
    }

    @GetMapping("/identities")
    @Operation(summary = "Chaves públicas de quem divide uma conversa com o usuário autenticado")
    public CollectionModel<PublicIdentityResponse> identities(
            @RequestParam(name = "userId", required = false) List<UUID> userIds,
            @CurrentUser User currentUser
    ) {
        return CollectionModel.of(encryptionKeyService.getContactIdentities(
                userIds == null ? List.of() : userIds,
                currentUser
        ));
    }

    @GetMapping("/keys")
    @Operation(summary = "Master Key cifrada para cada passkey do usuário autenticado")
    public CollectionModel<EncryptionKeyResponse> keys(@CurrentUser User currentUser) {
        return CollectionModel.of(encryptionKeyService.getKeys(currentUser));
    }

    @PostMapping("/keys")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cadastra mais uma passkey para a Master Key existente (exige prova de posse)")
    public EncryptionKeyResponse registerKey(
            @Valid @RequestBody RegisterEncryptionKeyRequest request,
            @CurrentUser User currentUser
    ) {
        return encryptionKeyService.registerKey(request, currentUser);
    }

    @DeleteMapping("/keys/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoga uma passkey do usuário autenticado; a última não pode ser removida")
    public void revokeKey(
            @PathVariable String credentialId,
            @RequestHeader(name = KEY_PROOF_HEADER, required = false) String masterKeyProof,
            @CurrentUser User currentUser
    ) {
        encryptionKeyService.revokeKey(credentialId, masterKeyProof, currentUser);
    }

    /*
     * Só para este controller: devolve o ProblemDetail com o "code" de
     * domínio (E2EE_LAST_KEY, E2EE_KEY_PROOF_INVALID...). O resto da API
     * continua com o tratamento de erro padrão.
     */
    @ExceptionHandler(E2eeException.class)
    public ResponseEntity<ProblemDetail> handleE2eeError(E2eeException exception) {
        return ResponseEntity
                .status(exception.getStatusCode())
                .body(exception.getBody());
    }
}
