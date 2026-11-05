import copy
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import backup_monitor as monitor


class BackupMonitorTests(unittest.TestCase):
    def info(self):
        return [{"name": "harudle-prod-al2023", "status": {"code": 0}, "backup": [
            {"error": False, "timestamp": {"stop": 100}},
            {"error": False, "timestamp": {"stop": 200}},
            {"error": True, "timestamp": {"stop": 290}},
        ]}]

    def test_uses_latest_successful_completion_without_changing_metadata(self):
        info = self.info()
        before = copy.deepcopy(info)
        self.assertEqual(monitor.backup_age(info, 300), 100)
        info[0]["backup"].reverse()
        self.assertEqual(monitor.backup_age(info, 300), 100)
        info[0]["backup"].reverse()
        self.assertEqual(info, before)

    def test_unknown_failed_missing_or_future_backups_are_not_reported_as_zero_age(self):
        for info in ([], [{}], [{"name": "other"}],
                     [{"name": "harudle-prod-al2023", "status": {"code": 1}}],
                     [{"name": "harudle-prod-al2023", "status": {"code": 0}, "backup": []}],
                     [{"name": "harudle-prod-al2023", "status": {"code": 0}, "backup": [
                         {"error": False, "timestamp": {"stop": 301}}]}]):
            with self.subTest(info=info), self.assertRaises(ValueError):
                monitor.backup_age(info, 300)

    def test_old_and_dev_stanzas_are_rejected_even_with_successful_backups(self):
        for name in ("harudle", "harudle-dev-al2023"):
            info = self.info()
            info[0]["name"] = name
            with self.subTest(stanza=name), self.assertRaises(ValueError):
                monitor.backup_age(info, 300)

    def test_success_publishes_two_bounded_metrics_and_only_runs_info(self):
        output = subprocess.CompletedProcess([], 0, json.dumps(self.info()), "")
        with patch.object(monitor.subprocess, "run", return_value=output) as run:
            metrics = monitor.collect_metrics(300)
        self.assertEqual([(m["MetricName"], m["Value"]) for m in metrics],
                         [("BackupInfoCheckSuccess", 1), ("BackupWithin24Hours", 1)])
        self.assertEqual(run.call_args.args[0][-1], "info")
        self.assertIn("--stanza=harudle-prod-al2023", run.call_args.args[0])
        self.assertEqual(run.call_args.kwargs["timeout"], 45)
        for metric in metrics:
            self.assertEqual(metric["Dimensions"], [{"Name": "InstanceId", "Value": monitor.INSTANCE_ID}])

    def test_daily_status_accepts_24_hours_and_rejects_older_success(self):
        output = subprocess.CompletedProcess([], 0, json.dumps(self.info()), "")
        for age, expected in ((86399, 1), (86400, 1), (86401, 0)):
            with self.subTest(age=age), patch.object(monitor.subprocess, "run", return_value=output):
                metrics = monitor.collect_metrics(200 + age)
            self.assertEqual(metrics[0]["Value"], 1)
            self.assertEqual(metrics[1]["Value"], expected)

    def test_read_failures_publish_only_failed_check(self):
        for error in (subprocess.TimeoutExpired("info", 45),
                      subprocess.CalledProcessError(1, "info"), OSError("unavailable")):
            with self.subTest(error=error), patch.object(monitor.subprocess, "run", side_effect=error):
                metrics = monitor.collect_metrics(300)
            self.assertEqual(len(metrics), 2)
            self.assertEqual(metrics[1]["Value"], 0)
            self.assertEqual(metrics[0]["Value"], 0)
        with patch.object(monitor.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "bad", "")):
            self.assertEqual(monitor.collect_metrics(300)[0]["Value"], 0)

    def test_target_matches_prod_and_timer_does_not_start_existing_backup_service(self):
        root = Path(__file__).parent
        targets = json.loads((root / "targets.json").read_text())
        self.assertEqual(monitor.INSTANCE_ID, targets["prod"]["db_instance_id"])
        timer = (root / "systemd/harudle-db-backup-monitor.timer").read_text()
        self.assertIn("OnCalendar=*-*-* 05:00:00 Asia/Seoul", timer)
        self.assertNotIn("OnUnitActiveSec", timer)
        self.assertNotIn("OnBootSec", timer)
        self.assertNotIn("Unit=harudle-db-backup.service", timer)


if __name__ == "__main__":
    unittest.main()
