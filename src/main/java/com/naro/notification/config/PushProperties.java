package com.naro.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Notificações Web Push (naro.push.*). Veja docs/notifications.md.
 *
 * @param vapid                    chaves VAPID; sem elas o Web Push fica desligado
 * @param preview                  o que a notificação mostra da mensagem
 * @param allowedEndpointHosts     push services aceitos (proteção contra SSRF)
 * @param ttl                      quanto tempo o push service guarda a mensagem para um aparelho offline
 * @param maxSubscriptionsPerUser  aparelhos por conta; acima disso sai o usado há mais tempo
 */
@ConfigurationProperties(prefix = "naro.push")
public record PushProperties(

        @DefaultValue
        Vapid vapid,

        @DefaultValue("NAME_AND_PREVIEW")
        PreviewMode preview,

        @DefaultValue({
                "fcm.googleapis.com",
                "updates.push.services.mozilla.com",
                "web.push.apple.com",
                "*.notify.windows.com"
        })
        List<String> allowedEndpointHosts,

        @DefaultValue("24h")
        Duration ttl,

        @DefaultValue("10")
        int maxSubscriptionsPerUser

) {

    /**
     * @param publicKey  ponto P-256 não comprimido, Base64url (vai para o navegador)
     * @param privateKey escalar P-256, Base64url (NUNCA sai do servidor)
     * @param subject    contato para os push services: mailto: ou https:
     */
    public record Vapid(String publicKey, String privateKey, String subject) {

        /** Nenhuma das três informada: Web Push desligado, sem erro. */
        public boolean isBlank() {
            return isBlank(publicKey) && isBlank(privateKey) && isBlank(subject);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        /** Nunca imprime a chave privada (nem em logs de binding do Spring). */
        @Override
        public String toString() {
            return "Vapid[publicKey=" + publicKey + ", subject=" + subject + "]";
        }
    }

    /**
     * Preparado para virar preferência do usuário. Mensagens cifradas de
     * ponta a ponta nunca têm prévia: o servidor não consegue lê-las.
     */
    public enum PreviewMode {
        /** "Leonardo" / "Você vai entrar hoje?" */
        NAME_AND_PREVIEW,
        /** "Leonardo" / "Nova mensagem" */
        NAME_ONLY,
        /** "Naro" / "Você recebeu uma nova mensagem" */
        GENERIC
    }
}
