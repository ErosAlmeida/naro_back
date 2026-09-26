#!/usr/bin/env bash
set -euo pipefail
if systemctl cat naro-backend.service >/dev/null 2>&1; then
  systemctl stop naro-backend.service
fi
