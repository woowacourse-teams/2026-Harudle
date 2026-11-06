#!/usr/bin/env python3
"""Bound CI test phases and collect worker thread stacks, without heap or configuration dumps."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

LIMITS = {"targeted": 300, "full": 600}
TARGETS = (
    "com.harudle.recovery.ImageRecoveryApplicationTest",
    "com.harudle.diary.service.EncryptedGuestDiaryClaimPersistenceTest",
)
WORKER_MAIN = b"worker.org.gradle.process.internal.worker.GradleWorkerMain"


def worker_pids(proc_root: Path = Path("/proc")) -> list[int]:
    pids = []
    for directory in proc_root.iterdir():
        if not directory.name.isdecimal():
            continue
        try:
            if directory.stat().st_uid != os.getuid():
                continue
            # Inspect only to select test workers; command lines are never printed or saved.
            if WORKER_MAIN in (directory / "cmdline").read_bytes().split(b"\0"):
                pids.append(int(directory.name))
        except (OSError, ProcessLookupError):
            continue
    return sorted(pids)


def command(phase: str) -> list[str]:
    args = ["timeout", "--signal=TERM", "--kill-after=15s", f"{LIMITS[phase]}s",
            "./gradlew", "--no-daemon", "--console=plain", "--build-cache",
            "--init-script", "../deploy/scripts/ci_test_progress.gradle", "test", "--rerun"]
    if phase == "targeted":
        for target in TARGETS:
            args.extend(("--tests", target))
    return args


def thread_stacks(phase: str, output: Path) -> None:
    pids = worker_pids()
    print(f"CI_TEST_DIAGNOSTIC {phase} workers={len(pids)}", flush=True)
    for pid in pids:
        with (output / f"{phase}-worker-{pid}.threads.txt").open("w") as log:
            try:
                # Thread.print contains stack frames, not heap values, JVM options or environment data.
                subprocess.run(["jcmd", str(pid), "Thread.print"], stdout=log,
                               stderr=subprocess.STDOUT, timeout=15, check=False)
            except (OSError, subprocess.TimeoutExpired):
                log.write("Worker thread stack collection did not complete.\n")


def copy_reports(phase: str, output: Path) -> None:
    for source, suffix in ((Path("build/test-results/test"), "xml"),
                           (Path("build/reports/tests/test"), "html")):
        if source.is_dir():
            shutil.copytree(source, output / f"{phase}-{suffix}", dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns("binary"))


def run(phase: str) -> int:
    output = Path("build/ci-test-diagnostics")
    output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    process = subprocess.Popen(command(phase), start_new_session=True)
    try:
        try:
            status = process.wait(timeout=LIMITS[phase] - 30)
        except subprocess.TimeoutExpired:
            thread_stacks(phase, output)
            try:
                status = process.wait(timeout=max(1, LIMITS[phase] + 20 - (time.monotonic() - started)))
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                status = 124
    finally:
        copy_reports(phase, output)
    elapsed = round(time.monotonic() - started, 2)
    (output / f"{phase}-summary.json").write_text(json.dumps(
        {"phase": phase, "exitCode": status, "elapsedSeconds": elapsed}) + "\n")
    print(f"CI_TEST_PHASE_END {phase} exit={status} seconds={elapsed}", flush=True)
    return status if status >= 0 else 128 - status


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=LIMITS)
    raise SystemExit(run(parser.parse_args().phase))
