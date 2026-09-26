#!/usr/bin/env bash
set -euo pipefail
for attempt in $(seq 1 90); do
  if curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null \
    && curl --fail --silent http://127.0.0.1:9000/health/ready >/dev/null \
    && curl --fail --silent http://127.0.0.1:8081/auth/realms/social/.well-known/openid-configuration >/dev/null; then
    echo 'Spring and Keycloak are healthy.'
    exit 0
  fi
  sleep 2
done
echo 'Health validation failed. Inspect CloudWatch logs and systemctl status.' >&2
exit 1
