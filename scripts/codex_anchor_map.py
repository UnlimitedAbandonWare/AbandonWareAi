#!/usr/bin/env python3
"""Read-only live symbol map for Codex OAuth R5.

Stdlib only. Prints file, line, and three lines of context. Does not modify
sources, call providers, or read secret files.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SCAN_ROOTS = ("main/java", "main/resources", "src/test/java")
SKIP_PARTS = {
    ".git", "build", "var", "node_modules", "__patch_drop__", "data",
    "agent-handoff", "agent-prompts", ".gradle",
}

QUERIES = [
    {
        "id": "LlmResponseTerminalException.constructor",
        "basename": "LlmResponseTerminalException.java",
        "under": "main/java",
        "regex": r"LlmResponseTerminalException\s*\(",
        "note": "Live constructor is 7-arg: reason, failureClass, partialText, metadata, status, incompleteReason, providerCode.",
    },
    {
        "id": "OpenAiResponsesChatModel.oauthFailure",
        "basename": "OpenAiResponsesChatModel.java",
        "under": "main/java",
        "regex": r"\boauthFailure\s*\(",
        "note": "Declaration and call sites.",
    },
    {
        "id": "OpenAiResponsesChatModel.newLlmResponseTerminalException",
        "basename": "OpenAiResponsesChatModel.java",
        "under": "main/java",
        "regex": r"new\s+LlmResponseTerminalException\s*\(",
        "note": "Construction sites. The R5 brief expected about four.",
    },
    {
        "id": "TimedChatModelCaller.terminal.metadata",
        "basename": "TimedChatModelCaller.java",
        "under": "main/java",
        "regex": r"terminal\.metadata\s*\(",
        "note": "Consumer that must tolerate a null tokenUsage inside metadata.",
    },
    {
        "id": "ChatResponseDto.terminal",
        "basename": "ChatResponseDto.java",
        "under": "main/java",
        "regex": r"\bterminal\s*\(|GenerationTermination\.from\s*\(",
        "note": "Factory and the record builder it calls.",
    },
    {
        "id": "ChatApiController.LlmResponseTerminalException.find",
        "basename": "ChatApiController.java",
        "under": "main/java",
        "regex": r"LlmResponseTerminalException\.find\s*\(",
        "note": "The R5 brief expected two call sites.",
    },
    {
        "id": "ChatWorkflow.LlmResponseTerminalException",
        "basename": "ChatWorkflow.java",
        "under": "main/java",
        "regex": r"LlmResponseTerminalException",
        "note": "Every mention in ChatWorkflow. Edit only the lines Codex owns.",
    },
    {
        "id": "RouterPolicy.complexityThreshold",
        "basename": "RouterPolicy.java",
        "under": "main/java",
        "regex": r"complexityThreshold",
        "note": "Declared threshold. Confirm whether any branch reads it.",
    },
    {
        "id": "MoeRoutingProps.complexityThreshold",
        "basename": "MoeRoutingProps.java",
        "under": "main/java",
        "regex": r"complexityThreshold",
        "note": "Separate threshold from RouterPolicy.",
    },
    {
        "id": "ConversateCueRoutingPolicy.select",
        "basename": "ConversateCueRoutingPolicy.java",
        "under": "main/java",
        "regex": r"\bselect\s*\(",
        "note": "select( only. selectOauth is a separate row.",
    },
    {
        "id": "ConversateCueRoutingPolicy.selectOauth",
        "basename": "ConversateCueRoutingPolicy.java",
        "under": "main/java",
        "regex": r"\bselectOauth\s*\(",
        "note": "OAuth selection method.",
    },
    {
        "id": "ConversateCueRoutingPolicy.reserve",
        "basename": "ConversateCueRoutingPolicy.java",
        "under": "main/java",
        "regex": r"\breserve\s*\(",
        "note": "Reservation method.",
    },
    {
        "id": "ConversateCueRoutingPolicy.estimate",
        "basename": "ConversateCueRoutingPolicy.java",
        "under": "main/java",
        "regex": r"\bestimate\s*\(",
        "note": "Estimate method.",
    },
    {
        "id": "ChatGptOAuthRegistration.constructor",
        "basename": "ChatGptOAuthRegistration.java",
        "under": "main/java",
        "regex": r"ChatGptOAuthRegistration\s*\(",
        "note": "Constructors only. Do not open credentials files from this map.",
    },
    {
        "id": "ChatGptOAuthRegistration.models",
        "basename": "ChatGptOAuthRegistration.java",
        "under": "main/java",
        "regex": r"\bmodels\s*\(|models-file|modelsFile|MODELS",
        "note": "Model-list accessors and the models-file setting.",
    },
    {
        "id": "ChatModelCatalogService.oauthMerge",
        "basename": "ChatModelCatalogService.java",
        "under": "main/java",
        "regex": r"chatgpt-oauth|chatgpt_oauth|ChatGptOAuth|CHATGPT_OAUTH",
        "note": "Where OAuth catalog rows are merged.",
    },
    {
        "id": "DynamicChatModelFactory.chatgpt-oauth",
        "basename": "DynamicChatModelFactory.java",
        "under": "main/java",
        "regex": r"chatgpt-oauth|chatgpt_oauth|ChatGptOAuth",
        "note": "Factory branch for the OAuth provider.",
    },
    {
        "id": "FallbackAwareChatModel.fallbackAllowed",
        "basename": "FallbackAwareChatModel.java",
        "under": "main/java",
        "regex": r"fallbackAllowed",
        "note": "Fallback gate used by transition continuity.",
    },
    {
        "id": "PlannerNode.class",
        "basename": "PlannerNode.java",
        "under": "main/java",
        "regex": r"class\s+PlannerNode\b",
        "note": "Current planner shape. R5 does not rewrite it into a sequential agent.",
    },
    {
        "id": "SynthNode.class",
        "basename": "SynthNode.java",
        "under": "main/java",
        "regex": r"class\s+SynthNode\b",
        "note": "Synth node declaration.",
    },
    {
        "id": "application-meta-display.yml.jev",
        "basename": "application-meta-display.yml",
        "under": "main/resources",
        "regex": r"^\s*jev\s*:",
        "note": "Jev block. 62,500 ChatGPT credit text in this comment is a separate cost domain from Vercel AI Gateway.",
    },
]

RELATED_TESTS = [
    "ResponsesTerminalStatusContractTest",
    "OpenAiResponsesChatModelTest",
    "ChatGptOAuthRedTeamContractTest",
    "TimedChatModelCallerUsageTest",
    "TimedChatModelCallerTest",
    "ChatResponseDtoLearningContextTest",
    "ConversateCueRoutingPolicyTest",
    "LlmTraceAspectTerminalTest",
    "JevComplexityGateContractTest",
    "ConfigurableProviderContractTest",
    "RoutingBenefitLifecycleTest",
    "AdaptiveRouteDecisionTest",
    "RoutingTransitionContinuityTest",
    "RoutingConfigurationContinuityTest",
]


def unique_path(path: Path) -> Path:
    if not path.exists():
        return path
    stem, suffix = path.stem, path.suffix
    for n in range(1, 100):
        candidate = path.with_name(f"{stem}-{n:03d}{suffix}")
        if not candidate.exists():
            return candidate
    raise SystemExit("output name exhausted")


def iter_sources(root: Path):
    for rel in SCAN_ROOTS:
        base = root / rel
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if not path.is_file():
                continue
            if any(part in SKIP_PARTS for part in path.parts):
                continue
            if path.suffix.lower() not in {".java", ".yml", ".yaml"}:
                continue
            yield path


def read_lines(path: Path):
    data = path.read_bytes()
    if b"\x00" in data[:4096]:
        return None
    text = data.decode("utf-8-sig")
    return text.splitlines()


def context_hit(lines, index, radius=3):
    start = max(0, index - radius)
    end = min(len(lines), index + radius + 1)
    return {
        "line": index + 1,
        "text": lines[index],
        "before": [{"line": i + 1, "text": lines[i]} for i in range(start, index)],
        "after": [{"line": i + 1, "text": lines[i]} for i in range(index + 1, end)],
    }


def declaration_kind(line, name_pattern):
    stripped = line.strip()
    if re.search(r"\b(public|protected|private)\b", stripped) and re.search(name_pattern, stripped):
        return "declaration"
    return "reference"


def locate(root: Path, query, files):
    regex = re.compile(query["regex"])
    hits = []
    scanned = []
    for path in files:
        if path.name != query["basename"]:
            continue
        rel = path.relative_to(root).as_posix()
        if not rel.startswith(query["under"] + "/"):
            continue
        scanned.append(rel)
        lines = read_lines(path)
        if lines is None:
            continue
        for index, line in enumerate(lines):
            if regex.search(line):
                hit = context_hit(lines, index)
                hit["path"] = rel
                hit["kind"] = declaration_kind(line, query["regex"])
                hits.append(hit)
    status = "FOUND" if hits else "MISSING"
    return {
        "id": query["id"],
        "status": status,
        "basename": query["basename"],
        "under": query["under"],
        "regex": query["regex"],
        "note": query["note"],
        "files_scanned": scanned,
        "hit_count": len(hits),
        "hits": hits,
    }


def find_tests(root: Path):
    found = {name: [] for name in RELATED_TESTS}
    test_root = root / "src" / "test" / "java"
    if not test_root.is_dir():
        return [{"class": name, "status": "MISSING", "paths": []} for name in RELATED_TESTS]
    class_re = re.compile(r"(?m)^(?:public\s+)?(?:final\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)\b")
    pkg_re = re.compile(r"(?m)^package\s+([A-Za-z0-9_.]+)\s*;")
    for path in test_root.rglob("*.java"):
        if any(part in SKIP_PARTS for part in path.parts):
            continue
        lines = read_lines(path)
        if lines is None:
            continue
        text = "\n".join(lines)
        names = class_re.findall(text)
        if not any(name in found for name in names):
            continue
        package = pkg_re.search(text)
        package_name = package.group(1) if package else ""
        rel = path.relative_to(root).as_posix()
        for name in names:
            if name not in found:
                continue
            found[name].append({
                "path": rel,
                "fqcn": f"{package_name}.{name}" if package_name else name,
                "line": next((i + 1 for i, line in enumerate(lines) if re.search(rf"\bclass\s+{name}\b", line)), None),
            })
    rows = []
    for name in RELATED_TESTS:
        paths = found[name]
        rows.append({
            "class": name,
            "status": "FOUND" if paths else "MISSING",
            "paths": paths,
        })
    return rows


def probe_summary(path: Path | None):
    if path is None:
        return {"status": "NOT_RUN"}
    if not path.is_file():
        return {"status": "MISSING", "path": str(path)}
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    counts = {}
    nonmatch = []
    for entry in data.get("entries", []):
        status = entry.get("byte_status", "UNKNOWN")
        counts[status] = counts.get(status, 0) + 1
        if status != "MATCH":
            nonmatch.append({
                "id": entry.get("id"),
                "member": entry.get("member"),
                "byte_status": status,
            })
    return {
        "status": "READ",
        "path": str(path),
        "schema": data.get("schema"),
        "tool_exit_recorded_in_file": data.get("tool_exit_code"),
        "counts": counts,
        "nonmatch": nonmatch,
    }


def render_md(report) -> str:
    lines = [
        "# Codex R5 live anchor map",
        "",
        f"Generated: {report['generated_at']}",
        "",
        "Line numbers are from this working tree at generation time. ZIP line numbers are not used.",
        "A MISSING row means the symbol pattern had zero hits in the named file.",
        "",
        "## Engine-slots probe",
        "",
    ]
    probe = report["probe"]
    if probe.get("status") != "READ":
        lines.append(f"Probe summary: {probe.get('status')}")
    else:
        lines.append(f"Source: `{probe['path']}`")
        lines.append("")
        lines.append(f"Byte status counts: {json.dumps(probe.get('counts'), ensure_ascii=False)}")
        if probe.get("nonmatch"):
            lines.append("")
            lines.append("CHANGED or MISSING members:")
            for row in probe["nonmatch"]:
                lines.append(f"- {row['id']} {row['member']} {row['byte_status']}")
        else:
            lines.append("")
            lines.append("No CHANGED or MISSING members in this probe file.")
    lines.extend(["", "## Symbols", ""])
    for row in report["symbols"]:
        lines.append(f"### {row['id']}")
        lines.append("")
        lines.append(f"Status: **{row['status']}** ({row['hit_count']} hits)")
        lines.append("")
        lines.append(row["note"])
        lines.append("")
        if row["status"] == "MISSING":
            lines.append(f"MISSING. Scanned files: {', '.join(row['files_scanned']) or '(no file named ' + row['basename'] + ')'}")
            lines.append("")
            continue
        for hit in row["hits"]:
            lines.append(f"`{hit['path']}:{hit['line']}` ({hit['kind']})")
            lines.append("")
            lines.append("```")
            for item in hit["before"]:
                lines.append(f"{item['line']}: {item['text']}")
            lines.append(f"{hit['line']}: {hit['text']}")
            for item in hit["after"]:
                lines.append(f"{item['line']}: {item['text']}")
            lines.append("```")
            lines.append("")
    lines.extend(["## Related tests", ""])
    lines.append("| Class | Status | Path |")
    lines.append("|---|---|---|")
    for row in report["related_tests"]:
        if row["paths"]:
            where = ", ".join(f"{item['path']}:{item['line']}" for item in row["paths"])
        else:
            where = ""
        lines.append(f"| {row['class']} | {row['status']} | {where} |")
    lines.append("")
    return "\n".join(lines)


def main(argv):
    parser = argparse.ArgumentParser(description="Read-only Codex R5 symbol anchor map")
    parser.add_argument("--root", default=".", help="Project root")
    parser.add_argument("--out-dir", default="var/codex-assist-20261001")
    parser.add_argument("--json-name", default="anchor-map.json")
    parser.add_argument("--md-name", default="anchor-map.md")
    parser.add_argument("--probe-json", default="", help="Optional engine-slots probe JSON to summarize")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    if not (root / "main" / "java").is_dir():
        print(json.dumps({"error": "project-root-missing", "root": str(root)}))
        return 2
    files = list(iter_sources(root))
    symbols = [locate(root, query, files) for query in QUERIES]
    report = {
        "schema": "awx.codex-anchor-map.v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "root": str(root),
        "scan_roots": list(SCAN_ROOTS),
        "context_lines": 3,
        "external_provider_calls": 0,
        "symbols": symbols,
        "symbol_found": sum(1 for row in symbols if row["status"] == "FOUND"),
        "symbol_missing": [row["id"] for row in symbols if row["status"] == "MISSING"],
        "related_tests": find_tests(root),
        "probe": probe_summary(Path(args.probe_json) if args.probe_json else None),
    }
    out_dir = root / args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = unique_path(out_dir / args.json_name)
    md_path = unique_path(out_dir / args.md_name)
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md_path.write_text(render_md(report), encoding="utf-8")
    print(json.dumps({
        "json": str(json_path),
        "md": str(md_path),
        "found": report["symbol_found"],
        "missing": report["symbol_missing"],
        "tests_missing": [row["class"] for row in report["related_tests"] if row["status"] == "MISSING"],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
