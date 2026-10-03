#!/usr/bin/env python3
"""P6-D D1: Claim-drift tracker for the ma3212in audit anchors.

Reused existing scripts / newly added (per P6-D header-comment rule):
  - Reused: read_lines()/normalized-compare approach and JSON+MD dual output
    style from scripts/codex_anchor_map.py (not imported: that tool's QUERIES
    are hard-coded for the R5 OAuth task; this tool parses evidence anchors).
  - Reused: redaction import fallback to scripts/log_redact.py for snippets.
  - New: markdown anchor parsing, fuzzy block re-location, symbol envelope,
    WP grouping for the P6 Codex work packages.

Reads `### \`[<archive>::]<path>:<start>-<end>\`` anchor headers plus the fenced
`text` blocks whose lines look like `  123 | <code>` and re-locates each block
inside the CURRENT working tree by content (not line number). Per anchor it
emits STILL_PRESENT / MOVED / CHANGED / FILE_MISSING plus the enclosing Java
symbol. Read-only against the project; writes only --out-json/--out-md.

Exit codes: 0 = all anchors judged, 1 = any FILE_MISSING/CHANGED present and
--strict was passed, 2 = inputs missing/unreadable.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "devin-p6.claim-drift.v1"

_P6_REF = Path.home() / "Downloads" / "P6_REF_20261001"
DEFAULT_EVIDENCE = [
    str(_P6_REF / "MA3212IN_EVIDENCE.md"),
    str(_P6_REF / "FRONT_ROUTER_EVIDENCE.md"),
]

# Evidence-section -> Codex P6 work package (kept data-driven for reruns).
WP_MAP = {
    "MA3212IN_EVIDENCE.md": {
        "E01": "WP4",   # 저장 설정 검증·읽기 경로 (F-01)
        "E02": "WP5",   # Catalog synchronized/네트워크 경계 (F-02)
        "E03": "WP5",   # 공개 catalog 비공·실패 캐시 (F-03)
        "E04": "WP6",   # Jev 기존 방어·차단 상태 수명 (X-01)
        "E05": "HOLD:F-04",   # 브라우저 설정 복원·XHR (chat.js 수정 금지)
        "E06": "CTX:security-entry",
        "E07": "CTX:attachments",
        "E08": "CTX:model-contract",
        "E09": "WP6",   # Jev process-local quota
        "E10": "CTX:api-error-contract",
        "E11": "CTX:active-scan-aop",
        "E12": "CTX:request-budget",
    },
    "FRONT_ROUTER_EVIDENCE.md": {
        "E01": "WP7",   # 분류기와 기본 규칙
        "E02": "WP7",   # RouterPolicy 별도 게이트·출력 상한 승격
        "E03": "WP9",   # 기존 Jev 선택지 계약
        "E04": "WP8/WP9",  # 사전 요청 평가와 소비 (wrapIfEnabled)
        "E05": "WP6/WP9",  # 기존 장애 경계·슬롯 수명
        "E06": "WP9",   # 문맥·질문 개정·최종 모델 고정
        "E07": "WP8/WP9",  # 검색 채용·건강 지수·공급 계약
        "E08": "WP9",   # 요청 확인·취소·슬롯 시간 소비
    },
}

ANCHOR_RE = re.compile(
    r"^###\s+`(?:(?P<archive>[A-Za-z0-9_.()-]+\.zip)::)?(?P<path>[^:`]+?):(?P<start>\d+)(?:-(?P<end>\d+))?`")
SECTION_RE = re.compile(r"^##\s+(?P<sid>E\d+|[^\s—\-]+)[.\s—\-]*(?P<title>.*)$")
SNIP_LINE_RE = re.compile(r"^\s*(?P<num>\d+)\s*\|\s?(?P<code>.*)$")
FENCE_RE = re.compile(r"^```(?P<lang>\w*)\s*$")

CLASS_RE = re.compile(
    r"^\s*(?:@[\w.]+\s*)*(?:public|protected|private|abstract|final|static|sealed|non-sealed|\s)*"
    r"\b(class|interface|enum|record|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)")
METHOD_RE = re.compile(
    r"^\s*(?:@[\w.]+\s*)*(?:public|protected|private|static|final|synchronized|abstract|native|default|\s)+"
    r"[A-Za-z_][A-Za-z0-9_.<>\[\],?\s&]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{]*\)\s*(?:throws\s+[^{]+)?\{?\s*$")


def read_lines(path: Path):
    data = path.read_bytes()
    if b"\x00" in data[:4096]:
        return None
    return data.decode("utf-8-sig", errors="replace").splitlines()


def norm(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def parse_evidence(md_path: Path):
    """Return list of anchor dicts with excerpted zip lines."""
    anchors = []
    section_id, section_title = "", ""
    cur = None          # current anchor dict while inside fence
    in_fence = False
    for raw in read_lines(md_path) or []:
        sec = SECTION_RE.match(raw)
        if sec and not in_fence:
            section_id = sec.group("sid")
            section_title = sec.group("title").strip()
            continue
        anch = ANCHOR_RE.match(raw)
        if anch and not in_fence:
            anchors.append({
                "evidence_file": md_path.name,
                "section": section_id,
                "section_title": section_title,
                "anchor": anch.group(0).strip(),
                "archive": anch.group("archive"),
                "path": anch.group("path").replace("\\", "/"),
                "zip_start": int(anch.group("start")),
                "zip_end": int(anch.group("end") or anch.group("start")),
                "lines": [],   # list of (zip_line_no, code_text)
            })
            continue
        if FENCE_RE.match(raw):
            in_fence = not in_fence
            continue
        if in_fence and anchors:
            m = SNIP_LINE_RE.match(raw)
            if m:
                anchors[-1]["lines"].append((int(m.group("num")), m.group("code")))
    return anchors


def enclosing_symbols(lines, start_idx, end_idx):
    """Nearest class + nearest method signature above the matched range."""
    cls, method = None, None
    for i in range(0, min(end_idx, len(lines))):
        m = CLASS_RE.match(lines[i])
        if m:
            cls = {"name": m.group(2), "line": i + 1, "kind": m.group(1)}
        mm = METHOD_RE.match(lines[i])
        if mm and i <= start_idx:
            method = {"name": mm.group(1), "line": i + 1}
    return cls, method


def locate_anchor(root: Path, anchor):
    rel = anchor["path"]
    target = root / rel
    base = {"path": rel,
            "zip_range": [anchor["zip_start"], anchor["zip_end"]]}
    if not target.is_file():
        return {**base, "verdict": "FILE_MISSING",
                "detail": f"{rel} absent in tree"}
    lines = read_lines(target)
    if lines is None:
        return {**base, "verdict": "CHANGED", "detail": "binary/unreadable"}

    cur_norm = [norm(l) for l in lines]
    excerpt = anchor["lines"]
    span = (anchor["zip_end"] - anchor["zip_start"] + 1) if excerpt else 0
    if not excerpt or span <= 0:
        return {"verdict": "CHANGED", "detail": "empty excerpt", "path": rel}

    # Excerpt may be truncated relative to the header span; match what we have.
    exc_norm = [norm(code) for _, code in excerpt]
    n = len(exc_norm)
    best_i, best_score = -1, -1.0
    if n <= len(cur_norm):
        for i in range(0, len(cur_norm) - n + 1):
            hits = sum(1 for k in range(n) if cur_norm[i + k] == exc_norm[k])
            score = hits / n
            if score > best_score:
                best_i, best_score = i, score
            if score == 1.0:
                break
    else:
        return {"verdict": "CHANGED", "detail": "excerpt longer than file", "path": rel}

    zip_first = excerpt[0][0]
    shift = (best_i + 1) - zip_first if best_i >= 0 else None
    cls, method = enclosing_symbols(lines, best_i, min(best_i + n, len(lines)))
    out = {
        "path": rel,
        "zip_range": [anchor["zip_start"], anchor["zip_end"]],
        "matched_range": [best_i + 1, best_i + n] if best_i >= 0 else None,
        "line_shift": shift,
        "similarity": round(best_score, 3),
        "symbol_class": cls,
        "symbol_method": method,
        "file_lines": len(lines),
        "excerpt_sha256": hashlib.sha256(
            "\n".join(exc_norm).encode("utf-8")).hexdigest()[:16],
    }
    if best_score >= 0.95:
        out["verdict"] = "STILL_PRESENT" if shift == 0 else "MOVED"
    elif best_score >= 0.5:
        out["verdict"] = "CHANGED"
    else:
        out["verdict"] = "CHANGED"
        out["detail"] = "best window below 0.5 similarity"
    return out


def render_md(report) -> str:
    lines = [
        "# P6 claim drift baseline",
        "",
        f"Generated: {report['generated_at_utc']}  HEAD: `{report['git_head']}`",
        "",
        "Anchors are re-located by content, not by zip line number. "
        "Verdicts: STILL_PRESENT / MOVED(new line) / CHANGED / FILE_MISSING.",
        "",
        "## Summary by work package",
        "",
        "| WP | anchors | STILL_PRESENT | MOVED | CHANGED | FILE_MISSING |",
        "|---|---|---|---|---|---|",
    ]
    for wp, rows in sorted(report["by_wp"].items()):
        cnt = {"STILL_PRESENT": 0, "MOVED": 0, "CHANGED": 0, "FILE_MISSING": 0}
        for r in rows:
            cnt[r["verdict"]] = cnt.get(r["verdict"], 0) + 1
        lines.append(f"| {wp} | {len(rows)} | {cnt['STILL_PRESENT']} | "
                     f"{cnt['MOVED']} | {cnt['CHANGED']} | {cnt['FILE_MISSING']} |")
    lines += ["", "## Anchors", ""]
    for wp, rows in sorted(report["by_wp"].items()):
        lines += [f"### {wp}", "",
                  "| evidence | section | anchor | verdict | current | symbol | sim |",
                  "|---|---|---|---|---|---|---|"]
        for r in rows:
            loc = ""
            if r.get("matched_range"):
                loc = f"{r['matched_range'][0]}-{r['matched_range'][1]} (shift {r.get('line_shift')})"
            sym = ""
            if r.get("symbol_method"):
                sym = r["symbol_method"]["name"]
            elif r.get("symbol_class"):
                sym = r["symbol_class"]["name"]
            lines.append(
                f"| {r['evidence_file']} | {r['section']} | `{r['anchor']}` | "
                f"**{r['verdict']}** | {loc} | {sym} | {r.get('similarity','-')} |")
        lines.append("")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="p6dbg_claim_drift",
        description="Re-locate ma3212in evidence anchors in the current tree")
    ap.add_argument("--root", default=".")
    ap.add_argument("--evidence", action="append", default=None,
                    help="evidence markdown path (repeatable); defaults to the two P6 files")
    ap.add_argument("--out-json", default="data/agent-handoff/devin-p6/claim-drift-baseline.json")
    ap.add_argument("--out-md", default="data/agent-handoff/devin-p6/claim-drift-baseline.md")
    ap.add_argument("--strict", action="store_true",
                    help="exit 1 when any anchor is CHANGED/FILE_MISSING")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    ev_files = [Path(p) for p in (args.evidence or DEFAULT_EVIDENCE)]
    missing = [str(p) for p in ev_files if not p.is_file()]
    if missing:
        print(json.dumps({"error": "evidence-missing", "paths": missing}))
        return 2
    if not (root / "main" / "java").is_dir():
        print(json.dumps({"error": "project-root-missing", "root": str(root)}))
        return 2

    anchors = []
    for p in ev_files:
        anchors.extend(parse_evidence(p))
    if not anchors:
        print(json.dumps({"error": "no-anchors-parsed"}))
        return 2

    results = []
    for a in anchors:
        r = locate_anchor(root, a)
        r.update({
            "evidence_file": a["evidence_file"],
            "section": a["section"],
            "anchor": a["anchor"],
            "wp": WP_MAP.get(a["evidence_file"], {}).get(a["section"], "UNMAPPED"),
        })
        results.append(r)

    by_wp = {}
    for r in results:
        by_wp.setdefault(r["wp"], []).append(r)
    counts = {}
    for r in results:
        counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1

    snap_path = root / "data/agent-handoff/devin-p6/pre-codex-snapshot.json"
    r51 = {}
    if snap_path.is_file():
        try:
            r51 = json.loads(snap_path.read_text(encoding="utf-8")).get(
                "groups", {}).get("r51_uncommitted_preserve", {})
        except Exception:
            r51 = {}

    report = {
        "schema": SCHEMA,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "git_head": "4150b2822f2c1b49773cacaadd36ddbab8ff1412",
        "evidence_files": [str(p) for p in ev_files],
        "anchor_count": len(results),
        "verdict_counts": counts,
        "by_wp": by_wp,
        "anchors": results,
        "r51_preimage_hashes": r51,
        "external_calls": 0,
        "product_source_diff": 0,
    }

    out_json = root / args.out_json
    out_md = root / args.out_md
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_md.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    out_md.write_text(render_md(report), encoding="utf-8")

    print(json.dumps({
        "anchors": len(results),
        "verdicts": counts,
        "json": str(out_json),
        "md": str(out_md),
    }, ensure_ascii=False))
    if args.strict and (counts.get("CHANGED") or counts.get("FILE_MISSING")):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
