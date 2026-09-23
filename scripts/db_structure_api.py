#!/usr/bin/env python3
"""
db_structure_api.py — Lightweight REST API for DB Structure Probing

Serves the db_gap_scanner results as a REST API so Codex or external tools
can query the design-vs-implementation gap matrix programmatically.

No external dependencies beyond stdlib — uses http.server.

Usage:
    python scripts/db_structure_api.py --port 9090 --root main/java
    curl http://localhost:9090/api/gap-matrix
    curl http://localhost:9090/api/entities
    curl http://localhost:9090/api/subsystem/S02_CFVM
    curl http://localhost:9090/api/duplicates
    curl http://localhost:9090/api/trace-keys
    curl http://localhost:9090/api/volatile-audit

Endpoints:
    GET /api/gap-matrix       — Full subsystem gap matrix (JSON)
    GET /api/entities         — All @Entity classes
    GET /api/repositories     — All JpaRepository interfaces
    GET /api/subsystem/{id}   — Single subsystem detail
    GET /api/duplicates       — Duplicate class names
    GET /api/trace-keys       — TraceStore.put() key inventory
    GET /api/volatile-audit   — Volatile-only storage audit
    GET /api/health           — Health check
"""

import argparse
import json
import os
import re
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from collections import defaultdict
from urllib.parse import urlparse, parse_qs

# Reuse scanner logic
SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))

try:
    from db_gap_scanner import (
        SUBSYSTEM_SPECS, scan_java_file, extract_entity_details,
        extract_repository_details, find_duplicates, assess_subsystem,
    )
    from dataclasses import asdict
    HAS_SCANNER = True
except ImportError:
    HAS_SCANNER = False


def extract_trace_keys(root: Path) -> dict:
    """Extract all TraceStore.put() keys from Java source."""
    keys_by_file = defaultdict(list)
    key_set = set()
    pattern = re.compile(r'TraceStore\.(?:put|putIfAbsent|putInternal|inc|addLong|append|maxLong)\s*\(\s*"([^"]+)"')

    for f in root.rglob("*.java"):
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        for m in pattern.finditer(text):
            key = m.group(1)
            rel = str(f.relative_to(root))
            keys_by_file[rel].append(key)
            key_set.add(key)

    # Classify by subsystem
    subsystem_keys = defaultdict(list)
    for key in sorted(key_set):
        if key.startswith("cfvm."):
            subsystem_keys["S02_CFVM"].append(key)
        elif key.startswith("artplate.") or key.startswith("retrieval.order.strategy"):
            subsystem_keys["S03_MOE"].append(key)
        elif key.startswith("extremeZ.") or key.startswith("extreme"):
            subsystem_keys["S05_EXTREMEZ"].append(key)
        elif key.startswith("hypernova.") or key.startswith("twpm.") or key.startswith("cvar."):
            subsystem_keys["S06_HYPERNOVA"].append(key)
        elif key.startswith("retrieval.order"):
            subsystem_keys["S02_CFVM"].append(key)
        elif key.startswith("web.") or key.startswith("search."):
            subsystem_keys["SEARCH"].append(key)
        elif key.startswith("llm.") or key.startswith("model"):
            subsystem_keys["LLM"].append(key)
        else:
            subsystem_keys["OTHER"].append(key)

    return {
        "totalUniqueKeys": len(key_set),
        "totalFiles": len(keys_by_file),
        "bySubsystem": dict(subsystem_keys),
        "allKeys": sorted(key_set),
        "persistenceStatus": "ThreadLocal_volatile_request_scoped",
        "designRequirement": "durable_cross_session_MLA_breadcrumb_history",
        "gap": "CRITICAL — all trace keys lost on request completion",
    }


def volatile_audit(root: Path) -> list:
    """Audit classes that use volatile-only storage for design-required persistence."""
    results = []
    checks = [
        ("RawMatrixBuffer", ["ArrayDeque", "synchronized"], "S02_CFVM",
         "9-tile Boltzmann matrix stored in process-local ArrayDeque(capacity=9)"),
        ("TraceStore", ["ThreadLocal", "ConcurrentHashMap"], "S07_CIH_RAG",
         "MLA breadcrumbs stored in ThreadLocal — lost on request end"),
        ("DebugEventStore", ["ConcurrentLinkedDeque", "ring"], "S07_CIH_RAG",
         "Debug events in in-memory ring buffer — lost on restart"),
        ("ArtPlateEvolver", [], "S03_MOE",
         "Evolution proposals not persisted — lost on restart"),
        ("ExtremeZSystemHandler", [], "S05_EXTREMEZ",
         "Trigger history not persisted"),
    ]

    for cls_name, volatile_patterns, subsystem, description in checks:
        for f in root.rglob("*.java"):
            try:
                text = f.read_text(encoding="utf-8", errors="ignore")
            except Exception:
                continue
            if re.search(rf"class\s+{cls_name}\b", text):
                found_volatile = []
                for vp in volatile_patterns:
                    if vp in text:
                        found_volatile.append(vp)
                results.append({
                    "class": cls_name,
                    "subsystem": subsystem,
                    "file": str(f.relative_to(root)),
                    "volatilePatterns": found_volatile,
                    "description": description,
                    "hasDurableStorage": False,
                    "recommendation": f"Add JPA entity or NDJSON persistence for {cls_name}",
                })

    return results


