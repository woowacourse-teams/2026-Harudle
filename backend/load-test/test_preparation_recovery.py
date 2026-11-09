import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

import assets
import cleanup
import ec2
import prepare
from fixture_test_support import encrypted_test_rows


class PreparationRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.group = self.root / 'members'
        self.settings = {'PGHOST': 'dev.example', 'PGPORT': '5432', 'PGDATABASE': 'harudle_dev'}
        self.addCleanup(patch.stopall)
        patch('sys.stdout', new=io.StringIO()).start()
        self.upload = patch.object(ec2, 'upload').start()
        self.purge_images = patch.object(ec2, 'delete_images').start()
        self.cleanup_images = patch.object(cleanup, 'delete_images').start()
        ec2.prepare_run(self.group, self.settings, Mock())
        self.accounts = assets.load_fixture(self.group)

    @staticmethod
    def sql(sql):
        if 'generation_prompts' in sql:
            return 't'
        if "'membersValid'" in sql:
            return json.dumps({'membersValid': True, 'workExists': False})
        if 'SELECT jsonb_build_object(' in sql:
            return json.dumps({'feeds': [], 'comments': [], 'likes': []})
        if 'jsonb_agg' in sql:
            return '[]'
        return ''

    def failed_run(self, name='failed'):
        directory = self.root / name
        with patch.object(ec2, 'set_active_run', side_effect=OSError('public preparation interruption')):
            with self.assertRaises(OSError):
                ec2.reset_run(directory, self.group, self.settings, self.sql, True, encrypted_test_rows)
        self.assertTrue((directory / 'members.json').is_file())
        self.assertTrue((directory / 'database.json').is_file())
        self.assertFalse((directory / '.ready').exists())
        return directory

    def test_failed_a_then_completed_b_can_recover_a_and_purge_without_touching_b_or_baseline(self):
        failed = self.failed_run()
        completed = self.root / 'completed'
        ec2.reset_run(completed, self.group, self.settings, self.sql, True, encrypted_test_rows)
        ec2.cleanup_run(completed, self.settings, self.sql, True)
        baseline = {path.name: path.read_bytes() for path in (self.group / 'baseline').iterdir() if path.is_file()}
        members = (self.group / 'members.json').read_bytes()
        proof = Mock(side_effect=self.sql)
        self.cleanup_images.reset_mock()
        self.purge_images.reset_mock()
        ec2.cleanup_run(failed, self.settings, proof, True)
        self.assertTrue((failed / '.cleaned').is_file())
        self.assertTrue((completed / '.cleaned').is_file())
        self.assertEqual((self.group / 'active-run').read_text(), 'completed')
        self.assertEqual(baseline, {path.name: path.read_bytes() for path in (self.group / 'baseline').iterdir()
                                    if path.is_file()})
        self.assertEqual((self.group / 'members.json').read_bytes(), members)
        self.assertEqual(proof.call_count, 1)
        self.assertNotRegex(proof.call_args.args[0], r'\b(?:INSERT|UPDATE|DELETE)\b')
        self.cleanup_images.assert_not_called()
        self.purge_images.assert_not_called()
        ec2.purge_baseline(self.group, self.settings, self.sql, True)
        self.assertFalse((self.group / 'baseline').exists())
        self.assertEqual((self.group / 'members.json').read_bytes(), members)

    def test_all_statuses_and_deleted_work_block_recovery_with_a_direct_canonical_key_query(self):
        directory = self.failed_run()
        fixture_path = directory / 'members.json'
        fixture = assets.load_fixture(directory)
        fixture['members'][0]['idempotencyKey'] = str(uuid.uuid4())
        fixture_path.write_text(json.dumps(fixture))
        canonical = str(uuid.uuid5(uuid.UUID(fixture['runId']), 'create-0'))
        for state in ('PENDING', 'PROCESSING', 'FAILED', 'SUCCEEDED', 'soft-deleted'):
            proof = Mock(return_value=json.dumps({'membersValid': True, 'workExists': True}))
            with self.subTest(state=state), self.assertRaises(ValueError):
                ec2.cleanup_run(directory, self.settings, proof, True)
            sql = proof.call_args.args[0]
            lookup = sql.split("'workExists',", 1)[1]
            self.assertIn('FROM diary_generations WHERE idempotency_key IN', lookup)
            self.assertIn(canonical, lookup)
            self.assertNotIn(fixture['members'][0]['idempotencyKey'], lookup)
            self.assertNotRegex(lookup, r'\b(?:JOIN|status|deleted_at|user_id)\b')
            self.assertFalse((directory / '.cleaned').exists())
        self.cleanup_images.assert_not_called()

    def test_active_preparation_and_existing_markers_are_never_metadata_recovery_candidates(self):
        directory = self.failed_run()
        fixture = assets.load_fixture(directory)
        proof = Mock()
        (self.group / 'active-run').write_text(directory.name)
        with self.assertRaises(ValueError):
            cleanup.recover_preparation(directory, fixture, proof, True, ec2.database_target(self.settings))
        (self.group / 'active-run').unlink()
        for marker in ('.ready', '.exported', '.started', 'cleanup.json'):
            (directory / marker).touch()
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                ec2.cleanup_run(directory, self.settings, proof, True)
            (directory / marker).unlink()
        with self.assertRaises(ValueError):
            ec2.cleanup_run(directory, self.settings, proof, False)
        proof.assert_not_called()
        self.assertFalse((directory / '.cleaned').exists())

    def test_tampered_or_missing_member_and_database_identity_refuses_before_sql(self):
        directory = self.failed_run()
        proof = Mock()
        fixture_path = directory / 'members.json'
        original = fixture_path.read_bytes()
        fixture = json.loads(original)
        fixture['cleanupKey'] = '0' * 64
        fixture_path.write_text(json.dumps(fixture))
        with self.assertRaises(ValueError):
            ec2.cleanup_run(directory, self.settings, proof, True)
        fixture_path.write_bytes(original)
        for owner in (directory, self.group):
            database = owner / 'database.json'
            original_database = database.read_bytes()
            database.write_text(json.dumps({**ec2.database_target(self.settings), 'PGHOST': 'other.example'}))
            with self.assertRaises(ValueError):
                ec2.cleanup_run(directory, self.settings, proof, True)
            database.unlink()
            with self.assertRaises(FileNotFoundError):
                ec2.cleanup_run(directory, self.settings, proof, True)
            database.write_bytes(original_database)
        proof.assert_not_called()
        self.assertFalse((directory / '.cleaned').exists())

    def test_invalid_member_or_proof_response_fails_closed_without_a_cleaned_marker(self):
        directory = self.failed_run()
        for response in ({'membersValid': False, 'workExists': False},
                         {'membersValid': True, 'workExists': 'false'}, {'workExists': False}, []):
            with self.subTest(response=response), self.assertRaises(ValueError):
                ec2.cleanup_run(directory, self.settings, Mock(return_value=json.dumps(response)), True)
            self.assertFalse((directory / '.cleaned').exists())

    def test_run_is_published_only_after_complete_identity_with_the_final_run_name(self):
        directory = self.root / 'publish-failure'
        def interrupted_rename(staging, target):
            self.assertTrue(staging.name.startswith(ec2.PREPARATION_PREFIX))
            self.assertFalse(target.exists())
            fixture = assets.load_fixture(staging)
            self.assertEqual(fixture['runName'], directory.name)
            self.assertEqual(fixture['runId'], str(uuid.uuid5(uuid.UUID(self.accounts['memberSetId']),
                                                           'run-' + directory.name)))
            self.assertEqual(json.loads((staging / 'database.json').read_text()), ec2.database_target(self.settings))
            self.assertTrue((staging / 'seed.sql').is_file())
            raise OSError('public interrupted publication')
        with patch.object(Path, 'rename', interrupted_rename), self.assertRaises(OSError):
            ec2.publish_run_fixture(directory, self.accounts, self.settings)
        self.assertFalse(directory.exists())
        fixture = ec2.publish_run_fixture(directory, self.accounts, self.settings)
        expected, _, _ = prepare.build_fixture(self.accounts['baseUrl'], self.accounts['diariesPerMonth'],
                                                self.accounts, directory.name)
        self.assertEqual(fixture, expected)
        self.assertEqual(assets.load_fixture(directory), expected)
        self.assertFalse(any(path.name.startswith(ec2.PREPARATION_PREFIX) for path in self.root.iterdir()))

    def test_purge_ignores_only_its_unselectable_staging_prefix(self):
        staging = self.root / (ec2.PREPARATION_PREFIX + 'interrupted')
        staging.mkdir()
        (staging / 'members.json').write_text('incomplete public staging JSON')
        ec2.require_cleaned_runs(self.group, self.accounts, self.settings)
        foreign = self.root / '.unrelated-hidden'
        foreign.mkdir()
        (foreign / 'members.json').write_text('unrecognized public JSON')
        with self.assertRaises(ValueError):
            ec2.require_cleaned_runs(self.group, self.accounts, self.settings)


