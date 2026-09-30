"""Offline tests: python3 -m unittest discover -s deploy/monitoring -p 'test_*.py'."""

import contextlib
import io
import json
import os
import sys
import unittest
from unittest import mock

import discord_forwarder as forwarder


TOPIC = "arn:aws:sns:ap-northeast-2:123456789012:harudle-dev-alerts"
SECRET = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:test"
WEBHOOK = "https://discord.com/api/webhooks/123456/secret-token"


def sns_event(name="harudle-dev-s3-put-failure", state="ALARM", reason="s3://private/key"):
    return {
        "Records": [{
            "Sns": {
                "TopicArn": TOPIC,
                "Message": json.dumps({
                    "AlarmName": name,
                    "NewStateValue": state,
                    "NewStateReason": reason,
                    "AlarmDescription": "private user data",
                }),
            },
        }],
    }


class DiscordForwarderTest(unittest.TestCase):
    def setUp(self):
        self.environment = mock.patch.dict(os.environ, {
            "DEPLOY_ENV": "dev",
            "ALARM_TOPIC_ARN": TOPIC,
            "WEBHOOK_SECRET_ARN": SECRET,
            "WEBHOOK_URL": "",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_only_four_allowlisted_fields_leave_the_function(self):
        with mock.patch.object(forwarder, "_webhook_url", return_value=WEBHOOK), \
                mock.patch.object(forwarder, "_post") as post:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = forwarder.handler(sns_event(), None)

        self.assertEqual(result, {"delivered": True})
        self.assertEqual(post.call_args.args[0], WEBHOOK)
        payload = post.call_args.args[1]
        self.assertEqual(payload["allowed_mentions"], {"parse": []})
        self.assertEqual(payload["content"], (
            "환경: dev\n알람: harudle-dev-s3-put-failure\n"
            "상태: ALARM\n원인: 생성 이미지 S3 저장 실패"
        ))
        self.assertNotIn("s3://", payload["content"])
        self.assertNotIn("private user data", payload["content"])
        self.assertNotIn(WEBHOOK, output.getvalue())

    def test_environment_webhook_delivers_without_secret_lookup_or_leaking_url(self):
        with mock.patch.dict(os.environ, {"WEBHOOK_SECRET_ARN": "", "WEBHOOK_URL": WEBHOOK}), \
                mock.patch.object(forwarder, "_webhook_url") as secret_lookup, \
                mock.patch.object(forwarder, "_post") as post:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = forwarder.handler(sns_event(), None)

        secret_lookup.assert_not_called()
        self.assertEqual(result, {"delivered": True})
        self.assertEqual(post.call_args.args[0], WEBHOOK)
        self.assertEqual(post.call_args.args[1]["content"], (
            "환경: dev\n알람: harudle-dev-s3-put-failure\n"
            "상태: ALARM\n원인: 생성 이미지 S3 저장 실패"
        ))
        self.assertEqual(post.call_args.args[1]["allowed_mentions"], {"parse": []})
        self.assertNotIn(WEBHOOK, output.getvalue())
        self.assertNotIn("s3://", post.call_args.args[1]["content"])

    def test_exactly_one_webhook_source_is_required_before_any_delivery(self):
        for secret_arn, webhook_url in ((SECRET, WEBHOOK), ("", "")):
            with self.subTest(secret_present=bool(secret_arn), env_present=bool(webhook_url)), \
                    mock.patch.dict(os.environ, {"WEBHOOK_SECRET_ARN": secret_arn, "WEBHOOK_URL": webhook_url}), \
                    mock.patch.object(forwarder, "_webhook_url") as secret_lookup, \
                    mock.patch.object(forwarder, "_post") as post:
                with self.assertRaises(forwarder.DeliveryError) as raised:
                    forwarder.handler(sns_event(), None)
                self.assertEqual(str(raised.exception), "forwarder configuration invalid")
                secret_lookup.assert_not_called()
                post.assert_not_called()

    def test_environment_webhook_uses_the_same_strict_url_validation(self):
        for unsafe in (
            "https://discord.com.evil.example/api/webhooks/123456/secret-token",
            "http://discord.com/api/webhooks/123456/secret-token",
            "https://user:secret@discord.com/api/webhooks/123456/secret-token",
            "https://discord.com:443/api/webhooks/123456/secret-token",
            "https://discord.com/api/webhooks/not-an-id/secret-token",
            WEBHOOK + "?wait=true",
            WEBHOOK + "#secret-token",
        ):
            with self.subTest(case=unsafe.split('/')[2]), \
                    mock.patch.dict(os.environ, {"WEBHOOK_SECRET_ARN": "", "WEBHOOK_URL": unsafe}), \
                    mock.patch.object(forwarder, "_post") as post:
                with self.assertRaises(forwarder.DeliveryError) as raised:
                    forwarder.handler(sns_event(), None)
                self.assertEqual(str(raised.exception), "webhook URL invalid")
                self.assertNotIn(unsafe, str(raised.exception))
                post.assert_not_called()

    def test_ok_uses_fixed_recovery_reason(self):
        payload = forwarder._alarm_message(sns_event(state="OK")["Records"][0]["Sns"], TOPIC, "dev")
        self.assertIn("원인: 생성 이미지 S3 저장 실패 해소", payload["content"])

    def test_collection_stale_alarm_is_allowlisted(self):
        record = sns_event(name="harudle-dev-telemetry-stale")["Records"][0]["Sns"]
        payload = forwarder._alarm_message(record, TOPIC, "dev")
        self.assertIn("원인: 서버 지표 수집 중단", payload["content"])

    def test_resource_and_error_rate_alarms_are_allowlisted(self):
        for suffix in ("ec2-status-check", "ec2-disk-high", "rds-memory-low", "api-error-rate"):
            record = sns_event(name=f"harudle-dev-{suffix}")["Records"][0]["Sns"]
            payload = forwarder._alarm_message(record, TOPIC, "dev")
            self.assertIn(f"알람: harudle-dev-{suffix}", payload["content"])

    def test_each_gemini_filter_alarm_is_allowlisted(self):
        for suffix in (
            "gemini-storyboard-transient",
            "gemini-storyboard-response",
            "gemini-image-transient",
            "gemini-image-response",
        ):
            record = sns_event(name=f"harudle-dev-{suffix}")["Records"][0]["Sns"]
            payload = forwarder._alarm_message(record, TOPIC, "dev")
            self.assertIn(f"알람: harudle-dev-{suffix}", payload["content"])

        for obsolete_suffix in ("gemini-storyboard-errors", "gemini-image-errors"):
            record = sns_event(name=f"harudle-dev-{obsolete_suffix}")["Records"][0]["Sns"]
            with self.assertRaises(forwarder.DeliveryError):
                forwarder._alarm_message(record, TOPIC, "dev")

    def test_unknown_alarm_or_topic_is_rejected(self):
        for event in (
            sns_event(name="harudle-dev-user-1234"),
            sns_event(name="harudle-prod-s3-put-failure"),
        ):
            with self.assertRaises(forwarder.DeliveryError):
                forwarder.handler(event, None)
        wrong_topic = sns_event()
        wrong_topic["Records"][0]["Sns"]["TopicArn"] = "other-topic"
        with self.assertRaises(forwarder.DeliveryError):
            forwarder.handler(wrong_topic, None)

    def test_transport_error_does_not_expose_webhook(self):
        with mock.patch.object(forwarder.request, "urlopen", side_effect=RuntimeError(WEBHOOK)):
            with self.assertRaises(forwarder.DeliveryError) as raised:
                forwarder._post(WEBHOOK, {"content": "safe"})
        self.assertEqual(str(raised.exception), "discord transport failed")
        self.assertNotIn(WEBHOOK, str(raised.exception))

    def test_post_identifies_client_to_discord(self):
        response = mock.MagicMock()
        response.__enter__.return_value.status = 204
        with mock.patch.object(forwarder.request, "urlopen", return_value=response) as urlopen:
            forwarder._post(WEBHOOK, {"content": "safe"})

        outgoing = urlopen.call_args.args[0]
        self.assertEqual(outgoing.get_header("Content-type"), "application/json")
        self.assertEqual(
            outgoing.get_header("User-agent"),
            "DiscordBot (https://github.com/woowacourse-teams/2026-Harudle, 1.0)",
        )

    def test_secret_must_contain_a_discord_webhook_without_query_parameters(self):
        client = mock.Mock()
        boto3 = mock.Mock()
        boto3.client.return_value = client
        with mock.patch.dict(sys.modules, {"boto3": boto3}):
            client.get_secret_value.return_value = {"SecretString": WEBHOOK}
            self.assertEqual(forwarder._webhook_url(SECRET), WEBHOOK)
            for unsafe in (
                "https://discord.com.evil.example/api/webhooks/123456/secret-token",
                WEBHOOK + "?wait=true",
            ):
                client.get_secret_value.return_value = {"SecretString": unsafe}
                with self.assertRaises(forwarder.DeliveryError) as raised:
                    forwarder._webhook_url(SECRET)
                self.assertEqual(str(raised.exception), "webhook secret unavailable")
                self.assertNotIn(unsafe, str(raised.exception))


if __name__ == "__main__":
    unittest.main()
