#!/usr/bin/env python3
"""test_git_ship_easy.py -- sandbox tests for scripts/git_ship_easy.py.

Runs in temp dirs only: git init + local commits, no network, no hooks,
never the real tree. Run: python -B -m unittest scripts.test_git_ship_easy -v
(this file only; never a suite)
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent
EASY = SCRIPTS / "git_ship_easy.py"
BAT = ROOT / "Git-Ship.bat"
GIT = os.environ.get("AWX_GIT_EXE") or shutil.which("git") or r"F:\git\cmd\git.exe"

sys.path.insert(0, str(ROOT))
from scripts import git_ship  # noqa: E402
from scripts import git_ship_easy as ge  # noqa: E402


def git(repo, *args, check=True):
    proc = subprocess.run(
        [GIT, "--no-optional-locks", "-C", str(repo)] + list(args),
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and proc.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} -> {proc.returncode}: {proc.stderr}")
    return proc


def scripted(*answers):
    it = iter(answers)

    def fn(prompt=""):
        try:
            return next(it)
        except StopIteration:
            raise EOFError

    return fn


class EasyCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="gitship-easy-"))
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-b", "main")
        git(self.repo, "config", "user.email", "t@example.invalid")
        git(self.repo, "config", "user.name", "sandbox")
        (self.repo / "README.md").write_text("hello\n", encoding="utf-8")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-m", "init")
        self.g = git_ship.Git(str(self.repo), GIT)
        self.out = []

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    # -- helpers ---------------------------------------------------------
    def w(self, rel, text):
        p = self.repo / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        return p

    def head_count(self):
        return int(git(self.repo, "rev-list", "--count", "HEAD").stdout.strip())

    def staged(self):
        return [p for p in git(
            self.repo, "diff", "--cached", "--name-only").stdout.splitlines()
            if p]

    def joined(self):
        return "\n".join(self.out)

    # (1) 기본 선택에서 lease/junk/.pyc/실행산출물이 빠진다
    def test_default_excludes_lease_junk_pyc(self):
        self.w("keep.txt", "k\n")
        self.w("leased.txt", "l\n")
        self.w("x.pyc", "pyc\n")
        self.w("var/run.log", "log\n")
        self.w("data/agent-handoff/t.txt", "t\n")
        entries = ge.collect_changes(self.g)
        with mock.patch.object(
                ge, "load_active_lease_paths",
                lambda root, timeout=20: ({"leased.txt"}, "ok", 1)):
            cand, excl = ge.classify_changes(
                self.g, self.repo, entries, {"leased.txt"})
        self.assertEqual([e["path"] for e in cand], ["keep.txt"])
        reasons = {e["path"]: why for e, why in excl}
        self.assertIn("lease", reasons["leased.txt"])
        self.assertIn("junk", reasons["x.pyc"])
        self.assertIn("실행 산출물", reasons["var/run.log"])
        self.assertIn("실행 산출물", reasons["data/agent-handoff/t.txt"])

    # (2) 번호/범위 선택 해석
    def test_parse_selection(self):
        self.assertEqual(ge.parse_selection("1 3 5-9", 9),
                         {1, 3, 5, 6, 7, 8, 9})
        self.assertEqual(ge.parse_selection("  ", 3), {1, 2, 3})
        self.assertEqual(ge.parse_selection("q", 3), "cancel")
        self.assertEqual(ge.parse_selection("3-1", 3), {1, 2, 3})
        with self.assertRaises(ValueError):
            ge.parse_selection("99", 3)
        with self.assertRaises(ValueError):
            ge.parse_selection("abc", 3)

    # (3) 미리보기 단계까지는 커밋 0
    def test_cancel_before_stage_commits_nothing(self):
        self.w('a.txt','a\n')
        rc=ge.commit_flow(self.g,self.repo,input_fn=scripted('q'),out=self.out.append)
        self.assertEqual(rc,0);self.assertEqual(self.head_count(),1)
        self.assertEqual(self.staged(),[])

    def test_commit_y_creates_commit(self):
        self.w("a.txt", "a\n")
        res = ge.commit_flow(
            self.g, str(self.repo), input_fn=scripted("", "fix a", "y"),
            out=self.out.append)
        self.assertIsInstance(res, dict)
        self.assertEqual(self.head_count(), 2)
        self.assertIn("커밋했어요", self.joined())
        self.assertEqual(self.staged(), [])

    # (5) 비밀값 파일이 stage되면 커밋 거부, 출력에 값 없음
    def test_secret_held_and_value_hidden(self):
        secret='sk-'+'Qm7vX2pL9wK4tR8zN5bH3jF6'
        self.w('secret.txt',f'key = "{secret}"\n')
        res=ge.commit_flow(self.g,self.repo,input_fn=scripted(''),out=self.out.append)
        self.assertIsInstance(res,dict);self.assertEqual(self.head_count(),1)
        self.assertIn('secret.txt',res['holds']);self.assertNotIn(secret,self.joined())
        self.assertIn('openai',self.joined())

    def test_lock_wait_keeps_lock(self):
        lockdir = git(self.repo, "rev-parse", "--absolute-git-dir").stdout.strip()
        lock = Path(lockdir) / "index.lock"
        lock.write_text("", encoding="ascii")
        ok = ge.wait_for_lock(self.g, out=self.out.append,
                              sleep=lambda s: None, wait_s=0, retries=3)
        self.assertFalse(ok)
        self.assertTrue(lock.is_file())

    # (7) 비대화형 실행이면 exit 2, 입력을 기다리지 않음
    def test_noninteractive_exits_2_fast(self):
        env = dict(os.environ)
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        proc = subprocess.run(
            [sys.executable, "-B", str(EASY), "--root", str(self.repo)],
            stdin=subprocess.DEVNULL, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=10, env=env)
        self.assertEqual(proc.returncode, 2)

    # (8) 원래 stage돼 있던 남의 파일은 기본으로 커밋에 안 들어가고 그대로
    def test_foreign_staged_untouched(self):
        self.w('other.txt','other\n');git(self.repo,'add','other.txt')
        self.w('mine.txt','mine\n')
        before=git(self.repo,'ls-files','-s','other.txt').stdout
        res=ge.commit_flow(self.g,self.repo,input_fn=scripted('m'),out=self.out.append)
        self.assertIsInstance(res,dict);self.assertEqual(self.head_count(),2)
        self.assertEqual(self.staged(),['other.txt'])
        self.assertEqual(git(self.repo,'ls-files','-s','other.txt').stdout,before)
        self.assertIn('원래 올라가 있던 파일',self.joined())

    def test_push_refused_on_main(self):
        calls = []
        rc = ge.push_flow(self.g, input_fn=scripted("y"), out=self.out.append,
                          push_fn=lambda: calls.append(1),
                          verify_fn=lambda: calls.append(2))
        self.assertEqual(rc, git_ship.EXIT_POLICY)
        self.assertEqual(calls, [])
        self.assertIn("올리기를 하지 않아요", self.joined())

    # (10) Git-Ship.bat 인수 경로의 실행 명령줄이 수정 전과 같음
    def test_bat_arg_path_unchanged(self):
        text = BAT.read_text(encoding="utf-8")
        self.assertIn('py -3 -B "%PROJECT_ROOT%\\scripts\\git_ship.py" %*', text)
        self.assertIn('python -B "%PROJECT_ROOT%\\scripts\\git_ship.py" %*', text)
        self.assertIn('if "%~1"=="" goto easy', text)
        self.assertIn('call "%~dp0Git-Ship-Easy.bat" %*', text)

    # (11) $null/.lnk 같은 쓰레기는 후보가 아니라 제외 목록으로
    def test_junk_excluded_from_candidates(self):
        for name in ("$null", "x.lnk", "x.url", "Thumbs.db", "desktop.ini"):
            self.w(name, "junk\n")
        self.w("keep.txt", "k\n")
        entries = ge.collect_changes(self.g)
        cand, excl = ge.classify_changes(self.g, self.repo, entries, set())
        self.assertEqual([e["path"] for e in cand], ["keep.txt"])
        excluded = {e["path"]: why for e, why in excl}
        for name in ("$null", "x.lnk", "x.url", "Thumbs.db", "desktop.ini"):
            self.assertIn(name, excluded, name)
            self.assertIn("junk", excluded[name])

    # (12) 훅 검사가 막으면 미리보기 단계에서 한국어로 경로+규칙, 커밋 없음
    def test_hook_blocked_path_held_and_explained_in_korean(self):
        fake='sk-'+'FAKEKEY1234567890abcd'
        self.w('scripts/test_f.py',f'KEY = "{fake}"\n')
        res=ge.commit_flow(self.g,self.repo,input_fn=scripted(''),out=self.out.append)
        self.assertIsInstance(res,dict);self.assertEqual(self.head_count(),1)
        self.assertIn('scripts/test_f.py',res['holds'])
        self.assertIn('provider-key',self.joined());self.assertNotIn(fake,self.joined())

    def test_holding_selected_paths_preserves_foreign_stage(self):
        fake='sk-'+'FAKEKEY1234567890abcd'
        self.w('other.txt','other\n');git(self.repo,'add','other.txt')
        self.w('scripts/test_f.py',f'KEY = "{fake}"\n')
        res=ge.commit_flow(self.g,self.repo,input_fn=scripted('m'),out=self.out.append)
        self.assertIsInstance(res,dict);self.assertEqual(self.staged(),['other.txt'])
        self.assertTrue((self.repo/'scripts/test_f.py').is_file())
        self.assertIn('인덱스에서만',self.joined())

    def test_cancel_keeps_existing_stage(self):
        self.w('other.txt','safe\n');git(self.repo,'add','other.txt')
        rc=ge.commit_flow(self.g,self.repo,input_fn=scripted('q'),out=self.out.append)
        self.assertEqual(rc,0);self.assertEqual(self.staged(),['other.txt'])

    def test_enter_all_over_30_has_one_selection(self):
        for i in range(35):self.w(f'f{i:02d}.txt','x\n')
        prompts=[]
        def answer(prompt):prompts.append(prompt);return ''
        res=ge.commit_flow(self.g,self.repo,input_fn=answer,out=self.out.append)
        self.assertIsInstance(res,dict);self.assertEqual(len(prompts),1)
        self.assertEqual(self.staged(),[]);self.assertEqual(self.head_count(),2)
        self.assertLessEqual(sum('. [' in x for x in self.out),30)

    def test_enter_all_under_30_no_confirm(self):
        for i in range(3):self.w(f's{i}.txt','x\n')
        res=ge.commit_flow(self.g,self.repo,input_fn=scripted(''),out=self.out.append)
        self.assertIsInstance(res,dict);self.assertEqual(self.staged(),[])
        self.assertNotIn('정말 전부',self.joined())

    def test_unstage_flow_clears_stage_only(self):
        self.w("a.txt", "a\n")
        self.w("b.txt", "b\n")
        git(self.repo, "add", "a.txt", "b.txt")
        rc = ge.unstage_flow(self.g, input_fn=scripted("n"),
                             out=self.out.append)
        self.assertEqual(rc, 0)
        self.assertEqual(len(self.staged()), 2)
        rc = ge.unstage_flow(self.g, input_fn=scripted("y"),
                             out=self.out.append)
        self.assertEqual(rc, 0)
        self.assertEqual(self.staged(), [])
        self.assertTrue((self.repo / "a.txt").exists())
        self.assertTrue((self.repo / "b.txt").exists())

    # -- WP1: 대기 중 주기적 진행 알림 ------------------------------------

    def _lock_path(self):
        lockdir = git(self.repo, "rev-parse",
                      "--absolute-git-dir").stdout.strip()
        return Path(lockdir) / "index.lock"

    # (18) lock 대기 중 침묵하지 않고 경과 알림이 나온다
    def test_wait_for_lock_progress(self):
        lock = self._lock_path()
        lock.write_text("held", encoding="ascii")
        ok = ge.wait_for_lock(self.g, out=self.out.append,
                              sleep=lambda s: None, wait_s=3, retries=3,
                              notify_interval_s=6)
        self.assertFalse(ok)
        self.assertTrue(lock.is_file())
        j = self.joined()
        self.assertIn("기다려요", j)
        self.assertIn("[대기 중 6초 경과]", j)
        self.assertIn("index.lock", j)

    # (19) 대기 도중 lock이 풀리면 True로 돌아간다
    def test_wait_for_lock_release_continues(self):
        lock = self._lock_path()
        lock.write_text("held", encoding="ascii")
        calls = {"n": 0}

        def fake_sleep(s):
            calls["n"] += 1
            if calls["n"] >= 2:
                lock.unlink()

        ok = ge.wait_for_lock(self.g, out=self.out.append,
                              sleep=fake_sleep, wait_s=3, retries=5,
                              notify_interval_s=3)
        self.assertTrue(ok)
        self.assertFalse(lock.is_file())
        self.assertIn("기다려요", self.joined())

    # -- WP2: Wait & Ship (commit/push가 lock 해제를 기다렸다 진행) --------

    def _wait_unlock_spy(self, lock, calls):
        """wait_for_lock을 감싸 실제 대기 경로를 타면서 첫 sleep 때 lock 해제."""
        orig = ge.wait_for_lock

        def wrapped(g, out=print, **kw):
            calls.append(1)

            def drop(_s):
                lock.unlink(missing_ok=True)

            return orig(g, out=out, sleep=drop, wait_s=0.01, retries=30, **kw)
        return wrapped

    # (20) 커밋 실행 직전 lock을 기다렸다가 풀리면 커밋이 완료된다
    def test_commit_waits_then_commits_after_unlock(self):
        self.w("a.txt", "a\n")
        git(self.repo, "add", "a.txt")  # 미리 stage -> 선택 없이 진행
        lock = self._lock_path()
        lock.write_text("held", encoding="ascii")
        calls = []
        with mock.patch.object(ge, "wait_for_lock",
                               self._wait_unlock_spy(lock, calls)):
            res = ge.commit_flow(
                self.g, str(self.repo),
                input_fn=scripted(""), out=self.out.append)
        self.assertIsInstance(res, dict)
        self.assertEqual(self.head_count(), 2)
        self.assertGreaterEqual(len(calls), 1)
        self.assertIn("기다려요", self.joined())
        self.assertIn("커밋했어요", self.joined())

    # (21) push 직전 lock을 기다렸다가 풀리면 실제 push_fn까지 도달한다
    def test_push_waits_then_pushes_after_unlock(self):
        origin = self.tmp / "origin.git"
        origin.mkdir()
        git(origin, "init", "--bare", "-b", "main")
        git(self.repo, "remote", "add", "origin", str(origin))
        git(self.repo, "checkout", "-b", "ship/wait")
        lock = self._lock_path()
        lock.write_text("held", encoding="ascii")
        waits = []
        calls = []
        with mock.patch.object(ge, "wait_for_lock",
                               self._wait_unlock_spy(lock, waits)):
            rc = ge.push_flow(
                self.g, input_fn=scripted("y"), out=self.out.append,
                push_fn=lambda: calls.append("push") or
                {"push": {"pushed": False}},
                verify_fn=lambda: {"ok": True, "branch": "ship/wait",
                                   "head": "0" * 40, "remoteSha": "0" * 40,
                                   "remoteReachable": True})
        self.assertEqual(rc, 0)
        self.assertEqual(calls, ["push"])
        self.assertGreaterEqual(len(waits), 1)
        self.assertIn("기다려요", self.joined())

    # (22) lock이 안 풀리면 push_fn을 호출하지 않고 EXIT_LOCK으로 중단
    def test_push_stuck_lock_never_calls_push(self):
        git(self.repo, "checkout", "-b", "ship/stuck")
        lock = self._lock_path()
        lock.write_text("held", encoding="ascii")
        calls = []
        with mock.patch.object(ge, "EASY_LOCK_WAIT_S", 0.01), \
                mock.patch.object(ge, "EASY_LOCK_RETRIES", 2):
            rc = ge.push_flow(
                self.g, input_fn=scripted("y"), out=self.out.append,
                push_fn=lambda: calls.append("push") or {},
                verify_fn=lambda: {"ok": True})
        self.assertEqual(rc, git_ship.EXIT_LOCK)
        self.assertEqual(calls, [])
        self.assertTrue(lock.is_file())

    # -- WP3: 고아 index.lock 감지 + 승인 해제 ------------------------------

    # (23) 오래된 lock + git 프로세스 없음 -> orphan 판정, git 살아있으면 아님
    def test_check_stale_lock_orphan(self):
        lock = self._lock_path()
        lock.write_text("held", encoding="ascii")
        old = time.time() - 120
        os.utime(lock, (old, old))
        info = ge.check_stale_lock(self.g, threshold_s=90,
                                   proc_check=lambda: False)
        self.assertTrue(info["present"])
        self.assertTrue(info["isStale"])
        self.assertFalse(info["gitProcessRunning"])
        self.assertTrue(info["isOrphan"])
        info2 = ge.check_stale_lock(self.g, threshold_s=90,
                                    proc_check=lambda: True)
        self.assertFalse(info2["isOrphan"])
        # 기준 시간 미만이면 stale 아님
        fresh = self._lock_path()
        os.utime(fresh, (time.time(), time.time()))
        info3 = ge.check_stale_lock(self.g, threshold_s=90,
                                    proc_check=lambda: False)
        self.assertFalse(info3["isStale"])

    # (24) 고아 lock에서 y -> 삭제 후 계속(True); n -> lock 유지 + 중단(False)
    def test_orphan_lock_break_on_yes_and_decline(self):
        lock = self._lock_path()
        lock.write_text("held", encoding="ascii")
        old = time.time() - 120
        os.utime(lock, (old, old))
        ok = ge.wait_for_lock(
            self.g, out=self.out.append, sleep=lambda s: None,
            wait_s=0, retries=3, stale_threshold_s=90,
            proc_check=lambda: False, input_fn=scripted("n"))
        self.assertFalse(ok)
        self.assertTrue(lock.is_file())
        self.assertIn("유지", self.joined())
        self.out.clear()
        ok = ge.wait_for_lock(
            self.g, out=self.out.append, sleep=lambda s: None,
            wait_s=0, retries=3, stale_threshold_s=90,
            proc_check=lambda: False, input_fn=scripted("y"))
        self.assertTrue(ok)
        self.assertFalse(lock.is_file())
        self.assertIn("고아 락을 해제했습니다", self.joined())

    # (25) input_fn이 없으면(비대화) 고아 lock도 절대 지우지 않는다
    def test_orphan_lock_never_deleted_without_input(self):
        lock = self._lock_path()
        lock.write_text("held", encoding="ascii")
        old = time.time() - 120
        os.utime(lock, (old, old))
        ok = ge.wait_for_lock(
            self.g, out=self.out.append, sleep=lambda s: None,
            wait_s=0, retries=2, stale_threshold_s=90,
            proc_check=lambda: False, input_fn=None)
        self.assertFalse(ok)
        self.assertTrue(lock.is_file())

    # -- WP4: 커밋 대상 합산 안내 -------------------------------------------

    # (26) 신규 N개 + 기존 staged M개 = 총 X개 커밋 대상 안내
    def test_commit_count_includes_new_and_existing(self):
        self.w('old.txt','o\n');git(self.repo,'add','old.txt')
        self.w('new1.txt','n\n');self.w('new2.txt','n\n')
        res=ge.commit_flow(self.g,self.repo,input_fn=scripted(''),out=self.out.append)
        self.assertIsInstance(res,dict);self.assertEqual(res['shipped'],3)
        self.assertEqual(res['remainingStaged'],0)


class AutoShipCase(unittest.TestCase):
    """T1~T10: 빈 Enter = 자동 올리기(auto_ship_flow) fixture 검증.

    전부 temp dir의 git init + bare 로컬 원격에서만 돌린다. 실제 저장소/
    네트워크는 절대 건드리지 않는다."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="gitship-auto-"))
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-b", "main")
        git(self.repo, "config", "user.email", "t@example.invalid")
        git(self.repo, "config", "user.name", "sandbox")
        (self.repo / "README.md").write_text("hello\n", encoding="utf-8")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-m", "init")
        self.g = git_ship.Git(str(self.repo), GIT)
        self.out = []

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    # -- helpers ---------------------------------------------------------
    def w(self, rel, text):
        p = self.repo / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        return p

    def joined(self):
        return "\n".join(self.out)

    def head_count(self):
        return int(git(self.repo, "rev-list", "--count", "HEAD").stdout.strip())

    def staged(self):
        return [p for p in git(
            self.repo, "diff", "--cached", "--name-only").stdout.splitlines()
            if p]

    def make_origin(self):
        origin = self.tmp / "origin.git"
        origin.mkdir()
        git(origin, "init", "--bare", "-b", "main")
        git(self.repo, "remote", "add", "origin", str(origin))
        return origin

    def ahead_behind(self):
        out = git(self.repo, "rev-list", "--left-right", "--count",
                  "@{u}...HEAD").stdout.split()
        return int(out[0]), int(out[1])

    def write_manifest(self, skill, files):
        """files: {rel: text}. 매니페스트 + data/agent-archive 사본을 만든다."""
        entries = {}
        for rel, text in files.items():
            arc = self.repo / "data" / "agent-archive" / "skills" / skill / rel
            arc.parent.mkdir(parents=True, exist_ok=True)
            arc.write_text(text, encoding="utf-8")
            entries[rel] = hashlib.sha256(text.encode("utf-8")).hexdigest()
        man = self.repo / "data" / "agent-handoff" / "t" / \
            "moved-skills-manifest.json"
        man.parent.mkdir(parents=True, exist_ok=True)
        man.write_text(json.dumps({skill: {"files": entries}}),
                       encoding="utf-8")

    # T1: Enter -> 커밋 1개 + push -> 앞 0 / 뒤 0
    def test_t1_enter_commits_pushes_and_verifies(self):
        self.make_origin()
        git(self.repo, "checkout", "-b", "ship/t1")
        self.w("a.txt", "a\n")
        rc = ge.auto_ship_flow(self.g, str(self.repo),
                               input_fn=scripted(), out=self.out.append)
        self.assertEqual(rc, 0)
        self.assertEqual(self.head_count(), 2)
        self.assertEqual(self.ahead_behind(), (0, 0))
        j = self.joined()
        self.assertIn("커밋했어요", j)
        self.assertIn("올렸어요", j)
        last = self.repo / "var" / "git-ship-easy" / "last-ship.json"
        rec = json.loads(last.read_text(encoding="utf-8"))
        self.assertTrue(rec["pushed"])
        self.assertTrue(rec["verified"])

    # T2: 매니페스트 없는 대량 삭제(25개) -> HOLD, 나머지만 커밋·push
    def test_t2_unexplained_bulk_delete_is_held(self):
        self.make_origin()
        git(self.repo, "checkout", "-b", "ship/t2")
        self.w(".gitignore", "data/\n")
        for i in range(25):
            self.w(f"uploads/chat/f{i:02d}.md", f"doc{i}\n")
        self.w("keep.txt", "k\n")
        git(self.repo, "add", ".gitignore", "uploads", "keep.txt")
        git(self.repo, "commit", "-m", "seed")
        for i in range(25):
            (self.repo / "uploads" / "chat" / f"f{i:02d}.md").unlink()
        self.w("new.txt", "n\n")
        rc = ge.auto_ship_flow(self.g, str(self.repo),
                               input_fn=scripted(), out=self.out.append)
        self.assertEqual(rc, 0)
        j = self.joined()
        self.assertIn("자동 보류 25개", j)
        self.assertIn("대량 삭제", j)
        show = git(self.repo, "show", "--name-status",
                   "--pretty=format:", "HEAD").stdout
        self.assertIn("new.txt", show)
        self.assertNotIn("uploads/chat/", show)   # 삭제는 커밋에 안 들어감
        git(self.repo, "cat-file", "-e", "HEAD:uploads/chat/f00.md")

    # T3: 매니페스트로 설명되는 삭제 -> 커밋에 포함
    def test_t3_manifest_explained_delete_is_committed(self):
        self.make_origin()
        git(self.repo, "checkout", "-b", "ship/t3")
        self.w(".gitignore", "data/\n")
        self.w(".agents/skills/foo-skill/SKILL.md", "skill body\n")
        git(self.repo, "add", ".gitignore", ".agents")
        git(self.repo, "commit", "-m", "seed")
        (self.repo / ".agents" / "skills" / "foo-skill" /
         "SKILL.md").unlink()
        self.write_manifest("foo-skill", {"SKILL.md": "skill body\n"})
        rc = ge.auto_ship_flow(self.g, str(self.repo),
                               input_fn=scripted(), out=self.out.append)
        self.assertEqual(rc, 0)
        show = git(self.repo, "show", "--name-status",
                   "--pretty=format:", "HEAD").stdout
        self.assertIn("D", show)
        self.assertIn(".agents/skills/foo-skill/SKILL.md", show)
        self.assertNotIn("자동 보류", self.joined())

    # T4: [AD] -> 인덱스에서만 빠지고 디스크·다른 stage 그대로
    def test_t4_ad_unstaged_only(self):
        self.make_origin()
        git(self.repo, "checkout", "-b", "ship/t4")
        self.w("ghost.txt", "g\n")
        git(self.repo, "add", "ghost.txt")
        (self.repo / "ghost.txt").unlink()          # AD 상태
        self.w("keep.txt", "k\n")
        git(self.repo, "add", "keep.txt")
        rc = ge.auto_ship_flow(self.g, str(self.repo),
                               input_fn=scripted(), out=self.out.append)
        self.assertEqual(rc, 0)
        j = self.joined()
        self.assertIn("AD", j)
        self.assertIn("인덱스에서만 뺐어요", j)
        ls = git(self.repo, "ls-files", "ghost.txt").stdout.strip()
        self.assertEqual(ls, "")                     # 인덱스에서 제거됨
        self.assertFalse((self.repo / "ghost.txt").exists())  # 디스크 그대로
        show = git(self.repo, "show", "--name-status",
                   "--pretty=format:", "HEAD").stdout
        self.assertIn("keep.txt", show)              # 다른 stage는 커밋됨
        self.assertNotIn("ghost.txt", show)

    # T5: main 브랜치 -> push 거부, 커밋은 로컬 유지
    def test_t5_main_branch_commit_kept_no_push(self):
        self.make_origin()
        calls = []
        self.w("a.txt", "a\n")
        rc = ge.auto_ship_flow(
            self.g, str(self.repo), input_fn=scripted(), out=self.out.append,
            push_fn=lambda: calls.append(1) or {"push": {"pushed": True}},
            verify_fn=lambda: {"ok": True})
        self.assertEqual(rc, git_ship.EXIT_POLICY)
        self.assertEqual(self.head_count(), 2)       # 커밋은 남음
        self.assertEqual(calls, [])                  # push는 안 부름
        self.assertIn("올리기를 하지 않아요", self.joined())

    # T6: 비밀 문자열 stage -> 커밋·push 안 됨, 값 출력 없음
    def test_t6_staged_secret_held_without_blocking_safe_push(self):
        self.make_origin()
        git(self.repo, "checkout", "-b", "ship/t6")
        secret = "sk-" + "Qm7vX2pL9wK4tR8zN5bH3jF6"
        self.w("secret.txt", f'key = "{secret}"\n')
        git(self.repo, "add", "secret.txt")
        calls = []
        rc = ge.auto_ship_flow(
            self.g, str(self.repo), input_fn=scripted(), out=self.out.append,
            push_fn=lambda: calls.append(1) or {"push": {"pushed": True}},
            verify_fn=lambda: {"ok": True,"branch":"ship/t6","head":"a"*40})
        self.assertEqual(rc, 0)
        self.assertEqual(self.head_count(), 1)
        self.assertEqual(calls, [1])
        self.assertNotIn(secret, self.joined())
        self.assertEqual(self.staged(),[])
        self.assertTrue((self.repo/"secret.txt").is_file())

    # T7: 원격이 앞섬 -> push 안 하고 안내, 커밋은 로컬 유지
    def test_t7_remote_ahead_skips_push(self):
        origin = self.make_origin()
        git(self.repo, "checkout", "-b", "ship/t7")
        git(self.repo, "push", "-u", "origin", "ship/t7")
        other = self.tmp / "other"
        git(self.tmp, "clone", str(origin), str(other))
        git(other, "config", "user.email", "o@example.invalid")
        git(other, "config", "user.name", "other")
        git(other, "checkout", "ship/t7")
        (other / "remote.txt").write_text("r\n", encoding="utf-8")
        git(other, "add", "remote.txt")
        git(other, "commit", "-m", "remote ahead")
        git(other, "push", "origin", "ship/t7")
        git(self.repo, "fetch", "origin", "ship/t7")  # upstream 최신화
        self.w("mine.txt", "m\n")
        calls = []
        rc = ge.auto_ship_flow(
            self.g, str(self.repo), input_fn=scripted(), out=self.out.append,
            push_fn=lambda: calls.append(1) or {"push": {"pushed": True}},
            verify_fn=lambda: {"ok": True})
        self.assertEqual(rc, git_ship.EXIT_VERIFY)
        self.assertEqual(self.head_count(), 2)       # 내 커밋은 남음
        self.assertEqual(calls, [])
        self.assertIn("앞서 있어요", self.joined())

    # T8: 커밋만 있고 바뀐 것 없음 -> push만 다시
    def test_t8_ahead_only_pushes_again(self):
        self.make_origin()
        git(self.repo, "checkout", "-b", "ship/t8")
        git(self.repo, "push", "-u", "origin", "ship/t8")
        self.w("a.txt", "a\n")
        git(self.repo, "add", "a.txt")
        git(self.repo, "commit", "-m", "pending")
        rc = ge.auto_ship_flow(self.g, str(self.repo),
                               input_fn=scripted(), out=self.out.append)
        self.assertEqual(rc, 0)
        self.assertEqual(self.head_count(), 2)       # 새 커밋 없음
        self.assertEqual(self.ahead_behind(), (0, 0))
        j = self.joined()
        self.assertIn("커밋할 변경이 없어요", j)
        self.assertIn("올렸어요", j)

    # T9: 콘솔 아닌 stdin -> exit 2
    def test_t9_nonconsole_stdin_exits_2(self):
        env = dict(os.environ)
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        proc = subprocess.run(
            [sys.executable, "-B", str(EASY), "--root", str(self.repo)],
            stdin=subprocess.DEVNULL, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=10, env=env)
        self.assertEqual(proc.returncode, 2)

    # T10: 빈 Enter는 자동 올리기, 0/q는 끝내기
    def test_t10_enter_is_autoship_zero_quits(self):
        calls = []
        with mock.patch.object(ge, "auto_ship_flow",
                               lambda *a, **k: calls.append("auto") or 0):
            ge.run_menu_action(self.g, str(self.repo), "",
                               input_fn=scripted(), out=self.out.append)
        self.assertEqual(calls, ["auto"])
        for ans in ("0", "q"):
            self.assertEqual(
                ge.run_menu_action(self.g, str(self.repo), ans,
                                   input_fn=scripted(),
                                   out=self.out.append), "quit")



