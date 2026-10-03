#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dot_brief_save.py — [DOT-BRIEF] 경로 전용 Codex 지시서 저장기.

사용 조건(계약): 사용자가 ChatGPT 점(dot, UnlimitedAbandon)에게 "코덱스한테
지시서 써달라"고 맡길 때, 첫 요청 안에 `[DOT-BRIEF]` 태그와 저장 범위가 함께
있을 때만 Codex가 이 스크립트를 호출한다. 태그 없는 일반 대화·다른 에이전트의
기존 저장 흐름(brief_save.py 등)은 이 파일을 쓰지 않는다.

  save   --agent DEVIN --topic <topic> [--date YYYYMMDD] --from <파일|->
           [--dry-run] [--check]
           → Downloads 와 agent-prompts/<agent>-<topic>-<date>/BRIEF.txt 두 곳에
             새 파일로만 저장(같은 이름이면 _v2, _v3). 덮어쓰기·삭제 0.
  rescue [--hours 24] [--apply] [--dry-run]
           → %TEMP%\\demo1-*\\PASTE_*.txt 중 최근 N시간 파일을 나열(기본)하거나,
             --apply일 때만 Downloads에 없는 것을 새 이름으로 복사.

출력은 stdout 마지막 줄에 JSON 한 줄. 저장 성공:
  {"ok": true, "via": "dot", "paths": [...], "size": N, "sha12": "..."}
기록은 <repo>/data/agent-handoff/dot-brief-save/log.jsonl (경로·크기·sha만,
본문은 절대 기록하지 않음). 네트워크 0. 표준 라이브러리만.

테스트/격리용 env override (미설정 시 실제 경로):
  DOT_BRIEF_SAVE_DOWNLOADS   기본 %USERPROFILE%\\Downloads
  DOT_BRIEF_SAVE_TEMP        기본 %TEMP%
  DOT_BRIEF_SAVE_AGENT_PROMPTS  기본 <repo>/agent-prompts
  DOT_BRIEF_SAVE_LOG         기본 <repo>/data/agent-handoff/dot-brief-save/log.jsonl
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timedelta
from pathlib import Path

NAME_RE = re.compile(r"^PASTE_[A-Z0-9]+_[a-z0-9-]+_\d{8}(_v\d+)?\.txt$")
AGENT_RE = re.compile(r"^[A-Z0-9]+$")
TOPIC_RE = re.compile(r"^[a-z0-9-]+$")
DATE_RE = re.compile(r"^\d{8}$")
MAX_BYTES = 256 * 1024
# 런타임 패턴은 그대로 password 대입 형태 — 소스 바이트에 비밀값 형태 리터럴을
# 남기지 않기 위해 저장소 관례대로 분할 결합(build_error_miner의 synthetic fixture 방식).
SECRET_PATTERNS = ("sk-", "AIza", "ghp_", "xox", "-----BEGIN", "pass" + "word=")

EXIT_OK = 0
EXIT_INVALID = 2
EXIT_SECRET = 3
EXIT_IO = 4
EXIT_VERIFY = 5


# ---------- 공용 ----------

def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _env_dir(name: str, default: Path) -> Path:
    v = os.environ.get(name)
    return Path(v) if v else default


def downloads_dir() -> Path:
    home = os.environ.get("USERPROFILE") or str(Path.home())
    return _env_dir("DOT_BRIEF_SAVE_DOWNLOADS", Path(home) / "Downloads")


def temp_root() -> Path:
    return _env_dir("DOT_BRIEF_SAVE_TEMP",
                    Path(os.environ.get("TEMP") or (Path.home() / "AppData/Local/Temp")))


def prompts_dir() -> Path:
    return _env_dir("DOT_BRIEF_SAVE_AGENT_PROMPTS", repo_root() / "agent-prompts")


def log_path() -> Path:
    return _env_dir(
        "DOT_BRIEF_SAVE_LOG",
        repo_root() / "data" / "agent-handoff" / "dot-brief-save" / "log.jsonl",
    )


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")


def fail(error: str, code: int = EXIT_INVALID, **kw) -> "NoReturn":
    emit({"ok": False, "via": "dot", "error": error, **kw})
    sys.exit(code)


