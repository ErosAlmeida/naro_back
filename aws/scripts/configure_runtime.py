#!/usr/bin/env python3
"""Create isolated database users and root-only environment files on the EC2 host."""
import json
import os
import subprocess
import sys
from urllib.parse import urlencode

from runtime_support import environment_file, infrastructure, parameters, private_file


CA_FILE = '/etc/pki/rds/global-bundle.pem'


def sql_literal(value):
    return "'" + value.replace("'", "''") + "'"


def database_setup(settings, secrets):
    # Passwords travel through stdin/environment, never argv, user data or log output.
    process_env = dict(os.environ, PGHOST=settings['DB_HOST'], PGPORT=settings['DB_PORT'],
                       PGUSER=settings['DB_ADMIN_USER'], PGPASSWORD=secrets['/database/master-password'],
                       PGSSLMODE='verify-full', PGSSLROOTCERT=CA_FILE, PGCONNECT_TIMEOUT='15')

    def execute(database, statements):
        result = subprocess.run(['psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-d', database],
                                input=statements, env=process_env, capture_output=True, text=True)
        if result.returncode:
            # psql may include an SQL statement in its error, including a password.
            raise RuntimeError('RDS bootstrap failed; verify reachability, TLS, credentials and existing ownership')

    for user, database, password in [
        (settings['DB_USER'], settings['DB_NAME'], secrets['/database/password']),
        (settings['KEYCLOAK_DB_USER'], settings['KEYCLOAK_DB_NAME'], secrets['/keycloak/db-password']),
    ]:
        # Identifiers are validated in infrastructure(); all password literals are escaped.
        execute('postgres', f"""
SET standard_conforming_strings = on;
SELECT 'CREATE ROLE {user} LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '{user}')\n\\gexec
ALTER ROLE {user} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD {sql_literal(password)};
GRANT {user} TO {settings['DB_ADMIN_USER']};
SELECT 'CREATE DATABASE {database} OWNER {user}' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '{database}')\n\\gexec
ALTER DATABASE {database} OWNER TO {user};
REVOKE ALL ON DATABASE {database} FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE {database} TO {user};
""")
        execute(database, f"""
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO {user};
""")


def main():
    settings = infrastructure()
    print('Reading required SSM parameters through the EC2 instance role.', flush=True)
    secrets = parameters(settings, ['/database/master-password', '/database/password',
                                    '/keycloak/db-password', '/keycloak/admin-password'])
    print('Preparing database users and permissions over verified TLS.', flush=True)
    database_setup(settings, secrets)
    public_origin = settings['PUBLIC_ORIGIN'].rstrip('/')
    frontend_origin = settings['FRONTEND_ORIGIN'].rstrip('/')
    database_url = f"jdbc:postgresql://{settings['DB_HOST']}:{settings['DB_PORT']}/{settings['DB_NAME']}"
    database_url += '?' + urlencode({'sslmode': 'verify-full', 'sslrootcert': CA_FILE})
    environment_file('/etc/naro/backend.env', {
        'SPRING_PROFILES_ACTIVE': 'prod', 'SERVER_PORT': '8080',
        'DATABASE_URL': database_url, 'DATABASE_USERNAME': settings['DB_USER'],
        'DATABASE_PASSWORD': secrets['/database/password'],
        'AWS_REGION': settings['AWS_REGION'], 'S3_BUCKET': settings['S3_BUCKET_NAME'],
        'KEYCLOAK_ISSUER_URI': public_origin + '/auth/realms/social',
        'KEYCLOAK_JWK_SET_URI': 'http://127.0.0.1:8081/auth/realms/social/protocol/openid-connect/certs',
        'CORS_ALLOWED_ORIGINS': frontend_origin,
    })
    keycloak_url = f"jdbc:postgresql://{settings['DB_HOST']}:{settings['DB_PORT']}/{settings['KEYCLOAK_DB_NAME']}"
    keycloak_url += '?' + urlencode({'sslmode': 'verify-full', 'sslrootcert': '/opt/keycloak/conf/rds-ca.pem'})
    environment_file('/etc/naro/keycloak.env', {
        'KC_BOOTSTRAP_ADMIN_USERNAME': settings.get('KEYCLOAK_ADMIN', 'naro-admin'),
        'KC_BOOTSTRAP_ADMIN_PASSWORD': secrets['/keycloak/admin-password'],
        'KC_DB': 'postgres', 'KC_DB_URL': keycloak_url,
        'KC_DB_USERNAME': settings['KEYCLOAK_DB_USER'], 'KC_DB_PASSWORD': secrets['/keycloak/db-password'],
        'KC_DB_POOL_INITIAL_SIZE': '2', 'KC_DB_POOL_MIN_SIZE': '2', 'KC_DB_POOL_MAX_SIZE': '8',
        'KC_HOSTNAME': public_origin + '/auth', 'KC_HOSTNAME_STRICT': 'true',
        # Browser administration is reachable only through an SSM local port tunnel.
        'KC_HOSTNAME_ADMIN': 'http://localhost:18081/auth',
        'KC_HTTP_ENABLED': 'true', 'KC_HTTP_PORT': '8081', 'KC_HTTP_RELATIVE_PATH': '/auth',
        'KC_PROXY_HEADERS': 'xforwarded', 'KC_HEALTH_ENABLED': 'true', 'KC_METRICS_ENABLED': 'true',
        'KC_HTTP_MANAGEMENT_PORT': '9000', 'KC_HTTP_MANAGEMENT_RELATIVE_PATH': '/',
        'KC_HTTP_MANAGEMENT_SCHEME': 'http', 'KC_CACHE': 'local',
        'JAVA_OPTS_APPEND': '-Xms128m -Xmx384m -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError',
    }, docker=True)
    # CloudWatch agent reads an explicit list: no .env, user-data or credentials.
    private_file('/etc/naro/cloudwatch-agent.json', json.dumps({
        'agent': {'run_as_user': 'root', 'region': settings['AWS_REGION']},
        'logs': {'logs_collected': {'files': {'collect_list': [
            {'file_path': f'/var/log/naro/{name}.log', 'log_group_name': settings['CLOUDWATCH_LOG_GROUP'],
             'log_stream_name': '{instance_id}/' + name, 'timezone': 'UTC'}
            for name in ['backend', 'keycloak']
        ]}}},
    }))
    print('RDS users/databases, runtime environment and CloudWatch configuration prepared.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print repr(error): it can carry subprocess output or a secret.
        print(f'Runtime configuration failed ({type(error).__name__}); check SSM parameters, IAM and RDS.', file=sys.stderr)
        raise SystemExit(1)
