"""Render API-only CloudWatch dashboard and alarm payloads without calling AWS."""

import argparse
import json
import math
from pathlib import Path
import re


METRIC = "harudle_api_response_seconds"
METHODS = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT"}
TAGS = [{"Key": key, "Value": value} for key, value in {
    "Service": "techcourse", "Role": "techcourse-etc", "ProjectTeam": "harudle",
}.items()]


def dimensions(node, method="ALL", uri="ALL", quantile=None):
    values = {"job": "harudle-backend", "node": node, "method": method, "uri": uri}
    if quantile is not None:
        values["quantile"] = quantile
    return [{"Name": name, "Value": value} for name, value in values.items()]


def query(identifier, namespace, node, suffix, statistic, period=300, quantile=None):
    return {
        "Id": identifier, "ReturnData": False,
        "MetricStat": {
            "Metric": {"Namespace": namespace, "MetricName": METRIC + suffix,
                       "Dimensions": dimensions(node, quantile=quantile)},
            "Period": period, "Stat": statistic,
            "Unit": "Count" if suffix == "_count" else "Seconds",
        },
    }


def graph_metric(namespace, node, method, uri, suffix, identifier, statistic, quantile=None, period=300):
    definition = [namespace, METRIC + suffix]
    for dimension in dimensions(node, method, uri, quantile):
        definition.extend([dimension["Name"], dimension["Value"]])
    definition.append({"id": identifier, "stat": statistic, "period": period, "visible": False})
    return definition


