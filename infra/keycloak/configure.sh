#!/usr/bin/env bash
# Executado somente pela infraestrutura. Nenhuma credencial administrativa chega ao Naro.
set -euo pipefail

NARO_CONFIG_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NARO_ADMIN_CONFIG="$(mktemp /tmp/naro-kcadm.XXXXXX)"
trap 'rm -f "$NARO_ADMIN_CONFIG"' EXIT

KEYCLOAK_SERVER="${KEYCLOAK_SERVER:-http://keycloak:8080}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
NARO_REALM="${NARO_REALM:-social}"
NARO_CLIENT_ID="${NARO_CLIENT_ID:-naro-web}"
NARO_APP_URL="${NARO_APP_URL:-http://localhost:3000}"
NARO_APP_URL="${NARO_APP_URL%/}"
: "${KC_CLI_PASSWORD:?Forneça a credencial de infraestrutura do Keycloak}"

case "$NARO_APP_URL" in
  http://localhost:*|http://127.0.0.1:*|https://*) ;;
  *) echo "NARO_APP_URL deve usar HTTPS, exceto em localhost." >&2; exit 1 ;;
esac

kcadm() {
  /opt/keycloak/bin/kcadm.sh "$@" --config "$NARO_ADMIN_CONFIG"
}

# O Keycloak não traz curl; kcadm verifica prontidão e autentica usando KC_CLI_PASSWORD.
NARO_READY=false
for ((attempt = 1; attempt <= 60; attempt++)); do
  if kcadm config credentials --server "$KEYCLOAK_SERVER" --realm master --user "$KEYCLOAK_ADMIN" >/dev/null 2>&1; then
    NARO_READY=true
    break
  fi
  sleep 2
done
if [[ "$NARO_READY" != true ]]; then
  echo "Keycloak indisponível ou credencial administrativa inválida." >&2
  exit 1
fi

# Cria somente se ausente; PATCH parcial preserva usuários, roles, clients e SMTP existentes.
if ! kcadm get "realms/$NARO_REALM" >/dev/null 2>&1; then
  kcadm create realms -s "realm=$NARO_REALM" -s enabled=true >/dev/null
fi
kcadm update "realms/$NARO_REALM" -f "$NARO_CONFIG_DIR/realm-settings.json" --merge >/dev/null

NARO_CLIENT_UUID="$(kcadm get clients -r "$NARO_REALM" -q "clientId=$NARO_CLIENT_ID" --fields id --format csv --noquotes | tr -d '\r')"
NARO_CLIENT_SETTINGS=(
  -s "clientId=$NARO_CLIENT_ID"
  -s 'name=Naro Web'
  -s enabled=true
  -s protocol=openid-connect
  -s publicClient=false
  -s standardFlowEnabled=true
  -s directAccessGrantsEnabled=false
  -s implicitFlowEnabled=false
  -s serviceAccountsEnabled=false
  -s "redirectUris=[\"$NARO_APP_URL/api/auth/callback\"]"
  -s "webOrigins=[\"$NARO_APP_URL\"]"
  -s 'attributes."pkce.code.challenge.method"=S256'
  -s "attributes.\"post.logout.redirect.uris\"=$NARO_APP_URL/login?signedOut=1"
)
if [[ -z "$NARO_CLIENT_UUID" ]]; then
  kcadm create clients -r "$NARO_REALM" "${NARO_CLIENT_SETTINGS[@]}" >/dev/null
else
  kcadm update "clients/$NARO_CLIENT_UUID" -r "$NARO_REALM" "${NARO_CLIENT_SETTINGS[@]}" >/dev/null
fi

echo "Cadastro Naro habilitado por código: realm $NARO_REALM, client $NARO_CLIENT_ID, PKCE S256 e tema Naro."
