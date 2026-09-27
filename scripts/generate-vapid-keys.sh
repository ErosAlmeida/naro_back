#!/usr/bin/env bash
# Gera um par de chaves VAPID (P-256) para o Web Push do Naro.
#
#   ./scripts/generate-vapid-keys.sh [mailto:voce@dominio | https://seu-site]
#
# Imprime as três variáveis no formato do Spring (docs/notifications.md).
# Só usa o openssl; a chave privada vive apenas num diretório temporário
# (apagado na saída) e na saída deste comando: copie para o gerenciador de
# segredos (SSM, IntelliJ, .env local fora do Git) e limpe o terminal.
#
# Troque de chave só se necessário: inscrições feitas com a chave antiga
# deixam de funcionar (o servidor as desativa e o navegador se inscreve de
# novo na próxima vez que alguém abrir o Naro).
set -euo pipefail

subject="${1:-mailto:voce@exemplo.com}"

case "$subject" in
  mailto:*|https://*) ;;
  *) echo "O subject precisa começar com mailto: ou https://" >&2; exit 1 ;;
esac

command -v openssl >/dev/null || { echo "openssl não encontrado" >&2; exit 1; }

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
umask 077

base64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

openssl ecparam -name prime256v1 -genkey -noout -out "$workdir/vapid.pem" 2>/dev/null

# SEC1 DER: 30 77 02 01 01 04 20 <32 bytes do escalar privado> ...
private_key="$(openssl ec -in "$workdir/vapid.pem" -outform DER 2>/dev/null | tail -c +8 | head -c 32 | base64url)"
# SubjectPublicKeyInfo DER: os últimos 65 bytes são o ponto não comprimido (04 || X || Y).
public_key="$(openssl ec -in "$workdir/vapid.pem" -pubout -outform DER 2>/dev/null | tail -c 65 | base64url)"

if [ "${#private_key}" -ne 43 ] || [ "${#public_key}" -ne 87 ]; then
  echo "Falha ao extrair as chaves; confira a versão do openssl." >&2
  exit 1
fi

cat <<EOF
NARO_PUSH_VAPID_PUBLIC_KEY=$public_key
NARO_PUSH_VAPID_PRIVATE_KEY=$private_key
NARO_PUSH_VAPID_SUBJECT=$subject
EOF
