#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
# Deliberately allowlist content: never include .env, CDK context or credentials.
rm -rf deployment-artifact
install -d deployment-artifact/aws deployment-artifact/infra/keycloak
jars=()
for jar in target/*.jar; do
  [[ -f "$jar" ]] || continue
  case "$jar" in *-sources.jar|*-javadoc.jar) continue ;; esac
  jars+=("$jar")
done
[[ "${#jars[@]}" = 1 ]] || { echo 'Expected exactly one application JAR.' >&2; exit 1; }
install -m 0644 "${jars[0]}" deployment-artifact/application.jar
install -m 0644 appspec.yml deployment-artifact/appspec.yml
cp -R aws/scripts aws/systemd aws/keycloak deployment-artifact/aws/
cp -R infra/keycloak/themes deployment-artifact/infra/keycloak/
install -m 0644 infra/keycloak/realm-settings.json deployment-artifact/infra/keycloak/realm-settings.json
# Local Python bytecode is neither needed nor appropriate in the deployment bundle.
find deployment-artifact -type d -name __pycache__ -prune -exec rm -rf {} +
