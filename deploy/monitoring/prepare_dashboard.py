"""Prepare CloudWatch files locally. This script never calls AWS."""

import argparse
import copy
import json
from pathlib import Path
import re


REGION = "ap-northeast-2"
NAMESPACES = {"dev": "Harudle/Dev", "prod": "Harudle/Prod"}
PROCSTAT_PATTERN = "^([^ ]*/)?java +.*-jar +/app/app[.]jar( +.*)?$"


def metric(namespace, name, dimensions=(), **options):
    return [namespace, name, *dimensions, options]


def expression(formula, label, **options):
    return [{"expression": formula, "label": label, **options}]


def widget(title, metrics):
    for index, row in enumerate(metrics):
        if isinstance(row[0], dict) and "expression" in row[0]:
            row[0].setdefault("id", f"e{index}")
    return {"type": "metric", "width": 12, "height": 6, "properties": {
        "title": title, "view": "timeSeries", "region": REGION,
        "period": 60, "stat": "Average", "metrics": metrics,
    }}


def service_widgets(environment, instance_id):
    namespace = NAMESPACES[environment]
    widgets = []
    for group, label in ((None, "전체"), ("general", "일반"), ("generation", "일기 생성")):
        dimensions = () if group is None else ("ApiGroup", group)
        widgets.append(widget(f"API 응답 시간 ({label})", [
            metric(namespace, "ApiResponseTimeMs", dimensions, stat=stat, label=name)
            for stat, name in (("Average", "평균 (ms)"), ("p95", "P95 (ms)"), ("p99", "P99 (ms)"))
        ] + [metric(namespace, "ApiResponseTimeMs", dimensions,
                    stat="SampleCount", label="요청 수", yAxis="right")]))
    widgets.append(widget("API RPS (완료한 요청/초)", [
        metric(namespace, "ApiResponseTimeMs", stat="SampleCount", id="requests", visible=False),
        expression("requests/PERIOD(requests)", "RPS"),
    ]))
    operations = (
        ("DiaryGenerationSucceeded", "일기 생성"), ("FeedPublicationSucceeded", "피드 게시"),
        ("FeedListSucceeded", "피드 목록 조회"), ("FeedDetailSucceeded", "피드 상세 조회"),
    )
    metrics = []
    for index, (name, label) in enumerate(operations):
        identifier = f"success{index}"
        metrics.extend([
            metric(namespace, name, stat="Sum", id=identifier, visible=False),
            expression(f"{identifier}/PERIOD({identifier})", label),
        ])
    widgets.append(widget("작업별 TPS (성공한 작업/초)", metrics))
    pool = ("job", "harudle-backend", "pool", "HikariPool-1")
    widgets.append(widget("DB 연결 획득 시간", [
        metric(namespace, "hikaricp_connections_acquire_seconds_sum", pool,
               stat="Sum", id="seconds", visible=False),
        metric(namespace, "hikaricp_connections_acquire_seconds_count", pool,
               stat="Sum", id="acquires", visible=False),
        metric(namespace, "hikaricp_connections_acquire_seconds_max", pool,
               stat="Maximum", id="recentmax", visible=False),
        expression("IF(acquires>0,1000*seconds/acquires)", "평균 (ms)"),
        expression("1000*recentmax", "최근 최대 (ms)"),
    ]))
    widgets.append(widget("DB 연결 대기와 시간 초과", [
        metric(namespace, "hikaricp_connections_pending", pool, stat="Maximum", label="기다리는 요청 수"),
        metric(namespace, "hikaricp_connections_timeout_total", pool,
               stat="Sum", label="연결 시간 초과 횟수", yAxis="right"),
    ]))
    heap_search = (f'SEARCH(\'{{"{namespace}",job,area,id}} MetricName="jvm_memory_used_bytes" '
                   'job="harudle-backend" area="heap"\', \'Average\', 60)')
    search_pattern = "".join("\\" + char if char in '\\"()' else char for char in PROCSTAT_PATTERN)
    rss_search = ('SEARCH(\'Namespace="CWAgent" MetricName="procstat_memory_rss" '
                  f'InstanceId="{instance_id}" pattern="{search_pattern}"\', \'Average\', 60)')
    widgets.append(widget("Java 힙 사용량", [expression(f"SUM({heap_search})/1048576", "힙 사용량 (MiB)")]))
    widgets.append(widget("Java 실제 메모리 (RSS)", [expression(f"SUM({rss_search})/1048576", "RSS (MiB)")]))
    return widgets


