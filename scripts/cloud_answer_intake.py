#!/usr/bin/env python3
"""cloud_answer_intake — vet an answer a cloud web agent wrote from a demo1_*.zip snapshot.

Cloud environments (Codex web / GPT Pro / dot) receive only a gptpro_pack ZIP:
no src/test, no gradlew, no app/build.gradle.kts, possibly dirty, minutes or
hours stale. Their answers mix several topics, cite snapshot line numbers that
drift, link sandbox:/mnt/data files the PC cannot open, claim executed tests,
and may widen a running directive's declared scope. This tool turns such an
answer into an intake card (채택/조정/폐기/사용자결정) instead of trusting it.

Subcommands:
    split <answer.txt> [--json]
    run --answer <txt> [--bundle <zip>] [--brief <PASTE_*.txt>]
        [--zip <source-snapshot.zip>] [--root <repo>] [--out <dir>]
        [--search-dir <Downloads>] [--self-lease <topic>]

Output (run): <out>/<yyyymmdd-HHMM>_<answerSha8>/intake.json + intake.md
Read-only against the repo except writing its own output dir. Git is used
read-only (rev-parse/status). Never executes anything the answer proposes.

Schema: awx.cloud-answer-intake.v1
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
SCHEMA = "awx.cloud-answer-intake.v1"
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_ROOT = SCRIPT_DIR.parent
GIT_CANDIDATES = ["git", r"F:\git\cmd\git.exe"]

sys.path.insert(0, str(SCRIPT_DIR))
try:
    import zip_path_to_sourceset as _zpts  # reuse: find_symbol_lines, roots
except ImportError:  # HOLD-3 fallback: tool still works, symbol search degraded
    _zpts = None

# ---------------------------------------------------------------------------
# Segmentation
# ---------------------------------------------------------------------------
HEADER_TABLE_RE = re.compile(r"\|\s*(입력|근거)\s*\|[^|]*\|[^|]*기준\s*\|")
GREETING_RE = re.compile(r"\*\*\(AbandonWare\)")
SNAPSHOT_NAME_RE = re.compile(
    r"demo1_([a-zA-Z0-9]+)_(\d{8})-(\d{4})_([0-9a-f]{4,10})(-dirty)?\.zip")
SNAPSHOT_TRUNC_RE = re.compile(
    r"demo1_([a-zA-Z0-9]+)_(\d{8}-\d{4})_([0-9a-f]{2,})…")
BOLD_SPAN_RE = re.compile(r"\*\*(.+?)\*\*")
H2_RE = re.compile(r"^##\s+(.+?)\s*$")


def _find_boundaries(lines: list[str]) -> list[int]:
    """0-based indices where a new segment starts. A boundary line can carry
    the tail of the previous segment (mid-line join) — it belongs to both."""
    marks = []
    for i, line in enumerate(lines):
        if HEADER_TABLE_RE.search(line):
            marks.append(("table", i))
        elif GREETING_RE.search(line):
            marks.append(("greeting", i))
    if not marks:
        return []
    # A greeting within a few lines of a table header belongs to the same
    # segment that the table opens (table card → greeting → 전달 파일).
    bounds = []
    for kind, idx in marks:
        if kind == "greeting" and bounds and idx - bounds[-1][0] <= 8:
            continue
        if bounds and idx == bounds[-1][0]:
            continue
        bounds.append((idx, kind))
    return bounds


def _seg_title(lines: list[str], start: int, end: int) -> str:
    for i in range(start, min(end + 1, len(lines))):
        line = lines[i].strip()
        if not line or line.startswith("|"):
            continue
        m = BOLD_SPAN_RE.search(line)
        if m:
            t = m.group(1).strip()
            return t[:110]
        h = H2_RE.match(line)
        if h:
            return h.group(1)[:110]
        if not line.startswith("```"):
            return line[:110]
    return ""


def split_segments(text: str) -> list[dict]:
    lines = text.splitlines()
    bounds = _find_boundaries(lines)
    if not bounds or bounds[0][0] != 0:
        bounds = [(0, "file")] + [b for b in bounds if b[0] != 0]
    segs = []
    for k, (start, kind) in enumerate(bounds):
        # A boundary line carrying both segments' content counts in both
        # (shared-line convention: A:1-165, B:165-321).
        end = bounds[k + 1][0] if k + 1 < len(bounds) else len(lines) - 1
        body = "\n".join(lines[start:end + 1])
        snaps = sorted({m.group(0) for m in SNAPSHOT_NAME_RE.finditer(body)}
                       | {m.group(0) for m in SNAPSHOT_TRUNC_RE.finditer(body)})
        # A table-boundary line is mid-line joined: the segment's own title
        # sits a few lines later (greeting), not in the shared tail.
        title_start = start + 1 if kind == "table" else start
        segs.append({
            "id": chr(ord("A") + k),
            "lineStart": start + 1,
            "lineEnd": end + 1,
            "boundarySharedLine": k + 1 < len(bounds) and bounds[k + 1][0] == end,
            "title": _seg_title(lines, title_start, end),
            "snapshots": snaps,
        })
    return segs


# ---------------------------------------------------------------------------
# Claim / anchor extraction
# ---------------------------------------------------------------------------
FILE_TOKEN = r"[\w.\\/*+-]+\.(?:java|kts|gradle|md|txt|py|js|ts|jsx|tsx|yml|yaml|xml|properties|json|bat|ps1|css|html|adoc)"
FILE_RANGE_RE = re.compile(
    r"((?:[a-zA-Z]:[\\/])?" + FILE_TOKEN + r")\s*[:：]\s*"
    r"(\d[\d,]*)\s*[–~—-]\s*(\d+)")
FILE_LINE_RE = re.compile(
    r"((?:[a-zA-Z]:[\\/])?" + FILE_TOKEN + r")\s*[:：]\s*(\d[\d,]*)"
    r"(?![\d,])(?!\s*[–~—-])")
KR_RANGE_RE = re.compile(
    r"([\w.-]+\.(?:java|md|kts))\s*(\d+)\s*[–~—-]\s*(\d+)\s*행")
SYMBOL_RANGE_RE = re.compile(
    r"\b([A-Za-z_$][\w$]*)\s*\(\s*\)\s*[:,：]?\s*"
    r"((?:\d+\s*[–~—-]\s*\d+)(?:\s*,\s*\d+\s*[–~—-]\s*\d+)*)")
REPO_PATH_RE = re.compile(
    r"(?<![:/\w.-])((?:main|app|src|docs|configs|scripts|frontend|gradle|data|var)"
    r"[/\\][\w./\\-]+\.(?:java|kts|gradle|md|py|js|ts|yml|yaml|xml|properties|json))")
RANGE_LIST_RE = re.compile(r"(\d+)\s*[–~—-]\s*(\d+)")
SYMBOL_MENTION_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\.([a-zA-Z_$][\w$]*)\s*\(")
NEG_CUE_RE = re.compile(r"없|missing|absent|없다|없는|없습니다")
FENCE_RE = re.compile(r"^\s*```")
IDENT_RE = re.compile(r"\b([A-Za-z_$][A-Za-z0-9_$]{3,})\b")
NUM_TOKEN_RE = re.compile(r"\b\d+\.\d+\b")

STOP_IDENT = {
    "public", "private", "protected", "return", "double", "float", "static",
    "final", "void", "import", "package", "class", "interface", "enum",
    "String", "Integer", "Long", "Boolean", "null", "true", "false", "this",
    "new", "record", "extends", "implements", "override", "abstract", "int",
    "long", "boolean", "throws", "var", "builder", "http", "https", "java",
    "main", "com", "example", "lms", "demo1", "zip", "sandbox",
    # generic role-words: substring hits in imports/class names everywhere —
    # no positional evidence value
    "DTO", "Factory", "Service", "Controller", "Helper", "helper", "Config",
    "Manager", "Utils", "Util", "Data", "Info", "Item", "Object",
}


def _line_numbers(rng_text: str) -> list[tuple[int, int]]:
    return [(int(a), int(b)) for a, b in RANGE_LIST_RE.findall(rng_text)]


def extract_claims(seg_text: str, seg_line_offset: int) -> list[dict]:
    """Extract file:line claims. Later lines' bare symbol():N~M binds to the
    most recently seen repo path (same convention as zip_path_to_sourceset)."""
    claims = []
    lines = seg_text.splitlines()
    current_path = None
    for li, line in enumerate(lines):
        abs_line = seg_line_offset + li
        for m in REPO_PATH_RE.finditer(line):
            current_path = m.group(1).replace("\\", "/")
        bound = set()
        for m in FILE_RANGE_RE.finditer(line):
            p = m.group(1).replace("\\", "/")
            a, b = int(m.group(2).replace(",", "")), int(m.group(3))
            claims.append({"line": abs_line, "path": p, "symbol": None,
                           "start": a, "end": b, "raw": m.group(0),
                           "kind": "range"})
            bound.add(id(m))
            current_path = p
        for m in FILE_LINE_RE.finditer(line):
            p = m.group(1).replace("\\", "/")
            if any(c["raw"] == m.group(0) for c in claims):
                continue
            n = int(m.group(2).replace(",", ""))
            claims.append({"line": abs_line, "path": p, "symbol": None,
                           "start": n, "end": n, "raw": m.group(0),
                           "kind": "line"})
            current_path = p
        for m in KR_RANGE_RE.finditer(line):
            p = m.group(1)
            claims.append({"line": abs_line, "path": p, "symbol": None,
                           "start": int(m.group(2)), "end": int(m.group(3)),
                           "raw": m.group(0), "kind": "range"})
            current_path = p
        for m in SYMBOL_RANGE_RE.finditer(line):
            if current_path is None:
                continue
            sym = m.group(1)
            for a, b in _line_numbers(m.group(2)):
                claims.append({"line": abs_line, "path": current_path,
                               "symbol": sym, "start": a, "end": b,
                               "raw": m.group(0), "kind": "range"})
        # Class.method( mentions — symbol-level claims with no line range.
        for m in SYMBOL_MENTION_RE.finditer(line):
            cls, meth = m.group(1), m.group(2)
            near = line[max(0, m.start() - 15):m.end() + 25]
            neg = bool(NEG_CUE_RE.search(near))
            claims.append({"line": abs_line, "path": f"{cls}.java",
                           "symbol": meth, "start": 0, "end": 0,
                           "raw": m.group(0), "kind": "symbolMention",
                           "polarity": "absent" if neg else "present"})
    # dedupe
    seen, out = set(), []
    for c in claims:
        key = (c["path"], c["symbol"], c["start"], c["end"])
        if key not in seen:
            seen.add(key)
            out.append(c)
    return out


def _evidence_tokens(seg_lines: list[str], claim: dict, seg_offset: int) -> dict:
    """Strong tokens = cited symbol + code identifiers near the claim.
    A fenced ``` block following the claim is code evidence (all idents
    strong); the claim's own prose line only yields tokens that look like
    code (uppercase/underscore/digit inside). The file's own stem never
    counts — it always sits at the top of the file. Weak = decimals."""
    strong, weak = [], []
    stem = Path(claim["path"]).stem
    if claim.get("symbol"):
        strong.append(claim["symbol"])
    li = claim["line"] - seg_offset
    prose = [seg_lines[li]] if 0 <= li < len(seg_lines) else []
    code = []
    for j in range(li + 1, min(li + 16, len(seg_lines))):
        if FENCE_RE.match(seg_lines[j]):
            for k in range(j + 1, min(j + 25, len(seg_lines))):
                if FENCE_RE.match(seg_lines[k]):
                    break
                code.append(seg_lines[k])
            break
    for tok in IDENT_RE.findall("\n".join(code)):
        if tok not in STOP_IDENT and tok != stem and tok not in strong:
            strong.append(tok)
    for tok in IDENT_RE.findall("\n".join(prose)):
        if (tok not in STOP_IDENT and tok != stem and tok not in strong
                and re.search(r"[A-Z0-9_]", tok)):
            strong.append(tok)
    for tok in NUM_TOKEN_RE.findall("\n".join(prose + code)):
        if tok not in weak:
            weak.append(tok)
    return {"strong": strong[:24], "weak": weak[:8]}


# ---------------------------------------------------------------------------
# Snapshot core-profile membership (what the cloud could NOT have seen)
# ---------------------------------------------------------------------------
CORE_EXACT = {
    "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
    "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
    "agents.md", "readme.md", ".env.example",
    "frontend/package.json", "frontend/next.config.mjs",
    "frontend/jsconfig.json", "frontend/.env.example",
    "docs/project_status.md",
}
CORE_PREFIX = (
    "main/java/", "main/resources/", "configs/", "frontend/src/",
    "frontend/scripts/", "docs/architecture/",
)


def in_core_profile(path: str) -> bool:
    p = path.replace("\\", "/").lower()
    return p in CORE_EXACT or any(p.startswith(x) for x in CORE_PREFIX)


PRUNE_DIRS = {".git", ".gradle", "node_modules", "build", "out", "__pycache__",
              "__patch_drop__", ".next", ".idea", ".venv", "venv", "data",
              "var", "logs", "uploads", "scratch", ".secrets"}


def _pruned_walk(root: Path, name: str, cap: int = 4) -> list[Path]:
    hits = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in PRUNE_DIRS]
        if name in filenames:
            hits.append(Path(dirpath) / name)
            if len(hits) >= cap:
                return hits
    return hits


def find_live_file(root: Path, ref: str) -> Path | None:
    ref = ref.replace("\\", "/")
    cand = root / ref
    if cand.is_file():
        return cand
    name = Path(ref).name
    if "/" not in ref:
        for base in ("main/java", "app/src/main/java_clean", "src/test/java",
                     "app/src/test/java", "main/resources", "docs", "configs",
                     "scripts"):
            b = root / base
            hits = _pruned_walk(b, name, cap=1) if b.is_dir() else []
            if hits:
                return hits[0]
        hits = _pruned_walk(root, name, cap=1)
        if hits:
            return hits[0]
    return None


def judge_claim(root: Path, claim: dict, tokens: dict, window: int = 40,
                zip_names: set[str] | None = None) -> dict:
    path = claim["path"].replace("\\", "/")
    out = dict(claim)
    out["evidenceTokens"] = tokens
    name = Path(path).name
    if name.lower() in ("agents.md",):
        out["normPath"] = path
        out.update(verdict="UNVERIFIABLE",
                   reason="agents.md byte claims checked via budget card, not lines")
        return out
    # Resolve bare filenames to the live tree BEFORE the core-profile check —
    # `ChatRequestSettingsMerger.java` is main/java/... and IS in the snapshot.
    live = find_live_file(root, path)
    resolved = str(live.relative_to(root)).replace("\\", "/") if live else path
    out["normPath"] = resolved
    if live is None and "/" not in path and claim.get("kind") != "symbolMention":
        out.update(verdict="REFUTED", reason="file absent in live tree")
        return out
    if zip_names is not None:
        in_zip = resolved in zip_names or path in zip_names \
            or name in {Path(n).name for n in zip_names}
        if not in_zip:
            out.update(verdict="UNVERIFIABLE",
                       reason="file absent from provided source zip — PC recheck needed")
            return out
    elif not in_core_profile(resolved):
        out.update(verdict="UNVERIFIABLE",
                   reason="path not in core snapshot profile — PC recheck needed")
        return out
    if live is None:
        if claim.get("kind") == "symbolMention":
            out.update(verdict="UNVERIFIABLE",
                       reason="class file not found (may be a nested type) — PC recheck needed")
        else:
            out.update(verdict="REFUTED", reason="file absent in live tree")
        return out
    out["livePath"] = resolved
    text = live.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    n_lines = len(lines)
    out["liveLines"] = n_lines
    sym = claim.get("symbol")

    # Symbol-mention claim (no line range): verify existence/absence only.
    if claim.get("kind") == "symbolMention":
        decl = None
        if sym:
            if _zpts is not None:
                sl = _zpts.find_symbol_lines(text, sym)
                decl = (sl["declaration"] or sl["all"] or [None])[0]
            elif re.search(r"\b" + re.escape(sym) + r"\s*\(", text):
                decl = next((i for i, ln in enumerate(lines, 1)
                             if re.search(r"\b" + re.escape(sym) + r"\s*\(", ln)), None)
        out["symbolLiveLine"] = decl
        if claim.get("polarity") == "absent":
            v = "LIVE_OK" if decl is None else "REFUTED"
            r = "symbol absent as claimed" if decl is None else "symbol present — absence claim wrong"
        else:
            v = "LIVE_OK" if decl is not None else "REFUTED"
            r = "symbol found live" if decl is not None else "symbol absent in live file"
        out.update(verdict=v, liveLine=decl, reason=r)
        return out

    lo = max(1, claim["start"] - window)
    hi = min(n_lines, claim["end"] + window)
    window_text = "\n".join(lines[lo - 1:hi])

    found_in, found_else = [], []
    for tok in tokens["strong"]:
        if tok in window_text:
            found_in.append(tok)
        elif tok in text:
            found_else.append(tok)
    for tok in tokens["weak"]:
        if tok in window_text:
            found_in.append(tok)
    out["tokensInWindow"] = found_in
    out["tokensElsewhere"] = found_else

    sym_decl = None
    if sym and _zpts is not None:
        sl = _zpts.find_symbol_lines(text, sym)
        sym_decl = (sl["declaration"] or sl["all"] or [None])[0]
        out["symbolLiveLine"] = sym_decl

    def _first_token_line(toks: list[str], lo_: int, hi_: int) -> int | None:
        best = None
        for i in range(lo_ - 1, hi_):
            for tok in toks:
                if tok in lines[i]:
                    best = i + 1 if best is None else min(best, i + 1)
        return best

    inh = " (evidence inherited from same-file claim)" if tokens.get("inherited") else ""
    if found_in:
        tl = _first_token_line(found_in, claim["start"], hi)
        out.update(verdict="LIVE_OK",
                   liveLine=sym_decl if tl is None else tl,
                   drift=None if sym_decl is None else sym_decl - claim["start"],
                   reason=f"{len(found_in)} cited identifier(s) present at/around cited lines" + inh)
        return out
    if found_else or sym_decl is not None:
        cands = []
        for tok in (found_else or ([sym] if sym else [])):
            for i, ln in enumerate(lines, 1):
                if tok in ln:
                    cands.append(i)
                    break
        first = min(cands) if cands else sym_decl
        out.update(verdict="REANCHORED", liveLine=first,
                   drift=None if first is None else first - claim["start"],
                   reason="content matches but cited lines drifted" + inh)
        return out
    if not tokens["strong"]:
        if claim["end"] <= n_lines:
            out.update(verdict="LIVE_OK", confidence="weak",
                       reason="range in bounds; no identifier evidence extracted")
        else:
            out.update(verdict="REFUTED",
                       reason=f"range {claim['start']}-{claim['end']} exceeds file length {n_lines}")
        return out
    out.update(verdict="REFUTED",
               reason="cited identifiers absent in live file")
    return out


# ---------------------------------------------------------------------------
# Cloud claims vs real evidence
# ---------------------------------------------------------------------------
CLOUD_CLAIM_RES = [
    re.compile(r"테스트[^.\n]*통과"), re.compile(r"빌드[^.\n]*성공"),
    re.compile(r"RED\s*→\s*GREEN", re.I), re.compile(r"GREEN[^.\n]*통과"),
    re.compile(r"curl[^\n]*\b200\b"), re.compile(r"exit\s*0"),
    re.compile(r"통과했"), re.compile(r"검사를 통과"),
    re.compile(r"해시[^.\n]*일치"), re.compile(r"바이트[^.\n]*일치"),
    re.compile(r"diff\s+-rq"), re.compile(r"검증[^.\n]*완료"),
    re.compile(r"실행[^.\n]*성공"), re.compile(r"재현했"),
]
NOTRUN_RE = re.compile(r"NOT_RUN|수행하지 않았|하지 않았습니다|실행하지 않았")


def detect_cloud_claims(text: str) -> dict:
    claims, notruns = [], []
    for i, line in enumerate(text.splitlines(), 1):
        s = line.strip()
        if NOTRUN_RE.search(s):
            notruns.append({"line": i, "text": s[:160]})
            continue
        if any(r.search(s) for r in CLOUD_CLAIM_RES):
            claims.append({"line": i, "text": s[:160]})
    return {"cloudClaimCount": len(claims), "notRunCount": len(notruns),
            "cloudClaims": claims[:20], "notRun": notruns[:20]}


# ---------------------------------------------------------------------------
# Sandbox links → local bundle
# ---------------------------------------------------------------------------
SANDBOX_RE = re.compile(r"sandbox:/mnt/data/([^\s)\]>\"'`]+)")


