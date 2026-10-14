import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import uuid
from datetime import datetime, timedelta
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

PREFIX = "harudle/generated/diary-images/dev"
MEMBERS = 200


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def database_guard():
    return r"""\set ON_ERROR_STOP on
DO $$
BEGIN
    IF current_database() <> 'harudle_dev' THEN
        RAISE EXCEPTION 'Load test requires harudle_dev. Connected to: %', current_database();
    END IF;
END $$;
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
"""


def seed_image(run_id, member_index, month, index):
    generation_id = str(uuid.uuid5(run_id, f"generation-{member_index}-{month}-{index}"))
    asset_id = uuid.uuid5(run_id, 'asset-' + generation_id)
    return generation_id, f"{PREFIX}/{generation_id}/{asset_id}/image.png"


def build_fixture(base_url, per_month):
    run_id = uuid.uuid4()
    today = datetime.now(ZoneInfo("Asia/Seoul")).date()
    current = today.replace(day=1)
    previous = (current - timedelta(days=1)).replace(day=1)
    category = "부하" + run_id.hex[:12]
    members, image_keys, statements = [], [], [database_guard()]
    statements += [
        "SELECT id AS prompt_id FROM generation_prompts ORDER BY id DESC LIMIT 1 \\gset",
        "INSERT INTO categories (name, sort_order) VALUES (" + quote(category)
        + ", 999) RETURNING id AS category_id \\gset",
    ]
    storyboard = json.dumps({
        "title": "부하 테스트 일기", "castContinuity": "같은 테스트 회원",
        "panels": [{"panelNumber": n, "caption": f"부하 테스트 {n}",
                    "scene": "테스트 장면", "characters": "테스트 회원", "emotion": "평온", "props": []}
                   for n in range(1, 5)], "scenePlan": None,
    }, ensure_ascii=False)

    def identifier(key):
        return str(uuid.uuid5(run_id, key))

    for i in range(MEMBERS):
        user_id = identifier(f"member-{i}")
        refresh = secrets.token_urlsafe(48)
        email = f"{i}@{run_id.hex}.loadtest.invalid"
        statements.append(
            "INSERT INTO users (id, primary_email, name, nickname, profile_image_code, role) VALUES "
            f"({quote(user_id)}, {quote(email)}, '부하테스트{i:03}', '{run_id.hex[:6]}{i:03}', 1, 'USER');")
        statements.append(
            "INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES "
            f"({quote(user_id)}, 'KAKAO', 'load-test-{run_id.hex}-{i}');")
        statements.append(
            "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES "
            f"({quote(user_id)}, '{hashlib.sha256(refresh.encode()).hexdigest()}', CURRENT_TIMESTAMP + INTERVAL '1 day');")
        member = {"id": user_id, "refreshToken": refresh,
                  "idempotencyKey": identifier(f"create-{i}"), "diaryId": None, "feedId": None}
        for month in [current, previous]:
            for j in range(per_month):
                diary_id = identifier(f"diary-{i}-{month}-{j}")
                generation_id, key = seed_image(run_id, i, month, j)
                image_keys.append(key)
                diary_date = month.replace(day=min(j + 1, today.day if month == current else 28))
                statements.append(
                    "INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES "
                    f"({quote(diary_id)}, {quote(user_id)}, {quote(diary_date)}, '부하 테스트 준비 데이터');")
                statements.append(
                    "INSERT INTO diary_generations (id, diary_id, prompt_id, idempotency_key, request_fingerprint, "
                    "status, storyboard, title, image_object_key, completed_at, usage_date, storyboard_provider, storyboard_model) VALUES "
                    f"({quote(generation_id)}, {quote(diary_id)}, :prompt_id, {quote(identifier('key-' + diary_id))}, "
                    f"'{hashlib.sha256(diary_id.encode()).hexdigest()}', 'SUCCEEDED', {quote(storyboard)}::jsonb, "
                    f"'부하 테스트 일기', {quote(key)}, CURRENT_TIMESTAMP, {quote(diary_date)}, 'stub', 'seed');")
                if month == previous and j == 0:
                    member["diaryId"] = diary_id
                if month == current:
                    feed_id = identifier(f"feed-{i}-{j}")
                    statements.append(
                        "INSERT INTO feeds (id, diary_id, category_id, published_at) VALUES "
                        f"({quote(feed_id)}, {quote(diary_id)}, :category_id, "
                        f"CURRENT_TIMESTAMP - INTERVAL '{i * per_month + j + 1} seconds');")
                    if j == 0:
                        member["feedId"] = feed_id
        members.append(member)

    for i, member in enumerate(members):
        feed = member["feedId"]
        # Real rows and counters agree; reaction actors use a different target.
        for offset in range(1, 3):
            author = members[(i + offset) % MEMBERS]["id"]
            statements.append(
                "INSERT INTO comments (id, feed_id, author_id, content) VALUES "
                f"({quote(identifier(f'comment-{i}-{offset}'))}, {quote(feed)}, {quote(author)}, '준비된 댓글 {offset}');")
        for offset in range(1, i % 6 + 1):
            liker = members[(i + offset) % MEMBERS]["id"]
            statements.append(
                "INSERT INTO feed_likes (id, feed_id, user_id) VALUES "
                f"({quote(identifier(f'like-{i}-{offset}'))}, {quote(feed)}, {quote(liker)});")
        statements.append(f"UPDATE feeds SET comment_count = 2, like_count = {i % 6} WHERE id = {quote(feed)};")
    statements.append("COMMIT;")
    fixture = {"version": 2, "runId": str(run_id), "cleanupKey": secrets.token_hex(32),
               "baseUrl": base_url, "date": str(today),
               "currentMonth": str(current), "previousMonth": str(previous), "categoryName": category,
               "diariesPerMonth": per_month, "members": members}
    return fixture, "\n".join(statements), image_keys


def write_fixture(directory, base_url='https://dev.harudle.com', per_month=1):
    os.umask(0o077)
    directory.mkdir(parents=True, exist_ok=False)
    fixture, seed, keys = build_fixture(base_url, per_month)
    (directory / "members.json").write_text(json.dumps(fixture, ensure_ascii=False, indent=2))
    (directory / "seed.sql").write_text(seed)
    return fixture, seed, keys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="https://dev.harudle.com")
    parser.add_argument("--diaries-per-month", type=int, default=1)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    if url.hostname not in {"dev.harudle.com", "localhost", "127.0.0.1", "host.docker.internal"} or url.path not in {"", "/"} or url.username or url.query or url.fragment:
        parser.error("base-url must be the dev or local origin")
    if url.scheme not in {"http", "https"} or (url.hostname == "dev.harudle.com" and url.scheme != "https"):
        parser.error("dev requires HTTPS")
    if not 1 <= args.diaries_per_month <= 100:
        parser.error("diaries-per-month must be between 1 and 100")
    _, _, keys = write_fixture(args.output, args.base_url.rstrip("/"), args.diaries_per_month)
    print(f"Prepared {MEMBERS} members, {len(keys)} diaries, {MEMBERS * args.diaries_per_month} feeds: {args.output}")
    print("No server was changed. Upload test images, apply seed.sql to the dev DB, then run k6 once.")


if __name__ == "__main__":
    main()