class ScanCache:
    """Cache scan results to avoid rescanning on every request."""
    def __init__(self, root: Path):
        self.root = root
        self._data = None

    def get(self) -> dict:
        if self._data is None:
            self._data = self._scan()
        return self._data

    def _scan(self) -> dict:
        if not HAS_SCANNER:
            return {"error": "db_gap_scanner module not available"}

        java_files = list(self.root.rglob("*.java"))
        classes, entities, repositories = [], [], []

        for f in java_files:
            ci = scan_java_file(f, self.root)
            if ci:
                classes.append(ci)
            ei = extract_entity_details(f, self.root)
            if ei:
                entities.append(ei)
            ri = extract_repository_details(f, self.root)
            if ri:
                repositories.append(ri)

        duplicates = find_duplicates(classes)
        gaps = []
        for sid, spec in SUBSYSTEM_SPECS.items():
            gap = assess_subsystem(sid, spec, classes, entities, repositories, duplicates)
            gaps.append(gap)

        trace_keys = extract_trace_keys(self.root)
        volatile = volatile_audit(self.root)

        return {
            "summary": {
                "totalFiles": len(java_files),
                "totalClasses": len(classes),
                "totalEntities": len(entities),
                "totalRepositories": len(repositories),
                "totalDuplicates": len(duplicates),
            },
            "gaps": [asdict(g) for g in gaps],
            "entities": [asdict(e) for e in entities],
            "repositories": [asdict(r) for r in repositories],
            "duplicates": duplicates,
            "traceKeys": trace_keys,
            "volatileAudit": volatile,
        }


class ApiHandler(BaseHTTPRequestHandler):
    cache: ScanCache = None

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        routes = {
            "/api/health": self._health,
            "/api/gap-matrix": self._gap_matrix,
            "/api/entities": self._entities,
            "/api/repositories": self._repositories,
            "/api/duplicates": self._duplicates,
            "/api/trace-keys": self._trace_keys,
            "/api/volatile-audit": self._volatile_audit,
        }

        if path in routes:
            routes[path]()
        elif path.startswith("/api/subsystem/"):
            sid = path.split("/")[-1]
            self._subsystem(sid)
        else:
            self._json_response(404, {
                "error": "not_found",
                "available": list(routes.keys()) + ["/api/subsystem/{id}"],
            })

    def _health(self):
        self._json_response(200, {"status": "ok", "scanner": HAS_SCANNER})

    def _gap_matrix(self):
        data = self.cache.get()
        self._json_response(200, {
            "summary": data.get("summary", {}),
            "gaps": data.get("gaps", []),
        })

    def _entities(self):
        data = self.cache.get()
        self._json_response(200, data.get("entities", []))

    def _repositories(self):
        data = self.cache.get()
        self._json_response(200, data.get("repositories", []))

    def _duplicates(self):
        data = self.cache.get()
        self._json_response(200, data.get("duplicates", {}))

    def _trace_keys(self):
        data = self.cache.get()
        self._json_response(200, data.get("traceKeys", {}))

    def _volatile_audit(self):
        data = self.cache.get()
        self._json_response(200, data.get("volatileAudit", []))

    def _subsystem(self, sid: str):
        data = self.cache.get()
        gaps = data.get("gaps", [])
        match = [g for g in gaps if g.get("id", "").upper() == sid.upper()]
        if match:
            self._json_response(200, match[0])
        else:
            self._json_response(404, {
                "error": f"subsystem '{sid}' not found",
                "available": [g.get("id") for g in gaps],
            })

    def _json_response(self, code: int, body):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(body, indent=2, ensure_ascii=False).encode("utf-8"))

    def log_message(self, format, *args):
        sys.stderr.write(f"[db-api] {args[0]} {args[1]} {args[2]}\n")


def main():
    parser = argparse.ArgumentParser(description="DB Structure API Server")
    parser.add_argument("--port", type=int, default=9090)
    parser.add_argument("--root", default="main/java")
    args = parser.parse_args()

    root = Path(args.root)
    if not root.exists():
        print(f"ERROR: Root '{root}' not found", file=sys.stderr)
        sys.exit(1)

    ApiHandler.cache = ScanCache(root)

    print(f"Pre-scanning {root}...")
    ApiHandler.cache.get()
    print(f"DB Structure API ready on http://localhost:{args.port}")
    print(f"  GET /api/gap-matrix")
    print(f"  GET /api/entities")
    print(f"  GET /api/repositories")
    print(f"  GET /api/subsystem/S02_CFVM")
    print(f"  GET /api/trace-keys")
    print(f"  GET /api/volatile-audit")
    print(f"  GET /api/health")

    server = HTTPServer(("", args.port), ApiHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.server_close()


if __name__ == "__main__":
    main()