class UnblockCase(unittest.TestCase):
    setUp = AutoShipCase.setUp
    tearDown = AutoShipCase.tearDown
    w = AutoShipCase.w
    staged = AutoShipCase.staged
    head_count = AutoShipCase.head_count
    make_origin = AutoShipCase.make_origin
    joined = AutoShipCase.joined
    write_manifest = AutoShipCase.write_manifest

    def seed(self):
        self.w('.agents/skills/INDEX.md', '.agents/skills/referenced/SKILL.md\n')
        self.w('.agents/skills/referenced/SKILL.md', 'skill\n')
        for i in range(25):
            self.w(f'delete/f{i}.txt', 'old\n')
        for i in range(20):
            self.w(f'foreign/m{i}.txt', 'old\n')
        git(self.repo, 'add', '.agents', 'delete', 'foreign')
        git(self.repo, 'commit', '-m', 'seed')
        for i in range(20):
            self.w(f'foreign/m{i}.txt', 'new\n')
            self.w(f'foreign/a{i}.txt', 'added\n')
        for p in (self.repo/'delete').iterdir():
            p.unlink()
        (self.repo/'.agents/skills/referenced/SKILL.md').unlink()
        for name in ('$null', 'x.md.bak-20261004-dotcard', 'guard.txt'):
            self.w(name, 'synthetic\n')
        git(self.repo, 'add', 'foreign', 'delete', '.agents', '$null',
            'x.md.bak-20261004-dotcard', 'guard.txt')
        self.w('mine.txt', 'mine\n')

    def test_u1_menu_single_question_bounded_foreign_summary(self):
        self.seed(); prompts=[]
        def answer(prompt):
            prompts.append(prompt); return ''
        res=ge.commit_flow(self.g, self.repo, input_fn=answer, out=self.out.append)
        self.assertIsInstance(res, dict)
        self.assertEqual(len(prompts),1)
        self.assertLessEqual(sum('foreign/' in line for line in self.out),30)
        self.assertTrue((self.repo/'var/git-ship-easy/staged-foreign.txt').is_file())
        self.assertEqual(git(self.repo,'show','HEAD:mine.txt').stdout,'mine\n')

    def test_u2_mine_only_preserves_foreign_blobs(self):
        self.seed()
        before=git(self.repo,'diff','--cached','--raw','-z').stdout
        res=ge.commit_flow(self.g,self.repo,input_fn=scripted('m'),out=self.out.append)
        self.assertIsInstance(res,dict)
        self.assertEqual(git(self.repo,'diff','--cached','--raw','-z').stdout,before)
        self.assertEqual(git(self.repo,'show','--pretty=format:','--name-only','HEAD').stdout.strip(),'mine.txt')

    def test_u3_auto_holds_junk_guard_and_deletes_but_ships_clean(self):
        self.seed(); self.make_origin(); git(self.repo,'checkout','-b','ship/unblock')
        def guard(root,exe):
            rows=git_ship.staged_name_status(self.g)
            findings=[{'pathHash':hashlib.sha256(p.encode()).hexdigest(),'rule':'fixture-guard'}
                      for st,p in rows if p=='guard.txt']
            return {'ok':not findings,'findings':findings}
        with mock.patch.object(git_ship,'run_staged_guard',side_effect=guard):
            rc=ge.auto_ship_flow(self.g,self.repo,out=self.out.append)
        self.assertEqual(rc,0)
        record=json.loads((self.repo/ge.LAST_SHIP_JSON).read_text(encoding='utf-8'))
        self.assertEqual(len(record['holds']),29)
        self.assertEqual(len(record['unstaged']),29)
        self.assertTrue(record['pushed'])
        for p in ('$null','x.md.bak-20261004-dotcard','guard.txt'):
            self.assertTrue((self.repo/p).is_file())
            self.assertNotIn(p,self.staged())
        self.assertEqual(git(self.repo,'show','HEAD:mine.txt').stdout,'mine\n')

    def test_auto_all_added_files_held_reports_zero_shipped(self):
        self.w('held.txt', 'safe\n')
        finding = {'pathHash': hashlib.sha256(b'held.txt').hexdigest(),
                   'rule': 'fixture-guard'}
        with mock.patch.object(git_ship, 'run_staged_guard',
                               return_value={'ok': False, 'findings': [finding]}), \
                mock.patch.object(git_ship, 'cmd_commit') as commit:
            result = ge.auto_ship_flow(self.g, self.repo, commit_only=True,
                                       selected_paths=['held.txt'], out=self.out.append)
        self.assertEqual(result['shipped'], 0)
        self.assertIsNone(result['commitSha'])
        self.assertEqual(result['stagedThenHeld'], 1)
        commit.assert_not_called()
        record = json.loads((self.repo / ge.LAST_SHIP_JSON).read_text(encoding='utf-8'))
        self.assertEqual(record['shipped'], 0)

    def test_u4_failures_always_record_step_and_redacted_single_line(self):
        self.make_origin(); git(self.repo,'checkout','-b','ship/errors')
        cases=[('restore','_git_restore_staged'),('add','_git_add'),
               ('scan','run_scan'),('guard','run_staged_guard'),
               ('commit','cmd_commit'),('push',None),('verify',None)]
        for step,symbol in cases:
            with self.subTest(step=step):
                self.w('a.txt',step+'\n'); self.w('$null','synthetic\n')
                git(self.repo,'add','$null')
                secret='gsk_'+'A'*24
                err=git_ship.ShipError(1,'synthetic '+secret+'\nsecond line')
                target=ge if step in ('restore','add') else git_ship
                ctx=mock.patch.object(target,symbol,side_effect=err) if symbol else mock.patch.object(ge,'_remote_ahead',return_value=None)
                kw={'push_fn':lambda:{'push':{'pushed':False}},'verify_fn':lambda:{'ok':True,'branch':'ship/errors','head':'a'*40}}
                if step=='push': kw['push_fn']=mock.Mock(side_effect=err)
                if step=='verify': kw['verify_fn']=mock.Mock(side_effect=err)
                with ctx:
                    rc=ge.auto_ship_flow(self.g,self.repo,out=self.out.append,**kw)
                rec=json.loads((self.repo/ge.LAST_SHIP_JSON).read_text(encoding='utf-8'))
                self.assertNotEqual(rc,0)
                self.assertEqual(rec['failedStep'],step)
                if rec['commitSha'] is None:
                    self.assertEqual(rec['shipped'], 0)
                self.assertNotIn('\n',rec['error'])
                self.assertNotIn(secret,rec['error']); self.assertNotIn(secret,self.joined())
                self.assertTrue(any(rec['error'] in line and step in line for line in self.out))

    def test_u5_auto_nonconsole_exit_matches_result(self):
        self.make_origin(); git(self.repo,'checkout','-b','ship/cli')
        self.w('mine.txt','mine\n')
        proc=subprocess.run([sys.executable,'-B',str(EASY),'--root',str(self.repo),
                             '--git-exe',GIT,'--auto'],stdin=subprocess.DEVNULL,
                            capture_output=True,text=True,encoding='utf-8',timeout=30)
        self.assertEqual(proc.returncode,0,proc.stderr)
        rec=json.loads((self.repo/ge.LAST_SHIP_JSON).read_text(encoding='utf-8'))
        self.assertTrue(rec['pushed']); self.assertTrue(rec['verified'])
        self.assertIsNone(rec['failedStep']); self.assertEqual(rec['error'],'')

    def test_u6_menu_three_single_question_pushes_selected_only(self):
        self.seed(); self.make_origin();git(self.repo,'checkout','-b','ship/menu3')
        before=git(self.repo,'diff','--cached','--raw','-z').stdout
        prompts=[]
        def answer(prompt):prompts.append(prompt);return 'm'
        rc=ge.run_menu_action(self.g,self.repo,'3',input_fn=answer,out=self.out.append)
        self.assertEqual(rc,0);self.assertEqual(len(prompts),1)
        self.assertEqual(git(self.repo,'diff','--cached','--raw','-z').stdout,before)
        self.assertEqual(git(self.repo,'rev-parse','HEAD').stdout.strip(),
                         git(self.repo,'ls-remote','origin','refs/heads/ship/menu3').stdout.split()[0])

    def test_u7_pathspec_repartition_holds_only_bad_path_and_keeps_disk(self):
        self.w('good.txt','safe\n');self.w('bad.txt','safe\n')
        before=(self.repo/'bad.txt').read_bytes(); real=self.g.run; calls=[]
        def run(argv,**kw):
            if argv[0]=='add' and '--' in argv:
                calls.append(argv)
                if any(p.endswith('bad.txt') for p in argv):
                    return 1,'',"fatal: pathspec 'bad.txt' did not match any files"
            return real(argv,**kw)
        with mock.patch.object(self.g,'run',side_effect=run):
            res=ge.auto_ship_flow(self.g,self.repo,commit_only=True,out=self.out.append)
        self.assertIsInstance(res,dict);self.assertIn('bad.txt',res['holds'])
        self.assertEqual(git(self.repo,'show','HEAD:good.txt').stdout,'safe\n')
        self.assertEqual((self.repo/'bad.txt').read_bytes(),before)
        self.assertLessEqual(len(calls),6)

    def test_u8_menu_setup_failure_is_persisted(self):
        self.w('old.txt','safe\n');git(self.repo,'add','old.txt')
        original=Path.write_text
        def write(path,*a,**kw):
            if path.name=='staged-foreign.txt':raise OSError('synthetic summary failure')
            return original(path,*a,**kw)
        with mock.patch.object(Path,'write_text',new=write):
            rc=ge.commit_flow(self.g,self.repo,input_fn=scripted(),out=self.out.append)
        self.assertNotEqual(rc,0)
        rec=json.loads((self.repo/ge.LAST_SHIP_JSON).read_text(encoding='utf-8'))
        self.assertEqual(rec['failedStep'],'verify');self.assertEqual(rec['error'],'OSError')

    # (U9) MAX_PATH를 넘는 깊은 폴더가 있어도 manifest 탐색·올리기가 멈추지 않는다
    def test_u9_deep_dir_manifest_walk_does_not_crash(self):
        if os.name != "nt":
            self.skipTest("MAX_PATH 재현은 Windows 전용")
        self.make_origin(); git(self.repo,'checkout','-b','ship/deep')
        deep = self.repo / "data" / "agent-handoff" / "deep"
        for i in range(8):
            deep = deep / ("d" * 30 + str(i))
        os.makedirs("\\\\?\\" + str(deep))
        self.write_manifest("moved-skill", {"SKILL.md": "moved\n"})
        self.w("keep.txt", "k\n")
        try:
            moved = ge.load_manifest_moved(str(self.repo))
            self.assertIn(".agents/skills/moved-skill/SKILL.md", moved)
            res = ge.auto_ship_flow(self.g, str(self.repo),
                                    input_fn=scripted(), out=self.out.append)
            self.assertEqual(res, 0)
            self.assertEqual(
                git(self.repo, "show", "HEAD:keep.txt").stdout, "k\n")
        finally:
            shutil.rmtree(
                "\\\\?\\" + str(
                    self.repo / "data" / "agent-handoff" / "deep"),
                ignore_errors=True)

    # (U10) 수집 뒤 사라진 신규 파일은 '파일 없음' hold로 건너뛴다
    def test_u10_vanished_candidate_is_held_not_crash(self):
        self.w("keep.txt", "k\n")
        fake = [{"xy": "??", "path": "ghost.txt"},
                {"xy": "??", "path": "keep.txt"}]
        with mock.patch.object(ge, "collect_changes", return_value=fake):
            res = ge.auto_ship_flow(self.g, str(self.repo),
                                    out=self.out.append, commit_only=True)
        self.assertIsInstance(res, dict)
        self.assertIn("파일 없음", res["holds"]["ghost.txt"][0])
        self.assertEqual(
            git(self.repo, "show", "HEAD:keep.txt").stdout, "k\n")

    # (U11) lease 확인 실패는 FileNotFoundError가 아니라 정책 보류(lease 단계)
    def test_u11_lease_error_is_policy_hold(self):
        self.w("a.txt", "a\n")
        with mock.patch.object(
                ge, "load_active_lease_paths",
                lambda root, timeout=20: (set(), "error", 0)):
            res = ge.auto_ship_flow(self.g, str(self.repo),
                                    out=self.out.append, commit_only=True)
        self.assertEqual(res, git_ship.EXIT_POLICY)
        rec = json.loads(
            (self.repo / ge.LAST_SHIP_JSON).read_text(encoding="utf-8"))
        self.assertEqual(rec["failedStep"], "lease")

    # (U12) FileNotFoundError 기록에는 단계 이름 + 프로젝트 상대 경로가 붙는다
    def test_u12_fnf_error_carries_step_and_relpath(self):
        target = str(self.repo / "sub" / "gone.txt")
        err = FileNotFoundError(3, "no such file", target)
        with mock.patch.object(ge, "collect_changes", side_effect=err):
            res = ge.auto_ship_flow(self.g, str(self.repo),
                                    out=self.out.append, commit_only=True)
        self.assertNotEqual(res, 0)
        rec = json.loads(
            (self.repo / ge.LAST_SHIP_JSON).read_text(encoding="utf-8"))
        self.assertEqual(rec["failedStep"], "collect")
        self.assertIn("sub/gone.txt", rec["error"])
        self.assertNotIn(str(self.repo), rec["error"])

    # (U13) 삭제된 추적 파일은 읽기 없이 그대로 삭제 커밋된다
    def test_u13_deleted_file_commits_deletion(self):
        self.make_origin(); git(self.repo, "checkout", "-b", "ship/del")
        self.w("gone.txt", "g\n"); git(self.repo, "add", "gone.txt")
        git(self.repo, "commit", "-m", "seed")
        (self.repo / "gone.txt").unlink()
        res = ge.auto_ship_flow(self.g, str(self.repo), out=self.out.append,
                                commit_only=True)
        self.assertIsInstance(res, dict)
        show = git(self.repo, "show", "--name-status", "--pretty=format:",
                   "HEAD").stdout
        self.assertIn("D", show)
        self.assertIn("gone.txt", show)


if __name__ == "__main__":
    unittest.main()
