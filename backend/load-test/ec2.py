import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from assets import BUCKET, TEST_IMAGE, delete_images, load_fixture, run_lock, seed_records, upload
from cleanup import baseline_state_sql, cleanup, cleanup_locked, cleanup_sql, load_baseline_state, save_plan
from prepare import MEMBERS, MEMBER_COUNTS, NAME, PREFIX, build_fixture, database_guard, login_sql, members_sql, reset_guard, write_fixture, write_members

SCRIPT_DIR = Path(__file__).resolve().parent
APP_DIR = SCRIPT_DIR.parent
CLIENT_IMAGE = 'postgres:18-alpine'


def backend_settings(stopped=False):
    for command in (['docker'] if stopped else ['docker', 'aws']):
        if not shutil.which(command):
            raise ValueError(f'{command} is required on EC2')
    command = [
        'docker', 'compose', '--project-name', 'harudle', '--env-file', str(APP_DIR / '.env'),
        '--file', str(APP_DIR / 'compose.prod.yaml'), 'ps',
    ]
    command += ['--all'] if stopped else ['--status', 'running']
    result = subprocess.run(command + ['--quiet', 'backend'], check=True, capture_output=True, text=True)
    container = result.stdout.strip()
    if not re.fullmatch(r'[0-9a-f]{12,64}', container):
        raise ValueError('Exactly one backend container is required; keep it with docker compose stop, not down')
    if stopped:
        ensure_backend_stopped(container)
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


def ensure_backend_stopped(container):
    result = subprocess.run(['docker', 'inspect', '--format', '{{json .State}}', container],
                            check=True, capture_output=True, text=True)
    state = json.loads(result.stdout)
    if (not isinstance(state, dict) or state.get('Status') != 'exited' or state.get('Running') is not False
            or state.get('Restarting') is not False or state.get('Pid') != 0):
        raise ValueError('Stop the backend container before cleaning unfinished test work')


def database_target(settings):
    return {key: settings[key] for key in ['PGHOST', 'PGPORT', 'PGDATABASE']}


def execute_sql(container, settings, sql, network=None):
    command = ['docker', 'run', '--rm', '-i', '--network', network or f'container:{container}']
    for key in settings:
        command += ['--env', key]
    command += [CLIENT_IMAGE, 'psql', '-X', '-qAt', '--no-password', '-v', 'ON_ERROR_STOP=1', '-f', '-']
    # Only the client runs in Docker. The actual DB is the backend's dev RDS.
    result = subprocess.run(command, input=sql, env={**os.environ, **settings},
                            check=True, stdout=subprocess.PIPE, text=True)
    return result.stdout.strip()


def prepare_run(directory, settings, sql_runner, member_count=MEMBERS):
    if not directory.exists():
        write_members(directory, member_count)
    with run_lock(directory):
        fixture = load_fixture(directory)
        if len(fixture['members']) != member_count:
            raise ValueError('The existing member set has a different size; use a new folder')
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
    print(f'Prepared {member_count} reusable members in the dev DB: {directory.name}. Export members once.')


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


def prepare_baseline(group, accounts, settings, sql_runner):
    directory = group / 'baseline'
    expected, _, _ = build_fixture(accounts['baseUrl'], accounts['diariesPerMonth'], accounts, 'baseline')
    if directory.exists():
        fixture = load_fixture(directory)
        check_database(directory, settings)
        if fixture.get('memberSetId') != accounts['memberSetId'] or fixture['cleanupKey'] != accounts['cleanupKey']:
            raise ValueError('The baseline belongs to another test member set')
        if fixture.get('baselineId') == expected['baselineId'] and (directory / '.ready').exists():
            return False
        # A new month or an interrupted first preparation needs one full rebuild.
        records = seed_records(fixture)
        sql_runner(cleanup_sql(fixture, records))
        delete_images(fixture, records)
        shutil.rmtree(directory)
    sql_runner(database_guard() + reset_guard(accounts) + '\nROLLBACK;')
    fixture, seed, _ = write_fixture(directory, accounts=accounts)
    (directory / 'database.json').write_text(json.dumps(database_target(settings)))
    upload(fixture)
    sql_runner(seed)
    state = json.loads(sql_runner(baseline_state_sql(fixture)))
    save_plan(directory / 'state.json', {'runId': fixture['runId'], 'baselineId': fixture['baselineId'],
              'generations': seed_records(fixture), 'state': state}, fixture)
    (directory / '.ready').touch()
    print('Prepared the monthly baseline. Later runs will reuse its data and images.')
    return True


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
        created = prepare_baseline(group, accounts, settings, sql_runner)
        fixture, _, _ = write_fixture(directory, accounts=accounts)
        with run_lock(directory):
            load_baseline_state(directory, fixture)
            (directory / 'database.json').write_text(json.dumps(database_target(settings)))
            set_active_run(group, directory.name)
            if not created:
                sql_runner(login_sql(fixture))
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
    parser.add_argument('--member-count', type=int, choices=MEMBER_COUNTS, default=MEMBERS,
                        help='Number of reusable members for prepare (200 or 1200)')
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
            prepare_run(directory, settings, sql_runner, args.member_count)
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
