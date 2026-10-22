import contextlib
import copy
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import assets
import ec2
import prepare


class Ec2PreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / 'smoke1'
        self.values = {
            'DEPLOY_ENV': 'dev', 'DB_HOST': 'dev-rds.example', 'DB_NAME': 'harudle_dev',
            'DB_PORT': '5432', 'DB_USERNAME': 'dev-user', 'DB_PASSWORD': 'secret=with$characters',
            'S3_BUCKET': assets.BUCKET, 'S3_GENERATED_PREFIX': prepare.PREFIX,
        }
        self.settings = {'PGHOST': 'dev-rds.example', 'PGPORT': '5432', 'PGDATABASE': 'harudle_dev',
                         'PGUSER': 'dev-user', 'PGPASSWORD': self.values['DB_PASSWORD'],
                         'PGSSLMODE': 'require', 'PGCONNECT_TIMEOUT': '10'}

    def read_backend(self, values, container='a' * 64):
        responses = [subprocess.CompletedProcess([], 0, container),
                     subprocess.CompletedProcess([], 0, json.dumps([f'{k}={v}' for k, v in values.items()]))]
        with patch.object(ec2.shutil, 'which', return_value='/bin/tool'):
            with patch.object(ec2.subprocess, 'run', side_effect=responses):
                return ec2.backend_settings()

    def test_settings_come_from_the_running_backend_and_preserve_password(self):
        container, settings = self.read_backend(self.values)
        self.assertEqual(container, 'a' * 64)
        self.assertEqual(settings, self.settings)

    def test_prod_wrong_db_and_wrong_s3_are_rejected(self):
        for key, value in [('DEPLOY_ENV', 'prod'), ('DB_NAME', 'harudle'),
                           ('S3_BUCKET', 'other-bucket'), ('S3_GENERATED_PREFIX', prepare.PREFIX.replace('dev', 'prod')),
                           ('DB_HOST', ''), ('DB_PASSWORD', '')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.read_backend({**self.values, key: value})

    def test_missing_or_multiple_backends_are_rejected(self):
        for container in ['', 'a' * 64 + '\n' + 'b' * 64]:
            with self.subTest(container=container), self.assertRaises(ValueError):
                self.read_backend(self.values, container)

    def test_docker_runs_only_psql_and_keeps_password_out_of_arguments(self):
        with patch.object(ec2.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 't\n')) as run:
            self.assertEqual(ec2.execute_sql('a' * 64, self.settings, 'SELECT 1;'), 't')
        command = run.call_args.args[0]
        self.assertIn('container:' + 'a' * 64, command)
        self.assertEqual(command[command.index(ec2.CLIENT_IMAGE) + 1], 'psql')
        self.assertNotIn(self.values['DB_PASSWORD'], ' '.join(command))
        self.assertEqual(run.call_args.kwargs['env']['PGPASSWORD'], self.values['DB_PASSWORD'])
        self.assertEqual(run.call_args.kwargs['input'], 'SELECT 1;')

    def test_prepare_registers_reusable_members_without_uploading_images(self):
        steps = []

        def sql_runner(sql):
            self.assertFalse((self.directory / '.ready').exists())
            if 'INSERT INTO users' in sql:
                steps.append('seed')
                self.assertIn('COMMIT;', sql)
                return ''
            return 't'

        with patch.object(ec2, 'upload') as upload:
            with contextlib.redirect_stdout(io.StringIO()):
                ec2.prepare_run(self.directory, self.settings, sql_runner)
        upload.assert_not_called()
        self.assertEqual(steps, ['seed'])
        self.assertTrue((self.directory / '.ready').exists())
        self.assertEqual(len(assets.load_fixture(self.directory)['members']), 200)
        target = json.loads((self.directory / 'database.json').read_text())
        self.assertEqual(target, ec2.database_target(self.settings))
        self.assertNotIn('PGPASSWORD', target)

    def test_missing_prompt_stops_reset_before_new_files_or_upload(self):
        with contextlib.redirect_stdout(io.StringIO()):
            ec2.prepare_run(self.directory, self.settings, Mock())
        run = self.directory.with_name('peak1')
        with patch.object(ec2, 'upload') as upload, self.assertRaises(ValueError):
            ec2.reset_run(run, self.directory, self.settings, lambda sql: 'f', True)
        self.assertFalse(run.exists())
        upload.assert_not_called()

    def test_failed_seed_keeps_cleanup_files_but_cannot_be_exported(self):
        runner = Mock(side_effect=subprocess.CalledProcessError(3, 'psql'))
        with patch.object(ec2, 'upload'), self.assertRaises(subprocess.CalledProcessError):
            ec2.prepare_run(self.directory, self.settings, runner)
        self.assertFalse((self.directory / '.ready').exists())
        self.assertTrue((self.directory / 'members.json').exists())
        self.assertTrue((self.directory / 'database.json').exists())

    def test_cleanup_rejects_changed_db_before_deleting_anything(self):
        self.directory.mkdir()
        target = ec2.database_target(self.settings)
        (self.directory / 'database.json').write_text(json.dumps(target))
        changed = copy.deepcopy(self.settings)
        changed['PGHOST'] = 'new-dev-rds.example'
        with patch.object(ec2, 'cleanup') as cleanup, self.assertRaises(ValueError):
            ec2.cleanup_run(self.directory, changed, Mock(), True)
        cleanup.assert_not_called()

    def test_deployment_contains_all_runtime_files_but_no_accounts(self):
        root = Path(__file__).resolve().parents[2]
        buildspec = (root / 'buildspec.yml').read_text().split('artifacts:\n', 1)[1].split('  discard-paths:', 1)[0]
        sources = [line.strip()[2:] for line in buildspec.splitlines() if line.strip().startswith('- backend/')]
        destination = Path(self.temporary.name) / 'deployed'
        destination.mkdir()
        for source in sources:
            if '/load-test/' in source or source.endswith('/load-test-diary.png'):
                self.assertNotIn('*', source)
                (destination / Path(source).name).write_bytes((root / source).read_bytes())
        result = subprocess.run(['python3', '-c',
                                 'import ec2, transfer, cleanup_stopped; assert ec2.TEST_IMAGE.is_file(); print(ec2.TEST_IMAGE.name)'],
                                cwd=destination, check=True, capture_output=True, text=True)
        self.assertEqual(result.stdout.strip(), 'load-test-diary.png')
        self.assertFalse((destination / 'runs').exists())


if __name__ == '__main__':
    unittest.main()
