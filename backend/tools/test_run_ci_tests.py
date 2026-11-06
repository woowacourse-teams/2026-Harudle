import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("ci_runner", Path(__file__).with_name("run_ci_tests.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class CiRunnerTest(unittest.TestCase):
    def test_targeted_phase_selects_exact_two_postgres_classes_and_is_bounded(self):
        args = runner.command("targeted")
        self.assertIn("300s", args)
        self.assertEqual(args.count("--tests"), 2)
        self.assertEqual(args[-4:], ["--tests", runner.TARGETS[0], "--tests", runner.TARGETS[1]])
        self.assertIn("--rerun", args)

    def test_full_phase_runs_all_tests_without_filters_and_preserves_job_budget(self):
        args = runner.command("full")
        self.assertIn("600s", args)
        self.assertNotIn("--tests", args)
        self.assertLess(sum(runner.LIMITS.values()) + 2 * 20 + 180, 20 * 60)

    def test_only_actual_gradle_test_worker_main_is_selected(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path)
            for pid, command in {"12": b"java\0" + runner.WORKER_MAIN + b"\0" + b"1",
                                 "13": b"java\0org.gradle.launcher.daemon.bootstrap.GradleDaemon",
                                 "14": b"echo\0" + runner.WORKER_MAIN + b"-suffix"}.items():
                (root / pid).mkdir()
                (root / pid / "cmdline").write_bytes(command)
            self.assertEqual(runner.worker_pids(root), [12])

    def test_targeted_results_are_preserved_before_full_test_overwrites_them(self):
        with tempfile.TemporaryDirectory() as path:
            import os
            original = Path.cwd()
            try:
                os.chdir(path)
                source = Path("build/test-results/test")
                source.mkdir(parents=True)
                (source / "TEST.xml").write_text("<testsuite tests='6'/>")
                runner.copy_reports("targeted", Path("build/ci-test-diagnostics"))
                (source / "TEST.xml").write_text("later full run")
                self.assertEqual(Path("build/ci-test-diagnostics/targeted-xml/TEST.xml").read_text(), "<testsuite tests='6'/>")
            finally:
                os.chdir(original)


if __name__ == "__main__":
    unittest.main()
