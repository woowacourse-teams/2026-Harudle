"""Offline tests: python3 -m unittest discover -s deploy/monitoring -p 'test_*.py'."""

import contextlib
import io
import json
import traceback
from email.message import Message
from http.client import HTTPResponse, IncompleteRead
import ssl
import os
import sys
import unittest
from unittest import mock

import discord_forwarder as forwarder


TOPIC = "arn:aws:sns:ap-northeast-2:123456789012:harudle-dev-alerts"
SECRET = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:test"
WEBHOOK = "https://discord.com/api/webhooks/123456/secret-token"


def discord_response(status=200, body=b'{"id":"123456789"}'):
    response = mock.MagicMock()
    response.__enter__.return_value = response
    response.status = status
    response.read.side_effect = lambda size: body[:size]
    return response



def framed_discord_response(body, headers):
    """Exercise real HTTP framing with an in-memory connection."""
    header_bytes = "".join(f"{name}: {value}\r\n" for name, value in headers.items()).encode("ascii")
    socket = mock.Mock()
    socket.makefile.return_value = io.BytesIO(b"HTTP/1.1 200 OK\r\n" + header_bytes + b"\r\n" + body)
    response = HTTPResponse(socket)
    response.begin()
    return response


def http_failure(status, body=b'{}', retry_after=None):
    headers = {} if retry_after is None else {"Retry-After": retry_after}
    return forwarder.error.HTTPError(WEBHOOK, status, "provider error", headers, io.BytesIO(body))


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
        self.now = 0.0
        self.clock = mock.patch.object(forwarder.time, "monotonic", side_effect=lambda: self.now)
        self.clock.start()
        self.addCleanup(self.clock.stop)
        self.sleeper = mock.patch.object(forwarder.time, "sleep", side_effect=self.advance_time)
        self.sleep = self.sleeper.start()
        self.addCleanup(self.sleeper.stop)
        self.environment = mock.patch.dict(os.environ, {
            "DEPLOY_ENV": "dev",
            "ALARM_TOPIC_ARN": TOPIC,
            "WEBHOOK_SECRET_ARN": SECRET,
            "WEBHOOK_URL": "",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def advance_time(self, seconds):
        self.now += seconds

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
        self.assertEqual(set(payload), {"embeds", "allowed_mentions"})
        self.assertEqual(len(payload["embeds"]), 1)
        embed = payload["embeds"][0]
        self.assertEqual(embed["description"], "생성 이미지 S3 저장 실패")
        self.assertEqual(embed["fields"], [
            {"name": "환경", "value": "`dev`", "inline": True},
            {"name": "상태", "value": "`ALARM`", "inline": True},
            {"name": "알람", "value": "`harudle-dev-s3-put-failure`", "inline": False},
        ])
        serialized = json.dumps(payload, ensure_ascii=False)
        self.assertNotIn("s3://", serialized)
        self.assertNotIn("private user data", serialized)
        self.assertNotIn(WEBHOOK, serialized)
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
        self.assertEqual(
            post.call_args.args[1]["embeds"][0]["description"],
            "생성 이미지 S3 저장 실패",
        )
        self.assertEqual(post.call_args.args[1]["allowed_mentions"], {"parse": []})
        self.assertNotIn(WEBHOOK, output.getvalue())
        self.assertNotIn("s3://", json.dumps(post.call_args.args[1]))

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
            WEBHOOK + "?",
            WEBHOOK + "#",
            WEBHOOK + "?#",
            WEBHOOK + "#secret-token",
            " " + WEBHOOK,
            WEBHOOK + "\n",
            WEBHOOK.replace("discord.com", "dis\tcord.com"),
            WEBHOOK + "\x7f",
        ):
            with self.subTest(case=type(unsafe).__name__), \
                    mock.patch.dict(os.environ, {"WEBHOOK_SECRET_ARN": "", "WEBHOOK_URL": unsafe}), \
                    mock.patch.object(forwarder, "_post") as post:
                with self.assertRaises(forwarder.DeliveryError) as raised:
                    forwarder.handler(sns_event(), None)
                self.assertEqual(str(raised.exception), "webhook URL invalid")
                if isinstance(unsafe, str):
                    self.assertNotIn(unsafe, str(raised.exception))
                post.assert_not_called()

    def test_ok_uses_fixed_recovery_reason(self):
        payload = forwarder._alarm_message(sns_event(state="OK")["Records"][0]["Sns"], TOPIC, "dev")
        self.assertEqual(
            payload["embeds"][0]["description"],
            "생성 이미지 S3 저장 실패 (현재 경보 상태: OK)",
        )
        self.assertNotIn("복구", payload["embeds"][0]["title"])
        self.assertNotIn("해결", payload["embeds"][0]["title"])

    def test_embeds_distinguish_alarm_states_and_environments(self):
        presentations = {
            "ALARM": ("🔴 백엔드 경보가 발생했어요", 0xED4245),
            "OK": ("🟢 백엔드 경보가 정상 상태예요", 0x57F287),
            "INSUFFICIENT_DATA": ("🟡 지표 데이터가 부족해요", 0xFEE75C),
        }
        for environment in ("dev", "prod"):
            topic = TOPIC.replace("-dev-", f"-{environment}-")
            for state, (title, color) in presentations.items():
                with self.subTest(environment=environment, state=state):
                    record = sns_event(
                        name=f"harudle-{environment}-s3-put-failure", state=state,
                    )["Records"][0]["Sns"]
                    record["TopicArn"] = topic
                    payload = forwarder._alarm_message(record, topic, environment)
                    embed = payload["embeds"][0]
                    self.assertEqual(embed["title"], title)
                    self.assertEqual(embed["color"], color)
                    fields = {field["name"]: field["value"] for field in embed["fields"]}
                    self.assertEqual(fields["환경"], f"`{environment}`")
                    self.assertEqual(fields["상태"], f"`{state}`")
                    if state == "INSUFFICIENT_DATA":
                        self.assertEqual(embed["description"], "지표 데이터 부족")
                    self.assertEqual(payload["allowed_mentions"], {"parse": []})

    def test_all_allowlisted_embeds_fit_discord_limits_without_raw_details(self):
        for suffix in forwarder._ALARM_REASONS:
            for state in ("ALARM", "OK", "INSUFFICIENT_DATA"):
                with self.subTest(suffix=suffix, state=state):
                    record = sns_event(name=f"harudle-dev-{suffix}", state=state)["Records"][0]["Sns"]
                    payload = forwarder._alarm_message(record, TOPIC, "dev")
                    embed = payload["embeds"][0]
                    self.assertEqual(set(embed), {"title", "description", "color", "fields"})
                    self.assertLessEqual(len(embed["title"]), 256)
                    self.assertLessEqual(len(embed["description"]), 4096)
                    self.assertEqual(len(embed["fields"]), 3)
                    total = len(embed["title"]) + len(embed["description"])
                    for field in embed["fields"]:
                        self.assertLessEqual(len(field["name"]), 256)
                        self.assertLessEqual(len(field["value"]), 1024)
                        total += len(field["name"]) + len(field["value"])
                    self.assertLessEqual(total, 6000)
                    serialized = json.dumps(payload, ensure_ascii=False)
                    self.assertNotIn("s3://private/key", serialized)
                    self.assertNotIn("private user data", serialized)

    def test_embed_payload_is_posted_as_utf8_and_confirmed(self):
        record = sns_event()["Records"][0]["Sns"]
        payload = forwarder._alarm_message(record, TOPIC, "dev")
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", return_value=discord_response()) as client:
            forwarder._post(WEBHOOK, payload)
        outgoing = client.call_args.args[0]
        self.assertEqual(outgoing.full_url, WEBHOOK + "?wait=true")
        self.assertEqual(json.loads(outgoing.data.decode("utf-8")), payload)
        self.assertIn("생성 이미지 S3 저장 실패", outgoing.data.decode("utf-8"))

    def test_collection_stale_alarm_is_allowlisted(self):
        record = sns_event(name="harudle-dev-telemetry-stale")["Records"][0]["Sns"]
        payload = forwarder._alarm_message(record, TOPIC, "dev")
        self.assertEqual(payload["embeds"][0]["description"], "서버 지표 수집 중단")

    def test_resource_and_error_rate_alarms_are_allowlisted(self):
        for suffix in ("ec2-status-check", "ec2-disk-high", "rds-memory-low", "api-error-rate"):
            record = sns_event(name=f"harudle-dev-{suffix}")["Records"][0]["Sns"]
            payload = forwarder._alarm_message(record, TOPIC, "dev")
            self.assertEqual(payload["embeds"][0]["fields"][2]["value"], f"`harudle-dev-{suffix}`")

    def test_each_gemini_filter_alarm_is_allowlisted(self):
        for suffix in (
            "gemini-storyboard-transient",
            "gemini-storyboard-response",
            "gemini-image-transient",
            "gemini-image-response",
        ):
            record = sns_event(name=f"harudle-dev-{suffix}")["Records"][0]["Sns"]
            payload = forwarder._alarm_message(record, TOPIC, "dev")
            self.assertEqual(payload["embeds"][0]["fields"][2]["value"], f"`harudle-dev-{suffix}`")

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

    def test_failure_event_alarms_keep_fixed_four_fields_in_both_environments(self):
        reasons = {
            "s3-delete-failure": "S3 이미지 삭제 실패",
            "image-cleanup-deferred": "이미지 정리를 위한 상태 확인 실패",
            "generation-cleanup-failure": "만료된 생성 작업 정리 실패",
            "gemini-request-failure": "Gemini 인증·요청 구성 또는 요청 거절 오류",
            "image-fallback-unavailable": "S3 이미지 누락·대체 URL 확보 실패",
        }
        for environment in ("dev", "prod"):
            topic = TOPIC.replace("-dev-", f"-{environment}-")
            for suffix, reason in reasons.items():
                for state in ("ALARM", "OK", "INSUFFICIENT_DATA"):
                    with self.subTest(environment=environment, suffix=suffix, state=state), \
                            mock.patch.dict(os.environ, {"DEPLOY_ENV": environment, "ALARM_TOPIC_ARN": topic}), \
                            mock.patch.object(forwarder, "_webhook_url", return_value=WEBHOOK), \
                            mock.patch.object(forwarder, "_post") as post, \
                            contextlib.redirect_stdout(io.StringIO()):
                        name = f"harudle-{environment}-{suffix}"
                        event = sns_event(name=name, state=state)
                        event["Records"][0]["Sns"]["TopicArn"] = topic
                        self.assertEqual(forwarder.handler(event, None), {"delivered": True})
                        payload = post.call_args.args[1]
                        expected_reason = (
                            "지표 데이터 부족" if state == "INSUFFICIENT_DATA"
                            else reason + " (현재 경보 상태: OK)" if state == "OK" else reason
                        )
                        if state == "OK":
                            expected_reason += (
                                "\n현재 평가에서 새 실패 로그가 감지되지 않았어요(데이터 없음 포함).\n"
                                "이전 실패 건이 해결됐다는 뜻은 아니니 별도로 확인해 주세요."
                            )
                        self.assertEqual(set(payload), {"embeds", "allowed_mentions"})
                        self.assertEqual(payload["allowed_mentions"], {"parse": []})
                        self.assertEqual(len(payload["embeds"]), 1)
                        embed = payload["embeds"][0]
                        self.assertEqual(set(embed), {"title", "description", "color", "fields"})
                        self.assertEqual(embed["description"], expected_reason)
                        expected_title, expected_color = {
                            "ALARM": ("🔴 백엔드 경보가 발생했어요", 0xED4245),
                            "OK": ("🟢 현재 경보 상태: OK", 0x57F287),
                            "INSUFFICIENT_DATA": ("🟡 지표 데이터가 부족해요", 0xFEE75C),
                        }[state]
                        self.assertEqual(embed["title"], expected_title)
                        self.assertEqual(embed["color"], expected_color)
                        self.assertEqual(embed["fields"], [
                            {"name": "환경", "value": f"`{environment}`", "inline": True},
                            {"name": "상태", "value": f"`{state}`", "inline": True},
                            {"name": "알람", "value": f"`{name}`", "inline": False},
                        ])
                        serialized = json.dumps(payload, ensure_ascii=False)
                        for private in ("s3://private/key", "private user data", WEBHOOK):
                            self.assertNotIn(private, serialized)

    def test_failure_event_ok_does_not_infer_repair_or_no_data_from_notification_details(self):
        for environment in ("dev", "prod"):
            topic = TOPIC.replace("-dev-", f"-{environment}-")
            for suffix in (
                "s3-delete-failure", "image-cleanup-deferred", "generation-cleanup-failure",
                "gemini-request-failure", "image-fallback-unavailable",
            ):
                payloads = []
                for reason in ("no data points received", "1 datapoint [0]", "s3://private/key @everyone"):
                    with self.subTest(environment=environment, suffix=suffix, reason=reason):
                        record = sns_event(name=f"harudle-{environment}-{suffix}", state="OK", reason=reason)["Records"][0]["Sns"]
                        record["TopicArn"] = topic
                        payload = forwarder._alarm_message(record, topic, environment)
                        serialized = json.dumps(payload, ensure_ascii=False)
                        self.assertNotIn(reason, serialized)
                        self.assertNotIn("최근 5분", serialized)
                        self.assertNotIn("정상 상태", serialized)
                        self.assertIn("데이터 없음 포함", serialized)
                        self.assertIn("별도로 확인", serialized)
                        payloads.append(payload)
                self.assertEqual(payloads[0], payloads[1])
                self.assertEqual(payloads[1], payloads[2])

    def test_failure_event_alarms_reject_wrong_topic_environment_and_unknown_suffix(self):
        suffixes = (
            "s3-delete-failure", "image-cleanup-deferred", "generation-cleanup-failure",
            "gemini-request-failure", "image-fallback-unavailable",
        )
        for environment in ("dev", "prod"):
            topic = TOPIC.replace("-dev-", f"-{environment}-")
            other_environment = "prod" if environment == "dev" else "dev"
            other_topic = TOPIC.replace("-dev-", f"-{other_environment}-")
            for suffix in suffixes:
                for case in ("wrong_topic", "wrong_environment", "unknown_suffix"):
                    with self.subTest(environment=environment, suffix=suffix, case=case), \
                            mock.patch.dict(os.environ, {"DEPLOY_ENV": environment, "ALARM_TOPIC_ARN": topic}), \
                            mock.patch.object(forwarder, "_webhook_url") as lookup, \
                            mock.patch.object(forwarder, "_post") as post, \
                            contextlib.redirect_stdout(io.StringIO()):
                        name_environment = other_environment if case == "wrong_environment" else environment
                        name_suffix = suffix + "-unknown" if case == "unknown_suffix" else suffix
                        event = sns_event(name=f"harudle-{name_environment}-{name_suffix}")
                        event["Records"][0]["Sns"]["TopicArn"] = other_topic if case == "wrong_topic" else topic
                        with self.assertRaises(forwarder.DeliveryError):
                            forwarder.handler(event, None)
                        lookup.assert_not_called()
                        post.assert_not_called()

    def test_transport_error_does_not_expose_webhook(self):
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=RuntimeError(WEBHOOK)):
            with self.assertRaises(forwarder.DeliveryError) as raised:
                forwarder._post(WEBHOOK, {"content": "safe"})
        self.assertEqual(str(raised.exception), "discord transport failed")
        self.assertNotIn(WEBHOOK, str(raised.exception))

    def test_post_identifies_client_to_discord(self):
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", return_value=discord_response()) as urlopen:
            forwarder._post(WEBHOOK, {"content": "safe"})

        outgoing = urlopen.call_args.args[0]
        self.assertEqual(outgoing.full_url, WEBHOOK + "?wait=true")
        self.assertEqual(outgoing.method, "POST")
        self.assertEqual(json.loads(outgoing.data), {"content": "safe"})
        self.assertEqual(urlopen.call_args.kwargs["timeout"], 3)
        self.assertEqual(outgoing.get_header("Content-type"), "application/json")
        self.assertEqual(
            outgoing.get_header("User-agent"),
            "DiscordBot (https://github.com/woowacourse-teams/2026-Harudle, 1.0)",
        )

    def test_unconfirmed_or_oversized_response_is_not_success(self):
        cases = (
            (204, b""), (200, b"{}"), (200, b'{"id":123}'),
            (200, b'{"id":"not-an-id"}'), (200, b'{"id":""}'),
            (200, b'[]'), (200, b'not-json'),
            (200, b"x" * (forwarder._MAX_RESPONSE_BYTES + 1)),
        )
        for status, body in cases:
            with self.subTest(status=status, size=len(body)), \
                    mock.patch.object(forwarder._HTTP_CLIENT, "open", return_value=discord_response(status, body)) as client:
                with self.assertRaises(forwarder.DeliveryError):
                    forwarder._post(WEBHOOK, {"content": "safe"})
                self.assertEqual(client.call_count, 1)
        self.sleep.assert_not_called()

    def test_retry_after_and_transient_error_recover_only_after_confirmation(self):
        responses = [
            http_failure(429, b'{"retry_after":2.5}', retry_after="1"),
            http_failure(503), discord_response(),
        ]
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=responses) as client:
            forwarder._post(WEBHOOK, {"content": "safe"})
        self.assertEqual(client.call_count, 3)
        self.assertEqual(self.sleep.call_args_list, [mock.call(2.5), mock.call(2.0)])

    def test_all_retryable_http_statuses_can_recover(self):
        for status in (429, 500, 502, 503, 504):
            with self.subTest(status=status), \
                    mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=[http_failure(status), discord_response()]) as client:
                forwarder._post(WEBHOOK, {"content": "safe"})
                self.assertEqual(client.call_count, 2)

    def test_permanent_http_error_is_sanitized_and_never_retried(self):
        for status in (400, 401, 403, 404):
            with self.subTest(status=status), \
                    mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=http_failure(status, WEBHOOK.encode())) as client:
                with self.assertRaises(forwarder.DeliveryError) as raised:
                    forwarder._post(WEBHOOK, {"content": "safe"})
                self.assertEqual(str(raised.exception), f"discord returned HTTP {status}")
                self.assertNotIn(WEBHOOK, "".join(traceback.format_exception(raised.exception)))
                self.assertEqual(client.call_count, 1)
        self.sleep.assert_not_called()

    def test_retry_exhaustion_is_an_error_for_lambda_not_a_delivered_result(self):
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=[http_failure(503) for _ in range(3)]) as client:
            with self.assertRaisesRegex(forwarder.DeliveryError, "discord returned HTTP 503"):
                forwarder._post(WEBHOOK, {"content": "safe"})
        self.assertEqual(client.call_count, 3)
        self.assertEqual(self.sleep.call_args_list, [mock.call(1.0), mock.call(2.0)])

    def test_network_failure_retries_and_recovers(self):
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=[forwarder.error.URLError(WEBHOOK), discord_response()]) as client:
            forwarder._post(WEBHOOK, {"content": "safe"})
        self.assertEqual(client.call_count, 2)
        self.sleep.assert_called_once_with(1.0)

    def test_interrupted_http_response_and_tls_connection_are_retried_safely(self):
        interrupted = discord_response()
        interrupted.read.side_effect = IncompleteRead(WEBHOOK.encode(), 42)
        interrupted_rate_limit = http_failure(429)
        interrupted_rate_limit.read = mock.Mock(side_effect=IncompleteRead(WEBHOOK.encode(), 42))
        for first in (interrupted, ssl.SSLError(WEBHOOK), interrupted_rate_limit):
            with self.subTest(failure_type=type(first).__name__), \
                    mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=[first, discord_response()]) as client:
                forwarder._post(WEBHOOK, {"content": "safe"})
                self.assertEqual(client.call_count, 2)

    def test_short_content_length_response_retries_even_when_json_is_valid(self):
        for partial_body in (b'{"id":', b'{"id":"123456789"}'):
            with self.subTest(valid_json=partial_body.endswith(b"}")):
                first = framed_discord_response(partial_body, {"Content-Length": len(partial_body) + 20})
                with mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=[first, discord_response()]) as client:
                    forwarder._post(WEBHOOK, {"content": "safe"})
                self.assertEqual(client.call_count, 2)
        self.assertEqual(self.sleep.call_args_list, [mock.call(1.0)] * 2)

    def test_short_response_retry_exhaustion_is_sanitized(self):
        partial_body = WEBHOOK.encode()
        responses = [framed_discord_response(partial_body, {"Content-Length": len(partial_body) + 20}) for _ in range(3)]
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=responses) as client:
            with self.assertRaises(forwarder.DeliveryError) as raised:
                forwarder._post(WEBHOOK, {"content": "safe"})
        self.assertEqual(str(raised.exception), "discord transport failed")
        self.assertNotIn(WEBHOOK, "".join(traceback.format_exception(raised.exception)))
        self.assertEqual(client.call_count, 3)
        self.assertEqual(self.sleep.call_args_list, [mock.call(1.0), mock.call(2.0)])

    def test_complete_http_framing_confirms_delivery(self):
        body = b'{"id":"123456789"}'
        chunked_body = f"{len(body):X}\r\n".encode() + body + b"\r\n0\r\n\r\n"
        cases = (
            (body, {"Content-Length": len(body)}),
            (body, {}),
            (chunked_body, {"Transfer-Encoding": "chunked", "Content-Length": len(body) + 20}),
        )
        for framed_body, headers in cases:
            with self.subTest(headers=headers):
                response = framed_discord_response(framed_body, headers)
                with mock.patch.object(forwarder._HTTP_CLIENT, "open", return_value=response) as client:
                    forwarder._post(WEBHOOK, {"content": "safe"})
                self.assertEqual(client.call_count, 1)
        self.sleep.assert_not_called()

    def test_http_response_size_limit_is_preserved(self):
        body = b"x" * (forwarder._MAX_RESPONSE_BYTES + 100)
        response = framed_discord_response(body, {"Content-Length": len(body)})
        with mock.patch.object(response, "read", wraps=response.read) as read, \
                mock.patch.object(forwarder._HTTP_CLIENT, "open", return_value=response) as client:
            with self.assertRaisesRegex(forwarder.DeliveryError, "discord response too large"):
                forwarder._post(WEBHOOK, {"content": "safe"})
        read.assert_called_once_with(forwarder._MAX_RESPONSE_BYTES + 1)
        self.assertEqual(client.call_count, 1)
        self.sleep.assert_not_called()

    def test_long_retry_after_is_not_shortened_to_fit_budget(self):
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=http_failure(429, b'{"retry_after":30}', retry_after="2")) as client:
            with self.assertRaisesRegex(forwarder.DeliveryError, "retry exceeds delivery budget"):
                forwarder._post(WEBHOOK, {"content": "safe"})
        self.assertEqual(client.call_count, 1)
        self.sleep.assert_not_called()

    def test_invalid_retry_after_values_cannot_disable_the_bounded_retry(self):
        for header, body in (("NaN", b'{"retry_after":true}'), ("Infinity", b'{"retry_after":-1}'), ("invalid", b'not-json')):
            with self.subTest(header=header), \
                    mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=[http_failure(429, body, header), discord_response()]) as client:
                forwarder._post(WEBHOOK, {"content": "safe"})
                self.assertEqual(client.call_count, 2)
        self.assertEqual(self.sleep.call_args_list, [mock.call(1.0)] * 3)

    def test_lambda_remaining_time_limits_request_and_preserves_one_second(self):
        context = mock.Mock()
        context.get_remaining_time_in_millis.return_value = 1500
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=http_failure(503)) as client:
            with self.assertRaisesRegex(forwarder.DeliveryError, "retry exceeds delivery budget"):
                forwarder._post(WEBHOOK, {"content": "safe"}, context)
        self.assertEqual(client.call_args.kwargs["timeout"], 0.5)
        self.sleep.assert_not_called()
        context.get_remaining_time_in_millis.return_value = 900
        with mock.patch.object(forwarder._HTTP_CLIENT, "open") as client:
            with self.assertRaisesRegex(forwarder.DeliveryError, "delivery budget exhausted"):
                forwarder._post(WEBHOOK, {"content": "safe"}, context)
        client.assert_not_called()

    def test_request_time_and_retry_delay_share_the_same_budget(self):
        def slow_failure(*_args, **kwargs):
            self.advance_time(kwargs["timeout"])
            raise http_failure(503)
        with mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=slow_failure) as client:
            with self.assertRaisesRegex(forwarder.DeliveryError, "discord returned HTTP 503"):
                forwarder._post(WEBHOOK, {"content": "safe"})
        self.assertEqual([c.kwargs["timeout"] for c in client.call_args_list], [3, 3, 1])
        self.assertEqual(self.now, 10)

    def test_redirects_are_rejected_without_following_another_destination(self):
        self.assertTrue(any(isinstance(h, forwarder._NoRedirect) for h in forwarder._HTTP_CLIENT.handlers))
        headers = Message()
        headers["Location"] = "https://example.org/alert"
        outgoing = forwarder.request.Request(WEBHOOK, data=b"safe", method="POST")
        for status in (301, 302, 303, 307, 308):
            with self.subTest(status=status), mock.patch.object(forwarder._HTTP_CLIENT, "open") as follow:
                with self.assertRaises(forwarder.error.HTTPError):
                    forwarder._HTTP_CLIENT.error("http", outgoing, io.BytesIO(), status, "redirect", headers)
                follow.assert_not_called()

    def test_malformed_event_types_are_safe_errors_before_secret_lookup(self):
        events = [None, [], {}, {"Records": {}}, {"Records": []}, {"Records": [None]},
                  {"Records": [{"Sns": None}]}, sns_event(state=[]), sns_event(state={})]
        with mock.patch.object(forwarder, "_webhook_url") as lookup, \
                mock.patch.object(forwarder, "_post") as post, \
                contextlib.redirect_stdout(io.StringIO()):
            for event in events:
                with self.subTest(event_type=type(event).__name__), self.assertRaises(forwarder.DeliveryError):
                    forwarder.handler(event, None)
        lookup.assert_not_called()
        post.assert_not_called()

    def test_failed_delivery_logs_failure_without_claiming_success_or_leaking_input(self):
        output = io.StringIO()
        with mock.patch.object(forwarder, "_webhook_url", return_value=WEBHOOK), \
                mock.patch.object(forwarder._HTTP_CLIENT, "open", side_effect=http_failure(403, WEBHOOK.encode())), \
                contextlib.redirect_stdout(output):
            with self.assertRaises(forwarder.DeliveryError):
                forwarder.handler(sns_event(), None)
        self.assertEqual(output.getvalue(), "event=discord_alert_delivery_failed environment=dev\n")
        self.assertNotIn(WEBHOOK, output.getvalue())
        self.assertNotIn("s3://", output.getvalue())

    def test_non_string_secret_urls_are_rejected_with_a_fixed_error(self):
        for value in (None, 123, []):
            with self.subTest(value_type=type(value).__name__), self.assertRaisesRegex(forwarder.DeliveryError, "webhook URL invalid"):
                forwarder._validate_webhook_url(value)

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
                WEBHOOK + "?",
                WEBHOOK + "#",
                WEBHOOK + "?#",
            ):
                client.get_secret_value.return_value = {"SecretString": unsafe}
                with self.assertRaises(forwarder.DeliveryError) as raised:
                    forwarder._webhook_url(SECRET)
                self.assertEqual(str(raised.exception), "webhook secret unavailable")
                self.assertNotIn(unsafe, str(raised.exception))


if __name__ == "__main__":
    unittest.main()
