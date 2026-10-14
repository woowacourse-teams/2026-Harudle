import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from assets import BUCKET, TEST_IMAGE, load_fixture, run_lock, upload
from cleanup import cleanup, cleanup_locked
from prepare import NAME, PREFIX, database_guard, members_sql, reset_guard, write_fixture, write_members

SCRIPT_DIR = Path(__file__).resolve().parent
APP_DIR = SCRIPT_DIR.parent
CLIENT_IMAGE = 'postgres:18-alpine'


def backend_settings():
    for command in ['docker', 'aws']:
        if not shutil.which(command):
            raise ValueError(f'{command} is required on EC2')
    result = subprocess.run([
        'docker', 'compose', '--project-name', 'harudle', '--env-file', str(APP_DIR / '.env'),
        '--file', str(APP_DIR / 'compose.prod.yaml'), 'ps', '--status', 'running', '--quiet', 'backend',
    ], check=True, capture_output=True, text=True)
    container = result.stdout.strip()
    if not re.fullmatch(r'[0-9a-f]{12,64}', container):
        raise ValueError('Exactly one running backend is required')
    result = subprocess.run(['docker', 'inspect', '--format', '{{json .Config.Env}}', container],
                            check=True, capture_output=True, text=True)
    values = dict(item.split('=', 1) for item in json.loads(result.stdout))
    if (values.get('DEPLOY_ENV') != 'dev' or values.get('DB_NAME') != 'harudle_dev'
            or values.get('S3_BUCKET') != BUCKET or values.get('S3_GENERATED_PREFIX') != PREFIX):
        raise ValueError('The backend must use dev, harudle_dev and the dev S3 image path')
    if not all(values.get(key) for key in ['DB_HOST', 'DB_USERNAME', 'DB_PASSWORD']):
        raise ValueError('The backend is missing DB connection settings')
    settings = {
        'PGHOST': values['DB_HOST'], 'PGPORT': values.get('DB_PORT') or '5432',
        'PGDATABASE': values['DB_NAME'], 'PGUSER': values['DB_USERNAME'],
        'PGPASSWORD': values['DB_PASSWORD'], 'PGCONNECT_TIMEOUT': '10', 'PGSSLMODE': 'require',
    }
    return container, settings


def database_target(settings):
    return {key: settings[key] for key in ['PGHOST', 'PGPORT', 'PGDATABASE']}


def execute_sql(container, settings, sql):
    command = ['docker', 'run', '--rm', '-i', '--network', f'container:{container}']
    for key in settings:
        command += ['--env', key]
    command += [CLIENT_IMAGE, 'psql', '-X', '-qAt', '--no-password', '-v', 'ON_ERROR_STOP=1', '-f', '-']
    # Only the client runs in Docker. The actual DB is the backend's dev RDS.
    result = subprocess.run(command, input=sql, env={**os.environ, **settings},
                            check=True, stdout=subprocess.PIPE, text=True)
    return result.stdout.strip()


def prepare_run(directory, settings, sql_runner):
    if not directory.exists():
        write_members(directory)
    with run_lock(directory):
        fixture = load_fixture(directory)
        if (fixture['version'] != 3 or 'runName' in fixture or fixture['memberSetName'] != directory.name):
            raise ValueError('Use a reusable members folder')
        if (directory / 'database.json').exists():
            check_database(directory, settings)
        if (directory / '.ready').exists():
            print(f'Reusable members already prepared: {directory.name}. Use reset before each test.')
            return
        (directory / 'database.json').write_text(json.dumps(database_target(settings)))
        sql_runner(members_sql(fixture))
        (directory / '.ready').touch()
    print(f'Prepared 200 reusable members in the dev DB: {directory.name}. Export members once.')


def check_database(directory, settings):
    target = json.loads((directory / 'database.json').read_text())
    if target != database_target(settings):
        raise ValueError('The backend DB address changed. Use the original dev DB')


def set_active_run(group, name):
    temporary = group / 'active-run.tmp'
    with temporary.open('w') as output:
        output.write(name)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(group / 'active-run')
    descriptor = os.open(group, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def reset_run(directory, group, settings, sql_runner, client_stopped):
    if not client_stopped:
        raise ValueError('Stop local k6 first, then use --client-stopped')
    if not TEST_IMAGE.is_file():
        raise ValueError('The test image is missing from this deployment')
    if directory.exists():
        raise ValueError('This run name already exists. Use a new name, such as peak2')
    with run_lock(group):
        accounts = load_fixture(group)
        if (accounts['version'] != 3 or 'runName' in accounts or accounts['memberSetName'] != group.name
                or not (group / '.ready').exists()):
            raise ValueError('Prepare reusable members first')
        check_database(group, settings)
        result = sql_runner(database_guard() + 'SELECT EXISTS (SELECT 1 FROM generation_prompts);\nCOMMIT;')
        if result != 't':
            raise ValueError('The dev DB needs generation_prompts before preparing data')
        active = group / 'active-run'
        if active.exists():
            name = active.read_text()
            if not re.fullmatch(NAME, name):
                raise ValueError('Invalid active run name')
            previous = group.parent / name
            check_database(previous, settings)
            with run_lock(previous):
                cleanup_locked(previous, sql_runner, client_stopped)
        # Check member ownership and leftover data before publishing a new active run.
        sql_runner(database_guard() + reset_guard(accounts) + '\nROLLBACK;')
        fixture, seed, _ = write_fixture(directory, accounts=accounts)
        with run_lock(directory):
            (directory / 'database.json').write_text(json.dumps(database_target(settings)))
            # Persist ownership before either S3 or DB can change.
            set_active_run(group, directory.name)
            upload(fixture)
            sql_runner(seed)
            (directory / '.ready').touch()
    print(f'Ready: {directory.name}. Run local k6 with the same run name. Reuse the existing members.json.')


def cleanup_run(directory, settings, sql_runner, client_stopped):
    check_database(directory, settings)
    cleanup(directory, execute_sql=sql_runner, client_stopped=client_stopped)


def main():
    parser = argparse.ArgumentParser(description='Prepare or clean test data on the dev EC2')
    parser.add_argument('action', choices=['prepare', 'export', 'reset', 'cleanup'])
    parser.add_argument('run', help='members for prepare/export; a new name such as peak1 for reset')
    parser.add_argument('--members', default='members', help='Reusable members folder used by reset')
    parser.add_argument('--client-stopped', action='store_true', help='Confirm local k6 has stopped')
    args = parser.parse_args()
    if not all(re.fullmatch(NAME, name) for name in [args.run, args.members]):
        parser.error('Use only letters, digits, hyphens and underscores for the run name')
    os.umask(0o077)
    directory = SCRIPT_DIR / 'runs' / args.run
    try:
        if args.action == 'export':
            from transfer import export_members
            print(export_members(directory, sys.stdin.read(16384)), end='')
            return
        container, settings = backend_settings()
        sql_runner = lambda sql: execute_sql(container, settings, sql)
        if args.action == 'prepare':
            prepare_run(directory, settings, sql_runner)
        elif args.action == 'reset':
            reset_run(directory, SCRIPT_DIR / 'runs' / args.members, settings, sql_runner, args.client_stopped)
        else:
            cleanup_run(directory, settings, sql_runner, args.client_stopped)
    except subprocess.CalledProcessError:
        parser.error('EC2 command failed. Check Docker, dev DB access and S3 permissions; then retry cleanup if needed')
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
