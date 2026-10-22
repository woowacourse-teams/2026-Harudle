import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import uuid
from datetime import datetime, timedelta
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

PREFIX = "harudle/generated/diary-images/dev"
MEMBERS = 200
NAME = r'[a-zA-Z0-9_-]+'


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


def member_identity(fixture):
    return uuid.UUID(fixture.get('memberSetId', fixture['runId']))


def data_identity(fixture):
    return uuid.UUID(fixture.get('baselineId', fixture['runId']))


def diary_ids(fixture):
    return [str(uuid.uuid5(data_identity(fixture), f'diary-{i}-{month}-{j}'))
            for i in range(MEMBERS) for month in [fixture['currentMonth'], fixture['previousMonth']]
            for j in range(fixture['diariesPerMonth'])]


def account_statements(fixture):
    member_set = member_identity(fixture)
    statements = []
    for i, member in enumerate(fixture['members']):
        statements += [
            "INSERT INTO users (id, primary_email, name, nickname, profile_image_code, role) VALUES "
            f"({quote(member['id'])}, '{i}@{member_set.hex}.loadtest.invalid', "
            f"'부하테스트{i:03}', '{member_set.hex[:6]}{i:03}', 1, 'USER');",
            "INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES "
            f"({quote(member['id'])}, 'KAKAO', 'load-test-{member_set.hex}-{i}');",
        ]
    return statements


def reset_guard(accounts):
    ids = ', '.join(quote(member['id']) for member in accounts['members'])
    email = quote('%@' + member_identity(accounts).hex + '.loadtest.invalid')
    allowed = ', '.join(quote(value) for value in diary_ids(accounts)) if 'baselineId' in accounts else ''
    extra = f' AND id NOT IN ({allowed})' if allowed else ''
    baseline = f"""
    IF (SELECT COUNT(*) FROM diaries WHERE id IN ({allowed}) AND user_id IN ({ids})
        AND deleted_at IS NULL) <> {len(diary_ids(accounts))} THEN
        RAISE EXCEPTION 'The baseline diaries changed or are missing';
    END IF;
""" if allowed else ''
    return f"""
DO $$
BEGIN
    PERFORM 1 FROM users WHERE id IN ({ids}) FOR UPDATE;
    IF (SELECT COUNT(*) FROM users WHERE id IN ({ids}) AND primary_email LIKE {email}
        AND deleted_at IS NULL AND role = 'USER') <> {MEMBERS} THEN
        RAISE EXCEPTION 'The reusable test members changed';
    END IF;
    IF EXISTS (SELECT 1 FROM diaries WHERE user_id IN ({ids}){extra}) THEN
        RAISE EXCEPTION 'Clean the previous test before reset';
    END IF;
{baseline}
END $$;
DELETE FROM refresh_tokens WHERE user_id IN ({ids});
DELETE FROM daily_generation_usage WHERE user_id IN ({ids});
"""


def login_sql(fixture):
    statements = [database_guard(), reset_guard(fixture)]
    for member in fixture['members']:
        token = hashlib.sha256(member['refreshToken'].encode()).hexdigest()
        statements.append("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES "
                          f"({quote(member['id'])}, '{token}', CURRENT_TIMESTAMP + INTERVAL '1 day');")
    return '\n'.join(statements) + '\nCOMMIT;'


def build_fixture(base_url, per_month, accounts=None, run_name=None):
    if accounts is not None and (not re.fullmatch(NAME, run_name or '') or 'runName' in accounts
                                 or accounts.get('version') != 3):
        raise ValueError('Use reusable members and a new run name')
    run_id = uuid.uuid4() if accounts is None else uuid.uuid5(member_identity(accounts), 'run-' + run_name)
    today = datetime.now(ZoneInfo("Asia/Seoul")).date()
    current = today.replace(day=1)
    previous = (current - timedelta(days=1)).replace(day=1)
    baseline_id = uuid.uuid5(member_identity(accounts), f'baseline-{current}') if accounts is not None else run_id
    category = "부하" + baseline_id.hex[:12]
    members, image_keys, statements = [], [], [database_guard()]
    if accounts is not None:
        statements.append(reset_guard(accounts))
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
        return str(uuid.uuid5(baseline_id, key))

    for i in range(MEMBERS):
        user_id = identifier(f"member-{i}") if accounts is None else accounts['members'][i]['id']
        refresh = secrets.token_urlsafe(48) if accounts is None else accounts['members'][i]['refreshToken']
        statements.append(
            "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES "
            f"({quote(user_id)}, '{hashlib.sha256(refresh.encode()).hexdigest()}', CURRENT_TIMESTAMP + INTERVAL '1 day');")
        member = {"id": user_id, "refreshToken": refresh,
                  "idempotencyKey": str(uuid.uuid5(run_id, f"create-{i}")), "diaryId": None, "feedId": None}
        for month in [current, previous]:
            for j in range(per_month):
                diary_id = identifier(f"diary-{i}-{month}-{j}")
                generation_id, key = seed_image(baseline_id, i, month, j)
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
    if accounts is None:
        statements[1:1] = account_statements(fixture)
    else:
        fixture.update(version=3, memberSetId=accounts['memberSetId'], memberSetName=accounts['memberSetName'],
                       cleanupKey=accounts['cleanupKey'], runName=run_name, baselineId=str(baseline_id))
    return fixture, "\n".join(statements), image_keys


def write_fixture(directory, base_url='https://dev.harudle.com', per_month=1, accounts=None):
    os.umask(0o077)
    directory.mkdir(parents=True, exist_ok=False)
    if accounts is not None:
        base_url, per_month = accounts['baseUrl'], accounts['diariesPerMonth']
    fixture, seed, keys = build_fixture(base_url, per_month, accounts, directory.name)
    (directory / "members.json").write_text(json.dumps(fixture, ensure_ascii=False, indent=2))
    (directory / "seed.sql").write_text(seed)
    return fixture, seed, keys


def write_members(directory):
    fixture, _, _ = write_fixture(directory)
    fixture.update(version=3, memberSetId=fixture['runId'], memberSetName=directory.name)
    (directory / 'members.json').write_text(json.dumps(fixture, ensure_ascii=False, indent=2))
    seed = members_sql(fixture)
    (directory / 'seed.sql').write_text(seed)
    return fixture, seed


def members_sql(fixture):
    ids = ', '.join(quote(member['id']) for member in fixture['members'])
    inserts = '\n'.join(account_statements(fixture))
    # A lost DB response must not require a new member file after a successful commit.
    return database_guard() + f"""
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE id IN ({ids})) THEN
        {inserts}
    END IF;
END $$;
""" + reset_guard(fixture) + '\nCOMMIT;'


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
