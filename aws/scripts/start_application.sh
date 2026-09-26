#!/usr/bin/env bash
set -euo pipefail
systemctl restart naro-keycloak.service
# Wait for real readiness and update the existing realm without recreating users.
python3 /opt/naro/deployment/aws/keycloak/configure.py
systemctl restart naro-backend.service
