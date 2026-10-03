"""Read-only SKIP_INACTIVE classifier for MAX-PUSH Kit D.

F05 GraphRAG indexing, F06 Neo4j, F11 incremental publish, F12 directive BM25.
Prints JSON. Does not enable any of those paths and does not print URI or
credential values.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

SCHEMA = "awx.max-push.skip-inactive.v1"
APP_YML = "main/resources/application.yml"
GRAPH_YML = "main/resources/application-graph-rag.yml"
CONTROLLER = "main/java/com/example/lms/api/ChatApiController.java"
BM25 = "main/java/com/example/lms/service/service/rag/bm25/Bm25Index.java"
BM25_TOKEN = "com.example.lms.service.service.rag.bm25.Bm25Index"
LATEST = "var/rag-launcher/LATEST.json"
TRUE_WORDS = {"1", "true", "yes", "on"}
FALSE_WORDS = {"0", "false", "no", "off"}


def flag_word(raw: str | None) -> str:
    if raw is None or raw.strip() == "":
        return "unset"
    word = raw.strip().lower()
    if word in TRUE_WORDS:
        return "true"
    if word in FALSE_WORDS:
        return "false"
    return "non-boolean"


def classify_f06(app_yml: str, kg_flag: str, manual_flag: str, child_flag: str, has_uri: bool) -> dict:
    kg_default_off = "RETRIEVAL_KG_NEO4J_ENABLED:false" in app_yml
    manual_parent_off = "GRAPHDB_MANUAL_LEARNING_ENABLED:false" in app_yml
    env_on = kg_flag == "true" or manual_flag == "true" or child_flag == "true"
    if env_on or has_uri:
        verdict = "EVIDENCE_NEEDED"
        reason = (
            "A process flag is on or a URI is present. Do not enable Neo4j from this script. "
            "Re-check the live process before F06."
        )
    elif kg_default_off and manual_parent_off and kg_flag == "unset" and manual_flag == "unset":
        verdict = "SKIP_INACTIVE"
        reason = (
            "application.yml defaults retrieval.kg.neo4j.enabled off and "
            "graphdb.manual-learning.enabled off. A nested neo4j flag under a disabled parent is not activation."
        )
    else:
        verdict = "EVIDENCE_NEEDED"
        reason = "Expected default-off markers were missing or an enable flag was non-boolean."
    return {
        "id": "F06",
        "verdict": verdict,
        "doNotEnable": True,
        "reason": reason,
        "kgFlag": kg_flag,
        "manualLearningFlag": manual_flag,
        "hasUri": has_uri,
    }


def classify_f05(graph_yml: str, profiles: str) -> dict:
    indexing_default_on = "RAG_BRAIN_STATE_INDEXING_ENABLED:true" in graph_yml
    parts = [p.strip() for p in profiles.split(",") if p.strip()]
    if profiles.strip() in ("", "not_observed"):
        verdict = "EVIDENCE_NEEDED"
        reason = "Spring profile list was not observed. Do not turn the graph-rag profile on to collect F05."
    elif "graph-rag" in parts:
        verdict = "ACTIVE_PROFILE"
        reason = "Observed profile list includes graph-rag. Indexing defaults apply only inside that profile."
    elif indexing_default_on:
        verdict = "SKIP_INACTIVE"
        reason = (
            "Indexing default lives in application-graph-rag.yml. "
            "The observed profile list does not include graph-rag."
        )
    else:
        verdict = "EVIDENCE_NEEDED"
        reason = "Graph indexing marker was missing from application-graph-rag.yml."
    return {
        "id": "F05",
        "verdict": verdict,
        "doNotEnable": True,
        "reason": reason,
        "profiles": profiles,
        "indexingDefaultInProfileFile": indexing_default_on,
    }


def classify_f12(production_hits: list[str], bean_declared: bool = False) -> dict:
    callers = [p for p in production_hits if not p.replace("\\", "/").endswith(BM25)]
    if bean_declared:
        verdict = "ACTIVE_CANDIDATE"
        reason = "A component or bean factory for the directive Bm25Index is present. Confirm call volume before a patch."
    elif callers:
        verdict = "SKIP_INACTIVE"
        reason = (
            "main/java references the directive Bm25Index, but no component stereotype or bean factory was found. "
            "Do not register a new bean. Optional injection is not activation."
        )
    else:
        verdict = "SKIP_INACTIVE"
        reason = (
            "Directive class com.example.lms.service.service.rag.bm25.Bm25Index has no other main/java reference. "
            "Do not register a new bean. A different Bm25Index under com.abandonware is not this item."
        )
    return {
        "id": "F12",
        "verdict": verdict,
        "doNotEnable": True,
        "reason": reason,
        "beanDeclared": bean_declared,
        "otherMainJavaHits": callers,
    }


def classify_f11(controller: str) -> dict:
    incremental = "releasePolicyAllowsIncrementalOutput" in controller
    buffered = "chunk(visibleFinalText, 60)" in controller
    if incremental:
        verdict = "ACTIVE_CANDIDATE"
        reason = "An incremental-release symbol is present. F11 is in scope for Codex."
    elif buffered:
        verdict = "SKIP_INACTIVE"
        reason = (
            "ChatApiController still cuts a finished visible answer into 60-character pieces. "
            "No incremental-release symbol was found."
        )
    else:
        verdict = "EVIDENCE_NEEDED"
        reason = "Neither the incremental symbol nor the 60-character cut was found."
    return {"id": "F11", "verdict": verdict, "doNotEnable": True, "reason": reason}


def classify(app_yml: str, graph_yml: str, profiles: str, controller: str,
             production_hits: list[str], kg_flag: str, manual_flag: str,
             child_flag: str, has_uri: bool, bean_declared: bool = False) -> dict:
    items = [
        classify_f05(graph_yml, profiles),
        classify_f06(app_yml, kg_flag, manual_flag, child_flag, has_uri),
        classify_f11(controller),
        classify_f12(production_hits, bean_declared),
    ]
    return {"schemaVersion": SCHEMA, "items": items, "enablesNothing": True}


def read_text(root: Path, rel: str) -> str:
    path = root / rel
    if not path.is_file():
        return ""
    return path.read_text(encoding="utf-8-sig", errors="replace")


def production_hits(root: Path) -> list[str]:
    base = root / "main" / "java"
    found = []
    if not base.is_dir():
        return found
    for path in base.rglob("*.java"):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if BM25_TOKEN in text:
            found.append(path.relative_to(root).as_posix())
    return sorted(found)


def observed_profiles(root: Path) -> str:
    path = root / LATEST
    if not path.is_file():
        return "not_observed"
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return "not_observed"
    profile = data.get("springProfile")
    if isinstance(profile, str) and profile.strip():
        return profile.strip()
    return "not_observed"


def bean_declared(root: Path, hits: list[str]) -> bool:
    class_text = read_text(root, BM25)
    if "@Component" in class_text or "@Service" in class_text:
        return True
    for rel in hits:
        if rel.replace("\\", "/") == BM25:
            continue
        text = read_text(root, rel)
        if "@Bean" in text:
            return True
    return False


def live_report(root: Path) -> dict:
    has_uri = bool(os.environ.get("NEO4J_URI") or os.environ.get("RETRIEVAL_KG_NEO4J_URI"))
    hits = production_hits(root)
    report = classify(
        read_text(root, APP_YML),
        read_text(root, GRAPH_YML),
        observed_profiles(root),
        read_text(root, CONTROLLER),
        hits,
        flag_word(os.environ.get("RETRIEVAL_KG_NEO4J_ENABLED")),
        flag_word(os.environ.get("GRAPHDB_MANUAL_LEARNING_ENABLED")),
        flag_word(os.environ.get("GRAPHDB_MANUAL_LEARNING_NEO4J_ENABLED")),
        has_uri,
        bean_declared(root, hits),
    )
    report["profilesSource"] = LATEST
    report["bm25ClassPresent"] = (root / BM25).is_file()
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    json.dump(live_report(root), sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
