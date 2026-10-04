#!/usr/bin/env python3
"""codex_attachment_coverage — goal-attachment coverage ledger for Codex sessions.

Codex goal-mode attachments arrive only as `## <name>: <path>` lines in the
first user message (the files live under Downloads or
%USERPROFILE%\\.codex\\attachments). Nothing forces the agent to open them.
This tool makes coverage measurable:

    manifest  --rollout <jsonl> | --goal-text <file> | --paths <p>...
              [--out DIR] [--json]
        Parse the attachment list, probe each file (exists/size/sha12/kind;
        zip entry list, md headings, json top-level keys — structure only,
        never content), and write manifest.json + an empty
        ATTACHMENT_LEDGER.md verdict table.
    coverage  --rollout <jsonl> [--manifest manifest.json] [--json]
        Scan the rollout's tool calls for the first line each attachment is
        referenced. Status OPENED / OPENED_PARTIAL / NOT_OPENED, split at the
        first task_complete event (goal completion boundary).
        Exit 3 when any attachment was never opened.
    gate      --ledger <ATTACHMENT_LEDGER.md|.json> [--manifest manifest.json]
        Every manifest attachment needs a verdict; OUT_OF_SCOPE / CONFLICT /
        UNREADABLE need a one-line reason. Exit 2 + missing names on gaps.

Privacy: file contents and secret values are never emitted — names, sizes,
hashes and structure only. Rollouts are read line-by-line (shared read, no
retry); no raw message text is copied into output.

Schema: awx.codex-attachment-coverage.v1
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import io
import json
import re
import sys
import zipfile
from pathlib import Path

SCHEMA = "awx.codex-attachment-coverage.v1"
SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
try:  # reuse the F5 secret-masker; degrade to a stub if the layout moves
    from quarantine_codex_rollout_mine import redact
except Exception:  # pragma: no cover - fallback only
    def redact(text):
        return "" if text is None else str(text)

DEFAULT_OUT = Path("var") / "codex-assist-attachment-coverage"
MAX_JSON_PROBE_BYTES = 8 * 1024 * 1024
MAX_MD_PROBE_BYTES = 1024 * 1024

# `## <name>: <path>` — name may contain spaces/parens/Korean; the path must
# look path-like (drive letter or a separator) so plain `## Heading` lines and
# `## Word: prose` do not false-match.
RE_ATT_LINE = re.compile(r"^##\s+(?P<name>.+?):\s+(?P<path>\S.*?)\s*$")
RE_PATHISH = re.compile(r"(?:[A-Za-z]:[/\\])|[/\\]|~[/\\]")

KIND_BY_EXT = {
    ".md": "md", ".markdown": "md",
    ".txt": "txt", ".log": "txt",
    ".json": "json", ".jsonl": "json",
    ".zip": "zip",
    ".png": "image", ".jpg": "image", ".jpeg": "image",
    ".gif": "image", ".webp": "image", ".bmp": "image",
}

PARTIAL_MARKERS = re.compile(
    r"Select-Object[^\n\"']{0,60}-First|-First\s+\d+|-TotalCount|-Skip\s+\d+|"
    r"\bhead\s+-\w*\s*\d+|Get-Content[^\n\"']{0,80}-TotalCount|"
    r"Select-String[^\n\"']{0,60}-List|앞\s*\d+\s*줄|read_lines|"
    r"itertools\.islice|splitlines\(\)\s*\[\s*:\s*\d+\s*\]",
    re.I)
TOOL_CALL_TYPES = ("function_call", "custom_tool_call", "local_shell_call")


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def kind_of(path: str) -> str:
    return KIND_BY_EXT.get(Path(path).suffix.lower(), "other")


def parse_attachment_lines(text: str) -> list[tuple[str, str]]:
    """Extract (name, path) pairs from `## name: path` lines, order preserved."""
    out: list[tuple[str, str]] = []
    seen: set[str] = set()
    for line in text.splitlines():
        m = RE_ATT_LINE.match(line.strip())
        if not m:
            continue
        name, path = m.group("name").strip(), m.group("path").strip()
        if not name or not RE_PATHISH.search(path):
            continue
        if name in seen:
            continue
        seen.add(name)
        out.append((name, path))
    return out


def iter_jsonl(path: Path):
    """Yield (line_no, parsed_dict|None, raw_text). Shared read, no retry."""
    with io.open(str(path), "r", encoding="utf-8", errors="replace") as fh:
        for no, raw in enumerate(fh, 1):
            rec = None
            if '"type"' in raw:
                try:
                    rec = json.loads(raw)
                except ValueError:
                    rec = None
            yield no, rec, raw


def rollout_attachments(path: Path) -> tuple[list[tuple[str, str]], int | None]:
    """Attachment lines from the first user message that carries any, plus the
    first task_complete line number (goal-completion boundary)."""
    atts: list[tuple[str, str]] = []
    complete_line = None
    for no, rec, _ in iter_jsonl(path):
        if rec is None:
            continue
        rtype = rec.get("type")
        payload = rec.get("payload") or {}
        if not isinstance(payload, dict):
            continue
        if rtype == "event_msg" and payload.get("type") == "task_complete" \
                and complete_line is None:
            complete_line = no
            continue
        if atts or rtype != "response_item" or payload.get("type") != "message" \
                or payload.get("role") != "user":
            continue
        text = "\n".join(
            str(c.get("text", "")) for c in payload.get("content") or []
            if isinstance(c, dict))
        atts = parse_attachment_lines(text)
    return atts, complete_line


def sha12(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()[:12]


def probe_md(path: Path) -> dict:
    data = path.read_bytes()[:MAX_MD_PROBE_BYTES]
    heads = []
    for line in data.decode("utf-8", errors="replace").splitlines():
        m = re.match(r"^#{1,6}\s+(.+?)\s*$", line)
        if m:
            heads.append(redact(m.group(1))[:120])
        if len(heads) >= 10:
            break
    return {"headings": heads}


def probe_json(path: Path) -> dict:
    size = path.stat().st_size
    if size > MAX_JSON_PROBE_BYTES:
        return {"topKeys": None, "topKeysNote": "file-too-large"}
    try:
        obj = json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except ValueError:
        return {"topKeys": None, "topKeysNote": "not-json"}
    if isinstance(obj, dict):
        return {"topKeys": [redact(str(k))[:80] for k in list(obj.keys())[:30]]}
    return {"topKeys": None, "topKeysNote": type(obj).__name__}


def probe_zip(path: Path) -> dict:
    try:
        with zipfile.ZipFile(path) as zf:
            infos = zf.infolist()
            names = [i.filename for i in infos[:30]]
            return {"entryCount": len(infos),
                    "topEntryNames": [redact(n)[:160] for n in names],
                    "uncompressedBytes": sum(i.file_size for i in infos)}
    except (zipfile.BadZipFile, OSError) as exc:
        return {"entryCount": None, "error": type(exc).__name__}


def probe_attachment(name: str, raw_path: str) -> dict:
    entry = {"name": name, "path": raw_path, "kind": kind_of(raw_path)}
    p = Path(raw_path)
    try:
        if not p.is_file():
            entry["exists"] = False
            return entry
    except OSError:
        entry["exists"] = False
        entry["error"] = "stat-failed"
        return entry
    entry["exists"] = True
    try:
        entry["size"] = p.stat().st_size
    except OSError:
        entry["size"] = None
    try:
        entry["sha12"] = sha12(p)
    except OSError:
        entry["sha12"] = None
        entry.setdefault("errors", []).append("sha-unreadable")
    try:
        if entry["kind"] == "md":
            entry["detail"] = probe_md(p)
        elif entry["kind"] == "json":
            entry["detail"] = probe_json(p)
        elif entry["kind"] == "zip":
            entry["detail"] = probe_zip(p)
    except OSError as exc:
        entry.setdefault("errors", []).append("probe-%s" % type(exc).__name__)
    return entry


def render_ledger_md(entries: list[dict]) -> str:
    lines = [
        "# ATTACHMENT_LEDGER",
        "",
        "Fill `verdict` (+ `reason` for OUT_OF_SCOPE / CONFLICT / UNREADABLE)",
        "for every row, then run `codex_attachment_coverage.py gate --ledger",
        "ATTACHMENT_LEDGER.md`. Verdicts: APPLIED / REFERENCE /",
        "OUT_OF_SCOPE->NEXT / CONFLICT->HOLD / DUPLICATE / UNREADABLE.",
        "",
        "| # | name | kind | size | sha12 | verdict | reason |",
        "|---|------|------|------|-------|---------|--------|",
    ]
    for i, e in enumerate(entries, 1):
        name = e["name"].replace("|", "\\|")
        lines.append("| %d | %s | %s | %s | %s |  |  |" % (
            i, name, e.get("kind", "?"), e.get("size"), e.get("sha12") or "-"))
    lines.append("")
    return "\n".join(lines)


def cmd_manifest(args) -> int:
    pairs: list[tuple[str, str]] = []
    source = {}
    if args.rollout:
        pairs, _ = rollout_attachments(Path(args.rollout))
        source = {"rollout": str(args.rollout)}
    elif args.goal_text:
        pairs = parse_attachment_lines(
            Path(args.goal_text).read_text(encoding="utf-8", errors="replace"))
        source = {"goalText": str(args.goal_text)}
    elif args.paths:
        pairs = [(Path(p).name, p) for p in args.paths]
        source = {"paths": list(args.paths)}
    else:
        print(json.dumps({"schemaVersion": SCHEMA,
                          "error": "manifest-source-required"}))
        return 2
    entries = [probe_attachment(n, p) for n, p in pairs]
    doc = {"schemaVersion": SCHEMA, "command": "manifest",
           "generatedAtUtc": utcnow(), "source": source,
           "count": len(entries), "attachments": entries}
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "manifest.json").write_text(
        json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    (out_dir / "ATTACHMENT_LEDGER.md").write_text(
        render_ledger_md(entries), encoding="utf-8")
    if args.json:
        print(json.dumps(doc, ensure_ascii=False))
    else:
        print("attachments=%d wrote %s" % (len(entries), out_dir))
    return 0


# --------------------------------------------------------------------------
# coverage
# --------------------------------------------------------------------------

def ref_patterns(entry: dict) -> list[re.Pattern]:
    """Name regex + path regex (separators interchangeable). A match must not
    sit inside a longer filename, e.g. `artifact_checks.json` inside
    `xartifact_checks.json.bak`."""
    name = entry["name"]
    pats = [re.compile(r"(?<![\w.])" + re.escape(name) + r"(?![\w.])")]
    raw = entry.get("path") or ""
    if raw:
        flex = "[/\\\\]".join(re.escape(seg) for seg in re.split(r"[/\\]+", raw))
        pats.append(re.compile(r"(?<![\w.])" + flex + r"(?![\w.])"))
    return pats


def tool_call_text(payload: dict) -> str:
    for key in ("input", "arguments"):
        v = payload.get(key)
        if isinstance(v, str):
            return v
        if v is not None:
            return json.dumps(v, ensure_ascii=False)
    return ""


def cmd_coverage(args) -> int:
    rollout = Path(args.rollout)
    if not rollout.is_file():
        print(json.dumps({"schemaVersion": SCHEMA, "error": "rollout-missing",
                          "path": str(rollout)}))
        return 2
    if args.manifest:
        doc = json.loads(Path(args.manifest).read_text(
            encoding="utf-8", errors="replace"))
        entries = [{"name": a["name"], "path": a.get("path", "")}
                   for a in doc.get("attachments", [])]
    else:
        pairs, _ = rollout_attachments(rollout)
        entries = [{"name": n, "path": p} for n, p in pairs]
    pats = {e["name"]: ref_patterns(e) for e in entries}
    opens: dict[str, list[dict]] = {e["name"]: [] for e in entries}
    complete_line = None
    for no, rec, _ in iter_jsonl(rollout):
        if rec is None:
            continue
        rtype = rec.get("type")
        payload = rec.get("payload") or {}
        if rtype == "event_msg" and payload.get("type") == "task_complete" \
                and complete_line is None:
            complete_line = no
        if rtype != "response_item" or payload.get("type") not in TOOL_CALL_TYPES:
            continue
        text = tool_call_text(payload)
        if not text:
            continue
        for name, plist in pats.items():
            if opens[name] and opens[name][-1]["partial"] is False:
                continue  # already have a full open; first evidence wins
            if any(p.search(text) for p in plist):
                opens[name].append(
                    {"line": no, "partial": bool(PARTIAL_MARKERS.search(text))})
    rows, before, after, never = [], 0, 0, 0
    for e in entries:
        lst = opens[e["name"]]
        if not lst:
            status, first_line, timing = "NOT_OPENED", None, "never"
            never += 1
        else:
            first_line = lst[0]["line"]
            timing = ("before" if complete_line and first_line < complete_line
                      else "after" if complete_line else "unknown")
            status = ("OPENED" if any(not o["partial"] for o in lst)
                      else "OPENED_PARTIAL")
            if timing == "before":
                before += 1
            elif timing == "after":
                after += 1
        rows.append({"name": e["name"], "status": status,
                     "firstOpenLine": first_line, "timing": timing})
    summary = {"total": len(entries), "openedBeforeFirstCompletion": before,
               "openedAfterFirstCompletion": after, "neverOpened": never,
               "firstTaskCompleteLine": complete_line}
    doc = {"schemaVersion": SCHEMA, "command": "coverage",
           "generatedAtUtc": utcnow(), "rollout": str(rollout),
           "summary": summary, "attachments": rows}
    if args.json:
        print(json.dumps(doc, ensure_ascii=False))
    else:
        print("| name | status | first-open line | timing |")
        print("|------|--------|-----------------|--------|")
        for r in rows:
            print("| %s | %s | %s | %s |" % (
                r["name"], r["status"], r["firstOpenLine"] or "-", r["timing"]))
        print("opened %d/%d before first completion, never %d"
              % (before, len(entries), never))
    return 3 if never else 0


# --------------------------------------------------------------------------
# gate
# --------------------------------------------------------------------------

VERDICTS = {"APPLIED", "REFERENCE", "OUT_OF_SCOPE", "CONFLICT", "DUPLICATE",
            "UNREADABLE", "NEXT", "HOLD"}
NEEDS_REASON = {"OUT_OF_SCOPE", "CONFLICT", "UNREADABLE"}


def norm_verdict(raw: str) -> str:
    v = raw.strip().upper()
    v = v.split("->")[0].split("→")[0].split("(")[0].strip()
    return v


def load_ledger(path: Path) -> dict[str, dict]:
    """name -> {verdict, reason} from a ledger .md table or .json."""
    rows: dict[str, dict] = {}
    if path.suffix.lower() == ".json":
        doc = json.loads(path.read_text(encoding="utf-8", errors="replace"))
        items = doc.get("attachments", doc if isinstance(doc, list) else [])
        for it in items:
            if isinstance(it, dict) and it.get("name"):
                rows[it["name"]] = {"verdict": it.get("verdict", ""),
                                    "reason": it.get("reason", "")}
        return rows
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line.startswith("|") or "---" in line:
            continue
        cells = [c.strip().replace("\\|", "|") for c in line.strip("|").split("|")]
        if len(cells) >= 7 and cells[0].isdigit():
            rows[cells[1]] = {"verdict": cells[5], "reason": cells[6]}
        elif len(cells) >= 3 and cells[0] and not cells[0].isdigit() \
                and cells[0].lower() not in ("#", "name"):
            rows[cells[0]] = {"verdict": cells[1],
                              "reason": cells[2] if len(cells) > 2 else ""}
    return rows


def cmd_gate(args) -> int:
    ledger = Path(args.ledger)
    if not ledger.is_file():
        print(json.dumps({"schemaVersion": SCHEMA, "error": "ledger-missing",
                          "path": str(ledger)}))
        return 2
    rows = load_ledger(ledger)
    if args.manifest:
        doc = json.loads(Path(args.manifest).read_text(
            encoding="utf-8", errors="replace"))
        required = [a["name"] for a in doc.get("attachments", [])]
    else:
        required = list(rows.keys())
    missing, bad = [], []
    for name in required:
        row = rows.get(name)
        if row is None or not row["verdict"].strip():
            missing.append(name)
            continue
        v = norm_verdict(row["verdict"])
        if v not in VERDICTS:
            bad.append("%s(unknown-verdict)" % name)
        elif v in NEEDS_REASON and not row["reason"].strip():
            bad.append("%s(reason-required)" % name)
    ok = not missing and not bad
    doc = {"schemaVersion": SCHEMA, "command": "gate",
           "generatedAtUtc": utcnow(), "ledger": str(ledger),
           "required": len(required), "filled": len(required) - len(missing),
           "missingVerdict": missing, "invalid": bad, "ok": ok}
    if args.json:
        print(json.dumps(doc, ensure_ascii=False))
    else:
        if ok:
            print("gate OK: %d/%d verdicts filled" % (len(required), len(required)))
        else:
            for n in missing:
                print("MISSING %s" % n)
            for n in bad:
                print("INVALID %s" % n)
    return 0 if ok else 2


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("manifest", help="build manifest.json + ledger table")
    g = m.add_mutually_exclusive_group()
    g.add_argument("--rollout")
    g.add_argument("--goal-text")
    g.add_argument("--paths", nargs="+")
    m.add_argument("--out", default=str(DEFAULT_OUT))
    m.add_argument("--json", action="store_true")
    c = sub.add_parser("coverage", help="scan rollout tool calls for opens")
    c.add_argument("--rollout", required=True)
    c.add_argument("--manifest")
    c.add_argument("--json", action="store_true")
    t = sub.add_parser("gate", help="verify the ledger verdicts are complete")
    t.add_argument("--ledger", required=True)
    t.add_argument("--manifest")
    t.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    return {"manifest": cmd_manifest, "coverage": cmd_coverage,
            "gate": cmd_gate}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
