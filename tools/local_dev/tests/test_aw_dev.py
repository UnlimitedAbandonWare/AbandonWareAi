#!/usr/bin/env python3
"""Offline self-test for the aw-dev dispatcher (tools/local_dev/aw_dev.py).

Runs the real aw_dev.py as a subprocess against the real repo root, plus
direct unit checks of argument validation, env scrubbing and result
classification. No network, no paid calls, no product files touched.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
AW_DEV = ROOT / "tools" / "local_dev" / "aw_dev.py"
sys.path.insert(0, str(AW_DEV.parent))
import aw_dev  # noqa: E402

RUNS = ROOT / "var" / "local_dev" / "runs"


def run_aw(*args, env=None, cwd=None):
    e = dict(os.environ)
    if env:
        e.update(env)
    return subprocess.run([sys.executable, "-B", str(AW_DEV)] + list(args),
                          cwd=str(cwd or ROOT), env=e, capture_output=True,
                          text=True, timeout=300)


def last_json(proc):
    return json.loads(proc.stdout)


class ArgValidation(unittest.TestCase):
    def test_unknown_action_exit_2(self):
        proc = run_aw("frobnicate")
        self.assertEqual(proc.returncode, 2, proc.stdout + proc.stderr)

    def test_no_action_exit_2(self):
        proc = run_aw()
        self.assertEqual(proc.returncode, 2)

    def test_unregistered_case_exit_2(self):
        proc = run_aw("test", "--case", "no-such-case")
        self.assertEqual(proc.returncode, 2)
        self.assertIn("unregistered-case", proc.stdout)

    def test_bad_case_id_exit_2(self):
        proc = run_aw("test", "--case", "Bad;id")
        self.assertEqual(proc.returncode, 2)

    def test_unregistered_arg_exit_2(self):
        proc = run_aw("test", "--case", "fixture-child-fail", "--bogus", "1")
        self.assertEqual(proc.returncode, 2)

    def test_injected_arg_rejected(self):
        # shell metacharacters in a case arg must never reach a child
        proc = run_aw("test", "--case", "fixture-child-fail&calc")
        self.assertEqual(proc.returncode, 2)

    def test_check_value_boundaries(self):
        for bad in ("a&b", "a|b", "a>b", "a<b", 'a"b', "a;b", "a\nb",
                    "a!b", "a`b", "a=b"):
            self.assertIsNotNone(aw_dev.check_value(bad), bad)
        for good in ("abc", "a.b.c", "data/x.json", "a-b_c"):
            self.assertIsNone(aw_dev.check_value(good), good)

    def test_case_id_regex(self):
        for ok in ("aw-selftest", "x", "a.b-c_1"):
            self.assertRegex(ok, aw_dev.CASE_ID_RE)
        for bad in ("A", "-x", "a b", "a;b", ""):
            self.assertIsNone(aw_dev.CASE_ID_RE.match(bad), bad)


class ArgPathSafety(unittest.TestCase):
    def test_relpath_escape_rejected(self):
        with self.assertRaises(ValueError):
            aw_dev.resolve_arg_path(ROOT, "..\\outside.json")

    def test_abs_path_outside_root_rejected(self):
        with self.assertRaises(ValueError):
            aw_dev.resolve_arg_path(ROOT, "C:\\Windows\\x.json")

    @unittest.skipUnless(os.name == "nt", "junction test is Windows-only")
    def test_junction_escape_rejected(self):
        with tempfile.TemporaryDirectory() as outside, \
                tempfile.TemporaryDirectory(dir=ROOT / "var") as inner:
            link = Path(inner) / "link"
            subprocess.run(["cmd.exe", "/c", "mklink", "/J", str(link),
                            outside], capture_output=True, check=True)
            target = Path(outside) / "loot.json"
            target.write_text("{}", encoding="utf-8")
            with self.assertRaises(ValueError):
                aw_dev.resolve_arg_path(
                    ROOT, str(Path(inner).relative_to(ROOT) / "link" /
                              "loot.json"))


class EnvScrub(unittest.TestCase):
    def test_scrub_drops_secret_names(self):
        env = {"PATH": "x", "OPENAI_API_KEY": "k", "MY_TOKEN": "t",
               "NORMAL": "v", "VERCEL_OIDC_TOKEN": "x",
               "AWX_JEV_VIA_WRAPPER": "1"}
        out = aw_dev.scrub_env(env)
        self.assertIn("PATH", out)
        self.assertIn("NORMAL", out)
        for name in ("OPENAI_API_KEY", "MY_TOKEN", "VERCEL_OIDC_TOKEN",
                     "AWX_JEV_VIA_WRAPPER"):
            self.assertNotIn(name, out)

    def test_scrub_text_redacts_values(self):
        text = "OPENAI_API_KEY=abc123\nline Bearer tok_abc\nnormal"
        out = aw_dev.scrub_text(text)
        self.assertNotIn("abc123", out)
        self.assertNotIn("tok_abc", out)
        self.assertIn("OPENAI_API_KEY=<redacted>", out)


class Classification(unittest.TestCase):
    def _spec(self, **kw):
        spec = {"resultKind": "exit"}
        spec.update(kw)
        return spec

    def _run(self, **rec):
        return {"run": rec}

    def test_child_nonzero_passes_through(self):
        code, result, reason, origin, _ = aw_dev.classify_exec(
            self._spec(), self._run(status="failed", exitCode=6,
                                    verificationExitCode=6, failures=[]),
            ROOT, {})
        self.assertEqual((code, origin), (6, "child"))
        self.assertEqual(result, "FAIL")

    def test_child_zero_no_evidence_passes(self):
        code, result, *_ = aw_dev.classify_exec(
            self._spec(), self._run(status="passed", exitCode=0,
                                    verificationExitCode=0, failures=[],
                                    totals={"tests": 0}),
            ROOT, {})
        self.assertEqual(code, 0)
        self.assertEqual(result, "PASS")

    def test_zero_junit_is_inconclusive(self):
        code, result, reason, *_ = aw_dev.classify_exec(
            self._spec(resultKind="junit"),
            self._run(status="evidence_incomplete", exitCode=0,
                      verificationExitCode=3,
                      failures=["invalid_xml:awx.zero"],
                      totals={"tests": 0, "failures": 0, "errors": 0,
                              "skipped": 0}),
            ROOT, {})
        self.assertEqual(code, 11)
        self.assertEqual(result, "INCONCLUSIVE")

    def test_stale_xml_inconclusive(self):
        code, _, reason, *_ = aw_dev.classify_exec(
            self._spec(resultKind="junit"),
            self._run(status="evidence_incomplete", exitCode=0,
                      verificationExitCode=3,
                      failures=["stale_xml:X"],
                      totals={"tests": 5, "failures": 0, "errors": 0,
                              "skipped": 0}),
            ROOT, {})
        self.assertEqual(code, 11)

    def test_junit_failure_is_fail(self):
        code, result, *_ = aw_dev.classify_exec(
            self._spec(resultKind="junit"),
            self._run(status="evidence_incomplete", exitCode=0,
                      verificationExitCode=3,
                      failures=["junit_failure"],
                      totals={"tests": 3, "failures": 1, "errors": 0,
                              "skipped": 0}),
            ROOT, {})
        self.assertEqual(code, 1)
        self.assertEqual(result, "FAIL")

    def test_interrupted_is_fail_child(self):
        code, result, reason, origin, *_ = aw_dev.classify_exec(
            self._spec(), self._run(status="interrupted", exitCode=None,
                                    verificationExitCode=3,
                                    failures=["command_interrupted"]),
            ROOT, {})
        self.assertEqual(code, 3)
        self.assertEqual(origin, "child")


class EndToEnd(unittest.TestCase):
    """Subprocess checks against the real root; writes to var/local_dev."""

    def test_fixture_child_fail_exit6(self):
        proc = run_aw("test", "--case", "fixture-child-fail")
        self.assertEqual(proc.returncode, 6, proc.stdout + proc.stderr)
        data = last_json(proc)
        self.assertEqual(data["result"], "FAIL")
        self.assertEqual(data["origin"], "child")
        self.assertEqual(data["exitCode"], 6)
        result_file = RUNS / data["runId"] / "result.json"
        self.assertTrue(result_file.is_file())

    def test_fixture_zero_tests_inconclusive(self):
        proc = run_aw("test", "--case", "fixture-zero-tests")
        self.assertEqual(proc.returncode, 11, proc.stdout + proc.stderr)
        data = last_json(proc)
        self.assertEqual(data["result"], "INCONCLUSIVE")

    def test_env_names_fixture_scrubs(self):
        # A secret-looking name must never reach the child.
        proc = run_aw("smoke", "--case", "env-names-fixture",
                      env={"AWX_SECRET_SENTINEL_VALUE": "marker-shhh",
                           "OPENAI_API_KEY": "fake-key-for-test"})
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        data = last_json(proc)
        self.assertEqual(data["result"], "PASS")
        tail = (data.get("steps") or [{}])[-1].get("outputTail", "")
        self.assertNotIn("marker-shhh", tail)
        self.assertNotIn("fake-key-for-test", tail)

    def test_status_foreign_run_refused(self):
        foreign = RUNS / "run-foreign-test"
        foreign.mkdir(parents=True, exist_ok=True)
        (foreign / "aw-dev-run.json").write_text(json.dumps(
            {"createdBy": "someone-else", "runId": "run-foreign-test"}))
        try:
            proc = run_aw("status", "--run", "run-foreign-test")
            self.assertEqual(proc.returncode, 2)
            proc = run_aw("stop", "--run", "run-foreign-test")
            self.assertEqual(proc.returncode, 2)
            data = last_json(proc)
            self.assertEqual(data.get("kills"), 0)
        finally:
            shutil.rmtree(foreign, ignore_errors=True)

    def test_status_on_own_run(self):
        proc = run_aw("test", "--case", "fixture-child-fail")
        data = last_json(proc)
        run_id = data["runId"]
        proc = run_aw("status", "--run", run_id)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        obs = last_json(proc)["observations"]
        self.assertTrue(obs)
        self.assertIn(obs[0].get("observation"), ("finalized",))

    def test_stop_orphaned_run_no_kill(self):
        # A run dir whose recorded pid is dead: stop must kill nothing.
        run_id = "run-orphan-selftest"
        run_dir = RUNS / run_id
        verify = run_dir / "verify"
        verify.mkdir(parents=True, exist_ok=True)
        try:
            (run_dir / "aw-dev-run.json").write_text(json.dumps(
                {"createdBy": "aw-dev", "runId": run_id,
                 "schemaVersion": aw_dev.SCHEMA}))
            (verify / "run.json").write_text(json.dumps(
                {"runId": "x", "status": "running", "pid": 4000000,
                 "startedAt": "2000-01-01T00:00:00+00:00",
                 "exitCode": None}))
            proc = run_aw("stop", "--run", run_id)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            data = last_json(proc)
            self.assertEqual(data["kills"], 0)
        finally:
            shutil.rmtree(run_dir, ignore_errors=True)

    def test_report_writes_files(self):
        proc = run_aw("report", "--last", "5")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        data = last_json(proc)
        self.assertTrue(Path(data["reportJson"]).is_file())
        self.assertTrue(Path(data["reportMd"]).is_file())
        self.assertIn("pendingCases", data)

    def test_doctor_runs(self):
        proc = run_aw("doctor")
        self.assertIn(proc.returncode, (0, 10), proc.stdout + proc.stderr)
        data = last_json(proc)
        self.assertIn("checks", data)
        names = {c["name"] for c in data["checks"]}
        self.assertIn("python", names)
        self.assertIn("java17", names)

    def test_bootstrap_plan_readonly(self):
        proc = run_aw("bootstrap", "--plan")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        data = last_json(proc)
        self.assertIn("planHash", data)
        self.assertFalse((ROOT / "var" / "local_dev" /
                          "bindings.local.json.written").exists())

    def test_bootstrap_apply_requires_matching_sha(self):
        proc = run_aw("bootstrap", "--apply", "--plan-sha", "deadbeef")
        self.assertEqual(proc.returncode, 2)

    def test_apply_writes_bindings(self):
        plan = last_json(run_aw("bootstrap", "--plan"))
        bindings = aw_dev.bindings_path()
        existed = bindings.is_file()
        prior = bindings.read_bytes() if existed else None
        try:
            proc = run_aw("bootstrap", "--apply",
                          "--plan-sha", plan["planHash"])
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            if not existed:
                self.assertTrue(bindings.is_file())
                data = json.loads(bindings.read_text(encoding="utf-8"))
                self.assertTrue(Path(data["python"]).is_file())
        finally:
            if existed and prior is not None:
                bindings.write_bytes(prior)

    def test_registry_cases_parse(self):
        reg = aw_dev.load_registry()
        self.assertTrue(reg.get("cases"))
        for case in reg["cases"]:
            self.assertRegex(case["id"], aw_dev.CASE_ID_RE)
            self.assertIn(case["action"], aw_dev.ACTIONS)
            self.assertNotEqual(case.get("network"), "remote-paid")
            self.assertLessEqual(set(case), aw_dev.REGISTRY_FIELDS)

    def test_list_action(self):
        proc = run_aw("list")
        self.assertEqual(proc.returncode, 0)
        data = last_json(proc)
        self.assertTrue(data["cases"])

    def test_start_holds(self):
        proc = run_aw("start", "--target", "verify")
        self.assertEqual(proc.returncode, 10)
        data = last_json(proc)
        self.assertEqual(data["reasonCode"], "HOLD_NOT_ISOLATED")

    def test_other_cwd(self):
        proc = run_aw("doctor", cwd=Path(tempfile.gettempdir()))
        self.assertIn(proc.returncode, (0, 10))
        data = last_json(proc)
        self.assertEqual(Path(data["root"]), ROOT)


if __name__ == "__main__":
    unittest.main()
