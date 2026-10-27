import contextlib
import copy
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import uuid

import assets
import cleanup
import prepare


class RunSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.fixture, _, self.keys = prepare.build_fixture('http://localhost:8080', 1)
        self.write_fixture()
        self.seeds = assets.seed_records(self.fixture)
        self.generated = {
            'id': str(uuid.uuid4()), 'userId': self.fixture['members'][0]['id'], 'imageKey': None,
        }
        self.generated['imageKey'] = (f"{prepare.PREFIX}/{self.generated['id']}/{uuid.uuid4()}/image-960.webp")

    def write_fixture(self):
        (self.directory / 'members.json').write_text(json.dumps(self.fixture))

    def run_cleanup(self):
        with contextlib.redirect_stdout(io.StringIO()):
            cleanup.cleanup(self.directory)

    def test_seed_destinations_are_derived_from_this_run(self):
        self.assertEqual([record['imageKey'] for record in self.seeds], self.keys)
        self.fixture['members'][0]['id'] = str(uuid.uuid4())
        self.write_fixture()
        with self.assertRaises(ValueError):
            assets.load_fixture(self.directory)

    def test_upload_uses_fixed_image_and_cannot_overwrite_objects(self):
        with patch.object(assets, 'aws', return_value={}) as aws, contextlib.redirect_stdout(io.StringIO()):
            assets.upload(self.fixture)
        self.assertEqual(aws.call_count, 400)
        self.assertTrue(assets.TEST_IMAGE.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'))
        for call in aws.call_args_list:
            args = call.args
            self.assertEqual(args[0], 'put-object')
            self.assertEqual(args[args.index('--if-none-match') + 1], '*')
            self.assertEqual(args[args.index('--body') + 1], str(assets.TEST_IMAGE))
            self.assertIn(self.fixture['runId'], args[args.index('--metadata') + 1])

    def test_foreign_member_prod_key_and_wrong_generation_key_are_rejected_before_s3(self):
        invalid = []
        record = copy.deepcopy(self.generated)
        record['userId'] = str(uuid.uuid4())
        invalid.append(record)
        for key in [self.generated['imageKey'].replace('/dev/', '/prod/'),
                    self.generated['imageKey'].replace(self.generated['id'], str(uuid.uuid4())),
                    self.generated['imageKey'] + '/../image.png']:
            invalid.append({**self.generated, 'imageKey': key})
        for record in invalid:
            with self.subTest(record=record), patch.object(assets, 'aws') as aws:
                with self.assertRaises(ValueError):
                    assets.delete_images(self.fixture, [record])
                aws.assert_not_called()

    def test_seed_metadata_must_match_run_before_deletion(self):
        key = self.seeds[0]['imageKey']
        for metadata in [{}, {'load-test-run': str(uuid.uuid4())}]:
            with self.subTest(metadata=metadata), patch.object(assets, 'aws', side_effect=[
                    {'Contents': [{'Key': key}]}, {'Metadata': metadata}]) as aws:
                with self.assertRaises(ValueError):
                    assets.delete_images(self.fixture, self.seeds[:1])
                self.assertFalse(any(call.args[0] == 'delete-objects' for call in aws.call_args_list))

    def test_deletion_uses_exact_keys_and_keeps_versions(self):
        folder = self.generated['imageKey'].rsplit('/', 1)[0]
        keys = [folder + '/' + filename for filename in ['image.png', 'image-240.webp', 'image-960.webp']]
        with patch.object(assets, 'aws', side_effect=[{'Contents': [{'Key': key} for key in keys]}, {}]) as aws:
            with contextlib.redirect_stdout(io.StringIO()):
                assets.delete_images(self.fixture, [self.generated])
        self.assertEqual(aws.call_args_list[0].args, ('list-objects-v2', '--prefix',
                         f"{prepare.PREFIX}/{self.generated['id']}/"))
        body = json.loads(aws.call_args_list[1].args[2])
        self.assertEqual(body['Objects'], [{'Key': key} for key in keys])
        self.assertNotIn('--recursive', str(aws.call_args_list))

    def test_unexpected_listed_object_stops_that_generation_before_delete(self):
        foreign = self.generated['imageKey'].replace(self.generated['id'], str(uuid.uuid4()))
        with patch.object(assets, 'aws', return_value={'Contents': [{'Key': foreign}]}) as aws:
            with self.assertRaises(ValueError):
                assets.delete_images(self.fixture, [self.generated])
            self.assertEqual(aws.call_count, 1)

    def test_partial_s3_errors_are_not_reported_as_success(self):
        with patch.object(assets, 'aws', side_effect=[
                {'Contents': [{'Key': self.generated['imageKey']}]}, {'Errors': [{'Code': 'AccessDenied'}]}]):
            with self.assertRaises(RuntimeError):
                assets.delete_images(self.fixture, [self.generated])

    def test_known_failed_generation_can_clean_an_image_without_a_recorded_key(self):
        with patch.object(assets, 'aws', side_effect=[
                {'Contents': [{'Key': self.generated['imageKey']}]}, {}]) as aws:
            with contextlib.redirect_stdout(io.StringIO()):
                assets.delete_images(self.fixture, [{**self.generated, 'imageKey': None}])
            self.assertEqual(aws.call_args_list[-1].args[0], 'delete-objects')

    def test_foreign_image_in_db_snapshot_stops_before_db_delete(self):
        foreign = {**self.generated, 'imageKey': self.generated['imageKey'].replace(self.generated['id'], str(uuid.uuid4()))}
        with patch.object(cleanup, 'psql', return_value=json.dumps([foreign])) as db:
            with patch.object(cleanup, 'delete_images') as delete:
                with self.assertRaises(ValueError):
                    self.run_cleanup()
                self.assertEqual(db.call_count, 1)
                delete.assert_not_called()

    def test_aws_bucket_cannot_be_chosen_by_a_manifest(self):
        with patch.object(assets.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '{}')) as run:
            assets.aws('list-objects-v2', '--prefix', prepare.PREFIX + '/')
        command = run.call_args.args[0]
        self.assertEqual(command[command.index('--bucket') + 1], assets.BUCKET)

    def test_failed_db_cleanup_never_calls_s3_and_keeps_plan(self):
        with patch.object(cleanup, 'psql', side_effect=[json.dumps([self.generated]),
                                                      subprocess.CalledProcessError(3, 'psql')]):
            with patch.object(cleanup, 'delete_images') as delete:
                with self.assertRaises(subprocess.CalledProcessError):
                    self.run_cleanup()
                delete.assert_not_called()
        plan = json.loads((self.directory / 'cleanup.json').read_text())
        self.assertIn(self.generated, plan['generations'])
        self.assertEqual(plan['signature'], cleanup.signature(self.fixture, plan))

    def test_snapshot_failure_never_calls_s3(self):
        with patch.object(cleanup, 'psql', side_effect=subprocess.CalledProcessError(3, 'psql')):
            with patch.object(cleanup, 'delete_images') as delete:
                with self.assertRaises(subprocess.CalledProcessError):
                    self.run_cleanup()
                delete.assert_not_called()

    def test_retry_after_db_commit_preserves_generated_images_when_db_is_empty(self):
        with patch.object(cleanup, 'psql', side_effect=[json.dumps([self.generated]), '']):
            with patch.object(cleanup, 'delete_images', side_effect=RuntimeError('S3 unavailable')):
                with self.assertRaises(RuntimeError):
                    self.run_cleanup()
        original = (self.directory / 'cleanup.json').read_bytes()
        with patch.object(cleanup, 'psql', side_effect=['[]', '']) as db:
            with patch.object(cleanup, 'delete_images') as delete:
                self.run_cleanup()
                self.assertEqual(db.call_count, 2)
                self.assertIn(self.generated, delete.call_args.args[1])
        self.assertEqual((self.directory / 'cleanup.json').read_bytes(), original)

    def test_failed_seed_can_still_clean_uploaded_seed_images(self):
        with patch.object(cleanup, 'psql', side_effect=['[]', '']):
            with patch.object(cleanup, 'delete_images') as delete:
                self.run_cleanup()
                self.assertEqual(delete.call_args.args[1], self.seeds)

    def test_edited_or_other_run_plan_is_rejected_before_db_and_s3(self):
        plan = {'runId': self.fixture['runId'], 'generations': self.seeds}
        cleanup.save_plan(self.directory / 'cleanup.json', plan, self.fixture)
        for change in ['image', 'run']:
            bad = copy.deepcopy(plan)
            if change == 'image':
                bad['generations'].append(self.generated)
            else:
                bad['runId'] = str(uuid.uuid4())
            (self.directory / 'cleanup.json').write_text(json.dumps(bad))
            with patch.object(cleanup, 'psql') as db, patch.object(cleanup, 'delete_images') as delete:
                with self.assertRaises(ValueError):
                    self.run_cleanup()
                db.assert_not_called()
                delete.assert_not_called()

    def test_cleanup_cannot_run_while_run_lock_is_held(self):
        with assets.run_lock(self.directory), patch.object(cleanup, 'psql') as db:
            with self.assertRaises(ValueError):
                self.run_cleanup()
            db.assert_not_called()


if __name__ == '__main__':
    unittest.main()
