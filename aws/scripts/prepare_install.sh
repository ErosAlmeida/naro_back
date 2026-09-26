#!/usr/bin/env bash
set -euo pipefail
# ApplicationStop does not run on the very first deployment.
if ! id naro >/dev/null 2>&1; then
  useradd --system --home-dir /opt/naro --shell /sbin/nologin naro
fi
install -d -m 0755 /opt/naro /var/log/naro
install -d -m 0700 /etc/naro
install -d -m 0755 /etc/pki/rds
if [[ ! -s /etc/pki/rds/global-bundle.pem ]]; then
  NARO_CA_DOWNLOAD="$(mktemp /etc/pki/rds/.bundle.XXXXXX)"
  trap 'rm -f "$NARO_CA_DOWNLOAD"' EXIT
  curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
    https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem \
    -o "$NARO_CA_DOWNLOAD"
  install -m 0644 "$NARO_CA_DOWNLOAD" /etc/pki/rds/global-bundle.pem
fi
# CodeDeploy overwrites its managed files; we intentionally do not delete this directory here.
