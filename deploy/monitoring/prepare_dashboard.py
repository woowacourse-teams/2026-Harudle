"""Prepare CloudWatch files locally. This script never calls AWS."""

import argparse
import copy
import json
from pathlib import Path
import re


REGION = "ap-northeast-2"
NAMESPACES = {"dev": "Harudle/Dev", "prod": "Harudle/Prod"}
PROCSTAT_PATTERN = "^([^ ]*/)?java +.*-jar +/app/app[.]jar( +.*)?$"
REQUEST_STORAGE_OPERATIONS = (
    ("S3Head", "s3Head", "S3 존재 확인"), ("R2Head", "r2Head", "R2 존재 확인"),
    ("S3Sign", "s3Sign", "S3 URL 서명"), ("R2Sign", "r2Sign", "R2 URL 서명"),
)
RPS_Y_AXIS = {"left": {"label": "요청/초", "showUnits": False}}
DB_ACQUIRE_Y_AXIS = {"left": {"label": "밀리초 (ms)", "showUnits": False}}
MEMORY_MIB_Y_AXIS = {"left": {"label": "메모리 (MiB)", "showUnits": False}}
LOG_QUERIES = {
    "OAuth Login Events": r'''SOURCE '/harudle/{environment}/backend'
| filter event in ["oauth_login_failure", "oauth_login_rejected"] or message like /event=oauth_login_(failure|rejected) /
| parse message /event=(?<messageEvent>oauth_login_failure|oauth_login_rejected) provider=(?<messageProvider>\S+) reason=(?<messageReason>\S+)/
| fields @timestamp, traceId, coalesce(event, messageEvent) as oauthEvent, coalesce(provider, messageProvider) as oauthProvider, coalesce(reason, messageReason) as oauthReason
| sort @timestamp desc
| limit 50
| display @timestamp, traceId, oauthEvent, oauthProvider, oauthReason''',
    "Nginx 502/504 & Upstream Errors": r'''SOURCE '/harudle/{environment}/frontend-nginx'
| fields @timestamp, @message
| filter status in [502, 504]
    or @message like /" (502|504) /
    or (@message like /\[(error|crit|alert|emerg)\]/ and @message like /upstream|timed out/)
| sort @timestamp desc
| limit 30''',
}


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


DEV_REMOVED_TITLES = {
    "API 응답 시간 (전체)", "API 응답 시간 (일반)", "API 응답 시간 (일기 생성)", "작업별 TPS (성공한 작업/초)",
    "평균 시간의 실행 표본 수", "평균 시간의 실행 표본 수 (5분)", "Gemini 관측 토큰 total", "Gemini 관측 토큰 total (5분)",
    "CodeBuild Gradle cache logs",
}
GENERATION_NOTICE = (
    "### 생성 시간과 관련 실패\n"
    "생성 시간은 구간별 평균이며, 실행이 없는 구간은 표시하지 않습니다. 그래프 간격은 선택한 시간 범위에 따라 달라집니다.\n"
    "returned는 예외 없이 반환된 실행, threw는 예외가 발생한 실행입니다. DB 최종 성공 여부는 위의 '일기 생성' 지표에서 확인합니다.\n\n"
    "정리 실패 3종과 Gemini 요청 실패는 경보가 설정되어 있습니다. 이미지 대체 실패 경보는 R2 활성화 여부와 로그 확인 후 설정합니다. "
    "경보가 OK여도 이전 실패가 해결되었다는 뜻은 아닙니다.")


def route_search(environment, group, statistic, limit=None):
    search = (f'SEARCH(\'{{{NAMESPACES[environment]},ApiGroup,ApiRoute}} MetricName="ApiResponseTimeByRouteMs" '
              f'ApiGroup="{group}"\', \'{statistic}\', 60)')
    return search if limit is None else f"SORT({search}, MAX, DESC, {limit})"


def route_widget(environment, group, label, statistic="p95", limit=None):
    item = widget(f"API 경로별 P95 응답 시간 ({label})", [
        expression(route_search(environment, group, statistic, limit), "${PROP('Dim.ApiRoute')}")
    ])
    if environment == "dev":
        item["properties"]["yAxis"] = {"left": {"label": "응답 시간 (ms)", "showUnits": False, "min": 0}}
    return item


