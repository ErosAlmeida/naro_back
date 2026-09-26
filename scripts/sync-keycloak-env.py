#!/usr/bin/env python3
"""Sincroniza o client secret só no .env.local do servidor, sem imprimi-lo."""
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    frontend = Path(os.environ["NARO_FRONTEND_DIR"]).resolve()
    if not (frontend / "package.json").is_file():
        raise RuntimeError("NARO_FRONTEND_DIR deve apontar para o frontend do Naro")
    container = os.environ.get("KEYCLOAK_CONTAINER", "keycloak")
    realm = os.environ.get("NARO_REALM", "social")
    client_id = os.environ.get("NARO_CLIENT_ID", "naro-web")
    app_url = os.environ.get("NARO_APP_URL", "http://localhost:3000")
    config = f"/tmp/naro-client-sync-{os.getpid()}.config"
    process_env = dict(os.environ, KC_CLI_PASSWORD=os.environ["KEYCLOAK_ADMIN_PASSWORD"])

    def kcadm(*args, payload=None):
        command = ["docker", "exec", *( ["-i"] if payload is not None else [] ), "-e", "KC_CLI_PASSWORD", container,
                   "/opt/keycloak/bin/kcadm.sh", *args, "--config", config]
        result = subprocess.run(command, env=process_env, input=payload, capture_output=True, text=True, check=True)
        return result.stdout

    try:
        kcadm("config", "credentials", "--server", "http://localhost:8080", "--realm", "master",
              "--user", os.environ.get("KEYCLOAK_ADMIN", "admin"))
        clients = json.loads(kcadm("get", "clients", "-r", realm, "-q", f"clientId={client_id}", "--fields", "id"))
        if len(clients) != 1:
            raise RuntimeError("Client Naro ausente ou ambíguo")
        secret = json.loads(kcadm("get", f"clients/{clients[0]['id']}/client-secret", "-r", realm))["value"]
        # Validação já no cadastro, compatível com os limites da entity User.
        # Mantém os outros atributos, permissões e validações do realm existente.
        profile = json.loads(kcadm("get", "users/profile", "-r", realm))
        limits = {"username": 100, "email": 255, "firstName": 100, "lastName": 100}
        for attribute in profile.get("attributes", []):
            if attribute.get("name") in limits:
                length = attribute.setdefault("validations", {}).setdefault("length", {})
                length["max"] = min(int(length.get("max", limits[attribute["name"]])), limits[attribute["name"]])
        kcadm("update", "users/profile", "-r", realm, "-f", "-", "--no-merge", payload=json.dumps(profile))
        if os.environ.get("NARO_SMTP_HOST"):
            smtp = {
                "host": os.environ["NARO_SMTP_HOST"],
                "from": os.environ["NARO_SMTP_FROM"],
                "fromDisplayName": "Naro",
                "port": os.environ.get("NARO_SMTP_PORT", "587"),
                "starttls": os.environ.get("NARO_SMTP_STARTTLS", "true"),
                "ssl": os.environ.get("NARO_SMTP_SSL", "false"),
                "auth": "true" if os.environ.get("NARO_SMTP_USER") else "false",
            }
            if os.environ.get("NARO_SMTP_USER"):
                smtp.update(user=os.environ["NARO_SMTP_USER"], password=os.environ["NARO_SMTP_PASSWORD"])
            kcadm("update", f"realms/{realm}", "-f", "-", "--merge",
                  payload=json.dumps({"smtpServer": smtp, "verifyEmail": True}))
    finally:
        subprocess.run(["docker", "exec", container, "rm", "-f", config], capture_output=True, check=False)

    env_file = frontend / ".env.local"
    existing = env_file.read_text() if env_file.exists() else (frontend / ".env.example").read_text()
    replacements = {
        "APP_URL": app_url,
        "KEYCLOAK_CLIENT_ID": client_id,
        "KEYCLOAK_CLIENT_SECRET": secret,
        "KEYCLOAK_ISSUER": os.environ.get("KEYCLOAK_ISSUER", f"http://localhost:8180/realms/{realm}"),
    }
    lines = []
    for line in existing.splitlines():
        key = line.split("=", 1)[0].strip()
        if key in replacements:
            line = f"{key}={replacements.pop(key)}"
        lines.append(line)
    lines.extend(f"{key}={value}" for key, value in replacements.items())
    descriptor, temporary = tempfile.mkstemp(prefix=".naro-env-", dir=frontend)
    try:
        with os.fdopen(descriptor, "w") as output:
            output.write("\n".join(lines) + "\n")
        os.chmod(temporary, 0o600)
        os.replace(temporary, env_file)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print("Configuração do frontend sincronizada em .env.local. Nenhum secret foi exibido.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, KeyError, ValueError, OSError) as error:
        # Não imprime stdout de subprocessos nem o conteúdo do ambiente.
        print(f"Não foi possível sincronizar .env.local ({type(error).__name__}). Confira a infraestrutura e NARO_FRONTEND_DIR.")
        raise SystemExit(1)
