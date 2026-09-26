#!/usr/bin/env python3
"""Run on the EC2 host via SSM as root: exercise JWT, RDS and private S3 through Spring.

Creates a tiny diagnostic publication on the first run; reuses its IDs on later
runs. No application data is deleted. The dedicated Keycloak user/client are
disabled in finally. Passwords, tokens and presigned URLs are never printed.
"""
import argparse
import base64
import fcntl
import json
import os
from pathlib import Path
import secrets
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, quote, urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

# Works in the repository and in /opt/naro/deployment/aws/tools.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from runtime_support import infrastructure, parameters, private_file

KEYCLOAK = 'http://127.0.0.1:8081/auth'
BACKEND = 'http://127.0.0.1:8080'
# A one-pixel PNG, sufficient for the actual multipart -> S3Client upload path.
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a8c8AAAAASUVORK5CYII=')
MARKER = 'naro-infrastructure-smoke-v1'


class SmokeFailure(Exception):
    """Only fixed, non-sensitive diagnostics should enter this exception."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # API endpoints should not redirect; never forward credentials elsewhere.
        return None


urlopen = build_opener(NoRedirect()).open


def request(method, url, payload=None, token=None, *, form=False, headers=None, raw=False):
    request_headers = dict(headers or {})
    if token:
        request_headers['Authorization'] = 'Bearer ' + token
    data = payload
    if payload is not None and not isinstance(payload, bytes):
        request_headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        data = (urlencode(payload) if form else json.dumps(payload)).encode()
    try:
        with urlopen(Request(url, data=data, headers=request_headers, method=method), timeout=30) as response:
            body = response.read()
            result = body if raw else (json.loads(body) if body else None)
            return result, response.headers
    except HTTPError as error:
        # A provider error can contain a token, password, signed URL or request body.
        raise SmokeFailure(f'HTTP {error.code} during {method}; response body intentionally suppressed') from None
    except (URLError, TimeoutError, OSError):
        raise SmokeFailure(f'Network request failed during {method}; URL intentionally suppressed') from None


def multipart(fields, image=None):
    boundary = 'naro-smoke-' + secrets.token_hex(16)
    parts = []
    for name, value in fields.items():
        parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                      str(value).encode(), b'\r\n'])
    if image is not None:
        parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="smoke.png"\r\n'
                      'Content-Type: image/png\r\n\r\n'.encode(), image, b'\r\n'])
    parts.append(f'--{boundary}--\r\n'.encode())
    return b''.join(parts), 'multipart/form-data; boundary=' + boundary


class SmokeTest:
    def __init__(self, state_file):
        self.settings = infrastructure()
        self.state_file = state_file
        self.state = json.loads(state_file.read_text()) if state_file.exists() else {
            'marker': MARKER, 'suffix': secrets.token_hex(6), 'bucket': self.settings['S3_BUCKET_NAME'],
        }
        if self.state.get('marker') != MARKER or self.state.get('bucket') != self.settings['S3_BUCKET_NAME']:
            raise SmokeFailure('Diagnostic state does not belong to this application/bucket')
        self.admin_token = None
        self.admin_password = None
        self.stage = 'initialization'
        self.owned_identity = {}

    def save(self):
        # Only resource IDs and a namespace; never credentials or signed URLs.
        private_file(self.state_file, json.dumps(self.state, indent=2) + '\n')

    def admin(self, method, path, payload=None):
        return request(method, KEYCLOAK + '/admin/realms/social' + path, payload, self.admin_token)

    def authenticate_admin(self):
        auth, _ = request('POST', KEYCLOAK + '/realms/master/protocol/openid-connect/token', {
            'grant_type': 'password', 'client_id': 'admin-cli',
            'username': self.settings.get('KEYCLOAK_ADMIN', 'naro-admin'),
            'password': self.admin_password,
        }, form=True)
        self.admin_token = auth['access_token']

    def provision_identity(self):
        self.stage = 'dedicated test identity'
        namespace = 'naro-infrastructure-smoke-' + self.state['suffix']
        email = namespace + '@example.invalid'
        password = secrets.token_urlsafe(36)
        client_secret = secrets.token_urlsafe(36)
        if not self.state.get('user_id'):
            existing, _ = self.admin('GET', '/users?username=' + quote(namespace) + '&exact=true')
            if existing:
                raise SmokeFailure('Diagnostic username exists without matching recorded ownership; refusing to modify it')
            _, headers = self.admin('POST', '/users', {
                'username': namespace, 'email': email, 'emailVerified': True,
                'firstName': 'Infrastructure', 'lastName': 'Smoke Test', 'enabled': False,
            })
            self.state['user_id'] = headers['Location'].rstrip('/').split('/')[-1]
            self.owned_identity['user_id'] = self.state['user_id']
            self.save()
        user, _ = self.admin('GET', '/users/' + self.state['user_id'])
        if user.get('username') != namespace or user.get('email') != email:
            raise SmokeFailure('Recorded diagnostic user no longer matches its ownership marker')
        self.owned_identity['user_id'] = self.state['user_id']
        if not self.state.get('client_id'):
            existing, _ = self.admin('GET', '/clients?clientId=' + quote(namespace))
            if existing:
                raise SmokeFailure('Diagnostic client exists without recorded ownership; refusing to modify it')
            _, headers = self.admin('POST', '/clients', {
                'clientId': namespace, 'name': 'Infrastructure smoke test (normally disabled)',
                'enabled': False, 'protocol': 'openid-connect', 'publicClient': False,
                'standardFlowEnabled': False, 'directAccessGrantsEnabled': False,
                'serviceAccountsEnabled': False, 'implicitFlowEnabled': False,
                'defaultClientScopes': ['basic', 'profile', 'email', 'roles'],
                'attributes': {'naro.smoke.managed': MARKER, 'access.token.lifespan': '60'},
            })
            self.state['client_id'] = headers['Location'].rstrip('/').split('/')[-1]
            self.owned_identity['client_id'] = self.state['client_id']
            self.save()
        client, _ = self.admin('GET', '/clients/' + self.state['client_id'])
        if client.get('clientId') != namespace or client.get('attributes', {}).get('naro.smoke.managed') != MARKER:
            raise SmokeFailure('Recorded diagnostic client no longer matches its ownership marker')
        self.owned_identity['client_id'] = self.state['client_id']
        self.admin('PUT', '/users/' + self.state['user_id'] + '/reset-password',
                   {'type': 'password', 'value': password, 'temporary': False})
        self.admin('PUT', '/users/' + self.state['user_id'], {'enabled': True, 'requiredActions': []})
        client.update(enabled=True, directAccessGrantsEnabled=True, secret=client_secret,
                      defaultClientScopes=['basic', 'profile', 'email', 'roles'])
        self.admin('PUT', '/clients/' + self.state['client_id'], client)
        token, _ = request('POST', KEYCLOAK + '/realms/social/protocol/openid-connect/token', {
            'grant_type': 'password', 'client_id': namespace, 'client_secret': client_secret,
            'username': namespace, 'password': password, 'scope': 'openid profile email',
        }, form=True)
        return token['access_token']

    def check_application(self, token):
        self.stage = 'JWT validation and RDS user provisioning'
        user, _ = request('GET', BACKEND + '/api/users/me', token=token)
        self.state['application_user_id'] = user['id']
        self.save()
        print('PASS: Spring validated the Keycloak JWT and resolved the user through RDS.', flush=True)
        self.stage = 'diagnostic channel'
        if not self.state.get('channel_id'):
            body, content_type = multipart({
                'name': 'AWS infrastructure smoke test', 'slug': 'naro-aws-smoke-' + self.state['suffix'],
                'description': 'Dedicated infrastructure diagnostic; one tiny private S3 image. Not a real user.',
            })
            channel, _ = request('POST', BACKEND + '/api/v1/channels', body, token,
                                 headers={'Content-Type': content_type})
            self.state['channel_id'] = channel['id']
            self.save()
        channel, _ = request('GET', BACKEND + '/api/v1/channels/' + self.state['channel_id'], token=token)
        if channel['ownerId'] != user['id'] or channel['slug'] != 'naro-aws-smoke-' + self.state['suffix']:
            raise SmokeFailure('Recorded channel is not owned by this diagnostic identity')
        self.stage = 'Spring multipart upload to S3 using its IAM role'
        if not self.state.get('publication_id'):
            body, content_type = multipart({'content': 'AWS infrastructure smoke test: private S3 upload/download.'}, PNG)
            publication, _ = request('POST', BACKEND + '/api/v1/publications', body, token,
                                     headers={'Content-Type': content_type, 'X-Channel-Id': channel['id']})
            self.state['publication_id'] = publication['id']
            self.save()
            print('PASS: Spring uploaded the diagnostic PNG to S3 and stored the publication in RDS.', flush=True)
        else:
            print('INFO: Existing diagnostic publication reused; this run does not upload another object.', flush=True)
        publication, _ = request('GET', BACKEND + '/api/v1/publications/' + self.state['publication_id'], token=token)
        if publication.get('author', {}).get('id') != channel['id']:
            raise SmokeFailure('Recorded publication does not belong to the diagnostic channel')
        self.stage = 'presigned download and anonymous access denial'
        signed_url = publication['imageUrl']
        parsed = urlsplit(signed_url)
        query = parse_qs(parsed.query)
        expected_hosts = {
            self.settings['S3_BUCKET_NAME'] + '.s3.' + self.settings['AWS_REGION'] + '.amazonaws.com',
            self.settings['S3_BUCKET_NAME'] + '.s3.amazonaws.com',
        }
        if parsed.scheme != 'https' or parsed.hostname not in expected_hosts:
            raise SmokeFailure('Backend returned a URL outside this private AWS S3 bucket')
        if not query.get('X-Amz-Signature') or not query.get('X-Amz-Security-Token'):
            raise SmokeFailure('Expected a SigV4 URL signed with temporary IAM role credentials')
        downloaded, _ = request('GET', signed_url, raw=True)
        if downloaded != PNG:
            raise SmokeFailure('Downloaded object did not match the uploaded diagnostic PNG')
        unsigned_url = urlunsplit((parsed.scheme, parsed.netloc, parsed.path, '', ''))
        try:
            with urlopen(unsigned_url, timeout=30):
                raise SmokeFailure('Private object unexpectedly permits anonymous reads')
        except HTTPError as error:
            if error.code != 403:
                raise SmokeFailure(f'Unexpected anonymous S3 response: HTTP {error.code}') from None
        print('PASS: Presigned download matched the image; anonymous object access returned HTTP 403.', flush=True)
        print('PASS: The signed URL contains a temporary-credential token from the IAM role; no static key was used.', flush=True)

    def disable_identity(self):
        # Master access tokens can expire while an S3/network request is pending.
        if self.owned_identity and getattr(self, 'admin_password', None):
            self.authenticate_admin()
        errors = []
        for key, path, payload in [
            ('client_id', '/clients/', {'enabled': False, 'directAccessGrantsEnabled': False}),
            ('user_id', '/users/', {'enabled': False}),
        ]:
            if self.owned_identity.get(key):
                try:
                    self.admin('PUT', path + self.owned_identity[key], payload)
                except Exception:
                    errors.append(key)
        if self.owned_identity.get('user_id'):
            try:
                self.admin('POST', '/users/' + self.owned_identity['user_id'] + '/logout')
            except Exception:
                errors.append('sessions')
        if errors:
            raise SmokeFailure('Could not disable all diagnostic access; inspect IDs in the root-only state file')
        print('PASS: Dedicated test client/user disabled and sessions closed; no application data deleted.', flush=True)

    def run(self):
        self.save()
        self.stage = 'health checks'
        health, _ = request('GET', BACKEND + '/actuator/health')
        kc_health, _ = request('GET', 'http://127.0.0.1:9000/health/ready')
        if health.get('status') != 'UP' or kc_health.get('status') != 'UP':
            raise SmokeFailure('Application or Keycloak readiness is not UP')
        self.stage = 'admin authentication via loopback'
        values = parameters(self.settings, ['/keycloak/admin-password'])
        self.admin_password = values['/keycloak/admin-password']
        self.authenticate_admin()
        try:
            token = self.provision_identity()
            self.check_application(token)
        finally:
            self.disable_identity()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-file', type=Path, default=Path('/var/lib/naro/smoke-state.json'))
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SmokeFailure('Run as root on the Naro EC2 instance through Session Manager')
    args.state_file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with open(str(args.state_file) + '.lock', 'w') as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        test = SmokeTest(args.state_file)
        try:
            test.run()
        except Exception as error:
            print('FAILED at stage: ' + test.stage, file=sys.stderr)
            if isinstance(error, SmokeFailure):
                raise
            raise SmokeFailure('Unexpected diagnostic failure (' + type(error).__name__ + '); sensitive details suppressed') from None


if __name__ == '__main__':
    try:
        main()
    except SmokeFailure as error:
        raise SystemExit(str(error)) from None
    except Exception as error:
        raise SystemExit('Diagnostic initialization failed (' + type(error).__name__ + '); sensitive details suppressed') from None
