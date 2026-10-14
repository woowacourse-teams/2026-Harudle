import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import assets


class AssetSafetyTest(unittest.TestCase):
    folder = assets.PREFIX + "00000000-0000-0000-0000-000000000001/00000000-0000-0000-0000-000000000002/"
    source = assets.PREFIX + "00000000-0000-0000-0000-000000000003/00000000-0000-0000-0000-000000000004/image-960.webp"

    def invoke(self, action, keys, bucket=assets.BUCKET):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "keys.json"
            path.write_text(json.dumps(keys))
            args = ["assets.py", action, "--bucket", bucket, "--keys", str(path)]
            if action == "copy":
                args += ["--source-detail-key", self.source]
            with patch("sys.argv", args), contextlib.redirect_stdout(io.StringIO()):
                assets.main()

    def assert_rejected(self, action, keys, bucket=assets.BUCKET):
        with patch.object(assets, "aws") as aws, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                self.invoke(action, keys, bucket)
            self.assertEqual(error.exception.code, 2)
            aws.assert_not_called()

    def test_other_buckets_are_rejected_before_any_aws_call(self):
        for action in ["copy", "delete"]:
            with self.subTest(action=action):
                self.assert_rejected(action, [self.folder + "image-960.webp"], "another-bucket")

    def test_copy_requires_optimized_detail_and_copies_both_sizes(self):
        self.assert_rejected("copy", [self.folder + "image.webp"])
        with patch.object(assets, "aws") as aws:
            self.invoke("copy", [self.folder + "image-960.webp"])
        self.assertEqual(aws.call_count, 4)
        self.assertEqual([call.args[-1] for call in aws.call_args_list[-2:]],
                         [self.folder + "image-960.webp", self.folder + "image-240.webp"])

    def test_delete_accepts_optimized_images_and_original_formats(self):
        keys = [self.folder + name for name in ["image-960.webp", "image-240.webp", "image.webp", "image.png", "image.jpg"]]
        with patch.object(assets, "aws") as aws:
            self.invoke("delete", keys)
        for call in aws.call_args_list:
            self.assertEqual(call.args, ("s3", "rm", f"s3://{assets.BUCKET}/{self.folder}",
                                         "--recursive", "--only-show-errors"))
        self.assertGreater(aws.call_count, 0)

    def test_one_invalid_key_stops_the_entire_batch(self):
        invalid_keys = [
            self.folder.replace("/dev/", "/prod/") + "image.webp",
            self.folder + "../image.webp", self.folder + "other.webp",
            assets.PREFIX + "image.webp", self.folder, 123,
        ]
        for invalid in invalid_keys:
            with self.subTest(key=invalid):
                self.assert_rejected("delete", [self.folder + "image-960.webp", invalid])

    def test_copy_cannot_overwrite_the_source(self):
        self.assert_rejected("copy", [self.source])


if __name__ == "__main__":
    unittest.main()
