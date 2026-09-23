import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
SUT = ROOT / ".agents/skills/demo1-docker-autograder/scripts/docker_autograder.py"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def publish_ready_json(path: Path, value: dict) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    digest = sha256(path)
    path.with_name(path.name + ".sha256").write_text(digest + "\n", encoding="utf-8")
    path.with_name(path.name + ".ready").write_text(digest + "\n", encoding="utf-8")
    return digest


class DockerAutograderTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="awx-docker-grader-test-")
        self.base = Path(self.temp.name)
        self.root = self.base / "source"
        (self.root / "tests").mkdir(parents=True)
        (self.root / "app.py").write_text("def answer(): return 42\n", encoding="utf-8")
        (self.root / "tests/test_app.py").write_text(
            "from app import answer\ndef test_answer(): assert answer() == 42\n",
            encoding="utf-8",
        )
        self.job_path = self.root / "data/job.json"
        self.output_path = self.root / "data/result.json"
        self.capture_path = self.base / "docker-argv.json"
        self.kill_path = self.base / "docker-kill.txt"
        self.fake_docker = self._write_fake_docker()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write_fake_docker(self) -> Path:
        script = self.base / "fake_docker.py"
        script.write_text(
            textwrap.dedent(
                """
                import json
                import io
                import os
                from pathlib import Path
                import sys
                import tarfile
                import time

                args = sys.argv[1:]
                capture = Path(os.environ["AWX_FAKE_DOCKER_CAPTURE"])
                if args and args[0] == "kill":
                    Path(os.environ["AWX_FAKE_DOCKER_KILL"]).write_text(args[-1], encoding="utf-8")
                    if os.environ.get("AWX_FAKE_DOCKER_KILL_HANG") == "1":
                        time.sleep(30)
                    raise SystemExit(0)
                capture.write_text(json.dumps(args), encoding="utf-8")
                mode = os.environ.get("AWX_FAKE_DOCKER_MODE", "partial")
                if mode == "sleep":
                    time.sleep(30)
                    raise SystemExit(124)
                reports = {
                    "success": '<testsuite tests="4" failures="0" errors="0" skipped="0"/>',
                    "partial": '<testsuite tests="4" failures="1" errors="0" skipped="1"/>',
                    "huge": '<testsuite tests="4" failures="1" errors="0" skipped="1"/>',
                    "zero": '<testsuite tests="0" failures="0" errors="0" skipped="0"/>',
                    "dtd": '<!DOCTYPE x [<!ENTITY boom "x">]><testsuite tests="1" failures="0" errors="0" skipped="0"/>',
                }
                xml = (b"x" * 2048) if mode == "xml_flood" else reports[mode].encode("utf-8")
                archive_bytes = io.BytesIO()
                with tarfile.open(fileobj=archive_bytes, mode="w") as archive:
                    member = tarfile.TarInfo("junit.xml")
                    member.size = len(xml)
                    archive.addfile(member, io.BytesIO(xml))
                print("expected failure signal", file=sys.stderr)
                if mode == "huge":
                    print("x" * 200000, file=sys.stderr)
                print("bounded stderr", file=sys.stderr)
                sys.stdout.buffer.write(archive_bytes.getvalue())
                sys.stdout.buffer.flush()
                raise SystemExit(0 if mode == "success" else 1)
                """
            ).lstrip(),
            encoding="utf-8",
        )
        command = self.base / "docker.cmd"
        command.write_text(
            f'@echo off\r\n"{sys.executable}" "{script}" %*\r\n', encoding="ascii"
        )
        return command

    def _job(self, **overrides) -> dict:
        value = {
            "schemaVersion": "awx.docker-autograder.job.v1",
            "runId": "docker-red-001",
            "purpose": "RED_PROBE",
            "profile": "pytest",
            "imageRef": "example.invalid/awx-pytest@sha256:" + "a" * 64,
            "includePaths": ["app.py", "tests/test_app.py"],
            "testSelectors": ["tests/test_app.py"],
            "declaredTestCommand": "python -m pytest -q tests/test_app.py",
            "expectedSignal": "expected failure signal",
            "decisionSha256": "b" * 64,
            "intentSpecSha256": "c" * 64,
            "limits": {
                "cpus": 0.5,
                "memoryMb": 256,
                "pids": 64,
                "timeoutSeconds": 5,
                "maxFiles": 16,
                "maxInputBytes": 1048576,
                "maxLogBytes": 65536,
                "maxXmlBytes": 1048576,
                "maxTestCases": 1000,
            },
            "networkMode": "none",
            "pullPolicy": "never",
            "mutationAllowed": False,
        }
        value.update(overrides)
        return value

    def _run(
        self,
        job: dict,
        mode: str = "partial",
        docker_bin: Path | None = None,
        kill_hang: bool = False,
    ):
        publish_ready_json(self.job_path, job)
        env = os.environ.copy()
        env.update(
            {
                "AWX_FAKE_DOCKER_CAPTURE": str(self.capture_path),
                "AWX_FAKE_DOCKER_KILL": str(self.kill_path),
                "AWX_FAKE_DOCKER_MODE": mode,
                "AWX_FAKE_DOCKER_KILL_HANG": "1" if kill_hang else "0",
            }
        )
        command = [
            sys.executable,
            str(SUT),
            "--root",
            str(self.root),
            "--job",
            str(self.job_path),
            "--output",
            str(self.output_path),
            "--docker-bin",
            str(docker_bin or self.fake_docker),
        ]
        completed = subprocess.run(command, text=True, capture_output=True, env=env, timeout=15)
        result = None
        if self.output_path.exists():
            result = json.loads(self.output_path.read_text(encoding="utf-8"))
        return completed, result

    def test_partial_junit_uses_local_hardened_container_and_exact_counts(self):
        completed, result = self._run(self._job())

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(
            result["tests"],
            {
                "total": 4,
                "passed": 2,
                "failed": 1,
                "errored": 0,
                "skipped": 1,
                "passRatio": 0.5,
            },
        )
        self.assertEqual(result["executionStatus"], "COMPLETE")
        self.assertEqual(result["testVerdict"], "FAIL")
        self.assertTrue(result["expectedSignalMatched"])
        self.assertFalse(result["mutationAllowed"])
        self.assertRegex(result["stdout"]["sha256"], r"^[a-f0-9]{64}$")
        expected_combined = hashlib.sha256(
            (result["stdout"]["sha256"] + ":" + result["stderr"]["sha256"]).encode("ascii")
        ).hexdigest()
        self.assertEqual(result["combinedOutputSha256"], expected_combined)
        self.assertNotIn("expected failure signal", json.dumps(result))
        self.assertEqual(sha256(self.output_path), self.output_path.with_name(self.output_path.name + ".ready").read_text(encoding="utf-8").strip())

        argv = json.loads(self.capture_path.read_text(encoding="utf-8"))
        rendered = " ".join(argv)
        self.assertNotIn("\\\\desktop-m5nov6k\\MacSrc", rendered)
        for pair in (
            ("--network", "none"),
            ("--pull", "never"),
            ("--cap-drop", "ALL"),
            ("--security-opt", "no-new-privileges"),
            ("--pids-limit", "64"),
            ("--cpus", "0.5"),
            ("--memory", "256m"),
            ("--memory-swap", "256m"),
        ):
            index = argv.index(pair[0])
            self.assertEqual(argv[index + 1], pair[1])
        self.assertIn("--read-only", argv)
        workspace_tmpfs = argv[argv.index("--tmpfs") + 1]
        self.assertIn("uid=65532", workspace_tmpfs)
        self.assertIn("gid=65532", workspace_tmpfs)
        mounts = [argv[index + 1] for index, token in enumerate(argv) if token == "--mount"]
        self.assertFalse(any("target=/results" in mount for mount in mounts))
        tmpfs_values = [argv[index + 1] for index, token in enumerate(argv) if token == "--tmpfs"]
        results_tmpfs = next(value for value in tmpfs_values if value.startswith("/results:"))
        self.assertIn("size=1048576", results_tmpfs)

    def test_stdout_is_drained_but_persisted_only_to_the_bounded_prefix(self):
        limits = {**self._job()["limits"], "maxLogBytes": 1024}
        completed, result = self._run(self._job(limits=limits), mode="huge")

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertGreater(result["stderr"]["bytes"], 200000)
        self.assertEqual(result["stderr"]["persistedBytes"], 1024)
        self.assertTrue(result["stderr"]["truncated"])
        self.assertTrue(result["expectedSignalMatched"])
        self.assertNotIn("x" * 128, json.dumps(result))

    def test_invalid_input_is_rejected_before_docker_runs(self):
        cases = (
            (self._job(includePaths=["../outside.py"]), "path-outside-root"),
            (self._job(imageRef="python:3.12-slim"), "image-not-digest-pinned"),
            (self._job(testSelectors=["--collect-only"]), "selector-invalid"),
            (self._job(networkMode="bridge"), "network-policy-invalid"),
        )
        for index, (job, expected) in enumerate(cases):
            with self.subTest(expected=expected):
                self.job_path = self.root / f"data/job-{index}.json"
                self.output_path = self.root / f"data/result-{index}.json"
                self.capture_path.unlink(missing_ok=True)
                completed, result = self._run(job)
                self.assertNotEqual(completed.returncode, 0)
                self.assertIn(expected, completed.stderr)
                self.assertIsNone(result)
                self.assertFalse(self.capture_path.exists())

    def test_zero_tests_and_unsafe_xml_publish_hold_without_score(self):
        for index, (mode, expected) in enumerate(
            (("zero", "autograder-zero-tests"), ("dtd", "junit-xml-unsafe"))
        ):
            with self.subTest(mode=mode):
                self.job_path = self.root / f"data/job-hold-{index}.json"
                self.output_path = self.root / f"data/result-hold-{index}.json"
                completed, result = self._run(self._job(runId=f"docker-hold-00{index}"), mode=mode)
                self.assertEqual(completed.returncode, 0, completed.stderr)
                self.assertEqual(result["executionStatus"], "HOLD")
                self.assertEqual(result["failureClass"], expected)
                self.assertIsNone(result["tests"]["passRatio"])
                self.assertTrue(self.output_path.with_name(self.output_path.name + ".ready").exists())

    def test_timeout_kills_container_and_publishes_hold(self):
        job = self._job(limits={**self._job()["limits"], "timeoutSeconds": 1})
        completed, result = self._run(job, mode="sleep")

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(result["executionStatus"], "HOLD")
        self.assertEqual(result["failureClass"], "autograder-timeout")
        self.assertTrue(result["timedOut"])
        self.assertTrue(self.kill_path.exists())

    def test_hung_docker_kill_is_classified_before_ready_publication(self):
        job = self._job(limits={**self._job()["limits"], "timeoutSeconds": 1})
        completed, result = self._run(job, mode="sleep", kill_hang=True)

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(result["executionStatus"], "HOLD")
        self.assertEqual(result["failureClass"], "docker-kill-timeout")
        self.assertTrue(result["timedOut"])
        self.assertTrue(self.output_path.with_name(self.output_path.name + ".ready").exists())

    def test_junit_size_is_checked_before_file_bytes_are_materialized(self):
        source = SUT.read_text(encoding="utf-8")
        stat_check = source.index("report.stat().st_size")
        full_read = source.index("report.read_bytes()", stat_check)
        self.assertLess(stat_check, full_read)

    def test_growing_junit_output_is_stopped_before_test_timeout(self):
        limits = {
            **self._job()["limits"],
            "maxXmlBytes": 1024,
            "timeoutSeconds": 10,
        }
        completed, result = self._run(self._job(limits=limits), mode="xml_flood")

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(result["executionStatus"], "HOLD")
        self.assertEqual(result["failureClass"], "junit-output-limit-exceeded")
        self.assertFalse(result["timedOut"])

    def test_missing_docker_publishes_classified_hold(self):
        missing = self.base / "missing-docker.exe"
        completed, result = self._run(self._job(), docker_bin=missing)

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(result["executionStatus"], "HOLD")
        self.assertEqual(result["failureClass"], "docker-cli-unavailable")
        self.assertIsNone(result["tests"]["passRatio"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
