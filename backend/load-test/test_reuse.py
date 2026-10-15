import contextlib
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import uuid
from zoneinfo import ZoneInfo

import assets
import cleanup
import ec2
import prepare
import transfer


class ReusableMembersTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.group = self.root / 'members'
        self.settings = {'PGHOST': 'dev.example', 'PGPORT': '5432', 'PGDATABASE': 'harudle_dev'}
        self.addCleanup(patch.stopall)
        patch('sys.stdout', new=io.StringIO()).start()
        self.upload = patch.object(ec2, 'upload').start()
        self.delete = patch.object(cleanup, 'delete_images').start()
        ec2.prepare_run(self.group, self.settings, Mock())
        self.original = (self.group / 'members.json').read_bytes()
        self.accounts = assets.load_fixture(self.group)

    def sql(self, sql):
        if 'SELECT EXISTS (SELECT 1 FROM generation_prompts)' in sql:
            return 't'
        if 'jsonb_agg' in sql:
            return '[]'
        return ''

    def reset(self, name):
        directory = self.root / name
        ec2.reset_run(directory, self.group, self.settings, self.sql, True)
        return directory

    def test_same_members_and_file_are_reused_with_new_data_and_idempotency_keys(self):
        first = assets.load_fixture(self.reset('smoke1'))
        second = assets.load_fixture(self.reset('peak1'))
        self.assertEqual(self.original, (self.group / 'members.json').read_bytes())
        for one, two, account in zip(first['members'], second['members'], self.accounts['members']):
            self.assertEqual(one['id'], account['id'])
            self.assertEqual(two['refreshToken'], account['refreshToken'])
            self.assertNotEqual(one['diaryId'], two['diaryId'])
            self.assertNotEqual(one['feedId'], two['feedId'])
            self.assertNotEqual(one['idempotencyKey'], two['idempotencyKey'])
        self.assertTrue(set(row['imageKey'] for row in assets.seed_records(first)).isdisjoint(
            row['imageKey'] for row in assets.seed_records(second)))

    def test_retrying_initial_registration_preserves_the_same_member_file(self):
        (self.group / '.ready').unlink()
        with self.assertRaises(subprocess.CalledProcessError):
            ec2.prepare_run(self.group, self.settings, Mock(side_effect=subprocess.CalledProcessError(1, 'psql')))
        self.assertFalse((self.group / '.ready').exists())
        ec2.prepare_run(self.group, self.settings, Mock())
        db = Mock()
        ec2.prepare_run(self.group, self.settings, db)
        db.assert_not_called()
        self.assertEqual(self.original, (self.group / 'members.json').read_bytes())

    def test_local_and_ec2_derive_identical_runtime_from_one_encrypted_transfer(self):
        local = self.root / 'local' / 'members'
        transfer.create_key(local)
        encrypted = transfer.export_members(self.group, (local / 'recipient.pem').read_text())
        transfer.receive_members(local, encrypted.encode())
        local_accounts = assets.load_fixture(local)
        for name in ['smoke1', 'peak1']:
            remote = assets.load_fixture(self.reset(name))
            client, _, _ = prepare.write_fixture(local.parent / name, accounts=local_accounts)
            self.assertEqual(remote, client)
        self.assertEqual(self.accounts, assets.load_fixture(local))

    def test_reset_refreshes_the_date_and_month_without_replacing_members(self):
        with patch.object(prepare, 'datetime') as clock:
            clock.now.return_value = datetime(2027, 1, 2, tzinfo=ZoneInfo('Asia/Seoul'))
            fixture = assets.load_fixture(self.reset('nextyear'))
        self.assertEqual(fixture['date'], '2027-01-02')
        self.assertEqual(fixture['previousMonth'], '2026-12-01')
        self.assertEqual(self.original, (self.group / 'members.json').read_bytes())

    def test_reset_cleans_db_then_s3_before_upload_and_seed(self):
        self.reset('smoke1')
        steps = []

        def sql(sql):
            if 'DELETE FROM diaries' in sql:
                steps.append('db-cleanup')
                self.assertNotIn('DELETE FROM users', sql)
            if 'INSERT INTO diaries' in sql:
                steps.append('seed')
                self.assertNotIn('INSERT INTO users', sql)
            return self.sql(sql)

        self.delete.side_effect = lambda *args: steps.append('s3-cleanup')
        self.upload.side_effect = lambda *args: steps.append('upload')
        ec2.reset_run(self.root / 'peak1', self.group, self.settings, sql, True)
        self.assertEqual(steps, ['db-cleanup', 's3-cleanup', 'upload', 'seed'])

    def test_db_failure_keeps_plan_and_blocks_s3_and_next_run(self):
        directory = self.reset('smoke1')
        self.upload.reset_mock()

        def sql(sql):
            if 'DELETE FROM diaries' in sql:
                raise subprocess.CalledProcessError(1, 'psql')
            return self.sql(sql)

        with self.assertRaises(subprocess.CalledProcessError):
            ec2.reset_run(self.root / 'peak1', self.group, self.settings, sql, True)
        self.delete.assert_not_called()
        self.upload.assert_not_called()
        self.assertTrue((directory / 'cleanup.json').exists())
        self.assertFalse((self.root / 'peak1').exists())

    def test_s3_failure_blocks_reset_and_retry_preserves_generated_image(self):
        directory = self.reset('smoke1')
        generated = {'id': str(uuid.uuid4()), 'userId': self.accounts['members'][0]['id'], 'imageKey': None}
        self.delete.side_effect = RuntimeError('S3 unavailable')
        with self.assertRaises(RuntimeError):
            cleanup.cleanup(directory, execute_sql=Mock(side_effect=[json.dumps([generated]), '']), client_stopped=True)
        with self.assertRaises(RuntimeError):
            self.reset('peak1')
        self.assertFalse((directory / '.cleaned').exists())
        self.assertFalse((self.root / 'peak1').exists())
        self.delete.side_effect = None
        self.reset('peak1')
        self.assertIn(generated, self.delete.call_args.args[1])
        self.assertTrue((directory / '.cleaned').exists())

    def test_old_run_cleanup_cannot_delete_new_run_data(self):
        old = self.reset('smoke1')
        self.reset('peak1')
        self.delete.reset_mock()
        db = Mock()
        with self.assertRaises(ValueError):
            cleanup.cleanup(old, execute_sql=db, client_stopped=True)
        db.assert_not_called()
        self.delete.assert_not_called()

    def test_pool_cleanup_run_name_reuse_and_missing_stop_confirmation_are_rejected(self):
        first = self.reset('smoke1')
        with self.assertRaises(ValueError):
            cleanup.cleanup(self.group, execute_sql=Mock(), client_stopped=True)
        with self.assertRaises(ValueError):
            self.reset('smoke1')
        with self.assertRaises(ValueError):
            cleanup.cleanup(first, execute_sql=Mock())
        with self.assertRaises(ValueError):
            ec2.reset_run(self.root / 'peak1', self.group, self.settings, Mock(), False)

    def test_member_set_lock_prevents_parallel_reset_and_cleanup(self):
        first = self.reset('smoke1')
        with assets.run_lock(self.group):
            with self.assertRaises(ValueError):
                self.reset('peak1')
            with self.assertRaises(ValueError):
                cleanup.cleanup(first, execute_sql=Mock(), client_stopped=True)

    def test_failed_seed_can_be_cleaned_and_retried_with_new_run_name(self):
        with self.assertRaises(subprocess.CalledProcessError):
            ec2.reset_run(self.root / 'smoke1', self.group, self.settings,
                          Mock(side_effect=['t', '', subprocess.CalledProcessError(1, 'psql')]), True)
        self.assertFalse((self.root / 'smoke1' / '.ready').exists())
        self.reset('smoke2')
        self.assertTrue((self.root / 'smoke1' / '.cleaned').exists())

    def test_local_runner_keeps_members_and_refuses_a_repeated_run(self):
        scripts = self.root / 'client'
        scripts.mkdir()
        source = Path(__file__).parent
        for name in ['run.sh', 'prepare.py', 'assets.py']:
            shutil.copy(source / name, scripts / name)
        group = scripts / 'runs' / 'members'
        group.mkdir(parents=True)
        (group / 'members.json').write_bytes(self.original)
        fake_bin = self.root / 'bin'
        fake_bin.mkdir()
        docker = fake_bin / 'docker'
        docker.write_text('#!/bin/sh\nexit 0\n')
        docker.chmod(0o700)
        env = {**os.environ, 'PATH': str(fake_bin) + os.pathsep + os.environ['PATH']}
        for name in ['smoke1', 'peak1']:
            result = subprocess.run(['bash', str(scripts / 'run.sh'), 'smoke', name], env=env, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            self.assertTrue((scripts / 'runs' / name / '.started').exists())
        duplicate = subprocess.run(['bash', str(scripts / 'run.sh'), 'smoke', 'smoke1'], env=env, capture_output=True)
        self.assertNotEqual(duplicate.returncode, 0)
        self.assertEqual(self.original, (group / 'members.json').read_bytes())


@unittest.skipUnless(os.environ.get('LOAD_TEST_POSTGRES_TEST') == '1',
                     'Set LOAD_TEST_POSTGRES_TEST=1 to verify SQL in an isolated local PostgreSQL')
class ReusableMembersPostgresTest(unittest.TestCase):
    def sql(self, sql):
        return subprocess.run(['docker', 'exec', '-i', self.container, 'psql', '-U', 'postgres',
                               '-d', 'harudle_dev', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                              input=sql, capture_output=True, text=True, check=True).stdout.strip()

    def setUp(self):
        self.container = 'harudle-reuse-test-' + uuid.uuid4().hex[:12]
        subprocess.run(['docker', 'run', '-d', '--rm', '--name', self.container, '--network', 'none',
                        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_DB=harudle_dev',
                        'postgres:18-alpine'], check=True, capture_output=True)
        self.addCleanup(lambda: subprocess.run(['docker', 'rm', '-f', self.container], capture_output=True, check=True))
        for attempt in range(60):
            try:
                self.sql('SELECT 1;')
                break
            except subprocess.CalledProcessError:
                if attempt == 59:
                    raise
                time.sleep(0.25)
        migrations = Path(__file__).resolve().parents[1] / 'src/main/resources/db/migration'
        for path in sorted(migrations.glob('V*__*.sql'), key=lambda path: int(path.name.split('__')[0][1:])):
            self.sql(path.read_text())
        self.sql("INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key) "
                 "VALUES ('test', 'test', 'test');")

    def add_generation(self, fixture, idempotency_key):
        diary, generation = str(uuid.uuid4()), str(uuid.uuid4())
        user = fixture['members'][0]['id']
        key = f'{prepare.PREFIX}/{generation}/{uuid.uuid4()}/image.png'
        self.sql(f"INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES "
                 f"('{diary}', '{user}', CURRENT_DATE, '이번 테스트 생성'); "
                 "INSERT INTO diary_generations (id, diary_id, prompt_id, idempotency_key, request_fingerprint, "
                 "status, storyboard, title, image_object_key, completed_at, usage_date) "
                 f"SELECT '{generation}', '{diary}', prompt_id, '{idempotency_key}', request_fingerprint, "
                 f"status, storyboard, title, '{key}', completed_at, usage_date FROM diary_generations LIMIT 1;")
        return {'id': generation, 'userId': user, 'imageKey': key}

    def test_two_runs_restore_login_and_quota_keep_accounts_and_preserve_unrelated_data(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            group = root / 'members'
            settings = {'PGHOST': 'isolated', 'PGPORT': '5432', 'PGDATABASE': 'harudle_dev'}
            ec2.prepare_run(group, settings, self.sql)
            # Retry an acknowledged-late registration using the same 200 identities.
            self.sql(prepare.members_sql(assets.load_fixture(group)))
            original = (group / 'members.json').read_bytes()
            account = assets.load_fixture(group)['members'][0]
            outsider, diary = str(uuid.uuid4()), str(uuid.uuid4())
            self.sql(f"INSERT INTO users (id, name, nickname, profile_image_code, role) VALUES "
                     f"('{outsider}', '다른회원', '다른회원', 1, 'USER'); "
                     f"INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES "
                     f"('{diary}', '{outsider}', CURRENT_DATE, '보존할 일기'); "
                     f"INSERT INTO feeds (id, diary_id, category_id) VALUES "
                     f"('{uuid.uuid4()}', '{diary}', (SELECT MIN(id) FROM categories));")

            def s3_deleted(fixture, records):
                self.assertEqual(self.sql(f"SELECT COUNT(*) FROM diaries WHERE user_id = '{account['id']}';"), '0')
                self.assertEqual(self.sql('SELECT COUNT(*) FROM users;'), '201')
                self.assertEqual(self.sql('SELECT COUNT(*) FROM feeds;'), '1')
                self.assertTrue(all(record['userId'] != outsider for record in records))

            with patch.object(ec2, 'upload'), patch.object(cleanup, 'delete_images', side_effect=s3_deleted) as delete:
                ec2.reset_run(root / 'smoke1', group, settings, self.sql, True)
                first = assets.load_fixture(root / 'smoke1')
                generated = self.add_generation(first, first['members'][0]['idempotencyKey'])
                self.assertEqual(self.sql('SELECT COUNT(*) FROM feeds;'), '201')
                # Simulate the real refresh-token rotation and an exhausted generation quota.
                self.sql(f"UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = '{account['id']}'; "
                         f"INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES "
                         f"('{account['id']}', '{'a' * 64}', CURRENT_TIMESTAMP + INTERVAL '1 day'); "
                         f"INSERT INTO daily_generation_usage (user_id, usage_date, used_count, limit_count) "
                         f"VALUES ('{account['id']}', CURRENT_DATE, 3, 3);")
                ec2.reset_run(root / 'peak1', group, settings, self.sql, True)
                self.assertEqual(delete.call_count, 1)
                self.assertIn(generated, delete.call_args.args[1])
                self.assertEqual(self.sql('SELECT COUNT(*) FROM users;'), '201')
                self.assertEqual(self.sql('SELECT COUNT(*) FROM oauth_accounts;'), '200')
                self.assertEqual(self.sql('SELECT COUNT(*) FROM refresh_tokens;'), '200')
                self.assertEqual(self.sql('SELECT COUNT(*) FROM daily_generation_usage;'), '0')
                token_hash = hashlib.sha256(account['refreshToken'].encode()).hexdigest()
                self.assertEqual(self.sql(f"SELECT token_hash FROM refresh_tokens WHERE user_id = '{account['id']}' "
                                          "AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP;"), token_hash)
                self.assertEqual(self.sql(f"SELECT COUNT(*) FROM diaries WHERE id = '{first['members'][0]['diaryId']}';"), '0')
                cleanup.cleanup(root / 'peak1', execute_sql=self.sql, client_stopped=True)
                self.assertEqual(self.sql('SELECT COUNT(*) FROM comments;'), '0')
                self.assertEqual(self.sql('SELECT COUNT(*) FROM feed_likes;'), '0')
                self.assertEqual(self.sql('SELECT COUNT(*) FROM refresh_tokens;'), '0')
                self.assertEqual(self.sql(f"SELECT COUNT(*) FROM diaries WHERE id = '{diary}';"), '1')
                self.assertEqual(original, (group / 'members.json').read_bytes())

    def test_processing_or_unknown_run_data_stops_cleanup_before_s3(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            group = root / 'members'
            settings = {'PGHOST': 'isolated', 'PGPORT': '5432', 'PGDATABASE': 'harudle_dev'}
            ec2.prepare_run(group, settings, self.sql)
            with patch.object(ec2, 'upload'), patch.object(cleanup, 'delete_images') as delete:
                directory = root / 'peak1'
                ec2.reset_run(directory, group, settings, self.sql, True)
                fixture = assets.load_fixture(directory)
                unknown = self.add_generation(fixture, str(uuid.uuid4()))
                with self.assertRaises(subprocess.CalledProcessError):
                    cleanup.cleanup(directory, execute_sql=self.sql, client_stopped=True)
                delete.assert_not_called()
                self.assertEqual(self.sql('SELECT COUNT(*) FROM diaries;'), '401')
                current_key = fixture['members'][0]['idempotencyKey']
                self.sql(f"UPDATE diary_generations SET idempotency_key = '{current_key}', status = 'PROCESSING' "
                         f"WHERE id = '{unknown['id']}';")
                with self.assertRaises(subprocess.CalledProcessError):
                    cleanup.cleanup(directory, execute_sql=self.sql, client_stopped=True)
                delete.assert_not_called()
                self.sql(f"UPDATE diary_generations SET status = 'SUCCEEDED' WHERE id = '{unknown['id']}';")
                cleanup.cleanup(directory, execute_sql=self.sql, client_stopped=True)
                self.assertIn(unknown, delete.call_args.args[1])


if __name__ == '__main__':
    unittest.main()