class CommandDiagnosticsTest(unittest.TestCase):
    def assert_cli_failure(self, failure, code, return_code):
        output = io.StringIO()
        with patch('sys.argv', ['ec2.py', 'prepare', 'members']), \
                patch.object(ec2, 'backend_settings', side_effect=failure), contextlib.redirect_stderr(output):
            with self.assertRaises(SystemExit) as result:
                ec2.main()
        self.assertNotEqual(result.exception.code, 0)
        self.assertIn('phase=prepare', output.getvalue())
        self.assertIn('errorCode=' + code, output.getvalue())
        if return_code is not None:
            self.assertIn('returnCode=' + str(return_code), output.getvalue())
        self.assertNotIn('private-sentinel', output.getvalue())

    def test_only_fixed_command_codes_and_numeric_exit_status_are_public(self):
        cases = [(['docker', 'run', '--rm', ec2.CLIENT_IMAGE, 'psql', 'private-sentinel'], 'DB_SQL', 3),
                 (['docker', 'exec', '-i', 'a' * 64, 'sh', '-c', ec2.FIXTURE_MAIN + ' private-sentinel'],
                  'FIXTURE_ENCRYPTION', -9),
                 (['aws', 's3api', 'delete-objects', 'private-sentinel'], 'S3', 47),
                 (['docker', 'inspect', 'private-sentinel'], 'CONTAINER_CONFIG', 1),
                 ('private-sentinel', 'EXTERNAL_COMMAND', 2)]
        for command, code, return_code in cases:
            failure = subprocess.CalledProcessError(return_code, command, output='private-sentinel',
                                                    stderr='private-sentinel')
            with self.subTest(code=code):
                self.assert_cli_failure(failure, code, return_code)

    def test_untrusted_state_exception_text_is_not_printed(self):
        self.assert_cli_failure(ValueError('private-sentinel'), 'INVALID_STATE', None)

    def test_aws_child_stderr_is_captured_before_the_cli_prints_only_the_safe_s3_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            executable = Path(temporary) / 'aws'
            executable.write_text('#!/bin/sh\nprintf "private-sentinel\\n" >&2\nexit 47\n')
            executable.chmod(0o700)
            output = io.StringIO()
            with patch('sys.argv', ['ec2.py', 'prepare', 'members']), \
                    patch.object(ec2, 'backend_settings', return_value=('a' * 64, {})), \
                    patch.object(ec2, 'prepare_run', side_effect=lambda *_: assets.aws('list-objects-v2')), \
                    patch.dict(os.environ, {'PATH': temporary + os.pathsep + os.environ['PATH']}), \
                    patch.object(assets.subprocess, 'run', wraps=subprocess.run) as invoked, \
                    contextlib.redirect_stderr(output):
                with self.assertRaises(SystemExit):
                    ec2.main()
            self.assertEqual(invoked.call_args.kwargs['stderr'], subprocess.PIPE)
            self.assertIn('errorCode=S3', output.getvalue())
            self.assertIn('returnCode=47', output.getvalue())
            self.assertNotIn('private-sentinel', output.getvalue())


if __name__ == '__main__':
    unittest.main()
