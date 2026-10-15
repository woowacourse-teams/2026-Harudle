"""Operational regression checks for the bounded CloudWatch scrape contract.

Uses only the Python standard library. Run in a checkout or copied deployment,
or set HARUDLE_MONITORING_CONFIG_DIR to its monitoring directory. Compose files
must be available in that directory's parent for the dev logging check.
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
RESOURCE_METRICS = {
    "hikaricp_connections_acquire_seconds_sum": "Seconds",
    "hikaricp_connections_acquire_seconds_count": "Count",
    "hikaricp_connections_acquire_seconds_max": "Seconds",
    "hikaricp_connections_timeout_total": "Count",
    "jvm_memory_used_bytes": "Bytes",
}
DEV_DIAGNOSTICS = {
    "jvm_memory_committed_bytes": "Bytes",
    "jvm_memory_max_bytes": "Bytes",
    "jvm_gc_pause_seconds_count": "Count",
    "jvm_gc_pause_seconds_sum": "Seconds",
    "jvm_gc_pause_seconds_max": "Seconds",
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
            expected |= RESOURCE_METRICS.keys()
            if environment == "dev":
                expected |= DEV_DIAGNOSTICS.keys()
            self.assertEqual(set(processor["metric_unit"]), expected)

    def test_connection_metrics_preserve_pool_and_heap_preserves_memory_pool(self):
        for environment in ("dev", "prod"):
            _, _, processor = read_processor(environment)
            for name, unit in RESOURCE_METRICS.items():
                with self.subTest(environment=environment, metric=name):
                    declarations = matching_declarations(processor, name)
                    self.assertEqual(len(declarations), 1)
                    declaration = declarations[0]
                    self.assertEqual(processor["metric_unit"][name], unit)
                    if name == "jvm_memory_used_bytes":
                        self.assertEqual(declaration["dimensions"], [["job", "area", "id"]])
                        self.assertEqual(declaration["source_labels"], ["job", "area"])
                        self.assertIsNotNone(re.fullmatch(declaration["label_matcher"], "harudle-backend;heap"))
                        matches_nonheap = re.fullmatch(declaration["label_matcher"], "harudle-backend;nonheap")
                        self.assertEqual(matches_nonheap is not None, environment == "dev")
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
        for name in EXISTING_DIMENSIONS.keys() | NEW_DIMENSIONS.keys() | RESOURCE_METRICS.keys():
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
                environments = ("prod",) if name in DEV_DIAGNOSTICS else ("dev", "prod")
                for environment in environments:
                    self.assertEqual(matching_declarations(read_processor(environment)[2], name), [])

    def test_dev_diagnostics_preserve_the_existing_collection_contract(self):
        normalized = []
        for environment, namespace in (("dev", "Harudle/Dev"), ("prod", "Harudle/Prod")):
            configuration, prometheus, processor = read_processor(environment)
            self.assertEqual(prometheus["log_group_name"], f"/harudle/{environment}/prometheus-emf")
            self.assertEqual(processor["metric_namespace"], namespace)
            suffix = ".dev" if environment == "dev" else ""
            self.assertEqual(prometheus["prometheus_config_path"], f"/opt/harudle/monitoring/prometheus{suffix}.yaml")
            item = copy.deepcopy(configuration)
            metrics = item["metrics"]
            self.assertEqual(metrics["namespace"], "CWAgent")
            self.assertEqual(metrics["append_dimensions"], {"InstanceId": "${aws:InstanceId}"})
            # Add only procstat; retain host CPU/memory/disk fragments on append-config.
            self.assertEqual(set(metrics["metrics_collected"]), {"procstat"})
            item["logs"]["metrics_collected"]["prometheus"]["log_group_name"] = "ENVIRONMENT"
            item["logs"]["metrics_collected"]["prometheus"]["emf_processor"]["metric_namespace"] = "ENVIRONMENT"
            if environment == "dev":
                # Strip only the deliberate dev additions before comparing the shared contract.
                native = item["metrics"]["metrics_collected"]["procstat"][0]
                self.assertEqual(native["metrics_collection_interval"], 10)
                native["metrics_collection_interval"] = 60
                prom = item["logs"]["metrics_collected"]["prometheus"]
                prom["prometheus_config_path"] = "/opt/harudle/monitoring/prometheus.yaml"
                emf = prom["emf_processor"]
                for name in DEV_DIAGNOSTICS:
                    del emf["metric_unit"][name]
                emf["metric_declaration"] = [d for d in emf["metric_declaration"]
                                              if d["metric_selectors"] != ["^jvm_gc_pause_seconds_(count|sum|max)$"]]
                memory = matching_declarations(emf, "jvm_memory_used_bytes")[0]
                memory["label_matcher"] = "^harudle-backend;heap$"
                memory["metric_selectors"] = ["^jvm_memory_used_bytes$"]
            normalized.append(item)
        self.assertEqual(*normalized)

    def test_dev_scrape_collects_gc_and_nonheap_without_identifiers_or_histograms(self):
        _, _, processor = read_processor("dev")
        source = (CONFIG_DIR / "prometheus.dev.yaml").read_text()
        regexes = re.findall(r"^\s+regex:\s*'([^']+)'\s*$", source, re.MULTILINE)
        self.assertEqual(len(regexes), 1)
        keep = re.compile(regexes[0])
        self.assertRegex(source, r"scrape_interval:\s*10s\b")
        self.assertRegex(source, r"scrape_timeout:\s*5s\b")
        self.assertIn("127.0.0.1:19091", source)
        for name in EXISTING_DIMENSIONS.keys() | NEW_DIMENSIONS.keys() | RESOURCE_METRICS.keys() | DEV_DIAGNOSTICS.keys():
            self.assertIsNotNone(keep.fullmatch(name), name)
        for name, unit in DEV_DIAGNOSTICS.items():
            declarations = matching_declarations(processor, name)
            self.assertEqual(len(declarations), 1)
            self.assertEqual(processor["metric_unit"][name], unit)
            expected = ["job", "gc", "action", "cause"] if name.startswith("jvm_gc_") else ["job", "area", "id"]
            self.assertEqual(declarations[0]["dimensions"], [expected])
        for name in ("jvm_gc_pause_seconds_bucket", "jvm_gc_pause_seconds", "jvm_gc_pause_seconds_sum_extra",
                     "jvm_memory_used_bytes_extra", "process_cpu_usage", "untrusted_user_id"):
            self.assertIsNone(keep.fullmatch(name))
            self.assertEqual(matching_declarations(processor, name), [])
        self.assertNotIn("traceId", json.dumps(processor))

    def test_gc_logging_is_dev_only_and_preserves_heap_and_collector_selection(self):
        deploy = CONFIG_DIR.parent
        source = (deploy / "compose.dev.yaml").read_text()
        self.assertIn("-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError", source)
        self.assertIn("-Xlog:gc*,safepoint:stdout:time,uptime,level,tags", source)
        self.assertNotRegex(source, r"-Xmx|-Xms|-XX:[+-]Use\w+GC|mem_limit")
        self.assertNotIn("-Xlog:", (deploy / "compose.prod.yaml").read_text())

    def test_procstat_selects_backend_java_without_counting_parent_or_other_java(self):
        configuration, _, _ = read_processor("prod")
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
