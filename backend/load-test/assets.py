import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import date, timedelta
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

from prepare import MEMBERS, NAME, PREFIX, data_identity, member_identity, seed_image

BUCKET = "techcourse-project-2026"
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
IMAGE = re.compile(rf"{PREFIX}/({UUID})/{UUID}/image(?:-(?:960|240)\.webp|\.(?:webp|png|jpg))")
TEST_IMAGE = Path(__file__).with_name('load-test-diary.png')
if not TEST_IMAGE.is_file():
    TEST_IMAGE = Path(__file__).resolve().parents[1] / "src/main/resources/load-test-diary.png"


def aws(*args):
    result = subprocess.run(["aws", "s3api", *args, "--bucket", BUCKET, "--output", "json", "--no-cli-pager"],
                            check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return json.loads(result.stdout or '{}')


@contextmanager
def run_lock(directory):
    os.umask(0o077)
    with (directory / '.assets.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another upload or cleanup is using this run') from None
        yield


def load_fixture(directory):
    fixture = json.loads((directory / 'members.json').read_text())
    run_id = uuid.UUID(fixture['runId'])
    member_set = member_identity(fixture)
    expected = [str(uuid.uuid5(member_set, f'member-{i}')) for i in range(MEMBERS)]
    current = date.fromisoformat(fixture['currentMonth'])
    previous = (current - timedelta(days=1)).replace(day=1)
    if (fixture.get('version') not in {2, 3} or str(run_id) != fixture['runId']
            or not re.fullmatch(r'[0-9a-f]{64}', fixture.get('cleanupKey', ''))
            or [member['id'] for member in fixture['members']] != expected
            or fixture['categoryName'] != '부하' + data_identity(fixture).hex[:12]
            or current.day != 1 or fixture['previousMonth'] != str(previous)
            or type(fixture['diariesPerMonth']) is not int or not 1 <= fixture['diariesPerMonth'] <= 100):
        raise ValueError('Invalid run fixture; prepare a fresh run with the current prepare.py')
    if fixture['version'] == 3:
        if not re.fullmatch(NAME, fixture.get('memberSetName', '')) or str(member_set) != fixture['memberSetId']:
            raise ValueError('Invalid reusable member set')
        if 'runName' in fixture:
            name = fixture['runName']
            if not re.fullmatch(NAME, name) or run_id != uuid.uuid5(member_set, 'run-' + name):
                raise ValueError('Invalid test run identity')
        elif run_id != member_set:
            raise ValueError('Invalid reusable member identity')
    elif 'memberSetId' in fixture:
        raise ValueError('Legacy fixtures cannot reuse members')
    if 'baselineId' in fixture and (fixture['version'] != 3 or 'runName' not in fixture
            or data_identity(fixture) != uuid.uuid5(member_set, f'baseline-{current}')):
        raise ValueError('Invalid baseline identity')
    return fixture


def member_directory(directory, fixture):
    if fixture['version'] != 3 or fixture.get('runName') != directory.name:
        raise ValueError('Select a test run, not the reusable members folder')
    return directory.parent / fixture['memberSetName']


def validate_active_run(directory, fixture):
    group = member_directory(directory, fixture)
    accounts = load_fixture(group)
    if ('runName' in accounts or accounts['memberSetId'] != fixture['memberSetId']
            or accounts['cleanupKey'] != fixture['cleanupKey']
            or (group / 'active-run').read_text() != directory.name):
        raise ValueError('This is not the active test run for these members')
    return group


def seed_records(fixture):
    records = []
    for i, member in enumerate(fixture['members']):
        for month in [fixture['currentMonth'], fixture['previousMonth']]:
            for index in range(fixture['diariesPerMonth']):
                generation_id, key = seed_image(data_identity(fixture), i, month, index)
                records.append({'id': generation_id, 'userId': member['id'], 'imageKey': key})
    return records


def validate_records(fixture, records):
    member_ids = {member['id'] for member in fixture['members']}
    seeds = {record['id']: record for record in seed_records(fixture)}
    seen = set()
    for record in records:
        generation_id = record['id']
        if (not re.fullmatch(UUID, generation_id) or record['userId'] not in member_ids
                or generation_id in seen):
            raise ValueError('Image ownership does not match this run')
        key = record['imageKey']
        match = IMAGE.fullmatch(key) if isinstance(key, str) else None
        if key is not None and (match is None or match.group(1) != generation_id):
            raise ValueError('Image key does not belong to its test generation')
        if generation_id in seeds and record != seeds[generation_id]:
            raise ValueError('Seed image does not match this run')
        seen.add(generation_id)


def upload(fixture):
    def put(record):
        aws('put-object', '--key', record['imageKey'], '--body', str(TEST_IMAGE),
            '--content-type', 'image/png', '--if-none-match', '*',
            '--metadata', json.dumps({'load-test-run': str(data_identity(fixture))}))
    records = seed_records(fixture)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(put, records))
    print(f"Uploaded {len(records)} test images. Existing objects were not overwritten.")


def delete_images(fixture, records):
    validate_records(fixture, records)
    seeds = {record['id']: record for record in seed_records(fixture)}

    def delete(record):
        prefix = f"{PREFIX}/{record['id']}/"
        objects = aws('list-objects-v2', '--prefix', prefix).get('Contents', [])
        keys = [item['Key'] for item in objects]
        # Validate the complete response before deleting any object in this generation.
        for key in keys:
            match = IMAGE.fullmatch(key)
            if match is None or match.group(1) != record['id']:
                raise ValueError('Unexpected object in a test generation; cleanup stopped')
            if record['id'] in seeds:
                if key != seeds[record['id']]['imageKey']:
                    raise ValueError('Unexpected object in a seeded image folder')
                metadata = aws('head-object', '--key', key).get('Metadata', {})
                if metadata.get('load-test-run') != str(data_identity(fixture)):
                    raise ValueError('S3 object was not uploaded by this test run')
        for start in range(0, len(keys), 1000):
            result = aws('delete-objects', '--delete', json.dumps({
                'Objects': [{'Key': key} for key in keys[start:start + 1000]], 'Quiet': True}))
            if result.get('Errors'):
                raise RuntimeError('Some S3 objects could not be deleted; rerun cleanup.py')

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(delete, records))
    print(f"Cleaned images for {len(records)} test generations.")


def main():
    parser = argparse.ArgumentParser(description='Upload fixed images for one prepared run')
    parser.add_argument('action', choices=['upload'])
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    try:
        with run_lock(args.run_dir):
            fixture = load_fixture(args.run_dir)
            if fixture['version'] == 3:
                raise ValueError('Use ec2.py reset to prepare data for reusable members')
            if (args.run_dir / 'cleanup.json').exists():
                raise ValueError('Cleanup has started; prepare a fresh run')
            upload(fixture)
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
