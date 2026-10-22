"""Remove unfinished work for one load-test run while the dev backend is stopped."""

import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

from assets import load_fixture, member_directory, run_lock, validate_active_run
from cleanup import member_filter
from ec2 import backend_settings, check_database, ensure_backend_stopped, execute_sql
from prepare import NAME, database_guard, quote


SCRIPT_DIR = Path(__file__).resolve().parent


def cleanup_sql(fixture):
    ids, email = member_filter(fixture)
    keys = ', '.join(quote(uuid.uuid5(uuid.UUID(fixture['runId']), f'create-{index}'))
                     for index in range(len(fixture['members'])))
    return database_guard() + f"""
CREATE TEMP TABLE stopped_cleanup_targets ON COMMIT DROP AS
SELECT g.id, g.diary_id, d.user_id, g.usage_date, g.image_object_key
FROM diary_generations g JOIN diaries d ON d.id = g.diary_id JOIN users u ON u.id = d.user_id
WHERE u.id IN ({ids}) AND u.primary_email LIKE {email} AND u.role = 'USER'
  AND g.idempotency_key IN ({keys}) AND g.status IN ('PENDING', 'PROCESSING')
ORDER BY g.id FOR UPDATE OF g, d, u;
CREATE TEMP TABLE stopped_cleanup_result (result jsonb) ON COMMIT DROP;
DO $$
DECLARE
    removed_diaries integer;
    updated_usage_rows integer;
    remaining_test_work integer;
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE id IN ({ids})
               AND (primary_email IS NULL OR primary_email NOT LIKE {email} OR role <> 'USER')) THEN
        RAISE EXCEPTION 'Test member ownership changed';
    END IF;
    IF EXISTS (SELECT 1 FROM stopped_cleanup_targets WHERE image_object_key IS NOT NULL)
       OR EXISTS (SELECT 1 FROM feeds f JOIN stopped_cleanup_targets t ON t.diary_id = f.diary_id) THEN
        RAISE EXCEPTION 'Unfinished work has an image or feed; inspect it before cleanup';
    END IF;
    UPDATE daily_generation_usage usage
    SET used_count = GREATEST(0, usage.used_count - refunds.amount), updated_at = CURRENT_TIMESTAMP
    FROM (SELECT user_id, usage_date, COUNT(*)::integer AS amount FROM stopped_cleanup_targets
          WHERE usage_date IS NOT NULL GROUP BY user_id, usage_date) refunds
    WHERE usage.user_id = refunds.user_id AND usage.usage_date = refunds.usage_date;
    GET DIAGNOSTICS updated_usage_rows = ROW_COUNT;
    DELETE FROM diaries d USING stopped_cleanup_targets t WHERE d.id = t.diary_id;
    GET DIAGNOSTICS removed_diaries = ROW_COUNT;
    IF removed_diaries <> (SELECT COUNT(*) FROM stopped_cleanup_targets) THEN
        RAISE EXCEPTION 'Cleanup target changed; transaction cancelled';
    END IF;
    SELECT COUNT(*) INTO remaining_test_work
    FROM diary_generations g JOIN diaries d ON d.id = g.diary_id JOIN users u ON u.id = d.user_id
    WHERE u.primary_email LIKE '%.loadtest.invalid' AND g.status IN ('PENDING', 'PROCESSING');
    INSERT INTO stopped_cleanup_result VALUES (jsonb_build_object(
        'removedGenerations', removed_diaries, 'removedDiaries', removed_diaries,
        'updatedUsageRows', updated_usage_rows, 'remainingTestWork', remaining_test_work));
END $$;
SELECT result FROM stopped_cleanup_result;
COMMIT;
"""


def cleanup_run(directory, container, settings, sql_runner, client_stopped):
    if not client_stopped:
        raise ValueError('Stop local k6 first, then use --client-stopped')
    fixture = load_fixture(directory)
    group = member_directory(directory, fixture)
    if 'baselineId' not in fixture:
        raise ValueError('Use a reusable test run prepared with reset')
    with ExitStack() as locks:
        locks.enter_context(run_lock(group))
        locks.enter_context(run_lock(directory))
        validate_active_run(directory, fixture)
        check_database(directory, settings)
        check_database(group, settings)
        ensure_backend_stopped(container)
        result = json.loads(sql_runner(cleanup_sql(fixture)))
        names = {'removedGenerations', 'removedDiaries', 'updatedUsageRows', 'remainingTestWork'}
        if (not isinstance(result, dict) or set(result) != names
                or any(type(value) is not int or value < 0 for value in result.values())):
            raise ValueError('Unexpected cleanup result; inspect the DB before starting the backend')
        print(json.dumps(result), flush=True)
        if result['remainingTestWork']:
            raise ValueError('This run was cleaned, but unfinished test work remains. Clean its run before restarting')
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', help='Active test run, such as peak1')
    parser.add_argument('--client-stopped', action='store_true', help='Confirm local k6 has stopped')
    args = parser.parse_args()
    if not re.fullmatch(NAME, args.run):
        parser.error('Use only letters, digits, hyphens and underscores for the run name')
    if not args.client_stopped:
        parser.error('Stop local k6 first, then use --client-stopped')
    os.umask(0o077)
    try:
        container, settings = backend_settings(stopped=True)
        runner = lambda sql: execute_sql(container, settings, sql, network='host')
        cleanup_run(SCRIPT_DIR / 'runs' / args.run, container, settings, runner, args.client_stopped)
    except subprocess.CalledProcessError:
        parser.error('Command failed. Check the stopped container and dev DB access. Do not restart yet')
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
