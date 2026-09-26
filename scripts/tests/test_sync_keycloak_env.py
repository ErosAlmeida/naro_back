import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "sync-keycloak-env.py"
SPEC = importlib.util.spec_from_file_location("sync_keycloak_env", MODULE_PATH)
SYNC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SYNC)


class SyncKeycloakEnvTest(unittest.TestCase):
    def test_sync_is_idempotent_preserves_other_settings_and_never_prints_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            frontend = Path(directory)
            (frontend / "package.json").write_text("{}")
            env_file = frontend / ".env.local"
            env_file.write_text("API_BASE_URL=http://localhost:8080\nOTHER_SETTING=preserved\nKEYCLOAK_CLIENT_SECRET=old\n")
            calls = []

            def run(command, **kwargs):
                calls.append((command, kwargs))
                if "client-secret" in " ".join(command):
                    value = {"value": "test-only-secret"}
                elif "get" in command and "clients" in command:
                    value = [{"id": "client-id"}]
                elif "get" in command and "users/profile" in command:
                    value = {"attributes": [
                        {"name": "username", "validations": {"length": {"min": 3, "max": 255}}, "permissions": {"view": ["user"]}},
                        {"name": "email", "validations": {"length": {"max": 200}}},
                        {"name": "custom", "validations": {"length": {"max": 500}}},
                    ]}
                else:
                    value = {}
                return subprocess.CompletedProcess(command, 0, json.dumps(value), "")

            settings = {"NARO_FRONTEND_DIR": directory, "KEYCLOAK_ADMIN_PASSWORD": "test-admin-credential"}
            output = io.StringIO()
            with patch.dict(os.environ, settings, clear=True), patch.object(SYNC.subprocess, "run", side_effect=run), contextlib.redirect_stdout(output):
                SYNC.main()
                first = env_file.read_text()
                SYNC.main()
            self.assertEqual(first, env_file.read_text())
            self.assertIn("API_BASE_URL=http://localhost:8080", first)
            self.assertIn("OTHER_SETTING=preserved", first)
            self.assertEqual(first.count("KEYCLOAK_CLIENT_SECRET="), 1)
            self.assertEqual(env_file.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("test-only-secret", output.getvalue())
            self.assertNotIn("test-admin-credential", output.getvalue())
            for command, kwargs in calls:
                self.assertNotIn("test-admin-credential", " ".join(command))
                if "update" in command and "users/profile" in command:
                    profile = json.loads(kwargs["input"])
                    attributes = profile["attributes"]
                    self.assertEqual(attributes[0]["validations"]["length"], {"min": 3, "max": 100})
                    self.assertEqual(attributes[0]["permissions"], {"view": ["user"]})
                    self.assertEqual(attributes[1]["validations"]["length"]["max"], 200)
                    self.assertEqual(attributes[2]["validations"]["length"]["max"], 500)
            self.assertTrue(any("rm" in command for command, _ in calls))

    def test_failed_setup_keeps_existing_env_and_removes_temporary_admin_session(self):
        with tempfile.TemporaryDirectory() as directory:
            frontend = Path(directory)
            (frontend / "package.json").write_text("{}")
            env_file = frontend / ".env.local"
            env_file.write_text("UNCHANGED=yes\n")
            commands = []

            def fail_get(command, **kwargs):
                commands.append(command)
                if "get" in command:
                    raise subprocess.CalledProcessError(1, command)
                return subprocess.CompletedProcess(command, 0, "", "")

            with patch.dict(os.environ, {"NARO_FRONTEND_DIR": directory, "KEYCLOAK_ADMIN_PASSWORD": "test-password"}, clear=True), patch.object(SYNC.subprocess, "run", side_effect=fail_get):
                with self.assertRaises(subprocess.CalledProcessError):
                    SYNC.main()
            self.assertEqual(env_file.read_text(), "UNCHANGED=yes\n")
            self.assertTrue(any("rm" in command for command in commands))


if __name__ == "__main__":
    unittest.main()