def sha12(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:12]


def next_free_file(path: Path) -> Path:
    """같은 이름이 있으면 _v2, _v3 … 새 경로를 돌려준다. 절대 덮어쓰지 않는다."""
    if not path.exists():
        return path
    n = 2
    while True:
        cand = path.with_name(f"{path.stem}_v{n}{path.suffix}")
        if not cand.exists():
            return cand
        n += 1


def next_free_prompt_dir(base: Path) -> Path:
    """agent-prompts 쪽 대상 디렉터리. BRIEF.txt가 이미 있으면 <name>_vN 디렉터리."""
    if not (base / "BRIEF.txt").exists():
        return base
    n = 2
    while True:
        cand = base.with_name(f"{base.name}_v{n}")
        if not (cand / "BRIEF.txt").exists():
            return cand
        n += 1


def write_new(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "xb") as f:  # exclusive create — 기존 파일 덮어쓰기 불가
        f.write(data)


def verify_written(path: Path, expect_sha: str, expect_size: int) -> None:
    try:
        rb = path.read_bytes()
    except OSError as e:
        fail("VERIFY_MISMATCH", EXIT_VERIFY, path=str(path), detail=str(e))
    if len(rb) != expect_size or sha12(rb) != expect_sha:
        fail("VERIFY_MISMATCH", EXIT_VERIFY, path=str(path))


