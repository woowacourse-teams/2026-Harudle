import argparse
import hmac
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

from assets import BUCKET, TEST_IMAGE, delete_images, load_fixture, run_lock, seed_records, upload
from cleanup import (baseline_state_sql, cleanup, cleanup_locked, cleanup_sql, load_baseline_state,
                     save_plan, signature)
from prepare import (NAME, PREFIX, build_fixture, database_guard, login_sql, members_sql, protect_seed,
                     require_encrypted_seed, reset_guard, write_fixture, write_members)

SCRIPT_DIR = Path(__file__).resolve().parent
APP_DIR = SCRIPT_DIR.parent
CLIENT_IMAGE = 'postgres:18-alpine'
FIXTURE_MAIN = 'com.harudle.diary.infrastructure.crypto.DiarySourceTextLoadTestFixtureMain'
PREPARATION_PREFIX = '.harudle-run-preparation-'


class LoadTestCommandFailure(RuntimeError):
    """Only fixed classification and validated control values may cross the CLI boundary."""

    def __init__(self, error, action):
        command = error.cmd
        code = 'EXTERNAL_COMMAND'
        if isinstance(command, (list, tuple)) and all(isinstance(part, str) for part in command):
            if command[:2] == ['aws', 's3api']:
                code = 'S3'
            elif command[:2] == ['docker', 'run'] and CLIENT_IMAGE in command:
                index = command.index(CLIENT_IMAGE)
                if command[index + 1:index + 2] == ['psql']:
                    code = 'DB_SQL'
            elif (command[:3] == ['docker', 'exec', '-i'] and len(command) == 7
                  and re.fullmatch(r'[0-9a-f]{12,64}', command[3])
                  and command[4:6] == ['sh', '-c'] and FIXTURE_MAIN in command[6]):
                code = 'FIXTURE_ENCRYPTION'
            elif command[:1] == ['docker']:
                code = 'CONTAINER_CONFIG'
        phase = action if action in {'prepare', 'export', 'reset', 'cleanup', 'purge-baseline'} else 'unknown'
        return_code = error.returncode if type(error.returncode) is int else 'unknown'
        super().__init__(f'EC2 command failed: phase={phase}, errorCode={code}, returnCode={return_code}. '
                         'Raw diagnostics were suppressed; keep the preparation files for retry.')