def sha12_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()[:12]


def extract_sandbox_links(text: str) -> list[str]:
    seen, out = set(), []
    for m in SANDBOX_RE.finditer(text):
        p = m.group(1).rstrip(".,;:")
        if p not in seen:
            seen.add(p)
            out.append(p)
    return out


def _zip_members(zf: zipfile.ZipFile) -> dict[str, str]:
    return {Path(n).name: n for n in zf.namelist() if not n.endswith("/")}


GENERIC_NAMES = {"report.md", "readme.md", "validation.json", "manifest.json",
                 "codex_start.txt", "evidence.json", "package_check.json"}


def map_sandbox_links(links: list[str], search_dirs: list[Path],
                      bundle: Path | None) -> list[dict]:
    zips = []
    if bundle and bundle.is_file():
        zips.append(bundle)
    for d in search_dirs:
        if d.is_dir():
            for z in sorted(d.glob("*.zip")):
                if z not in zips:
                    zips.append(z)
    results = []
    for link in links:
        name = Path(link).name
        parent = Path(link).parent.name if "/" in link else ""
        rec = {"link": f"sandbox:/mnt/data/{link}", "name": name,
               "status": "MISSING"}
        member_hit = None
        guess_hit = None
        for z in zips:
            try:
                with zipfile.ZipFile(z) as zf:
                    names = [n for n in zf.namelist() if not n.endswith("/")]
                    # strongest: member path contains the sandbox parent dir
                    tail = f"{parent}/{name}" if parent else name
                    exact = [n for n in names if n.endswith("/" + tail) or n == tail]
                    pick = exact[0] if exact else None
                    if pick is None and name in {Path(n).name for n in names}:
                        cand = [n for n in names if Path(n).name == name][0]
                        guess_hit = (z, cand)
                    if pick is not None:
                        member_hit = (z, pick)
                        break
            except (OSError, zipfile.BadZipFile):
                continue
        if member_hit is not None:
            z, member = member_hit
            with zipfile.ZipFile(z) as zf:
                data = zf.read(member)
            rec.update(status="FOUND", zipMember=f"{z.name}!{member}",
                       size=len(data), sha12=sha12_bytes(data))
        else:
            for d in search_dirs:
                loose = d / name
                if loose.is_file():
                    data = loose.read_bytes()
                    rec.update(status="FOUND", localPath=str(loose),
                               size=len(data), sha12=sha12_bytes(data))
                    if name.lower() in GENERIC_NAMES and parent:
                        rec["ambiguous"] = True
                        rec["note"] = ("generic basename — loose file may not be "
                                       f"the sandbox {parent} copy")
                    break
            if rec["status"] == "MISSING" and guess_hit is not None:
                z, member = guess_hit
                with zipfile.ZipFile(z) as zf:
                    data = zf.read(member)
                rec.update(status="FOUND", zipMember=f"{z.name}!{member}",
                           size=len(data), sha12=sha12_bytes(data),
                           ambiguous=True,
                           note="basename-only match inside another bundle")
        results.append(rec)
    return results


