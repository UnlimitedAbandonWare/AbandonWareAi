#!/usr/bin/env python3
"""dot_card_check.py — verify a dot ChatGPT Library file card; audit Downloads.

Contract DEMO1-DOT-FILE-CARD: a dot deliverable succeeds only when the reply
carries a Library file card (library_file_id). This tool proves the card exists
and audits Downloads for files the old deliver machinery would have piled up.

  --identity <json>          exit 0 + CARD_OK name=<file> libfile=<id[:12]> when
                             library_file_id, file_id and path are non-empty
                             strings; else exit 2 + CARD_MISSING missing=<keys>
  --downloads-audit          exit 0 when no hook-suspect file was created inside
      --since-minutes N      the window; exit 4 when suspects exist. Classes:
      [--downloads <dir>]      HOOK_SUSPECT  name matches the deliverable-name
                                            filter the removed Stop hook used
                               USER_DOWNLOAD browser ' (n)' suffix — a card the
                                            user actually clicked
                               OTHER         any other new file
                             Prints one line per file + an AUDIT summary.
  --sub-report <file> [--json] SUB_REPORT_V1 — 하위 에이전트 1회 완결 보고의
                             필수 6칸 검사(F2·F4, 2026-10-06). 칸 누락 시 칸
                             이름별 FAIL. exit 0 PASS / 2 칸 누락 / 3 IO 오류.
                             칸: field1_overall_status(상태 DONE|PARTIAL +
                             산출물 sha12) field2_item_status(항목별
                             PASS/PARTIAL 표) field3_input_source(입력 원본
                             파일명·sha12·INPUT_FALLBACK 여부)
                             field4_write_ledger(단계 support/brief × 위치
                             repo/Downloads/scratch 쓰기 장부)
                             field5_own_leases(own lease 잔존 수)
                             field6_not_run(NOT_RUN 목록). 계약 상세:
                             .agents/skills/demo1-dot-control-tower/
                             references/sub-report-contract.md

Stdlib only, no network, exits are stable for scripting. Values printed are
truncated (libfile first 12 chars); no secrets ever emitted.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

USER_DOWNLOAD_RE = re.compile(r"^.+ \(\d+\)\.[^.]+$")
REQUIRED_IDENTITY_KEYS = ("library_file_id", "file_id", "path")

EXIT_OK = 0
EXIT_MISSING = 2
EXIT_USAGE = 3
EXIT_HOOK_FOUND = 4

# SUB_REPORT_V1 (W4, 2026-10-06): 각 칸은 모든 패턴이 본문에 있어야 충족.
SUB_REPORT_FIELDS = (
    ("field1_overall_status",
     (r"(?:상태|overall|status)\s*[:：]\s*(?:DONE|PARTIAL|HOLD)",
      r"sha12\s*[:=]?\s*[0-9a-fA-F]{6,}")),
    ("field2_item_status",
     (r"\b(?:W|WP|A|DV)\d+\s*[:：]\s*(?:PASS|PARTIAL|FAIL|NOT_RUN|DONE)\b",)),
    ("field3_input_source",
     (r"(?:입력|input)[^\n]{0,40}(?:원본|source|파일|file)",
      r"sha12\s*[:=]?\s*[0-9a-fA-F]{6,}",
      r"INPUT_FALLBACK")),
    ("field4_write_ledger",
     (r"(?:쓰기|write)[^\n]{0,12}(?:장부|ledger)",
      r"support", r"brief",
      r"repo|저장소", r"Downloads", r"scratch")),
    ("field5_own_leases",
     (r"leases?[^\n]{0,12}(?:잔존|remaining|left)[^\n]{0,10}\d|"
      r"(?:own|자기|남은)[^\n]{0,12}leases?[^\n]{0,10}\d",)),
    ("field6_not_run", (r"NOT_RUN",)),
)


def deliverable_name(name: str) -> bool:
    """Same name filter the removed Stop hook used to copy into Downloads."""
    low = name.lower()
    if "지시서" in name:
        return True
    if low.endswith(".txt"):
        return low.startswith("paste_")
    if low.endswith(".md"):
        return ("directive" in low) or ("_report_" in low) or ("brief" in low)
    return False


def downloads_dir(override: str | None) -> Path:
    if override:
        return Path(override)
    env_dir = os.environ.get("AWX_DOWNLOADS_DIR")
    if env_dir:
        return Path(env_dir)
    return Path(os.environ.get("USERPROFILE", str(Path.home()))) / "Downloads"


def cmd_identity(path_str: str) -> int:
    try:
        data = json.loads(Path(path_str).read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"CARD_MISSING error={type(exc).__name__}")
        return EXIT_MISSING
    if not isinstance(data, dict):
        print("CARD_MISSING error=not-an-object")
        return EXIT_MISSING
    missing = [k for k in REQUIRED_IDENTITY_KEYS
               if not isinstance(data.get(k), str) or not data.get(k)]
    if missing:
        print(f"CARD_MISSING missing={','.join(missing)}")
        return EXIT_MISSING
    name = data.get("file_name") or Path(data["path"]).name
    print(f"CARD_OK name={name} libfile={data['library_file_id'][:12]}")
    return EXIT_OK


def cmd_audit(downloads: Path, since_minutes: int) -> int:
    cutoff = time.time() - since_minutes * 60
    suspects, user_dl, others = [], [], []
    try:
        entries = [p for p in downloads.iterdir() if p.is_file()]
    except OSError as exc:
        print(f"AUDIT_ERROR {type(exc).__name__}: {exc}")
        return EXIT_USAGE
    for p in entries:
        try:
            st = p.stat()
        except OSError:
            continue
        created = getattr(st, "st_ctime", st.st_mtime)
        if created < cutoff:
            continue
        row = (p.name, st.st_size)
        if USER_DOWNLOAD_RE.match(p.name):
            user_dl.append(row)
        elif deliverable_name(p.name):
            suspects.append(row)
        else:
            others.append(row)
    for tag, rows in (("HOOK_SUSPECT", suspects),
                      ("USER_DOWNLOAD", user_dl),
                      ("OTHER", others)):
        for name, size in sorted(rows):
            print(f"{tag} {name} {size}B")
    print(f"AUDIT hook_suspect={len(suspects)} user_download={len(user_dl)} "
          f"other={len(others)} since_minutes={since_minutes} dir={downloads}")
    return EXIT_HOOK_FOUND if suspects else EXIT_OK


def cmd_sub_report(path_str: str, as_json: bool) -> int:
    try:
        text = Path(path_str).read_text(encoding="utf-8-sig", errors="replace")
    except OSError as exc:
        print(f"SUB_REPORT_ERROR {type(exc).__name__}")
        return EXIT_USAGE
    results = {}
    missing = []
    for name, pats in SUB_REPORT_FIELDS:
        ok = all(re.search(p, text, re.IGNORECASE | re.M) for p in pats)
        results[name] = ok
        if not ok:
            missing.append(name)
    verdict = "PASS" if not missing else "FAIL"
    if as_json:
        print(json.dumps({"schema": "sub_report_v1", "verdict": verdict,
                          "missing": missing, "fields": results},
                         ensure_ascii=False))
    else:
        print(f"SUB_REPORT_V1 {verdict} fields={len(results) - len(missing)}"
              f"/{len(results)}"
              + ("" if not missing else " missing=" + ",".join(missing)))
    return EXIT_OK if not missing else EXIT_MISSING


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--identity", metavar="JSON",
                    help="dot directive-library-identity.json to verify")
    ap.add_argument("--downloads-audit", action="store_true",
                    help="classify files created in Downloads within --since-minutes")
    ap.add_argument("--since-minutes", type=int, default=60)
    ap.add_argument("--downloads", default=None,
                    help="Downloads dir override (default: AWX_DOWNLOADS_DIR or USERPROFILE)")
    ap.add_argument("--sub-report", metavar="FILE",
                    help="하위 에이전트 완결 보고 파일 — SUB_REPORT_V1 6칸 검사")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    if args.sub_report:
        return cmd_sub_report(args.sub_report, args.json)
    if args.identity:
        return cmd_identity(args.identity)
    if args.downloads_audit:
        if args.since_minutes <= 0:
            ap.error("--since-minutes must be > 0")
        return cmd_audit(downloads_dir(args.downloads), args.since_minutes)
    ap.print_help()
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
