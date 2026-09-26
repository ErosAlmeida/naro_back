#!/usr/bin/env python3
"""Create missing SSM Standard SecureStrings, without printing or persisting values."""
import argparse
import json
import secrets
import subprocess

NAMES = (
    'database/master-password',
    'database/password',
    'keycloak/db-password',
    'keycloak/admin-password',
    'keycloak/client-secret',
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--region', required=True)
    parser.add_argument('--profile')
    parser.add_argument('--account', required=True, help='Expected AWS account ID; prevents targeting another account')
    parser.add_argument('--prefix', default='/naro/prod')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    base = ['aws', '--region', args.region, '--no-cli-pager']
    if args.profile:
        base += ['--profile', args.profile]

    def call(command, payload=None):
        result = subprocess.run(base + command, input=json.dumps(payload) if payload else None,
                                text=True, capture_output=True)
        if result.returncode:
            # Never relay a provider response which might include a submitted secret.
            raise RuntimeError('AWS request failed: ' + ' '.join(command[:2]) + '. Check session and IAM permissions.')
        return json.loads(result.stdout or '{}')

    identity = call(['sts', 'get-caller-identity'])
    if identity['Account'] != args.account:
        raise RuntimeError('Active AWS account differs from --account; nothing changed.')
    for suffix in NAMES:
        name = args.prefix.rstrip('/') + '/' + suffix
        metadata = call(['ssm', 'describe-parameters', '--parameter-filters',
                         json.dumps([{'Key': 'Name', 'Option': 'Equals', 'Values': [name]}])])
        if metadata.get('Parameters'):
            param = metadata['Parameters'][0]
            if param['Type'] != 'SecureString' or param.get('Tier') != 'Standard':
                raise RuntimeError(name + ' exists with unexpected type/tier; inspect before continuing.')
            print('Preserved: ' + name)
            continue
        if args.dry_run:
            print('Would create: ' + name)
            continue
        # Use --value from stdin. --cli-input-json can reread stdin on some CLI versions.
        result = subprocess.run(base + [
            'ssm', 'put-parameter', '--name', name,
            '--description', 'Naro laboratory runtime secret; generated outside CloudFormation',
            '--value', 'file:///dev/stdin', '--type', 'SecureString', '--tier', 'Standard',
            '--no-overwrite', '--tags', 'Key=Project,Value=naro', 'Key=Environment,Value=prod',
        ], input=secrets.token_hex(24), text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError('Could not create ' + name + '; check AWS session and IAM permissions.')
        print('Created SecureString: ' + name)
    print('Values stayed in process memory and AWS Parameter Store; no secret files were created.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        raise SystemExit(str(error)) from None
