import argparse
from contextlib import ExitStack
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import uuid

from assets import delete_images, load_fixture, member_directory, run_lock, seed_records, validate_active_run, validate_records
from prepare import database_guard, diary_ids, member_identity, quote


def psql(sql):
    # Use PGHOST/PGDATABASE/PGUSER and the normal psql password prompt or .pgpass.
    result = subprocess.run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                            input=sql, check=True, stdout=subprocess.PIPE, text=True)
    return result.stdout.strip()


def member_filter(fixture):
    ids = ', '.join(quote(member['id']) for member in fixture['members'])
    email = quote('%@' + member_identity(fixture).hex + '.loadtest.invalid')
    return ids, email


def snapshot_sql(fixture):
    ids, email = member_filter(fixture)
    scope = ''
    if fixture['version'] == 3:
        seeds = ', '.join(quote(record['id']) for record in seed_records(fixture))
        creates = ', '.join(quote(uuid.uuid5(uuid.UUID(fixture['runId']), f'create-{i}'))
                            for i in range(len(fixture['members'])))
        scope = (f' AND g.idempotency_key IN ({creates})' if 'baselineId' in fixture
                 else f' AND (g.id IN ({seeds}) OR g.idempotency_key IN ({creates}))')
    return database_guard() + f"""
SELECT COALESCE(jsonb_agg(jsonb_build_object(
    'id', g.id, 'userId', u.id, 'imageKey', g.image_object_key)), '[]'::jsonb)
FROM diary_generations g JOIN diaries d ON d.id = g.diary_id JOIN users u ON u.id = d.user_id
WHERE u.id IN ({ids}) AND u.primary_email LIKE {email}{scope};
COMMIT;
"""


def baseline_state_sql(fixture):
    ids, _ = member_filter(fixture)
    return database_guard() + f"""
-- Save only the baseline rows changed by the current load-test scenarios.
WITH baseline_feeds AS (
    SELECT f.* FROM feeds f JOIN diaries d ON d.id = f.diary_id WHERE d.user_id IN ({ids})
)
SELECT jsonb_build_object(
    'feeds', (SELECT COALESCE(jsonb_agg(f), '[]'::jsonb) FROM baseline_feeds f),
    'comments', (SELECT COALESCE(jsonb_agg(c), '[]'::jsonb) FROM comments c
                 WHERE c.feed_id IN (SELECT id FROM baseline_feeds)),
    'likes', (SELECT COALESCE(jsonb_agg(l), '[]'::jsonb) FROM feed_likes l
              WHERE l.feed_id IN (SELECT id FROM baseline_feeds)));
COMMIT;
"""


def restore_baseline_sql(fixture, state):
    ids, _ = member_filter(fixture)
    return f"""
CREATE TEMP TABLE baseline_feeds ON COMMIT DROP AS
SELECT * FROM jsonb_populate_recordset(NULL::feeds, {quote(json.dumps(state['feeds']))}::jsonb);
CREATE TEMP TABLE baseline_comments ON COMMIT DROP AS
SELECT * FROM jsonb_populate_recordset(NULL::comments, {quote(json.dumps(state['comments']))}::jsonb);
CREATE TEMP TABLE baseline_likes ON COMMIT DROP AS
SELECT * FROM jsonb_populate_recordset(NULL::feed_likes, {quote(json.dumps(state['likes']))}::jsonb);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM baseline_feeds b WHERE NOT EXISTS (SELECT 1 FROM feeds f WHERE f.id = b.id)) THEN
        RAISE EXCEPTION 'A baseline feed is missing';
    END IF;
    IF EXISTS (SELECT 1 FROM comments WHERE feed_id IN (SELECT id FROM baseline_feeds)
               AND author_id NOT IN ({ids})) OR
       EXISTS (SELECT 1 FROM feed_likes WHERE feed_id IN (SELECT id FROM baseline_feeds)
               AND user_id NOT IN ({ids})) THEN
        RAISE EXCEPTION 'A non-test member interacted with the baseline';
    END IF;
END $$;
DELETE FROM notifications WHERE feed_id IN (SELECT id FROM baseline_feeds)
    AND actor_user_id IN ({ids}) AND recipient_user_id IN ({ids});
DELETE FROM comments WHERE feed_id IN (SELECT id FROM baseline_feeds)
    AND author_id IN ({ids}) AND id NOT IN (SELECT id FROM baseline_comments);
DELETE FROM feed_likes WHERE feed_id IN (SELECT id FROM baseline_feeds)
    AND user_id IN ({ids}) AND id NOT IN (SELECT id FROM baseline_likes);
INSERT INTO comments SELECT b.* FROM baseline_comments b
    WHERE NOT EXISTS (SELECT 1 FROM comments c WHERE c.id = b.id);
UPDATE comments c SET content = b.content, deleted_at = b.deleted_at FROM baseline_comments b
    WHERE c.id = b.id AND (c.content, c.deleted_at) IS DISTINCT FROM (b.content, b.deleted_at);
INSERT INTO feed_likes SELECT b.* FROM baseline_likes b
    WHERE NOT EXISTS (SELECT 1 FROM feed_likes l WHERE l.id = b.id);
UPDATE feeds f SET like_count = b.like_count, comment_count = b.comment_count, deleted_at = b.deleted_at
    FROM baseline_feeds b WHERE f.id = b.id
    AND (f.like_count, f.comment_count, f.deleted_at) IS DISTINCT FROM (b.like_count, b.comment_count, b.deleted_at);
"""


