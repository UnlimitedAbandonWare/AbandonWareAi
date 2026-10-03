#!/usr/bin/env python3
"""Classify Hibernate/H2 DDL boot-log warnings by kind.

Read-only: parses a launcher out.log (or any text log) and reports a
per-kind count of "Error executing DDL" causes plus the object names seen.
It never touches the database, never deletes files, and never kills a JVM.

Usage:
    python -B scripts/classify_h2_ddl_warnings.py <path-to-out.log>
    python -B scripts/classify_h2_ddl_warnings.py --latest

--latest resolves the newest var/rag-launcher/*/chat-ui-vibe-listener-*.out.log
under the repository root.

P1-2 extension (DEMO1-CODEX-POSTF01B-TRACE-R2-TOOLMAP-20260929): also checks
whether the manual awx_* migration tables overlap JPA @Entity/@Table mappings
and whether log object names touch manual objects, then emits an advisory
verdict. --json-out writes the payload for handoff (ddl-noise.json). This is
diagnosis only - ddl-auto mode changes stay ASK_ONCE.
"""
from __future__ import annotations

import collections
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MIGRATIONS_DIR = ROOT / "main" / "resources" / "db" / "migration"
ENTITIES_ROOT = ROOT / "main" / "java"
MANUAL_TABLES = ("awx_jobs", "awx_job_results", "awx_understanding_receipts")

KIND_PATTERN = re.compile(
    r"(Constraint|Index|Table|Sequence|Column|Schema|View|Synonym|Domain|Type"
    r"|Trigger|Function|User|Role|Alias)\b[^A-Za-z0-9_]{1,4}"
    r"([A-Za-z0-9_$.-]{1,120})[^A-Za-z0-9_]{1,4}already exists",
    re.IGNORECASE,
)
DDL_PATTERN = re.compile(r'Error executing DDL "([^"]{0,200})')
WARN_PATTERN = re.compile(r"GenerationTarget encountered exception accepting command")
AWX_OBJECT_PATTERN = re.compile(r"\b(awx_[A-Za-z0-9_]+)\b", re.IGNORECASE)
TABLE_NAME_PATTERN = re.compile(r'@Table\s*\([^)]*?name\s*=\s*"([^"]+)"')
ENTITY_PATTERN = re.compile(r"@Entity\b")
CLASS_PATTERN = re.compile(r"\bclass\s+([A-Za-z0-9_]+)")


def latest_launcher_log() -> pathlib.Path | None:
    base = ROOT / "var" / "rag-launcher"
    if not base.is_dir():
        return None
    candidates = sorted(base.glob("*/chat-ui-vibe-listener-*.out.log"),
                        key=lambda p: p.stat().st_mtime, reverse=True)
    return candidates[0] if candidates else None


def classify(text: str) -> dict:
    lines = text.splitlines()
    ddl_lines = [line for line in lines if "Error executing DDL" in line]
    warn_lines = [line for line in lines if WARN_PATTERN.search(line)]
    kind_counts: collections.Counter = collections.Counter()
    object_counts: collections.Counter = collections.Counter()
    other_samples: collections.Counter = collections.Counter()
    ddl_prefixes: collections.Counter = collections.Counter()

    for line in lines:
        if "JdbcSQLSyntaxErrorException" in line or (
                "Caused by:" in line and "SQL statement" in line):
            match = KIND_PATTERN.search(line)
            if match:
                kind_counts[match.group(1).lower() + " already exists"] += 1
                object_counts[match.group(2)] += 1
            elif "already exists" in line:
                kind_counts["other already-exists"] += 1
                other_samples[line.strip()[:160]] += 1
            else:
                kind_counts["non-already-exists"] += 1
                other_samples[line.strip()[:160]] += 1
        match = DDL_PATTERN.search(line)
        if match:
            ddl_prefixes[match.group(1)[:80]] += 1

    return {
        "ddlWarnLines": len(ddl_lines),
        "hibernateWarnLines": len(warn_lines),
        "kindCounts": dict(kind_counts.most_common()),
        "topObjects": dict(object_counts.most_common(25)),
        "otherSamples": dict(other_samples.most_common(10)),
        "ddlStatementPrefixes": dict(ddl_prefixes.most_common(15)),
    }


def camel_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def manual_objects(migrations_dir: pathlib.Path) -> tuple[set[str], list[dict]]:
    """수동 마이그레이션 SQL 에서 awx_* 객체 이름(테이블/인덱스/제약) 수집."""
    objects = {t.lower() for t in MANUAL_TABLES}
    files = []
    if migrations_dir.is_dir():
        for p in sorted(migrations_dir.glob("*.sql")):
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            found = {m.lower() for m in AWX_OBJECT_PATTERN.findall(text)}
            if found:
                objects |= found
                files.append({"file": p.name, "objects": sorted(found)})
    return objects, files


