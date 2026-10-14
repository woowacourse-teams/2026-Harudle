"""Operational regression checks for the bounded CloudWatch scrape contract.

Uses only the Python standard library. Run from deploy/monitoring after copying,
or set HARUDLE_MONITORING_CONFIG_DIR to the directory containing the three files.
These checks do not simulate CloudWatch ingestion or prove its counter conversion;
the deployment check must confirm raw endpoint types and EMF delta records.
"""

import copy
import json
import os
from pathlib import Path
import re
import unittest


CONFIG_DIR = Path(os.environ.get("HARUDLE_MONITORING_CONFIG_DIR", Path(__file__).parent))

# This is the existing production contract. An expansion must preserve it.
EXISTING_DIMENSIONS = {
    "harudle_generation_executions_total": [["job", "result"]],
    "harudle_generation_finalizations_total": [["job", "status", "errorCode"]],
    "harudle_generation_unexpected_failures_total": [["job", "phase"]],
    "harudle_gemini_stage_calls_total": [["job", "stage", "outcome", "failureType"]],
    "harudle_s3_operation_calls_total": [["job", "operation", "result", "failureType"]],
    "harudle_s3_url_signs_total": [["job", "result", "failureType"]],
    "harudle_image_load_failures_total": [["job", "surface"]],
    "http_server_requests_seconds_count": [["job"], ["job", "outcome"], ["job", "status"]],
    "hikaricp_connections_pending": [["job", "pool"]],
}
NEW_DIMENSIONS = {
    "harudle_generation_duration_seconds_sum": [["job", "result"]],
    "harudle_generation_duration_seconds_count": [["job", "result"]],
    "harudle_gemini_tokens_total": [["job", "stage", "kind"]],
}
DEV_METRICS = {
    "hikaricp_connections_acquire_seconds_sum": "Seconds",
    "hikaricp_connections_acquire_seconds_count": "Count",
    "hikaricp_connections_acquire_seconds_max": "Seconds",
    "hikaricp_connections_timeout_total": "Count",
    "jvm_memory_used_bytes": "Bytes",
}


def read_processor(environment):
    configuration = json.loads((CONFIG_DIR / f"cloudwatch-agent.{environment}.json").read_text())
    prometheus = configuration["logs"]["metrics_collected"]["prometheus"]
    return configuration, prometheus, prometheus["emf_processor"]


def matching_declarations(processor, name):
    return [
        declaration
        for declaration in processor["metric_declaration"]
        if any(re.fullmatch(selector, name) for selector in declaration["metric_selectors"])
    ]