def metric_filters(environment):
    namespace = NAMESPACES[environment]

    def request(name, pattern, value="1", unit="Count", dimensions=None):
        transformation = {"metricNamespace": namespace, "metricName": name,
                          "metricValue": value, "unit": unit}
        if dimensions:
            transformation["dimensions"] = dimensions
        if unit == "Count":
            transformation["defaultValue"] = 0
        suffix = "-by-group" if dimensions else ""
        return {"logGroupName": f"/harudle/{environment}/backend",
                "filterName": f"harudle-{environment}-{name}{suffix}",
                "filterPattern": pattern, "metricTransformations": [transformation]}

    completed = '$.event = "api_request_completed"'
    duration = '{ ' + completed + ' && $.durationMs >= 0 }'
    grouped_duration = ('{ ' + completed + ' && $.durationMs >= 0 '
                        '&& ($.apiGroup = "general" || $.apiGroup = "generation") }')
    return [
        request("ApiResponseTimeMs", duration, "$.durationMs", "Milliseconds"),
        request("ApiResponseTimeMs", grouped_duration, "$.durationMs", "Milliseconds",
                {"ApiGroup": "$.apiGroup"}),
        request("DiaryGenerationSucceeded", '{ $.event = "generation_finalized" && $.status = "SUCCEEDED" }'),
        request("FeedPublicationSucceeded", '{ $.event = "feed_publication_succeeded" }'),
        request("FeedListSucceeded", '{ ' + completed + ' && $.operation = "feed_list" && $.httpStatus = 200 }'),
        request("FeedDetailSucceeded", '{ ' + completed + ' && $.operation = "feed_detail" && $.httpStatus = 200 }'),
    ]


def prepare_dashboard(original, environment, instance_id, rds_instance_id):
    dashboard = copy.deepcopy(original)
    additions = service_widgets(environment, instance_id)
    titles = {item["properties"]["title"] for item in additions}
    retained = [item for item in dashboard["widgets"] if item.get("properties", {}).get("title") not in titles]
    for item in retained:
        for row in item.get("properties", {}).get("metrics", []):
            for index in range(len(row) - 1):
                if row[index] == "InstanceId" and row[index + 1] == "i-00000000000000000":
                    row[index + 1] = instance_id
                if row[index] == "DBInstanceIdentifier" and row[index + 1] == f"harudle-{environment}":
                    row[index + 1] = rds_instance_id
    bottom = max((item.get("y", 0) + item.get("height", 6) for item in retained), default=0)
    for index, item in enumerate(additions):
        item.update(x=(index % 2) * 12, y=bottom + (index // 2) * 6)
    dashboard["widgets"] = retained + additions
    return dashboard


def read_dashboard(path, environment):
    dashboard = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(dashboard, dict) and "DashboardBody" in dashboard:
        expected_name = f"DASHBOARD-harudle-{environment}"
        arn = dashboard.get("DashboardArn", "")
        if not isinstance(arn, str) or not arn.endswith(f":dashboard/{expected_name}"):
            raise ValueError(f"{expected_name} 대시보드를 조회한 JSON이 필요합니다.")
        if not isinstance(dashboard["DashboardBody"], str):
            raise ValueError("DashboardBody에는 JSON 문자열이 필요합니다.")
        dashboard = json.loads(dashboard["DashboardBody"])
    if not isinstance(dashboard, dict) or not isinstance(dashboard.get("widgets"), list):
        raise ValueError("View/edit source의 JSON 전체를 저장하세요. widgets 목록이 필요합니다.")
    if any(not isinstance(item, dict) or not isinstance(item.get("properties"), dict)
           for item in dashboard["widgets"]):
        raise ValueError("widgets의 각 항목에는 properties 객체가 필요합니다.")
    return dashboard


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", choices=NAMESPACES, required=True)
    parser.add_argument("--instance-id", required=True)
    parser.add_argument("--rds-instance-id", required=True)
    parser.add_argument("--dashboard", type=Path, required=True,
                        help="CloudWatch View/edit source JSON or AWS get-dashboard JSON output")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"i-[0-9a-f]{8}(?:[0-9a-f]{9})?", args.instance_id) or set(args.instance_id[2:]) == {"0"}:
        parser.error("실제 EC2 InstanceId를 입력하세요.")
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", args.rds_instance_id):
        parser.error("실제 RDS DBInstanceIdentifier를 입력하세요.")
    try:
        original = read_dashboard(args.dashboard, args.environment)
    except (OSError, ValueError) as error:
        parser.error(f"대시보드 JSON을 읽을 수 없습니다: {error}")
    dashboard = prepare_dashboard(original, args.environment, args.instance_id, args.rds_instance_id)
    files = {"dashboard.json": dashboard}
    for index, configuration in enumerate(metric_filters(args.environment), start=1):
        files[f"metric-filter-{index}.json"] = configuration
    if args.dashboard.resolve() in {(args.output / name).resolve() for name in files}:
        parser.error("원본 JSON과 생성할 파일의 경로를 다르게 지정하세요.")
    args.output.mkdir(parents=True, exist_ok=True)
    for name, content in files.items():
        target = args.output / name
        target.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(target)


if __name__ == "__main__":
    main()