def append_log(entry: dict) -> None:
    lp = log_path()
    lp.parent.mkdir(parents=True, exist_ok=True)
    with open(lp, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def scan_secrets(text: str) -> None:
    for pat in SECRET_PATTERNS:
        if pat in text:
            fail("BLOCKED_SECRET", EXIT_SECRET, pattern=pat)


# ---------- save ----------

def cmd_save(a) -> int:
    if not AGENT_RE.fullmatch(a.agent):
        fail("BAD_AGENT", detail="agent must match [A-Z0-9]+")
    if not TOPIC_RE.fullmatch(a.topic):
        fail("BAD_TOPIC", detail="topic must match [a-z0-9-]+")
    date = a.date or datetime.now().strftime("%Y%m%d")
    if not DATE_RE.fullmatch(date):
        fail("BAD_DATE", detail="date must be YYYYMMDD")

    if getattr(a, "from") == "-":
        raw = sys.stdin.buffer.read()
    else:
        src = Path(getattr(a, "from"))
        if not src.is_file():
            fail("BAD_SOURCE", detail="--from must be an existing file or '-' for stdin")
        try:
            raw = src.read_bytes()
        except OSError as e:
            fail("BAD_SOURCE", EXIT_IO, detail=str(e))

    if len(raw) > MAX_BYTES:
        fail("TOO_LARGE", size=len(raw), max=MAX_BYTES)
    try:
        text = raw.decode("utf-8-sig")  # BOM 제거 포함
    except UnicodeDecodeError:
        fail("NOT_UTF8")
    scan_secrets(text)
    body = text.encode("utf-8")  # BOM 없는 UTF-8로 정규화
    digest, size = sha12(body), len(body)

    fname = f"PASTE_{a.agent}_{a.topic}_{date}.txt"
    if not NAME_RE.fullmatch(fname):  # 방어적 재검증
        fail("BAD_FILENAME", detail=fname)

    t_dl = next_free_file(downloads_dir() / fname)
    sub = f"{a.agent.lower()}-{a.topic}-{date}"
    t_pp = next_free_prompt_dir(prompts_dir() / sub) / "BRIEF.txt"
    planned = [str(t_dl), str(t_pp)]

    if a.check:
        emit({"ok": True, "via": "dot", "check": True,
              "planned": planned, "size": size, "sha12": digest})
        return EXIT_OK
    if a.dry_run:
        emit({"ok": True, "via": "dot", "dryRun": True,
              "planned": planned, "size": size, "sha12": digest})
        return EXIT_OK

    try:
        write_new(t_dl, body)
        write_new(t_pp, body)
    except OSError as e:
        fail("WRITE_FAILED", EXIT_IO, detail=str(e))
    verify_written(t_dl, digest, size)
    verify_written(t_pp, digest, size)

    append_log({"ts": datetime.now().astimezone().isoformat(timespec="seconds"),
                "via": "dot", "op": "save", "agent": a.agent, "topic": a.topic,
                "date": date, "paths": planned, "size": size, "sha12": digest})
    emit({"ok": True, "via": "dot", "paths": planned, "size": size, "sha12": digest})
    return EXIT_OK


# ---------- rescue ----------

def cmd_rescue(a) -> int:
    cutoff = datetime.now().timestamp() - a.hours * 3600
    candidates = []
    for d in sorted(temp_root().glob("demo1-*")):
        if not d.is_dir():
            continue
        for f in sorted(d.glob("PASTE_*.txt")):
            if not f.is_file():
                continue
            try:
                st = f.stat()
            except OSError:
                continue
            if st.st_mtime < cutoff:
                continue
            candidates.append(f)

    rows = []
    for f in candidates:
        try:
            data = f.read_bytes()
        except OSError as e:
            rows.append({"src": str(f), "action": "error", "detail": str(e)})
            continue
        digest, size = sha12(data), len(data)
        row = {"src": str(f), "size": size, "sha12": digest,
               "mtime": datetime.fromtimestamp(f.stat().st_mtime).isoformat(timespec="seconds")}
        if not (a.apply and not a.dry_run):
            row["action"] = "listed"
            rows.append(row)
            continue
        # --apply: Downloads 쪽만 복구 대상
        if not NAME_RE.fullmatch(f.name):
            row["action"] = "skipped-bad-name"
            rows.append(row)
            continue
        if size > MAX_BYTES:
            row["action"] = "skipped-too-large"
            rows.append(row)
            continue
        try:
            text = data.decode("utf-8-sig")
        except UnicodeDecodeError:
            row["action"] = "skipped-not-utf8"
            rows.append(row)
            continue
        hit = next((p for p in SECRET_PATTERNS if p in text), None)
        if hit:
            row["action"] = "skipped-blocked-secret"
            rows.append(row)
            continue
        dest = downloads_dir() / f.name
        if dest.exists():
            if sha12(dest.read_bytes()) == digest:
                row["action"] = "skipped-same-sha"
                rows.append(row)
                continue
            dest = next_free_file(dest)
        body = text.encode("utf-8")
        try:
            write_new(dest, body)
        except OSError as e:
            row["action"] = "error"
            row["detail"] = str(e)
            rows.append(row)
            continue
        verify_written(dest, sha12(body), len(body))
        row["action"] = "copied"
        row["dest"] = str(dest)
        rows.append(row)

    copied = [r for r in rows if r.get("action") == "copied"]
    if a.apply and not a.dry_run and copied:
        append_log({"ts": datetime.now().astimezone().isoformat(timespec="seconds"),
                    "via": "dot", "op": "rescue",
                    "paths": [r["dest"] for r in copied],
                    "count": len(copied)})
    emit({"ok": True, "via": "dot", "applied": bool(a.apply and not a.dry_run),
          "candidates": rows})
    return EXIT_OK


# ---------- entry ----------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="dot_brief_save.py",
                                 description="[DOT-BRIEF] 전용 지시서 저장기")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("save", help="지시서 파일을 Downloads+agent-prompts에 새 이름으로 저장")
    sp.add_argument("--agent", required=True)
    sp.add_argument("--topic", required=True)
    sp.add_argument("--date", default=None, help="YYYYMMDD (기본: 오늘)")
    sp.add_argument("--from", dest="from", required=True, metavar="FILE|-")
    sp.add_argument("--dry-run", action="store_true")
    sp.add_argument("--check", action="store_true")
    sp.set_defaults(func=cmd_save)

    rp = sub.add_parser("rescue", help="%TEMP%\\demo1-*\\PASTE_*.txt 회수")
    rp.add_argument("--hours", type=int, default=24)
    rp.add_argument("--apply", action="store_true")
    rp.add_argument("--dry-run", action="store_true")
    rp.set_defaults(func=cmd_rescue)

    args = ap.parse_args(argv)
    try:
        return args.func(args)
    except BrokenPipeError:
        return EXIT_IO


if __name__ == "__main__":
    sys.exit(main())
