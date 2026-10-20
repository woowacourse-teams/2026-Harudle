"""Fake encryption output for SQL/orchestration tests; cryptography is verified by Java tests."""


def encrypted_test_rows(rows):
    return [{'diaryId': row['diaryId'], 'sourceText': 'fixture:' + 'A' * 40,
             'requestFingerprint': 'a' * 64} for row in rows]
