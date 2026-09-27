"""Small runtime helpers. Never print process output containing secrets."""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile


def infrastructure():
    settings = {}
    for line in Path('/etc/naro/infrastructure.env').read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        fields = shlex.split(line, comments=True)
        if len(fields) != 1 or '=' not in fields[0]:
            raise ValueError('Invalid infrastructure configuration')
        key, value = fields[0].split('=', 1)
        if not re.fullmatch(r'[A-Z][A-Z0-9_]*', key):
            raise ValueError('Invalid infrastructure configuration key')
        settings[key] = value
    required = ['AWS_REGION', 'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER',
                'DB_ADMIN_USER', 'KEYCLOAK_DB_NAME', 'KEYCLOAK_DB_USER',
                'NARO_PARAMETER_PREFIX', 'PUBLIC_ORIGIN', 'FRONTEND_ORIGIN',
                'S3_BUCKET_NAME', 'CLOUDWATCH_LOG_GROUP']
    if any(not settings.get(key) for key in required):
        raise ValueError('Missing infrastructure configuration')
    for key in ['DB_NAME', 'DB_USER', 'DB_ADMIN_USER', 'KEYCLOAK_DB_NAME', 'KEYCLOAK_DB_USER']:
        if not re.fullmatch(r'[a-z][a-z0-9_]{0,62}', settings[key]):
            raise ValueError('Unsafe database identifier')
    return settings


def parameters(settings, suffixes):
    prefix = settings['NARO_PARAMETER_PREFIX'].rstrip('/')
    names = [prefix + suffix for suffix in suffixes]
    result = subprocess.run(
        ['aws', 'ssm', 'get-parameters', '--region', settings['AWS_REGION'],
         '--names', *names, '--with-decryption', '--output', 'json'],
        capture_output=True, text=True, env=dict(os.environ, AWS_PAGER=''), check=False)
    if result.returncode:
        raise RuntimeError('Could not read the required SSM parameters; check instance role and region')
    payload = json.loads(result.stdout)
    values = {entry['Name']: entry['Value'] for entry in payload.get('Parameters', [])}
    if any(name not in values for name in names):
        raise RuntimeError('Missing SSM parameters; run prepare-secrets before deployment')
    for value in values.values():
        if not value or any(character in value for character in '\r\n\0'):
            raise ValueError('Secrets must be nonempty single-line values')
    return {suffix: values[prefix + suffix] for suffix in suffixes}


def optional_parameters(settings, suffixes):
    """Like parameters(), for optional features: all or nothing, never an error.

    Absent parameters, or an instance role that cannot read them yet (before the
    next cdk deploy), leave the feature off instead of failing the deployment.
    """
    try:
        return parameters(settings, suffixes)
    except (RuntimeError, ValueError):
        return {}


def private_file(path, content):
    destination = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix='.naro-', dir=destination.parent)
    try:
        with os.fdopen(descriptor, 'w') as output:
            output.write(content)
        os.chmod(temporary, 0o600)
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def environment_file(path, values, *, docker=False):
    lines = []
    for key, value in values.items():
        value = str(value)
        if any(character in value for character in '\r\n\0'):
            raise ValueError('Environment values must be single-line')
        if docker:
            # Docker --env-file does NOT remove shell quotes.
            encoded = value
        else:
            # systemd EnvironmentFile syntax; expansion is not performed.
            encoded = '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
        lines.append(f'{key}={encoded}\n')
    private_file(path, ''.join(lines))