def nginx_gateway_widget(environment):
    item = widget("Nginx 502/504 응답 수", [
        metric(NAMESPACES[environment], "NginxGatewayErrorLogs", label="502/504 응답 수"),
    ])
    item["properties"].update(stat="Sum", yAxis={"left": {"label": "응답 수", "showUnits": False, "min": 0}})
    return item


def service_widgets(environment, instance_id):
    namespace = NAMESPACES[environment]
    widgets = []
    if environment == "prod":
        widgets.append(widget("API 응답 시간 (일반)", [
            metric(namespace, "ApiResponseTimeMs", ("ApiGroup", "general"),
                   stat="p95", label="P95 (ms)"),
        ]))
        widgets.extend((route_widget(environment, "general", "일반 상위 5개", limit=5),
                        route_widget(environment, "generation", "일기 생성 전체")))
    else:
        for group, label in ((None, "전체"), ("general", "일반"), ("generation", "일기 생성")):
            dimensions = () if group is None else ("ApiGroup", group)
            widgets.append(widget(f"API 응답 시간 ({label})", [
                metric(namespace, "ApiResponseTimeMs", dimensions, stat=stat, label=name)
                for stat, name in (("Average", "평균 (ms)"), ("p95", "P95 (ms)"), ("p99", "P99 (ms)"))
            ] + [metric(namespace, "ApiResponseTimeMs", dimensions,
                        stat="SampleCount", label="요청 수", yAxis="right")]))
    rps_widget = widget("API RPS (완료한 요청/초)", [
        metric(namespace, "ApiResponseTimeMs", stat="SampleCount", id="requests", visible=False),
        expression("requests/PERIOD(requests)", "RPS"),
    ])
    rps_widget["properties"]["yAxis"] = copy.deepcopy(RPS_Y_AXIS)
    widgets.append(rps_widget)
    widgets.append(widget("API 응답 시간 P95·P99 (일반·일기 생성)", [
        metric(namespace, "ApiResponseTimeMs", ("ApiGroup", group), stat=stat, label=f"{label} {name} (ms)", **axis)
        for group, label, axis in (("general", "일반", {}), ("generation", "일기 생성", {"yAxis": "right"}))
        for stat, name in (("p95", "P95"), ("p99", "P99"))
    ]))
    # Generation requests can be hundreds of times slower, so they keep a separate axis.
    widgets[-1]["properties"]["yAxis"] = {"left": {"label": "일반 (ms)", "showUnits": False, "min": 0},
                                          "right": {"label": "일기 생성 (ms)", "showUnits": False, "min": 0}}
    if environment == "dev":
        # Reads and writes differ by orders of magnitude, so each group keeps its own axis.
        for title, operations in (
                ("조회 TPS (성공 건수/초)", (("FeedListSucceeded", "피드 목록"), ("FeedDetailSucceeded", "피드 상세"))),
                ("쓰기 TPS (성공 건수/초)", (("DiaryGenerationSucceeded", "일기 생성"), ("FeedPublicationSucceeded", "피드 게시")))):
            metrics = []
            for index, (name, label) in enumerate(operations):
                metrics.extend([
                    metric(namespace, name, stat="Sum", id=f"s{index}", visible=False),
                    expression(f"s{index}/PERIOD(s{index})", label, id=f"e{index}"),
                ])
            widgets.append(widget(title, metrics))
            widgets[-1]["properties"]["yAxis"] = {"left": {"label": "성공 건수/초", "showUnits": False, "min": 0}}
        for item in widgets[:3]:
            item["properties"]["yAxis"] = {
                "left": {"label": "응답 시간 (ms)", "showUnits": False},
                "right": {"label": "요청 수", "showUnits": False},
            }
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
    widgets[-1]["properties"]["yAxis"] = copy.deepcopy(DB_ACQUIRE_Y_AXIS)
    widgets.append(widget("DB 연결 대기와 시간 초과", [
        metric(namespace, "hikaricp_connections_pending", pool, stat="Maximum", label="기다리는 요청 수"),
        metric(namespace, "hikaricp_connections_timeout_total", pool,
               stat="Sum", label="연결 시간 초과 횟수", yAxis="right"),
    ]))
    heap_search = (f'SEARCH(\'{{"{namespace}",job,area,id}} MetricName="jvm_memory_used_bytes" '
                   'job="harudle-backend" area="heap"\', \'Average\', 60)')
    search_pattern = "".join("\\" + char if char in '\\"()' else char for char in PROCSTAT_PATTERN)
    host_namespace = json.loads(Path(__file__).with_name("targets.json").read_text())[environment]["host_namespace"]
    rss_search = (f'SEARCH(\'Namespace="{host_namespace}" MetricName="procstat_memory_rss" '
                  f'InstanceId="{instance_id}" pattern="{search_pattern}"\', \'Average\', 60)')
    widgets.append(widget("Java 힙 사용량", [expression(f"SUM({heap_search})/1048576", "힙 사용량 (MiB)")]))
    widgets.append(widget("Java 실제 메모리 (RSS)", [expression(f"SUM({rss_search})/1048576", "RSS (MiB)")]))
    for item in widgets[-2:]:
        item["properties"]["yAxis"] = copy.deepcopy(MEMORY_MIB_Y_AXIS)
    job = ("job", "harudle-backend")
    widgets.append(widget("생성 시작 전 평균 대기 시간", [
        metric(namespace, "harudle_generation_queue_wait_seconds_sum", job,
               stat="Sum", id="waitseconds", visible=False),
        metric(namespace, "harudle_generation_queue_wait_seconds_count", job,
               stat="Sum", id="claims", visible=False),
        expression("IF(claims>0,waitseconds/claims)", "평균 대기 시간 (초)"),
    ]))
    widgets.append(widget("생성 대기와 처리 중 작업 수", [
        metric(namespace, "harudle_generation_queue_size", job + ("status", status),
               stat="Maximum", label=label)
        for status, label in (("PENDING", "대기 작업 수"), ("PROCESSING", "처리 중 작업 수"))
    ]))
    widgets.append(widget("생성 접수 거절 횟수", [
        metric(namespace, "harudle_generation_capacity_rejections_total", job,
               stat="Sum", label="접수 상한으로 거절한 횟수"),
    ]))
    widgets.append(widget("요청 안에서 실행한 저장소 작업 수", [
        metric(namespace, f"Request{name}Count", stat="Sum", label=label)
        for name, _, label in REQUEST_STORAGE_OPERATIONS
    ]))
    widgets.append(widget("요청별 저장소 누적 시간 P95", [
        metric(namespace, f"Request{name}DurationMs", stat="p95", label=label + " (ms)")
        for name, _, label in REQUEST_STORAGE_OPERATIONS
    ]))
    widgets.append(widget("피드 데이터 조회 시간", [
        metric(namespace, "RequestFeedQueryDurationMs", stat=stat, label=label)
        for stat, label in (("Average", "평균 (ms)"), ("p95", "P95 (ms)"), ("p99", "P99 (ms)"))
    ]))
    if environment == "dev":
        def gc_search(suffix, statistic):
            return (f'SEARCH(\'{{"{namespace}",job,gc,action,cause}} '
                    f'MetricName="jvm_gc_pause_seconds_{suffix}" job="harudle-backend"\', \'{statistic}\', 60)')

        widgets.append(widget("GC 정지 시간과 횟수", [
            expression(f"1000*MAX({gc_search('max', 'Maximum')})", "최근 최대 정지 (ms)"),
            expression(f"1000*SUM({gc_search('sum', 'Sum')})", "정지 시간 합 (ms)"),
            expression(f"SUM({gc_search('count', 'Sum')})", "GC 횟수", yAxis="right"),
        ]))
        widgets[-1]["properties"]["yAxis"] = {
            "left": {"label": "정지 시간 (ms)", "showUnits": False},
            "right": {"label": "GC 횟수", "showUnits": False},
        }
        memory = []
        for area, label in (("heap", "힙"), ("nonheap", "힙 외")):
            for name, description in (("used", "사용량"), ("committed", "확보량")):
                search = (f'SEARCH(\'{{"{namespace}",job,area,id}} MetricName="jvm_memory_{name}_bytes" '
                          f'job="harudle-backend" area="{area}"\', \'Average\', 60)')
                memory.append(expression(f"SUM({search})/1048576", f"{label} {description} (MiB)"))
        widgets.append(widget("Java 힙과 힙 외 메모리", memory))
        widgets[-1]["properties"]["yAxis"] = copy.deepcopy(MEMORY_MIB_Y_AXIS)
        widgets.extend((route_widget(environment, "general", "일반 상위 5개", limit=5),
                        route_widget(environment, "generation", "일기 생성 전체")))
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
        suffix = f"-by-{'-'.join(key.removeprefix('Api').lower() for key in dimensions)}" if dimensions else ""
        return {"logGroupName": f"/harudle/{environment}/backend",
                "filterName": f"harudle-{environment}-{name}{suffix}",
                "filterPattern": pattern, "metricTransformations": [transformation]}

    completed = '$.event = "api_request_completed"'
    duration = '{ ' + completed + ' && $.durationMs >= 0 }'
    grouped_duration = ('{ ' + completed + ' && $.durationMs >= 0 '
                        '&& ($.apiGroup = "general" || $.apiGroup = "generation") }')
    filters = [
        request("ApiResponseTimeMs", duration, "$.durationMs", "Milliseconds"),
        request("ApiResponseTimeMs", grouped_duration, "$.durationMs", "Milliseconds",
                {"ApiGroup": "$.apiGroup"}),
    ]
    if environment == "dev":
        filters.extend([
            request("DiaryGenerationSucceeded", '{ $.event = "generation_finalized" && $.status = "SUCCEEDED" }'),
            request("FeedPublicationSucceeded", '{ $.event = "feed_publication_succeeded" }'),
            request("FeedListSucceeded", '{ ' + completed + ' && $.operation = "feed_list" && $.httpStatus = 200 }'),
            request("FeedDetailSucceeded", '{ ' + completed + ' && $.operation = "feed_detail" && $.httpStatus = 200 }'),
        ])
    filters.append(request(
        "ApiResponseTimeByRouteMs",
        '{ ' + completed + ' && $.durationMs >= 0 && $.apiRoute = "*" '
        '&& ($.apiGroup = "general" || $.apiGroup = "generation") }',
        "$.durationMs", "Milliseconds",
        {"ApiGroup": "$.apiGroup", "ApiRoute": "$.apiRoute"},
    ))
    # Frontend Nginx writes JSON access logs, so the numeric status field is matched directly.
    filters.append({
        "logGroupName": f"/harudle/{environment}/frontend-nginx",
        "filterName": f"harudle-{environment}-nginx-gateway-error",
        "filterPattern": "{ $.status = 502 || $.status = 504 }",
        "metricTransformations": [{"metricNamespace": namespace, "metricName": "NginxGatewayErrorLogs",
                                   "metricValue": "1", "defaultValue": 0}],
    })
    for name, field, _ in REQUEST_STORAGE_OPERATIONS + (("FeedQuery", "feedQuery", "피드 데이터 조회"),):
        filters.append(request(f"Request{name}Count",
                               '{ ' + completed + f' && $.{field}Count >= 0 }}', f"$.{field}Count"))
        filters.append(request(f"Request{name}DurationMs",
                               '{ ' + completed + f' && $.{field}Count > 0 && $.{field}DurationMs >= 0 }}',
                               f"$.{field}DurationMs", "Milliseconds"))
    return filters


