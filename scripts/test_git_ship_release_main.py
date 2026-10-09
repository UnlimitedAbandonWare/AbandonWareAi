"""Main release contract, real Git against temporary local bare repos only."""
import contextlib
import datetime
import io
import json
import os
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from scripts import git_ship as ship, git_ship_easy as easy
from scripts.test_git_ship import ShipCase, git

SOURCE = "codex/owned-runtime-browser-restart"


class MainReleaseCase(ShipCase):
    def setUp(self):
        super().setUp()
        (self.repo / ".git" / "info" / "exclude").write_text("var/\nscripts/\n", encoding="utf-8")
        toolsdir = self.repo / "scripts"
        toolsdir.mkdir()
        (toolsdir / "agent_scope_lease.py").write_text(
            'import json\nfrom pathlib import Path\n'
            'leases=[json.loads(p.read_text()) for p in Path("__patch_drop__/source-edit-locks").glob("*/lease.json")]\n'
            'print(json.dumps({"leases":leases}))\n', encoding="utf-8")
        git(self.repo, "push", "origin", "main")
        self.old = self.head()
        self.g = ship.Git(str(self.repo), ship.DEFAULT_GIT)

    def source(self, unrelated=False):
        if unrelated:
            git(self.repo, "checkout", "--orphan", SOURCE)
            git(self.repo, "read-tree", "--empty")
            self.w("README.md", "source\n")
        else:
            git(self.repo, "checkout", "-b", SOURCE)
        self.w("feature.txt", "feature\n")
        git(self.repo, "add", "README.md", "feature.txt")
        git(self.repo, "commit", "-m", "source feature")
        git(self.repo, "push", "origin", SOURCE)
        return self.head()

    def args(self, **kw):
        return SimpleNamespace(source=SOURCE, remote="origin", apply=False,
                               expect_main=kw.pop("expect_main", self.remote_sha("main")), **kw)

    def release(self, **kw):
        a = self.args(**kw)
        a.apply = True
        with patch.dict(os.environ, {"AWX_PUBLISH_APPROVED": "1"}):
            return ship.cmd_release_main(self.g, a)

    def fingerprint(self):
        return (self.head(), git(self.repo, "branch", "--show-current").stdout,
                git(self.repo, "status", "--porcelain=v1", "-uall").stdout,
                git(self.repo, "write-tree").stdout)

    def test_a_unrelated_merge_two_parents_and_main_only_preserved(self):
        self.w("main-only.txt", "old asset\n")
        git(self.repo, "add", "main-only.txt")
        git(self.repo, "commit", "-m", "main asset")
        git(self.repo, "push", "origin", "main")
        old = self.head()
        src = self.source(unrelated=True)
        self.w("dirty.txt", "preserve me\n")
        before = self.fingerprint()
        result = self.release()
        new = self.remote_sha("main")
        self.assertEqual(git(self.repo, "show", "-s", "--format=%P", new).stdout.strip(), old + " " + src)
        self.assertEqual(git(self.repo, "show", new + ":README.md").stdout, "source\n")
        self.assertEqual(git(self.repo, "show", new + ":main-only.txt").stdout, "old asset\n")
        self.assertEqual(before, self.fingerprint())
        self.assertTrue(result["published"])
        self.assertEqual(len(json.loads((self.repo / ship.LAST_RELEASE_MAIN_JSON).read_text())["parents"]), 2)
        self.assertEqual(len(git(self.repo, "worktree", "list", "--porcelain").stdout.split("worktree ")) - 1, 1)

    def test_b_second_release_normal_merge(self):
        self.source(unrelated=True)
        self.release()
        previous = self.remote_sha("main")
        self.w("next.txt", "next\n")
        git(self.repo, "add", "next.txt")
        git(self.repo, "commit", "-m", "next feature")
        git(self.repo, "push", "origin", SOURCE)
        result = self.release()
        self.assertTrue(result["commonAncestor"])
        self.assertEqual(result["parents"], [previous, self.head()])

    def test_c_expected_sha_mismatch_no_remote_change(self):
        self.source()
        with self.assertRaises(ship.ShipError) as err:
            self.release(expect_main="0" * 40)
        self.assertIn("G1", err.exception.message)
        self.assertEqual(self.old, self.remote_sha("main"))

    def test_d_secret_gate_redacts_commit_subject_and_value(self):
        self.source()
        value = "sk-" + "Ab9Z" * 9
        self.w("app.properties", "api=" + value + "\n")
        git(self.repo, "add", "app.properties")
        git(self.repo, "commit", "-m", "secret " + value)
        git(self.repo, "push", "origin", SOURCE)
        plan = ship.cmd_release_main(self.g, self.args())
        self.assertFalse(plan["gates"]["G3"]["ok"])
        self.assertNotIn(value, json.dumps(plan))
        with self.assertRaises(ship.ShipError):
            self.release()
        self.assertEqual(self.old, self.remote_sha("main"))

    def test_e_local_source_ahead(self):
        self.source()
        self.w("ahead.txt", "not published\n")
        git(self.repo, "add", "ahead.txt")
        git(self.repo, "commit", "-m", "ahead")
        plan = ship.cmd_release_main(self.g, self.args())
        self.assertFalse(plan["gates"]["G2"]["ok"])
        self.assertIn("3", plan["gates"]["G2"]["reason"])
        with self.assertRaises(ship.ShipError):
            self.release()

    def test_f_related_conflict_aborts_remote_unchanged(self):
        self.source()
        self.w("README.md", "source changes\n")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-m", "source conflict")
        git(self.repo, "push", "origin", SOURCE)
        git(self.repo, "checkout", "main")
        self.w("README.md", "main changes\n")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-m", "main conflict")
        git(self.repo, "push", "origin", "main")
        old = self.head()
        git(self.repo, "checkout", SOURCE)
        before = self.fingerprint()
        with self.assertRaises(ship.ShipError) as err:
            self.release()
        self.assertEqual(err.exception.details["conflicts"], ["README.md"])
        self.assertEqual(old, self.remote_sha("main"))
        self.assertEqual(before, self.fingerprint())
        self.assertEqual(len(git(self.repo, "worktree", "list", "--porcelain").stdout.split("worktree ")) - 1, 1)

    def test_g_no_force_arguments_or_refspecs(self):
        self.source(unrelated=True)
        calls = []
        original = ship.Git.run
        def record(g, args, **kwargs):
            calls.append(list(map(str, args)))
            return original(g, args, **kwargs)
        with patch.object(ship.Git, "run", record):
            self.release()
        for call in calls:
            self.assertFalse({"-f", "--force", "--force-with-lease"}.intersection(call))
            self.assertFalse(any(a.startswith("+") for a in call))

    def test_h_archive_existing_refs_never_overwritten(self):
        self.source(unrelated=True)
        git(self.repo, "push", "origin", self.old + ":refs/heads/archive/main-old")
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")
        tag = "refs/tags/archive/main-" + stamp + "-" + self.old[:8]
        git(self.repo, "push", "origin", self.head() + ":" + tag)
        self.release()
        self.assertEqual(self.remote_sha("archive/main-old"), self.old)
        self.assertEqual(git(self.repo, "ls-remote", "origin", tag).stdout.split()[0], self.head())

    def test_i_pending_and_plan_do_not_write_refs_index_or_checkout(self):
        self.source()
        before = self.fingerprint()
        refs = git(self.repo, "show-ref").stdout
        fetch_head = self.repo / ".git" / "FETCH_HEAD"
        ship.cmd_pending_main(self.g, self.args())
        ship.cmd_release_main(self.g, self.args())
        self.assertEqual(before, self.fingerprint())
        self.assertEqual(refs, git(self.repo, "show-ref").stdout)
        self.assertFalse(fetch_head.exists())

    def test_j_menu_confirmation_main_only_and_noninteractive(self):
        self.source()
        for answer in ("", "y", "MAIN", "no"):
            with patch.object(ship, "cmd_release_main", wraps=ship.cmd_release_main) as release:
                easy.release_main_flow(self.g, self.repo, input_fn=lambda _: answer, out=lambda _: None)
                self.assertTrue(all(not c.args[1].apply for c in release.call_args_list))
        for choice, expected in (("6", 0), ("7", 2)):
            with patch.object(easy, "_stdin_is_console", return_value=False), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(easy.main(["--root", str(self.repo), "--menu", choice]), expected)
        self.assertEqual(self.old, self.remote_sha("main"))

    def test_approval_missing_is_refused(self):
        self.source()
        a = self.args()
        a.apply = True
        with patch.dict(os.environ, {"AWX_PUBLISH_APPROVED": "0"}):
            with self.assertRaises(ship.ShipError):
                ship.cmd_release_main(self.g, a)

    def test_live_lease_and_index_lock_refuse(self):
        self.source()
        lockdir = self.repo / "__patch_drop__" / "source-edit-locks" / "peer.lock"
        lockdir.mkdir(parents=True)
        (lockdir / "lease.json").write_text(json.dumps({"status": "active"}))
        self.assertFalse(ship.cmd_release_main(self.g, self.args())["gates"]["G5"]["ok"])
        (lockdir / "lease.json").unlink()
        lockdir.rmdir()
        lock = self.repo / ".git" / "index.lock"
        lock.write_text("peer")
        self.assertFalse(ship.cmd_release_main(self.g, self.args())["gates"]["G5"]["ok"])
        self.assertTrue(lock.exists())

    def test_stale_remote_objects_explicit_evidence_needed(self):
        self.source()
        # Point remote main at a source commit already present locally; no fetch needed.
        git(self.origin, "update-ref", "refs/heads/main", self.head())
        plan = ship.cmd_pending_main(self.g, self.args())
        self.assertEqual(plan["mainSha"], self.head())
        self.assertEqual(plan["pendingCount"], 0)

    def test_dirty_menu_reuses_commit_flow_then_continues(self):
        self.source()
        self.w("dirty.txt", "pending local\n")
        answers = iter(("y", "main"))
        with patch.object(easy, "commit_flow", return_value=0), \
                patch.object(ship, "cmd_release_main", wraps=ship.cmd_release_main) as release:
            self.assertEqual(easy.release_main_flow(self.g, self.repo, lambda _: next(answers), lambda _: None), 0)
            self.assertTrue(any(c.args[1].apply for c in release.call_args_list))

    def test_pending_unicode_cli_under_windows_legacy_encoding(self):
        self.source()
        git(self.repo, "commit", "--allow-empty", "-m", "release \ufeff emoji \U0001f680")
        git(self.repo, "push", "origin", SOURCE)
        proc = self.ship("pending-main", "--json", env={"PYTHONIOENCODING": "cp949"})
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertTrue(json.loads(proc.stdout)["ok"])

    def test_subdirectory_root_still_checks_repository_leases(self):
        self.source()
        toolsdir = self.repo / "scripts"
        toolsdir.mkdir(exist_ok=True)
        (toolsdir / "agent_scope_lease.py").write_text(
            'print(\'{"leases":[{"lifecycle":"live","status":"active","targetPaths":[]}]}\')\n')
        subdir = self.repo / "nested"
        subdir.mkdir()
        plan = ship.cmd_release_main(ship.Git(str(subdir), self.g.exe), self.args())
        self.assertFalse(plan["gates"]["G5"]["ok"])

    def test_malformed_lease_report_is_unavailable(self):
        self.source()
        toolsdir = self.repo / "scripts"
        toolsdir.mkdir(exist_ok=True)
        (toolsdir / "agent_scope_lease.py").write_text("print('{}')\n")
        self.assertFalse(ship.cmd_release_main(self.g, self.args())["gates"]["G5"]["ok"])

    def test_console_menu_plan_only_never_asks_or_applies(self):
        self.source()
        self.w("dirty.txt", "uncommitted\n")
        with patch.object(easy, "_stdin_is_console", return_value=True), \
                patch("builtins.input", side_effect=AssertionError("dry run must not ask")), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(easy.main(["--root", str(self.repo), "--menu", "7", "--plan-only"]), 2)
        self.assertEqual(self.old, self.remote_sha("main"))

    def test_generic_subject_credential_is_redacted(self):
        self.source()
        value = "private-credential-" + "Ab9Z" * 8
        git(self.repo, "commit", "--allow-empty", "-m", "password=" + value)
        git(self.repo, "push", "origin", SOURCE)
        self.assertNotIn(value, json.dumps(ship.cmd_pending_main(self.g, self.args())))

    def test_cleanup_failure_preserves_publication_result_and_reports_path(self):
        self.source()
        original = ship.Git.run
        cleanup_args = []
        def record(g, args, **kwargs):
            if args[:2] == ["worktree", "remove"]:
                cleanup_args.append(args)
                return 1, "", "synthetic cleanup failure"
            return original(g, args, **kwargs)
        try:
            with patch.object(ship.Git, "run", record):
                result = self.release()
            self.assertTrue(result["published"])
            self.assertFalse(result["cleanup"]["ok"])
            self.assertEqual(result["newMain"], self.remote_sha("main"))
        finally:
            for args in cleanup_args:
                original(self.g, args)
                from pathlib import Path
                Path(args[2]).parent.rmdir()

    def test_missing_lease_checker_blocks_release(self):
        self.source()
        (self.repo / "scripts" / "agent_scope_lease.py").unlink()
        self.assertFalse(ship.cmd_release_main(self.g, self.args())["gates"]["G5"]["ok"])

    def test_blob_warning_and_hard_limit_reuse_existing_helper(self):
        self.source()
        with patch.object(ship, "_oversize_in_range", return_value=[("big.bin", 51 * 1024 * 1024)]):
            plan = ship.cmd_release_main(self.g, self.args())
            self.assertTrue(plan["gates"]["G4"]["ok"])
            self.assertTrue(plan["gates"]["G4"]["warnings"])
        with patch.object(ship, "_oversize_in_range", return_value=[("big.bin", 101 * 1024 * 1024)]):
            with self.assertRaises(ship.ShipError):
                self.release()
        self.assertEqual(self.old, self.remote_sha("main"))

    def test_confirmation_env_is_restored_on_apply_failure(self):
        self.source()
        real = ship.cmd_release_main
        def refuse(g, args):
            if args.apply:
                raise ship.ShipError(ship.EXIT_POLICY, "synthetic refusal")
            return real(g, args)
        env = {"AWX_PUBLISH_APPROVED": "previous", "AWX_RELEASE_MAIN_EXPECT": self.old}
        with patch.dict(os.environ, env), patch.object(ship, "cmd_release_main", refuse):
            with self.assertRaises(ship.ShipError):
                easy.release_main_flow(self.g, self.repo, lambda _: "main", lambda _: None)
            for key, value in env.items():
                self.assertEqual(os.environ[key], value)


if __name__ == "__main__":
    unittest.main()
