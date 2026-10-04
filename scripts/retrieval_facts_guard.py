"""Read-only sparse/BM25 fact guard. Stdlib only. Network calls: none.

Exit 0: PASS, or PENDING_CODEX only.
Exit 2: at least one DRIFT or STALE finding.
Exit 1: the tool itself failed.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

SCHEMA = "awx.retrieval-facts-guard.v1"
BM25_PROPS = "main/java/com/abandonware/ai/agent/config/Bm25Props.java"
PINECONE = "main/java/com/example/lms/service/vector/PineconeVectorStoreAdapter.java"
BM25_CONFIG = "main/java/com/example/lms/config/Bm25Config.java"
STATUS_DOC = "docs/RAG_SPARSE_STATUS.md"
PROPERTIES = "main/resources/application.properties"
MAIN_CHAIN = (
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/service/rag/HybridRetriever.java",
    "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java",
    "main/java/com/example/lms/config/RetrieverChainConfig.java",
)
SCAN_DIRS = (
    ".grok/rules",
    ".agents/skills",
    ".devin/rules",
    "docs/agents-rules",
)
SCAN_FILES = ("AGENTS.md",)
TEXT_SUFFIXES = {".md", ".yml", ".yaml", ".txt", ".mdc"}
SPRING_ANN = ("@Component", "@Configuration", "@ConfigurationProperties")
OFF_TOKEN = re.compile(
    r"(?i)(?<![.\w])bm25(?![\w.]).{0,32}(?:기본값|기본|default).{0,16}"
    r"(?:\boff\b|꺼짐|꺼져|disabled)"
)
OFF_SHORT = re.compile(r"(?i)(?<![.\w])bm25\s*기본\s*(?:off|꺼)")
VALUE_DEFAULT = re.compile(r'@Value\(\s*"\$\{bm25\.enabled:([^}"]+)\}"\s*\)')
COSINE = re.compile(r'"cosine"\s*\.equals\s*\(\s*index\.path\(\s*"metric"\s*\)')
DENSE = re.compile(r'"dense"\s*\.equals\s*\(\s*index\.path\(\s*"vector_type"\s*\)')
NEGATED_MAIN = re.compile(
    r"메인\s*스위치\s*아님|메인이\s*아님|가짜\s*스위치|not\s+the\s+main\s+switch",
    re.I,
)
CALLS_MAIN = re.compile(r"메인|main\s+switch", re.I)
TURNS_ON = re.compile(r"켜면|켜라|켜서|켜기|turn(?:\s+it)?\s+on", re.I)
PROHIBITION = re.compile(r"켜기\s*제안\s*금지|켜지\s*마|켜면\s*안|제안\s*금지|금지|do\s+not", re.I)
BM25_WORD = re.compile(r"(?i)bm25")


def under(root: Path, rel: str) -> Path:
    path = (root / rel).resolve()
    base = root.resolve()
    if path != base and base not in path.parents:
        raise RuntimeError("path-escape:" + rel)
    return path


def read_text(path: Path) -> str:
    return path.read_bytes().decode("utf-8", errors="replace")


def rel_posix(root: Path, path: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def check_f1(root: Path) -> dict:
    path = under(root, BM25_PROPS)
    if not path.is_file():
        return {"id": "F1", "status": "DRIFT", "path": BM25_PROPS, "line": None,
                "detail": "Bm25Props missing"}
    text = read_text(path)
    match = VALUE_DEFAULT.search(text)
    if match is None:
        return {"id": "F1", "status": "DRIFT", "path": BM25_PROPS, "line": None,
                "detail": "bm25.enabled @Value default missing"}
    line = text[: match.start()].count("\n") + 1
    default = match.group(1).strip()
    if default != "true":
        return {"id": "F1", "status": "DRIFT", "path": BM25_PROPS, "line": line,
                "detail": "bm25.enabled default is " + default}
    return {"id": "F1", "status": "PASS", "path": BM25_PROPS, "line": line,
            "detail": "bm25.enabled default true"}


def check_f2(root: Path) -> dict:
    path = under(root, PINECONE)
    if not path.is_file():
        return {"id": "F2", "status": "DRIFT", "path": PINECONE, "line": None,
                "detail": "PineconeVectorStoreAdapter missing"}
    text = read_text(path)
    cosine = COSINE.search(text)
    dense = DENSE.search(text)
    if cosine is None or dense is None:
        return {"id": "F2", "status": "DRIFT", "path": PINECONE, "line": None,
                "detail": "cosine/dense index check missing"}
    line = text[: cosine.start()].count("\n") + 1
    return {"id": "F2", "status": "PASS", "path": PINECONE, "line": line,
            "detail": "cosine and dense checks present"}


def check_f3(root: Path) -> dict:
    counts = []
    missing = []
    total = 0
    for rel in MAIN_CHAIN:
        path = under(root, rel)
        if not path.is_file():
            missing.append(rel)
            continue
        text = read_text(path)
        count = len(BM25_WORD.findall(text))
        total += count
        counts.append({"path": rel, "count": count})
    if missing or total != 0:
        return {"id": "F3", "status": "DRIFT", "path": MAIN_CHAIN[0], "line": None,
                "detail": "사실 갱신 필요", "counts": counts, "missing": missing, "total": total}
    return {"id": "F3", "status": "PASS", "path": MAIN_CHAIN[0], "line": None,
            "detail": "main chain BM25 refs 0", "counts": counts, "total": 0}


def check_f4(root: Path) -> dict:
    path = under(root, BM25_CONFIG)
    if not path.is_file():
        return {"id": "F4", "status": "DRIFT", "path": BM25_CONFIG, "line": None,
                "detail": "Bm25Config missing"}
    text = read_text(path)
    stripped = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    for index, line in enumerate(stripped.splitlines(), start=1):
        code = line.split("//", 1)[0]
        for ann in SPRING_ANN:
            if re.search(r"(?<![\w])" + re.escape(ann) + r"\b", code):
                return {"id": "F4", "status": "DRIFT", "path": BM25_CONFIG, "line": index,
                        "detail": ann + " present"}
    return {"id": "F4", "status": "PASS", "path": BM25_CONFIG, "line": None,
            "detail": "no Component/Configuration/ConfigurationProperties"}


def line_is_stale(line: str) -> bool:
    if "facts-guard:allow" in line:
        return False
    if OFF_TOKEN.search(line) or OFF_SHORT.search(line):
        return True
    lowered = line.lower()
    if "retrieval.bm25.enabled" not in lowered:
        return False
    negated = NEGATED_MAIN.search(line) is not None
    if CALLS_MAIN.search(line) and not negated:
        return True
    if TURNS_ON.search(line) and PROHIBITION.search(line) is None and not negated:
        return True
    return False


def iter_scan_files(root: Path):
    for rel in SCAN_FILES:
        path = under(root, rel)
        if path.is_file():
            yield path
    for rel in SCAN_DIRS:
        directory = under(root, rel)
        if not directory.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(directory):
            dirnames[:] = [name for name in dirnames if name not in {".git"}]
            for name in filenames:
                path = Path(dirpath) / name
                if path.suffix.lower() in TEXT_SUFFIXES and path.is_file():
                    yield path


def check_f5(root: Path) -> dict:
    findings = []
    for path in iter_scan_files(root):
        text = read_text(path)
        rel = rel_posix(root, path)
        for index, line in enumerate(text.splitlines(), start=1):
            if line_is_stale(line):
                findings.append({
                    "id": "F5",
                    "status": "STALE",
                    "path": rel,
                    "line": index,
                    "detail": "wrong sparse/BM25 phrase",
                })
    if findings:
        return {"id": "F5", "status": "STALE", "path": findings[0]["path"],
                "line": findings[0]["line"], "detail": "wrong phrase count " + str(len(findings)),
                "hits": findings}
    return {"id": "F5", "status": "PASS", "path": SCAN_DIRS[0], "line": None,
            "detail": "no wrong phrase", "hits": []}


def check_f6(root: Path) -> dict:
    path = under(root, STATUS_DOC)
    if path.is_file():
        return {"id": "F6", "status": "PASS", "path": STATUS_DOC, "line": None,
                "detail": "status doc present"}
    if path.exists():
        return {"id": "F6", "status": "DRIFT", "path": STATUS_DOC, "line": None,
                "detail": "status path is not a file"}
    return {"id": "F6", "status": "PENDING_CODEX", "path": STATUS_DOC, "line": None,
            "detail": "docs/RAG_SPARSE_STATUS.md absent"}


def check_f7(root: Path) -> dict:
    path = under(root, PROPERTIES)
    present = False
    occurrences = 0
    if path.is_file():
        blob = path.read_bytes()
        occurrences = blob.count(b"rag.hybrid.weight")
        present = occurrences > 0
    consumers = []
    java_root = under(root, "main/java")
    if java_root.is_dir():
        for dirpath, dirnames, filenames in os.walk(java_root):
            dirnames[:] = [name for name in dirnames if name not in {".git"}]
            for name in filenames:
                if not name.endswith(".java"):
                    continue
                file_path = Path(dirpath) / name
                if b"rag.hybrid.weight" in file_path.read_bytes():
                    consumers.append(rel_posix(root, file_path))
    info = {
        "weightPresent": present,
        "occurrences": occurrences,
        "javaConsumerCount": len(consumers),
        "javaConsumerPaths": consumers,
    }
    return {"id": "F7", "status": "INFO", "path": PROPERTIES, "line": None,
            "detail": "rag.hybrid.weight info", "info": info}


def verdict_of(checks: list) -> tuple:
    if any(item["status"] == "DRIFT" for item in checks):
        return "DRIFT", 2
    if any(item["status"] == "STALE" for item in checks):
        return "STALE", 2
    if any(item["status"] == "PENDING_CODEX" for item in checks):
        return "PENDING_CODEX", 0
    return "PASS", 0


def evaluate(root: Path) -> dict:
    checks = [
        check_f1(root),
        check_f2(root),
        check_f3(root),
        check_f4(root),
        check_f5(root),
        check_f6(root),
        check_f7(root),
    ]
    verdict, code = verdict_of(checks)
    findings = []
    for item in checks:
        if item["status"] in {"DRIFT", "STALE", "PENDING_CODEX"}:
            if item["id"] == "F5" and item.get("hits"):
                findings.extend(item["hits"])
            else:
                findings.append({
                    "id": item["id"],
                    "status": item["status"],
                    "path": item.get("path"),
                    "line": item.get("line"),
                    "detail": item.get("detail"),
                })
    f7 = checks[-1].get("info", {})
    return {
        "schemaVersion": SCHEMA,
        "verdict": verdict,
        "exitCode": code,
        "checks": checks,
        "findings": findings,
        "info": {"f7": f7},
    }


def emit(payload: dict, root: Path) -> None:
    text = json.dumps(payload, ensure_ascii=True, indent=2)
    out_dir = under(root, "var/codex-assist-sparse-facts-guard")
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "last.json"
    out_path.write_text(text + "\n", encoding="utf-8", newline="\n")
    sys.stdout.write(text + "\n")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="retrieval_facts_guard.py")
    parser.add_argument("--root", default=".")
    parser.add_argument("--json", action="store_true")
    try:
        args, unknown = parser.parse_known_args(argv)
    except SystemExit:
        return 1
    if unknown:
        error = {"schemaVersion": SCHEMA, "verdict": "ERROR", "exitCode": 1,
                 "error": "unknown-args"}
        sys.stdout.write(json.dumps(error, ensure_ascii=True) + "\n")
        return 1
    try:
        root = Path(args.root).resolve()
        if not root.is_dir():
            raise RuntimeError("root-not-dir")
        payload = evaluate(root)
        emit(payload, root)
        return int(payload["exitCode"])
    except Exception as exc:  # tool failure, not a fact verdict
        error = {"schemaVersion": SCHEMA, "verdict": "ERROR", "exitCode": 1,
                 "error": exc.__class__.__name__}
        try:
            sys.stdout.write(json.dumps(error, ensure_ascii=True) + "\n")
        except Exception:
            pass
        return 1


if __name__ == "__main__":
    sys.exit(main())
