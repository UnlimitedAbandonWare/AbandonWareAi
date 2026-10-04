#!/usr/bin/env python3
"""git_ship_easy.py -- Git-Ship-Easy.bat용 대화형 커밋/올리기 도우미.

scripts/git_ship.py 위에 얹는 얇은 대화형 껍질이다. git_ship.py와
Git-Ship.bat의 인수 동작은 한 글자도 바꾸지 않는다.

메뉴:
  Enter = 자동 올리기(안전한 변경 전부 커밋 + push + 원격 일치 확인)
  1. 상태 보기   2. 커밋하기   3. 커밋 + push(올리기)   4. 원격 확인
  5. 올라간 파일 내리기(내용은 그대로)   0/q. 끝내기

안전 규칙 (git_ship 규칙 그대로):
  - Enter는 안전한 것 전부, m은 내 파일만, q는 취소하는 한 번의 선택.
  - 활성 에이전트 lease 경로 / junk 규칙 / 10MB 초과 / .gitignore 대상 /
    실행 산출물(var/, build/, data/agent-handoff/)은 기본 선택에서 제외.
  - 내 파일만 선택하면 다른 stage 항목을 유지하고 --only로 커밋한다.
    전체 선택은 위험한 경로를 자동 보류하고 인덱스에서만 뺀다.
  - index.lock은 기다리기만 한다. 절대 지우지 않는다.
  - main/master push 거부. push --apply는 이 프로세스 환경에만
    AWX_PUBLISH_APPROVED=1을 넣었을 때만.
  - --skip-guard/--no-verify/force push/reset/checkout/restore/stash/clean은
    메뉴에 없다.
  - stdin이 콘솔이 아니면(isatty false) 메뉴를 띄우지 않고 사용법만 출력한
    뒤 exit 2 -- 에이전트가 실수로 불러도 입력을 기다리지 않는다.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace

if __package__:
    from . import git_ship
    try:
        from .demo1_skill_quality_audit import CORE_KEEP_SKILLS
    except ImportError:
        CORE_KEEP_SKILLS = frozenset()
else:  # python -B scripts\git_ship_easy.py 직접 실행
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import git_ship
    try:
        from demo1_skill_quality_audit import CORE_KEEP_SKILLS
    except ImportError:
        CORE_KEEP_SKILLS = frozenset()

RUNTIME_PREFIXES = ("var/", "build/", "data/agent-handoff/")
ADD_CHUNK = 200
SELECTION_RETRY = 3
LEASE_TIMEOUT_S = 20
ENTER_ALL_CONFIRM = 30
EASY_LOCK_WAIT_S = 3.0
EASY_LOCK_RETRIES = 30          # 30회 * 3초 = 최대 90초 대기
DEFAULT_STALE_LOCK_S = 90.0     # 이 시간 이상 방치된 lock만 고아 후보
BULK_DELETE_HOLD = 20           # 한 폴더 설명 안 되는 삭제가 이 개수 이상이면 보류
LAST_SHIP_JSON = Path("var") / "git-ship-easy" / "last-ship.json"

USAGE = """사용법: Git-Ship-Easy.bat (더블클릭) 또는
  python -B scripts/git_ship_easy.py [--root <repo>] [--git-exe <git>] [--plan-only | --auto]
