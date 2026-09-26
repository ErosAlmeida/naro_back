#!/usr/bin/env bash
set -euo pipefail
NARO_BUNDLE=/opt/naro/deployment
[[ -r /etc/naro/infrastructure.env ]] || { echo 'Missing infrastructure.env from EC2 user data.' >&2; exit 1; }
install -m 0644 -o root -g root "$NARO_BUNDLE/application.jar" /opt/naro/application.jar
install -m 0644 "$NARO_BUNDLE/aws/systemd/naro-backend.service" /etc/systemd/system/naro-backend.service
install -m 0644 "$NARO_BUNDLE/aws/systemd/naro-keycloak.service" /etc/systemd/system/naro-keycloak.service
install -m 0644 "$NARO_BUNDLE/aws/systemd/naro-logrotate" /etc/logrotate.d/naro
python3 "$NARO_BUNDLE/aws/scripts/configure_runtime.py"
# Pin the same Keycloak release as local development; never use latest.
docker pull quay.io/keycloak/keycloak:26.7.3
systemctl daemon-reload
systemctl enable naro-keycloak.service naro-backend.service
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config -m ec2 -s -c file:/etc/naro/cloudwatch-agent.json >/dev/null