def render(environment, node, topic_arn, p95_seconds, p99_seconds,
           min_p95_requests=100, min_p99_requests=1000, endpoints=()):
    if environment not in {"dev", "prod"}:
        raise ValueError("Environment must be dev or prod")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", node):
        raise ValueError("Node must match the backend deployment identifier")
    if not re.fullmatch(rf"arn:aws:sns:[a-z0-9-]+:\d{{12}}:harudle-{environment}-alerts", topic_arn):
        raise ValueError("Use the matching environment's SNS alerts topic")
    for threshold in (p95_seconds, p99_seconds):
        if not math.isfinite(threshold) or threshold <= 0:
            raise ValueError("Response-time thresholds must be positive finite seconds")
    for minimum in (min_p95_requests, min_p99_requests):
        if not isinstance(minimum, int) or minimum < 1:
            raise ValueError("Minimum requests must be a positive integer")
    endpoints = list(dict.fromkeys(endpoints))
    if len(endpoints) > 20:
        raise ValueError("Select at most 20 endpoint panels")
    for method, uri in endpoints:
        if method not in METHODS or not uri.startswith("/api/") or "?" in uri or "\n" in uri:
            raise ValueError("Endpoints must use an HTTP method and an API route template")
    namespace = f"Harudle/{environment.title()}"
    region = topic_arn.split(":")[3]
    alarms = []
    for percentile, threshold, minimum in ((95, p95_seconds, min_p95_requests),
                                           (99, p99_seconds, min_p99_requests)):
        alarms.append({
            "AlarmName": f"harudle-{environment}-api-p{percentile}",
            "AlarmDescription": (
                f"API 응답 시간 P{percentile}: {node}의 전체 API summary 게시값 중 5분 Maximum. "
                f"5분 API 완료 {minimum}건 이상과 양수 백분위일 때만 평가하며 "
                "2개 평가 창 연속 초과 시 경보. summary 창과 CloudWatch 창은 정렬되지 않으므로 "
                "5분 전체 요청의 백분위 또는 summary 내부 표본 수 보장이 아니다. "
                "dev 수집·목표 검증 전 알림 비활성. 비동기 생성 시간 제외."
            ),
            "ActionsEnabled": False,
            "AlarmActions": [topic_arn], "OKActions": [topic_arn], "InsufficientDataActions": [],
            "EvaluationPeriods": 2, "DatapointsToAlarm": 2,
            "Threshold": threshold, "ComparisonOperator": "GreaterThanThreshold",
            "TreatMissingData": "missing",
            "Metrics": [
                query("q", namespace, node, "", "Maximum", quantile=str(percentile / 100)),
                query("c", namespace, node, "_count", "Sum"),
                {"Id": "latency", "Expression": f"IF((c >= {minimum}) AND (q > 0), q)",
                 "Label": f"API P{percentile} (seconds), sufficient activity", "ReturnData": True},
            ],
            "Tags": TAGS,
        })
    widgets = [{
        "type": "text", "x": 0, "y": 0, "width": 24, "height": 3,
        "properties": {"markdown": (
            f"### API 응답 시간 · {environment} · {node}\n"
            "HTTP 요청 관측 종료까지 측정. 비동기 생성 작업·내부 healthcheck 제외. "
            "P95/P99는 앱의 최대 5분 감쇠 창에서 계산해 1분마다 수집한 값(0은 숨김). "
            "CloudWatch 5분 요청 전체의 백분위가 아니다. 평균·요청 수는 5분 증가분 기준. "
            "ALL과 개별 경로는 동일 요청의 별도 모집단이므로 합산하지 않는다."
        )},
    }]
    for index, (method, uri) in enumerate([("ALL", "ALL"), *endpoints]):
        label = "전체 API" if method == "ALL" else f"{method} {uri}"
        metrics = [
            graph_metric(namespace, node, method, uri, "", "q95", "Maximum", "0.95", 60),
            graph_metric(namespace, node, method, uri, "", "q99", "Maximum", "0.99", 60),
            graph_metric(namespace, node, method, uri, "_sum", "s", "Sum"),
            graph_metric(namespace, node, method, uri, "_count", "c", "Sum"),
            [{"expression": "IF(q95 > 0, q95)", "id": "p95", "label": "P95 (1분 게시값)", "period": 60}],
            [{"expression": "IF(q99 > 0, q99)", "id": "p99", "label": "P99 (1분 게시값)", "period": 60}],
            [{"expression": "IF(c > 0, s / c)", "id": "mean", "label": "평균 (5분)", "period": 300}],
        ]
        common = {"region": region, "view": "timeSeries", "period": 300}
        widgets.extend([
            {"type": "metric", "x": 0, "y": 3 + index * 6, "width": 16, "height": 6,
             "properties": common | {"title": f"{label} 응답 시간 (초)", "metrics": metrics,
                                     "yAxis": {"left": {"min": 0}}}},
            {"type": "metric", "x": 16, "y": 3 + index * 6, "width": 8, "height": 6,
             "properties": common | {"title": f"{label} 요청 수 (5분)", "metrics": [
                 graph_metric(namespace, node, method, uri, "_count", "requests", "Sum")[:-1]
                 + [{"id": "requests", "stat": "Sum", "visible": True}]],
                                     "yAxis": {"left": {"min": 0}}}},
        ])
    return {"widgets": widgets}, alarms


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", choices=("dev", "prod"), required=True)
    parser.add_argument("--node", required=True)
    parser.add_argument("--topic-arn", required=True)
    parser.add_argument("--p95-seconds", type=float, required=True)
    parser.add_argument("--p99-seconds", type=float, required=True)
    parser.add_argument("--min-p95-requests", type=int, default=100)
    parser.add_argument("--min-p99-requests", type=int, default=1000)
    parser.add_argument("--endpoint", action="append", default=[], metavar="METHOD:/api/route/{id}")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        dashboard, alarms = render(args.environment, args.node, args.topic_arn,
                                   args.p95_seconds, args.p99_seconds,
                                   args.min_p95_requests, args.min_p99_requests,
                                   [tuple(endpoint.split(":", 1)) for endpoint in args.endpoint])
    except (ValueError, TypeError) as error:
        parser.error(str(error))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    payloads = {"dashboard.json": dashboard} | {
        f"api-p{percentile}.json": alarm for percentile, alarm in zip((95, 99), alarms)
    }
    for filename, payload in payloads.items():
        (args.output_dir / filename).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                                               encoding="utf-8")
    print(f"Rendered {len(payloads)} files; alarm actions disabled; no AWS calls made")


if __name__ == "__main__":
    main()
