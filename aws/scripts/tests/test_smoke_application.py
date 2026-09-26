"""Safety checks for the optional, operator-invoked diagnostic helper."""
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

PATH = Path(__file__).resolve().parents[2] / 'tools/smoke_application.py'
spec = importlib.util.spec_from_file_location('naro_smoke_application', PATH)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeApplicationTests(unittest.TestCase):
    def test_multipart_preserves_the_image_bytes(self):
        body, content_type = smoke.multipart({'content': 'diagnostic'}, smoke.PNG)
        self.assertIn(smoke.PNG, body)
        self.assertIn(b'name="image"; filename="smoke.png"', body)
        self.assertIn(b'Content-Type: image/png', body)
        self.assertTrue(content_type.startswith('multipart/form-data; boundary='))

    def test_provider_errors_never_expose_signed_urls_or_response_body(self):
        signed = 'https://example.invalid/image?X-Amz-Signature=DO_NOT_PRINT'
        error = HTTPError(signed, 403, 'DO_NOT_PRINT', {}, io.BytesIO(b'DO_NOT_PRINT'))
        with patch.object(smoke, 'urlopen', side_effect=error):
            with self.assertRaises(smoke.SmokeFailure) as failure:
                smoke.request('GET', signed)
        self.assertNotIn('DO_NOT_PRINT', str(failure.exception))
        self.assertNotIn('example.invalid', str(failure.exception))

    def test_cleanup_never_modifies_unverified_ids_from_state(self):
        check = smoke.SmokeTest.__new__(smoke.SmokeTest)
        check.state = {'user_id': 'unverified-user', 'client_id': 'unverified-client'}
        check.owned_identity = {}
        check.admin = Mock()
        with patch('builtins.print'):
            check.disable_identity()
        check.admin.assert_not_called()


if __name__ == '__main__':
    unittest.main()