def has_prod_layout(dashboard):
    """Keep a completed console layout, including cards added outside this script."""
    titles = {w.get("properties", {}).get("title") for w in dashboard["widgets"]}
    return ({"백엔드 5xx 비율 (선택 기간)", "일반 API P95 (ms)", "최근 DB 연결 대기 최댓값",
             "DB 데이터 여유 공간 (GiB)", "DB 백업 상태 (1 정상, 0 주의)"}.issubset(titles)
            and not titles.isdisjoint({"생성 실패", "생성 실패 (선택 기간)"})
            and all(all(key in w for key in ("x", "y", "width", "height")) for w in dashboard["widgets"]))


def has_dev_layout(dashboard):
    """Keep the completed Dev console layout, including cards added outside this script."""
    titles = {w.get("properties", {}).get("title") for w in dashboard["widgets"]}
    return ({"백엔드 5xx 비율 (선택 기간)", "일반 API P95 (ms)", "생성 실패 (선택 기간)", "최근 DB 연결 대기 최댓값",
             "DB 데이터 여유 공간 (GiB)", "GC 최근 최대 정지 (ms)",
             "API 응답 시간 P95·P99 (일반·일기 생성)"}.issubset(titles)
            and titles.isdisjoint(DEV_REMOVED_TITLES)
            and all(all(key in w for key in ("x", "y", "width", "height")) for w in dashboard["widgets"]))


