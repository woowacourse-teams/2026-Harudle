"""Read Prod pgBackRest metadata and publish two metrics; never run a backup."""

import argparse
import json
import subprocess
import time


INSTANCE_ID = "i-01baa5c11a6224cd7"
NAMESPACE = "Harudle/Prod"
INFO_COMMAND = [
    "/usr/bin/docker", "compose", "--env-file", "/srv/harudle/db/.env",
    "-f", "/srv/harudle/db/compose.yaml", "exec", "-T", "--user", "postgres",
    "db", "pgbackrest", "--stanza=harudle", "--output=json", "info",
]


def backup_age(info, now):
    if not isinstance(info, list) or len(info) != 1:
        raise ValueError("Expected one backup stanza")
    stanza = info[0]
    if not isinstance(stanza, dict) or stanza.get("name") != "harudle" or stanza.get("status", {}).get("code") != 0:
        raise ValueError("Backup repository information is not healthy")
    backups = stanza.get("backup", [])
    if not isinstance(backups, list) or any(not isinstance(b, dict) for b in backups):
        raise ValueError("Invalid backup metadata")
    completed = [b["timestamp"]["stop"] for b in backups
                 if b.get("error") is False
                 and type(b.get("timestamp", {}).get("stop")) is int
                 and b["timestamp"]["stop"] > 0]
    if not completed or max(completed) > now:
        raise ValueError("No completed backup with a valid completion time")
    return now - max(completed)


def collect_metrics(now):
    dimensions = [{"Name": "InstanceId", "Value": INSTANCE_ID}]
    result = [{"MetricName": "BackupInfoCheckSuccess", "Dimensions": dimensions,
               "Value": 0, "Unit": "Count"},
              {"MetricName": "BackupWithin24Hours", "Dimensions": dimensions,
               "Value": 0, "Unit": "Count"}]
    try:
        info = subprocess.run(INFO_COMMAND, check=True, capture_output=True,
                              text=True, timeout=45)
        age = backup_age(json.loads(info.stdout), now)
    except (OSError, subprocess.SubprocessError, ValueError, TypeError, KeyError, AttributeError):
        return result
    result[0]["Value"] = 1
    result[1]["Value"] = int(age <= 24 * 60 * 60)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Read metadata without publishing")
    args = parser.parse_args()
    metrics = collect_metrics(time.time())
    if not args.dry_run:
        import boto3
        from botocore.config import Config

        try:
            client = boto3.client("cloudwatch", region_name="ap-northeast-2",
                                  config=Config(connect_timeout=3, read_timeout=10,
                                                retries={"total_max_attempts": 2}))
            client.put_metric_data(Namespace=NAMESPACE, MetricData=metrics)
        except Exception:
            # SDK diagnostics can contain request details; keep them out of the journal.
            print("backup_monitor_publish_failed", flush=True)
            return 1
    print(json.dumps({"dry_run": args.dry_run, "metrics": metrics}), flush=True)
    return 0 if metrics[0]["Value"] == 1 else 1


if __name__ == "__main__":
    raise SystemExit(main())
