import base64
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import assets
import cleanup
import prepare
import transfer


class MemberTransferTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.keys.cleanup)
        cls.recipient = Path(cls.keys.name) / 'recipient'
        cls.other = Path(cls.keys.name) / 'other'
        cls.certificate = transfer.create_key(cls.recipient)
        cls.other_certificate = transfer.create_key(cls.other)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.remote = Path(self.temporary.name) / 'ec2' / 'smoke1'
        self.local = Path(self.temporary.name) / 'local' / 'smoke1'
        self.local.mkdir(parents=True)
        for name in ['private.pem', 'recipient.pem']:
            (self.local / name).write_bytes((self.recipient / name).read_bytes())
        self.fixture, _, _ = prepare.write_fixture(self.remote)
        (self.remote / '.ready').touch()

    def export(self):
        return transfer.export_members(self.remote, self.certificate).encode()

    def test_encrypted_console_copy_restores_exactly_the_same_members(self):
        encrypted = self.export()
        self.assertNotIn(self.fixture['members'][0]['refreshToken'].encode(), encrypted)
        self.assertNotIn(self.fixture['cleanupKey'].encode(), encrypted)
        transfer.receive_members(self.local, encrypted)
        self.assertEqual(assets.load_fixture(self.local), self.fixture)
        self.assertEqual((self.remote / '.exported').read_text(), self.fixture['runId'])
        self.assertEqual((self.local / 'members.json').stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.recipient / 'private.pem').stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.recipient.stat().st_mode & 0o777, 0o700)

    def test_wrong_local_key_does_not_create_a_member_file(self):
        encrypted = self.export()
        (self.local / 'private.pem').write_bytes((self.other / 'private.pem').read_bytes())
        with self.assertRaises(ValueError):
            transfer.receive_members(self.local, encrypted)
        self.assertFalse((self.local / 'members.json').exists())

    def test_truncated_or_tampered_copy_does_not_create_a_member_file(self):
        encrypted = self.export()
        der = bytearray(base64.b64decode(b''.join(encrypted.splitlines()[1:-1])))
        der[-10] ^= 1
        tampered = b'-----BEGIN CMS-----\n' + base64.encodebytes(der) + b'-----END CMS-----\n'
        for data in [encrypted[:len(encrypted) // 2], tampered]:
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                transfer.receive_members(self.local, data)
            self.assertFalse((self.local / 'members.json').exists())

    def test_incomplete_preparation_or_started_cleanup_cannot_export(self):
        (self.remote / '.ready').unlink()
        with self.assertRaises(ValueError):
            self.export()
        (self.remote / '.ready').touch()
        (self.remote / 'cleanup.json').write_text('{}')
        with self.assertRaises(ValueError):
            self.export()
        self.assertFalse((self.remote / '.exported').exists())

    def test_copy_can_be_retried_only_with_the_original_recipient(self):
        self.export()
        transfer.receive_members(self.local, self.export())
        with self.assertRaises(ValueError):
            transfer.export_members(self.remote, self.other_certificate)

    def test_broken_public_certificate_can_be_corrected_without_repreparing_data(self):
        with self.assertRaises(ValueError):
            transfer.export_members(self.remote, '-----BEGIN CERTIFICATE-----\nYQ==\n-----END CERTIFICATE-----')
        self.assertFalse((self.remote / 'recipient.pem').exists())
        self.assertFalse((self.remote / '.exported').exists())
        transfer.receive_members(self.local, self.export())

    def test_command_line_export_and_receive_work_without_local_aws_cli(self):
        source = Path(__file__).resolve().parent
        for machine in [self.local.parent, self.remote.parent]:
            for name in ['ec2.py', 'transfer.py', 'assets.py', 'cleanup.py', 'prepare.py']:
                (machine / name).write_bytes((source / name).read_bytes())
            (machine / 'runs').mkdir()
        remote = self.remote.parent / 'runs' / 'smoke1'
        local = self.local.parent / 'runs' / 'smoke1'
        self.remote.rename(remote)
        self.local.rename(local)
        encrypted = subprocess.run(['python3', str(remote.parents[1] / 'ec2.py'), 'export', 'smoke1'],
                                   input=self.certificate.encode(), check=True, capture_output=True).stdout
        result = subprocess.run(['python3', str(local.parents[1] / 'transfer.py'), 'receive', 'smoke1'],
                                input=encrypted, check=True, capture_output=True)
        self.assertIn(b'Restored members.json', result.stdout)
        self.assertEqual(assets.load_fixture(local), self.fixture)

    def test_received_members_are_never_overwritten(self):
        encrypted = self.export()
        transfer.receive_members(self.local, encrypted)
        original = (self.local / 'members.json').read_bytes()
        with self.assertRaises(ValueError):
            transfer.receive_members(self.local, encrypted)
        self.assertEqual((self.local / 'members.json').read_bytes(), original)

    def test_exported_run_requires_explicit_client_stop_confirmation_before_db_or_s3(self):
        self.export()
        with patch.object(cleanup, 'psql') as db, patch.object(cleanup, 'delete_images') as s3:
            with self.assertRaises(ValueError):
                cleanup.cleanup(self.remote)
            db.assert_not_called()
            s3.assert_not_called()

    def test_confirmed_cleanup_still_deletes_db_before_s3(self):
        self.export()
        steps = []

        def sql_runner(sql):
            steps.append('delete' if 'DELETE FROM users' in sql else 'snapshot')
            return '[]' if steps[-1] == 'snapshot' else ''

        with patch.object(cleanup, 'delete_images', side_effect=lambda *args: steps.append('s3')):
            with contextlib.redirect_stdout(io.StringIO()):
                cleanup.cleanup(self.remote, execute_sql=sql_runner, client_stopped=True)
        self.assertEqual(steps, ['snapshot', 'delete', 's3'])

    def test_export_cannot_race_with_cleanup(self):
        with assets.run_lock(self.remote), self.assertRaises(ValueError):
            self.export()

    def test_invalid_fixture_is_rejected_without_publishing_members(self):
        self.fixture['baseUrl'] = 'https://harudle.com'
        (self.remote / 'members.json').write_text(json.dumps(self.fixture))
        with self.assertRaises(ValueError):
            transfer.receive_members(self.local, self.export())
        self.assertFalse((self.local / 'members.json').exists())


if __name__ == '__main__':
    unittest.main()
