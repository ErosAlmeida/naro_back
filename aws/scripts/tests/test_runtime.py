"""Fast security/format checks; no AWS access and no real credentials."""
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import configure_runtime
import runtime_support


class RuntimeTests(unittest.TestCase):
    def test_environment_files_are_private_and_docker_does_not_receive_quotes(self):
        with tempfile.TemporaryDirectory() as directory:
            systemd = Path(directory) / 'systemd.env'
            docker = Path(directory) / 'docker.env'
            values = {'EXAMPLE': 'complex "quote" $dollar \\backslash'}
            runtime_support.environment_file(systemd, values)
            runtime_support.environment_file(docker, values, docker=True)
            self.assertEqual(stat.S_IMODE(systemd.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(docker.stat().st_mode), 0o600)
            self.assertEqual(docker.read_text(), 'EXAMPLE=' + values['EXAMPLE'] + '\n')
            self.assertIn('\\"quote\\"', systemd.read_text())

    def test_newlines_cannot_inject_environment_variables(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                runtime_support.environment_file(Path(directory) / 'runtime.env', {'EXAMPLE': 'x\nINJECT=y'})

    def test_sql_password_literal_escapes_quote(self):
        self.assertEqual(configure_runtime.sql_literal("test'only"), "'test''only'")

    def test_parameter_fetch_uses_exact_names_and_never_secret_arguments(self):
        class Result:
            returncode = 0
            stdout = json.dumps({'Parameters': [{'Name': '/naro/prod/database/password', 'Value': 'fake-value'}]})
        with patch('runtime_support.subprocess.run', return_value=Result()) as run:
            result = runtime_support.parameters({'NARO_PARAMETER_PREFIX': '/naro/prod', 'AWS_REGION': 'us-east-1'}, ['/database/password'])
        self.assertEqual(result, {'/database/password': 'fake-value'})
        self.assertIn('/naro/prod/database/password', run.call_args.args[0])
        self.assertNotIn('fake-value', ' '.join(run.call_args.args[0]))
        self.assertIn('--with-decryption', run.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
