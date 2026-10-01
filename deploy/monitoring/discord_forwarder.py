"""Forward allowlisted CloudWatch alarm states to a team Discord webhook.

Set exactly one of ``WEBHOOK_URL`` or ``WEBHOOK_SECRET_ARN``. A Secrets
Manager secret is a raw URL or JSON containing ``webhook_url``. Alarm
reasons are fixed phrases: CloudWatch's NewStateReason is never forwarded.
The function never logs its input, the webhook URL, or provider responses.
"""

import json
import math
import os
import re
import time
from http.client import HTTPException
from urllib import error, parse, request


_ALARM_REASONS = {
    "generation-unexpected-failure": "일기 생성 중 예상하지 못한 오류",
    "generation-interrupted": "일기 생성 만료 처리 발생",
    "s3-put-failure": "생성 이미지 S3 저장 실패",
    "s3-reference-get-failure": "참조 이미지 S3 조회 실패",
    "s3-url-sign-failure": "이미지 접근 URL 발급 실패",
    "s3-authentication": "S3 인증 오류",
    "s3-authorization": "S3 권한 오류",
    "s3-configuration": "S3 설정 오류",
    "image-load-failure": "로그인 사용자 화면의 이미지 표시 실패",
    "api-5xx": "API 서버 오류 증가",
    "api-error-rate": "API 5xx 비율 증가",
    "gemini-storyboard-transient": "스토리보드 공급자 일시 오류 증가",
    "gemini-storyboard-response": "스토리보드 응답 처리 오류 증가",
    "gemini-image-transient": "네컷 이미지 공급자 일시 오류 증가",
    "gemini-image-response": "네컷 이미지 응답 처리 오류 증가",
    "hikari-pending": "데이터베이스 연결 대기 증가",
    "telemetry-stale": "서버 지표 수집 중단",
    "api-p95": "API 응답 시간 P95 증가",
    "api-p99": "API 응답 시간 P99 증가",
    "ec2-status-check": "EC2 상태 검사 실패",
    "ec2-cpu-high": "EC2 CPU 사용률 증가",
    "ec2-memory-high": "EC2 메모리 사용률 증가",
    "ec2-disk-high": "EC2 디스크 사용률 증가",
    "rds-cpu-high": "RDS CPU 사용률 증가",
    "rds-memory-low": "RDS 사용 가능 메모리 부족",
    "rds-storage-low": "RDS 사용 가능 저장 공간 부족",
    "rds-connections-high": "RDS 연결 수 증가",
}
_WEBHOOK_PATH = re.compile(r"/api/webhooks/[0-9]+/[A-Za-z0-9_-]+")
_ALERT_STYLES = {
    "ALARM": ("🔴 백엔드 경보가 발생했어요", 0xED4245),
    "OK": ("🟢 백엔드 경보가 정상 상태예요", 0x57F287),
    "INSUFFICIENT_DATA": ("🟡 지표 데이터가 부족해요", 0xFEE75C),
}
_STATES = frozenset(_ALERT_STYLES)
_MAX_ATTEMPTS = 3
_REQUEST_TIMEOUT_SECONDS = 3
_DELIVERY_BUDGET_SECONDS = 10
_MAX_RESPONSE_BYTES = 64 * 1024
_RETRYABLE_HTTP_CODES = frozenset({429, 500, 502, 503, 504})
_USER_AGENT = "DiscordBot (https://github.com/woowacourse-teams/2026-Harudle, 1.0)"


class DeliveryError(Exception):
    """A fixed error message safe for Lambda's automatic exception log."""


def _alarm_message(sns_record, expected_topic, environment):
    """Build only the four approved fields from a trusted topic and alarm name."""
    if sns_record.get("TopicArn") != expected_topic:
        raise DeliveryError("unexpected notification topic")
    try:
        alarm = json.loads(sns_record["Message"])
    except (KeyError, TypeError, ValueError):
        raise DeliveryError("invalid alarm notification") from None
    if not isinstance(alarm, dict):
        raise DeliveryError("invalid alarm notification")

    name = alarm.get("AlarmName")
    state = alarm.get("NewStateValue")
    if (
        not isinstance(name, str)
        or len(name) > 96
        or not isinstance(state, str)
        or state not in _STATES
    ):
        raise DeliveryError("invalid alarm name or state")
    prefix = f"harudle-{environment}-"
    if not name.startswith(prefix) or name[len(prefix):] not in _ALARM_REASONS:
        raise DeliveryError("alarm not allowlisted")

    if state == "INSUFFICIENT_DATA":
        reason = "지표 데이터 부족"
    elif state == "OK":
        reason = _ALARM_REASONS[name[len(prefix):]] + " (현재 경보 상태: OK)"
    else:
        reason = _ALARM_REASONS[name[len(prefix):]]
    title, color = _ALERT_STYLES[state]
    return {
        "embeds": [{
            "title": title,
            "description": reason,
            "color": color,
            "fields": [
                {"name": "환경", "value": f"`{environment}`", "inline": True},
                {"name": "상태", "value": f"`{state}`", "inline": True},
                {"name": "알람", "value": f"`{name}`", "inline": False},
            ],
        }],
        "allowed_mentions": {"parse": []},
    }


def _validate_webhook_url(value):
    """Accept a bare Discord endpoint; reject secret-bearing error details."""
    try:
        if not isinstance(value, str) or any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError("invalid webhook")
        parsed = parse.urlsplit(value)
        if (
            parsed.scheme != "https"
            or parsed.hostname not in {"discord.com", "discordapp.com"}
            or parsed.username is not None
            or parsed.password is not None
            or parsed.port is not None
            or parsed.query
            or parsed.fragment
            or not _WEBHOOK_PATH.fullmatch(parsed.path)
        ):
            raise ValueError("invalid webhook")
        return value
    except Exception:
        # URL errors may contain secret material. Suppress context.
        raise DeliveryError("webhook URL invalid") from None