# ---------------------------------------------------------------------------
# Snapshot identity card
# ---------------------------------------------------------------------------
def _git(root: Path, *args: str) -> str | None:
    for exe in GIT_CANDIDATES:
        try:
            p = subprocess.run([exe, "-C", str(root), *args],
                               capture_output=True, timeout=60)
            if p.returncode == 0:
                return p.stdout.decode("utf-8", "replace")
        except (OSError, subprocess.TimeoutExpired):
            continue
    return None


def snapshot_card(text: str, root: Path, src_zip: Path | None) -> dict:
    snaps = []
    for m in SNAPSHOT_NAME_RE.finditer(text):
        snaps.append({"raw": m.group(0), "profile": m.group(1),
                      "date": m.group(2), "time": m.group(3),
                      "sha": m.group(4), "dirty": bool(m.group(5)),
                      "snapshotKst": f"{m.group(2)} {m.group(3)[:2]}:{m.group(3)[2:]}"})
    for m in SNAPSHOT_TRUNC_RE.finditer(text):
        snaps.append({"raw": m.group(0), "profile": m.group(1),
                      "dateTime": m.group(2), "sha": m.group(3),
                      "dirty": None, "truncated": True})
    uniq = {s["raw"]: s for s in snaps}
    head = (_git(root, "rev-parse", "HEAD") or "").strip()
    dirty_out = _git(root, "status", "--porcelain") or ""
    dirty_n = len([ln for ln in dirty_out.splitlines() if ln.strip()])
    card = {"snapshots": list(uniq.values()), "liveHead": head or None,
            "liveDirtyFiles": dirty_n, "headMatch": None,
            "sourceZip": None, "lineTrust": "low"}
    for s in uniq.values():
        if head and s.get("sha") and head.startswith(s["sha"]):
            card["headMatch"] = True
    if src_zip is not None and src_zip.is_file():
        same, changed, missing, checked = 0, 0, 0, 0
        diffs = []
        try:
            with zipfile.ZipFile(src_zip) as zf:
                for n in zf.namelist():
                    if n.endswith("/") or Path(n).name in ("", "."):
                        continue
                    rel = n
                    if not (root / rel).is_file() and "/" in n:
                        rel = n.split("/", 1)[1]
                    live = root / rel
                    if not live.is_file():
                        missing += 1
                        continue
                    checked += 1
                    if checked > 1500:
                        break
                    zb = zf.read(n)
                    if sha12_bytes(zb) == sha12_bytes(live.read_bytes()):
                        same += 1
                    else:
                        changed += 1
                        if len(diffs) < 30:
                            diffs.append(rel)
        except (OSError, zipfile.BadZipFile) as e:
            card["sourceZip"] = {"path": str(src_zip), "error": str(e)}
        else:
            card["sourceZip"] = {"path": str(src_zip), "membersChecked": checked,
                                 "same": same, "changed": changed,
                                 "liveMissing": missing, "changedSample": diffs}
            card["lineTrust"] = "medium" if changed else "high"
    else:
        card["snapshotSourceAbsent"] = True
    return card


