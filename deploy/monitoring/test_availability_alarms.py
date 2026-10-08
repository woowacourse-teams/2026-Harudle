"""Contract checks for the two bounded availability log alarms.

Uses only the Python standard library. Run with unittest discovery, or set
HARUDLE_MONITORING_CONFIG_DIR to the directory containing the alarm payloads.
Exact predicate checks protect the approved event scope and OR grouping. These
checks do not emulate AWS filter matching: native TestMetricFilter validation
with positive and negative JSON events is still required before activation.
"""

import copy
import json
import os
from pathlib import Path
import unittest


CONFIG_DIR = Path(os.environ.get("HARUDLE_MONITORING_CONFIG_DIR", Path(__file__).parent))
METRICS = {
    "gemini-request-failure": "GeminiRequestFailureLogs",
    "image-fallback-unavailable": "ImageFallbackUnavailableLogs",
}
EXPECTED_PATTERNS = {
    # operation is the structured-log field; stage is a Prometheus metric tag.
    "gemini-request-failure": (
        '{ $.event = "external_api_failure" && $.provider = "gemini" '
        '&& ($.operation = "storyboard_generation" || $.operation = "image_generation") '
        '&& ($.failureType = "AUTH_CONFIGURATION_ERROR" || $.failureType = "REQUEST_REJECTED" '
        '|| $.failureType = "REQUEST_PREPARATION_ERROR" || $.failureType = "INLINE_REQUEST_TOO_LARGE") }'
    ),
    # A recovered R2 URL or an inconclusive S3 lookup is not an unavailable image.
    "image-fallback-unavailable": (
        '{ $.event = "image_url_selected" && $.s3Result = "MISSING" '
        '&& ($.result = "S3_FALLBACK" || $.result = "FAILED") }'
    ),
}
EXPECTED_TAGS = {
    "Service": "techcourse",
    "Role": "techcourse-etc",
    "ProjectTeam": "harudle",
}
FILTER_FIELDS = {"logGroupName", "filterName", "filterPattern", "metricTransformations"}
ALARM_FIELDS = {
    "AlarmName", "AlarmDescription", "ActionsEnabled", "AlarmActions", "OKActions",
    "InsufficientDataActions", "MetricName", "Namespace", "Statistic", "Dimensions",
    "Period", "EvaluationPeriods", "DatapointsToAlarm", "Threshold", "ComparisonOperator",
    "TreatMissingData", "Unit", "Tags",
}


def read_payloads(environment):
    return json.loads((CONFIG_DIR / f"availability-alarms.{environment}.json").read_text())


def suffix_of(payload, environment):
    return payload["putMetricAlarm"]["AlarmName"].removeprefix(f"harudle-{environment}-")


def native_filter_fixtures():
    """Labeled synthetic events for AWS TestMetricFilter; no local filter parser.

    Each case has a JSON message and the expected AWS match result. Submit the
    message values to TestMetricFilter and compare its eventNumber values with
    the indexes of cases where matches is true. No actual application IDs,
    object keys, buckets, URLs, request text, or credentials are included.
    """
    fixtures = {suffix: [] for suffix in METRICS}

    def add(suffix, name, event, matches):
        fixtures[suffix].append({
            "name": name, "message": json.dumps(event), "matches": matches,
        })

    gemini = {
        "event": "external_api_failure", "provider": "gemini",
        "operation": "storyboard_generation", "failureType": "AUTH_CONFIGURATION_ERROR",
    }
    for operation in ("storyboard_generation", "image_generation"):
        for failure in ("AUTH_CONFIGURATION_ERROR", "REQUEST_REJECTED",
                        "REQUEST_PREPARATION_ERROR", "INLINE_REQUEST_TOO_LARGE"):
            add("gemini-request-failure", f"{operation}-{failure}",
                gemini | {"operation": operation, "failureType": failure}, True)
    for field in gemini:
        add("gemini-request-failure", f"missing-{field}",
            {key: value for key, value in gemini.items() if key != field}, False)
    for field, values in {
        "event": ("generation_finalized",), "provider": ("s3", "r2"),
        "operation": ("other_operation",),
        "failureType": ("RATE_LIMIT", "PROVIDER_5XX", "TIMEOUT", "PROVIDER_ERROR",
                        "OUTPUT_TOKEN_LIMIT", "RESPONSE_PROCESSING_ERROR"),
    }.items():
        for value in values:
            add("gemini-request-failure", f"excluded-{field}-{value}",
                gemini | {field: value}, False)
    add("gemini-request-failure", "stage-cannot-replace-operation",
        {key: value for key, value in gemini.items() if key != "operation"}
        | {"stage": "storyboard_generation"}, False)

    image = {"event": "image_url_selected", "s3Result": "MISSING", "result": "S3_FALLBACK"}
    for result in ("S3_FALLBACK", "FAILED"):
        for r2_result in ("MISSING", "ERROR", "BUDGET_EXHAUSTED"):
            add("image-fallback-unavailable", f"missing-s3-{result}-{r2_result}",
                image | {"result": result, "r2Result": r2_result}, True)
        for s3_result in ("AVAILABLE", "ERROR", "BUDGET_EXHAUSTED", "NOT_CHECKED"):
            add("image-fallback-unavailable", f"unconfirmed-missing-{result}-{s3_result}",
                image | {"result": result, "s3Result": s3_result}, False)
    for field in image:
        add("image-fallback-unavailable", f"missing-{field}",
            {key: value for key, value in image.items() if key != field}, False)
    for result in ("R2", "S3"):
        add("image-fallback-unavailable", f"usable-url-{result}", image | {"result": result}, False)
    add("image-fallback-unavailable", "different-event",
        image | {"event": "image_backup_batch_completed"}, False)
    return fixtures


