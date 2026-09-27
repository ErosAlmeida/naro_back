package com.naro.notification.application.service;

import com.naro.notification.application.dto.PushSubscriptionResponse;
import com.naro.notification.application.dto.RegisterPushSubscriptionRequest;
import com.naro.notification.application.dto.VapidPublicKeyResponse;
import com.naro.user.domain.entity.User;

import java.util.List;
import java.util.UUID;

/**
 * Aparelhos que recebem notificações. Tudo é sempre do usuário autenticado:
 * nenhum método aceita o id de outra conta.
 */
public interface PushSubscriptionService {

    VapidPublicKeyResponse getVapidPublicKey();


    /**
     * Cadastra ou atualiza a inscrição deste navegador. O mesmo endpoint
     * cadastrado de novo não duplica; aparelhos diferentes convivem.
     *
     * @param userAgent cabeçalho User-Agent da requisição (só para exibição)
     */
    Registration register(
            RegisterPushSubscriptionRequest request,
            String userAgent,
            User currentUser
    );


    List<PushSubscriptionResponse> getSubscriptions(
            User currentUser
    );


    /** 404 para id inexistente ou de outra conta (não revela qual). */
    void delete(
            UUID subscriptionId,
            User currentUser
    );


    /** @param created false quando o endpoint já era desta conta */
    record Registration(PushSubscriptionResponse subscription, boolean created) {
    }
}
