package com.naro.notification.rest;

import com.naro.config.CurrentUser;
import com.naro.notification.application.dto.PushSubscriptionResponse;
import com.naro.notification.application.dto.RegisterPushSubscriptionRequest;
import com.naro.notification.application.dto.VapidPublicKeyResponse;
import com.naro.notification.application.service.PushSubscriptionService;
import com.naro.user.domain.entity.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.hateoas.CollectionModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Inscrições Web Push dos aparelhos do usuário autenticado.
 *
 * O dono é sempre o usuário do JWT: nenhum request carrega userId, e ids de
 * outra conta respondem 404. As inscrições vão sem links por item (como as
 * chaves do E2EE): não existe GET de um item isolado.
 */
@Tag(name = "Push", description = "Notificações Web Push: chave VAPID pública e aparelhos inscritos")
@RestController
@RequestMapping("/api/v1/push")
public class PushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;

    public PushSubscriptionController(PushSubscriptionService pushSubscriptionService) {
        this.pushSubscriptionService = pushSubscriptionService;
    }

    @GetMapping("/vapid-public-key")
    @Operation(summary = "applicationServerKey para PushManager.subscribe; enabled=false sem Web Push configurado")
    public VapidPublicKeyResponse vapidPublicKey() {
        return pushSubscriptionService.getVapidPublicKey();
    }

    @GetMapping("/subscriptions")
    @Operation(summary = "Aparelhos com notificações do usuário autenticado (sem endpoint nem chaves)")
    public CollectionModel<PushSubscriptionResponse> subscriptions(@CurrentUser User currentUser) {
        return CollectionModel.of(pushSubscriptionService.getSubscriptions(currentUser));
    }

    @PostMapping("/subscriptions")
    @Operation(summary = "Cadastra a inscrição deste navegador (201) ou atualiza a já existente (200)")
    public ResponseEntity<PushSubscriptionResponse> register(
            @Valid @RequestBody RegisterPushSubscriptionRequest request,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @CurrentUser User currentUser
    ) {
        PushSubscriptionService.Registration registration =
                pushSubscriptionService.register(request, userAgent, currentUser);

        return ResponseEntity
                .status(registration.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(registration.subscription());
    }

    @DeleteMapping("/subscriptions/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove um aparelho do usuário autenticado; 404 para id de outra conta")
    public void delete(
            @PathVariable UUID subscriptionId,
            @CurrentUser User currentUser
    ) {
        pushSubscriptionService.delete(subscriptionId, currentUser);
    }
}