def _webhook_url(secret_arn):
    """Read and validate the optional secret without exposing SDK errors."""
    try:
        import boto3

        secret = boto3.client("secretsmanager").get_secret_value(SecretId=secret_arn)
        value = secret["SecretString"]
        if value.lstrip().startswith("{"):
            value = json.loads(value)["webhook_url"]
        return _validate_webhook_url(value)
    except Exception:
        # AWS SDK and URL errors may contain secret material. Suppress context.
        raise DeliveryError("webhook secret unavailable") from None


class _NoRedirect(request.HTTPRedirectHandler):
    """Keep alert contents on the validated Discord endpoint."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """Reject redirects instead of issuing a request to another URL."""
        return None


_HTTP_CLIENT = request.build_opener(_NoRedirect())


def _response_bytes(response):
    """Bound provider data retained in memory; never include it in errors."""
    body = response.read(_MAX_RESPONSE_BYTES + 1)
    if len(body) > _MAX_RESPONSE_BYTES:
        raise DeliveryError("discord response too large")
    return body


def _confirm_message(response):
    """Require the message Discord returns after wait=true persistence."""
    if response.status != 200:
        raise DeliveryError("discord message not confirmed")
    try:
        message = json.loads(_response_bytes(response))
        message_id = message.get("id") if isinstance(message, dict) else None
        if not isinstance(message_id, str) or not re.fullmatch(r"[0-9]+", message_id):
            raise ValueError("missing message id")
    except (TypeError, ValueError):
        raise DeliveryError("discord message not confirmed") from None


def _retry_delay(exception, attempt):
    """Honor Discord's numeric Retry-After without shortening its delay."""
    delays = [float(attempt)]
    values = [exception.headers.get("Retry-After") if exception.headers else None]
    if exception.code == 429:
        try:
            body = json.loads(_response_bytes(exception))
            if isinstance(body, dict):
                values.append(body.get("retry_after"))
        except (DeliveryError, HTTPException, OSError, TypeError, ValueError):
            pass
    for value in values:
        try:
            if isinstance(value, bool):
                continue
            delay = float(value)
            if math.isfinite(delay) and delay >= 0:
                delays.append(delay)
        except (TypeError, ValueError, OverflowError):
            pass
    return max(delays)


def _post(webhook_url, payload, context=None):
    """Confirm delivery with bounded retries; propagate errors for Lambda retries."""
    destination = _validate_webhook_url(webhook_url) + "?wait=true"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    post = request.Request(
        destination,
        data=body,
        headers={"Content-Type": "application/json", "User-Agent": _USER_AGENT},
        method="POST",
    )
    budget = _DELIVERY_BUDGET_SECONDS
    if context is not None:
        budget = min(budget, context.get_remaining_time_in_millis() / 1000 - 1)
    deadline = time.monotonic() + budget
    for attempt in range(1, _MAX_ATTEMPTS + 1):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise DeliveryError("discord delivery budget exhausted")
        delay = float(attempt)
        try:
            with _HTTP_CLIENT.open(post, timeout=min(_REQUEST_TIMEOUT_SECONDS, remaining)) as response:
                _confirm_message(response)
            return
        except error.HTTPError as exception:
            failure = DeliveryError(f"discord returned HTTP {exception.code}")
            try:
                if exception.code not in _RETRYABLE_HTTP_CODES:
                    raise failure from None
                delay = _retry_delay(exception, attempt)
            finally:
                exception.close()
        except DeliveryError:
            raise
        except (error.URLError, HTTPException, OSError):
            failure = DeliveryError("discord transport failed")
        except Exception:
            raise DeliveryError("discord transport failed") from None
        if attempt == _MAX_ATTEMPTS:
            raise failure from None
        # Do not start another request at the deadline or shorten Retry-After.
        if delay >= deadline - time.monotonic():
            raise DeliveryError("discord retry exceeds delivery budget") from None
        time.sleep(delay)


def handler(event, context):
    """Forward one SNS record; log confirmed delivery or a fixed failure event."""
    environment = os.environ.get("DEPLOY_ENV")
    topic_arn = os.environ.get("ALARM_TOPIC_ARN")
    secret_arn = os.environ.get("WEBHOOK_SECRET_ARN")
    webhook_url = os.environ.get("WEBHOOK_URL")
    if (
        environment not in ("dev", "prod")
        or not topic_arn
        or bool(secret_arn) == bool(webhook_url)
    ):
        raise DeliveryError("forwarder configuration invalid")

    try:
        if not isinstance(event, dict):
            raise DeliveryError("invalid SNS event")
        records = event.get("Records")
        if not isinstance(records, list) or len(records) != 1 or not isinstance(records[0], dict):
            raise DeliveryError("invalid SNS event")
        sns_record = records[0].get("Sns")
        if not isinstance(sns_record, dict):
            raise DeliveryError("invalid SNS event")

        payload = _alarm_message(sns_record, topic_arn, environment)
        destination = _webhook_url(secret_arn) if secret_arn else _validate_webhook_url(webhook_url)
        _post(destination, payload, context)
    except DeliveryError:
        print(f"event=discord_alert_delivery_failed environment={environment}")
        raise
    print(f"event=discord_alert_delivered environment={environment}")
    return {"delivered": True}