class MonitoringConfigTests(unittest.TestCase):
    def test_preserves_existing_metric_dimensions_and_units(self):
        for environment in ("dev", "prod"):
            _, _, processor = read_processor(environment)
            for name, dimensions in EXISTING_DIMENSIONS.items():
                with self.subTest(environment=environment, metric=name):
                    declarations = matching_declarations(processor, name)
                    self.assertEqual(len(declarations), 1)
                    self.assertEqual(declarations[0]["dimensions"], dimensions)
                    self.assertEqual(processor["metric_unit"][name], "Count")

    def test_new_metrics_have_same_result_dimensions_and_correct_units(self):
        for environment in ("dev", "prod"):
            _, _, processor = read_processor(environment)
            self.assertTrue(processor["metric_declaration_dedup"])
            for name, dimensions in NEW_DIMENSIONS.items():
                with self.subTest(environment=environment, metric=name):
                    declarations = matching_declarations(processor, name)
                    self.assertEqual(len(declarations), 1)
                    self.assertEqual(declarations[0]["dimensions"], dimensions)
                    self.assertEqual(declarations[0]["source_labels"], ["job"])
                    self.assertEqual(declarations[0]["label_matcher"], "^harudle-backend$")
                    self.assertEqual(
                        processor["metric_unit"][name],
                        "Seconds" if name.endswith("_sum") else "Count",
                    )
            expected = EXISTING_DIMENSIONS.keys() | NEW_DIMENSIONS.keys()
            if environment == "dev":
                expected |= DEV_METRICS.keys()
            self.assertEqual(set(processor["metric_unit"]), expected)

    def test_dev_connection_metrics_preserve_pool_and_heap_preserves_memory_pool(self):
        _, _, dev = read_processor("dev")
        _, _, prod = read_processor("prod")
        for name, unit in DEV_METRICS.items():
            with self.subTest(metric=name):
                declarations = matching_declarations(dev, name)
                self.assertEqual(len(declarations), 1)
                declaration = declarations[0]
                self.assertEqual(dev["metric_unit"][name], unit)
                self.assertEqual(matching_declarations(prod, name), [])
                if name == "jvm_memory_used_bytes":
                    self.assertEqual(declaration["dimensions"], [["job", "area", "id"]])
                    self.assertEqual(declaration["source_labels"], ["job", "area"])
                    self.assertIsNotNone(re.fullmatch(declaration["label_matcher"], "harudle-backend;heap"))
                    self.assertIsNone(re.fullmatch(declaration["label_matcher"], "harudle-backend;nonheap"))
                    self.assertIsNone(re.fullmatch(declaration["label_matcher"], "another-app;heap"))
                else:
                    self.assertEqual(declaration["dimensions"], [["job", "pool"]])
                    self.assertEqual(declaration["source_labels"], ["job"])
                    self.assertEqual(declaration["label_matcher"], "^harudle-backend$")

    def test_metric_filter_keeps_required_series_and_drops_histograms_and_noise(self):
        source = (CONFIG_DIR / "prometheus.yaml").read_text()
        regexes = re.findall(r"^\s+regex:\s*'([^']+)'\s*$", source, re.MULTILINE)
        self.assertEqual(len(regexes), 2, "Review relabel rules if the scrape contract changes")
        keep = re.compile(regexes[0])
        drop = re.compile(regexes[1])
        self.assertRegex(source, r"scrape_interval:\s*1m\b")
        self.assertRegex(source, r"scrape_timeout:\s*10s\b")
        self.assertRegex(source, r"targets:\s*\n\s*- 127\.0\.0\.1:19091\s*$|targets:\s*\n\s*- 127\.0\.0\.1:19091\s*\n")
        self.assertRegex(source, r"source_labels:\s*\[__name__\]")
        self.assertRegex(source, r"action:\s*keep\b")
        self.assertRegex(source, r"source_labels:\s*\[__name__, area\]")
        self.assertRegex(source, r"action:\s*drop\b")
        for name in EXISTING_DIMENSIONS.keys() | NEW_DIMENSIONS.keys() | DEV_METRICS.keys():
            with self.subTest(metric=name):
                self.assertIsNotNone(keep.fullmatch(name))
                area = "heap" if name == "jvm_memory_used_bytes" else ""
                self.assertIsNone(drop.fullmatch(f"{name};{area}"))
        self.assertIsNotNone(drop.fullmatch("jvm_memory_used_bytes;nonheap"))
        for name in (
            "harudle_generation_duration_seconds_bucket",
            "harudle_generation_duration_seconds",  # Quantiles are not request samples.
            "harudle_generation_duration_seconds_max",
            "harudle_generation_duration_seconds_sum_unbounded_suffix",
            "harudle_gemini_tokens_total_created",
            "http_server_requests_seconds_bucket",
            "jvm_gc_pause_seconds_count",
            "jvm_memory_committed_bytes",
            "hikaricp_connections_acquire_seconds_bucket",
            "hikaricp_connections_acquire_seconds",  # No quantile samples.
            "hikaricp_connections_acquire_seconds_max_unbounded_suffix",
            "hikaricp_connections_usage_seconds_sum",
            "hikaricp_connections_timeout_total_created",
            "process_cpu_usage",
            "untrusted_user_id",
        ):
            with self.subTest(metric=name):
                self.assertIsNone(keep.fullmatch(name))
                for environment in ("dev", "prod"):
                    self.assertEqual(matching_declarations(read_processor(environment)[2], name), [])

    def test_dev_additions_preserve_common_app_and_host_configuration(self):
        normalized = []
        for environment, namespace in (("dev", "Harudle/Dev"), ("prod", "Harudle/Prod")):
            configuration, prometheus, processor = read_processor(environment)
            self.assertEqual(prometheus["log_group_name"], f"/harudle/{environment}/prometheus-emf")
            self.assertEqual(processor["metric_namespace"], namespace)
            self.assertEqual(prometheus["prometheus_config_path"], "/opt/harudle/monitoring/prometheus.yaml")
            item = copy.deepcopy(configuration)
            if environment == "dev":
                metrics = item.pop("metrics")
                self.assertEqual(metrics["namespace"], "CWAgent")
                self.assertEqual(metrics["append_dimensions"], {"InstanceId": "${aws:InstanceId}"})
                # Add only procstat; retain host CPU/memory/disk fragments on append-config.
                self.assertEqual(set(metrics["metrics_collected"]), {"procstat"})
                item_processor = item["logs"]["metrics_collected"]["prometheus"]["emf_processor"]
                for name in DEV_METRICS:
                    del item_processor["metric_unit"][name]
                item_processor["metric_declaration"] = [
                    declaration for declaration in item_processor["metric_declaration"]
                    if not any(declaration in matching_declarations(processor, name) for name in DEV_METRICS)
                ]
            self.assertEqual(set(item), {"logs"})
            item["logs"]["metrics_collected"]["prometheus"]["log_group_name"] = "ENVIRONMENT"
            item["logs"]["metrics_collected"]["prometheus"]["emf_processor"]["metric_namespace"] = "ENVIRONMENT"
            normalized.append(item)
        self.assertEqual(*normalized)

    def test_procstat_selects_backend_java_without_counting_parent_or_other_java(self):
        configuration, _, _ = read_processor("dev")
        processes = configuration["metrics"]["metrics_collected"]["procstat"]
        self.assertEqual(len(processes), 1)
        process = processes[0]
        self.assertEqual(set(process), {"pattern", "measurement", "metrics_collection_interval"})
        self.assertEqual(process["measurement"], ["memory_rss"])
        self.assertEqual(process["metrics_collection_interval"], 60)
        pattern = re.compile(process["pattern"])
        for command in (
            "java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar /app/app.jar",
            "/usr/lib/jvm/java-21/bin/java -jar /app/app.jar",
            "java -jar /app/app.jar --spring.profiles.active=prod",
        ):
            with self.subTest(command=command):
                self.assertIsNotNone(pattern.search(command))
        for command in (
            "/sbin/tini -- sh -c exec java $JAVA_OPTS -jar /app/app.jar",
            "sh -c exec java -jar /app/app.jar",
            "java -jar /app/other.jar",
            "java -jar /app/app.jar.backup",
            "java /tmp/Probe.java",
            "pgrep -af java -jar /app/app.jar",
        ):
            with self.subTest(command=command):
                self.assertIsNone(pattern.search(command))


if __name__ == "__main__":
    unittest.main()
