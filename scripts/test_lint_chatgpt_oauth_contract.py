"""Synthetic-only tests for explicit offline checks and default-mode delegation."""
from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock


VALID_HOST = "urn:uuid:12345678-1234-4234-8234-123456789abc"
VALID_REDIRECT = "http://127.0.0.1:1455/auth/callback"
VALID_SCOPE = (
    "openid profile email offline_access resource.invoke "
    "chatgpt.tokens.use.direct"
)


def load_linter():
    source = Path(__file__).with_name("lint_chatgpt_oauth_contract.py")
    spec = importlib.util.spec_from_file_location("synthetic_oauth_linter", source)
    if spec is None or spec.loader is None:
        raise AssertionError("OAuth linter source could not be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class OAuthContractOfflineTest(unittest.TestCase):
    def setUp(self):
        self.linter = load_linter()

    @staticmethod
    def valid_args(**overrides):
        values = {
            "host_id": VALID_HOST,
            "redirect_uri": VALID_REDIRECT,
            "scope": VALID_SCOPE,
            **overrides,
        }
        return [
            "--host-id", values["host_id"],
            "--redirect-uri", values["redirect_uri"],
            "--scope", values["scope"],
        ]

    def run_main(self, argv):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), mock.patch.object(
                self.linter.sys, "argv", ["oauth-linter", *argv]):
            rc = self.linter.main()
        return rc, list(self.linter.results), output.getvalue()

    def run_offline(self, argv):
        # Replace the linter's os namespace instead of process-global os.environ:
        # argparse/gettext may legitimately consult the process environment.
        environment = mock.MagicMock(name="forbidden_linter_environment")
        environment_reads = [
            environment.get, environment.__getitem__, environment.__iter__,
            environment.__contains__, environment.keys, environment.items,
            environment.values, environment.copy,
        ]
        for read in environment_reads:
            read.side_effect = AssertionError("offline environment access")
        getenv = mock.Mock(side_effect=AssertionError("offline getenv access"))
        os_proxy = SimpleNamespace(environ=environment, getenv=getenv)

        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(self.linter, "os", os_proxy))
            forbidden = [
                stack.enter_context(mock.patch.object(
                    self.linter, name,
                    side_effect=AssertionError("offline call: " + name),
                ))
                for name in (
                    "read_secret_file", "load_cred", "check_source_literals",
                )
            ]
            forbidden.append(stack.enter_context(mock.patch.object(
                self.linter.Path, "rglob",
                side_effect=AssertionError("offline source tree scan"),
            )))
            forbidden.append(stack.enter_context(mock.patch.object(
                self.linter.Path, "read_text",
                side_effect=AssertionError("offline file content read"),
            )))
            result = self.run_main(["--offline", *argv])

        for blocked in [*forbidden, *environment_reads, getenv]:
            blocked.assert_not_called()
        return result

    def assert_source_unobserved(self, rows):
        source_rows = [row for row in rows if row[0] == "source-literals"]
        self.assertEqual(1, len(source_rows))
        self.assertEqual("SKIP", source_rows[0][1])
        self.assertIn("not_observed", source_rows[0][2])

    def test_offline_valid_cli_values_pass_three_checks_without_external_reads(self):
        rc, rows, output = self.run_offline(self.valid_args())
        self.assertEqual(0, rc)
        self.assertEqual(
            [("host-id", "PASS"), ("redirect-uri", "PASS"),
             ("scope", "PASS"), ("source-literals", "SKIP")],
            [(name, status) for name, status, _ in rows],
        )
        self.assert_source_unobserved(rows)
        self.assertIn("lint: pass=3 skip=1 fail=0", output)

    def test_offline_invalid_cli_value_fails_only_its_own_check(self):
        cases = (
            ("host-id", {"host_id": "synthetic-host-not-a-urn"}),
            ("redirect-uri", {
                "redirect_uri": "https://127.0.0.1:1455/auth/callback",
            }),
            ("scope", {"scope": "openid profile"}),
        )
        for name, overrides in cases:
            with self.subTest(check=name):
                rc, rows, output = self.run_offline(self.valid_args(**overrides))
                self.assertEqual(1, rc)
                self.assertEqual([name], [n for n, s, _ in rows if s == "FAIL"])
                self.assertEqual(2, sum(s == "PASS" for _, s, _ in rows))
                self.assert_source_unobserved(rows)
                self.assertIn("lint: pass=2 skip=1 fail=1", output)

    def test_offline_missing_cli_values_stay_unobserved_without_fallback(self):
        rc, rows, output = self.run_offline([])
        self.assertEqual(0, rc)
        self.assertEqual(
            {"host-id", "redirect-uri", "scope", "source-literals"},
            {name for name, _, _ in rows},
        )
        self.assertTrue(all(status == "SKIP" for _, status, _ in rows))
        self.assert_source_unobserved(rows)
        self.assertIn("lint: pass=0 skip=4 fail=0", output)

    def test_offline_strict_missing_values_fail_on_skip(self):
        rc, rows, output = self.run_offline(["--strict"])
        self.assertEqual(1, rc)
        self.assertEqual(4, sum(status == "SKIP" for _, status, _ in rows))
        self.assertIn("strict mode: SKIP counts as FAIL", output)

    def test_offline_empty_cli_values_stay_unobserved_without_fallback(self):
        rc, rows, output = self.run_offline(self.valid_args(
            host_id="", redirect_uri="", scope="",
        ))
        self.assertEqual(0, rc)
        self.assertEqual(4, len(rows))
        self.assertTrue(all(status == "SKIP" for _, status, _ in rows))
        self.assert_source_unobserved(rows)
        self.assertIn("lint: pass=0 skip=4 fail=0", output)

    def test_offline_strict_valid_values_still_fail_on_unobserved_source(self):
        rc, rows, output = self.run_offline([*self.valid_args(), "--strict"])
        self.assertEqual(1, rc)
        self.assertEqual(3, sum(status == "PASS" for _, status, _ in rows))
        self.assert_source_unobserved(rows)
        self.assertIn("strict mode: SKIP counts as FAIL", output)

    def test_each_main_call_resets_results_including_a_prior_failure(self):
        self.linter.results.append(("synthetic-stale", "FAIL", "previous run"))
        first_rc, first_rows, _ = self.run_offline(self.valid_args())
        self.assertEqual(0, first_rc)
        self.assertEqual(4, len(first_rows))
        self.assertNotIn("synthetic-stale", [name for name, _, _ in first_rows])

        failed_rc, failed_rows, _ = self.run_offline(self.valid_args(
            host_id="synthetic-invalid-host",
        ))
        self.assertEqual(1, failed_rc)
        self.assertEqual(1, sum(s == "FAIL" for _, s, _ in failed_rows))

        recovered_rc, recovered_rows, output = self.run_offline(self.valid_args())
        self.assertEqual(0, recovered_rc)
        self.assertEqual(first_rows, recovered_rows)
        self.assertIn("lint: pass=3 skip=1 fail=0", output)

    def run_normal_synthetic(self, credential, *, strict=False, source_status="PASS"):
        environment = mock.Mock()
        environment.get.return_value = None
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(
                self.linter, "os", SimpleNamespace(environ=environment)))
            loader = stack.enter_context(mock.patch.object(
                self.linter, "load_cred", return_value=credential))
            reader = stack.enter_context(mock.patch.object(
                self.linter, "read_secret_file", return_value=None))
            scanner = stack.enter_context(mock.patch.object(
                self.linter, "check_source_literals",
                side_effect=lambda: self.linter.verdict(
                    "source-literals", source_status, "synthetic fixture only")))
            result = self.run_main(["--strict"] if strict else [])
        loader.assert_called_once_with()
        reader.assert_called_once_with(self.linter.HOST_ID_FILE)
        scanner.assert_called_once_with()
        return result

    def test_normal_fake_credential_provider_still_validates_shapes(self):
        rc, rows, _ = self.run_normal_synthetic({
            "ext_agent_host_id": VALID_HOST,
            "redirect_uri": VALID_REDIRECT,
            "scope": VALID_SCOPE,
        }, strict=True)
        self.assertEqual(0, rc)
        self.assertEqual(4, sum(s == "PASS" for _, s, _ in rows))

    def test_normal_strict_missing_fake_values_still_fails(self):
        rc, rows, _ = self.run_normal_synthetic(None, strict=True)
        self.assertEqual(1, rc)
        self.assertEqual(3, sum(s == "SKIP" for _, s, _ in rows))

    def test_normal_fake_source_failure_still_fails(self):
        rc, rows, _ = self.run_normal_synthetic({
            "host_id": VALID_HOST,
            "redirect_uri": VALID_REDIRECT,
            "scope": VALID_SCOPE,
        }, source_status="FAIL")
        self.assertEqual(1, rc)
        self.assertEqual(["source-literals"], [n for n, s, _ in rows if s == "FAIL"])


if __name__ == "__main__":
    unittest.main()
