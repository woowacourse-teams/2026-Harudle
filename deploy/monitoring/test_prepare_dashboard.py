import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from prepare_dashboard import (metric_filters, nginx_gateway_widget, prepare_dashboard, read_dashboard,
                               service_widgets, arrange_dashboard, LOG_QUERIES)


INSTANCE_ID = "i-0123456789abcdef0"


class DashboardTests(unittest.TestCase):
    def test_dev_removes_unneeded_graphs_but_keeps_p95_card_p99_graph_and_routes(self):
        source = {"widgets": service_widgets("dev", INSTANCE_ID)}
        result = prepare_dashboard(source, "dev", INSTANCE_ID)
        titles = {w['properties'].get('title') for w in result['widgets']}
        self.assertFalse(titles & {'API 응답 시간 (전체)', 'API 응답 시간 (일반)',
                                   'API 응답 시간 (일기 생성)', '작업별 TPS (성공한 작업/초)',
                                   '평균 시간의 실행 표본 수', 'Gemini 관측 토큰 total',
                                   'CodeBuild Gradle cache logs'})
        self.assertFalse(any(w['properties'].get('markdown', '').startswith('### 백업 감시')
                             for w in result['widgets']))
        self.assertTrue({'일반 API P95 (ms)', 'API RPS (완료한 요청/초)',
                         'API 응답 시간 P95·P99 (일반·일기 생성)',
                         'API 경로별 P95 응답 시간 (일반 상위 5개)',
                         'API 경로별 P95 응답 시간 (일기 생성 전체)'}.issubset(titles))
        self.assertEqual(prepare_dashboard(result, 'dev', INSTANCE_ID), result)

    def test_saved_dev_configuration_can_be_prepared_without_changes(self):
        root = Path(__file__).parent
        source = json.loads((root / "dashboards/dev.json").read_text())
        instance_id = json.loads((root / "targets.json").read_text())["dev"]["instance_id"]
        self.assertEqual(prepare_dashboard(source, "dev", instance_id), source)
        routes = [w for w in source["widgets"] if w["properties"].get("title", "").startswith("API 경로별 P95")]
        self.assertEqual(len(routes), 2)
        self.assertEqual([(w["x"], w["width"]) for w in routes], [(0, 12), (12, 12)])
        self.assertEqual(routes[0]["y"], routes[1]["y"])
        self.assertNotIn("Harudle/Prod", json.dumps(source))
        self.assertEqual(len(source["widgets"]), 56)
        cards = source["widgets"][:6]
        self.assertEqual([(w["x"], w["y"], w["width"], w["height"]) for w in cards],
                         [(x, y, 8, 5) for y in (0, 5) for x in (0, 8, 16)])
        by_title = {w["properties"].get("title"): w for w in source["widgets"]}
        title = "API 응답 시간 P95·P99 (일반·일기 생성)"
        expected = next(w for w in service_widgets("dev", instance_id) if w["properties"]["title"] == title)
        self.assertEqual(by_title[title]["properties"], expected["properties"])
        self.assertEqual([row[-1]["stat"] for row in expected["properties"]["metrics"]],
                         ["p95", "p99", "p95", "p99"])
        self.assertEqual([row[3] for row in expected["properties"]["metrics"]],
                         ["general", "general", "generation", "generation"])
        self.assertEqual((by_title["API RPS (완료한 요청/초)"]["x"], by_title[title]["x"]), (0, 12))
        self.assertEqual(by_title["API RPS (완료한 요청/초)"]["y"], by_title[title]["y"])

    def test_saved_prod_configuration_has_the_p99_graph_next_to_rps(self):
        root = Path(__file__).parent
        source = json.loads((root / "dashboards/prod.json").read_text())
        instance_id = json.loads((root / "targets.json").read_text())["prod"]["instance_id"]
        self.assertEqual(len(source["widgets"]), 57)
        by_title = {w["properties"].get("title"): w for w in source["widgets"]}
        title = "API 응답 시간 P95·P99 (일반·일기 생성)"
        expected = next(w for w in service_widgets("prod", instance_id) if w["properties"]["title"] == title)
        self.assertEqual(by_title[title]["properties"], expected["properties"])
        self.assertTrue(all(row[0] == "Harudle/Prod" for row in expected["properties"]["metrics"]))
        self.assertEqual((by_title["API RPS (완료한 요청/초)"]["x"], by_title[title]["x"]), (0, 12))
        self.assertEqual(by_title["API RPS (완료한 요청/초)"]["y"], by_title[title]["y"])
        titles = [w["properties"].get("title") for w in prepare_dashboard(source, "prod", instance_id)["widgets"]]
        self.assertEqual(titles, [w["properties"].get("title") for w in source["widgets"]])

    def test_saved_dashboards_show_gateway_errors_and_unexpected_generation_failures(self):
        root = Path(__file__).parent
        for environment, namespace in (("dev", "Harudle/Dev"), ("prod", "Harudle/Prod")):
            source = json.loads((root / f"dashboards/{environment}.json").read_text())
            by_title = {w["properties"].get("title"): w for w in source["widgets"]}
            gateway = by_title["Nginx 502/504 응답 수"]
            self.assertEqual(gateway["properties"], nginx_gateway_widget(environment)["properties"])
            self.assertEqual((gateway["x"], gateway["y"], gateway["width"]), (0, 28, 12))
            rows = by_title["일기 생성: DB 최종 확정 수"]["properties"]["metrics"]
            self.assertEqual(rows[-1], [namespace, "GenerationUnexpectedFailureLogs",
                                        {"id": "unexpected", "label": "예상 밖 실패", "stat": "Sum"}])
            prepared = prepare_dashboard(source, environment, INSTANCE_ID)
            self.assertEqual(json.dumps(prepared).count("GenerationUnexpectedFailureLogs"), 1)
            cells = set()
            for item in source["widgets"]:
                for x in range(item["x"], item["x"] + item["width"]):
                    for y in range(item["y"], item["y"] + item["height"]):
                        self.assertNotIn((x, y), cells)
                        cells.add((x, y))

    def test_generation_failure_card_excludes_the_unexpected_failure_line(self):
        metrics = [["Harudle/Dev", "harudle_generation_finalizations_total", {"id": "s"}],
                   ["Harudle/Dev", "harudle_generation_finalizations_total", {"id": "f0", "visible": False}],
                   [{"expression": "f0", "id": "failed"}]]
        source = {"widgets": [{"type": "metric", "height": 6, "properties": {
            "title": "일기 생성: DB 최종 확정 수", "metrics": metrics}}]}
        result = prepare_dashboard(source, "dev", INSTANCE_ID)
        by_title = {w["properties"].get("title"): w for w in result["widgets"]}
        self.assertIn("GenerationUnexpectedFailureLogs", json.dumps(by_title["일기 생성: DB 최종 확정 수"]))
        self.assertNotIn("GenerationUnexpectedFailureLogs", json.dumps(by_title["생성 실패 (선택 기간)"]))
        self.assertIn("Nginx 502/504 응답 수", by_title)
        self.assertEqual(prepare_dashboard(result, "dev", INSTANCE_ID), result)

    def test_gateway_filter_reads_the_frontend_nginx_log_group(self):
        for environment, namespace in (("dev", "Harudle/Dev"), ("prod", "Harudle/Prod")):
            item = next(f for f in metric_filters(environment)
                        if f["filterName"] == f"harudle-{environment}-nginx-gateway-error")
            self.assertEqual(item["logGroupName"], f"/harudle/{environment}/frontend-nginx")
            self.assertIn("status = 502 || status = 504", item["filterPattern"])
            self.assertEqual(item["metricTransformations"], [{
                "metricNamespace": namespace, "metricName": "NginxGatewayErrorLogs",
                "metricValue": "1", "defaultValue": 0}])

    def test_dev_keeps_a_completed_console_layout_and_added_cards(self):
        root = Path(__file__).parent
        source = json.loads((root / "dashboards/dev.json").read_text())
        source["widgets"].append({"type": "metric", "x": 12, "y": 500, "width": 6, "height": 5, "properties": {
            "title": "콘솔에서 추가한 카드", "metrics": [["Custom", "Value"]]}})
        instance_id = json.loads((root / "targets.json").read_text())["dev"]["instance_id"]
        self.assertEqual(prepare_dashboard(source, "dev", instance_id), source)

    def test_dev_p95_uses_the_prod_number_card_format_without_changing_values(self):
        source = {"widgets": [w for w in service_widgets("dev", INSTANCE_ID)
                              if w["properties"]["title"] == "API 응답 시간 (일반)"]}
        result = prepare_dashboard(source, "dev", INSTANCE_ID)
        card = next(w for w in result["widgets"] if w["properties"].get("title") == "일반 API P95 (ms)")
        self.assertEqual(card["properties"]["view"], "singleValue")
        self.assertTrue(card["properties"]["sparkline"])
        self.assertFalse(card["properties"]["setPeriodToTimeRange"])
        self.assertEqual(card["properties"]["metrics"], [
            ["Harudle/Dev", "ApiResponseTimeMs", "ApiGroup", "general", {"stat": "p95", "label": "P95 (ms)"}]])
        self.assertNotIn("yAxis", card["properties"])
        self.assertEqual(prepare_dashboard(result, "dev", INSTANCE_ID), result)

    def test_completed_prod_console_layout_keeps_p95_failure_and_external_cards(self):
        source = {"start": "-PT6H", "periodOverride": "auto", "widgets": [
            {"type": "metric", "x": index * 8 if index < 3 else (index - 3) * 6,
             "y": 0 if index < 3 else 5, "width": 8 if index < 3 else 6, "height": 5,
             "properties": {"title": title, "view": "singleValue", "metrics": [
                 ["Harudle/Prod", "existing", {"stat": "Maximum"}]]}}
            for index, title in enumerate(("백엔드 5xx 비율 (선택 기간)", "일반 API P95 (ms)",
                                           "생성 실패", "최근 DB 연결 대기 최댓값",
                                           "DB 데이터 여유 공간 (GiB)", "DB 백업 상태 (1 정상, 0 주의)"))
        ] + [
            {"type": "alarm", "x": 0, "y": 11, "width": 24, "height": 5,
             "properties": {"title": "prod 경보", "alarms": ["unchanged-alarm"]}},
            {"type": "metric", "x": 18, "y": 5, "width": 6, "height": 5,
             "properties": {"title": "R2 백업 상태 (최근 24h)", "view": "gauge", "start": "-PT24H",
                            "metrics": [["Harudle/Prod", "R2BackupStatus"]],
                            "yAxis": {"left": {"min": -1, "max": 2}}}},
            {"type": "text", "x": 0, "y": 16, "width": 24, "height": 2,
             "properties": {"markdown": "콘솔에서 추가한 안내"}},
        ]}
        unchanged = copy.deepcopy(source)
        result = prepare_dashboard(source, "prod", INSTANCE_ID)
        self.assertEqual(result, unchanged)
        self.assertEqual(arrange_dashboard(source, "prod"), unchanged)
        self.assertEqual(prepare_dashboard(result, "prod", INSTANCE_ID), result)
        self.assertEqual(source, unchanged)
        self.assertNotIn("생성 실패 (선택 기간)", [w["properties"].get("title") for w in result["widgets"]])

    def test_resource_cards_fit_one_row_without_changing_observations(self):
        source = {"widgets": [
            {"type": "metric", "width": 12, "height": 4, "properties": {
                "title": title, "view": "singleValue", "metrics": [["original", title]]}}
            for title in ("DB EC2 CPU", "DB EC2 Available Memory", "EC2 CPU",
                          "EC2 Available Memory", "EC2 Swap Used", "EC2 Root Disk")
        ] + [{"type": "metric", "width": 12, "height": 6, "properties": {
            "title": "DB EC2 CPU Utilization", "view": "timeSeries", "metrics": [["original", "CPU"]]}}]}
        for environment in ("dev", "prod"):
            result = arrange_dashboard(source, environment)
            by_title = {w["properties"].get("title"): w for w in result["widgets"]}
            app_cards = [by_title[t] for t in ("EC2 CPU", "EC2 Available Memory", "EC2 Swap Used", "EC2 Root Disk")]
            self.assertEqual([(w["x"], w["width"], w["height"]) for w in app_cards],
                             [(0, 6, 4), (6, 6, 4), (12, 6, 4), (18, 6, 4)])
            self.assertEqual(len({w["y"] for w in app_cards}), 1)
            db_row = [by_title[t] for t in ("DB EC2 CPU", "DB EC2 Available Memory", "DB EC2 CPU Utilization")]
            self.assertEqual([(w["x"], w["width"], w["height"]) for w in db_row],
                             [(0, 6, 6), (6, 6, 6), (12, 12, 6)])
            self.assertEqual(len({w["y"] for w in db_row}), 1)
            for original in source["widgets"]:
                self.assertEqual(by_title[original["properties"]["title"]]["properties"], original["properties"])
            self.assertEqual(arrange_dashboard(result, environment), result)

    def test_dev_repairs_units_and_labels_without_losing_load_test_observations(self):
        titles = ("생성 실행 평균 시간 (5분·초)", "평균 시간의 실행 표본 수 (5분)",
                  "Gemini 관측 토큰 total (5분)", "DB 연결 획득 시간", "Java 힙 사용량",
                  "작업별 TPS (성공한 작업/초)", "DB 연결 풀: 대기 요청 최댓값")
        original = {"widgets": [{"type": "metric", "height": 6, "properties": {
            "title": title, "period": 300, "metrics": [[{"expression": "original", "id": "e0"}]]}}
            for title in titles]}
        result = prepare_dashboard(original, "dev", INSTANCE_ID)
        props = {w["properties"].get("title"): w["properties"] for w in result["widgets"]}
        title = "생성 실행 평균 시간 (초)"
        self.assertEqual(props[title]["period"], 60)
        self.assertEqual(props[title]["metrics"], original["widgets"][0]["properties"]["metrics"])
        self.assertNotIn("평균 시간의 실행 표본 수", props)
        self.assertNotIn("Gemini 관측 토큰 total", props)
        self.assertNotIn("DB 연결 풀: 대기 요청 최댓값", props)
        self.assertEqual(props["DB 연결 획득 시간"]["yAxis"]["left"]["label"], "밀리초 (ms)")
        self.assertEqual(props["Java 힙 사용량"]["yAxis"]["left"]["label"], "메모리 (MiB)")
        self.assertNotIn("작업별 TPS (성공한 작업/초)", props)
        self.assertIn("GC 정지 시간과 횟수", props)
        self.assertIn("Java 힙과 힙 외 메모리", props)
        self.assertFalse(any("BackupWithin24Hours" in str(w) for w in result["widgets"]))
        self.assertEqual(prepare_dashboard(result, "dev", INSTANCE_ID), result)

    def test_external_failures_use_one_full_width_detail_table_in_both_environments(self):
        original = {"widgets": [
            {"type": "log", "width": 12, "height": 6,
             "properties": {"title": title, "query": "original query", "view": "table"}}
            for title in ("Backend Internal Errors", "Gemini & S3 Failures",
                          "External Failure Trend", "OAuth Login Events")
        ]}
        prod = prepare_dashboard(original, "prod", INSTANCE_ID)
        logs = [w for w in prod["widgets"] if w["type"] == "log"]
        self.assertEqual([w["properties"]["title"] for w in logs], [
            "Gemini & S3 Failures", "Backend Internal Errors", "OAuth Login Events"])
        table = logs[0]
        self.assertEqual((table["x"], table["width"]), (0, 24))
        self.assertEqual(table["properties"]["query"], "original query")
        self.assertTrue(all(w["y"] >= table["y"] + table["height"] for w in logs[1:]))
        self.assertEqual(prepare_dashboard(prod, "prod", INSTANCE_ID), prod)
        dev_logs = [w for w in prepare_dashboard(original, "dev", INSTANCE_ID)["widgets"]
                    if w["type"] == "log"]
        self.assertEqual([w["properties"]["title"] for w in dev_logs],
                         [w["properties"]["title"] for w in logs])
        self.assertEqual((dev_logs[0]["x"], dev_logs[0]["width"]), (0, 24))
        self.assertTrue(all(w["width"] == 12 for w in dev_logs[1:]))

    def test_repairs_known_log_queries_for_each_environment_without_fixing_the_time_range(self):
        original = {"widgets": [
            {"type": "log", "properties": {"title": title, "region": "ap-northeast-2",
             "view": "timeSeries" if title == "External Failure Trend" else "table", "query": "old query"}}
            for title in [*LOG_QUERIES, "Backend Internal Errors"]
        ]}
        unchanged = copy.deepcopy(original)
        for environment in ("prod", "dev"):
            result = prepare_dashboard(original, environment, INSTANCE_ID)
            logs = {w["properties"]["title"]: w["properties"]
                    for w in result["widgets"] if w["type"] == "log"}
            for source in original["widgets"]:
                expected = copy.deepcopy(source["properties"])
                title = expected["title"]
                if title in LOG_QUERIES:
                    expected["query"] = LOG_QUERIES[title].format(environment=environment)
                    self.assertNotIn("START=", expected["query"])
                    self.assertNotIn("END=", expected["query"])
                self.assertEqual(logs[title], expected)
            self.assertEqual(prepare_dashboard(result, environment, INSTANCE_ID), result)
        self.assertEqual(original, unchanged)

    def test_preserves_existing_widgets_and_only_repairs_known_placeholder_targets(self):
        original = {"start": "-PT6H", "periodOverride": "inherit", "widgets": [
            {"type": "text", "x": 0, "y": 0, "height": 2, "properties": {"markdown": "기존 안내"}},
            {"type": "metric", "x": 0, "y": 2, "height": 6, "properties": {"title": "기존 CPU", "metrics": [
                ["AWS/EC2", "CPUUtilization", "InstanceId", "i-00000000000000000"],
                ["AWS/EC2", "CPUUtilization", "InstanceId", "i-11111111111111111"],
            ]}},
        ]}
        unchanged = copy.deepcopy(original)
        result = prepare_dashboard(original, "prod", INSTANCE_ID)

        self.assertEqual(original, unchanged)
        self.assertEqual(result["start"], original["start"])
        self.assertEqual(result["periodOverride"], "auto")
        rows = next(w for w in result["widgets"] if w["properties"].get("title") == "기존 CPU")["properties"]["metrics"]
        self.assertEqual(rows[0][3], INSTANCE_ID)
        self.assertEqual(rows[1][3], "i-11111111111111111")
        self.assertEqual(prepare_dashboard(result, "prod", INSTANCE_ID), result)

    def test_latency_uses_raw_samples_with_no_zero_defaults_and_bounded_groups(self):
        dev_filters = metric_filters("dev")
        prod_filters = metric_filters("prod")
        self.assertEqual(len([f for f in dev_filters if f["metricTransformations"][0].get("unit") == "Milliseconds"]), 3)
        for filters in (dev_filters, prod_filters):
            durations = [item for item in filters if item["metricTransformations"][0].get("unit") == "Milliseconds"]
            for item in durations:
                transformation = item["metricTransformations"][0]
                self.assertEqual(transformation["metricValue"], "$.durationMs")
                self.assertNotIn("defaultValue", transformation)
                self.assertIn("$.durationMs >= 0", item["filterPattern"])
            self.assertNotIn("dimensions", durations[0]["metricTransformations"][0])
            self.assertEqual(durations[1]["metricTransformations"][0]["dimensions"], {"ApiGroup": "$.apiGroup"})
            self.assertIn('$.apiGroup = "general"', durations[1]["filterPattern"])
            self.assertIn('$.apiGroup = "generation"', durations[1]["filterPattern"])
        route_filter = next(f for f in prod_filters
                            if f["metricTransformations"][0]["metricName"] == "ApiResponseTimeByRouteMs")
        self.assertEqual(route_filter["filterName"], "harudle-prod-ApiResponseTimeByRouteMs-by-group-route")
        self.assertIn('$.apiRoute = "*"', route_filter["filterPattern"])
        self.assertEqual(route_filter["metricTransformations"][0]["dimensions"], {
            "ApiGroup": "$.apiGroup", "ApiRoute": "$.apiRoute"})
        self.assertEqual(dev_filters[-2]["metricTransformations"][0],
                         {**route_filter["metricTransformations"][0], "metricNamespace": "Harudle/Dev"})

    def test_dev_tps_requires_committed_writes_or_successful_read_responses(self):
        filters = {item["metricTransformations"][0]["metricName"]: item for item in metric_filters("dev")}
        self.assertIn('$.event = "generation_finalized" && $.status = "SUCCEEDED"',
                      filters["DiaryGenerationSucceeded"]["filterPattern"])
        self.assertIn('$.event = "feed_publication_succeeded"', filters["FeedPublicationSucceeded"]["filterPattern"])
        for name, operation in (("FeedListSucceeded", "feed_list"), ("FeedDetailSucceeded", "feed_detail")):
            self.assertIn('$.event = "api_request_completed"', filters[name]["filterPattern"])
            self.assertIn(f'$.operation = "{operation}"', filters[name]["filterPattern"])
            self.assertIn("$.httpStatus = 200", filters[name]["filterPattern"])
            self.assertEqual(filters[name]["metricTransformations"][0]["metricValue"], "1")
        self.assertFalse({"DiaryGenerationSucceeded", "FeedPublicationSucceeded",
                          "FeedListSucceeded", "FeedDetailSucceeded"}
                         & {item["metricTransformations"][0]["metricName"] for item in metric_filters("prod")})

    def test_route_p95_is_available_in_both_environments_and_dev_adds_p99_graph(self):
        dev_widgets = service_widgets("dev", INSTANCE_ID)
        self.assertEqual([w["properties"]["title"] for w in dev_widgets[:6]], [
            "API 응답 시간 (전체)", "API 응답 시간 (일반)", "API 응답 시간 (일기 생성)",
            "API RPS (완료한 요청/초)", "API 응답 시간 P95·P99 (일반·일기 생성)", "작업별 TPS (성공한 작업/초)",
        ])
        self.assertEqual(len(metric_filters("dev")), 8)
        dev_routes = dev_widgets[-2:]
        self.assertEqual([w["properties"]["title"] for w in dev_routes], [
            "API 경로별 P95 응답 시간 (일반 상위 5개)", "API 경로별 P95 응답 시간 (일기 생성 전체)"])
        for item in dev_routes:
            self.assertIn("Harudle/Dev,ApiGroup,ApiRoute", json.dumps(item))
            self.assertNotIn("Harudle/Prod", json.dumps(item))
            self.assertEqual(item["properties"]["yAxis"]["left"]["label"], "응답 시간 (ms)")
        self.assertTrue(dev_routes[0]["properties"]["metrics"][0][0]["expression"].endswith(", MAX, DESC, 5)"))

        prod_widgets = service_widgets("prod", INSTANCE_ID)
        self.assertEqual([w["properties"]["title"] for w in prod_widgets[:6]], [
            "API 응답 시간 (일반)", "API 경로별 P95 응답 시간 (일반 상위 5개)",
            "API 경로별 P95 응답 시간 (일기 생성 전체)", "API RPS (완료한 요청/초)",
            "API 응답 시간 P95·P99 (일반·일기 생성)", "DB 연결 획득 시간",
        ])
        self.assertNotIn("작업별 TPS (성공한 작업/초)",
                         [w["properties"]["title"] for w in prod_widgets])
        for item, group in zip(prod_widgets[1:3], ("general", "generation")):
            row = item["properties"]["metrics"][0]
            self.assertIn(f'ApiGroup="{group}"', row[0]["expression"])
            self.assertEqual(row[0]["label"], "${PROP('Dim.ApiRoute')}")
        self.assertEqual(prod_widgets[1]["properties"]["title"], "API 경로별 P95 응답 시간 (일반 상위 5개)")
        self.assertIn("SORT(SEARCH(", prod_widgets[1]["properties"]["metrics"][0][0]["expression"])
        self.assertTrue(prod_widgets[1]["properties"]["metrics"][0][0]["expression"].endswith(", MAX, DESC, 5)"))
        self.assertEqual(prod_widgets[2]["properties"]["title"], "API 경로별 P95 응답 시간 (일기 생성 전체)")
        self.assertTrue(prod_widgets[2]["properties"]["metrics"][0][0]["expression"].startswith("SEARCH("))
        self.assertEqual(len(metric_filters("prod")), 4)

    def test_both_environments_remove_existing_tps_widgets(self):
        tps = {"type": "metric", "properties": {"title": "작업별 TPS (성공한 작업/초)", "metrics": []}}
        prod = prepare_dashboard({"widgets": [copy.deepcopy(tps)]}, "prod", INSTANCE_ID)
        dev = prepare_dashboard({"widgets": [copy.deepcopy(tps)]}, "dev", INSTANCE_ID)

        self.assertNotIn("작업별 TPS (성공한 작업/초)",
                         [w["properties"].get("title") for w in prod["widgets"]])
        self.assertNotIn("작업별 TPS (성공한 작업/초)",
                      [w["properties"].get("title") for w in dev["widgets"]])

    def test_gc_and_nonheap_widgets_are_dev_only_and_use_converted_counters(self):
        self.assertEqual(len(service_widgets("prod", INSTANCE_ID)), 9)
        widgets = service_widgets("dev", INSTANCE_ID)
        self.assertEqual(len(widgets), 14)
        gc, memory = widgets[10:12]
        self.assertIn("jvm_gc_pause_seconds_max", json.dumps(gc))
        self.assertIn("1000*MAX(", json.dumps(gc))
        for row, name in zip(gc["properties"]["metrics"][1:], ("sum", "count")):
            formula = row[0]["expression"]
            self.assertIn(f"jvm_gc_pause_seconds_{name}", formula)
            self.assertIn("'Sum'", formula)
            self.assertNotIn("DIFF(", formula)
            self.assertNotIn("RATE(", formula)
        self.assertIn('area=\\"nonheap\\"', json.dumps(memory))
        self.assertIn("jvm_memory_committed_bytes", json.dumps(memory))
        prepared = prepare_dashboard({"widgets": []}, "dev", INSTANCE_ID)
        self.assertEqual(prepare_dashboard(prepared, "dev", INSTANCE_ID), prepared)

    def test_dashboard_uses_sample_percentiles_period_based_rates_and_matching_resource_units(self):
        widgets = service_widgets("dev", INSTANCE_ID)
        for item in widgets[:3]:
            self.assertEqual([row[-1]["stat"] for row in item["properties"]["metrics"]],
                             ["Average", "p95", "p99", "SampleCount"])
        rps = widgets[3]["properties"]["metrics"]
        self.assertEqual(rps[0][-1]["stat"], "SampleCount")
        self.assertEqual(rps[1][0]["expression"], "requests/PERIOD(requests)")
        for row in widgets[5]["properties"]["metrics"][1::2]:
            self.assertIn("/PERIOD(", row[0]["expression"])
        self.assertIn("IF(acquires>0,1000*seconds/acquires)", json.dumps(widgets[6]))
        self.assertIn('area="heap"', widgets[8]["properties"]["metrics"][0][0]["expression"])
        rss = widgets[9]["properties"]["metrics"][0][0]["expression"]
        self.assertIn('Namespace="CWAgent"', rss)
        self.assertIn('Namespace="Harudle/Prod"', service_widgets("prod", INSTANCE_ID)[8]["properties"]["metrics"][0][0]["expression"])
        self.assertIn(f'InstanceId="{INSTANCE_ID}"', rss)
        self.assertIn(r"\(", rss)
        self.assertIn("/1048576", rss)
        for item in widgets:
            ids = [row[-1]["id"] for row in item["properties"]["metrics"] if "id" in row[-1]]
            self.assertEqual(len(ids), len(set(ids)))

    def test_rps_axis_shows_rate_units_in_both_environments(self):
        prod_rps = service_widgets("prod", INSTANCE_ID)[3]
        self.assertEqual(prod_rps["properties"]["yAxis"], {
            "left": {"label": "요청/초", "showUnits": False},
        })
        self.assertEqual(service_widgets("dev", INSTANCE_ID)[3]["properties"]["yAxis"],
                         prod_rps["properties"]["yAxis"])

        existing = {"widgets": [{"type": "metric", "properties": {
            "title": "API RPS (완료한 요청/초)", "metrics": [],
        }}]}
        prepared = prepare_dashboard(existing, "prod", INSTANCE_ID)
        rps = next(item for item in prepared["widgets"]
                   if item["properties"].get("title") == "API RPS (완료한 요청/초)")
        self.assertEqual(rps["properties"]["yAxis"], prod_rps["properties"]["yAxis"])

    def test_cli_rejects_other_environment_and_generates_reviewable_files_without_aws(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = root / "before.json"
            output = root / "result"
            previous = {"DashboardArn": "arn:aws:cloudwatch::123456789012:dashboard/DASHBOARD-harudle-prod",
                        "DashboardBody": json.dumps({"widgets": []})}
            before.write_text(json.dumps(previous))
            command = [sys.executable, str(Path(__file__).with_name("prepare_dashboard.py")),
                       "--environment", "dev", "--instance-id", INSTANCE_ID,
                       "--dashboard", str(before), "--output", str(output)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())
            previous["DashboardArn"] = previous["DashboardArn"].replace("prod", "dev")
            before.write_text(json.dumps(previous))
            result = subprocess.run(command, capture_output=True, text=True, check=True)
            self.assertEqual(len(list(output.glob("*.json"))), 9)
            self.assertEqual(json.loads(before.read_text()), previous)
            self.assertIn("GC 정지 시간과 횟수", (output / "dashboard.json").read_text())

    def test_cli_accepts_console_source_and_preserves_widgets_and_variables_for_both_environments(self):
        for environment in ("dev", "prod"):
            with self.subTest(environment=environment), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                before = root / "before.json"
                output = root / "result"
                original = {"start": "-PT6H", "variables": [
                    {"type": "property", "property": "InstanceId", "inputType": "input",
                     "id": "ec2InstanceId", "defaultValue": INSTANCE_ID},
                ], "widgets": [
                    {"type": "text", "x": 0, "y": 0, "height": 2, "properties": {"markdown": "기존 안내"}},
                ]}
                before.write_text(json.dumps(original, ensure_ascii=False), encoding="utf-8")
                saved_source = before.read_bytes()
                command = [sys.executable, str(Path(__file__).with_name("prepare_dashboard.py")),
                           "--environment", environment, "--instance-id", INSTANCE_ID,
                           "--dashboard", str(before), "--output", str(output)]

                subprocess.run(command, capture_output=True, text=True, check=True)

                result = json.loads((output / "dashboard.json").read_text())
                self.assertEqual(before.read_bytes(), saved_source)
                self.assertEqual(result["start"], original["start"])
                self.assertTrue(any(w["properties"].get("markdown") == "기존 안내" for w in result["widgets"]))
                self.assertNotIn("variables", result)
                self.assertEqual(len(list(output.glob("*.json"))), 1 + len(metric_filters(environment)))
                for index, expected in enumerate(metric_filters(environment), start=1):
                    self.assertEqual(json.loads((output / f"metric-filter-{index}.json").read_text()), expected)
                before.write_text(json.dumps(result), encoding="utf-8")
                subprocess.run(command, capture_output=True, text=True, check=True)
                self.assertEqual(json.loads((output / "dashboard.json").read_text()), result)

    def test_rejects_old_db_metrics_and_keeps_existing_formulas(self):
        with self.assertRaisesRegex(ValueError, "EC2 PostgreSQL"):
            prepare_dashboard({"widgets":[{"type":"metric","properties":{"metrics":[["AWS/RDS","CPUUtilization"]]}}]}, "dev", INSTANCE_ID)
        original={"widgets":[{"type":"metric","height":6,"properties":{"title":"Java 힙 사용량","metrics":[[{"expression":"existing_formula"}]]}}]}
        result=prepare_dashboard(original,"dev",INSTANCE_ID)
        existing=next(w for w in result['widgets'] if w['properties'].get('title')=='Java 힙 사용량')
        self.assertEqual(existing['properties']['metrics'],original['widgets'][0]['properties']['metrics'])
        self.assertEqual(existing['properties']['yAxis']['left']['label'], '메모리 (MiB)')
        self.assertEqual(prepare_dashboard(result,'dev',INSTANCE_ID),result)

    def test_layout_preserves_metrics_queries_and_alarm_actions_without_overlap(self):
        for env in ('dev','prod'):
            original={'widgets':service_widgets(env,INSTANCE_ID)+[
                {'type':'alarm','width':24,'height':5,'properties':{'title':'기존 경보','alarms':['unchanged-arn']}},
                {'type':'log','width':24,'height':8,'properties':{'title':'기존 검색','query':'unchanged query'}}]}
            result=arrange_dashboard(original,env)
            if env == 'dev':
                for before in original['widgets']:
                    if before['properties'].get('title') in {
                            'API 응답 시간 (전체)', 'API 응답 시간 (일반)', 'API 응답 시간 (일기 생성)',
                            '작업별 TPS (성공한 작업/초)'}:
                        continue
                    self.assertIn(before['properties'],[w['properties'] for w in result['widgets']])
            else:
                self.assertIn('API 경로별 P95 응답 시간 (일반 상위 5개)',
                              [w['properties'].get('title') for w in result['widgets']])
                self.assertIn('API 경로별 P95 응답 시간 (일기 생성 전체)',
                              [w['properties'].get('title') for w in result['widgets']])
            self.assertEqual(arrange_dashboard(result,env),result)
            for i,a in enumerate(result['widgets']):
                for b in result['widgets'][i+1:]:
                    self.assertTrue(a['x']+a['width']<=b['x'] or b['x']+b['width']<=a['x'] or
                                    a['y']+a['height']<=b['y'] or b['y']+b['height']<=a['y'])

    def test_http_response_widget_uses_clear_response_labels(self):
        source = {'widgets': [{'type': 'metric', 'properties': {
            'title': '백엔드 HTTP: 완료 수와 5xx 수',
            'metrics': [
                ['Harudle/Prod', 'http_server_requests_seconds_count',
                 {'stat': 'Sum', 'label': '전체 HTTP 완료'}],
                ['Harudle/Prod', 'http_server_requests_seconds_count', 'outcome', 'SERVER_ERROR',
                 {'stat': 'Sum', 'label': '5xx 완료'}],
            ],
        }}]}
        result = arrange_dashboard(source, 'prod')
        item = next(w for w in result['widgets']
                    if w.get('properties', {}).get('title') == '백엔드 HTTP 응답 수')

        self.assertEqual(item['properties']['title'], '백엔드 HTTP 응답 수')
        self.assertEqual([row[-1]['label'] for row in item['properties']['metrics']],
                         ['전체 응답 수', '5xx 오류 응답 수'])
        self.assertEqual(source['widgets'][0]['properties']['title'], '백엔드 HTTP: 완료 수와 5xx 수')

    def test_keeps_selected_period_5xx_card_in_both_environments(self):
        for environment in ('dev', 'prod'):
            namespace = f'Harudle/{environment.title()}'
            source = {'widgets': [
                {'type': 'metric', 'width': 12, 'height': 6, 'properties': {
                    'title': '백엔드 HTTP: 5xx 비율',
                    'metrics': [
                        [namespace, 'http_server_requests_seconds_count', 'outcome', 'SERVER_ERROR',
                         {'id': 'e', 'stat': 'Sum'}],
                        [namespace, 'http_server_requests_seconds_count', {'id': 't', 'stat': 'Sum'}],
                        [{'id': 'r', 'expression': 'IF(t>=20,100*e/t,0)', 'label': '5xx 비율 (%)'}],
                    ],
                }},
                {'type': 'metric', 'width': 12, 'height': 6, 'properties': {
                    'title': '백엔드 HTTP 응답 수',
                    'metrics': [[namespace, 'http_server_requests_seconds_count', {'stat': 'Sum'}]],
                }},
            ]}

            result = arrange_dashboard(source, environment)
            titles = {w['properties'].get('title') for w in result['widgets']}

            self.assertNotIn('백엔드 HTTP: 5xx 비율', titles)
            self.assertIn('백엔드 HTTP 응답 수', titles)
            rate_card = next(w for w in result['widgets']
                             if w['properties'].get('title') == (
                                 '백엔드 5xx 비율 (선택 기간)'))
            self.assertEqual(rate_card['properties']['metrics'][-1][0]['expression'],
                             'TIME_SERIES(100*SUM(e)/SUM(t))')
            self.assertEqual(rate_card['properties']['metrics'][0][0], namespace)
            self.assertEqual(arrange_dashboard(result, environment), result)

    def test_summary_hides_generation_success_and_keeps_pending_separate_from_timeouts(self):
        source={"widgets":[
            {"type":"metric","height":6,"properties":{"title":"일기 생성: DB 최종 확정 수","metrics":[
                ["Harudle/Prod","success",{"id":"s"}],
                ["Harudle/Prod","failure",{"id":"f0","visible":False}],
                ["Harudle/Prod","failure",{"id":"f1","visible":False}],
                [{"expression":"SUM([f0,f1])","id":"failed"}]]}},
            {"type":"metric","height":6,"properties":{"title":"DB 연결 대기와 시간 초과","metrics":[
                ["Harudle/Prod","pending",{}], ["Harudle/Prod","timeouts",{}]]}}]}
        result=arrange_dashboard(source,'prod')
        cards={w['properties'].get('title'):w['properties'] for w in result['widgets']}
        failures = cards['생성 실패 (선택 기간)']
        self.assertFalse(failures['metrics'][0][-1]['visible'])
        self.assertEqual(failures['metrics'][-1][0]['expression'],
                         'TIME_SERIES(SUM(f0)+SUM(f1))')
        self.assertEqual(failures['metrics'][-1][0]['label'], '선택 기간 생성 실패 건수')
        self.assertEqual(len(cards['최근 DB 연결 대기 최댓값']['metrics']),1)
        dev_cards = {w['properties'].get('title') for w in arrange_dashboard(source, 'dev')['widgets']}
        self.assertIn('최근 DB 연결 대기 최댓값', dev_cards)
        self.assertEqual(arrange_dashboard(result, 'prod'), result)
        self.assertNotIn('visible',source['widgets'][0]['properties']['metrics'][0][-1])

    def test_daily_backup_status_is_prod_only_and_never_fills_missing_results(self):
        prod = arrange_dashboard({"widgets": []}, "prod")
        self.assertEqual(arrange_dashboard(prod, "prod"), prod)
        cards = {w["properties"].get("title"): w["properties"] for w in prod["widgets"]}
        status = cards["DB 백업 상태 (1 정상, 0 주의)"]
        self.assertEqual(status["view"], "singleValue")
        self.assertEqual(status["start"], "-PT24H")
        self.assertEqual(status["period"], 300)
        self.assertTrue(status["setPeriodToTimeRange"])
        self.assertEqual(status["metrics"][0][-1]["stat"], "Minimum")
        self.assertFalse(status["sparkline"])
        target = json.loads(Path(__file__).with_name("targets.json").read_text())["prod"]["db_instance_id"]
        self.assertEqual(status["metrics"][0][:4], ["Harudle/Prod", "BackupWithin24Hours", "InstanceId", target])
        self.assertNotIn("FILL", json.dumps(status))
        self.assertNotIn("BackupAgeSeconds", json.dumps(prod))
        self.assertNotIn("BackupWithin24Hours", json.dumps(arrange_dashboard({"widgets": []}, "dev")))

    def test_rejects_incomplete_console_source(self):
        for source in ("{", "null", "[]", "{}", '{"widgets": {}}', '{"widgets": [null]}',
                       '{"widgets": [{"properties": []}]}'):
            with self.subTest(source=source), tempfile.TemporaryDirectory() as directory:
                before = Path(directory) / "before.json"
                before.write_text(source)
                with self.assertRaises(ValueError):
                    read_dashboard(before, "dev")

    def test_cli_does_not_overwrite_input_dashboard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = root / "dashboard.json"
            before.write_text('{"widgets": []}')
            saved_source = before.read_bytes()
            command = [sys.executable, str(Path(__file__).with_name("prepare_dashboard.py")),
                       "--environment", "dev", "--instance-id", INSTANCE_ID,
                       "--dashboard", str(before), "--output", str(root)]

            result = subprocess.run(command, capture_output=True, text=True)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("원본 JSON과 생성할 파일의 경로를 다르게 지정하세요.", result.stderr)
            self.assertEqual(before.read_bytes(), saved_source)
            self.assertEqual(list(root.iterdir()), [before])


if __name__ == "__main__":
    unittest.main()
