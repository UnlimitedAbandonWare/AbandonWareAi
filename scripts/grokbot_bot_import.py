#!/usr/bin/env python3
"""Import Grok Bot (desktop app) durable memory into the demo-1 tree.

Reads a Grok Bot export — a folder or a zip — that contains
``memory/RULES.md``, ``memory/EPISODES_*.md`` and ``MANIFEST.json``, then
produces three artifacts for agy / repo agents:

  docs/GROKBOT_BOT_RULES.md                    SSOT copy of RULES.md (redacted)
  data/agent-handoff/grokbot/bot_episodes.jsonl  one JSON object per episode
                                               line, deduped by sha12
  .agents/rules/grokbot-bot-memory.md          <=5-line pointer rule

Default mode is --dry-run; pass --apply to write. Any line matching a
secret-looking pattern is replaced with ``[REDACTED]`` and reported.
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
SECRET_RE = re.compile(r"(?i)(key|token|password|bearer|vck_|sk-|AIza|ghp_)")
PASTE_RE = re.compile(r"(PASTE_\S+?\.txt)")
SHA12_RE = re.compile(r"/\s*([0-9a-fA-F]{12})\b")
EP_DATE_RE = re.compile(r"EPISODES_(\d{8})\.md$")
EP_TIME_RE = re.compile(r"^(오전|오후|\d{1,2}:\d{1,2}[xX]?|\d{1,2}:\d{2}~\d{1,2}:\d{2})\s+")

POINTER_LINES = [
    "# Grok Bot bot memory pointer — SSOT: docs/GROKBOT_BOT_RULES.md · episodes: data/agent-handoff/grokbot/bot_episodes.jsonl",
    "- R1 말투: 한국어 쉬운 존댓말, 결론 첫 줄, 중요한 답 끝 `한 줄:`.",
    "- R13 비용(화력 위주): Codex 크레딧 → 외부 유료 API → 무료 → 로컬 Ollama 맨 마지막; 지시서마다 라이브 호출 상한; 401/403/429 재시도 없음.",
    "- R12b AUTO 기본 + R20 범위 예산: 되돌릴 수 있는 작은 확장(≤3파일·≤300줄)은 AUTO + SCOPE_EXPAND 기록.",
    "- 전체 규칙은 docs/GROKBOT_BOT_RULES.md; 재가져오기 `python -B scripts/grokbot_bot_import.py --src var/grokbot-export-20261003 --apply`.",
]


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


def build_rules_doc(rules_text: str, src_label: str):
    """Wrap RULES.md as an SSOT doc; returns (doc_text, redacted_count)."""
    now = datetime.now(KST).strftime("%Y-%m-%d %H:%M KST")
    out_lines, redacted = [], 0
    for line in rules_text.splitlines():
        new, hit = redact_line(line)
        redacted += 1 if hit else 0
        out_lines.append(new)
    header = [
        "# Grok Bot (desktop app) durable rules — SSOT copy for agy / agents on demo-1",
        "",
        f"- 출처: Grok Bot export `{src_label}` (가져온 시각 {now})",
        "- 새 결정이 이김: 이 파일의 어떤 줄보다 최신 사용자 결정·AGENTS.md가 우선한다.",
        "- 원본 export: var/grokbot-export-20261003/memory/RULES.md",
        "",
    ]
    return "\n".join(header + out_lines).rstrip() + "\n", redacted


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
        print(json.dumps({"ok": True, "warning": "MANIFEST.json absent — contents unverified"},
                         ensure_ascii=False))
    else:
        fails = verify_manifest(files, manifest)
        if fails:
            print(json.dumps({"ok": False, "error": "manifest verify failed",
                              "fails": fails}, ensure_ascii=False))
            return 3

    rules_path = next((p for p in files if p.endswith("memory/RULES.md")
                       or p == "memory/RULES.md"), None)
    if rules_path is None:
        print(json.dumps({"ok": False, "error": "memory/RULES.md not in export"},
                         ensure_ascii=False))
        return 4

    rules_text = files[rules_path].decode("utf-8")
    doc, redacted_rules = build_rules_doc(rules_text, os.path.basename(src.rstrip(os.sep)))
    records, skipped = collect_episodes(files)
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

    plan = {
        "ok": True,
        "mode": "apply" if args.apply else "dry-run",
        "src": os.path.relpath(src, root) if src.startswith(root) else src,
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
    write_text(pointer_out, "\n".join(POINTER_LINES) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
