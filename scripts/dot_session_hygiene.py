#!/usr/bin/env python3
"""dot_session_hygiene.py — dot/세션 꼬임 읽기 전용 탐침 (2026-10-05).

쓰기 0. 지정 시간 창 안에서 네 종류 의심 항목만 목록으로 보고한다.
본문(transcript text)은 절대 출력하지 않는다 — 경로·크기·mtime·flag뿐.

사용:
  python -B scripts/dot_session_hygiene.py --since-hours 36 [--json] [--verbose]

Findings:
  LARGE_ROLLOUT          %USERPROFILE%\\.codex\\sessions\\**\\rollout-*.jsonl
                         이 창 안에 바뀌었고 ≥ DOT_HYGIENE_LARGE_MB(기본 5MB)
  ROLLOUT_NEAR_CAPACITY  창 안 rollout이 ≥ DOT_HYGIENE_NEAR_MB(기본 3MB)이고
                         LARGE 임계 미만 — 용량 이어달리기 예고 신호
                         (DEMO1-DOT-CONTROL-TOWER §1-C). 경로·크기·mtime만.
  PARTIAL_STAGING        Documents\\Codex\\<날짜>\\*\\partial-* 가 창 안에 존재
  BRIEF_NOT_IN_DOWNLOADS agent-prompts\\**\\BRIEF.txt(창 안) 와 동일 sha12의
                         PASTE_* 파일이 Downloads 에 없음
  BARE_AUTO_SUSPECT      창 안 rollout-*.jsonl 첫 128KB 안에
                         PASTE_ / [DOT-BRIEF] / 지시서 마커가 하나도 없음
                         (맨명령 오토 세션 의심 — heuristic)
  MULTI_GOAL_CONTAMINATION  한 rollout 안에 서로 다른 PASTE_<AGENT>_* 식별자
                         ≥2개 — SERIAL_LANE 위반 의심(세션당 활성 지시서 1개,
                         DEMO1-DOT-CONTROL-TOWER §1-B). 지시서 파일명 식별자만
                         출력하고 본문은 출력하지 않는다. [DOT-ASSIST-PAIR]
                         선언 세션에서는 선언 레인 수(최대 2)를 넘는 3번째
                         식별자에만 발동하고 assistPairBreach:true 를 단다.
  ROLE_SWITCH_MID_SESSION   한 rollout 안에 지원 역할 마커(서브딜러/companion/
                         지원 등)와 작성 역할 마커(지시서 작성/PASTE 작성/
                         누락주제 등)가 같이 있음 — 역할 중도 전환 의심.
                         마커 리터럴만 출력하고 본문은 출력하지 않는다.
                         [DOT-ASSIST-PAIR] 선언 세션에서는 작성 역할 마커
                         단독으로도 발동하고 assistPairBreach:true 를 단다.
  ASSIST_PAIR_OK         [DOT-ASSIST-PAIR] lanes=<idA>,<idB> 선언이 있고
                         등장 PASTE 식별자가 선언 레인 이내 + 작성 역할
                         마커가 없음 — 위반이 아니라 count만 남긴다.

환경 오버라이드(테스트 격리용):
  DOT_HYGIENE_SESSIONS_ROOT   기본 ~\\.codex\\sessions
  DOT_HYGIENE_CODEX_DOCS      기본 ~\\Documents\\Codex
  DOT_HYGIENE_DOWNLOADS       기본 ~\\Downloads
  DOT_HYGIENE_AGENT_PROMPTS   기본 <repo>\\agent-prompts
  DOT_HYGIENE_LARGE_MB        기본 5
  DOT_HYGIENE_NEAR_MB         기본 3 — ROLLOUT_NEAR_CAPACITY 임계
                              (0 이하 또는 LARGE 이상이면 비활성)
  DOT_HYGIENE_SCAN_MB         기본 8 — MULTI_GOAL/ROLE_SWITCH 탐침이 읽는
                              rollout 앞부분 상한(그 뒤 내용은 못 본다)

exit: 0=실행 완료(findings 유무와 무관) 3=내부 오류
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path

EXIT_OK = 0
EXIT_FAIL = 3

LARGE_MB_DEFAULT = 5
NEAR_MB_DEFAULT = 3
SCAN_MB_DEFAULT = 8
HEAD_BYTES = 128 * 1024
BRIEF_MARKERS = ("PASTE_", "[DOT-BRIEF]", "DOT-BRIEF", "지시서",
                 "[DOT-ASSIST-PAIR]")
GOAL_RE = re.compile(r"PASTE_[A-Z][A-Z0-9]*_[A-Za-z0-9_.-]+")
ASSIST_PAIR_RE = re.compile(
    r"\[DOT-ASSIST-PAIR\]\s*lanes\s*=\s*([A-Za-z0-9_.,\-]+)")
ROLE_SUPPORT_MARKERS = ("서브딜러", "서브 딜러", "companion", "보조 지시서", "지원")
ROLE_WRITE_MARKERS = ("지시서 작성", "지시서작성", "PASTE 작성", "새 지시서",
                      "누락주제")


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def sessions_root() -> Path:
    env = os.environ.get("DOT_HYGIENE_SESSIONS_ROOT", "").strip()
    if env:
        return Path(env).expanduser()
    return Path.home() / ".codex" / "sessions"


def codex_docs_root() -> Path:
    env = os.environ.get("DOT_HYGIENE_CODEX_DOCS", "").strip()
    if env:
        return Path(env).expanduser()
    return Path.home() / "Documents" / "Codex"


def downloads_dir() -> Path:
    env = os.environ.get("DOT_HYGIENE_DOWNLOADS", "").strip()
    if env:
        return Path(env).expanduser()
    return Path.home() / "Downloads"


def agent_prompts_dir() -> Path:
    env = os.environ.get("DOT_HYGIENE_AGENT_PROMPTS", "").strip()
    if env:
        return Path(env).expanduser()
    return repo_root() / "agent-prompts"


def large_threshold() -> int:
    raw = os.environ.get("DOT_HYGIENE_LARGE_MB", "").strip() or str(LARGE_MB_DEFAULT)
    try:
        return int(float(raw) * 1024 * 1024)
    except ValueError:
        return LARGE_MB_DEFAULT * 1024 * 1024


def near_threshold() -> int:
    raw = os.environ.get("DOT_HYGIENE_NEAR_MB", "").strip() or str(NEAR_MB_DEFAULT)
    try:
        return int(float(raw) * 1024 * 1024)
    except ValueError:
        return NEAR_MB_DEFAULT * 1024 * 1024


def scan_cap() -> int:
    raw = os.environ.get("DOT_HYGIENE_SCAN_MB", "").strip() or str(SCAN_MB_DEFAULT)
    try:
        return int(float(raw) * 1024 * 1024)
    except ValueError:
        return SCAN_MB_DEFAULT * 1024 * 1024


def sha12_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:12]


def sha12_file(path: Path, cap: int = 8 * 1024 * 1024) -> str:
    h = hashlib.sha256()
    total = 0
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
            total += len(chunk)
            if total >= cap:
                break
    return h.hexdigest()[:12]


def in_window(path: Path, cutoff: float) -> bool:
    try:
        return path.stat().st_mtime >= cutoff
    except OSError:
        return False


def stat_entry(path: Path) -> dict:
    st = path.stat()
    return {
        "path": str(path),
        "sizeBytes": st.st_size,
        "mtime": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(st.st_mtime)),
    }


def iter_rollouts(root: Path, cutoff: float):
    if not root.is_dir():
        return
    for p in root.rglob("rollout-*.jsonl"):
        if p.is_file() and in_window(p, cutoff):
            yield p


def scan_large_rollouts(cutoff: float, threshold: int):
    out = []
    for p in iter_rollouts(sessions_root(), cutoff):
        try:
            if p.stat().st_size >= threshold:
                f = stat_entry(p)
                f["type"] = "LARGE_ROLLOUT"
                out.append(f)
        except OSError:
            continue
    return out


def scan_near_capacity(cutoff: float, near: int, large: int):
    """NEAR ≤ size < LARGE 인 창 안 rollout — 용량 이어달리기 예고."""
    out = []
    if near <= 0 or near >= large:
        return out
    for p in iter_rollouts(sessions_root(), cutoff):
        try:
            size = p.stat().st_size
        except OSError:
            continue
        if near <= size < large:
            f = stat_entry(p)
            f["type"] = "ROLLOUT_NEAR_CAPACITY"
            out.append(f)
    return out


def scan_partial_staging(cutoff: float):
    out = []
    root = codex_docs_root()
    if not root.is_dir():
        return out
    for p in root.rglob("partial-*"):
        try:
            if in_window(p, cutoff):
                f = stat_entry(p) if p.is_file() else {
                    "path": str(p), "sizeBytes": 0,
                    "mtime": time.strftime("%Y-%m-%dT%H:%M:%S%z",
                                           time.localtime(p.stat().st_mtime)),
                }
                f["type"] = "PARTIAL_STAGING"
                out.append(f)
        except OSError:
            continue
    return out


def downloads_sha12_set(cutoff: float):
    root = downloads_dir()
    hashes = set()
    if not root.is_dir():
        return hashes
    for p in root.glob("PASTE_*"):
        if not p.is_file() or not in_window(p, cutoff):
            continue
        try:
            hashes.add(sha12_file(p))
        except OSError:
            continue
    return hashes


def scan_brief_not_in_downloads(cutoff: float):
    out = []
    root = agent_prompts_dir()
    if not root.is_dir():
        return out
    dl_hashes = downloads_sha12_set(cutoff)
    for p in root.rglob("BRIEF.txt"):
        if not p.is_file() or not in_window(p, cutoff):
            continue
        try:
            if sha12_file(p) not in dl_hashes:
                f = stat_entry(p)
                f["type"] = "BRIEF_NOT_IN_DOWNLOADS"
                out.append(f)
        except OSError:
            continue
    return out


def scan_bare_auto(cutoff: float):
    out = []
    for p in iter_rollouts(sessions_root(), cutoff):
        try:
            with p.open("rb") as fh:
                head = fh.read(HEAD_BYTES).decode("utf-8", "replace")
        except OSError:
            continue
        if not any(m in head for m in BRIEF_MARKERS):
            f = stat_entry(p)
            f["type"] = "BARE_AUTO_SUSPECT"
            out.append(f)
    return out


def read_rollout_head(path: Path, cap: int) -> str:
    try:
        with path.open("rb") as fh:
            return fh.read(cap).decode("utf-8", "replace")
    except OSError:
        return ""


def scan_serial_lane(cutoff: float, cap: int):
    """SERIAL_LANE + ASSIST_PAIR 탐침 — 식별자·마커 리터럴만 출력, 본문은
    절대 출력 안 함. [DOT-ASSIST-PAIR] 선언 세션만 최대 2레인 예외."""
    out = []
    for p in iter_rollouts(sessions_root(), cutoff):
        text = read_rollout_head(p, cap)
        if not text:
            continue
        goals = sorted(set(GOAL_RE.findall(text)))
        decl = ASSIST_PAIR_RE.search(text)
        lanes = [s for s in (decl.group(1).split(",") if decl else []) if s]
        support_hits = [m for m in ROLE_SUPPORT_MARKERS if m in text]
        write_hits = [m for m in ROLE_WRITE_MARKERS if m in text]
        if lanes and len(lanes) <= 2:
            limit = len(lanes)
            if len(goals) > limit:
                f = stat_entry(p)
                f["type"] = "MULTI_GOAL_CONTAMINATION"
                f["goalCount"] = len(goals)
                f["goals"] = goals[:10]
                f["assistPairBreach"] = True
                f["declaredLanes"] = lanes
                out.append(f)
            if write_hits:
                f = stat_entry(p)
                f["type"] = "ROLE_SWITCH_MID_SESSION"
                f["supportMarkers"] = support_hits
                f["writeMarkers"] = write_hits
                f["assistPairBreach"] = True
                out.append(f)
            if len(goals) <= limit and not write_hits:
                f = stat_entry(p)
                f["type"] = "ASSIST_PAIR_OK"
                f["goalCount"] = len(goals)
                f["declaredLanes"] = lanes
                out.append(f)
        else:
            if len(goals) >= 2:
                f = stat_entry(p)
                f["type"] = "MULTI_GOAL_CONTAMINATION"
                f["goalCount"] = len(goals)
                f["goals"] = goals[:10]
                out.append(f)
            if support_hits and write_hits:
                f = stat_entry(p)
                f["type"] = "ROLE_SWITCH_MID_SESSION"
                f["supportMarkers"] = support_hits
                f["writeMarkers"] = write_hits
                out.append(f)
    return out


def scan(since_hours: float) -> dict:
    cutoff = time.time() - since_hours * 3600
    findings = []
    findings += scan_near_capacity(cutoff, near_threshold(), large_threshold())
    findings += scan_large_rollouts(cutoff, large_threshold())
    findings += scan_partial_staging(cutoff)
    findings += scan_brief_not_in_downloads(cutoff)
    findings += scan_bare_auto(cutoff)
    findings += scan_serial_lane(cutoff, scan_cap())
    counts = {}
    for f in findings:
        counts[f["type"]] = counts.get(f["type"], 0) + 1
    return {
        "ok": True,
        "sinceHours": since_hours,
        "roots": {
            "sessions": str(sessions_root()),
            "codexDocs": str(codex_docs_root()),
            "downloads": str(downloads_dir()),
            "agentPrompts": str(agent_prompts_dir()),
        },
        "counts": counts,
        "findings": sorted(findings, key=lambda f: (f["type"], f["path"])),
    }


def emit(obj) -> None:
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")


def main(argv=None) -> int:
    for s in (sys.stdout, sys.stderr):
        if hasattr(s, "reconfigure"):
            try:
                s.reconfigure(encoding="utf-8", errors="replace")
            except (OSError, ValueError):
                pass
    ap = argparse.ArgumentParser(prog="dot_session_hygiene")
    ap.add_argument("--since-hours", type=float, default=36.0)
    ap.add_argument("--json", action="store_true", help="JSON 한 줄 출력")
    ap.add_argument("--verbose", action="store_true", help="요약 줄 + JSON")
    args = ap.parse_args(argv)
    try:
        res = scan(args.since_hours)
    except OSError as e:
        emit({"ok": False, "error": "io", "message": str(e)})
        return EXIT_FAIL
    if args.json and not args.verbose:
        emit(res)
    else:
        c = res["counts"]
        print(f"dot_session_hygiene since={res['sinceHours']}h")
        print(f"  LARGE_ROLLOUT={c.get('LARGE_ROLLOUT', 0)} "
              f"ROLLOUT_NEAR_CAPACITY={c.get('ROLLOUT_NEAR_CAPACITY', 0)} "
              f"PARTIAL_STAGING={c.get('PARTIAL_STAGING', 0)} "
              f"BRIEF_NOT_IN_DOWNLOADS={c.get('BRIEF_NOT_IN_DOWNLOADS', 0)} "
              f"BARE_AUTO_SUSPECT={c.get('BARE_AUTO_SUSPECT', 0)} "
              f"MULTI_GOAL_CONTAMINATION={c.get('MULTI_GOAL_CONTAMINATION', 0)} "
              f"ROLE_SWITCH_MID_SESSION={c.get('ROLE_SWITCH_MID_SESSION', 0)} "
              f"ASSIST_PAIR_OK={c.get('ASSIST_PAIR_OK', 0)}")
        for f in res["findings"]:
            print(f"  [{f['type']}] {f['path']} ({f['sizeBytes']}B)")
        if args.verbose:
            emit(res)
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
