import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import uuid

from assets import delete_images, load_fixture, run_lock, seed_records, validate_records
from prepare import database_guard, quote


def psql(sql):
    # Use PGHOST/PGDATABASE/PGUSER and the normal psql password prompt or .pgpass.
    result = subprocess.run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                            input=sql, check=True, stdout=subprocess.PIPE, text=True)
    return result.stdout.strip()


def member_filter(fixture):
    ids = ', '.join(quote(member['id']) for member in fixture['members'])
    email = quote('%@' + uuid.UUID(fixture['runId']).hex + '.loadtest.invalid')
    return ids, email


def snapshot_sql(fixture):
    ids, email = member_filter(fixture)
    return database_guard() + f"""
SELECT COALESCE(jsonb_agg(jsonb_build_object(
    'id', g.id, 'userId', u.id, 'imageKey', g.image_object_key)), '[]'::jsonb)
FROM diary_generations g JOIN diaries d ON d.id = g.diary_id JOIN users u ON u.id = d.user_id
WHERE u.id IN ({ids}) AND u.primary_email LIKE {email};
COMMIT;
"""


def cleanup_sql(fixture, records):
    ids, email = member_filter(fixture)
    expected = quote(json.dumps(records))
    return database_guard() + f"""
CREATE TEMP TABLE cleanup_generations ON COMMIT DROP AS
SELECT * FROM jsonb_to_recordset({expected}::jsonb) AS x(id uuid, "userId" uuid, "imageKey" text);
DO $$
BEGIN
    PERFORM 1 FROM users WHERE id IN ({ids}) FOR UPDATE;
    IF EXISTS (SELECT 1 FROM users WHERE id IN ({ids})
               AND (primary_email IS NULL OR primary_email NOT LIKE {email})) THEN
        RAISE EXCEPTION 'Test member ownership changed';
    END IF;
    PERFORM 1 FROM diaries WHERE user_id IN ({ids}) FOR UPDATE;
    PERFORM 1 FROM diary_generations g JOIN diaries d ON d.id = g.diary_id
        WHERE d.user_id IN ({ids}) FOR UPDATE OF g;
    IF EXISTS (SELECT 1 FROM diary_generations g JOIN diaries d ON d.id = g.diary_id
               WHERE d.user_id IN ({ids}) AND g.status = 'PROCESSING') THEN
        RAISE EXCEPTION 'Generation is still processing. Wait before cleanup';
    END IF;
    IF EXISTS (SELECT 1 FROM diary_generations g JOIN diaries d ON d.id = g.diary_id
               WHERE d.user_id IN ({ids}) AND NOT EXISTS (
                   SELECT 1 FROM cleanup_generations e WHERE e.id = g.id AND e."userId" = d.user_id
                   AND e."imageKey" IS NOT DISTINCT FROM g.image_object_key)) THEN
        RAISE EXCEPTION 'Generation changed after snapshot. Rerun cleanup';
    END IF;
    IF EXISTS (SELECT 1 FROM cleanup_generations e JOIN diary_generations g ON g.id = e.id
               JOIN diaries d ON d.id = g.diary_id WHERE d.user_id <> e."userId") THEN
        RAISE EXCEPTION 'Generation belongs to another member';
    END IF;
END $$;
DELETE FROM users WHERE id IN ({ids}) AND primary_email LIKE {email};
DELETE FROM categories WHERE name = {quote(fixture['categoryName'])}
    AND NOT EXISTS (SELECT 1 FROM feeds WHERE category_id = categories.id);
COMMIT;
"""


def signature(fixture, plan):
    payload = json.dumps({'runId': plan['runId'], 'generations': plan['generations']}, sort_keys=True).encode()
    return hmac.new(bytes.fromhex(fixture['cleanupKey']), payload, hashlib.sha256).hexdigest()


def save_plan(path, plan, fixture):
    plan['signature'] = signature(fixture, plan)
    temporary = path.with_suffix('.tmp')
    with temporary.open('w') as output:
        json.dump(plan, output, indent=2)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)
    descriptor = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def cleanup(directory, execute_sql=None, client_stopped=False):
    execute_sql = execute_sql or psql
    with run_lock(directory):
        if (directory / '.exported').exists() and not client_stopped:
            raise ValueError('Members were exported. Stop local k6 first, then use --client-stopped')
        fixture = load_fixture(directory)
        plan_path = directory / 'cleanup.json'
        plan = {'runId': fixture['runId'], 'generations': seed_records(fixture)}
        if plan_path.exists():
            plan = json.loads(plan_path.read_text())
            if (plan['runId'] != fixture['runId']
                    or not hmac.compare_digest(plan.get('signature', ''), signature(fixture, plan))):
                raise ValueError('Cleanup plan was changed or belongs to another run')
        validate_records(fixture, plan['generations'])
        current = json.loads(execute_sql(snapshot_sql(fixture)))
        validate_records(fixture, current)
        records = {record['id']: record for record in plan['generations']}
        for record in current:
            previous = records.get(record['id'])
            if previous and previous['userId'] != record['userId']:
                raise ValueError('Generation owner changed')
            records[record['id']] = record
        plan['generations'] = list(records.values())
        # Persist before DELETE. Retain old entries when the DB is already empty on a retry.
        save_plan(plan_path, plan, fixture)
        execute_sql(cleanup_sql(fixture, plan['generations']))
        print('DB cleanup committed: test members, diaries, feeds and reactions removed.', flush=True)
        delete_images(fixture, plan['generations'])


def main():
    parser = argparse.ArgumentParser(description='Clean one test run, DB first and S3 second')
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--client-stopped', action='store_true', help='Confirm local k6 has stopped')
    args = parser.parse_args()
    try:
        cleanup(args.run_dir, client_stopped=args.client_stopped)
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
