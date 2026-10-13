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
            # No additional custom-metric extraction or identifier dimensions.
            self.assertEqual(set(processor["metric_unit"]), EXISTING_DIMENSIONS.keys() | NEW_DIMENSIONS.keys())

    def test_metric_filter_keeps_required_series_and_drops_histograms_and_noise(self):
        source = (CONFIG_DIR / "prometheus.yaml").read_text()
        regexes = re.findall(r"^\s+regex:\s*'([^']+)'\s*$", source, re.MULTILINE)
        self.assertEqual(len(regexes), 1, "Review relabel rules if the scrape contract changes")
        keep = re.compile(regexes[0])
        self.assertRegex(source, r"scrape_interval:\s*1m\b")
        self.assertRegex(source, r"scrape_timeout:\s*10s\b")
        self.assertRegex(source, r"targets:\s*\n\s*- 127\.0\.0\.1:19091\s*$|targets:\s*\n\s*- 127\.0\.0\.1:19091\s*\n")
        self.assertRegex(source, r"source_labels:\s*\[__name__\]")
        self.assertRegex(source, r"action:\s*keep\b")
        for name in EXISTING_DIMENSIONS.keys() | NEW_DIMENSIONS.keys():
            with self.subTest(metric=name):
                self.assertIsNotNone(keep.fullmatch(name))
        for name in (
            "harudle_generation_duration_seconds_bucket",
            "harudle_generation_duration_seconds",  # Quantiles are not request samples.
            "harudle_generation_duration_seconds_max",
            "harudle_generation_duration_seconds_sum_unbounded_suffix",
            "harudle_gemini_tokens_total_created",
            "http_server_requests_seconds_bucket",
            "jvm_gc_pause_seconds_count",
            "process_cpu_usage",
            "untrusted_user_id",
        ):
            with self.subTest(metric=name):
                self.assertIsNone(keep.fullmatch(name))
                for environment in ("dev", "prod"):
                    self.assertEqual(matching_declarations(read_processor(environment)[2], name), [])

    def test_dev_and_prod_only_differ_in_namespace_and_log_group(self):
        normalized = []
        for environment, namespace in (("dev", "Harudle/Dev"), ("prod", "Harudle/Prod")):
            configuration, prometheus, processor = read_processor(environment)
            self.assertEqual(prometheus["log_group_name"], f"/harudle/{environment}/prometheus-emf")
            self.assertEqual(processor["metric_namespace"], namespace)
            self.assertEqual(prometheus["prometheus_config_path"], "/opt/harudle/monitoring/prometheus.yaml")
            # Application fragment must not replace host CPU/memory/disk settings.
            self.assertEqual(set(configuration), {"logs"})
            item = copy.deepcopy(configuration)
            item["logs"]["metrics_collected"]["prometheus"]["log_group_name"] = "ENVIRONMENT"
            item["logs"]["metrics_collected"]["prometheus"]["emf_processor"]["metric_namespace"] = "ENVIRONMENT"
            normalized.append(item)
        self.assertEqual(*normalized)


if __name__ == "__main__":
    unittest.main()