# ---------------------------------------------------------------------------
# Scope / lease / staleness
# ---------------------------------------------------------------------------
BRIEF_FILE_RE = re.compile(r"(\*?[\w.-]+\.(?:java|md|kts|yml|yaml|js|ts|py|txt|json))")


def parse_brief_scope(text: str) -> dict:
    allowed, forbidden = set(), set()
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if ("수정 허용" in line or "제품 수정 파일" in line
                or "허용 파일" in line or "수정 범위" in line):
            for j in range(i, min(i + 3, len(lines))):
                if j != i and ("변경 금지" in lines[j] or "수정 금지" in lines[j]):
                    break
                allowed.update(BRIEF_FILE_RE.findall(lines[j]))
        if "변경 금지" in line or "수정 금지" in line:
            for j in range(i, min(i + 4, len(lines))):
                if j != i and ("수정 허용" in lines[j] or "제품 수정 파일" in lines[j]):
                    break
                forbidden.update(BRIEF_FILE_RE.findall(lines[j]))
    return {"allowed": sorted(allowed), "forbidden": sorted(forbidden)}


ANSWER_TARGET_ROW_RE = re.compile(r"^\s*\|\s*(?:신규\s*)?`?([^|`]+?)`?\s*\|")
NEW_FILE_RE = re.compile(r"(?:신규|새 파일|새로운)\s*`?([\w.-]+\.(?:java|kts|py|js|ts))")