터미널(콘솔)이 아닌 곳에서는 메뉴를 띄우지 않고 끝납니다 (exit 2)."""


# ---------------------------------------------------------------- util

def _norm(path: str) -> str:
    return path.replace("\\", "/")


def _file_size(root, path):
    try:
        return (Path(root) / path).stat().st_size
    except OSError:
        return None


def _size_label(n):
    if n is None:
        return "-"
    if n >= 1024 * 1024:
        return f"{n / 1024 / 1024:.1f}MB"
    if n >= 1024:
        return f"{n / 1024:.1f}KB"
    return f"{n}B"


def resolve_git_exe(explicit=None):
    if explicit:
        return explicit
    env = os.environ.get("AWX_GIT_EXE")
    if env:
        return env
    cand = r"F:\git\cmd\git.exe"
    if Path(cand).is_file():
        return cand
    return "git"


def _flush():
    try:
        sys.stdout.flush()
    except Exception:
        pass


def _ask(input_fn, prompt):
    _flush()
    try:
        return input_fn(prompt)
    except EOFError:
        return "q"


def _yes(ans) -> bool:
    return (ans or "").strip().lower() in ("y", "yes")


def _force_utf8():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


def _stdin_is_console():
    """stdin이 진짜 콘솔인지.

    Windows CRT _isatty(= sys.stdin.isatty())는 NUL 같은 문자 디바이스에서도
    True를 반환해 리다이렉션을 못 가린다. 콘솔 판정은 GetConsoleMode로 한다."""
    if os.name != "nt":
        return sys.stdin.isatty()
    try:
        import ctypes
        import msvcrt
        handle = msvcrt.get_osfhandle(sys.stdin.fileno())
        mode = ctypes.c_uint32()
        return bool(ctypes.windll.kernel32.GetConsoleMode(
            handle, ctypes.byref(mode)))
    except Exception:
        return sys.stdin.isatty()


# ------------------------------------------------------- lease / status

def load_active_lease_paths(root, timeout=LEASE_TIMEOUT_S):
    """활성(live/active) lease의 targetPaths 모음. 확인 불가면 빈 집합+상태."""
    script = Path(root) / "scripts" / "agent_scope_lease.py"
    if not script.is_file():
        return set(), "missing", 0
    try:
        proc = subprocess.run(
            [sys.executable, "-B", str(script), "who"],
            capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout, cwd=str(root))
    except (OSError, subprocess.TimeoutExpired):
        return set(), "error", 0
    if proc.returncode != 0:
        return set(), "error", 0
    try:
        data = json.loads(proc.stdout)
    except ValueError:
        return set(), "error", 0
    paths, count = set(), 0
    for lease in data.get("leases") or []:
        if lease.get("lifecycle") == "live" or lease.get("status") == "active":
            count += 1
            for p in lease.get("targetPaths") or []:
                paths.add(_norm(str(p)))
    return paths, "ok", count


def collect_changes(g):
    """porcelain -z -uall 변경 목록. [{'xy': ' M'|'??'|..., 'path': norm}].

    -uall: 추적 안 된 디렉터리를 통째로 'dir/'가 아니라 파일 단위로 풀어
    junk/lease/gitignore 판정이 파일별로 먹히게 한다."""
    out = g.out(["status", "--porcelain", "-z", "-uall"])
    parts = [p for p in out.split("\0") if p]
    entries, i = [], 0
    while i < len(parts):
        field = parts[i]
        i += 1
        xy, path = field[:2], field[3:]
        if xy[:1] in ("R", "C"):
            i += 1  # 이름바꾼 파일의 이전 경로 건너뛰기(새 경로만 취급)
        entries.append({"xy": xy, "path": _norm(path)})
    return entries


def _needs_add(entry):
    xy = entry["xy"]
    return xy == "??" or (len(xy) > 1 and xy[1] != " ")


def classify_changes(g, root, entries, lease_paths):
    """기본 후보(cand)와 기본 제외(excl=[(entry, 이유)])로 나눈다."""
    cand, excl = [], []
    ignored = git_ship.check_ignored(
        g, [e["path"] for e in entries if _needs_add(e)])
    for e in entries:
        if not _needs_add(e):
            continue  # 이미 올라간 상태는 '원래 올라가 있던 파일' 쪽에서 다룬다
        p = e["path"]
        st = e["xy"].strip() or "M"
        rule = next((rid for rid, fn in git_ship.JUNK_RULES if fn(p, st)), None)
        if p in lease_paths:
            excl.append((e, "에이전트 작업 중(lease)"))
        elif _name_hold(p):
            excl.append((e, "junk 규칙(" + _name_hold(p) + ")"))
        elif rule:
            excl.append((e, f"junk 규칙({rule})"))
        elif p.startswith(RUNTIME_PREFIXES):
            excl.append((e, "실행 산출물"))
        elif p in ignored:
            excl.append((e, ".gitignore 대상"))
        else:
            size = _file_size(root, p)
            e["size"] = size
            if size is not None and size > git_ship.DEFAULT_MAX_MB * 1024 * 1024:
                excl.append((e, f"{git_ship.DEFAULT_MAX_MB:.0f}MB 초과({_size_label(size)})"))
            else:
                cand.append(e)
    return cand, excl


def parse_selection(text, count):
    """"1 3 5-9" -> {1,3,5,6,7,8,9}; '' -> 전부; q -> 'cancel'."""
    s = (text or "").strip().lower()
    if s in ("q", "quit", "x"):
        return "cancel"
    if s == "":
        return set(range(1, count + 1))
    picks = set()
    for tok in re.split(r"[\s,]+", s):
        if not tok:
            continue
        m = re.fullmatch(r"(\d+)(?:-(\d+))?", tok)
        if not m:
            raise ValueError(f"이해 못하는 입력: {tok}")
        a, b = int(m.group(1)), int(m.group(2) or m.group(1))
        lo, hi = min(a, b), max(a, b)
        if lo < 1 or hi > count:
            raise ValueError(f"번호 범위(1~{count}) 밖: {tok}")
        picks.update(range(lo, hi + 1))
    if not picks:
        raise ValueError("고른 파일이 없어요")
    return picks


def _git_process_running():
    """실행 중인 git.exe가 있는지. True/False, 확인 불가면 None."""
    try:
        if os.name == "nt":
            proc = subprocess.run(
                ["tasklist", "/FI", "IMAGENAME eq git.exe", "/NH"],
                capture_output=True, text=True, encoding="utf-8",
                errors="replace", timeout=10)
            if proc.returncode != 0:
                return None
            return "git.exe" in proc.stdout.lower()
        proc = subprocess.run(["pgrep", "-x", "git"],
                              capture_output=True, timeout=10)
        if proc.returncode == 2:
            return None
        return proc.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return None


def check_stale_lock(g, threshold_s=DEFAULT_STALE_LOCK_S, proc_check=None):
    """index.lock의 방치 상태와 고아(orphan) 여부를 돌려준다.

    고아 = lock이 threshold_s 이상 방치 + 실행 중인 git 프로세스 없음.
    프로세스 확인이 불가(None)면 isOrphan은 False -- 모르면 절대 건드리지 않는다."""
    lock = git_ship.index_lock_path(g)
    info = {"present": False, "path": str(lock), "ageSeconds": None,
            "isStale": False, "gitProcessRunning": None, "isOrphan": False}
    if not lock.is_file():
        return info
    info["present"] = True
    try:
        st = lock.stat()
    except OSError:
        return info
    info["ageSeconds"] = round(time.time() - st.st_mtime, 1)
    info["sizeBytes"] = st.st_size
    info["isStale"] = info["ageSeconds"] >= threshold_s
    if info["isStale"]:
        running = (proc_check or _git_process_running)()
        info["gitProcessRunning"] = running
        info["isOrphan"] = running is False
    return info


def _offer_break_orphan(g, info, input_fn, out, stale_threshold_s, proc_check):
    """고아 index.lock을 사용자 승인(y) 뒤에만 삭제한다.
    True=해제해서 계속 진행, False=락 유지하고 안전 중단."""
    age = info.get("ageSeconds")
    age_txt = f"{age:g}" if isinstance(age, (int, float)) else "?"
    out(f"[경고] index.lock이 {age_txt}초 동안 남아있고, "
        "실행 중인 git.exe 프로세스가 없습니다.")
    out("(에이전트가 비정상 종료되어 락이 해제되지 못했을 수 있습니다)")
    _flush()
    if not _yes(_ask(input_fn,
                     "고아 락을 강제로 삭제하고 권한을 회수하여 "
                     "계속 진행할까요? (y/N) ")):
        out("락을 유지하고 중단해요. 잠깐 뒤 다시 시도하세요.")
        _flush()
        return False
    again = check_stale_lock(g, threshold_s=stale_threshold_s,
                             proc_check=proc_check)
    if not again["present"]:
        return True                       # 묻는 사이 상대가 정리했다
    if not again["isOrphan"]:
        out("지금은 git 프로세스가 보여요. 락은 그대로 두고 중단해요.")
        _flush()
        return False
    try:
        Path(info["path"]).unlink()
    except FileNotFoundError:
        return True
    except OSError:
        out("락 삭제에 실패했어요. 그대로 두고 중단해요.")
        _flush()
        return False
    out("고아 락을 해제했습니다. 작업을 계속 진행합니다.")
    _flush()
    return True


def wait_for_lock(g, out=print, sleep=None, wait_s=None, retries=None,
                  notify_interval_s=6.0, input_fn=None,
                  stale_threshold_s=DEFAULT_STALE_LOCK_S, proc_check=None):
    """index.lock이 풀릴 때까지 기다린다 (기본 30회*3초=최대 90초).

    대기 중엔 notify_interval_s마다 진행 상황을 알린다. 오래 방치된 고아
    락(git 프로세스 없음)이면 사용자 승인(y) 뒤에만 강제 해제한다 --
    input_fn이 없으면 절대 지우지 않는다."""
    sleep = time.sleep if sleep is None else sleep
    wait_s = EASY_LOCK_WAIT_S if wait_s is None else wait_s
    retries = EASY_LOCK_RETRIES if retries is None else retries
    lock = git_ship.index_lock_path(g)
    tries = 0
    next_notify = notify_interval_s
    while lock.is_file() and tries < retries:
        if tries == 0:
            out("에이전트가 git을 쓰는 중이라 기다려요 (index.lock 감지)...")
            _flush()
        info = check_stale_lock(g, threshold_s=stale_threshold_s,
                                proc_check=proc_check)
        if info["isOrphan"]:
            if input_fn is None:
                break                     # 비대화: 삭제 불가, 타임아웃 처리로
            return _offer_break_orphan(g, info, input_fn, out,
                                       stale_threshold_s, proc_check)
        tries += 1
        sleep(wait_s)
        elapsed = tries * wait_s
        if elapsed >= next_notify:
            out(f"[대기 중 {elapsed:g}초 경과] 다른 에이전트 git 작업 중... "
                "(취소: Ctrl+C)")
            _flush()
            next_notify += notify_interval_s
    if lock.is_file():
        info = check_stale_lock(g, threshold_s=stale_threshold_s,
                                proc_check=proc_check)
        if info["isOrphan"] and input_fn is not None:
            return _offer_break_orphan(g, info, input_fn, out,
                                       stale_threshold_s, proc_check)
        out("끝까지 lock이 안 풀렸어요. lock 파일은 지우지 않고 그대로 둡니다.")
        _flush()
        return False
    return True


def _git_path_batches(g, command, paths, out, input_fn=None, failures=None):
    plist = sorted(paths)
    for i in range(0, len(plist), ADD_CHUNK):
        chunk = plist[i:i + ADD_CHUNK]
        def attempt(batch):
            rc, _o, err = g.run(command + ["--"] + [":(literal)" + p for p in batch])
            if rc and "index.lock" in err and wait_for_lock(g, out):
                rc, _o, err = g.run(command + ["--"] + [":(literal)" + p for p in batch])
            return rc, err
        rc, err = attempt(chunk)
        if not rc:
            continue
        if failures is None or "pathspec" not in err.lower():
            raise git_ship.ShipError(git_ship.EXIT_ERROR, "git " + command[0] +
                                     " 실패: " + git_ship.sanitize(err))
        if len(chunk) == 1:
            failures[chunk[0]] = [command[0] + " pathspec 실패"]
            continue
        # One repartition, then singleton probes; no recursive retries.
        half = max(1, len(chunk) // 2)
        for batch in (chunk[:half], chunk[half:]):
            if not batch:
                continue
            rc, err = attempt(batch)
            if not rc:
                continue
            if len(batch) == 1:
                failures[batch[0]] = [command[0] + " pathspec 실패"]
                continue
            for path in batch:
                rc, err = attempt([path])
                if rc:
                    failures[path] = [command[0] + " pathspec 실패"]


def _git_add(g, paths, out, input_fn=None, failures=None):
    _git_path_batches(g, ["add"], paths, out, input_fn, failures)


def _git_restore_staged(g, paths, out, input_fn=None, failures=None):
    _git_path_batches(g, ["restore", "--staged"], paths, out, input_fn, failures)


# ------------------------------------------------------ auto-hold rules

def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_manifest_moved(root):
    """data/agent-handoff/**/moved-skills-manifest.json 전부 병합.

    반환 {'.agents/skills/<name>/<rel>': {'skill': name, 'rel': rel, 'sha256': sha}}
    -- rel은 '/' 정규화. 읽기 실패 파일은 건너뛴다."""
    moved = {}
    hand = Path(root) / "data" / "agent-handoff"
    if not hand.is_dir():
        return moved
    for p in hand.rglob("moved-skills-manifest.json"):
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(data, dict):
            continue
        for skill, rec in data.items():
            for rel, sha in ((rec or {}).get("files") or {}).items():
                rel_n = str(rel).replace("\\", "/")
                moved[f".agents/skills/{skill}/{rel_n}"] = {
                    "skill": skill, "rel": rel_n, "sha256": sha}
    return moved


def _manifest_explained(root, moved, path):
    """삭제 경로가 닫힌 매니페스트 + data/agent-archive sha-일치 사본으로
    설명되면 True (그 삭제를 커밋에 넣어도 안전)."""
    rec = moved.get(path)
    if not rec:
        return False
    arc = Path(root) / "data" / "agent-archive" / "skills" / \
        rec["skill"] / rec["rel"]
    try:
        return arc.is_file() and _sha256_file(arc) == rec["sha256"]
    except OSError:
        return False


def _under_any_prefix(path, prefixes):
    return any(path == p or path.startswith(p + "/") for p in prefixes)


def _referenced_skill_names(root):
    """live 참조되는 스킬명 + 코어 @skill keep 집합.

    - AGENTS.md/agents.md: bare name 등장 = live 참조 (archive 경로 언급 제외).
    - INDEX.md/SEMANTIC_INDEX.md: archive 항목(canonicalId+archive source)도
      이름을 포함하므로 `.agents/skills/<name>` live 경로만 참조로 센다.
    """
    names = set(CORE_KEEP_SKILLS)
    doc_parts = []
    index_parts = []
    for rel, sink in (("AGENTS.md", doc_parts), ("agents.md", doc_parts),
                      (".agents/skills/INDEX.md", index_parts),
                      (".agents/skills/SEMANTIC_INDEX.md", index_parts)):
        try:
            sink.append(
                (Path(root) / rel).read_text(
                    encoding="utf-8", errors="replace"))
        except OSError:
            pass
    doc_blob = "\n".join(doc_parts)
    index_blob = "\n".join(index_parts)
    candidates = set()
    for base in (Path(root) / ".agents" / "skills",
                 Path(root) / "data" / "agent-archive" / "skills"):
        if base.is_dir():
            candidates |= {d.name for d in base.iterdir() if d.is_dir()}
    for n in candidates:
        live_docs = re.sub(
            r"(?:data/)?agent-archive/skills/" + re.escape(n), "", doc_blob)
        if n in live_docs or (".agents/skills/" + n) in index_blob:
            names.add(n)
    return names


def _name_hold(path):
    name = Path(path).name.lower()
    if name in {"$null", "nul", "con"} or (name.startswith("$") and "." not in name):
        return "셸 출력 파일명"
    if name.endswith(".bak") or ".bak-" in name:
        return "백업 사본"
    return None


def compute_auto_holds(root, g, cand, pre_rows, lease_paths):
    """Enter 자동 올리기에서 빼야 할 경로 -> [이유] dict.

    규칙 (질문 없이 자동, 목록만 출력):
      - 한 폴더에서 설명 안 되는 삭제가 BULK_DELETE_HOLD개 이상
      - AGENTS/INDEX가 live 참조하는 스킬의 SKILL.md 삭제, 코어 @skill 삭제
      - [AD] 인덱스엔 추가됐는데 디스크에 없음
      - 활성 lease 경로 (stage된 것도)"""
    holds = {}

    def add(path, why):
        holds.setdefault(path, []).append(why)

    moved = load_manifest_moved(root)
    deletes = sorted({e["path"] for e in cand if "D" in e["xy"]} |
                     {_norm(p) for st, p in pre_rows if st.startswith("D")})
    unexplained_by_dir = {}
    for p in deletes:
        if not _manifest_explained(root, moved, p):
            unexplained_by_dir.setdefault(
                p.rsplit("/", 1)[0] if "/" in p else ".", []).append(p)
    for paths in unexplained_by_dir.values():
        if len(paths) >= BULK_DELETE_HOLD:
            for p in paths:
                add(p, "설명 안 되는 대량 삭제(한 폴더 "
                    f"{BULK_DELETE_HOLD}개+)")

    ref_names = _referenced_skill_names(root)
    for p in deletes:
        parts = p.split("/")
        if len(parts) >= 4 and parts[0] == ".agents" \
                and parts[1] == "skills" \
                and parts[-1].upper() == "SKILL.MD" \
                and parts[2] in ref_names:
            add(p, "참조 중인 스킬의 SKILL.md 삭제")

    rows = [(e["xy"].strip(), e["path"]) for e in cand] + list(pre_rows)
    for st, p in rows:
        pn = _norm(p)
        why = _name_hold(pn)
        if why:
            add(pn, why)
        size = _file_size(root, pn)
        if size is not None and size > git_ship.DEFAULT_MAX_MB * 1024 * 1024:
            add(pn, "10MB 초과")
        if st == "A" and not (Path(root) / pn).exists():
            add(pn, "올라갔는데 디스크에 없음(AD)")
        elif _under_any_prefix(pn, lease_paths):
            add(pn, "에이전트 작업 중(lease)")
    for finding in git_ship.find_junk(g, git_ship.DEFAULT_MAX_MB):
        add(_norm(finding["path"]), "junk 규칙(" + finding["rule"] + ")")
    return holds


# ------------------------------------------------------------- outputs

def _ab(n):
    return "?" if n is None else str(n)


def show_overview(g, root, out):
    st = git_ship.cmd_status(g, None)
    lease_paths, lease_state, lease_count = load_active_lease_paths(root)
    lock = "있음" if st["indexLock"]["present"] else "없음"
    lease_note = (f"{lease_count}건" if lease_state == "ok"
                      else f"확인 실패({lease_state})")
    out(f"브랜치: {st['branch']} | 바뀐 파일: {st['changedCount']} | "
        f"커밋 대기(stage): {st['stagedCount']} | "
        f"원격 대비 앞 {_ab(st['ahead'])} / 뒤 {_ab(st['behind'])} | "
        f"활성 lease: {lease_note} | index.lock: {lock}")
    return st, lease_paths


def _print_change_lists(cand, excl, out):
    if cand:
        out("바뀐 파일 (Enter=전부, 번호=일부 예: 1 3 5-9, q=취소):")
        for i, e in enumerate(cand[:29], 1):
            st = e["xy"].strip() or "M"
            out(f"  {i:>3}. [{st:>3}] {_size_label(e.get('size')):>8}  {e['path']}")
    if len(cand) > 29:
        out(f"  ... 외 {len(cand) - 29}개")
    if excl:
        out("기본 제외 (이유):")
        for e, why in excl[:29]:
            out(f"       {e['path']}  -- {why}")

    if len(excl) > 29:
        out(f"  ... 외 {len(excl) - 29}개")


_HOOK_RULE_HINTS = {
    "provider-key": "API 키 모양 문자열 (테스트용 가짜 키도 모양만 같으면 걸림)",
    "sensitive-assignment": "키·비밀번호 대입처럼 보이는 줄 (코드 오탐일 수 있음)",
    "binary-scan-unavailable": "글자로 못 읽는 파일 (바이너리·바로가기·특수 파일)",
    "credential-path": "비밀값이 들어가는 경로/파일 이름",
    "credential-or-database-path": "키·DB 확장자 파일",
    "private-profile": "비공개 설정 파일 이름",
    "runtime-data-path": "실행 데이터 경로 (data/, var/ 등)",
    "private-or-generated-path": "비공개·생성 폴더 (.secrets, build, models 등)",
    "invalid-index-path": "git 경로 형식 이상",
    "blob-size-limit": "2MB를 넘는 파일",
    "symlink-or-submodule": "심볼릭 링크/서브모듈",
    "unmerged-index": "합병 충돌이 남은 상태",
}


def _hook_hint(rule):
    hints = [_HOOK_RULE_HINTS.get(r.strip(), r.strip())
             for r in str(rule).split(",") if r.strip()]
    return "; ".join(hints) if hints else "알 수 없는 규칙"


def _explain(g, err, out):
    """영어 스택 대신 한 줄 한국어 원인 + 다음 행동."""
    if err.code == git_ship.EXIT_HOOK:
        out("커밋 전 비밀값 검사(pre-commit 훅)가 막았어요. 값은 보여 주지 않아요.")
        findings = (err.details or {}).get("hookFindings")
        if findings is None:
            try:
                guard = git_ship.run_staged_guard(g.root, g.exe)
                findings = git_ship.map_guard_findings(
                    g, guard.get("findings"))
            except git_ship.ShipError:
                findings = []
        for f in (findings or [])[:29]:
            out(f"  {f['path']}  규칙={f['rule']}  -- {_hook_hint(f['rule'])}")
        reason = (err.details or {}).get("reason")
        if reason:
            out(f"  검사기 상태: {reason}")
        out("해당 파일을 빼고 다시 고르거나, 메뉴 5번으로 올라간 파일을"
            " 내린 뒤 다시 시도하세요.")
    elif err.code == git_ship.EXIT_SECRET:
        out("비밀값처럼 보이는 내용이 발견돼 멈췄어요. 값은 보여 주지 않아요.")
        try:
            scan = git_ship.run_scan(g, staged=True)
            for f in [f for f in scan["findings"] if f["class"] == "real"][:29]:
                out(f"  {f['path']}  규칙={f['rule']}")
        except git_ship.ShipError:
            pass
        out("해당 파일에서 값을 지운 뒤 다시 시도하세요.")
    elif err.code == git_ship.EXIT_LOCK:
        out("다른 에이전트가 git을 쓰는 중이라 끝내지 못했어요. "
            "잠깐 뒤 다시 시도하세요.")
    elif err.code == git_ship.EXIT_POLICY:
        out(f"규칙상 거부됐어요: {err.message}")
    elif err.code == git_ship.EXIT_VERIFY:
        out(f"올린 뒤 원격과 맞지 않아요: {err.message}")
    else:
        out(f"오류가 났어요: {err.message}")


def _print_verify(res, out):
    if res["ok"]:
        out(f"원격과 같아요: {res['branch']} {res['head'][:12]}")
    elif not res["remoteReachable"]:
        out(f"원격에 연결하지 못했어요: {res['branch']} head={res['head'][:12]}")
    else:
        out(f"원격과 달라요: remote={res['remoteSha'] or '없음'} "
            f"head={res['head'][:12]}")
    if res.get("prUrl"):
        out(f"PR 만들기: {res['prUrl']}")


# ------------------------------------------------------------- actions

def status_flow(g, out):
    st = git_ship.cmd_status(g, None)
    out(f"브랜치: {st['branch']}  head: {st['head'][:12]}")
    out(f"원격: {st['upstream'] or '없음'}  앞: {st['ahead']}  뒤: {st['behind']}")
    out(f"커밋 대기(stage): {st['stagedCount']}  "
        f"바뀐 파일: {st['changedCount']}")
    lk = st["indexLock"]
    if lk["present"]:
        out(f"index.lock: 있음 (크기 {lk.get('sizeBytes')}B, "
            f"{lk.get('ageSeconds')}초 전)")
    else:
        out("index.lock: 없음")
    return 0


def verify_flow(g, out):
    res = git_ship.cmd_verify(g, SimpleNamespace(remote="origin"))
    _print_verify(res, out)
    return 0 if res["ok"] else git_ship.EXIT_VERIFY


def _commit_args(message, apply):
    return SimpleNamespace(
        message=message, message2=None, apply=apply,
        skip_guard=False, reason="", max_mb=git_ship.DEFAULT_MAX_MB,
        lock_wait=git_ship.DEFAULT_LOCK_WAIT_S,
        lock_retries=git_ship.DEFAULT_LOCK_RETRIES)


def _push_args(apply):
    return SimpleNamespace(apply=apply, remote="origin", base=None,
                           skip_guard=False, reason="")


def _apply_push(g):
    """push --apply. 승인 env는 이 프로세스 안에서만 잠깐 켠다."""
    os.environ["AWX_PUBLISH_APPROVED"] = "1"
    try:
        return git_ship.cmd_push(g, _push_args(True))
    finally:
        os.environ.pop("AWX_PUBLISH_APPROVED", None)


def _offer_unstage(g, paths, input_fn, out):
    """커밋 실패 뒤 이번 실행에서 직접 올린 파일만 내릴지 묻는다.
    원래부터 stage돼 있던 파일은 절대 손대지 않는다."""
    if not paths:
        return
    if not _yes(_ask(input_fn,
                     f"이번에 올린 {len(paths)}개를 다시 내릴까요? (y/N) ")):
        out("올라간 파일은 그대로 둡니다.")
        return
    if not wait_for_lock(g, out, input_fn=input_fn):
        return
    try:
        _git_restore_staged(g, paths, out, input_fn=input_fn)
    except git_ship.ShipError as e:
        _explain(g, e, out)
        return
    out(f"{len(paths)}개를 내렸어요. 파일 내용은 그대로예요.")


def commit_flow(g, root, input_fn=input, out=print, push=False):
    try:
        return _commit_selection_flow(g, root, input_fn, out, push)
    except Exception as err:
        result = {"holds": {}, "shipped": 0, "unstaged": [], "commitSha": None,
                  "pushed": False, "verified": None}
        code = _fail_ship(result, "verify", err, out)
        _write_last_ship(root, result)
        return code


def _commit_selection_flow(g, root, input_fn=input, out=print, push=False):
    """One selection; Enter includes safe staging, m preserves foreign staging."""
    rows = git_ship.staged_name_status(g)
    leases, state, _count = load_active_lease_paths(root)
    cand, excl = classify_changes(g, root, collect_changes(g), leases)
    _print_change_lists(cand, excl, out)
    if rows:
        target = Path(root) / "var/git-ship-easy/staged-foreign.txt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n".join(f"[{st}] {p}" for st, p in rows) + "\n", encoding="utf-8")
        counts = {}; folders = {}
        for st, p in rows:
            counts[st] = counts.get(st, 0) + 1
            folder = p.split("/")[0] if "/" in p else "."
            folders[folder] = folders.get(folder, 0) + 1
        out(f"원래 올라가 있던 파일 {len(rows)}개 · 상태 {counts}")
        out("상위 폴더: " + str(dict(sorted(folders.items())[:10])))
        for st, p in rows[:10]:
            out(f"  [{st}] {p}")
        out(f"전체 목록: {target}")
    raw = (_ask(input_fn, "[Enter] 안전한 것 전부(자동 보류 적용)  [m] 내 파일만  [q] 취소 (번호 선택 가능)> ") or "").strip().lower()
    if raw == "q":
        out("취소했어요.")
        return 0
    paths = None
    if raw:
        try:
            picks = set(range(1, len(cand)+1)) if raw == "m" else parse_selection(raw, len(cand))
            if picks == "cancel":
                return 0
            paths = {cand[i-1]["path"] for i in picks}
        except ValueError as err:
            out(str(err))
            return git_ship.EXIT_POLICY
        if not paths:
            out("내 파일 중 커밋할 변경이 없어요. 기존 stage는 그대로예요.")
            return 0
    return auto_ship_flow(g, root, out=out, commit_only=not push, selected_paths=paths)


def unstage_flow(g, input_fn=input, out=print):
    """[5. 올라간 파일 내리기] stage만 취소한다. 작업 파일 내용은 그대로."""
    rows = git_ship.staged_name_status(g)
    if not rows:
        out("올라가 있는 파일이 없어요.")
        return 0
    out(f"올라가 있는 파일 {len(rows)}개 (내려도 파일 내용은 그대로):")
    for st, p in rows[:29]:
        out(f"  [{st}] {p}")
    if len(rows) > 29:
        out(f"  ... 외 {len(rows) - 29}개")
    if not _yes(_ask(input_fn, "전부 내릴까요? (y/N) ")):
        out("취소했어요.")
        return 0
    if not wait_for_lock(g, out, input_fn=input_fn):
        out("index.lock이 안 풀려 멈췄어요. 잠깐 뒤 다시 시도하세요.")
        return git_ship.EXIT_LOCK
    try:
        _git_restore_staged(g, [p for _st, p in rows], out, input_fn=input_fn)
    except git_ship.ShipError as e:
        _explain(g, e, out)
        return e.code
    out(f"{len(rows)}개를 내렸어요. 파일 내용은 그대로예요.")
    return 0


def push_flow(g, input_fn=input, out=print, push_fn=None, verify_fn=None):
    """[3. 커밋+push] 의 push 부분. push_fn/verify_fn은 테스트용 주입점."""
    branch = git_ship.current_branch(g)
    if branch in git_ship.PROTECTED_BRANCHES:
        out(f"{branch} 브랜치는 올리기를 하지 않아요 (git_ship 규칙).")
        return git_ship.EXIT_POLICY
    push_fn = push_fn or (lambda: _apply_push(g))
    verify_fn = verify_fn or (
        lambda: git_ship.cmd_verify(g, SimpleNamespace(remote="origin")))
    try:
        plan = git_ship.cmd_push(g, _push_args(False))  # 미리보기
    except git_ship.ShipError as e:
        _explain(g, e, out)
        return e.code
    pp = plan["push"]
    sc = pp["scanCounts"]
    out(f"올리기 미리보기: {pp['branch']} -> {pp['remote']} (범위 {pp['range']})")
    out(f"  비밀값 검사: real={sc['real']} fake={sc['fake']} word={sc['word']}")
    if pp.get("bigBlobWarnings"):
        out("  큰 파일 경고: " + ", ".join(pp["bigBlobWarnings"]))
    if not _yes(_ask(input_fn, f"origin/{branch} 로 진짜 올릴까요? (Y/N) ")):
        out("올리기를 취소했어요.")
        return 0
    if not wait_for_lock(g, out, input_fn=input_fn):
        out("index.lock이 안 풀려 올리기를 멈췄어요. "
            "커밋은 되어 있으니 메뉴 3으로 다시 올리세요.")
        return git_ship.EXIT_LOCK
    try:
        res = push_fn()
    except git_ship.ShipError as e:
        _explain(g, e, out)
        return e.code
    if res.get("push", {}).get("pushed"):
        pp2 = res["push"]
        out(f"올렸어요: remote={pp2['remoteSha'][:12]} "
            f"head={pp2['head'][:12]}")
    vres = verify_fn()
    _print_verify(vres, out)
    return 0 if vres.get("ok") else git_ship.EXIT_VERIFY


# ----------------------------------------------------------- auto ship

def _write_last_ship(root, record):
    """var/git-ship-easy/last-ship.json 에 이번 Enter 결과를 남긴다."""
    record = dict(record)
    record["atUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    try:
        t = Path(root) / LAST_SHIP_JSON
        t.parent.mkdir(parents=True, exist_ok=True)
        t.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n",
                     encoding="utf-8")
    except OSError:
        pass


def _hold_lines(holds, out, limit=29):
    if not holds:
        return
    out(f"자동 보류 {len(holds)}개 (이번 Enter에서 제외, 이유):")
    for i, (p, whys) in enumerate(sorted(holds.items())):
        if i >= limit:
            out(f"  ... 외 {len(holds) - limit}개")
            break
        out(f"  {p}  -- {'; '.join(whys)}")


def _remote_ahead(g, remote, branch):
    """원격 head가 우리 head를 포함해 앞서 있으면 remote sha, 아니면 None.

    fetch 없이 보는 판정: 로컬 upstream ref는 옛날일 수 있으므로
    ls-remote로 실제 원격 head를 본다. diverge(서로 다른 커밋)는
    '앞섬'이 아니라 push가 거절되며 그때 안내한다."""
    rsha = git_ship.ls_remote_sha(g, remote, branch)
    if not rsha:
        return None
    head = git_ship.head_sha(g)
    if rsha == head:
        return None
    rc, _o, _e = g.run(["merge-base", "--is-ancestor", head, rsha])
    return rsha if rc == 0 else None


def _fail_ship(result, step, err, out):
    result["failedStep"] = step
    message = err.message if isinstance(err, git_ship.ShipError) else type(err).__name__
    for _rule, pattern in git_ship.PATTERNS:
        message = pattern.sub("[redacted]", message)
    message = re.sub(r"(?:pcsk_|sb_secret_)[A-Za-z0-9_-]+", "[redacted]", message)
    message = re.sub(r"(?i)(authorization|password|api[_-]?key|token|secret)\s*[:=]\s*[^\s,;]+", r"\1=[redacted]", message)
    result["error"] = git_ship.sanitize(message)
    out(f"실패 단계={step}: {result['error']}")
    return err.code if isinstance(err, git_ship.ShipError) else git_ship.EXIT_ERROR


def auto_ship_flow(g, root, input_fn=input, out=print, push_fn=None,
                   verify_fn=None, commit_only=False, selected_paths=None):
    """Hold unsafe paths, commit selected bytes, and persist every exit."""
    result = {"holds": {}, "shipped": 0, "unstaged": [], "commitSha": None,
              "pushed": False, "verified": None, "failedStep": None, "error": ""}
    step = "verify"
    try:
        branch = git_ship.current_branch(g)
        step = "restore"
        if not wait_for_lock(g, out):
            raise git_ship.ShipError(git_ship.EXIT_LOCK, "index.lock 대기 실패")
        entries = collect_changes(g)
        rows = git_ship.staged_name_status(g)
        pre = {_norm(p) for _st, p in rows}
        leases, state, _count = load_active_lease_paths(root)
        if state == "error":
            raise git_ship.ShipError(git_ship.EXIT_POLICY, "lease 상태 확인 실패")
        cand, _excl = classify_changes(g, root, entries, leases)
        holds = compute_auto_holds(root, g, cand, rows, leases)
        if selected_paths is not None:
            holds = {p: w for p, w in holds.items() if p in selected_paths}
            cand = [e for e in cand if e["path"] in selected_paths]
        result["holds"] = holds
        def unstage(paths):
            failures = {}
            _git_restore_staged(g, paths, out, failures=failures)
            holds.update(failures)
            result["unstaged"].extend(p for p in paths if p not in failures)
            out(f"보류된 {len(paths)-len(failures)}개를 인덱스에서만 뺐어요 (파일 내용은 그대로).")
        held = sorted(pre & set(holds))
        if held:
            unstage(held)
        step = "add"
        ship = [e["path"] for e in cand if e["path"] not in holds]
        if ship:
            failures = {}
            _git_add(g, ship, out, failures=failures)
            holds.update(failures)
            result["shipped"] = len(set(ship) - set(failures))
        scope = {_norm(p) for _st, p in git_ship.staged_name_status(g)} - set(holds)
        if selected_paths is not None:
            scope &= set(selected_paths)
        if scope:
            # Candidate index also excludes a held path whose unstage failed.
            step = "scan"
            with git_ship._commit_index(g, sorted(scope)):
                scan = git_ship.run_scan(g, staged=True)
                for finding in scan["findings"]:
                    if finding["class"] == "real":
                        holds.setdefault(finding["path"], []).append("비밀값 검사: " + finding["rule"])
                step = "guard"
                guard = git_ship.run_staged_guard(g.root, g.exe)
                findings = git_ship.map_guard_findings(g, guard.get("findings"))
                if guard.get("ok") is not True and not findings:
                    raise git_ship.ShipError(git_ship.EXIT_HOOK, "검사기 확인 실패: " + str(guard.get("reason") or "unknown"))
                for finding in findings:
                    if finding["path"] not in scope:
                        raise git_ship.ShipError(git_ship.EXIT_HOOK, "검사 경로 확인 실패")
                    holds.setdefault(finding["path"], []).append("pre-commit 가드: " + finding["rule"])
            step = "restore"
            blocked = sorted(scope & set(holds))
            if blocked:
                unstage(blocked)
            scope -= set(holds)
        _hold_lines(holds, out)
        if scope:
            step = "commit"
            msg = "chore: 자동 올리기 " + datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
            try:
                res = git_ship.cmd_commit(g, _commit_args(msg, True), paths=sorted(scope))
            except git_ship.ShipError as err:
                if err.code == git_ship.EXIT_SECRET:
                    step = "scan"
                elif err.code == git_ship.EXIT_HOOK:
                    step = "guard"
                raise
            result["commitSha"] = res["sha"]
            result["shipped"] = res["fileCount"]
            out(f"커밋했어요: {res['sha'][:12]}  파일 {res['fileCount']}개")
        else:
            out("커밋할 변경이 없어요.")
        remaining = len(git_ship.staged_name_status(g))
        out(f"남은 stage: {remaining}개")
        if commit_only:
            return dict(result, sha=result["commitSha"], remainingStaged=remaining)
        step = "push"
        st = git_ship.cmd_status(g, None)
        remote_ahead = _remote_ahead(g, "origin", branch)
        if (st["behind"] or 0) > 0 or remote_ahead:
            raise git_ship.ShipError(git_ship.EXIT_VERIFY, "원격이 앞서 있어요. 올리기를 보류했어요.")
        if branch in git_ship.PROTECTED_BRANCHES:
            raise git_ship.ShipError(git_ship.EXIT_POLICY, f"{branch} 브랜치는 올리기를 하지 않아요")
        if st["ahead"] == 0 and not result["commitSha"]:
            step = "verify"
            vres = (verify_fn or (lambda: git_ship.cmd_verify(g, SimpleNamespace(remote="origin"))))()
        else:
            pres = (push_fn or (lambda: _apply_push(g)))()
            result["pushed"] = bool(pres.get("push", {}).get("pushed"))
            if result["pushed"]:
                out("올렸어요: push 완료")
            step = "verify"
            vres = (verify_fn or (lambda: git_ship.cmd_verify(g, SimpleNamespace(remote="origin"))))()
        result["verified"] = bool(vres.get("ok"))
        _print_verify(vres, out)
        if not result["verified"]:
            raise git_ship.ShipError(git_ship.EXIT_VERIFY, "원격 SHA가 로컬 HEAD와 다르거나 확인 실패")
        return 0
    except Exception as err:
        return _fail_ship(result, step, err, out)
    finally:
        _write_last_ship(root, result)


def run_menu_action(g, root, ans, input_fn=input, out=print):
    """메뉴 선택 하나 실행. 반환 'quit'이면 끝내기."""
    if ans == "1":
        return status_flow(g, out)
    if ans == "2":
        return commit_flow(g, root, input_fn, out)
    if ans == "3":
        return commit_flow(g, root, input_fn, out, push=True)
    if ans == "4":
        return verify_flow(g, out)
    if ans == "5":
        return unstage_flow(g, input_fn, out)
    if ans == "":
        return auto_ship_flow(g, root, input_fn, out)
    if ans in ("0", "q"):
        return "quit"
    out("0~5 중에서 고르세요.")
    return None


# -------------------------------------------------------------- menu

def plan_only(root, git_exe, out=print):
    """첫 화면 + 기본 제외 목록 + Enter 자동 올리기 미리보기.
    stage/commit/push는 0 -- git에 아무것도 쓰지 않는다."""
    g = git_ship.Git(str(root), git_exe)
    st, lease_paths = show_overview(g, root, out)
    entries = collect_changes(g)
    cand, excl = classify_changes(g, root, entries, lease_paths)
    out("== 미리보기 (plan-only: stage/commit/push 없음) ==")
    _print_change_lists(cand, excl, out)
    staged = git_ship.staged_name_status(g)
    if staged:
        out("이미 올라가 있는 파일:")
        for st_code, p in staged[:29]:
            out(f"  [{st_code}] {p}")
    if len(staged) > 29:
        out(f"  ... 외 {len(staged) - 29}개")
    holds = compute_auto_holds(root, g, cand, staged, lease_paths)
    ship = [e for e in cand if e["path"] not in holds]
    kept_staged = sum(1 for _s, p in staged if _norm(p) not in holds)
    drop_staged = len(staged) - kept_staged
    out(f"== Enter 미리보기: 커밋될 {len(ship) + kept_staged}개 "
        f"(신규 {len(ship)} + 기존 stage {kept_staged}) · "
        f"보류 {len(holds)}개 · 인덱스에서 뺄 {drop_staged}개 · "
        f"push 대상 {st['branch']} (앞 {_ab(st['ahead'])} / "
        f"뒤 {_ab(st['behind'])}) ==")
    _hold_lines(holds, out)
    return 0


def build_arg_parser():
    p = argparse.ArgumentParser(prog="git_ship_easy.py")
    p.add_argument("--root", default=".")
    p.add_argument("--git-exe", default=None)
    p.add_argument("--plan-only", action="store_true",
                   help="첫 화면과 기본 제외 목록만 출력하고 종료")
    p.add_argument("--auto", action="store_true", help="콘솔 없이 자동 올리기")
    return p


def main(argv=None):
    args = build_arg_parser().parse_args(argv)
    _force_utf8()
    root = os.path.abspath(args.root)
    git_exe = resolve_git_exe(args.git_exe)
    if args.plan_only:
        try:
            return plan_only(root, git_exe)
        except git_ship.ShipError as e:
            print(f"git 상태를 읽지 못했어요: {e.message}")
            return e.code
    if args.auto:
        return auto_ship_flow(git_ship.Git(root, git_exe), root, input_fn=lambda _p: "")
    if not _stdin_is_console():
        print(USAGE)
        return 2
    g = git_ship.Git(root, git_exe)
    try:
        show_overview(g, root, print)
    except git_ship.ShipError as e:
        print(f"git 상태를 읽지 못했어요: {e.message}")
        return e.code
    while True:
        print()
        print("  Enter = 자동 올리기(커밋+push+확인)   0/q = 끝내기")
        print("  1. 상태 보기   2. 커밋하기   3. 커밋 + push(올리기)")
        print("  4. 원격과 맞는지 확인   5. 올라간 파일 내리기(내용은 그대로)")
        ans = (_ask(input, "선택> ") or "").strip()
        try:
            if run_menu_action(g, root, ans, input, print) == "quit":
                break
        except git_ship.ShipError as e:
            _explain(g, e, print)
    print("끝냈어요.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
