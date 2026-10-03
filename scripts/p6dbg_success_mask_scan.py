#!/usr/bin/env python3
"""P6-D D3: static scan for "failure reported as success" patterns.

Reused existing scripts / newly added:
  - Reused: read-only line scan + JSON+MD output style of codex_anchor_map.py;
    SKIP_PARTS exclusion set.
  - New: six detector families (a..f), risk grading, WP-overlap tagging,
    allowlist file support, TOP20 markdown.

Scans ONLY main/java (product code; tests live under src/test and are out of
scope). Every finding: file:line, pattern id, risk H/M/L, overlapping Codex
P6 work package (or OUT_OF_WP -> next-round candidate), one-line evidence.

Patterns:
  a  result-discard: statement call to flush|save|saveAll|persist|upsert|
     write|commit|store on an object where a same-named repo method returns
     *Outcome|*Result|*Status|boolean|Boolean (return value ignored)
  b  catch (Throwable | catch (Error | catch (java.lang.Error
  c  catch block whose only executable statements are log.debug/trace
  d  HTTP-ish call (RestTemplate/WebClient/HttpClient/HttpURLConnection/
     .exchange(/.postFor/.getFor/sendAsync) inside synchronized method/block
  e  AtomicBoolean/volatile flag with set(true) but no false/clear path
  f  empty collection (emptyList/List.of()/Map.of()) written into a
     cache/catalog/map field or returned from a catch block

Exit: 0 always on success (scan result, not product verdict), 2 on bad input.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "devin-p6.success-mask-scan.v1"

METHOD_RE = re.compile(
    r"^\s*(?:public|protected|private|static|final|synchronized|abstract|native|"
    r"default|@\w+(?:\([^)]*\))?|\s)+"
    r"(?P<ret>[A-Za-z_][\w.<>\[\],?]*)\s+(?P<name>[A-Za-z_][\w]*)\s*\([^;{]*\)\s*"
    r"(?:throws\s+[^{]+)?\{?")
RESULT_TYPE_RE = re.compile(r"(Outcome|Result|Status|boolean|Boolean)\b")
DISCARD_VERBS = ("flush", "save", "saveAll", "persist", "upsert", "write",
                 "writeAll", "commit", "store", "evict", "invalidate")
DISCARD_RE = re.compile(
    r"^\s*(?:(?:this|super)\.)?[\w.\)\[\]\>]+\.(?P<verb>" +
    "|".join(DISCARD_VERBS) + r")\s*\(")
CATCH_RE = re.compile(r"catch\s*\(\s*"
                      r"(?P<type>[\w.$| ]*?"
                      r"(?:Throwable|Error|Exception|RuntimeException)[\w.$| ]*)"
                      r"\s+(?P<var>\w+)\s*\)")
THROWABLE_RE = re.compile(r"catch\s*\(\s*(?:final\s+)?[\w.$| ]*\b(?:Throwable|Error)\b")
LOG_ONLY_RE = re.compile(r"^\s*(?:log|logger|LOG)\.(?:debug|trace)\s*\(")
SYNC_METHOD_RE = re.compile(r"\bsynchronized\b")
HTTP_TOKENS = ("RestTemplate", "WebClient", "HttpClient", "HttpURLConnection",
               ".exchange(", ".postFor", ".getFor", ".sendAsync(", ".send(",
               "openConnection")
FLAG_FIELD_RE = re.compile(
    r"(?P<flag>(?:AtomicBoolean\s+)?[A-Za-z_]\w*)\s*(?:=\s*(?:new\s+AtomicBoolean\s*\(\s*true\s*\)|true))?")
ATOMIC_FIELD_RE = re.compile(
    r"\b(?:private|protected|public|static|final|\s)*\s*(AtomicBoolean|volatile\s+boolean)\s+(\w+)")
EMPTY_COLL_RE = re.compile(r"(emptyList|emptyMap|emptySet|List\.of\(\)|Map\.of\(\)|Set\.of\(\)|Collections\.empty)")
CACHE_FIELD_RE = re.compile(r"(cache|catalog|cacheMap|store|lookup|byKey)", re.I)
PERSIST_CTX_RE = re.compile(r"(save|persist|checkpoint|flush|store|ingest|write|commit)", re.I)

WP_OF_FILE = [
    (re.compile(r"TrainRagIngestService|AutolearnRagRetrainOrchestrator|VectorStoreService|IndexingScheduler|PendingMemorySoakScheduler"), "WP1"),
    (re.compile(r"UawDatasetWriter|TrainRagIngestService"), "WP2"),
    (re.compile(r"FinalizedMemoryPersistence|CancellationFence"), "WP3"),
    (re.compile(r"SettingsController|SettingsService|ChatRequestSettingsMerger|ConfigurationSetting"), "WP4"),
    (re.compile(r"ChatModelCatalogService|ChatModelCatalogController|ChatGptCatalog"), "WP5"),
    (re.compile(r"JevEvaluationRuntime|JevRuntimeConfiguration|JevGatewayClient|JevSurfacePolicy"), "WP6"),
    (re.compile(r"RouterPolicy|QueryComplexity|ModelBasedQuery|JevComplexity|PolicyBasedModelRouter|ModelRouter|LlmRouteScorer"), "WP7"),
    (re.compile(r"JevRetrievalGateHandler|DynamicRetrievalHandlerChain|UnifiedRagOrchestrator|RetrieverChainConfig|JevChoiceAdvisor|JevSearchNeedAdvisor"), "WP8/WP9"),
    (re.compile(r"ChatApiController|ChatOpenSecurityConfig|InterviewDemoFilter|AppSecurityConfig"), "T01"),
    (re.compile(r"PromptBuilder|PromptContext"), "WP9"),
]


def wp_for(path: str) -> str:
    name = Path(path).name
    for rx, wp in WP_OF_FILE:
        if rx.search(name):
            return wp
    return "OUT_OF_WP"


def read_lines(path: Path):
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\x00" in data[:4096]:
        return None
    return data.decode("utf-8-sig", errors="replace").splitlines()


def iter_java(root: Path):
    base = root / "main" / "java"
    if not base.is_dir():
        return
    for p in base.rglob("*.java"):
        if any(part in SKIP for part in p.parts):
            continue
        if p.name.endswith(("Test.java", "Tests.java", "IT.java")):
            continue
        yield p


SKIP = {".git", "build", "node_modules", "__patch_drop__", "data", ".gradle"}


def build_method_index(files, root):
    """name -> set of return types (same-named methods across repo)."""
    idx: dict[str, set[str]] = {}
    for p in files:
        lines = read_lines(p)
        if not lines:
            continue
        for line in lines:
            m = METHOD_RE.match(line)
            if m:
                idx.setdefault(m.group("name"), set()).add(m.group("ret"))
    return idx


def brace_end(lines, start, from_col=0):
    """Line index where the brace that opens at/after (start,from_col) closes."""
    depth = 0
    opened = False
    for i in range(start, len(lines)):
        seg = lines[i][from_col:] if i == start else lines[i]
        for ch in seg:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}" and opened:
                depth -= 1
                if depth <= 0:
                    return i
        from_col = 0
    return len(lines) - 1


def open_brace_col(lines, start, min_col):
    """Column of the first '{' at/after (start,min_col); None if none nearby."""
    for i in range(start, min(start + 3, len(lines))):
        j = lines[i].find("{", min_col if i == start else 0)
        if j >= 0:
            return i, j
    return None, None


def find_method_envelope(lines):
    """Yield (decl_line_idx, end_line_idx, decl_text) for method-like decls."""
    i = 0
    while i < len(lines):
        m = METHOD_RE.match(lines[i])
        if m and ("{" in lines[i] or i + 1 < len(lines) and "{" in lines[i + 1]):
            end = brace_end(lines, i)
            yield i, end, lines[i].strip()
            i = end + 1
        else:
            i += 1


def scan_file(path: Path, root: Path, method_idx, allow):
    rel = path.relative_to(root).as_posix()
    lines = read_lines(path)
    if not lines:
        return []
    wp = wp_for(rel)
    findings = []

    def add(pat, line_idx, risk, detail):
        rel_short = rel[len("main/java/"):] if rel.startswith("main/java/") else rel
        keys = (rel, rel_short, f"{rel}#{pat}", f"{rel_short}#{pat}",
                f"{rel}:{line_idx + 1}", f"{rel_short}:{line_idx + 1}")
        if any(k in allow for k in keys):
            return
        findings.append({
            "file": rel, "line": line_idx + 1, "pattern": pat,
            "risk": risk, "wp": wp,
            "evidence": lines[line_idx].strip()[:160], "detail": detail,
        })

    # --- (e) one-way flags -------------------------------------------------
    flag_names = []
    for i, line in enumerate(lines):
        m = ATOMIC_FIELD_RE.search(line)
        if m:
            flag_names.append((m.group(2), i))
    for name, decl_i in flag_names:
        text = "\n".join(lines)
        true_n = len(re.findall(rf"\b{name}\.(?:set|lazySet|setRelease|compareAndSet)\s*\(\s*(?:true|[^,)]*,\s*true)", text))
        true_n += len(re.findall(rf"(?<![=!<>])\b{name}\s*=\s*true\b", text))
        false_n = len(re.findall(rf"\b{name}\.(?:set|lazySet|setRelease|compareAndSet)\s*\(\s*(?:false|[^,)]*,\s*false)", text))
        false_n += len(re.findall(rf"(?<![=!<>])\b{name}\s*=\s*false\b", text))
        if true_n >= 1 and false_n == 0:
            add("e-one-way-flag", decl_i, "H",
                f"{name}: set(true) x{true_n}, false/clear x0")

    # --- (b/c/f) catch blocks ----------------------------------------------
    i = 0
    while i < len(lines):
        cm = CATCH_RE.search(lines[i])
        if not cm:
            i += 1
            continue
        ctype = cm.group("type")
        blk_start = i
        ob_i, ob_j = open_brace_col(lines, i, cm.end())
        blk_end = brace_end(lines, ob_i if ob_i is not None else i,
                            ob_j if ob_i == i else 0) if ob_i is not None else i
        body = lines[blk_start: blk_end + 1]
        body_nonblank = [b.strip() for b in body
                         if b.strip() and not b.strip().startswith(("//", "/*", "*", "catch", "}", "{"))]
        if THROWABLE_RE.search(lines[i]):
            risk = "H" if "Throwable" in ctype else "M"
            add("b-catch-error", i, risk, f"catch ({ctype.strip()})")
        body_text = "\n".join(body)
        body_text = re.sub(r"^\s*\}?\s*catch\s*\([^)]*\)\s*\{?", "", body_text, count=1)
        body_text = re.sub(r"\}\s*$", "", body_text.strip())
        body_text = re.sub(r"//[^\n]*", "", body_text)
        stmts = [s.strip() for s in body_text.split(";") if s.strip()]
        swallow_re = re.compile(
            r"^return\s*(null|true|false|Optional\.empty\(\)|"
            r"Collections\.empty\w*\(\)|List\.of\(\)|Set\.of\(\)|Map\.of\(\))?\s*$")
        only_debug = bool(stmts) and all(
            LOG_ONLY_RE.match(s) or swallow_re.match(s) for s in stmts)
        if only_debug and any(LOG_ONLY_RE.match(s) for s in stmts):
            ctx = "persist-adjacent" if PERSIST_CTX_RE.search(
                path.name + " " + " ".join(lines[max(0, i - 12):i])) else "plain"
            add("c-log-only-catch", i, "H" if ctx == "persist-adjacent" else "M",
                f"catch swallows with debug/trace only ({ctx})")
        if EMPTY_COLL_RE.search("\n".join(body)) and re.search(r"(return|\.put|=)", "\n".join(body)):
            add("f-empty-cache-on-failure", i, "H" if wp != "OUT_OF_WP" else "M",
                "empty collection cached/returned inside catch")
        i = blk_end + 1

    # --- (a) discarded results ----------------------------------------------
    for i, line in enumerate(lines):
        dm = DISCARD_RE.match(line)
        if not dm:
            continue
        verb = dm.group("verb")
        rets = method_idx.get(verb, set())
        if any(RESULT_TYPE_RE.search(r or "") for r in rets):
            add("a-result-discard", i, "H" if wp != "OUT_OF_WP" else "M",
                f"{verb}() result discarded; repo return types: {sorted(rets)}")

    # --- (d) http inside synchronized ---------------------------------------
    sync_regions = []
    for start, end, decl in find_method_envelope(lines):
        if "synchronized" in decl:
            sync_regions.append((start, end, decl.strip()[:80]))
    for i, line in enumerate(lines):
        if "synchronized" in line and "(" in line and "{" in line:
            end = brace_end(lines, i)
            sync_regions.append((i, end, f"synchronized block @{i+1}"))
    for s, e, label in sync_regions:
        region = lines[s:e + 1]
        for j, l in enumerate(region):
            if any(tok in l for tok in HTTP_TOKENS):
                add("d-http-in-synchronized", s + j, "H" if wp != "OUT_OF_WP" else "M",
                    f"http call inside {label}")
                break

    # --- (f) empty collection into cache field outside catch -----------------
    for i, line in enumerate(lines):
        if EMPTY_COLL_RE.search(line) and re.search(r"\.put\s*\(|=", line):
            lhs = line.split("=")[0]
            if CACHE_FIELD_RE.search(lhs):
                add("f-empty-cache-field", i, "M",
                    "empty collection written to cache-like field")

    return findings


def load_allowlist(root: Path, name: str):
    p = root / name
    if not p.is_file():
        return set()
    out = set()
    for ln in p.read_text(encoding="utf-8", errors="replace").splitlines():
        ln = ln.strip()
        if ln and not ln.startswith("#"):
            out.add(ln)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(prog="p6dbg_success_mask_scan")
    ap.add_argument("--root", default=".")
    ap.add_argument("--out-json", default="data/agent-handoff/devin-p6/success-mask-scan.json")
    ap.add_argument("--out-md", default="data/agent-handoff/devin-p6/TOP20.md")
    ap.add_argument("--allowlist", default="data/agent-handoff/devin-p6/success-mask-allowlist.txt")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    if not (root / "main" / "java").is_dir():
        print(json.dumps({"error": "main/java missing", "root": str(root)}))
        return 2
    files = list(iter_java(root) or [])
    method_idx = build_method_index(files, root)
    allow = load_allowlist(root, args.allowlist)

    findings = []
    for p in files:
        findings.extend(scan_file(p, root, method_idx, allow))

    by_pat = {}
    by_risk = {"H": 0, "M": 0, "L": 0}
    for f in findings:
        by_pat[f["pattern"]] = by_pat.get(f["pattern"], 0) + 1
        by_risk[f["risk"]] = by_risk.get(f["risk"], 0) + 1
    findings.sort(key=lambda f: ({"H": 0, "M": 1, "L": 2}[f["risk"]],
                                 0 if f["wp"] != "OUT_OF_WP" else 1,
                                 f["file"], f["line"]))
    next_round = [f for f in findings if f["risk"] == "H" and f["wp"] == "OUT_OF_WP"]

    report = {
        "schema": SCHEMA,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "root": str(root),
        "files_scanned": len(files),
        "finding_count": len(findings),
        "by_pattern": by_pat,
        "by_risk": by_risk,
        "next_round_candidates": len(next_round),
        "allowlist_size": len(allow),
        "findings": findings,
        "external_calls": 0,
    }
    out_json = root / args.out_json
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    md = ["# Success-mask scan TOP20 (D3)", "",
          f"Generated: {report['generated_at_utc']} | files {len(files)} | "
          f"findings {len(findings)} | {json.dumps(by_pat)}", "",
          "| # | risk | pattern | wp | file:line | evidence |",
          "|---|---|---|---|---|---|"]
    for n, f in enumerate(findings[:20], 1):
        md.append(f"| {n} | {f['risk']} | {f['pattern']} | {f['wp']} | "
                  f"`{f['file']}:{f['line']}` | {f['evidence'][:90]} |")
    md += ["", "## Next-round candidates (H, OUT_OF_WP, one per file)", ""]
    seen_files = set()
    for f in next_round:
        if f["file"] in seen_files:
            continue
        seen_files.add(f["file"])
        md.append(f"- `{f['file']}:{f['line']}` {f['pattern']} — {f['detail']}")
        if len(seen_files) >= 10:
            break
    (root / args.out_md).write_text("\n".join(md) + "\n", encoding="utf-8")

    print(json.dumps({"files": len(files), "findings": len(findings),
                      "by_pattern": by_pat, "by_risk": by_risk,
                      "next_round_candidates": len(next_round),
                      "json": str(out_json), "md": str(root / args.out_md)},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
