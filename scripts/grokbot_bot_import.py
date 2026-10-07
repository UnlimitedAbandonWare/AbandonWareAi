#!/usr/bin/env python3
"""Import Grok Bot (desktop app) durable memory into the demo-1 tree.

Reads a Grok Bot export — a folder or a zip — in either bundle format:

  v1  memory/RULES.md + memory/EPISODES_*.md (+ optional MANIFEST.json)
  v2  memory/MEMORY_PROFILE.md (A/B/C/D sections) + memory/MEMORY_LOG.md
      (★ lines) + memory/SHARED_USER_MEMORY.md; ★ lines under
      prev/*/memory/MEMORY_LOG.md are picked up too. No MANIFEST.

then produces three artifacts for agy / repo agents:

  docs/GROKBOT_BOT_RULES.md                    SSOT copy of the rules (redacted)
  data/agent-handoff/grokbot/bot_episodes.jsonl  one JSON object per episode
                                               line, deduped by sha12
  .agents/rules/grokbot-bot-memory.md          <=5-line pointer rule

Default mode is --dry-run; pass --apply to write. Lines whose value matches
a secret shape are replaced with ``[REDACTED]`` and reported by count and
line number only — the values themselves are never printed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
# value-shaped secrets only — bare words like "token"/"key" in prose (e.g.
# "max-output-tokens", "API 키 진단", "401/403/429") must not trip it (R21)
SECRET_RE = re.compile(
    r"sk-[0-9A-Za-z_-]{20,}"
    r"|ghp_[0-9A-Za-z]{30,}"
    r"|vck_[0-9A-Za-z]{20,}"
    r"|AIza[0-9A-Za-z_-]{30,}"
    r"|(?i:password|passwd)\s*[:=]\s*\S+"
    r"|(?i:bearer)\s+[0-9A-Za-z._~+/=-]{10,}"
    r"|(?i:secret|token|api[_ -]?key)\s*[:=]\s*['\"]?[0-9A-Za-z._~+/-]{8,}"
)
PASTE_RE = re.compile(r"(PASTE_[0-9A-Za-z_.,~-]+?\.txt|PASTE_[0-9A-Za-z_-]+)")
SHA12_RE = re.compile(r"/\s*([0-9a-fA-F]{12})\b")
SHA12_TAG_RE = re.compile(r"(?i)sha12\s*[:=]?\s*([0-9a-f]{12})")
SHA12_BARE_RE = re.compile(r"(?<![0-9a-fA-F])([0-9a-fA-F]{12})(?![0-9a-fA-F])")
EP_DATE_RE = re.compile(r"EPISODES_(\d{8})\.md$")
EP_TIME_RE = re.compile(r"^(오전|오후|\d{1,2}:\d{1,2}[xX]?|\d{1,2}:\d{2}~\d{1,2}:\d{2})\s+")
SECTION_RE = re.compile(r"^##\s*([A-D])\.")
RULE_LINE_RE = re.compile(r"^(R\d+[a-zA-Z]?|P\d+|N\d+|X\d+)\s")
SHARED_USER_RE = re.compile(r"^U\d+\s")
LOG_DATE_RE = re.compile(r"^##\s*(\d{4})-(\d{2})-(\d{2})")
V2_TIME_RE = re.compile(r"^~?(\d{1,2}:\d{1,2}[xX]?|\d{2}-\d{2})\s+")

POINTER_LINES = [
    "# Grok Bot bot memory pointer — SSOT: docs/GROKBOT_BOT_RULES.md · episodes: data/agent-handoff/grokbot/bot_episodes.jsonl",
    "- R1 말투: 한국어 쉬운 존댓말, 결론 첫 줄, 중요한 답 끝 `한 줄:`.",
    "- R13 비용(화력 위주): Codex 크레딧 → 외부 유료 API → 무료 → 로컬 Ollama 맨 마지막; 지시서마다 라이브 호출 상한; 401/403/429 재시도 없음.",
    "- R12b AUTO 기본 + R20 범위 예산: 되돌릴 수 있는 작은 확장(≤3파일·≤300줄)은 AUTO + SCOPE_EXPAND 기록.",
    "- 전체 규칙은 docs/GROKBOT_BOT_RULES.md; 재가져오기는 가장 최신 var/grokbot-export-* 를 --src 로 `python -B scripts/grokbot_bot_import.py --src var/grokbot-export-20261006 --apply`.",
]

AGY_RULE_FRONTMATTER = "---\ntrigger: always_on\n---\n\n"
POINTER_TEXT = AGY_RULE_FRONTMATTER + "\n".join(POINTER_LINES) + "\n"


def sha12(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:12]


def read_export(src: str):
    """Return (files: {relpath: bytes}, manifest: dict|None)."""
    files: dict[str, bytes] = {}
    if os.path.isdir(src):
        for base, _dirs, names in os.walk(src):
            for name in names:
                fp = os.path.join(base, name)
                rel = os.path.relpath(fp, src).replace(os.sep, "/")
                with open(fp, "rb") as fh:
                    files[rel] = fh.read()
    elif zipfile.is_zipfile(src):
        with zipfile.ZipFile(src) as zf:
            names = [n for n in zf.namelist() if not n.endswith("/")]
            tops = {n.split("/", 1)[0] for n in names}
            prefix = ""
            if len(tops) == 1 and all("/" in n for n in names):
                prefix = next(iter(tops)) + "/"
            for n in names:
                files[n[len(prefix):]] = zf.read(n)
    else:
        raise FileNotFoundError(f"import source not found: {src}")
    manifest = None
    if "MANIFEST.json" in files:
        manifest = json.loads(files["MANIFEST.json"].decode("utf-8"))
    return files, manifest


def verify_manifest(files: dict, manifest: dict):
    """Return [(path, reason)] for every manifest entry that fails."""
    fails = []
    for entry in manifest.get("files", []):
        path = entry["path"]
        blob = files.get(path)
        if blob is None:
            fails.append((path, "missing"))
            continue
        if "bytes" in entry and len(blob) != entry["bytes"]:
            fails.append((path, f"bytes {len(blob)} != {entry['bytes']}"))
        if "sha12" in entry and sha12(blob) != entry["sha12"]:
            fails.append((path, "sha12 mismatch"))
    return fails


def redact_line(line: str):
    """Return (line_or_REDACTED, was_redacted)."""
    if SECRET_RE.search(line):
        return "[REDACTED]", True
    return line, False


def detect_format(files: dict):
    """'v2' when memory/MEMORY_PROFILE.md exists, 'v1' for memory/RULES.md."""
    if "memory/MEMORY_PROFILE.md" in files:
        return "v2"
    if any(p.endswith("memory/RULES.md") or p == "memory/RULES.md"
           for p in files):
        return "v1"
    return None


def parse_profile_v2(text: str):
    """Split MEMORY_PROFILE.md into top comment lines + lettered sections."""
    comments, sections = [], {}
    cur = None
    for line in text.splitlines():
        m = SECTION_RE.match(line)
        if m:
            cur = m.group(1)
            sections[cur] = {"title": line.rstrip(), "lines": []}
            continue
        if not line.strip():
            continue
        if cur is None:
            comments.append(line.rstrip())
        else:
            sections[cur]["lines"].append(line.rstrip())
    return comments, sections


def build_rules_doc(rules_text: str, src_label: str, src_rel: str,
                    head_notes=None, shared_text: str = "", fmt: str = "v1"):
    """Wrap the rules body as an SSOT doc; returns (doc_text, stats)."""
    now = datetime.now(KST).strftime("%Y-%m-%d %H:%M KST")
    if fmt == "v2":
        comments, sections = parse_profile_v2(rules_text)
        body = list(comments)
        for sec in sections.values():
            body += ["", sec["title"]] + sec["lines"]
        shared = [l.strip() for l in shared_text.splitlines()
                  if SHARED_USER_RE.match(l)]
        if shared:
            body += ["", "## 공유 사용자 메모리 (scope=user)"] + shared
        stats = {
            "rules": sum(1 for key, sec in sections.items() if key != "C"
                         for l in sec["lines"] if RULE_LINE_RE.match(l)),
            "retired": 1 if sections.get("C", {}).get("lines") else 0,
            "retired_lines": len(sections.get("C", {}).get("lines", [])),
        }
    else:
        body = rules_text.splitlines()
        stats = {"rules": sum(1 for l in body if RULE_LINE_RE.match(l)),
                 "retired": 0, "retired_lines": 0}
    out_lines, redacted_lines = [], []
    for idx, line in enumerate(body):
        new, hit = redact_line(line)
        if hit:
            redacted_lines.append(idx + 1)
        out_lines.append(new)
    header = [
        "# Grok Bot (desktop app) durable rules — SSOT copy for agy / agents on demo-1",
        "",
        f"- 출처: `{src_label}` (가져온 시각 {now})",
        "- 새 결정이 이김: 이 파일의 어떤 줄보다 최신 사용자 결정·AGENTS.md가 우선한다.",
        f"- 원본 export: {src_rel}",
    ]
    for note in (head_notes or []):
        header.append(f"- {note}")
    header.append("")
    stats["redacted"] = len(redacted_lines)
    stats["redacted_lines"] = redacted_lines
    return "\n".join(header + out_lines).rstrip() + "\n", stats


def parse_episode_line(line: str, date_iso: str):
    """Parse one '- ...' episode line -> record dict or None."""
    text = line.strip()
    if not text.startswith("- "):
        return None
    body = text[2:].strip()
    if not body:
        return None
    kst = None
    m = EP_TIME_RE.match(body)
    if m:
        kst = m.group(1)
        body = body[m.end():].strip()
    file_m = PASTE_RE.search(body)
    file_name = file_m.group(1) if file_m else None
    sha_m = SHA12_RE.search(body)
    if sha_m:
        digest = sha_m.group(1).lower()
        summary = body[sha_m.end():].strip()
    else:
        digest = "line-" + sha12(body.encode("utf-8"))
        summary = body
    summary = summary.lstrip("—-").strip()
    summary, _ = redact_line(summary)
    return {"date": date_iso, "kst": kst, "file": file_name,
            "sha12": digest, "summary": summary}


def collect_episodes(files: dict):
    """Parse every memory/EPISODES_*.md -> (records, skipped_non_episode_lines)."""
    records, skipped = [], 0
    ep_paths = sorted(p for p in files
                      if p.startswith("memory/EPISODES_") and p.endswith(".md"))
    for path in ep_paths:
        date_m = EP_DATE_RE.search(path)
        raw = date_m.group(1) if date_m else "00000000"
        date_iso = f"{raw[:4]}-{raw[4:6]}-{raw[6:8]}" if len(raw) == 8 else raw
        text = files[path].decode("utf-8")
        for line in text.splitlines():
            if not line.strip().startswith("- "):
                continue
            rec = parse_episode_line(line, date_iso)
            if rec:
                records.append(rec)
            else:
                skipped += 1
    return records, skipped


def parse_star_line(line: str, date_iso: str):
    """Parse one v2 '★ ...' memory-log line -> record dict or None."""
    text = line.strip()
    if not text.startswith("★"):
        return None
    body = text.lstrip("★").strip()
    if not body:
        return None
    kst = None
    m = V2_TIME_RE.match(body)
    if m:
        kst = m.group(1)
        body = body[m.end():].strip()
    file_m = PASTE_RE.search(body)
    file_name = file_m.group(1) if file_m else None
    sha_m = (SHA12_TAG_RE.search(body) or SHA12_RE.search(body)
             or SHA12_BARE_RE.search(body))
    digest = sha_m.group(1).lower() if sha_m else "line-" + sha12(
        body.encode("utf-8"))
    summary, _ = redact_line(body)
    return {"date": date_iso, "kst": kst, "file": file_name,
            "sha12": digest, "summary": summary}


def collect_episodes_v2(files: dict):
    """Parse ★ lines from MEMORY_LOG.md (incl. prev/*/memory/) -> records."""
    records, skipped = [], 0
    log_paths = sorted(
        p for p in files
        if p == "memory/MEMORY_LOG.md"
        or (p.startswith("prev/") and p.endswith("/memory/MEMORY_LOG.md")))
    for path in log_paths:
        date_iso = "0000-00-00"
        for line in files[path].decode("utf-8").splitlines():
            dm = LOG_DATE_RE.match(line)
            if dm:
                date_iso = "-".join(dm.groups())
                continue
            if not line.strip().startswith("★"):
                continue
            rec = parse_star_line(line, date_iso)
            if rec:
                records.append(rec)
            else:
                skipped += 1
    return records, skipped


def existing_sha12s(jsonl_path: str):
    """Read dedupe keys already stored in bot_episodes.jsonl."""
    keys = set()
    if not os.path.exists(jsonl_path):
        return keys
    with open(jsonl_path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                keys.add(json.loads(line).get("sha12"))
            except json.JSONDecodeError:
                continue
    return keys


def write_text(path: str, text: str):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def main(argv=None, root=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--src", required=True,
                        help="export folder or zip (e.g. var/grokbot-export-20261003)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", default=True)
    mode.add_argument("--apply", action="store_true")
    parser.add_argument("--out-root", default=None,
                        help="redirect the three outputs under this root (tests)")
    parser.add_argument("--src-label", default=None,
                        help="override the 출처 label in the rules doc header")
    parser.add_argument("--head-note", action="append", default=None,
                        help="extra '- <note>' line in the rules doc header")
    args = parser.parse_args(argv)

    root = root or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_root = args.out_root or root
    src = args.src if os.path.isabs(args.src) else os.path.join(root, args.src)

    try:
        files, manifest = read_export(src)
    except (FileNotFoundError, zipfile.BadZipFile) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        return 2

    if manifest is None:
        print(json.dumps({"ok": True, "warning": "MANIFEST.json absent - contents unverified"},
                         ensure_ascii=False))
    else:
        fails = verify_manifest(files, manifest)
        if fails:
            print(json.dumps({"ok": False, "error": "manifest verify failed",
                              "fails": fails}, ensure_ascii=False))
            return 3

    fmt = detect_format(files)
    if fmt is None:
        print(json.dumps({"ok": False,
                          "error": "memory/MEMORY_PROFILE.md or memory/RULES.md "
                                   "not in export"}, ensure_ascii=False))
        return 4
    rules_key = ("memory/MEMORY_PROFILE.md" if fmt == "v2"
                 else next(p for p in files if p.endswith("memory/RULES.md")))

    rules_text = files[rules_key].decode("utf-8")
    shared_text = (files.get("memory/SHARED_USER_MEMORY.md", b"")
                   .decode("utf-8") if fmt == "v2" else "")
    src_label = args.src_label or os.path.basename(src.rstrip(os.sep))
    src_rel = os.path.relpath(src, root) if src.startswith(root) else src
    doc, stats = build_rules_doc(rules_text, src_label, src_rel,
                                 head_notes=args.head_note,
                                 shared_text=shared_text, fmt=fmt)
    redacted_rules = stats["redacted"]
    records, skipped = (collect_episodes_v2(files) if fmt == "v2"
                        else collect_episodes(files))
    for rec in records:
        if SECRET_RE.search(rec["summary"]) or rec["summary"] == "[REDACTED]":
            rec["summary"] = "[REDACTED]"

    ep_out = os.path.join(out_root, "data", "agent-handoff", "grokbot",
                          "bot_episodes.jsonl")
    rules_out = os.path.join(out_root, "docs", "GROKBOT_BOT_RULES.md")
    pointer_out = os.path.join(out_root, ".agents", "rules",
                               "grokbot-bot-memory.md")

    known = existing_sha12s(ep_out)
    new_records = [r for r in records if r["sha12"] not in known]
    redacted_eps = sum(1 for r in records if r["summary"] == "[REDACTED]")
    skill_count = sum(1 for p in files
                      if p.startswith("skills/") and p.endswith("/SKILL.md"))
    shared_count = sum(1 for l in shared_text.splitlines()
                       if SHARED_USER_RE.match(l))

    plan = {
        "ok": True,
        "mode": "apply" if args.apply else "dry-run",
        "src": src_rel,
        "format": fmt,
        "rules": stats["rules"],
        "retired": stats["retired"],
        "retired_lines": stats["retired_lines"],
        "episodes": len(records),
        "sharedUser": shared_count,
        "skills": skill_count,
        "redacted": redacted_rules + redacted_eps,
        "redacted_lines": stats["redacted_lines"],
        "export_files": len(files),
        "episodes_parsed": len(records),
        "episodes_new": len(new_records),
        "episodes_skipped_lines": skipped,
        "redacted_rules_lines": redacted_rules,
        "redacted_episode_lines": redacted_eps,
        "outputs": [
            {"path": os.path.relpath(rules_out, out_root), "bytes": len(doc.encode("utf-8"))},
            {"path": os.path.relpath(ep_out, out_root), "append_lines": len(new_records)},
            {"path": os.path.relpath(pointer_out, out_root), "lines": len(POINTER_LINES)},
        ],
    }
    if redacted_rules or redacted_eps:
        plan["warning"] = "secret-looking lines replaced with [REDACTED]"
    print(json.dumps(plan, ensure_ascii=False))

    if not args.apply:
        return 0
    write_text(rules_out, doc)
    if new_records:
        os.makedirs(os.path.dirname(ep_out), exist_ok=True)
        with open(ep_out, "a", encoding="utf-8", newline="\n") as fh:
            for rec in new_records:
                fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    write_text(pointer_out, POINTER_TEXT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
