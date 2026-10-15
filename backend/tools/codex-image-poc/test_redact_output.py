import unittest

from redact_output import redact_output


class RedactOutputTest(unittest.TestCase):
    def test_masks_password_secret_key_and_token_including_url_encoding(self):
        settings = {
            "DB_PASSWORD": "db p@ss/word",
            "KAKAO_CLIENT_SECRET": "client-secret",
            "GOOGLE_API_KEY": "api-key-value",
            "ACCESS_TOKEN": "access-token",
            "token_lowercase": "lowercase-token",
            "DB_HOST": "database.local",
        }
        text = (
            "db p@ss/word db%20p%40ss%2Fword db+p%40ss%2Fword client-secret "
            "api-key-value access-token lowercase-token database.local"
        )
        result = redact_output(text, settings)
        self.assertEqual(result.count("[redacted]"), 7)
        self.assertIn("database.local", result)

    def test_masks_longer_overlapping_values_first(self):
        result = redact_output("shared-secret shared", {"API_KEY": "shared", "CLIENT_SECRET": "shared-secret"})
        self.assertEqual(result, "[redacted] [redacted]")

    def test_replacement_does_not_expose_a_secret_that_contains_the_marker(self):
        result = redact_output("[redacted]-secret", {"API_KEY": "[redacted]", "DB_PASSWORD": "[redacted]-secret"})
        self.assertEqual(result, "[redacted]")

    def test_empty_values_do_not_change_diagnostics(self):
        self.assertEqual(redact_output("safe error", {"PASSWORD": "", "SECRET": None}), "safe error")


if __name__ == "__main__":
    unittest.main()