def has_completed_layout(dashboard, environment):
    return has_prod_layout(dashboard) if environment == "prod" else has_dev_layout(dashboard)


def prepare_dashboard(original, environment, instance_id):
    if "AWS/RDS" in json.dumps(original):
        raise ValueError("EC2 PostgreSQL로 전환한 최신 대시보드 원본을 사용하세요.")
    dashboard = copy.deepcopy(original)
    dashboard["variables"] = [v for v in dashboard.get("variables", [])
                              if not (v.get("type") == "property" and v.get("property") == "InstanceId")]
    if not dashboard["variables"]:
        dashboard.pop("variables")
    dashboard["periodOverride"] = "auto"
    retained = dashboard["widgets"]
    titles = {item.get("properties", {}).get("title") for item in retained}
    for item in retained:
        properties = item.get("properties", {})
        if item.get("type") == "log" and properties.get("title") in LOG_QUERIES:
            properties["query"] = LOG_QUERIES[properties["title"]].format(environment=environment)
        if properties.get("title") == "일기 생성: DB 최종 확정 수" and properties.get("metrics"):
            if not any(row[-1].get("id") == "unexpected" for row in properties["metrics"]):
                properties["metrics"].append(metric(NAMESPACES[environment], "GenerationUnexpectedFailureLogs",
                                                    id="unexpected", label="예상 밖 실패", stat="Sum"))
        if properties.get("title") == "API RPS (완료한 요청/초)":
            properties["yAxis"] = copy.deepcopy(RPS_Y_AXIS)
        elif properties.get("title") == "DB 연결 획득 시간":
            properties["yAxis"] = copy.deepcopy(DB_ACQUIRE_Y_AXIS)
        elif properties.get("title") in ("Java 힙 사용량", "Java 실제 메모리 (RSS)"):
            properties["yAxis"] = copy.deepcopy(MEMORY_MIB_Y_AXIS)
        elif properties.get("title") == "EC2 Network":
            properties.update(metrics=[
                metric("AWS/EC2", "NetworkIn", ("InstanceId", instance_id),
                       stat="Sum", id="networkinbytes", visible=False),
                metric("AWS/EC2", "NetworkOut", ("InstanceId", instance_id),
                       stat="Sum", id="networkoutbytes", visible=False),
                expression("networkinbytes/1000000", "수신 (MB)", id="networkinmb", color="#1f77b4"),
                expression("networkoutbytes/1000000", "송신 (MB)", id="networkoutmb", color="#ff7f0e"),
            ], yAxis={"left": {"label": "전송량 (MB, 구간 합계)", "showUnits": False, "min": 0}})
        if environment == "dev":
            title = properties.get("title", "")
            if title in ("API 응답 시간 (전체)", "API 응답 시간 (일반)", "API 응답 시간 (일기 생성)"):
                properties["yAxis"] = {
                    "left": {"label": "응답 시간 (ms)", "showUnits": False},
                    "right": {"label": "요청 수", "showUnits": False},
                }
            elif title == "작업별 TPS (성공한 작업/초)":
                properties["yAxis"] = {"left": {"label": "성공한 작업/초", "showUnits": False}}
            elif title in ("Java 힙 외 메모리", "Java 힙과 힙 외 메모리"):
                properties["yAxis"] = copy.deepcopy(MEMORY_MIB_Y_AXIS)
            elif title == "생성 실행 평균 시간 (5분·초)":
                properties.update(title="생성 실행 평균 시간 (초)", period=60)
            elif properties.get("markdown", "").startswith("### 지표와 알림 안내"):
                properties["markdown"] = GENERATION_NOTICE
    additions = [] if has_completed_layout(dashboard, environment) else [
                 item for item in service_widgets(environment, instance_id)
                 if item["properties"]["title"] not in titles
                 and not (item["properties"]["title"] == "Java 힙과 힙 외 메모리" and "Java 힙 외 메모리" in titles)]
    if not has_completed_layout(dashboard, environment) and "Nginx 502/504 응답 수" not in titles:
        additions.append(nginx_gateway_widget(environment))
    for item in retained:
        for row in item.get("properties", {}).get("metrics", []):
            for index in range(len(row) - 1):
                if row[index] == "InstanceId" and row[index + 1] == "i-00000000000000000":
                    row[index + 1] = instance_id
    dashboard["widgets"] = retained + additions
    return arrange_dashboard(dashboard, environment)


