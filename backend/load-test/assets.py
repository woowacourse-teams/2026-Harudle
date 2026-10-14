import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import subprocess

PREFIX = "harudle/generated/diary-images/dev/"
BUCKET = "techcourse-project-2026"
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
DETAIL = re.compile(rf"{PREFIX}{UUID}/{UUID}/image-960\.webp")
IMAGE = re.compile(rf"{PREFIX}{UUID}/{UUID}/image(?:-(?:960|240)\.webp|\.(?:webp|png|jpg))")


def aws(*args):
    subprocess.run(["aws", *args], check=True, stdout=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["copy", "delete"])
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--keys", type=Path, required=True)
    parser.add_argument("--source-detail-key")
    args = parser.parse_args()
    if args.bucket != BUCKET:
        parser.error(f"bucket must be {BUCKET}")
    if args.keys.suffix == '.json':
        keys = json.loads(args.keys.read_text())
    else:
        keys = args.keys.read_text().splitlines()
    if args.action == "copy":
        if not args.source_detail_key or not DETAIL.fullmatch(args.source_detail_key):
            parser.error("source-detail-key must be an existing dev image-960.webp key")
    pattern = DETAIL if args.action == "copy" else IMAGE
    if not isinstance(keys, list) or not keys or not all(isinstance(key, str) and pattern.fullmatch(key) for key in keys):
        parser.error("all keys must be supported images inside exact dev generation/asset folders")
    if args.source_detail_key in keys:
        parser.error("source must not also be a destination")
    if args.action == "copy":
        for filename in ["image-960.webp", "image-240.webp"]:
            source = args.source_detail_key.rsplit('/', 1)[0] + '/' + filename
            aws("s3api", "head-object", "--bucket", args.bucket, "--key", source)

    def process(key):
        if args.action == "copy":
            for filename in ["image-960.webp", "image-240.webp"]:
                source = args.source_detail_key.rsplit('/', 1)[0] + '/' + filename
                destination = key.rsplit('/', 1)[0] + '/' + filename
                aws("s3api", "copy-object", "--bucket", args.bucket,
                    "--copy-source", f"{args.bucket}/{source}", "--key", destination)
        else:
            aws("s3", "rm", f"s3://{args.bucket}/{key.rsplit('/', 1)[0]}/", "--recursive", "--only-show-errors")

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(process, dict.fromkeys(keys)))
    print(f"{args.action}: {len(keys)} dev image folders completed")


if __name__ == "__main__":
    main()