def publish_run_fixture(directory, accounts, settings):
    # Do not expose a selectable run until its DB identity and complete fixture are durable.
    fixture, seed, _ = build_fixture(accounts['baseUrl'], accounts['diariesPerMonth'], accounts, directory.name)
    with tempfile.TemporaryDirectory(prefix=PREPARATION_PREFIX, dir=directory.parent) as temporary:
        staging = Path(temporary)
        files = {'members.json': json.dumps(fixture, ensure_ascii=False, indent=2),
                 'seed.sql': seed, 'database.json': json.dumps(database_target(settings))}
        for name, value in files.items():
            with (staging / name).open('w') as output:
                output.write(value)
                output.flush()
                os.fsync(output.fileno())
        descriptor = os.open(staging, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        if directory.exists():
            raise ValueError('This run name already exists. Use a new name, such as peak2')
        staging.rename(directory)
        descriptor = os.open(directory.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    return fixture


def backend_settings(stopped=False, require_writer=False):
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
    if require_writer and values.get('DIARY_SOURCE_TEXT_RELEASE_MODE') != 'writer':
        raise ValueError('Preparing load test data requires the dev writer deployment')
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
    require_encrypted_seed(sql)
    command = ['docker', 'run', '--rm', '-i', '--network', network or f'container:{container}']
    for key in settings:
        command += ['--env', key]
    command += [CLIENT_IMAGE, 'psql', '-X', '-qAt', '--no-password', '-v', 'ON_ERROR_STOP=1', '-f', '-']
    # Only the client runs in Docker. Connect to the actual dev DB configured on the backend.
    result = subprocess.run(command, input=sql, env={**os.environ, **settings},
                            check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return result.stdout.strip()


def encrypt_seed(container, rows):
    if not re.fullmatch(r'[0-9a-f]{12,64}', container):
        raise ValueError('Exactly one running backend is required for fixture encryption')
    encrypted = []
    for start in range(0, len(rows), 1000):
        result = subprocess.run([
            'docker', 'exec', '-i', container, 'sh', '-c',
            'exec java ${JAVA_OPTS:-} -Xmx128m -Xlog:disable -Dloader.main=' + FIXTURE_MAIN
            + ' -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher',
        ], input=json.dumps(rows[start:start + 1000]), check=True, capture_output=True, text=True)
        if len(result.stdout) > 1_048_576:
            raise ValueError('Fixture encryption returned an invalid result')
        try:
            chunk = json.loads(result.stdout)
        except (ValueError, TypeError):
            raise ValueError('Fixture encryption returned an invalid result') from None
        if not isinstance(chunk, list) or len(chunk) != len(rows[start:start + 1000]):
            raise ValueError('Fixture encryption returned an incomplete result')
        encrypted.extend(chunk)
    return encrypted


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


def prepare_baseline(group, accounts, settings, sql_runner, source_encryptor):
    directory = group / 'baseline'
    expected, _, _ = build_fixture(accounts['baseUrl'], accounts['diariesPerMonth'], accounts, 'baseline')
    if directory.exists():
        if (directory / 'purge.json').exists():
            raise ValueError('Finish purge-baseline before preparing another run')
        fixture = load_fixture(directory)
        check_database(directory, settings)
        if fixture.get('memberSetId') != accounts['memberSetId'] or fixture['cleanupKey'] != accounts['cleanupKey']:
            raise ValueError('The baseline belongs to another test member set')
        if fixture.get('sourceTextVersion') != 1:
            raise ValueError('Use purge-baseline explicitly to remove the legacy baseline before migration')
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
    if source_encryptor is None:
        raise ValueError('The running dev writer is required to encrypt the baseline')
    seed = protect_seed(fixture, seed, source_encryptor)
    (directory / 'seed.sql').write_text(seed)
    upload(fixture)
    sql_runner(seed)
    state = json.loads(sql_runner(baseline_state_sql(fixture)))
    save_plan(directory / 'state.json', {'runId': fixture['runId'], 'baselineId': fixture['baselineId'],
              'generations': seed_records(fixture), 'state': state}, fixture)
    (directory / '.ready').touch()
    print('Prepared the monthly baseline. Later runs will reuse its data and images.')
    return True


def reset_run(directory, group, settings, sql_runner, client_stopped, source_encryptor=None):
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
                cleanup_locked(previous, sql_runner, client_stopped, database_target(settings))
        created = prepare_baseline(group, accounts, settings, sql_runner, source_encryptor)
        fixture = publish_run_fixture(directory, accounts, settings)
        with run_lock(directory):
            load_baseline_state(directory, fixture)
            set_active_run(group, directory.name)
            if not created:
                sql_runner(login_sql(fixture))
            (directory / '.ready').touch()
    print(f'Ready: {directory.name}. Run local k6 with the same run name. Reuse the existing members.json.')


def cleanup_run(directory, settings, sql_runner, client_stopped):
    check_database(directory, settings)
    cleanup(directory, execute_sql=sql_runner, client_stopped=client_stopped,
            expected_database=database_target(settings))


def require_cleaned_runs(group, accounts, settings):
    active = group / 'active-run'
    if active.exists():
        name = active.read_text()
        if not re.fullmatch(NAME, name) or not (group.parent / name / '.cleaned').is_file():
            raise ValueError('Clean the active test run before purging its baseline')
        fixture = load_fixture(group.parent / name)
        if (fixture.get('memberSetId') != accounts['memberSetId'] or fixture.get('runName') != name
                or fixture.get('memberSetName') != group.name or fixture['cleanupKey'] != accounts['cleanupKey']):
            raise ValueError('The active run belongs to another member set')
        check_database(group.parent / name, settings)
    for directory in group.parent.iterdir():
        if directory.name.startswith(PREPARATION_PREFIX) and not re.fullmatch(NAME, directory.name):
            continue
        if directory == group or not (directory / 'members.json').is_file():
            continue
        candidate = json.loads((directory / 'members.json').read_text())
        if candidate.get('memberSetId') != accounts['memberSetId']:
            continue
        fixture = load_fixture(directory)
        if (fixture.get('memberSetName') != group.name or fixture.get('runName') != directory.name
                or fixture['cleanupKey'] != accounts['cleanupKey']):
            raise ValueError('A test run belongs to another member set')
        if not (directory / '.cleaned').is_file():
            raise ValueError('Clean every unfinished test run before purging its baseline')
        check_database(directory, settings)


def purge_baseline(group, settings, sql_runner, client_stopped):
    if not client_stopped:
        raise ValueError('Stop local k6 first, then use --client-stopped')
    with run_lock(group):
        accounts = load_fixture(group)
        if (accounts['version'] != 3 or 'runName' in accounts or accounts['memberSetName'] != group.name
                or not (group / '.ready').is_file()):
            raise ValueError('Select a prepared reusable members folder')
        check_database(group, settings)
        require_cleaned_runs(group, accounts, settings)
        directory = group / 'baseline'
        if not directory.exists():
            print('No monthly baseline remains. Reusable members were kept.')
            return
        with run_lock(directory):
            purge_baseline_locked(directory, accounts, settings, sql_runner)
    print('Monthly baseline purged. Reusable members were kept; create a new baseline after migration.')


def purge_baseline_locked(directory, accounts, settings, sql_runner):
    fixture = load_fixture(directory)
    check_database(directory, settings)
    if (fixture['version'] != 3 or fixture.get('runName') != 'baseline'
            or fixture.get('memberSetName') != accounts['memberSetName']
            or fixture.get('memberSetId') != accounts['memberSetId']
            or fixture['cleanupKey'] != accounts['cleanupKey']):
        raise ValueError('The baseline belongs to another test member set')
    records = seed_records(fixture)
    plan_path = directory / 'purge.json'
    if plan_path.exists():
        plan = json.loads(plan_path.read_text())
        if (plan.get('runId') != fixture['runId'] or plan.get('generations') != records
                or not hmac.compare_digest(plan.get('signature', ''), signature(fixture, plan))):
            raise ValueError('The baseline purge plan changed')
    else:
        save_plan(plan_path, {'runId': fixture['runId'], 'generations': records}, fixture)
    # The existing SQL refuses processing, extra diaries, changed owners and unexpected generation/image IDs.
    sql_runner(cleanup_sql(fixture, records))
    delete_images(fixture, records)
    shutil.rmtree(directory)


def main():
    parser = argparse.ArgumentParser(description='Prepare or clean test data on the dev EC2')
    parser.add_argument('action', choices=['prepare', 'export', 'reset', 'cleanup', 'purge-baseline'])
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
        container, settings = backend_settings(require_writer=args.action in {'prepare', 'reset'})
        sql_runner = lambda sql: execute_sql(container, settings, sql)
        if args.action == 'prepare':
            prepare_run(directory, settings, sql_runner)
        elif args.action == 'reset':
            reset_run(directory, SCRIPT_DIR / 'runs' / args.members, settings, sql_runner, args.client_stopped,
                      lambda rows: encrypt_seed(container, rows))
        elif args.action == 'purge-baseline':
            purge_baseline(directory, settings, sql_runner, args.client_stopped)
        else:
            cleanup_run(directory, settings, sql_runner, args.client_stopped)
    except subprocess.CalledProcessError as error:
        parser.error(str(LoadTestCommandFailure(error, args.action)))
    except (OSError, ValueError, KeyError, TypeError, RuntimeError):
        parser.error(f'EC2 operation refused: phase={args.action}, errorCode=INVALID_STATE. '
                     'Check the prepared files, member/DB identity and cleanup prerequisites.')


if __name__ == '__main__':
    main()