class AvailabilityAlarmTests(unittest.TestCase):
    def test_scope_is_exactly_two_log_metrics_and_alarms_per_environment(self):
        for environment in ("dev", "prod"):
            payloads = read_payloads(environment)
            with self.subTest(environment=environment):
                self.assertEqual(len(payloads), 2)
                self.assertEqual({suffix_of(item, environment) for item in payloads}, set(METRICS))
                for payload in payloads:
                    self.assertEqual(set(payload), {"putMetricFilter", "putMetricAlarm"})
                    self.assertEqual(set(payload["putMetricFilter"]), FILTER_FIELDS)
                    self.assertEqual(set(payload["putMetricAlarm"]), ALARM_FIELDS)

    def test_log_group_namespace_metric_and_alarm_names_stay_in_their_environment(self):
        for environment in ("dev", "prod"):
            namespace = f"Harudle/{environment.title()}"
            for payload in read_payloads(environment):
                suffix = suffix_of(payload, environment)
                metric_filter = payload["putMetricFilter"]
                alarm = payload["putMetricAlarm"]
                with self.subTest(environment=environment, alarm=suffix):
                    self.assertEqual(metric_filter["logGroupName"], f"/harudle/{environment}/backend")
                    self.assertEqual(metric_filter["filterName"], f"harudle-{environment}-{suffix}")
                    self.assertEqual(alarm["AlarmName"], metric_filter["filterName"])
                    self.assertEqual(alarm["Namespace"], namespace)
                    self.assertEqual(alarm["MetricName"], METRICS[suffix])
                    self.assertEqual(len(alarm["Tags"]), len(EXPECTED_TAGS))
                    self.assertEqual({tag["Key"]: tag["Value"] for tag in alarm["Tags"]}, EXPECTED_TAGS)

    def test_predicates_require_the_exact_event_provider_operations_and_failure_outcomes(self):
        for environment in ("dev", "prod"):
            for payload in read_payloads(environment):
                suffix = suffix_of(payload, environment)
                with self.subTest(environment=environment, alarm=suffix):
                    # Preserves AND/OR grouping as well as the allowlisted values.
                    actual = " ".join(payload["putMetricFilter"]["filterPattern"].split())
                    self.assertEqual(actual, EXPECTED_PATTERNS[suffix])

    def test_each_log_counts_once_without_identifiers_or_dimensions(self):
        for environment in ("dev", "prod"):
            for payload in read_payloads(environment):
                suffix = suffix_of(payload, environment)
                with self.subTest(environment=environment, alarm=suffix):
                    self.assertEqual(payload["putMetricFilter"]["metricTransformations"], [{
                        "metricName": METRICS[suffix],
                        "metricNamespace": f"Harudle/{environment.title()}",
                        "metricValue": "1",
                        "defaultValue": 0,
                        "unit": "Count",
                    }])
                    self.assertEqual(payload["putMetricAlarm"]["Dimensions"], [])

    def test_actions_are_disabled_and_only_reference_the_existing_environment_topic(self):
        for environment in ("dev", "prod"):
            topic = f"arn:aws:sns:ap-northeast-2:843255971531:harudle-{environment}-alerts"
            for payload in read_payloads(environment):
                alarm = payload["putMetricAlarm"]
                with self.subTest(environment=environment, alarm=alarm["AlarmName"]):
                    self.assertIs(alarm["ActionsEnabled"], False)
                    self.assertEqual(alarm["AlarmActions"], [topic])
                    self.assertEqual(alarm["OKActions"], [topic])
                    self.assertEqual(alarm["InsufficientDataActions"], [])

    def test_first_matching_event_alarms_in_five_minutes_and_missing_is_not_breaching(self):
        expected = {
            "Statistic": "Sum", "Period": 300, "EvaluationPeriods": 1,
            "DatapointsToAlarm": 1, "Threshold": 1,
            "ComparisonOperator": "GreaterThanOrEqualToThreshold",
            "TreatMissingData": "notBreaching", "Unit": "Count",
        }
        for environment in ("dev", "prod"):
            for payload in read_payloads(environment):
                alarm = payload["putMetricAlarm"]
                with self.subTest(environment=environment, alarm=alarm["AlarmName"]):
                    self.assertEqual({key: alarm[key] for key in expected}, expected)

    def test_dev_and_prod_differ_only_in_environment_bindings(self):
        normalized = []
        for environment in ("dev", "prod"):
            payloads = copy.deepcopy(read_payloads(environment))
            for payload in payloads:
                suffix = suffix_of(payload, environment)
                metric_filter = payload["putMetricFilter"]
                alarm = payload["putMetricAlarm"]
                metric_filter["logGroupName"] = "ENVIRONMENT"
                metric_filter["filterName"] = suffix
                metric_filter["metricTransformations"][0]["metricNamespace"] = "ENVIRONMENT"
                alarm["AlarmName"] = suffix
                alarm["Namespace"] = "ENVIRONMENT"
                alarm["AlarmActions"] = ["ENVIRONMENT"]
                alarm["OKActions"] = ["ENVIRONMENT"]
            normalized.append(sorted(payloads, key=lambda item: item["putMetricAlarm"]["AlarmName"]))
        self.assertEqual(*normalized)


if __name__ == "__main__":
    unittest.main()