def backup_status_widget():
    instance_id = json.loads(Path(__file__).with_name("targets.json").read_text())["prod"]["db_instance_id"]
    item = widget("DB 백업 상태 (1 정상, 0 주의)", [
        metric("Harudle/Prod", "BackupWithin24Hours", ("InstanceId", instance_id),
               stat="Minimum", label="최근 24시간 안에 성공한 백업"),
    ])
    item.update(x=20, y=0, width=4, height=4)
    item["properties"].update(view="singleValue", start="-PT24H", period=300,
                              sparkline=False, setPeriodToTimeRange=True)
    return item


def arrange_dashboard(original, environment):
    """Reorder existing observations; never infer DB health from EC2 health."""
    dashboard = copy.deepcopy(original)
    if has_completed_layout(dashboard, environment):
        return dashboard
    for item in dashboard['widgets']:
        props = item.get('properties', {})
        if props.get('title') != '백엔드 HTTP: 완료 수와 5xx 수':
            continue
        props['title'] = '백엔드 HTTP 응답 수'
        for row in props.get('metrics', []):
            if not row or not isinstance(row[-1], dict):
                continue
            if row[-1].get('label') == '전체 HTTP 완료':
                row[-1]['label'] = '전체 응답 수'
            elif row[-1].get('label') == '5xx 완료':
                row[-1]['label'] = '5xx 오류 응답 수'
    summary_titles = {'5xx 비율', '5xx 비율 (5분 20건 이상)', '5xx 비율 (선택 기간)', '일반 API P95 (ms)',
                      '생성 실패 (5분)', '생성 실패 (선택 기간)',
                      '앱 DB 연결 대기 최댓값', 'DB 데이터 여유 공간 (GiB)', 'GC 최근 최대 정지 (ms)',
                      '마지막 백업 성공 후 경과 시간 (시간)', '백업 상태 (1 정상, 0 주의)',
                      '백엔드 5xx 비율 (선택 기간)', '최근 DB 연결 대기 최댓값'}
    if environment == 'prod':
        summary_titles.update({'DB 백업 상태 (1 정상, 0 실패)', 'DB 백업 상태 (1 정상, 0 주의)'})
    widgets = [w for w in dashboard['widgets']
               if w.get('properties', {}).get('title', '') not in summary_titles
               and w.get('properties', {}).get('title') not in (
                   'DB 연결 풀: 대기 요청 최댓값', 'External Failure Trend')
               and not w.get('properties', {}).get('title', '').startswith('요약: ')
               and not w.get('properties', {}).get('markdown', '').startswith(('### 백업 감시:', '### 백업 확인'))]
    if environment == "prod":
        widgets = [w for w in widgets if w.get('properties', {}).get('title') not in {
            'API 응답 시간 (전체)', 'API 응답 시간 (일반)', 'API 응답 시간 (일기 생성)',
            '작업별 TPS (성공한 작업/초)'}]
    by_title = {w['properties'].get('title'): w for w in widgets if w['type'] == 'metric'}
    if environment == 'dev' and 'API 응답 시간 (일반)' not in by_title:
        p95_card = next((w for w in dashboard['widgets']
                         if w.get('properties', {}).get('title') == '일반 API P95 (ms)'), None)
        if p95_card:
            by_title['API 응답 시간 (일반)'] = p95_card
    if '백엔드 HTTP: 5xx 비율' not in by_title:
        rate_card = next((w for w in dashboard['widgets']
                          if w.get('properties', {}).get('title') in (
                              '5xx 비율 (선택 기간)', '백엔드 5xx 비율 (선택 기간)')), None)
        if rate_card:
            by_title['백엔드 HTTP: 5xx 비율'] = rate_card
    summary = []
    for title, label, select in (('백엔드 HTTP: 5xx 비율', '5xx 비율 (선택 기간)', None), ('API 응답 시간 (일반)', '일반 API P95 (ms)', 'p95'), ('일기 생성: DB 최종 확정 수', '생성 실패 (선택 기간)', 'failed'), ('DB 연결 대기와 시간 초과', '앱 DB 연결 대기 최댓값', 'pending'), ('DB EC2 Free Storage (GiB)', 'DB 데이터 여유 공간 (GiB)', 'data')):
        if title not in by_title:
            continue
        item = copy.deepcopy(by_title[title])
        props = item['properties']
        label = {'5xx 비율 (선택 기간)': '백엔드 5xx 비율 (선택 기간)',
                 '앱 DB 연결 대기 최댓값': '최근 DB 연결 대기 최댓값'}.get(label, label)
        props.update(title=label, view='singleValue', sparkline=True, setPeriodToTimeRange=False)
        props.pop('annotations', None)
        if select is None:
            rate = next(row[0] for row in props['metrics']
                        if isinstance(row[0], dict) and row[0].get('id') == 'r')
            rate.update(expression='TIME_SERIES(100*SUM(e)/SUM(t))',
                        label='선택 기간 전체 비율 (%)')
        elif select == 'p95':
            props['metrics'] = [r for r in props['metrics'] if r[-1].get('stat') == 'p95']
            if environment == 'dev':
                props.pop('yAxis', None)
        elif select == 'failed':
            props['metrics'] = [row for row in props['metrics'] if row[-1].get('id') != 'unexpected']
            props['metrics'][0][-1]['visible'] = False
            failed = next(row[0] for row in props['metrics']
                          if isinstance(row[0], dict) and row[0].get('id') == 'failed')
            ids = [row[-1]['id'] for row in props['metrics']
                   if isinstance(row[0], str) and row[-1].get('id', '').startswith('f')]
            failed.update(expression='TIME_SERIES(' + '+'.join(f'SUM({id})' for id in ids) + ')',
                          label='선택 기간 생성 실패 건수')
        elif select == 'pending':
            props['metrics'] = props['metrics'][:1]
        elif select == 'data':
            props['metrics'] = props['metrics'][:1]
            props['view'] = 'timeSeries'
        item.update(x=len(summary) * 4, y=0, width=4, height=4)
        summary.append(item)
    widgets = [w for w in widgets if w['properties'].get('title') != '백엔드 HTTP: 5xx 비율']
    if environment == 'dev':
        widgets = [w for w in widgets if w['properties'].get('title') not in DEV_REMOVED_TITLES]
    if environment == 'prod':
        notice = ('### 백업 확인\n매일 한국 시간 오전 5시에 한 번 확인합니다. 확인 시점의 최근 24시간 안에 성공한 백업이 있으면 1(정상), '
                  '없거나 정보를 읽지 못하면 0(주의)입니다. 표본이 없으면 확인되지 않은 상태입니다. '
                  '오전 5시에도 백업 중이면 완료 후 별도 확인이 필요합니다. WAL 전송과 실제 복원 가능 여부, 실패 알림은 별도 확인 대상입니다.')
        summary.append(backup_status_widget())
    elif environment == 'dev' and 'GC 정지 시간과 횟수' in by_title:
        item = copy.deepcopy(by_title['GC 정지 시간과 횟수'])
        item['properties'].update(title='GC 최근 최대 정지 (ms)', view='singleValue', sparkline=True)
        item['properties']['metrics'] = item['properties']['metrics'][:1]
        summary.append(item)
    if environment == 'dev':
        for index, item in enumerate(summary):
            item.update(x=index % 3 * 8, y=index // 3 * 5, width=8, height=5)
    groups = {name: [] for name in ('errors', 'api', 'generation', 'db', 'java', 'app', 'dbhost', 'logs', 'other')}
    for item in widgets:
        props = item['properties']
        title = props.get('title', '')
        if item['type'] == 'text' and props.get('markdown', '').startswith('## '):
            continue
        if item['type'] == 'alarm' or title.startswith(('백엔드 HTTP', 'Nginx ', 'Gemini:', 'S3:', '일기 생성:')):
            group = 'errors'
        elif item['type'] == 'log':
            group = 'logs'
        elif title.startswith(('API ', '작업별 TPS', '조회 TPS', '쓰기 TPS')):
            group = 'api'
        elif title.startswith(('DB 연결', 'DB EC2 Free Storage', '백업 정보 조회')):
            group = 'db'
        elif title.startswith(('Java ', 'GC ')):
            group = 'java'
        elif title.startswith(('DB EC2', 'DB Data EBS')):
            group = 'dbhost'
        elif title.startswith('EC2 '):
            group = 'app'
        elif item['type'] == 'text' or title.startswith(('생성 실행', '생성 시작', '생성 대기', '생성 접수', '평균 시간의', 'Gemini 관측', '정리')):
            group = 'generation'
        else:
            group = 'other'
        groups[group].append(item)
    check_title = "백업 정보 조회 성공 여부 (1 성공, 0 실패)"
    if environment == "prod" and check_title not in by_title:
        instance_id = json.loads(Path(__file__).with_name("targets.json").read_text())["prod"]["db_instance_id"]
        check = widget(check_title, [metric("Harudle/Prod", "BackupInfoCheckSuccess",
                       ("InstanceId", instance_id), stat="Minimum", label="조회 성공 여부")])
        check["properties"].update(period=300, yAxis={"left":{"min":0,"max":1}})
        groups["db"].append(check)
    order = [('errors', '서비스 오류와 알림'), ('api', 'API 속도와 요청 수'), ('generation', '일기 생성과 외부 API'), ('db', 'DB 연결과 데이터 저장 공간')]
    if environment == 'dev':
        order += [('java', 'Dev 부하 테스트 분석: Java 메모리와 GC')]
    order += [('dbhost', 'DB EC2 자원과 EBS'), ('app', '앱 EC2 자원')]
    if environment == 'prod':
        order += [('java', 'Prod 원인 확인: Java 메모리')]
    order += [('other', '추가 진단'), ('logs', '상세 로그')]
    result = summary
    y = 10 if environment == 'dev' else 4
    for name, label in order:
        items = groups[name]
        if environment == 'dev' and name == 'api':
            items.sort(key=lambda item: not item['properties'].get('title', '').startswith('API 경로별 P95'))
        if name == 'logs':
            items.sort(key=lambda item: item['properties'].get('title') != 'Gemini & S3 Failures')
        if not items:
            continue
        result.append({'type': 'text', 'x': 0, 'y': y, 'width': 24, 'height': 1, 'properties': {'markdown': f'## {label}'}})
        y += 1
        if name == 'db' and environment == 'prod':
            result.append({'type': 'text', 'x': 0, 'y': y, 'width': 24, 'height': 3, 'properties': {'markdown': notice}})
            y += 3
        x = 0
        row_height = 0
        for item in items:
            width = 24 if item['type'] in ('text', 'alarm') or item.get('width') == 24 else 12
            if name == 'logs' and item['properties'].get('title') == 'Gemini & S3 Failures':
                width = 24
            height = item.get('height', 6)
            if name == 'dbhost' and item['properties'].get('title') in (
                    'DB EC2 CPU', 'DB EC2 Available Memory'):
                width, height = 6, 6
            if name == 'app' and item['properties'].get('view') == 'singleValue' and item['properties'].get('title') in (
                    'EC2 CPU', 'EC2 Available Memory', 'EC2 Swap Used', 'EC2 Root Disk'):
                width, height = 6, 4
            if x + width > 24:
                y += row_height
                x = 0
                row_height = 0
            item.update(x=x, y=y, width=width, height=height)
            result.append(item)
            x += width
            row_height = max(row_height, height)
            if x == 24:
                y += row_height
                x = 0
                row_height = 0
        y += row_height
    dashboard['widgets'] = result
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
    parser.add_argument("--instance-id", help="App EC2 override; defaults to targets.json")
    parser.add_argument("--dashboard", type=Path, required=True,
                        help="CloudWatch View/edit source JSON or AWS get-dashboard JSON output")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.instance_id is None:
        targets = json.loads(Path(__file__).with_name("targets.json").read_text())
        args.instance_id = targets[args.environment]["instance_id"]
    if not re.fullmatch(r"i-[0-9a-f]{8}(?:[0-9a-f]{9})?", args.instance_id) or set(args.instance_id[2:]) == {"0"}:
        parser.error("실제 EC2 InstanceId를 입력하세요.")
    try:
        original = read_dashboard(args.dashboard, args.environment)
        dashboard = prepare_dashboard(original, args.environment, args.instance_id)
    except (OSError, ValueError) as error:
        parser.error(f"대시보드 JSON을 읽을 수 없습니다: {error}")
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
