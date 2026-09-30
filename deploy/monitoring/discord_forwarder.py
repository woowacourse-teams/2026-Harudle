"""Forward allowlisted CloudWatch alarm states to a team Discord webhook.

The webhook secret is a raw URL or JSON containing ``webhook_url``. Alarm
reasons are fixed phrases: CloudWatch's NewStateReason is never forwarded.
The function never logs its input, the webhook URL, or provider responses.
"""

import json
import os
import re
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
    "gemini-storyboard-errors": "스토리보드 생성 실패 증가",
    "gemini-image-errors": "네컷 이미지 생성 실패 증가",
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
_STATES = frozenset({"ALARM", "OK", "INSUFFICIENT_DATA"})
_USER_AGENT = "DiscordBot (https://github.com/woowacourse-teams/2026-Harudle, 1.0)"


class DeliveryError(Exception):
    """A fixed error message safe for Lambda's automatic exception log."""


def _alarm_message(sns_record, expected_topic, environment):
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
    if not isinstance(name, str) or len(name) > 96 or state not in _STATES:
        raise DeliveryError("invalid alarm name or state")
    prefix = f"harudle-{environment}-"
    if not name.startswith(prefix) or name[len(prefix):] not in _ALARM_REASONS:
        raise DeliveryError("alarm not allowlisted")

    if state == "INSUFFICIENT_DATA":
        reason = "지표 데이터 부족"
    elif state == "OK":
        reason = _ALARM_REASONS[name[len(prefix):]] + " 해소"
    else:
        reason = _ALARM_REASONS[name[len(prefix):]]
    return {
        "content": f"환경: {environment}\n알람: {name}\n상태: {state}\n원인: {reason}",
        "allowed_mentions": {"parse": []},
    }


def _webhook_url(secret_arn):
    try:
        import boto3

        secret = boto3.client("secretsmanager").get_secret_value(SecretId=secret_arn)
        value = secret["SecretString"]
        if value.lstrip().startswith("{"):
            value = json.loads(value)["webhook_url"]
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
        # AWS SDK and URL errors may contain secret material. Suppress context.
        raise DeliveryError("webhook secret unavailable") from None


def _post(webhook_url, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    post = request.Request(
        webhook_url,
        data=body,
        headers={"Content-Type": "application/json", "User-Agent": _USER_AGENT},
        method="POST",
    )
    try:
        with request.urlopen(post, timeout=5) as response:
            if response.status not in (200, 204):
                raise DeliveryError("discord rejected notification")
    except error.HTTPError as exception:
        raise DeliveryError(f"discord returned HTTP {exception.code}") from None
    except DeliveryError:
        raise
    except Exception:
        raise DeliveryError("discord transport failed") from None


def handler(event, _context):
    environment = os.environ.get("DEPLOY_ENV")
    topic_arn = os.environ.get("ALARM_TOPIC_ARN")
    secret_arn = os.environ.get("WEBHOOK_SECRET_ARN")
    if environment not in ("dev", "prod") or not topic_arn or not secret_arn:
        raise DeliveryError("forwarder configuration invalid")

    try:
        records = event["Records"]
        if len(records) != 1:
            raise ValueError("one SNS record required")
        sns_record = records[0]["Sns"]
    except (KeyError, TypeError, ValueError):
        raise DeliveryError("invalid SNS event") from None

    payload = _alarm_message(sns_record, topic_arn, environment)
    _post(_webhook_url(secret_arn), payload)
    print(f"event=discord_alert_delivered environment={environment}")
    return {"delivered": True}
