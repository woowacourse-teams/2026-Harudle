import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import cleanup_stopped
import ec2


class StoppedBackendSettingsTest(unittest.TestCase):
    def setUp(self):
        self.container = 'a' * 64
        self.state = {'Status': 'exited', 'Running': False, 'Restarting': False, 'Pid': 0}
        self.values = {'DEPLOY_ENV': 'dev', 'DB_NAME': 'harudle_dev', 'DB_HOST': 'dev.example',
                       'DB_USERNAME': 'test', 'DB_PASSWORD': 'private=password',
                       'S3_BUCKET': 'techcourse-project-2026',
                       'S3_GENERATED_PREFIX': 'harudle/generated/diary-images/dev'}

    def test_reads_stopped_container_without_aws_or_running_backend_network(self):
        responses = [subprocess.CompletedProcess([], 0, self.container),
                     subprocess.CompletedProcess([], 0, json.dumps(self.state)),
                     subprocess.CompletedProcess([], 0, json.dumps([f'{k}={v}' for k, v in self.values.items()]))]
        with patch.object(ec2.shutil, 'which', return_value='/bin/docker') as which:
            with patch.object(ec2.subprocess, 'run', side_effect=responses) as run:
                container, settings = ec2.backend_settings(stopped=True)
        which.assert_called_once_with('docker')
        self.assertEqual(container, self.container)
        self.assertEqual(settings['PGPASSWORD'], self.values['DB_PASSWORD'])
        self.assertIn('--all', run.call_args_list[0].args[0])
        self.assertNotIn('--status', run.call_args_list[0].args[0])
        with patch.object(ec2.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '{}')) as run:
            ec2.execute_sql(container, settings, 'SELECT 1;', network='host')
        command = run.call_args.args[0]
        self.assertEqual(command[command.index('--network') + 1], 'host')
        self.assertNotIn(self.values['DB_PASSWORD'], ' '.join(command))
        self.assertEqual(command[command.index(ec2.CLIENT_IMAGE) + 1], 'psql')

    def test_running_restarting_or_unclear_container_state_is_rejected(self):
        for state in ({**self.state, 'Running': True}, {**self.state, 'Status': 'running'},
                      {**self.state, 'Restarting': True}, {**self.state, 'Pid': 12}, {}, None):
            with self.subTest(state=state), patch.object(ec2.subprocess, 'run',
                    return_value=subprocess.CompletedProcess([], 0, json.dumps(state))):
                with self.assertRaises(ValueError):
                    ec2.ensure_backend_stopped(self.container)

    def test_prod_container_is_rejected_even_when_stopped(self):
        responses = [subprocess.CompletedProcess([], 0, self.container),
                     subprocess.CompletedProcess([], 0, json.dumps(self.state)),
                     subprocess.CompletedProcess([], 0, json.dumps(
                         [f'{k}={v}' for k, v in {**self.values, 'DEPLOY_ENV': 'prod'}.items()]))]
        with patch.object(ec2.shutil, 'which', return_value='/bin/docker'):
            with patch.object(ec2.subprocess, 'run', side_effect=responses), self.assertRaises(ValueError):
                ec2.backend_settings(stopped=True)


class StoppedCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.group = self.root / 'members'
        self.directory = self.root / 'peak1'
        self.settings = {'PGHOST': 'dev.example', 'PGPORT': '5432', 'PGDATABASE': 'harudle_dev'}
        self.result = {'removedGenerations': 2, 'removedDiaries': 2, 'updatedUsageRows': 1, 'remainingTestWork': 0}
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)
        with patch.object(ec2, 'upload'):
            ec2.prepare_run(self.group, self.settings, Mock())
            ec2.reset_run(self.directory, self.group, self.settings, self.prepare_sql, True)
        self.stopped = patch.object(cleanup_stopped, 'ensure_backend_stopped').start()
        self.addCleanup(patch.stopall)

    def prepare_sql(self, sql):
        return 't' if 'SELECT EXISTS' in sql else json.dumps({'feeds': [], 'comments': [], 'likes': []})

    def test_validates_scope_and_stopped_state_before_sql_and_preserves_run_files(self):
        before = (self.directory / 'members.json').read_bytes()
        runner = Mock(return_value=json.dumps(self.result))

        self.assertEqual(cleanup_stopped.cleanup_run(self.directory, 'stopped', self.settings, runner, True), self.result)

        self.stopped.assert_called_once_with('stopped')
        runner.assert_called_once()
        self.assertEqual((self.directory / 'members.json').read_bytes(), before)

    def test_missing_client_confirmation_or_changed_database_never_executes_sql(self):
        runner = Mock()
        with self.assertRaises(ValueError):
            cleanup_stopped.cleanup_run(self.directory, 'stopped', self.settings, runner, False)
        with self.assertRaises(ValueError):
            cleanup_stopped.cleanup_run(self.directory, 'stopped', {**self.settings, 'PGHOST': 'another'}, runner, True)
        runner.assert_not_called()

    def test_backend_restart_is_rechecked_before_sql(self):
        self.stopped.side_effect = ValueError('backend is running')
        runner = Mock()
        with self.assertRaises(ValueError):
            cleanup_stopped.cleanup_run(self.directory, 'running', self.settings, runner, True)
        runner.assert_not_called()

    def test_inactive_run_or_members_folder_is_rejected(self):
        runner = Mock()
        (self.group / 'active-run').write_text('another-run')
        for directory in (self.directory, self.group):
            with self.subTest(directory=directory), self.assertRaises(ValueError):
                cleanup_stopped.cleanup_run(directory, 'stopped', self.settings, runner, True)
        runner.assert_not_called()

    def test_sql_failure_does_not_report_success(self):
        runner = Mock(side_effect=subprocess.CalledProcessError(3, 'psql'))
        with self.assertRaises(subprocess.CalledProcessError):
            cleanup_stopped.cleanup_run(self.directory, 'stopped', self.settings, runner, True)

    def test_other_unfinished_test_work_requires_another_cleanup_before_restart(self):
        runner = Mock(return_value=json.dumps({**self.result, 'remainingTestWork': 1}))
        with self.assertRaisesRegex(ValueError, 'This run was cleaned'):
            cleanup_stopped.cleanup_run(self.directory, 'stopped', self.settings, runner, True)

    def test_unexpected_sql_result_is_not_reported_as_success(self):
        for result in (None, [], {}, {**self.result, 'removedGenerations': -1},
                       {**self.result, 'remainingTestWork': False}):
            with self.subTest(result=result), self.assertRaisesRegex(ValueError, 'Unexpected cleanup result'):
                cleanup_stopped.cleanup_run(self.directory, 'stopped', self.settings,
                                            Mock(return_value=json.dumps(result)), True)


if __name__ == '__main__':
    unittest.main()