def extract_answer_targets(seg_text: str) -> list[str]:
    targets = []
    lines = seg_text.splitlines()
    in_scope_table = False
    for line in lines:
        if re.match(r"^\s*\|", line) and re.search(r"제품 파일|수정 범위|맡길 수정|파일.*수정", line):
            in_scope_table = True
            continue
        if in_scope_table:
            if re.match(r"^\s*\|[\s:-]+\|", line):
                continue
            m = ANSWER_TARGET_ROW_RE.match(line)
            if m:
                targets.extend(BRIEF_FILE_RE.findall(m.group(1)))
                targets.extend(NEW_FILE_RE.findall(line))
                continue
            in_scope_table = False
        targets.extend(NEW_FILE_RE.findall(line))
    for m in re.finditer(r"새\s+([\w.-]+\.(?:java|kts|py|js|ts))", seg_text):
        targets.append(m.group(1))
    return sorted({t for t in targets})


def scope_widen(answer_targets: list[str], allowed: list[str],
                forbidden: list[str]) -> list[dict]:
    def covered(t: str) -> bool:
        for a in allowed:
            if a == t or (a.startswith("*") and t.endswith(a[1:])) \
                    or Path(a).name == t:
                return True
        return False
    out = []
    for t in answer_targets:
        if not covered(t):
            out.append({"file": t,
                        "inBriefForbidden": t in forbidden or Path(t).name in forbidden})
    return out


