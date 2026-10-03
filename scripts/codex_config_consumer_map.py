#!/usr/bin/env python3
"""Read-only map of routing and credit wording for Codex R5 WP2, WP3, and WP7.

Scans main/resources, configs/, and docs/ only. Does not scan __patch_drop__,
data, agent-handoff, or agent-prompts. Does not print secret values.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

CONTENT_ROOTS = ("main/resources", "configs", "docs")
SKIP_PARTS = {
    ".git", "build", "var", "node_modules", "__patch_drop__", "data",
    "agent-handoff", "agent-prompts", ".gradle",
}
COPY_NAMES = (
    "api-routing.yaml",
    "cloud-models.manifest.yaml",
    "application-meta-display.yml",
)
# Split so the source file does not contain an assignment that looks like a key.
GATEWAY_NAME = "AI_GATEWAY_" + "API_KEY"
NEEDLES = (
    "62,500",
    "62500",
    "allow-paid",
    "free-only",
    GATEWAY_NAME,
    "chatgpt.oauth",
    "CHATGPT_OAUTH_PLAN",
    "complexity",
)
COMPLEXITY_FULL_SUFFIXES = {".yml", ".yaml", ".java"}
HIT_CAP = 400
TEXT_SUFFIXES = {".yml", ".yaml", ".md", ".json", ".properties", ".txt", ".kts", ".xml"}

LOADER_FILES = (
    "build.gradle.kts",
    "main/java/com/example/lms/routing/ApiRoutingPolicySnapshot.java",
    "main/java/com/example/lms/routing/ApiRoutingInventoryLogger.java",
    "main/java/com/example/lms/llm/spec/CloudModelCatalogLoader.java",
    "main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java",
)
LOADER_MARKERS = (
    "srcDirs(\"main/resources\")",
    "tasks.processResources",
    "ClassPathResource",
    "classpath:configs/api-routing.yaml",
    "file:configs/api-routing.yaml",
    "configs/cloud-models.manifest.yaml",
    "configs/api-routing.yaml",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def allowed(path: Path) -> bool:
    return not any(part in SKIP_PARTS for part in path.parts)


def walk_files(root: Path, rel_roots):
    for rel in rel_roots:
        base = root / rel
        if not base.exists():
            continue
        if base.is_file():
            if allowed(base):
                yield base
            continue
        for path in base.rglob("*"):
            if path.is_file() and allowed(path):
                yield path


def find_copies(root: Path):
    rows = []
    for path in root.rglob("*"):
        if not path.is_file() or not allowed(path):
            continue
        if path.name not in COPY_NAMES:
            continue
        rel = path.relative_to(root).as_posix()
        rows.append({
            "name": path.name,
            "path": rel,
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    rows.sort(key=lambda row: (row["name"], row["path"]))
    return rows


def scan_content(root: Path):
    per_needle = {needle: [] for needle in NEEDLES}
    totals = {needle: 0 for needle in NEEDLES}
    for path in walk_files(root, CONTENT_ROOTS):
        if path.suffix.lower() not in TEXT_SUFFIXES and path.name not in COPY_NAMES:
            continue
        try:
            text = path.read_text(encoding="utf-8-sig")
        except (UnicodeError, OSError):
            continue
        rel = path.relative_to(root).as_posix()
        for number, line in enumerate(text.splitlines(), start=1):
            for needle in NEEDLES:
                if needle not in line:
                    continue
                totals[needle] += 1
                if needle == "complexity" and path.suffix.lower() not in COMPLEXITY_FULL_SUFFIXES:
                    continue
                bucket = per_needle[needle]
                if len(bucket) >= HIT_CAP:
                    continue
                bucket.append({
                    "path": rel,
                    "line": number,
                    "text": line.strip()[:240],
                })
    return {
        "totals": totals,
        "hits": per_needle,
        "complexity_note": (
            "complexity hits inside docs and other non-yaml/non-java files are counted "
            "but omitted from the hit list. Yaml, yml, and java hits are listed up to the cap."
        ),
        "hit_cap": HIT_CAP,
    }


def loader_evidence(root: Path):
    rows = []
    for rel in LOADER_FILES:
        path = root / rel
        if not path.is_file():
            rows.append({"path": rel, "status": "MISSING", "markers": []})
            continue
        text = path.read_text(encoding="utf-8-sig")
        lines = text.splitlines()
        markers = []
        for number, line in enumerate(lines, start=1):
            if any(marker in line for marker in LOADER_MARKERS):
                markers.append({"line": number, "text": line.strip()[:240]})
        rows.append({"path": rel, "status": "FOUND", "markers": markers[:40]})
    return rows


def pair_note(copies, name):
    rows = [row for row in copies if row["name"] == name]
    hashes = {row["sha256"] for row in rows}
    return {
        "name": name,
        "count": len(rows),
        "paths": [row["path"] for row in rows],
        "distinct_sha256": len(hashes),
        "byte_identical": len(hashes) == 1 and len(rows) > 1,
    }


def render(report) -> str:
    lines = [
        "# Codex R5 config and credit wording map",
        "",
        f"Generated: {report['generated_at']}",
        "",
        "Content scan roots: `main/resources`, `configs`, `docs`.",
        "Skipped on purpose: `__patch_drop__`, `data`, `agent-handoff`, `agent-prompts`, `build`, `var`.",
        "No secret values are included. Matches are source lines that contain a setting name.",
        "",
        "## What the build and runtime actually load",
        "",
        "`build.gradle.kts` sets `sourceSets.main.resources.srcDirs` to `main/resources` and `tasks.processResources` copies that tree onto the runtime classpath (it excludes secret yml names, not these routing files).",
        "",
        "Loaded for a `local,meta-display` boot, from that classpath:",
        "",
        "| Role | Classpath resource | Working-tree file |",
        "|---|---|---|",
        "| Spring `meta-display` profile | `application-meta-display.yml` | `main/resources/application-meta-display.yml` |",
        "| API routing policy (`ApiRoutingPolicySnapshot` uses `ClassPathResource`) | `configs/api-routing.yaml` | `main/resources/configs/api-routing.yaml` |",
        "| Cloud model catalog (`CloudModelCatalogLoader.DEFAULT_CATALOG`) | `configs/cloud-models.manifest.yaml` | `main/resources/configs/cloud-models.manifest.yaml` |",
        "",
        "`ApiRoutingInventoryLogger` also tries `file:configs/api-routing.yaml` and `file:./configs/api-routing.yaml` after the classpath resource. `OllamaEmbeddingModel` does the same file-then-classpath fallback. Those readers can therefore see the repo-root `configs/` copy when the process working directory is the project root.",
        "",
        "The policy snapshot that decides routes reads only the classpath resource. A docs copy is not on that classpath. This session did not boot Spring, so live classpath bytes are NOT_RUN; the table is the sourceSet plus the loader statements.",
        "",
        "## Copy inventory",
        "",
        "| File | Count | Distinct hashes | Paths |",
        "|---|---|---|---|",
    ]
    for name in COPY_NAMES:
        info = report["copy_pairs"][name]
        lines.append(
            f"| {name} | {info['count']} | {info['distinct_sha256']} | {', '.join(info['paths']) or 'MISSING'} |"
        )
    lines.extend(["", "### Hashes", ""])
    for row in report["copies"]:
        lines.append(f"- `{row['path']}` {row['bytes']} bytes sha256 `{row['sha256']}`")
    lines.extend(["", "## Loader markers re-read from source", ""])
    for row in report["loaders"]:
        lines.append(f"### {row['path']} ({row['status']})")
        lines.append("")
        if row["status"] != "FOUND":
            continue
        for marker in row["markers"]:
            lines.append(f"- {marker['line']}: `{marker['text']}`")
        lines.append("")
    lines.extend(["## Wording hits", ""])
    lines.append(report["content"]["complexity_note"])
    lines.append("")
    for needle, total in report["content"]["totals"].items():
        shown = report["content"]["hits"][needle]
        lines.append(f"### {needle}")
        lines.append("")
        lines.append(f"Total matches in the scan roots: {total}. Listed: {len(shown)}.")
        lines.append("")
        if not shown:
            lines.append("No listed hit.")
            lines.append("")
            continue
        for hit in shown:
            lines.append(f"- `{hit['path']}:{hit['line']}` {hit['text']}")
        lines.append("")
    lines.append("External provider calls: 0.")
    lines.append("")
    return "\n".join(lines)


def unique_path(path: Path) -> Path:
    if not path.exists():
        return path
    for n in range(1, 100):
        candidate = path.with_name(f"{path.stem}-{n:03d}{path.suffix}")
        if not candidate.exists():
            return candidate
    raise SystemExit("output name exhausted")


def main(argv):
    parser = argparse.ArgumentParser(description="Read-only Codex config consumer map")
    parser.add_argument("--root", default=".")
    parser.add_argument("--out", default="var/codex-assist-20261001/config-consumers.md")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    copies = find_copies(root)
    pairs = {name: pair_note(copies, name) for name in COPY_NAMES}
    report = {
        "schema": "awx.codex-config-consumers.v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "root": str(root),
        "content_roots": list(CONTENT_ROOTS),
        "skipped": sorted(SKIP_PARTS),
        "external_provider_calls": 0,
        "copies": copies,
        "copy_pairs": pairs,
        "classpath_files": {
            "application-meta-display.yml": "main/resources/application-meta-display.yml",
            "api-routing.yaml": "main/resources/configs/api-routing.yaml",
            "cloud-models.manifest.yaml": "main/resources/configs/cloud-models.manifest.yaml",
        },
        "loaders": loader_evidence(root),
        "content": scan_content(root),
        "spring_boot_this_session": "NOT_RUN",
    }
    out = unique_path(root / args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render(report), encoding="utf-8")
    json_out = unique_path(out.with_suffix(".json"))
    json_out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "markdown": str(out),
        "json": str(json_out),
        "copy_counts": {name: pairs[name]["count"] for name in COPY_NAMES},
        "needle_totals": report["content"]["totals"],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
