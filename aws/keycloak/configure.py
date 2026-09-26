#!/usr/bin/env python3
"""Idempotent Keycloak configuration, using only the host's loopback Admin API."""
import json
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from runtime_support import infrastructure, parameters


SERVER = 'http://127.0.0.1:8081/auth'


def http(method, path, payload=None, token=None, form=False):
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None
    if payload is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        data = (urlencode(payload) if form else json.dumps(payload)).encode()
    with urlopen(Request(SERVER + path, data=data, headers=headers, method=method), timeout=15) as response:
        body = response.read()
        return json.loads(body) if body else None


def main():
    settings = infrastructure()
    secrets = parameters(settings, ['/keycloak/admin-password', '/keycloak/client-secret'])
    print('Waiting for Keycloak database readiness.', flush=True)
    for attempt in range(150):
        try:
            with urlopen('http://127.0.0.1:9000/health/ready', timeout=3) as response:
                if response.status == 200:
                    break
        except (URLError, OSError):
            pass
        time.sleep(2)
    else:
        raise RuntimeError('Keycloak readiness timeout')
    print('Updating Keycloak configuration through the loopback Admin API.', flush=True)
    token = http('POST', '/realms/master/protocol/openid-connect/token', {
        'grant_type': 'password', 'client_id': 'admin-cli',
        'username': settings.get('KEYCLOAK_ADMIN', 'naro-admin'),
        'password': secrets['/keycloak/admin-password'],
    }, form=True)['access_token']
    realm_path = '/admin/realms/social'
    try:
        http('GET', realm_path, token=token)
    except HTTPError as error:
        if error.code != 404:
            raise
        http('POST', '/admin/realms', {'realm': 'social', 'enabled': True}, token)
    local_settings = Path(__file__).resolve().parents[2] / 'infra/keycloak/realm-settings.json'
    realm_settings = json.loads(local_settings.read_text())
    public_origin = settings['PUBLIC_ORIGIN'].rstrip('/')
    frontend_origin = settings['FRONTEND_ORIGIN'].rstrip('/')
    for origin in [public_origin, frontend_origin]:
        parsed = urlsplit(origin)
        if parsed.scheme not in ['http', 'https'] or not parsed.netloc or parsed.path or parsed.query or parsed.fragment:
            raise ValueError('Expected an HTTP(S) origin without a path')
    # HTTP is explicitly a temporary lab choice. Do not weaken the master realm.
    realm_settings['sslRequired'] = 'external' if public_origin.startswith('https://') else 'none'
    http('PUT', realm_path, realm_settings, token)
    clients = [
        {
            'clientId': 'naro-web', 'name': 'Naro Web', 'enabled': True, 'protocol': 'openid-connect',
            'publicClient': False, 'secret': secrets['/keycloak/client-secret'],
            'standardFlowEnabled': True, 'directAccessGrantsEnabled': False,
            'implicitFlowEnabled': False, 'serviceAccountsEnabled': False,
            'redirectUris': [frontend_origin + '/api/auth/callback'], 'webOrigins': [frontend_origin],
            'attributes': {'pkce.code.challenge.method': 'S256',
                           'post.logout.redirect.uris': frontend_origin + '/login?signedOut=1'},
        },
        {
            'clientId': 'naro-swagger', 'name': 'Naro Swagger', 'enabled': True, 'protocol': 'openid-connect',
            'publicClient': True, 'standardFlowEnabled': True, 'directAccessGrantsEnabled': False,
            'implicitFlowEnabled': False, 'serviceAccountsEnabled': False,
            'redirectUris': [public_origin + '/swagger-ui/oauth2-redirect.html'], 'webOrigins': [public_origin],
            'attributes': {'pkce.code.challenge.method': 'S256'},
        },
    ]
    for client in clients:
        matches = http('GET', realm_path + '/clients?clientId=' + quote(client['clientId']), token=token)
        if len(matches) > 1:
            raise RuntimeError('Ambiguous Keycloak client')
        if matches:
            # Preserve attributes/settings that this deployment does not manage.
            merged = http('GET', realm_path + '/clients/' + matches[0]['id'], token=token)
            attributes = {**merged.get('attributes', {}), **client['attributes']}
            merged.update(client)
            merged['attributes'] = attributes
            http('PUT', realm_path + '/clients/' + matches[0]['id'], merged, token)
        else:
            http('POST', realm_path + '/clients', client, token)
    profile = http('GET', realm_path + '/users/profile', token=token)
    limits = {'username': 100, 'email': 255, 'firstName': 100, 'lastName': 100}
    for attribute in profile.get('attributes', []):
        if attribute.get('name') in limits:
            length = attribute.setdefault('validations', {}).setdefault('length', {})
            length['max'] = min(int(length.get('max', limits[attribute['name']])), limits[attribute['name']])
    http('PUT', realm_path + '/users/profile', profile, token)
    # hostname-admin alone does not move master-realm authentication off the public
    # origin. Keep both the Admin UI and its login URLs on the encrypted SSM tunnel.
    # Updating the issuer can invalidate this token, so do this last and authenticate
    # afresh on the next run. Preserve master.sslRequired=external.
    master = http('GET', '/admin/realms/master', token=token)
    attributes = {**master.get('attributes', {}), 'frontendUrl': 'http://localhost:18081/auth'}
    http('PUT', '/admin/realms/master', {'attributes': attributes}, token)
    print('Realm social configured; confidential naro-web and public PKCE naro-swagger preserved.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # HTTP/subprocess errors can include credentials. Emit the type only.
        print(f'Keycloak configuration failed ({type(error).__name__}); inspect health, IAM and SSM.', file=sys.stderr)
        raise SystemExit(1)