def leased_by_other(root: Path, targets: list[str], self_topic: str | None) -> list[dict]:
    lock_root = root / "__patch_drop__" / "source-edit-locks"
    out = []
    if not lock_root.is_dir():
        return out
    leases = []
    for lj in lock_root.glob("*/lease.json"):
        try:
            data = json.loads(lj.read_text(encoding="utf-8", errors="replace"))
        except (OSError, ValueError):
            continue
        topic = data.get("topic") or lj.parent.name.replace(".lock", "")
        if topic == self_topic:
            continue
        status = str(data.get("status") or "active").lower()
        paths = {str(p).lower().replace("\\", "/")
                 for p in data.get("targetPaths") or []}
        leases.append((topic, status, paths))
    for t in targets:
        live = find_live_file(root, t)
        rel = str(live.relative_to(root)).replace("\\", "/").lower() if live else t.lower()
        name = Path(t).name.lower()
        for topic, status, paths in leases:
            if status not in ("active", "expired"):
                continue
            if rel in paths or any(p.endswith("/" + name) or p == name for p in paths):
                out.append({"file": t, "leaseTopic": topic, "leaseStatus": status})
                break
    return out


def stale_vs_brief(card: dict, brief_text: str | None, brief_mtime: float | None) -> dict:
    snap_kst = None
    for s in card.get("snapshots", []):
        if s.get("snapshotKst"):
            snap_kst = s["snapshotKst"]
    brief_kst = None
    if brief_text:
        m = re.search(r"(20\d{2}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})\s*KST", brief_text)
        if m:
            brief_kst = f"{m.group(1).replace('-', '')} {m.group(2)}"
    if brief_kst is None and brief_mtime:
        brief_kst = datetime.fromtimestamp(brief_mtime, KST).strftime("%Y%m%d %H:%M")
    stale = None
    if snap_kst and brief_kst:
        stale = snap_kst < brief_kst
    return {"snapshotKst": snap_kst, "briefKst": brief_kst,
            "staleVsBrief": stale}


# ---------------------------------------------------------------------------
# AGENTS.md budget estimate
# ---------------------------------------------------------------------------
def agents_md_budget(seg_text: str, root: Path, limit: int = 32768) -> dict | None:
    if not re.search(r"agents\.md", seg_text, re.I):
        return None
    current = None
    for cand in ("AGENTS.md", "agents.md"):
        p = root / cand
        if p.is_file():
            current = p.stat().st_size
            break
    add_bytes = 0
    lines = seg_text.splitlines()
    # nearest fenced block AFTER each agents.md mention (within 40 lines)
    for i, ln in enumerate(lines):
        if not re.search(r"agents\.md", ln, re.I):
            continue
        for j in range(i + 1, min(i + 40, len(lines))):
            if FENCE_RE.match(lines[j]):
                block = []
                for k in range(j + 1, len(lines)):
                    if FENCE_RE.match(lines[k]):
                        break
                    block.append(lines[k])
                add_bytes = max(add_bytes,
                                len("\n".join(block).encode("utf-8")))
                break
    return {"currentBytes": current, "proposedAddBytes": add_bytes,
            "projectedBytes": (current or 0) + add_bytes if current is not None else None,
            "limitBytes": limit,
            "limitSource": "project_doc_max_bytes 32KiB default — developers.openai.com/codex/guides/agents-md (verified 2026-10-03)",
            "combinedLimitNote": "limit is combined across global+project AGENTS.md; headroom shrinks if a global file exists",
            "fits": None if current is None else (current + add_bytes) <= limit}


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
VERDICT_KO = {"adopt": "채택", "adjust": "조정", "discard": "폐기",
              "user": "사용자결정", "reference": "참고"}


def segment_verdict(claims: list[dict], widen: list[dict],
                    leased: list[dict], stale: bool | None,
                    cloud: dict, seg_body: str = "") -> tuple[str, str]:
    if any(w.get("inBriefForbidden") for w in widen) or leased:
        return "user", "실행 중 지시서와 충돌(금지 파일/lease) — 사용자 결정 필요"
    if re.search(r"agents\.md", seg_body, re.I) and re.search(
            r"추가|두 줄|addendum|넣도|본문에 연결", seg_body):
        return "user", "공통 규칙(AGENTS.md) 변경 제안 — 사용자 결정 필요"
    counts = {}
    for c in claims:
        counts[c["verdict"]] = counts.get(c["verdict"], 0) + 1
    if counts.get("REFUTED", 0) > max(1, counts.get("LIVE_OK", 0)):
        return "discard", f"REFUTED {counts.get('REFUTED', 0)}건 — 스냅샷과 라이브 불일치"
    if widen or stale:
        return "adjust", "범위 확장 또는 지시서보다 오래된 스냅샷"
    if counts.get("REANCHORED") or counts.get("UNVERIFIABLE"):
        return "adjust", "줄 표류 또는 ZIP 미포함 파일 주장 포함"
    if counts.get("LIVE_OK"):
        return "adopt", "라이브 재확인 통과"
    if not claims:
        return "user", "코드 주장 없음(문서/설계 제안) — 사용자 판단"
    return "reference", "검증 가능한 주장 없음"


HANDOFF = {"adopt": "Codex 제품 소스", "adjust": "Codex 제품 소스(재앵커 반영)",
           "discard": "버림", "user": "사용자", "reference": "참고"}


