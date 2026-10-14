"""Regression checks for the HTTP-only dashboard and alarms; no AWS calls."""

import json
import math
import unittest

import api_latency_resources as resources
import discord_forwarder as forwarder


class ApiLatencyResourcesTest(unittest.TestCase):
    def render(self, environment="dev", **kwargs):
        return resources.render(environment, f"harudle-{environment}-backend",
                                f"arn:aws:sns:ap-northeast-2:123456789012:harudle-{environment}-alerts",
                                1.0, 2.0, **kwargs)

    def test_alarms_preserve_quantile_and_match_the_request_population(self):
        for environment in ("dev", "prod"):
            _, alarms = self.render(environment)
            for alarm, percentile, minimum in zip(alarms, (95, 99), (100, 1000)):
                queries = {query["Id"]: query for query in alarm["Metrics"]}
                quantile = queries["q"]["MetricStat"]
                count = queries["c"]["MetricStat"]
                expected = resources.dimensions(f"harudle-{environment}-backend")
                self.assertEqual(quantile["Metric"]["Dimensions"],
                                 expected + [{"Name": "quantile", "Value": str(percentile / 100)}])
                self.assertEqual(count["Metric"]["Dimensions"], expected)
                self.assertEqual(quantile["Metric"]["Namespace"], f"Harudle/{environment.title()}")
                self.assertEqual(quantile["Stat"], "Maximum")
                self.assertEqual(count["Stat"], "Sum")
                self.assertEqual(quantile["Period"], count["Period"])
                self.assertEqual(queries["latency"]["Expression"], f"IF((c >= {minimum}) AND (q > 0), q)")
                self.assertEqual(alarm["EvaluationPeriods"], 2)
                self.assertEqual(alarm["DatapointsToAlarm"], 2)
                self.assertEqual(alarm["TreatMissingData"], "missing")
                self.assertFalse(alarm["ActionsEnabled"])
                self.assertNotIn("ExtendedStatistic", alarm)
                self.assertEqual(sum(query["ReturnData"] for query in queries.values()), 1)
                self.assertIn(f"api-p{percentile}", forwarder._ALARM_REASONS)

    def test_dashboard_keeps_all_and_endpoint_samples_separate_and_has_no_generation_metrics(self):
        dashboard, _ = self.render(endpoints=[("GET", "/api/v1/diaries/{diaryId}")])
        self.assertEqual(len(dashboard["widgets"]), 5)
        serialized = json.dumps(dashboard)
        self.assertNotIn("harudle_generation", serialized)
        self.assertNotIn("DIFF(", serialized)
        self.assertNotIn("RATE(", serialized)
        for widget in dashboard["widgets"][1:]:
            for metric in widget["properties"]["metrics"]:
                if isinstance(metric[0], str):
                    pairs = dict(zip(metric[2:-1:2], metric[3:-1:2]))
                    self.assertEqual(pairs["node"], "harudle-dev-backend")
                    self.assertIn(pairs["uri"], {"ALL", "/api/v1/diaries/{diaryId}"})
                    self.assertEqual(pairs["method"], "ALL" if pairs["uri"] == "ALL" else "GET")
        latency = dashboard["widgets"][1]["properties"]["metrics"]
        self.assertEqual(latency[-1][0]["expression"], "IF(c > 0, s / c)")
        self.assertEqual(latency[0][-1]["period"], 60)

    def test_rejects_mixed_environment_topics_invalid_thresholds_and_unbounded_labels(self):
        base = dict(environment="dev", node="harudle-dev-backend",
                    topic_arn="arn:aws:sns:ap-northeast-2:123456789012:harudle-dev-alerts",
                    p95_seconds=1, p99_seconds=2)
        for changes in ({"environment": "prod"}, {"node": ""}, {"node": "user@example.com"},
                        {"p95_seconds": 0}, {"p99_seconds": math.nan}, {"p99_seconds": math.inf},
                        {"min_p99_requests": 0}, {"endpoints": [("GET", "/actuator/prometheus")]},
                        {"endpoints": [("GET", "/api/v1/diaries?token=private")]}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                resources.render(**(base | changes))


if __name__ == "__main__":
    unittest.main()
