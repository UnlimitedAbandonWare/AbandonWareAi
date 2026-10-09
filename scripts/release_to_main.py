#!/usr/bin/env python3
"""release_to_main.py -- 커밋된 작업 브랜치(RC)를 main에 "스냅샷 커밋"으로 올리는 전용 도구.

배경: main(b2eaba46, 2026-09-23 스냅샷)과 codex/owned-runtime-browser-restart는
공통 조상이 없어 일반 merge/rebase가 불가능하다. 이 도구는

    git commit-tree <RC>^{tree} -p <옛 main> -p <RC>

로 "내용=RC, 부모=[옛 main, RC]"인 커밋을 만든다. 첫 부모가 옛 main이라
main 입장에서는 fast-forward이고 force push가 필요 없으며, 두 이력 모두
보존된다. 작업 트리/현재 체크아웃은 건드리지 않는다.

하위 명령:
  plan           (기본) 게이트만 검사. 아무것도 바꾸지 않는다(원격 fetch만).
  apply          --yes-main + env AWX_RELEASE_MAIN_EXPECT=<옛 main sha> 필수.
  verify         원격 상태와 스냅샷 일치 확인.
  rollback-plan  되돌리기 명령만 출력(실행하지 않음). force push 없이 복귀 가능.

절대 금지: -f/--force/--force-with-lease, reset --hard, rebase, 기존
태그·브랜치 덮어쓰기/삭제, 작업 트리 전환, 미커밋 변경 커밋.
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import time
from pathlib import Path
from urllib.parse import urlsplit

SCHEMA = "awx.release-to-main.v1"

DEFAULT_BRANCH = "codex/owned-runtime-browser-restart"
DEFAULT_EXPECT_MAIN = "b2eaba4679f70ded860b052faa59b29073d0c859"
ARCHIVE_TAG = "archive/main-20260923-b2eaba46"
ARCHIVE_BRANCH = "archive/main-old"
MAIN_HEAD = "refs/heads/main"
ENV_EXPECT = "AWX_RELEASE_MAIN_EXPECT"
ENV_APPROVED = "AWX_PUBLISH_APPROVED"
ENV_GIT_EXE = "AWX_GIT_EXE"

DEFAULT_LOG = "docs/reports/agent-reviews/devin-release-to-main-tool-db8a0a4d/release_log.json"

MAX_BLOB_BYTES = 50 * 1024 * 1024          # GitHub hard limit 근처 — 실패 처리
G4_TIMEOUT_S = 1200
PUSH_TIMEOUT_S = 1500   # pre-push 훅의 전체 트리 스캔이 몇 분 걸릴 수 있다(git_ship과 동일)
GIT_TIMEOUT_S = 120

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_HOLD = 3
EXIT_ERROR = 4

# ------------------------------------------------------------- git runner

def resolve_git_exe(explicit=None):
    if explicit:
        return explicit
    env = os.environ.get(ENV_GIT_EXE)
    if env:
        return env
    cand = r"F:\git\cmd\git.exe"
    if Path(cand).is_file():
        return cand
    return "git"


def _sanitize(text):
    """stderr/stdout에서 자격증명이 새지 않게 URL userinfo를 마스킹한다."""
    if text is None:
        return ""
    text = re.sub(r"(https?://)[^/@\s]+@", r"\1***@", text)
    text = re.sub(r"(?i)(token|password|passwd|secret)[=:\s]+[^\s]+",
                  r"\1=***", text)
    return text


class Git:
    """모든 git 호출을 기록한다. force 계열 인자는 어떤 경로로도 만들지 않는다."""

    def __init__(self, exe, repo, log):
        self.exe = exe
        self.repo = str(repo)
        self.log = log            # list[dict] — release_log.json에 그대로 들어감

    def run(self, args, timeout=GIT_TIMEOUT_S, optional_locks=True,
            env_extra=None):
        for a in args:
            if a in ("-f", "--force", "--force-with-lease") or \
                    a.startswith("--force"):
                raise AssertionError("force push flag blocked: " + a)
        env = dict(os.environ)
        if optional_locks:
            env["GIT_OPTIONAL_LOCKS"] = "0"
        env["GIT_TERMINAL_PROMPT"] = "0"
        env["GCM_INTERACTIVE"] = "Never"
        if env_extra:
            env.update(env_extra)
        started = time.time()
        try:
            proc = subprocess.run(
                [self.exe] + args, cwd=self.repo, env=env,
                capture_output=True, text=True, encoding="utf-8",
                errors="replace", timeout=timeout)
            rc, out, err = proc.returncode, proc.stdout, proc.stderr
        except subprocess.TimeoutExpired:
            rc, out, err = 124, "", "timeout"
        self.log.append({
            "at": _utcnow(), "argv": [self.exe] + list(args),
            "rc": rc, "ms": int((time.time() - started) * 1000),
            "stderr": _sanitize(err)[-2000:]})
        return rc, out, err

    def out(self, args, **kw):
        rc, out, err = self.run(args, **kw)
        return out.strip() if rc == 0 else ""


# ------------------------------------------------------------- util

def _utcnow():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def _force_utf8():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


def _norm(p):
    return str(p).replace("\\", "/")


def _is_sha(text):
    return bool(re.fullmatch(r"[0-9a-fA-F]{4,64}", text or ""))


# ------------------------------------------------------------- secret scan

# (이름, Python 정규식, 값그룹index|0=전체토큰, git-grep용 느슨한 ERE 후보식).
# git grep는 POSIX ERE라 lookbehind 불가 → ERE로 후보 줄을 좁히고
# Python 정규식으로 다시 정밀 판정한다. 값계 패턴은 자리표시자 필터를 거친다.
SECRET_PATTERNS = [
    ("openai-sk",     re.compile(r"(?<![A-Za-z0-9_/-])sk-[A-Za-z0-9_-]{16,}"), 0,
     r"sk-[A-Za-z0-9_-]{16,}"),
    ("google-aiza",   re.compile(r"AIza[0-9A-Za-z_-]{20,}"), 0,
     r"AIza[0-9A-Za-z_-]{20,}"),
    ("vercel-vck",    re.compile(r"vck_[0-9A-Za-z]{10,}"), 0,
     r"vck_[0-9A-Za-z]{10,}"),
    ("github-ghp",    re.compile(r"ghp_[0-9A-Za-z]{20,}"), 0,
     r"ghp_[0-9A-Za-z]{20,}"),
    ("github-pat",    re.compile(r"github_pat_[0-9A-Za-z_]{20,}"), 0,
     r"github_pat_[0-9A-Za-z_]{20,}"),
    ("slack-xox",     re.compile(r"xox[baprs]-[0-9A-Za-z-]{10,}"), 0,
     r"xox[baprs]-[0-9A-Za-z-]{10,}"),
    ("aws-akia",      re.compile(r"(?<![A-Z0-9])AKIA[0-9A-Z]{16}"), 0,
     r"AKIA[0-9A-Z]{16}"),
    ("pem-block",     re.compile(r"-----BEGIN [A-Z0-9 ]{3,}-----"), 0,
     r"-----BEGIN [A-Z0-9 ]+-----"),
    ("client_secret", re.compile(
        r"client_secret[\"']?\s*[=:]\s*[\"']?([^\s\"',}]{4,})", re.I), 1,
     r"client_secret"),
    ("naver-secret",  re.compile(
        r"NAVER_APIHUB_CLIENT_SECRET[\"']?\s*=\s*[\"']?([^\s#'\"]{1,})"), 1,
     r"NAVER_APIHUB_CLIENT_SECRET"),
]

_PLACEHOLDER_HINT = re.compile(
    r"(?i)(example|your|changeme|change-me|placeholder|dummy|fake|sample|"
    r"synthetic|mock|fixture|redact|xxxx|xxxxxx|\*{3,}|test[-_]?key|notreal|"
    r"key-here|keyhere|replace|insert|<[^>]*>|\$\{[^}]*\}|%[A-Z_]+%|"
    r"^\.{2,}|todo|none|null)")

_FIXTURE_PATH = re.compile(r"(?i)(^|/)(test[s]?[_/.-]|.*test_|.*_test|"
                           r".*\.test\.|.*mock|.*fixture|.*spec)")

# 값 위치가 따옴표 없이 `name(` 형태면 저장된 비밀값이 아니라 호출식이다
# (예: client_secret=secrets.token_hex(12)).
_CALL_EXPR = re.compile(r"[A-Za-z_][\w.]*\(")


def is_placeholder(token, path=""):
    """실제 값처럼 보이지 않는 자리표시자면 True.

    - 흔한 자리표시자 힌트 단어, `{}`/`__`-형 구조 토큰은 어디서든 통과
    - 테스트/목 파일의 짧은(<16자) 값은 실제 제공자 비밀값(통상 20자+)이
      아니라고 보고 자리표시자로 분류한다. 긴 값은 테스트 파일에서도 의심."""
    t = (token or "").strip().strip("\"'").strip()
    if not t:
        return True
    if _PLACEHOLDER_HINT.search(t):
        return True
    if t[0] in "{[(<" or t.startswith("__") or t.endswith("__"):
        return True
    if len(t) < 16 and _FIXTURE_PATH.search(_norm(path)):
        return True
    return False


def _mask(token):
    t = token.strip()
    return t[:4] + "***" if len(t) >= 4 else "***"


def _scan_lines(lines, where, findings, placeholders):
    """lines 안에서 패턴 히트를 수집한다. 원문 라인은 절대 저장하지 않는다."""
    for path, lineno, text in lines:
        for name, pat, group, _ere in SECRET_PATTERNS:
            for m in pat.finditer(text):
                token = m.group(group) if group else m.group(0)
                hit = {"pattern": name, "path": path, "line": lineno,
                       "where": where, "masked": _mask(token)}
                # 호출식(secret=secrets.token_hex(12))은 리터럴 비밀값이 아님.
                # 따옴표로 감싼 값은 제외한다.
                start = m.start(group)
                quoted = start > 0 and text[start - 1] in "\"'"
                call_expr = bool(group and _CALL_EXPR.match(token)
                                 and not quoted)
                if is_placeholder(token, path) or call_expr:
                    placeholders.append(hit)
                else:
                    findings.append(hit)


def gate_secret_scan(g, rc_sha, remote_ref):
    """G2: RC 트리 전체 + 아직 origin에 없는 커밋의 diff를 스캔한다."""
    findings, placeholders = [], []
    scanned = {"treeFiles": None, "diffCommits": []}

    # 1) RC 트리 전체: 패턴별 git grep(ERE 후보 축소). 출력 원문은 파싱 후 버린다.
    for name, pat, group, ere in SECRET_PATTERNS:
        rc, out, _err = g.run(["grep", "-I", "-n", "-e", ere, rc_sha],
                              timeout=300)
        if rc not in (0, 1):           # 1 = no match
            continue
        rows = []
        for line in out.splitlines():
            # <sha>:<path>:<lineno>:<text>
            parts = line.split(":", 3)
            if len(parts) == 4 and parts[1] and parts[2].isdigit():
                rows.append((parts[1], int(parts[2]), parts[3]))
        _scan_lines(rows, "rc-tree", findings, placeholders)
        scanned["treeFiles"] = "git-grep"

    # 2) 아직 origin에 없는 커밋들의 diff(+라인만)
    remote_sha = g.out(["rev-parse", remote_ref]) if remote_ref else ""
    if remote_sha:
        unpushed = [l for l in g.out(
            ["rev-list", f"{remote_sha}..{rc_sha}"]).splitlines() if l]
        scanned["diffCommits"] = unpushed
        if unpushed:
            rc, out, _ = g.run(
                ["diff", "--unified=0", f"{remote_sha}..{rc_sha}"], timeout=300)
            if rc == 0:
                added = []
                cur_path = None
                new_lineno = 0
                for line in out.splitlines():
                    if line.startswith("+++"):
                        cur_path = line[4:].lstrip("b/")
                    elif line.startswith("@@"):
                        m = re.search(r"\+(\d+)", line)
                        new_lineno = int(m.group(1)) if m else 0
                    elif line.startswith("+") and cur_path:
                        added.append((cur_path, new_lineno, line[1:]))
                        new_lineno += 1
                _scan_lines(added, "unpushed-diff", findings, placeholders)
    return findings, placeholders, scanned


def gate_untracked(g, rc_sha):
    """G3: 추적 불필요 파일 경고(빌드 산출물·로그·var·quarantine·50MB+)."""
    warns, fails = [], []
    rc, out, _ = g.run(["ls-tree", "-r", "-l", rc_sha], timeout=300)
    if rc != 0:
        return warns, [{"path": "<ls-tree>", "reason": "ls-tree 실패"}]
    for line in out.splitlines():
        m = re.match(r"\S+ (\w+) [0-9a-f]{40} +(\S+)\t(.+)", line)
        if not m or m.group(1) != "blob":
            continue
        size_s, path = m.group(2), _norm(m.group(3))
        size = int(size_s) if size_s.isdigit() else 0
        if size > MAX_BLOB_BYTES:
            fails.append({"path": path, "reason": f"{size}B > 50MB"})
            continue
        if path.startswith(("build/", "var/", "data/agent-handoff/",
                            "data/quarantine/", "quarantine/")) \
                or "/quarantine/" in path or path.endswith(".log"):
            warns.append({"path": path,
                          "reason": "runtime/output-like path"})
    return warns, fails


# ------------------------------------------------------------- G4 compile

def gate_compile(g, rc_sha, skip=False, timeout_s=G4_TIMEOUT_S):
    """G4: RC 트리를 임시 디렉터리에 archive 추출해 compileJava만 돌린다."""
    if skip:
        return {"status": "skipped", "reason": "--g4-skip"}
    res = {"status": "fail", "surface": "tmp-archive-extract", "detail": ""}
    tar_path = None
    tmp = Path(tempfile.mkdtemp(prefix="awx-rc-build-"))
    try:
        tar_path = tmp / "rc.tar"
        with open(tar_path, "wb") as fh:
            proc = subprocess.run(
                [g.exe, "archive", "--format=tar", rc_sha], cwd=g.repo,
                stdout=fh, stderr=subprocess.PIPE, timeout=300)
        g.log.append({"at": _utcnow(),
                      "argv": [g.exe, "archive", "--format=tar", rc_sha],
                      "rc": proc.returncode, "ms": 0,
                      "stderr": "(binary stdout -> tar file)"})
        if proc.returncode != 0:
            res["detail"] = "git archive 실패: " + _sanitize(
                proc.stderr.decode("utf-8", "replace"))[:300]
            return res
        src = tmp / "src"
        src.mkdir()
        with tarfile.open(str(tar_path)) as tf:
            tf.extractall(str(src), filter="data")
        gradlew = src / "gradlew.bat"
        if not gradlew.is_file():
            res["detail"] = "RC 트리에 gradlew.bat 없음"
            return res
        res["surface"] = str(src)
        env = dict(os.environ)
        env["GIT_OPTIONAL_LOCKS"] = "0"
        proc = subprocess.run(
            ["cmd", "/c", "gradlew.bat", ":compileJava", "-x", "test",
             "--console=plain", "--no-daemon"], cwd=str(src), env=env,
            capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout_s)
        res["compileExit"] = proc.returncode
        tail = "\n".join((proc.stdout + "\n" + proc.stderr).splitlines()[-8:])
        res["detail"] = _sanitize(tail)[:800]
        res["status"] = "pass" if proc.returncode == 0 else "fail"
        try:  # gradle 데몬/파일 핸들 정리 후 삭제
            subprocess.run(["cmd", "/c", "gradlew.bat", "--stop"],
                           cwd=str(src), env=env, capture_output=True,
                           timeout=120)
        except Exception:
            pass
        return res
    except subprocess.TimeoutExpired:
        res["detail"] = "compile timeout"
        return res
    except Exception as e:                      # noqa: BLE001 - 게이트는 실패 보고용
        res["detail"] = f"{type(e).__name__}: {e}"[:300]
        return res
    finally:
        try:
            if tar_path and tar_path.is_file():
                tar_path.unlink()
        except OSError:
            pass


def gate_public_meta(g, rc_sha, remote_ref):
    """G5: 공개되는 커밋 작성자 이메일·제목 — 경고 전용."""
    out = {"authorIdent": g.out(["var", "GIT_AUTHOR_IDENT"]),
           "committerIdent": g.out(["var", "GIT_COMMITTER_IDENT"]),
           "unpushedCommits": []}
    remote_sha = g.out(["rev-parse", remote_ref]) if remote_ref else ""
    rng = f"{remote_sha}..{rc_sha}" if remote_sha else rc_sha
    for line in g.out(["log", "--format=%h|%ae|%s", rng]).splitlines()[:50]:
        h, _, rest = line.partition("|")
        a, _, s = rest.partition("|")
        out["unpushedCommits"].append(
            {"sha": h, "email": a, "subject": s[:100]})
    return out


# ------------------------------------------------------------- plan/apply

def _resolve_rc(g, rc_arg, branch):
    ref = rc_arg or branch
    sha = g.out(["rev-parse", "--verify", f"{ref}^{{commit}}"])
    if not sha:
        raise SystemExit(f"RC ref를 찾지 못함: {ref}")
    return sha, ref


def _remote_refs(g, remote, refs):
    out = g.out(["ls-remote", remote] + list(refs), timeout=60)
    got = {}
    for line in out.splitlines():
        m = re.match(r"([0-9a-f]{40})\t(.+)", line)
        if m:
            got[m.group(2)] = m.group(1)
    return got


def run_gates(ctx, do_fetch=True):
    g = ctx.git
    gates = []

    def add(gid, status, **kw):
        gates.append({"id": gid, "status": status, **kw})

    if do_fetch:
        rc, _o, err = g.run(["fetch", ctx.remote], timeout=300,
                            optional_locks=False)
        if rc != 0:
            add("G0", "fail", reason="fetch 실패: " + _sanitize(err)[:200])
            return gates

    remote_main = g.out(["rev-parse", f"{ctx.remote}/main"])
    ctx.remote_main = remote_main

    # G1: origin/main == expect
    if not remote_main:
        add("G1", "fail", reason=f"{ctx.remote}/main 없음")
    elif remote_main.lower() == ctx.expect_main.lower():
        add("G1", "pass", actual=remote_main)
    else:
        add("G1", "fail", reason="main이 이동됨(누군가 변경)",
            expected=ctx.expect_main, actual=remote_main)

    # G2: 비밀 스캔
    findings, placeholders, scanned = gate_secret_scan(
        g, ctx.rc_sha, f"{ctx.remote}/{ctx.branch}")
    ctx.secret_findings = findings
    add("G2", "fail" if findings else "pass",
        suspects=[{"pattern": f["pattern"], "path": f["path"],
                   "line": f["line"], "masked": f["masked"],
                   "where": f["where"]} for f in findings],
        placeholderHits=len(placeholders), scanScope=scanned)

    # G3: 산출물 경고
    warns, fails = gate_untracked(g, ctx.rc_sha)
    status = "fail" if fails else ("warn" if warns else "pass")
    add("G3", status, failItems=fails[:20], warnCount=len(warns),
        warnSample=warns[:10])

    # G4: 빠른 검증(RC 트리 compileJava)
    g4 = gate_compile(g, ctx.rc_sha, skip=ctx.g4_skip)
    add("G4", "pass" if g4.get("status") == "pass"
              else ("warn" if g4.get("status") == "skipped" else "fail"),
        detail=g4.get("detail", ""), surface=g4.get("surface"),
        compileExit=g4.get("compileExit"))

    # G5: 공개 메타 경고
    meta = gate_public_meta(g, ctx.rc_sha, f"{ctx.remote}/{ctx.branch}")
    add("G5", "warn",
        authorIdent=meta["authorIdent"], committerIdent=meta["committerIdent"],
        unpushedCount=len(meta["unpushedCommits"]),
        unpushedSample=meta["unpushedCommits"][:10])
    return gates


def cmd_plan(ctx):
    gates = run_gates(ctx)
    ok = all(x["status"] != "fail" for x in gates)
    result = {"action": "plan", "ok": ok,
              "rc": {"sha": ctx.rc_sha, "ref": ctx.rc_ref},
              "remoteMain": ctx.remote_main, "expectMain": ctx.expect_main,
              "gates": gates}
    ctx.results["plan"] = result
    print(json.dumps(result, ensure_ascii=False, indent=1))
    return EXIT_OK if ok else EXIT_HOLD


def _allow_target(g, remote):
    """publish.allowTarget용 host/owner/repo (git_publish_review.parse_target와 동일)."""
    url = (g.out(["remote", "get-url", "--push", remote])
           or g.out(["remote", "get-url", remote])).strip()
    parsed = urlsplit(url if "://" in url
                      else "ssh://" + url.replace(":", "/", 1))
    parts = [x for x in parsed.path.split("/") if x]
    repo = parts[-1][:-4] if parts and parts[-1].endswith(".git") \
        else (parts[-1] if parts else "")
    owner = parts[-2] if len(parts) >= 2 else ""
    return f"{(parsed.hostname or '').lower()}/{owner}/{repo}"


def _push_ref(g, remote, src_sha, dst_ref, timeout=PUSH_TIMEOUT_S):
    """pre-push publish-review 계약을 지키는 push.

    - 승인 신호는 AWX_PUBLISH_APPROVED=1 세션 env(호출 측이 설정, subprocess가
      상속). git_ship.cmd_push와 같은 계약이다.
    - publish.allowTarget / publish.allowRef는 git_ship처럼 일회성 `-c`로만
      전달한다. git이 GIT_CONFIG_PARAMETERS로 훅에 전파하며 .git/config는
      건드리지 않는다.
    - force 계열 인자는 어떤 경우에도 만들지 않는다(Git.run 가드가 재확인).
    """
    argv = ["-c", f"publish.allowTarget={_allow_target(g, remote)}",
            "-c", f"publish.allowRef={dst_ref}",
            "push", remote, f"{src_sha}:{dst_ref}"]
    return g.run(argv, timeout=timeout, optional_locks=False)


def cmd_apply(ctx):
    expect_env = os.environ.get(ENV_EXPECT, "").strip()
    if not expect_env:
        print(json.dumps({"action": "apply", "status": "refused",
                          "reason": f"{ENV_EXPECT} 환경변수 필요"},
                         ensure_ascii=False))
        return EXIT_HOLD
    if os.environ.get(ENV_APPROVED) != "1":
        print(json.dumps({"action": "apply", "status": "refused",
                          "reason": f"{ENV_APPROVED}=1 환경변수 필요 "
                                    "(pre-push publish-review 승인 채널, "
                                    "git_ship과 동일)"},
                         ensure_ascii=False))
        return EXIT_HOLD

    gates = run_gates(ctx)
    remote_main = ctx.remote_main
    if remote_main != expect_env:
        ctx.results["apply"] = {"action": "apply", "status": "hold",
                                "reason": "main-moved",
                                "expected": expect_env, "actual": remote_main,
                                "gates": gates}
        print(json.dumps(ctx.results["apply"], ensure_ascii=False, indent=1))
        return EXIT_HOLD
    if any(x["status"] == "fail" for x in gates):
        ctx.results["apply"] = {"action": "apply", "status": "hold",
                                "reason": "gate-fail", "gates": gates}
        print(json.dumps(ctx.results["apply"], ensure_ascii=False, indent=1))
        return EXIT_HOLD

    g = ctx.git
    rc_sha, old_main = ctx.rc_sha, remote_main
    steps = []

    def step(name, **kw):
        steps.append({"step": name, **kw})

    # 이미 릴리스된 상태? (main 트리 == RC 트리이고 RC가 main의 조상)
    if g.run(["diff", "--quiet", remote_main, rc_sha])[0] == 0 and \
            g.run(["merge-base", "--is-ancestor", rc_sha, remote_main])[0] == 0:
        ctx.results["apply"] = {"action": "apply", "status": "already-released",
                                "main": remote_main, "rc": rc_sha}
        print(json.dumps(ctx.results["apply"], ensure_ascii=False, indent=1))
        return EXIT_OK

    # 스냅샷 커밋 생성(로컬 객체만, 작업 트리 무관). 같은 (tree, parents) 조합이
    # 이전 apply에서 이미 만들어졌으면 재사용한다 — 재실행 때마다 고아 커밋을
    # 새로 만들지 않기 위함.
    rc_tree = g.out(["rev-parse", f"{rc_sha}^{{tree}}"])
    snapshot = ""
    for inv in (getattr(ctx, "log_doc", {}).get("invocations") or []):
        for res in (inv.get("results") or {}).values():
            for s in ((res or {}).get("steps") or []):
                cand = s.get("snapshot") if s.get("step") == "commit-tree" \
                    and s.get("status") in ("ok", "reused") else None
                if not cand or g.run(["cat-file", "-e", cand])[0] != 0:
                    continue
                cat = g.out(["cat-file", "-p", cand])
                parents = [l.split()[1] for l in cat.splitlines()
                           if l.startswith("parent ")]
                if parents == [old_main, rc_sha] and \
                        g.out(["rev-parse", f"{cand}^{{tree}}"]) == rc_tree:
                    snapshot = cand
    if snapshot:
        step("commit-tree", status="reused", snapshot=snapshot,
             parents=[old_main, rc_sha])
    else:
        msg = (f"release: {ctx.branch} {rc_sha[:8]} -> main "
               "(snapshot, history kept)")
        rc, snap, err = g.run(
            ["commit-tree", f"{rc_sha}^{{tree}}", "-p", old_main, "-p", rc_sha,
             "-m", msg, "-m",
             "content=RC tree; parents preserve both histories; "
             "created by scripts/release_to_main.py"],
            optional_locks=False)
        snapshot = snap.strip()
        if rc != 0 or not _is_sha(snapshot):
            step("commit-tree", status="fail", err=_sanitize(err)[:200])
            ctx.results["apply"] = {"action": "apply", "status": "error",
                                    "steps": steps}
            print(json.dumps(ctx.results["apply"], ensure_ascii=False,
                             indent=1))
            return EXIT_ERROR
        step("commit-tree", status="ok", snapshot=snapshot,
             parents=[old_main, rc_sha])
    ctx.snapshot = snapshot

    refs = _remote_refs(g, ctx.remote, [
        "refs/tags/" + ARCHIVE_TAG, "refs/heads/" + ARCHIVE_BRANCH,
        "refs/heads/" + ctx.branch, MAIN_HEAD])

    # 1) 옛 main 보관 — 로컬 참조 생성 + 원격 시도.
    # 원격 생성이 이 저장소 pre-push publish-review에 막혀도 릴리스는 계속한다:
    # 옛 main은 스냅샷 커밋의 첫 부모로 main 이력 안에 보존되고, 로컬 참조가
    # 남는다. (신규 원격 참조는 hook에서 base 없음 -> verdict UNKNOWN/exit 3
    # 구조라 실패가 예상된다. 실패해도 abort하지 않고 기록만 한다.)
    for refname in ("refs/tags/" + ARCHIVE_TAG, "refs/heads/" + ARCHIVE_BRANCH):
        if g.run(["rev-parse", "--verify", "--quiet", refname])[0] != 0:
            g.run(["update-ref", refname, old_main], optional_locks=False)
    blocked_refs = []
    archive_blocked = False
    for kind, refname in (("archive-tag", "refs/tags/" + ARCHIVE_TAG),
                          ("archive-branch", "refs/heads/" + ARCHIVE_BRANCH)):
        if archive_blocked:
            step(kind, status="skipped", reason="sibling-blocked-same-policy",
                 ref=refname)
            continue
        if refname in refs:
            step(kind, status="skipped", reason="already-exists",
                 existing=refs[refname])
            continue
        rc, _o, err = _push_ref(g, ctx.remote, old_main, refname)
        st = "ok" if rc == 0 else "blocked-nonfatal"
        step(kind, status=st, ref=refname, pointsAt=old_main,
             err=_sanitize(err)[:400])
        if rc != 0:
            blocked_refs.append(refname)
            archive_blocked = True   # 동일 정책 실패 예상 — 재시도 아닌 건너뜀
    if blocked_refs:
        step("archive-remote", status="partial",
             reason="new remote ref는 pre-push 정책상 생성 불가 "
                    "(hook base 없음 -> UNKNOWN/exit 3). "
                    "옛 main은 로컬 참조 + 스냅샷 첫 부모로 보존.",
             blocked=blocked_refs)

    # 2) codex 브랜치 원격 갱신 — fast-forward일 때만
    remote_rc = refs.get("refs/heads/" + ctx.branch)
    if remote_rc == rc_sha:
        step("codex-push", status="skipped", reason="already-up-to-date")
    elif remote_rc and g.run(["merge-base", "--is-ancestor",
                              remote_rc, rc_sha])[0] == 0:
        rc, _o, err = _push_ref(g, ctx.remote, rc_sha,
                                "refs/heads/" + ctx.branch)
        step("codex-push", status="ok" if rc == 0 else "fail",
             old=remote_rc, new=rc_sha, err=_sanitize(err)[:300])
        if rc != 0:
            ctx.results["apply"] = {"action": "apply", "status": "error",
                                    "failedStep": "codex-push", "steps": steps,
                                    "snapshot": snapshot}
            print(json.dumps(ctx.results["apply"], ensure_ascii=False, indent=1))
            return EXIT_ERROR
    else:
        step("codex-push", status="skipped",
             reason="not-fast-forward-or-missing", remote=remote_rc)

    # 3) main 반영 — force 계열 인자는 절대 사용하지 않음(거부 시 그대로 실패)
    rc, _o, err = _push_ref(g, ctx.remote, snapshot, MAIN_HEAD)
    step("main-push", status="ok" if rc == 0 else "fail",
         old=remote_main, new=snapshot, err=_sanitize(err)[:300])
    ctx.results["apply"] = {
        "action": "apply",
        "status": "pushed" if rc == 0 else "error",
        "failedStep": None if rc == 0 else "main-push",
        "snapshot": snapshot, "oldMain": old_main, "rc": rc_sha,
        "steps": steps}
    print(json.dumps(ctx.results["apply"], ensure_ascii=False, indent=1))
    return EXIT_OK if rc == 0 else EXIT_ERROR


def cmd_verify(ctx):
    g = ctx.git
    refs = _remote_refs(g, ctx.remote, [
        MAIN_HEAD, "refs/heads/" + ctx.branch,
        "refs/tags/" + ARCHIVE_TAG, "refs/heads/" + ARCHIVE_BRANCH])
    sym = g.out(["ls-remote", "--symref", ctx.remote, "HEAD"], timeout=60)
    default_branch = ""
    m = re.search(r"ref:\s+(refs/heads/\S+)\s+HEAD", sym)
    if m:
        default_branch = m.group(1)

    snapshot = ctx.snapshot or refs.get(MAIN_HEAD)
    checks = {}
    if ctx.snapshot:
        checks["remoteMainEqualsSnapshot"] = refs.get(MAIN_HEAD) == ctx.snapshot
    if snapshot and _is_sha(snapshot):
        rc = g.run(["diff", "--quiet", snapshot, ctx.rc_sha])[0]
        checks["treeIdenticalToRC"] = rc == 0
        cat = g.out(["cat-file", "-p", snapshot])
        parents = [l.split()[1] for l in cat.splitlines()
                   if l.startswith("parent ")]
        checks["parents"] = parents
        checks["parentsKeepBothHistories"] = \
            parents == [ctx.expect_main, ctx.rc_sha]
    checks["archiveTagExists"] = "refs/tags/" + ARCHIVE_TAG in refs
    checks["archiveTagPointsAt"] = (
        refs.get("refs/tags/" + ARCHIVE_TAG + "^{}")
        or refs.get("refs/tags/" + ARCHIVE_TAG))
    checks["archiveBranchExists"] = "refs/heads/" + ARCHIVE_BRANCH in refs
    checks["archiveBranchPointsAt"] = refs.get("refs/heads/" + ARCHIVE_BRANCH)
    # 옛 main 보존의 실질 판정: 스냅샷 첫 부모로 원격 main 이력 안에 남는가.
    remote_main_sha = refs.get(MAIN_HEAD)
    if remote_main_sha and ctx.expect_main:
        checks["oldMainPreservedInHistory"] = g.run(
            ["merge-base", "--is-ancestor",
             ctx.expect_main, remote_main_sha])[0] == 0
    checks["localArchiveTag"] = g.run(
        ["rev-parse", "--verify", "--quiet",
         "refs/tags/" + ARCHIVE_TAG])[0] == 0
    checks["localArchiveBranch"] = g.run(
        ["rev-parse", "--verify", "--quiet",
         "refs/heads/" + ARCHIVE_BRANCH])[0] == 0
    checks["archiveRemoteNote"] = (
        "신규 원격 참조 생성은 pre-push publish-review 정책상 불가"
        "(base 없음 -> UNKNOWN/exit 3). 원격 archive 참조 부재는 판정에서 제외.")
    checks["defaultBranchIsMain"] = default_branch == MAIN_HEAD
    checks["refs"] = refs
    ok = all(v is True for k, v in checks.items()
             if k not in ("parents", "refs", "archiveTagExists",
                          "archiveTagPointsAt", "archiveBranchExists",
                          "archiveBranchPointsAt", "archiveRemoteNote"))
    ctx.results["verify"] = {"action": "verify", "ok": ok,
                             "snapshot": snapshot, "checks": checks,
                             "defaultBranch": default_branch}
    print(json.dumps(ctx.results["verify"], ensure_ascii=False, indent=1))
    return EXIT_OK if ok else EXIT_HOLD


def cmd_rollback_plan(ctx):
    """실행하지 않는다. 되돌리기용 명령만 출력."""
    cur = ctx.git.out(["rev-parse", f"{ctx.remote}/main"]) or "<current-main>"
    old = ctx.expect_main
    lines = [
        "# rollback-plan (print only; 아무것도 실행하지 않음)",
        "# 옛 main 트리로 되돌리는 새 커밋 - force push 없이 되돌린다.",
        f"git fetch {ctx.remote}",
        f"NEW=$(git commit-tree {old}^{{tree}} -p {cur} "
        f'-m "rollback: restore main to pre-release tree {old[:8]}")',
        f"git push {ctx.remote} $NEW:{MAIN_HEAD}",
        f"# 기대: main={old} 트리와 동일, 부모={cur}",
    ]
    ctx.results["rollback-plan"] = {"action": "rollback-plan",
                                    "commands": lines}
    print("\n".join(lines))
    return EXIT_OK


# ------------------------------------------------------------- cli

def _load_log(path):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        if isinstance(data, dict) and isinstance(
                data.get("invocations"), list):
            return data
    except (OSError, ValueError):
        pass
    return {"schemaVersion": SCHEMA, "invocations": []}


def main(argv=None):
    _force_utf8()
    ap = argparse.ArgumentParser(
        description="codex 브랜치를 main에 스냅샷 커밋으로 반영 (force push 없음)")
    ap.add_argument("--repo", default=".")
    ap.add_argument("--remote", default="origin")
    ap.add_argument("--branch", default=DEFAULT_BRANCH,
                    help="RC로 쓸 로컬 브랜치 (default %(default)s)")
    ap.add_argument("--rc", default=None,
                    help="RC sha/ref (default = --branch의 커밋된 HEAD)")
    ap.add_argument("--expect-main", default=DEFAULT_EXPECT_MAIN)
    ap.add_argument("--log", default=DEFAULT_LOG)
    ap.add_argument("--no-fetch", action="store_true",
                    help="plan/apply에서 fetch 생략 (오프라인 테스트용)")
    ap.add_argument("--g4-skip", action="store_true",
                    help="G4 compileJava 게이트 생략(warn 처리)")
    ap.add_argument("--git-exe", default=None)
    sub = ap.add_subparsers(dest="action")
    sub.add_parser("plan")
    ap_apply = sub.add_parser("apply")
    ap_apply.add_argument("--yes-main", action="store_true")
    ap_verify = sub.add_parser("verify")
    ap_verify.add_argument("--snapshot", default=None)
    sub.add_parser("rollback-plan")
    args = ap.parse_args(argv)

    action = args.action or "plan"
    if action == "apply" and not args.yes_main:
        print("apply에는 --yes-main 플래그가 필요합니다")
        return EXIT_USAGE

    log_path = Path(args.repo) / args.log
    log_doc = _load_log(log_path)
    commands = []
    g = Git(resolve_git_exe(args.git_exe), args.repo, commands)

    ctx = argparse.Namespace(
        git=g, remote=args.remote, branch=args.branch,
        expect_main=args.expect_main, g4_skip=args.g4_skip,
        results={}, remote_main=None, secret_findings=[],
        log_doc=log_doc,
        snapshot=getattr(args, "snapshot", None))
    ctx.rc_sha, ctx.rc_ref = _resolve_rc(
        g, args.rc, f"refs/heads/{args.branch}")

    started = _utcnow()
    try:
        fn = {"plan": cmd_plan, "apply": cmd_apply,
              "verify": cmd_verify, "rollback-plan": cmd_rollback_plan}[action]
        code = fn(ctx)
    finally:
        code = locals().get("code", EXIT_ERROR)
        log_doc["invocations"].append({
            "at": started, "action": action,
            "argv": [a for a in (argv or sys.argv[1:])],
            "repo": str(Path(args.repo).resolve()),
            "remote": args.remote, "exit": code,
            "commands": commands, "results": ctx.results})
        try:
            log_path.parent.mkdir(parents=True, exist_ok=True)
            log_path.write_text(json.dumps(
                log_doc, ensure_ascii=False, indent=1), encoding="utf-8")
        except OSError as e:
            print(f"[warn] release_log 쓰기 실패: {e}", file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