def build_report(answer_path: Path, segs: list[dict], claims_by_seg: dict,
                 card: dict, cloud: dict, links: list[dict],
                 widen: list[dict], leased: list[dict], stale: dict,
                 budget: dict | None, brief_name: str | None) -> dict:
    seg_cards = []
    for s in segs:
        claims = claims_by_seg.get(s["id"], [])
        verdict, reason = segment_verdict(
            claims, widen if s["id"] == "A" else [],
            leased if s["id"] == "A" else [],
            stale.get("staleVsBrief") if s["id"] == "A" else None, cloud,
            seg_body=s.get("body", ""))
        seg_cards.append({**{k: v for k, v in s.items() if k != "body"},
                          "verdict": verdict, "verdictKo": VERDICT_KO[verdict],
                          "reason": reason, "handoff": HANDOFF[verdict],
                          "claimCount": len(claims)})
    return {"schemaVersion": SCHEMA, "answer": str(answer_path),
            "answerSha12": sha12_bytes(answer_path.read_bytes()),
            "generatedKst": datetime.now(KST).strftime("%Y-%m-%d %H:%M"),
            "brief": brief_name, "segments": seg_cards, "snapshotCard": card,
            "cloudEvidence": cloud, "sandboxLinks": links,
            "scopeWiden": widen, "leasedByOther": leased,
            "staleVsBrief": stale, "agentsMdBudget": budget,
            "claims": claims_by_seg}


def _md_table(report: dict) -> str:
    rows = ["| 세그먼트 | 판정 | 이유 | 넘길 대상 |", "|---|---|---|---|"]
    for s in report["segments"]:
        rows.append(f"| {s['id']} ({s['lineStart']}~{s['lineEnd']}행) {s['title'][:40]} "
                    f"| {s['verdictKo']} | {s['reason']} | {s['handoff']} |")
    return "\n".join(rows)


def _md_claims(report: dict) -> str:
    rows = ["| seg | claim | 판정 | live |", "|---|---|---|---|"]
    for seg, claims in report["claims"].items():
        for c in claims:
            live = ""
            if c["verdict"] == "REANCHORED":
                live = f"{c.get('livePath', c['normPath'])}:{c.get('liveLine')}"
            elif c.get("livePath"):
                live = c["livePath"]
            rows.append(f"| {seg} | `{c['raw'][:60]}` ({c['normPath']}:{c['start']}-{c['end']}) "
                        f"| {c['verdict']} | {live} {c['reason'][:60]} |")
    return "\n".join(rows)


def _md_links(report: dict) -> str:
    rows = ["| sandbox 링크 | 상태 | 로컬 |", "|---|---|---|"]
    for l in report["sandboxLinks"]:
        loc = l.get("localPath") or l.get("zipMember") or "-"
        size = f" ({l['size']}B sha12 {l['sha12']})" if l.get("sha12") else ""
        amb = " ⚠ambiguous" if l.get("ambiguous") else ""
        rows.append(f"| {l['link']} | {l['status']}{amb} | {loc}{size} |")
    return "\n".join(rows)


def _codex_summary(report: dict) -> list[str]:
    """3-6 lines to paste to Codex — re-anchored file:line only."""
    out = []
    ok = [c for cl in report["claims"].values() for c in cl
          if c["verdict"] in ("LIVE_OK", "REANCHORED") and c.get("livePath")]
    for c in ok[:4]:
        anchor = f"{c['livePath']}:{c.get('liveLine') or c['start']}"
        drift = f" (snapshot {c['start']}→live {c['liveLine']})" if c["verdict"] == "REANCHORED" else ""
        out.append(f"- {anchor}{drift} — {c['reason'][:70]}")
    if report["scopeWiden"]:
        files = ", ".join(w["file"] for w in report["scopeWiden"][:4])
        out.append(f"- SCOPE_WIDEN: {files} — 실행 중 지시서 범위 밖")
    if report["leasedByOther"]:
        files = ", ".join(f"{l['file']}({l['leaseTopic']})" for l in report["leasedByOther"][:3])
        out.append(f"- LEASED_BY_OTHER: {files}")
    if report["staleVsBrief"].get("staleVsBrief"):
        out.append(f"- STALE_VS_BRIEF: snapshot {report['staleVsBrief']['snapshotKst']} < brief {report['staleVsBrief']['briefKst']}")
    out.append("- 클라우드의 테스트/빌드 통과 주장은 CLOUD_CLAIM — PASS 증거 아님")
    return out[:6]


def to_markdown(report: dict) -> str:
    L = []
    L.append(f"# Cloud ZIP answer intake — {Path(report['answer']).name}")
    L.append(f"generated {report['generatedKst']} KST · tool scripts/cloud_answer_intake.py · "
             f"answer sha12 {report['answerSha12']}")
    if report.get("brief"):
        L.append(f"brief: {report['brief']}")
    L.append("")
    L.append(_md_table(report))
    L.append("")
    card = report["snapshotCard"]
    L.append("## 스냅샷 정체")
    for s in card["snapshots"]:
        L.append(f"- `{s['raw']}` profile={s.get('profile')} sha={s.get('sha')} dirty={s.get('dirty')}")
    L.append(f"- live HEAD {card.get('liveHead')} · headMatch={card.get('headMatch')} "
             f"· liveDirtyFiles={card.get('liveDirtyFiles')} · lineTrust={card.get('lineTrust')}")
    if card.get("sourceZip"):
        z = card["sourceZip"]
        if "error" in z:
            L.append(f"- source zip error: {z['error']}")
        else:
            L.append(f"- source zip: same={z['same']} changed={z['changed']} "
                     f"liveMissing={z['liveMissing']} (checked {z['membersChecked']})")
    if card.get("snapshotSourceAbsent"):
        L.append("- 스냅샷 원본 ZIP 없음 → 줄번호 신뢰도 낮음 (lineTrust=low)")
    L.append("")
    L.append("## 주장 재앵커 (claim → live)")
    L.append(_md_claims(report))
    L.append("")
    L.append(f"## 실행 주장 ≠ 증거: CLOUD_CLAIM {report['cloudEvidence']['cloudClaimCount']}건, "
             f"자체 표기 NOT_RUN {report['cloudEvidence']['notRunCount']}건")
    for c in report["cloudEvidence"]["cloudClaims"][:12]:
        L.append(f"- L{c['line']}: {c['text'][:100]}")
    L.append("")
    L.append("## sandbox 링크 → 로컬 번들")
    L.append(_md_links(report))
    L.append("")
    L.append("## 범위·충돌")
    if report["scopeWiden"]:
        for w in report["scopeWiden"]:
            tag = " + brief 변경금지 명시" if w.get("inBriefForbidden") else ""
            L.append(f"- SCOPE_WIDEN: `{w['file']}`{tag}")
    else:
        L.append("- SCOPE_WIDEN 없음")
    for l in report["leasedByOther"]:
        L.append(f"- LEASED_BY_OTHER: `{l['file']}` → {l['leaseTopic']} ({l['leaseStatus']})")
    svb = report["staleVsBrief"]
    if svb.get("staleVsBrief") is not None:
        L.append(f"- STALE_VS_BRIEF: {svb['staleVsBrief']} (snapshot {svb.get('snapshotKst')} vs brief {svb.get('briefKst')})")
    b = report.get("agentsMdBudget")
    if b:
        L.append(f"- AGENTS.md budget: 현재 {b['currentBytes']}B + 제안 ~{b['proposedAddBytes']}B "
                 f"= {b['projectedBytes']}B / 한도 {b['limitBytes']}B → fits={b['fits']} "
                 f"({b['limitSource']}; {b['combinedLimitNote']})")
    L.append("")
    L.append("## Codex에 붙일 요약")
    L.extend(_codex_summary(report))
    L.append("")
    return "\n".join(L)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def cmd_split(args) -> int:
    text = Path(args.answer).read_text(encoding="utf-8", errors="replace")
    segs = split_segments(text)
    out = {"schemaVersion": SCHEMA, "command": "split",
           "answer": str(args.answer), "segments": segs}
    print(json.dumps(out, ensure_ascii=False, indent=1))
    return 0


