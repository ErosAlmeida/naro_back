package com.naro.notification.webpush;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Para onde o servidor aceita enviar push.
 *
 * O endpoint vem do navegador, e o servidor faz um POST para ele: sem esta
 * lista, qualquer conta poderia apontar o Naro para um endereço interno
 * (SSRF). Só https, porta padrão e hosts dos push services conhecidos.
 * Uma entrada "*.dominio" aceita qualquer subdomínio (não o próprio domínio).
 */
public final class PushEndpointPolicy {

    public static final int MAX_ENDPOINT_LENGTH = 2048;

    private final List<String> allowedHosts;

    public PushEndpointPolicy(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream()
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .toList();
    }

    public boolean isAllowed(String endpoint) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.length() > MAX_ENDPOINT_LENGTH) {
            return false;
        }

        URI uri;

        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException exception) {
            return false;
        }

        String host = uri.getHost();

        return "https".equalsIgnoreCase(uri.getScheme())
                && host != null
                && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && allowedHosts.stream().anyMatch(allowed -> matches(host.toLowerCase(Locale.ROOT), allowed));
    }

    private static boolean matches(String host, String allowed) {
        if (allowed.startsWith("*.")) {
            return host.endsWith(allowed.substring(1));
        }
        return host.equals(allowed);
    }
}
