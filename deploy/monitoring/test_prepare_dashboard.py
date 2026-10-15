import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from prepare_dashboard import metric_filters, prepare_dashboard, read_dashboard, service_widgets


INSTANCE_ID = "i-0123456789abcdef0"


class DashboardTests(unittest.TestCase):
    def test_preserves_existing_widgets_and_only_repairs_known_placeholder_targets(self):
        original = {"start": "-PT6H", "periodOverride": "inherit", "widgets": [
            {"type": "text", "x": 0, "y": 0, "height": 2, "properties": {"markdown": "기존 안내"}},
            {"type": "metric", "x": 0, "y": 2, "height": 6, "properties": {"title": "기존 CPU", "metrics": [
                ["AWS/EC2", "CPUUtilization", "InstanceId", "i-00000000000000000"],
                ["AWS/EC2", "CPUUtilization", "InstanceId", "i-11111111111111111"],
                ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", "harudle-prod"],
                ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", "another-team-db"],
            ]}},
        ]}
        unchanged = copy.deepcopy(original)
        result = prepare_dashboard(original, "prod", INSTANCE_ID, "harudle-db")

        self.assertEqual(original, unchanged)
        self.assertEqual(result["start"], original["start"])
        self.assertEqual(result["periodOverride"], original["periodOverride"])
        self.assertEqual(result["widgets"][0], original["widgets"][0])
        rows = result["widgets"][1]["properties"]["metrics"]
        self.assertEqual(rows[0][3], INSTANCE_ID)
        self.assertEqual(rows[1][3], "i-11111111111111111")
        self.assertEqual(rows[2][3], "harudle-db")
        self.assertEqual(rows[3][3], "another-team-db")
        self.assertEqual(result["widgets"][2]["y"], 8)
        self.assertEqual(prepare_dashboard(result, "prod", INSTANCE_ID, "harudle-db"), result)

    def test_latency_uses_raw_samples_with_no_zero_defaults_and_bounded_groups(self):
        for environment in ("dev", "prod"):
            filters = metric_filters(environment)
            durations = [item for item in filters if item["metricTransformations"][0]["unit"] == "Milliseconds"]
            self.assertEqual(len(durations), 2)
            for item in durations:
                transformation = item["metricTransformations"][0]
                self.assertEqual(transformation["metricValue"], "$.durationMs")
                self.assertNotIn("defaultValue", transformation)
                self.assertIn("$.durationMs >= 0", item["filterPattern"])
            self.assertNotIn("dimensions", durations[0]["metricTransformations"][0])
            self.assertEqual(durations[1]["metricTransformations"][0]["dimensions"], {"ApiGroup": "$.apiGroup"})
            self.assertIn('$.apiGroup = "general"', durations[1]["filterPattern"])
            self.assertIn('$.apiGroup = "generation"', durations[1]["filterPattern"])

    def test_tps_requires_committed_writes_or_successful_read_responses(self):
        filters = {item["metricTransformations"][0]["metricName"]: item for item in metric_filters("dev")}
        self.assertIn('$.event = "generation_finalized" && $.status = "SUCCEEDED"',
                      filters["DiaryGenerationSucceeded"]["filterPattern"])
        self.assertIn('$.event = "feed_publication_succeeded"', filters["FeedPublicationSucceeded"]["filterPattern"])
        for name, operation in (("FeedListSucceeded", "feed_list"), ("FeedDetailSucceeded", "feed_detail")):
            self.assertIn('$.event = "api_request_completed"', filters[name]["filterPattern"])
            self.assertIn(f'$.operation = "{operation}"', filters[name]["filterPattern"])
            self.assertIn("$.httpStatus = 200", filters[name]["filterPattern"])
            self.assertEqual(filters[name]["metricTransformations"][0]["metricValue"], "1")

    def test_existing_dev_and_prod_widgets_and_filters_keep_the_same_contract(self):
        normalized = []
        for environment in ("dev", "prod"):
            value = {"filters": metric_filters(environment), "widgets": service_widgets(environment, INSTANCE_ID)[:9]}
            text = json.dumps(value)
            other = "Prod" if environment == "dev" else "Dev"
            self.assertNotIn(f"Harudle/{other}", text)
            text = text.replace(f"Harudle/{environment.title()}", "NAMESPACE")
            text = text.replace(f"/harudle/{environment}/", "/harudle/ENV/")
            text = text.replace(f"harudle-{environment}-", "harudle-ENV-")
            normalized.append(text)
        self.assertEqual(*normalized)

    def test_gc_and_nonheap_widgets_are_dev_only_and_use_converted_counters(self):
        self.assertEqual(len(service_widgets("prod", INSTANCE_ID)), 9)
        widgets = service_widgets("dev", INSTANCE_ID)
        self.assertEqual(len(widgets), 11)
        gc, memory = widgets[-2:]
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
        prepared = prepare_dashboard({"widgets": []}, "dev", INSTANCE_ID, "harudle-dev-db")
        self.assertEqual(prepare_dashboard(prepared, "dev", INSTANCE_ID, "harudle-dev-db"), prepared)

    def test_dashboard_uses_sample_percentiles_period_based_rates_and_matching_resource_units(self):
        widgets = service_widgets("dev", INSTANCE_ID)
        for item in widgets[:3]:
            self.assertEqual([row[-1]["stat"] for row in item["properties"]["metrics"]],
                             ["Average", "p95", "p99", "SampleCount"])
        rps = widgets[3]["properties"]["metrics"]
        self.assertEqual(rps[0][-1]["stat"], "SampleCount")
        self.assertEqual(rps[1][0]["expression"], "requests/PERIOD(requests)")
        for row in widgets[4]["properties"]["metrics"][1::2]:
            self.assertIn("/PERIOD(", row[0]["expression"])
        self.assertIn("IF(acquires>0,1000*seconds/acquires)", json.dumps(widgets[5]))
        self.assertIn('area="heap"', widgets[7]["properties"]["metrics"][0][0]["expression"])
        rss = widgets[8]["properties"]["metrics"][0][0]["expression"]
        self.assertIn(f'InstanceId="{INSTANCE_ID}"', rss)
        self.assertIn(r"\(", rss)
        self.assertIn("/1048576", rss)
        for item in widgets:
            ids = [row[-1]["id"] for row in item["properties"]["metrics"] if "id" in row[-1]]
            self.assertEqual(len(ids), len(set(ids)))

    def test_cli_rejects_other_environment_and_generates_reviewable_files_without_aws(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = root / "before.json"
            output = root / "result"
            previous = {"DashboardArn": "arn:aws:cloudwatch::123456789012:dashboard/DASHBOARD-harudle-prod",
                        "DashboardBody": json.dumps({"widgets": []})}
            before.write_text(json.dumps(previous))
            command = [sys.executable, str(Path(__file__).with_name("prepare_dashboard.py")),
                       "--environment", "dev", "--instance-id", INSTANCE_ID, "--rds-instance-id", "harudle-db",
                       "--dashboard", str(before), "--output", str(output)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())
            previous["DashboardArn"] = previous["DashboardArn"].replace("prod", "dev")
            before.write_text(json.dumps(previous))
            result = subprocess.run(command, capture_output=True, text=True, check=True)
            self.assertEqual(len(list(output.glob("*.json"))), 7)
            self.assertEqual(json.loads(before.read_text()), previous)
            self.assertEqual(len(json.loads((output / "dashboard.json").read_text())["widgets"]), 11)

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
                           "--rds-instance-id", "harudle-db", "--dashboard", str(before), "--output", str(output)]

                subprocess.run(command, capture_output=True, text=True, check=True)

                result = json.loads((output / "dashboard.json").read_text())
                self.assertEqual(before.read_bytes(), saved_source)
                self.assertEqual(result["variables"], original["variables"])
                self.assertEqual(result["start"], original["start"])
                self.assertEqual(result["widgets"][:1], original["widgets"])
                self.assertEqual(len(result["widgets"]), 12 if environment == "dev" else 10)
                self.assertEqual(len(list(output.glob("*.json"))), 7)
                for index, expected in enumerate(metric_filters(environment), start=1):
                    self.assertEqual(json.loads((output / f"metric-filter-{index}.json").read_text()), expected)
                before.write_text(json.dumps(result), encoding="utf-8")
                subprocess.run(command, capture_output=True, text=True, check=True)
                self.assertEqual(json.loads((output / "dashboard.json").read_text()), result)

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
                       "--environment", "dev", "--instance-id", INSTANCE_ID, "--rds-instance-id", "harudle-db",
                       "--dashboard", str(before), "--output", str(root)]

            result = subprocess.run(command, capture_output=True, text=True)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("원본 JSON과 생성할 파일의 경로를 다르게 지정하세요.", result.stderr)
            self.assertEqual(before.read_bytes(), saved_source)
            self.assertEqual(list(root.iterdir()), [before])


if __name__ == "__main__":
    unittest.main()
