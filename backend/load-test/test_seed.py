import contextlib
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

import ec2
import prepare
from fixture_test_support import encrypted_test_rows


class EncryptedSeedTest(unittest.TestCase):
    def setUp(self):
        self.fixture, self.template, _ = prepare.build_fixture('http://localhost:8080', 1)

    def test_sql_contains_only_v1_placeholders_until_real_core_output_is_supplied(self):
        self.assertIn('source_text, source_text_version)', self.template)
        self.assertNotIn('부하 테스트 준비 데이터', self.template)
        self.assertEqual(len(prepare.SEED_PLACEHOLDER.findall(self.template)), 800)
        provider = Mock(side_effect=encrypted_test_rows)
        seed = prepare.protect_seed(self.fixture, self.template, provider)
        self.assertNotRegex(seed, prepare.SEED_PLACEHOLDER)
        self.assertEqual(seed.count("'fixture:"), 400)
        requests = provider.call_args.args[0]
        self.assertEqual({row['diaryId'] for row in requests}, set(prepare.diary_ids(self.fixture)))
        self.assertEqual(set(requests[0]), {'diaryId', 'userId', 'diaryDate', 'idempotencyKey'})
        self.assertIn(f"'{requests[0]['diaryDate']}', 'fixture:", seed)
        self.assertIn(f"'{requests[0]['idempotencyKey']}'", seed)
        self.assertNotIn('부하 테스트 준비 데이터', seed)

    def test_missing_duplicate_unexpected_or_malformed_protected_rows_are_rejected(self):
        original = encrypted_test_rows(prepare.seed_requests(self.fixture))
        invalid = [original[:-1], original + [original[0]]]
        for replacement in [original[1], {**original[0], 'diaryId': str(uuid.uuid4())},
                            {**original[0], 'sourceText': 'plaintext'},
                            {**original[0], 'requestFingerprint': 'not-a-protected-fingerprint'},
                            {**original[0], 'extra': 'unexpected'}]:
            rows = copy.deepcopy(original)
            rows[0] = replacement
            invalid.append(rows)
        for rows in invalid:
            with self.subTest(rows=len(rows)), self.assertRaises(ValueError):
                prepare.protect_seed(self.fixture, self.template, lambda _: rows)

    def test_unresolved_seed_is_rejected_before_a_database_client_can_start(self):
        with patch.object(ec2.subprocess, 'run') as run, self.assertRaises(ValueError):
            ec2.execute_sql('a' * 64, {}, self.template)
        run.assert_not_called()

    def test_encryption_uses_the_running_backend_in_bounded_chunks_without_copying_its_keys(self):
        rows = [{**row, 'diaryId': str(uuid.uuid4())}
                for row in prepare.seed_requests(self.fixture) * 3]

        def run(command, **options):
            chunk = json.loads(options['input'])
            self.assertLessEqual(len(chunk), 1000)
            return subprocess.CompletedProcess(command, 0, json.dumps(encrypted_test_rows(chunk)))

        with patch.object(ec2.subprocess, 'run', side_effect=run) as invoked:
            result = ec2.encrypt_seed('a' * 64, rows)
        self.assertEqual(len(result), 1200)
        self.assertEqual(invoked.call_count, 2)
        command = invoked.call_args.args[0]
        self.assertEqual(command[:4], ['docker', 'exec', '-i', 'a' * 64])
        self.assertEqual(command[4:6], ['sh', '-c'])
        self.assertEqual(command[6], 'exec java ${JAVA_OPTS:-} -Xmx128m -Dloader.main=' + ec2.FIXTURE_MAIN
                         + ' -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher')
        self.assertNotIn('env', invoked.call_args.kwargs)
        self.assertNotIn('shell', invoked.call_args.kwargs)
        self.assertTrue(invoked.call_args.kwargs['capture_output'])

    def test_container_shell_preserves_java_options_before_fixed_heap_and_fixture_main(self):
        response = json.dumps(encrypted_test_rows(prepare.seed_requests(self.fixture)))
        with patch.object(ec2.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, response)) as run:
            ec2.encrypt_seed('a' * 64, prepare.seed_requests(self.fixture))
        command = run.call_args.args[0]
        with tempfile.TemporaryDirectory() as temporary:
            java = Path(temporary) / 'java'
            java.write_text('#!/bin/sh\nprintf \'%s\\n\' "$@"\n')
            java.chmod(0o700)
            options = '-Dfixture.parity=true -Dloader.main=unexpected.Main -Xmx256m'
            result = subprocess.run(command[4:], check=True, capture_output=True, text=True,
                                    env={**os.environ, 'PATH': temporary + os.pathsep + os.environ['PATH'],
                                         'JAVA_OPTS': options})
        arguments = result.stdout.splitlines()
        self.assertEqual(arguments[:3], options.split())
        self.assertEqual(arguments[3:5], ['-Xmx128m', '-Dloader.main=' + ec2.FIXTURE_MAIN])

    def test_invalid_encryption_output_stops_before_seed_or_s3_writes_and_preserves_retry_files(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            group = Path(temporary) / 'members'
            settings = {'PGHOST': 'dev.example', 'PGPORT': '5432', 'PGDATABASE': 'harudle_dev'}
            ec2.prepare_run(group, settings, Mock())
            sql = Mock(side_effect=lambda value: 't' if 'generation_prompts' in value else '')
            with patch.object(ec2, 'upload') as upload, self.assertRaises(ValueError):
                ec2.reset_run(group.parent / 'smoke1', group, settings, sql, True, lambda _: [])
            upload.assert_not_called()
            self.assertFalse((group / 'baseline' / '.ready').exists())
            self.assertTrue((group / 'baseline' / 'members.json').is_file())
            self.assertTrue((group / 'baseline' / 'database.json').is_file())
            self.assertFalse(any('INSERT INTO diaries' in call.args[0] for call in sql.call_args_list))

    def test_invalid_java_output_is_not_returned_as_fixture_data(self):
        for output in ['not-json', '{}', '[]']:
            completed = subprocess.CompletedProcess([], 0, output)
            with self.subTest(output=output), patch.object(ec2.subprocess, 'run', return_value=completed):
                with self.assertRaises(ValueError):
                    ec2.encrypt_seed('a' * 64, prepare.seed_requests(self.fixture))


class BaselinePurgeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.group = self.root / 'members'
        self.baseline = self.group / 'baseline'
        self.settings = {'PGHOST': 'dev.example', 'PGPORT': '5432', 'PGDATABASE': 'harudle_dev'}
        self.addCleanup(patch.stopall)
        patch('sys.stdout', new=io.StringIO()).start()
        patch.object(ec2, 'upload').start()
        ec2.prepare_run(self.group, self.settings, Mock())
        self.accounts = (self.group / 'members.json').read_bytes()
        self.sql = Mock(side_effect=self.result)
        ec2.reset_run(self.root / 'smoke1', self.group, self.settings, self.sql, True, encrypted_test_rows)
        self.sql.reset_mock()

    @staticmethod
    def result(sql):
        if 'generation_prompts' in sql:
            return 't'
        if 'SELECT jsonb_build_object(' in sql:
            return json.dumps({'feeds': [], 'comments': [], 'likes': []})
        if 'jsonb_agg' in sql:
            return '[]'
        return ''

    def mark_run_cleaned(self):
        (self.root / 'smoke1' / '.cleaned').touch()

    def test_only_baseline_is_removed_after_completed_run_and_reusable_members_are_kept(self):
        self.mark_run_cleaned()
        order = []
        self.sql.side_effect = lambda sql: order.append('db')
        with patch.object(ec2, 'delete_images', side_effect=lambda *_: order.append('s3')) as delete:
            ec2.purge_baseline(self.group, self.settings, self.sql, True)
        self.assertEqual(order, ['db', 's3'])
        self.assertNotIn('DELETE FROM users', self.sql.call_args.args[0])
        self.assertIn('Generation is still processing', self.sql.call_args.args[0])
        self.assertIn('Diary does not belong to this test run', self.sql.call_args.args[0])
        self.assertEqual(len(delete.call_args.args[1]), 400)
        self.assertFalse(self.baseline.exists())
        self.assertEqual((self.group / 'members.json').read_bytes(), self.accounts)
        self.assertTrue((self.root / 'smoke1' / '.cleaned').is_file())

    def test_active_or_unfinished_run_and_missing_stop_confirmation_block_every_delete(self):
        with patch.object(ec2, 'delete_images') as delete:
            for stopped in [False, True]:
                with self.subTest(stopped=stopped), self.assertRaises(ValueError):
                    ec2.purge_baseline(self.group, self.settings, self.sql, stopped)
            self.mark_run_cleaned()
            accounts = json.loads(self.accounts)
            prepare.write_fixture(self.root / 'unfinished', accounts=accounts)
            with self.assertRaises(ValueError):
                ec2.purge_baseline(self.group, self.settings, self.sql, True)
        self.sql.assert_not_called()
        delete.assert_not_called()
        self.assertTrue(self.baseline.exists())

    def test_reset_never_silently_removes_a_legacy_baseline(self):
        self.mark_run_cleaned()
        path = self.baseline / 'members.json'
        fixture = json.loads(path.read_text())
        fixture.pop('sourceTextVersion')
        path.write_text(json.dumps(fixture))
        with patch.object(ec2, 'delete_images') as delete, self.assertRaises(ValueError):
            ec2.reset_run(self.root / 'peak1', self.group, self.settings, self.sql, True, encrypted_test_rows)
        delete.assert_not_called()
        self.assertFalse(any('DELETE FROM diaries' in call.args[0] for call in self.sql.call_args_list))
        self.assertTrue(self.baseline.exists())

    def test_baseline_ownership_or_db_target_change_is_rejected_before_delete(self):
        self.mark_run_cleaned()
        fixture_path = self.baseline / 'members.json'
        original = fixture_path.read_bytes()
        fixture = json.loads(original)
        fixture['cleanupKey'] = '0' * 64
        fixture_path.write_text(json.dumps(fixture))
        with patch.object(ec2, 'delete_images') as delete:
            with self.assertRaises(ValueError):
                ec2.purge_baseline(self.group, self.settings, self.sql, True)
            fixture_path.write_bytes(original)
            with self.assertRaises(ValueError):
                ec2.purge_baseline(self.group, {**self.settings, 'PGHOST': 'another-db'}, self.sql, True)
        self.sql.assert_not_called()
        delete.assert_not_called()
        self.assertTrue(self.baseline.exists())

    def test_s3_failure_keeps_signed_plan_and_blocks_reset_until_retry_succeeds(self):
        self.mark_run_cleaned()
        original = (self.baseline / 'members.json').read_bytes()
        with patch.object(ec2, 'delete_images', side_effect=RuntimeError('S3 unavailable')):
            with self.assertRaises(RuntimeError):
                ec2.purge_baseline(self.group, self.settings, self.sql, True)
        self.assertEqual((self.baseline / 'members.json').read_bytes(), original)
        self.assertTrue((self.baseline / 'purge.json').is_file())
        with self.assertRaises(ValueError):
            ec2.reset_run(self.root / 'peak1', self.group, self.settings, self.sql, True, encrypted_test_rows)
        with patch.object(ec2, 'delete_images'):
            ec2.purge_baseline(self.group, self.settings, self.sql, True)
        self.assertFalse(self.baseline.exists())
        self.assertEqual((self.group / 'members.json').read_bytes(), self.accounts)

    def test_database_failure_keeps_plan_and_never_deletes_s3(self):
        self.mark_run_cleaned()
        self.sql.side_effect = subprocess.CalledProcessError(1, 'psql')
        with patch.object(ec2, 'delete_images') as delete, self.assertRaises(subprocess.CalledProcessError):
            ec2.purge_baseline(self.group, self.settings, self.sql, True)
        delete.assert_not_called()
        self.assertTrue((self.baseline / 'purge.json').is_file())
        self.assertTrue((self.baseline / 'members.json').is_file())

    def test_purge_cli_still_requires_running_backend_dev_guard(self):
        with patch('sys.argv', ['ec2.py', 'purge-baseline', 'members', '--client-stopped']):
            with patch.object(ec2, 'backend_settings', side_effect=ValueError('dev guard rejected')) as guard:
                with patch.object(ec2, 'execute_sql') as sql, contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit):
                        ec2.main()
        guard.assert_called_once()
        sql.assert_not_called()


if __name__ == '__main__':
    unittest.main()
