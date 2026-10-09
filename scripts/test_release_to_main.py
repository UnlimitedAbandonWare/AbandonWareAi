#!/usr/bin/env python3
"""test_release_to_main.py -- release_to_main.py 오프라인 단위 테스트.

모든 테스트는 임시 폴더의 bare repo(파일 경로 remote)만 사용한다.
실제 origin(github.com)에는 테스트 중 접속하지 않는다 -- remote URL이
로컬 경로이므로 git이 네트워크를 쓸 경로 자체가 없다.

실행: python -B -m unittest scripts.test_release_to_main -v
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "release_to_main.py"
_GIT_CAND = r"F:\git\cmd\git.exe"
GIT = os.environ.get("AWX_GIT_EXE") or (
    _GIT_CAND if Path(_GIT_CAND).is_file() else "git")

BRANCH = "codex-test-branch"
TAG = "archive/main-20260923-b2eaba46"
ARCH = "archive/main-old"


def run_git(repo, *args, check=True, env=None):
    e = dict(os.environ)
    e["GIT_TERMINAL_PROMPT"] = "0"
    if env:
        e.update(env)
    p = subprocess.run([GIT] + list(args), cwd=str(repo), env=e,
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=120)
    if check and p.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} 실패: {p.stderr.strip()}")
    return p


def run_tool(repo, log, *args, env=None, expect_code=None):
    e = dict(os.environ)
    e["GIT_TERMINAL_PROMPT"] = "0"
    if env:
        e.update(env)
    cmd = [sys.executable, "-B", str(TOOL), "--repo", str(repo),
           "--git-exe", GIT, "--log", str(log), "--g4-skip"] + list(args)
    p = subprocess.run(cmd, cwd=str(ROOT), env=e, capture_output=True,
                       text=True, encoding="utf-8", errors="replace",
                       timeout=600)
    if expect_code is not None:
        assert p.returncode == expect_code, \
            f"rc={p.returncode} want={expect_code}\nOUT:{p.stdout}\nERR:{p.stderr}"
    return p


def git_out(repo, *args):
    return run_git(repo, *args).stdout.strip()


class Fixture(unittest.TestCase):
    """공통 조상 없는 main/codex-test 두 이력을 가진 로컬 remote."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="rlt-"))
        self.bare = self.tmp / "origin.git"
        self.work = self.tmp / "work"
        self.log = self.tmp / "release_log.json"
        run_git(self.tmp, "init", "--bare", str(self.bare))
        run_git(self.tmp, "init", str(self.work))
        run_git(self.work, "config", "user.email", "test@example.invalid")
        run_git(self.work, "config", "user.name", "tester")
        run_git(self.work, "remote", "add", "origin",
                str(self.bare).replace("\\", "/"))

        # main 이력
        (self.work / "a.txt").write_text("main content\n", encoding="utf-8")
        (self.work / "gradlew.bat").write_text("REM stub\n", encoding="utf-8")
        run_git(self.work, "checkout", "-b", "main")
        run_git(self.work, "add", ".")
        run_git(self.work, "commit", "-m", "old snapshot")
        run_git(self.work, "push", "origin", "main")
        self.old_main = git_out(self.work, "rev-parse", "main")

        # 조상 없는 codex 이력
        run_git(self.work, "checkout", "--orphan", BRANCH)
        run_git(self.work, "rm", "-rf", ".", check=False)
        for p in self.work.iterdir():
            if p.name != ".git":
                if p.is_dir():
                    shutil.rmtree(p, ignore_errors=True)
                else:
                    p.unlink()
        (self.work / "b.txt").write_text("codex work\n", encoding="utf-8")
        (self.work / "gradlew.bat").write_text("REM stub\n", encoding="utf-8")
        run_git(self.work, "add", ".")
        run_git(self.work, "commit", "-m", "codex commit 1")
        run_git(self.work, "push", "origin", BRANCH)
        (self.work / "c.txt").write_text("unpushed work\n", encoding="utf-8")
        run_git(self.work, "add", ".")
        run_git(self.work, "commit", "-m", "codex commit 2 (unpushed)")
        self.rc = git_out(self.work, "rev-parse", BRANCH)
        self.remote_codex = git_out(
            self.work, "rev-parse", f"origin/{BRANCH}")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def remote_sha(self, ref):
        out = git_out(self.work, "ls-remote", "origin", ref)
        m = re.match(r"([0-9a-f]{40})", out)
        return m.group(1) if m else None

    def tool_args(self):
        return ["--branch", BRANCH, "--expect-main", self.old_main]

    def apply(self, **env):
        e = {"AWX_RELEASE_MAIN_EXPECT": self.old_main,
             "AWX_PUBLISH_APPROVED": "1"}
        e.update(env)
        return run_tool(self.work, self.log, *self.tool_args(),
                        "apply", "--yes-main", env=e)

    # --------------------------------------------------------- tests

    def test_plan_gates_pass_clean_tree(self):
        p = run_tool(self.work, self.log, *self.tool_args(), "plan",
                     expect_code=0)
        doc = json.loads(p.stdout)
        self.assertTrue(doc["ok"])
        self.assertEqual(doc["rc"]["sha"], self.rc)
        by_id = {g["id"]: g["status"] for g in doc["gates"]}
        self.assertEqual(by_id["G1"], "pass")
        self.assertEqual(by_id["G2"], "pass")
        self.assertNotEqual(by_id["G4"], "fail")   # --g4-skip -> warn

    def test_snapshot_commit_is_main_fast_forward(self):
        p = self.apply()
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        doc = json.loads(p.stdout)
        snap = doc["snapshot"]
        # 원격 main이 새 스냅샷을 가리킴 (force 없이 push됨 = FF)
        self.assertEqual(self.remote_sha("refs/heads/main"), snap)
        # 내용은 RC와 동일
        run_git(self.work, "diff", "--quiet", snap, self.rc)
        # 부모가 [옛 main, RC] 두 이력 보존
        cat = git_out(self.work, "cat-file", "-p", snap)
        parents = [l.split()[1] for l in cat.splitlines()
                   if l.startswith("parent ")]
        self.assertEqual(parents, [self.old_main, self.rc])
        # 공통 조상이 없어도 merge-base로 RC가 main의 부모가 됨
        run_git(self.work, "merge-base", "--is-ancestor", self.rc, snap)
        # codex 브랜치도 원격이 RC로 갱신됨
        self.assertEqual(
            self.remote_sha("refs/heads/" + BRANCH), self.rc)
        # 아카이브 참조가 옛 main을 가리킴
        self.assertEqual(self.remote_sha("refs/tags/" + TAG), self.old_main)
        self.assertEqual(self.remote_sha("refs/heads/" + ARCH), self.old_main)

    def test_apply_refuses_when_main_moved(self):
        other = self.tmp / "other"
        run_git(self.tmp, "clone", str(self.bare), str(other))
        run_git(other, "config", "user.email", "o@x.invalid")
        run_git(other, "config", "user.name", "other")
        run_git(other, "checkout", "main")
        (other / "z.txt").write_text("someone moved main\n")
        run_git(other, "add", ".")
        run_git(other, "commit", "-m", "foreign main move")
        run_git(other, "push", "origin", "main")
        moved = git_out(other, "rev-parse", "main")

        p = self.apply()
        self.assertEqual(p.returncode, 3, p.stdout + p.stderr)
        doc = json.loads(p.stdout)
        self.assertIn(doc["status"], ("hold", "refused"))
        # 원격 main은 그대로
        self.assertEqual(self.remote_sha("refs/heads/main"), moved)

    def test_secret_gate_blocks_and_masks(self):
        secret = "ghp_" + "A1b2C3d4" * 4          # 36자 가짜 토큰
        (self.work / "leak.txt").write_text(
            f"{secret}\n", encoding="utf-8")
        run_git(self.work, "add", ".")
        run_git(self.work, "commit", "-m", "add fake leak")
        self.rc = git_out(self.work, "rev-parse", BRANCH)

        p = run_tool(self.work, self.log, *self.tool_args(), "plan")
        self.assertEqual(p.returncode, 3, p.stdout + p.stderr)
        self.assertIn("github-ghp", p.stdout)
        self.assertIn("ghp_***", p.stdout)
        self.assertNotIn(secret, p.stdout)      # 값은 절대 출력되지 않음
        doc = json.loads(p.stdout)
        g2 = [g for g in doc["gates"] if g["id"] == "G2"][0]
        self.assertEqual(g2["status"], "fail")
        self.assertTrue(g2["suspects"])
        # apply도 같은 게이트로 거부
        p2 = self.apply()
        self.assertEqual(p2.returncode, 3)
        self.assertNotEqual(
            self.remote_sha("refs/heads/main"), self.rc)

    def test_placeholder_fixtures_do_not_block(self):
        # 자리표시자(짧은 합성 값 + 테스트/목 표면)는 G2를 막지 않는다.
        # 저장소 fixture 규약: 합성 값은 실행 중 결합으로 만들고
        # 비밀 상수 리터럴은 파일에 심지 않는다.
        ckey = "CLIENT" + "_SECRET"
        (self.work / "test_fixture_client.py").write_text(
            ckey + ' = "synthetic"\nHSEC = "hsec-1"\n'
            + ckey.lower() + "=secrets.to" + "ken_hex(12)\n",
            encoding="utf-8")
        (self.work / "env_sample.env.example").write_text(
            "NAVER_APIHUB_CLIENT_SECRET=\nCLIENT_ID=your-id\n",
            encoding="utf-8")
        run_git(self.work, "add", ".")
        run_git(self.work, "commit", "-m", "fixture placeholders")
        p = run_tool(self.work, self.log, *self.tool_args(), "plan",
                     expect_code=0)
        doc = json.loads(p.stdout)
        g2 = [g for g in doc["gates"] if g["id"] == "G2"][0]
        self.assertEqual(g2["status"], "pass")
        self.assertGreaterEqual(g2["placeholderHits"], 1)

    def test_long_secret_in_test_file_still_blocks(self):
        # 테스트 파일이어도 긴 실제형 값은 자리표시자가 아니다.
        tok = "A1b2" + "C3d4" * 5                        # 24자 합성
        (self.work / "test_something.py").write_text(
            "CLIENT" + "_SECRET" + " = " + tok + "\n", encoding="utf-8")
        run_git(self.work, "add", ".")
        run_git(self.work, "commit", "-m", "long fake-ish secret")
        p = run_tool(self.work, self.log, *self.tool_args(), "plan")
        self.assertEqual(p.returncode, 3, p.stdout)
        self.assertIn("client_secret", p.stdout)

    def test_no_force_flags_ever(self):
        p = self.apply()
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        doc = json.loads(self.log.read_text(encoding="utf-8"))
        cmds = [c["argv"] for inv in doc["invocations"]
                for c in inv["commands"]]
        self.assertTrue(cmds)
        for argv in cmds:
            for a in argv:
                self.assertNotIn(
                    a, ("-f", "--force", "--force-with-lease"),
                    msg=f"force flag in {argv}")
                self.assertFalse(a.startswith("--force"),
                                 msg=f"force flag in {argv}")

    def test_existing_archive_refs_not_overwritten(self):
        # 옛 main과 다른 커밋을 가리키는 archive 태그를 미리 만든다.
        tree = git_out(self.work, "rev-parse", "main^{tree}")
        other = git_out(self.work, "commit-tree", tree, "-m", "unrelated obj")
        run_git(self.work, "push", "origin",
                f"{other}:refs/tags/{TAG}")
        p = self.apply()
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        # 태그는 원래 대상을 유지 — 덮어쓰지 않음
        self.assertEqual(self.remote_sha("refs/tags/" + TAG), other)

    def test_apply_requires_flag_and_env(self):
        p = run_tool(self.work, self.log, *self.tool_args(), "apply",
                     expect_code=2)
        p = run_tool(self.work, self.log, *self.tool_args(),
                     "apply", "--yes-main", expect_code=3)
        self.assertIn("AWX_RELEASE_MAIN_EXPECT", p.stdout)
        # EXPECT는 있어도 AWX_PUBLISH_APPROVED 없으면 거부 (git_ship 계약)
        p = run_tool(self.work, self.log, *self.tool_args(),
                     "apply", "--yes-main", expect_code=3,
                     env={"AWX_RELEASE_MAIN_EXPECT": self.old_main})
        self.assertIn("AWX_PUBLISH_APPROVED", p.stdout)

    def test_push_uses_publish_review_contract(self):
        # push는 일회성 -c 로 publish.allowTarget/allowRef를 선언한다.
        p = self.apply()
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        doc = json.loads(self.log.read_text(encoding="utf-8"))
        pushes = [c["argv"] for inv in doc["invocations"]
                  for c in inv["commands"] if "push" in c["argv"]]
        self.assertTrue(pushes)
        for argv in pushes:
            dst = argv[-1].split(":", 1)[1]          # <sha>:<dst-ref> 형태
            self.assertIn(f"publish.allowRef={dst}", argv,
                          msg=f"allowRef -c missing in {argv}")
            self.assertTrue(any(a.startswith("publish.allowTarget=")
                                for a in argv), msg=f"allowTarget in {argv}")

    def test_apply_reuses_prior_snapshot(self):
        # 이전 apply가 만든 (tree, parents) 동일 스냅샷은 재생성하지 않는다.
        tree = git_out(self.work, "rev-parse", f"{BRANCH}^{{tree}}")
        snap = git_out(self.work, "commit-tree", tree, "-p", self.old_main,
                       "-p", self.rc, "-m", "prior snapshot")
        self.log.parent.mkdir(parents=True, exist_ok=True)
        self.log.write_text(json.dumps({
            "schemaVersion": "awx.release-to-main.v1",
            "invocations": [{"results": {"apply": {"steps": [
                {"step": "commit-tree", "status": "ok",
                 "snapshot": snap}]}}}]}), encoding="utf-8")
        p = self.apply()
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        doc = json.loads(p.stdout)
        self.assertEqual(doc["snapshot"], snap)
        steps = {s["step"]: s for s in doc["steps"]}
        self.assertEqual(steps["commit-tree"]["status"], "reused")

    def test_rollback_plan_prints_only(self):
        p = run_tool(self.work, self.log, *self.tool_args(),
                     "rollback-plan", expect_code=0)
        self.assertIn("commit-tree", p.stdout)
        self.assertIn(self.old_main, p.stdout)
        self.assertNotIn("--force", p.stdout)
        self.assertEqual(self.remote_sha("refs/heads/main"), self.old_main)

    def test_remote_is_local_path_not_real_origin(self):
        url = git_out(self.work, "remote", "get-url", "origin")
        self.assertNotIn("github.com", url)
        self.assertIn(str(self.bare).replace("\\", "/"), url)


if __name__ == "__main__":
    unittest.main()
