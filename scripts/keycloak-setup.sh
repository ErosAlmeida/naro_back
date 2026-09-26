#!/usr/bin/env bash
# Configuração idempotente local. Mantém usuários e o secret do client existente.
set -euo pipefail

NARO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export NARO_APP_URL="${NARO_APP_URL:-${APP_URL:-http://localhost:3000}}"
export NARO_APP_URL="${NARO_APP_URL%/}"
export NARO_REALM="${NARO_REALM:-${REALM:-social}}"
export NARO_CLIENT_ID="${NARO_CLIENT_ID:-${CLIENT_ID:-naro-web}}"
export KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
export KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
export NARO_FRONTEND_DIR="${NARO_FRONTEND_DIR:-$NARO_ROOT/../interface-da-rede-naro}"

docker compose -f "$NARO_ROOT/docker-compose.dev.yml" up -d keycloak
docker compose -f "$NARO_ROOT/docker-compose.dev.yml" run --rm --no-deps keycloak-setup
python3 "$NARO_ROOT/scripts/sync-keycloak-env.py"