def cmd_run(args) -> int:
    root = Path(os.path.abspath(args.root))
    answer = Path(args.answer)
    text = answer.read_text(encoding="utf-8", errors="replace")
    brief_text, brief_mtime = None, None
    if args.brief and Path(args.brief).is_file():
        brief_text = Path(args.brief).read_text(encoding="utf-8", errors="replace")
        brief_mtime = Path(args.brief).stat().st_mtime

    segs = split_segments(text)
    zip_names = None
    if args.zip and Path(args.zip).is_file():
        with zipfile.ZipFile(args.zip) as zf:
            zip_names = set(zf.namelist())

    claims_by_seg: dict[str, list[dict]] = {}
    lines = text.splitlines()
    targets: list[str] = []
    for s in segs:
        body = "\n".join(lines[s["lineStart"] - 1:s["lineEnd"]])
        s["body"] = body
        targets.extend(extract_answer_targets(body))
        seg_claims = []
        seg_lines = body.splitlines()
        tokens_by_file: dict[str, dict] = {}
        for c in extract_claims(body, s["lineStart"]):
            tokens = _evidence_tokens(seg_lines, c, s["lineStart"])
            live = find_live_file(root, c["path"])
            key = (str(live.relative_to(root)).replace("\\", "/")
                   if live else c["path"])
            if not tokens["strong"] and key in tokens_by_file:
                tokens = dict(tokens_by_file[key])
                tokens["inherited"] = True
            seg_claims.append(judge_claim(root, c, tokens, zip_names=zip_names))
            if tokens["strong"] and not tokens.get("inherited"):
                tokens_by_file[key] = tokens
        claims_by_seg[s["id"]] = seg_claims

    cloud = detect_cloud_claims(text)
    links = extract_sandbox_links(text)
    search_dirs = [Path(d) for d in args.search_dir]
    link_map = map_sandbox_links(links, search_dirs,
                                 Path(args.bundle) if args.bundle else None)
    card = snapshot_card(text, root, Path(args.zip) if args.zip else None)

    targets = sorted(set(targets))
    scope = parse_brief_scope(brief_text) if brief_text else {"allowed": [], "forbidden": []}
    widen = scope_widen(targets, scope["allowed"], scope["forbidden"]) if brief_text else []
    leased = leased_by_other(root, targets, args.self_lease)
    stale = stale_vs_brief(card, brief_text, brief_mtime)
    budget = None
    for s in segs:
        body = "\n".join(lines[s["lineStart"] - 1:s["lineEnd"]])
        budget = agents_md_budget(body, root) or budget

    report = build_report(answer, segs, claims_by_seg, card, cloud,
                          link_map, widen, leased, stale, budget,
                          Path(args.brief).name if args.brief else None)
    report["answerTargets"] = targets
    report["briefScope"] = scope

    sha8 = hashlib.sha256(answer.read_bytes()).hexdigest()[:8]
    stamp = datetime.now(KST).strftime("%Y%m%d-%H%M")
    out_dir = Path(args.out) / f"{stamp}_{sha8}"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "intake.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    (out_dir / "intake.md").write_text(to_markdown(report), encoding="utf-8")
    print(to_markdown(report))
    print(f"\nWROTE {out_dir / 'intake.json'}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    sp = sub.add_parser("split")
    sp.add_argument("answer")
    sp.add_argument("--json", action="store_true")
    sp.set_defaults(fn=cmd_split)
    rp = sub.add_parser("run")
    rp.add_argument("--answer", required=True)
    rp.add_argument("--bundle", default=None)
    rp.add_argument("--brief", default=None)
    rp.add_argument("--zip", default=None, help="original demo1_*.zip snapshot")
    rp.add_argument("--root", default=str(DEFAULT_ROOT))
    rp.add_argument("--out", default=str(DEFAULT_ROOT / "var" / "codex-assist-cloud-zip-intake"))
    rp.add_argument("--search-dir", action="append",
                    default=[str(Path(os.environ.get("USERPROFILE", ".")) / "Downloads")])
    rp.add_argument("--self-lease", default=None)
    rp.set_defaults(fn=cmd_run)
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