def cleanup_sql(fixture, records, state=None):
    ids, email = member_filter(fixture)
    expected = quote(json.dumps(seed_records(fixture) + records if state is not None else records))
    delete = f'DELETE FROM users WHERE id IN ({ids}) AND primary_email LIKE {email};'
    diary_guard = ''
    if fixture['version'] == 3:
        diary_guard = f"""
    IF EXISTS (SELECT 1 FROM diaries d WHERE d.user_id IN ({ids}) AND NOT EXISTS (
        SELECT 1 FROM diary_generations g JOIN cleanup_generations e ON e.id = g.id
        WHERE g.diary_id = d.id)) THEN
        RAISE EXCEPTION 'Diary does not belong to this test run';
    END IF;
"""
        keep = ', '.join(quote(value) for value in diary_ids(fixture)) if state is not None else ''
        extra = f' AND id NOT IN ({keep})' if keep else ''
        delete = f"""
DELETE FROM diaries WHERE user_id IN ({ids}){extra};
DELETE FROM daily_generation_usage WHERE user_id IN ({ids});
DELETE FROM refresh_tokens WHERE user_id IN ({ids});
"""
        if state is not None:
            delete += restore_baseline_sql(fixture, state)
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
{diary_guard}
END $$;
{delete}
DELETE FROM categories WHERE name = {quote(fixture['categoryName'])}
    AND NOT EXISTS (SELECT 1 FROM feeds WHERE category_id = categories.id);
COMMIT;
"""


def signature(fixture, plan):
    payload = json.dumps({key: value for key, value in plan.items() if key != 'signature'}, sort_keys=True).encode()
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


def load_baseline_state(directory, fixture):
    baseline = member_directory(directory, fixture) / 'baseline'
    plan = json.loads((baseline / 'state.json').read_text())
    if (not (baseline / '.ready').exists() or plan.get('baselineId') != fixture['baselineId']
            or plan['generations'] != seed_records(fixture)
            or not hmac.compare_digest(plan.get('signature', ''), signature(fixture, plan))):
        raise ValueError('The baseline state changed or belongs to another test member set')
    return plan['state']


def cleanup(directory, execute_sql=None, client_stopped=False):
    with ExitStack() as locks:
        fixture = load_fixture(directory)
        if fixture['version'] == 3:
            locks.enter_context(run_lock(member_directory(directory, fixture)))
        locks.enter_context(run_lock(directory))
        cleanup_locked(directory, execute_sql or psql, client_stopped)


def cleanup_locked(directory, execute_sql, client_stopped):
    # Caller holds both the member-set lock (v3) and this run's lock.
    fixture = load_fixture(directory)
    if fixture['version'] == 3:
        validate_active_run(directory, fixture)
        if not client_stopped:
            raise ValueError('Stop local k6 first, then use --client-stopped')
    if not (directory / '.cleaned').exists():
        if (directory / '.exported').exists() and not client_stopped:
            raise ValueError('Members were exported. Stop local k6 first, then use --client-stopped')
        state = load_baseline_state(directory, fixture) if 'baselineId' in fixture else None
        plan_path = directory / 'cleanup.json'
        plan = {'runId': fixture['runId'], 'generations': [] if state is not None else seed_records(fixture)}
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
        if state is not None and set(records) & {record['id'] for record in seed_records(fixture)}:
            raise ValueError('Baseline images cannot be deleted by test cleanup')
        # Persist before DELETE. Retain old entries when the DB is already empty on a retry.
        save_plan(plan_path, plan, fixture)
        execute_sql(cleanup_sql(fixture, plan['generations'], state))
        print('DB cleanup committed: test data removed.'
              + (' Baseline kept and counters restored.' if state is not None
                 else ' Reusable members kept.' if fixture['version'] == 3 else ' Test members removed.'), flush=True)
        if plan['generations']:
            delete_images(fixture, plan['generations'])
        # Only mark completion after all S3 deletions have succeeded.
        with (directory / '.cleaned').open('w') as marker:
            marker.flush()
            os.fsync(marker.fileno())


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