def scan_entities(entities_root: pathlib.Path) -> list[dict]:
    """main/java 의 @Entity 에서 유효 테이블명 수집 (@Table name 우선,
    없으면 Spring 관례 camel->snake 클래스명)."""
    found: list[dict] = []
    if not entities_root.is_dir():
        return found
    for p in sorted(entities_root.rglob("*.java")):
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "@Entity" not in text:
            continue
        rel = str(p.relative_to(entities_root))
        explicit = TABLE_NAME_PATTERN.findall(text)
        if explicit:
            for name in explicit:
                found.append({"table": name.lower(), "entity": None,
                              "source": rel, "naming": "explicit"})
            continue
        for em in ENTITY_PATTERN.finditer(text):
            cm = CLASS_PATTERN.search(text, em.end())
            if cm:
                found.append({"table": camel_to_snake(cm.group(1)),
                              "entity": cm.group(1), "source": rel,
                              "naming": "implicit"})
    return found


def overlap_report(result: dict, entities_root: pathlib.Path,
                   migrations_dir: pathlib.Path) -> dict:
    """JPA 엔티티 테이블 ∩ 수동 awx_* 테이블 + 로그 객체 ∩ 수동 객체."""
    manual_objs, mig_files = manual_objects(migrations_dir)
    entities = scan_entities(entities_root)
    entity_hits = sorted({e["table"] for e in entities
                         if e["table"] in set(MANUAL_TABLES)})
    log_objects = {str(k).lower() for k in (result.get("topObjects") or {})}
    log_hits = sorted(log_objects & manual_objs)
    overlap = bool(entity_hits or log_hits)
    if overlap:
        advice = ("overlap detected: record recommendation only; entity "
                  "exclusion or ddl-auto mode change is ASK_ONCE")
    else:
        advice = ("no JPA/manual-table overlap observed; ddl-auto noise is "
                  "unrelated to awx_* - record advice only, no mode change")
    return {
        "manualTables": sorted(MANUAL_TABLES),
        "manualObjectsFromMigrations": sorted(manual_objs),
        "migrationFilesScanned": mig_files,
        "entityTablesScanned": len(entities),
        "entityTablesMatchingManual": entity_hits,
        "manualObjectsSeenInLog": log_hits,
        "overlap": overlap,
        "recommendation": advice,
    }


def verdict_block(result: dict, overlap: dict) -> dict:
    kinds = result.get("kindCounts") or {}
    non_noise = int(kinds.get("non-already-exists", 0))
    total = sum(int(v) for v in kinds.values())
    if total == 0 and not result.get("ddlWarnLines"):
        classification = "no-ddl-exception-lines"
    elif non_noise == 0:
        classification = "all-already-exists"
    else:
        classification = "has-non-already-exists"
    return {
        "classification": classification,
        "alreadyExistsCount": total - non_noise,
        "nonAlreadyExistsCount": non_noise,
        "manualTableOverlap": bool(overlap.get("overlap")),
        "note": "diagnosis only - one real error promotes to FIX-n; "
                "ddl-auto mode change stays ASK_ONCE",
    }


def main(argv: list[str] | None = None) -> int:
    import argparse
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("log", nargs="?", default=None,
                    help="log file path (or --latest)")
    ap.add_argument("--latest", action="store_true",
                    help="newest var/rag-launcher chat-ui-vibe-listener out.log")
    ap.add_argument("--entities-root", default=str(ENTITIES_ROOT),
                    help="JPA entity scan root (default main/java)")
    ap.add_argument("--migrations-dir", default=str(MIGRATIONS_DIR),
                    help="manual migration SQL dir (default "
                         "main/resources/db/migration)")
    ap.add_argument("--json-out", default=None,
                    help="also write payload to this path (ddl-noise.json)")
    args = ap.parse_args(argv)

    if args.latest:
        path = latest_launcher_log()
        if path is None:
            print(json.dumps({"status": "no-launcher-log"}))
            return 2
    elif args.log:
        path = pathlib.Path(args.log)
    else:
        print(__doc__.strip())
        return 2
    if not path.is_file():
        print(json.dumps({"status": "log-not-found", "path": str(path)}))
        return 2
    result = classify(path.read_text(encoding="utf-8", errors="replace"))
    result["log"] = str(path)
    result["manualOverlap"] = overlap_report(
        result, pathlib.Path(args.entities_root),
        pathlib.Path(args.migrations_dir))
    result["verdict"] = verdict_block(result, result["manualOverlap"])
    result["status"] = "classified"
    text = json.dumps(result, indent=2, ensure_ascii=False)
    if args.json_out:
        try:
            target = pathlib.Path(args.json_out)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text + "\n", encoding="utf-8")
        except OSError as e:
            print(json.dumps({"status": "json-out-failed", "error": str(e)},
                             ensure_ascii=False))
            return 1
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
