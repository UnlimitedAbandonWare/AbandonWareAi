#!/usr/bin/env python3
"""Read-only source contract probe; writes bounded derived maps under repo var."""
from __future__ import annotations
import argparse
import ast
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {"journal": "scripts/work_journal.py", "checkpoint": "scripts/codex_work_checkpoint.py",
           "receipt": "scripts/coop_verify.py", "paths": "configs/agent-paths.yaml"}
OVERLAPS = {
    "grokbot_memory_primer": ("leases, in-progress journals, current-source/summary references", "stdout primer; optional clipboard"),
    "agent_signal_digest": ("lease/journal metadata, recent handoff dirs, Git metadata, bridge/bus events", "bounded Markdown/JSON stdout; advisory exit 0"),
    "session_close_gate": ("hot-files, target manifest, own journal/lease/checkpoint/test evidence", "can-start/close gate stdout; optional JSON report"),
    "awx_session_evidence": ("device-local source SQLite metadata and bounded session rollout files", "derived local session index DB for index/search; query stdout"),
    "agent_session_watch": ("device-local Codex/Grok/Devin store metadata and bounded rollout files", "bounded health reports/diagnostic evidence; original stores read-only"),
    "gptpro_pack_context": ("current source/docs, journals and diagnostic references", "context sections returned to pack caller; no task-context SSOT"),
}


def source_text(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError("missing-or-unsafe-contract-source")
    return path.read_text(encoding="utf-8-sig")


def constants(tree):
    out = {}
    for node in tree.body:
        if isinstance(node, ast.Assign):
            try:
                value = ast.literal_eval(node.value)
            except (ValueError, TypeError):
                continue
            for target in node.targets:
                if isinstance(target, ast.Name):
                    out[target.id] = (value, node.lineno)
    return out


def probe(root: Path, journal_limit=20):
    result = {"schema": "awx.handoff-contract-map.v1", "contracts": {}, "sources": [],
              "journalInventory": {}, "missing": []}
    texts = {}
    trees = {}
    for kind, rel in SOURCES.items():
        path = root / rel
        try:
            text = source_text(path)
            texts[kind] = text
            result["sources"].append({"path": rel, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
            if path.suffix == ".py":
                trees[kind] = ast.parse(text)
        except (OSError, ValueError, SyntaxError):
            result["missing"].append(rel)
    def ref(kind, line):
        return f"{SOURCES[kind]}:{line}"
    def find(kind, needle):
        return next((i for i, line in enumerate(texts.get(kind, "").splitlines(), 1) if needle in line), None)
    if "journal" in trees:
        values = constants(trees["journal"])
        base, line = values.get("BASE", (None, 0))
        events = []
        for node in ast.walk(trees["journal"]):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == "add_note":
                for child in ast.walk(node):
                    if isinstance(child, ast.Dict):
                        keys = [k.value for k in child.keys if isinstance(k, ast.Constant) and isinstance(k.value, str)]
                        if set(("at", "kind", "refs", "text")).issubset(keys):
                            events.append({"keys": sorted(keys), "source": ref("journal", child.lineno)})
        result["contracts"]["journal"] = {"base": base, "source": ref("journal", line),
            "file": "journal.json", "fileSource": ref("journal", find("journal", '/ "journal.json"') or 1),
            "events": events}
        top, event_keys = Counter(), Counter()
        errors = 0
        directory = root / base if isinstance(base, str) else root / "missing"
        candidates = sorted(directory.glob("*/journal.json"), key=lambda p: p.stat().st_mtime, reverse=True)[:journal_limit]
        for path in candidates:
            try:
                if path.is_symlink() or path.stat().st_size > 512 * 1024:
                    raise ValueError("unsafe-journal")
                data = json.loads(path.read_bytes())
                if not isinstance(data, dict):
                    raise ValueError("invalid-journal")
                top.update(data.keys())
                for event in data.get("events", []):
                    if isinstance(event, dict):
                        event_keys.update(event.keys())
            except (OSError, ValueError, TypeError):
                errors += 1
        result["journalInventory"] = {"sampleLimit": journal_limit, "files": len(candidates),
            "unreadable": errors, "topKeyCounts": dict(sorted(top.items())),
            "eventKeyCounts": dict(sorted(event_keys.items())), "textValuesExported": 0,
            "selection": "mtime newest; metadata inventory only, not authority"}
    if "checkpoint" in trees:
        contract = {}
        for key, needle in (("storage", 'name.startswith("data/agent-handoff/codex-autonomy/")'),
                            ("alternateStorage", 'name.startswith("autonomy-checkpoints/")'),
                            ("preimageSha256", '"preimageSha256": digest(data)'),
                            ("postimages", "postimages=hashes")):
            line = find("checkpoint", needle)
            contract[key] = {"source": ref("checkpoint", line) if line else None,
                             "observed": line is not None}
        contract["storage"]["path"] = "data/agent-handoff/codex-autonomy/<task>/<cycle>/"
        contract["alternateStorage"]["path"] = "autonomy-checkpoints/ (only root.name == .codex)"
        result["contracts"]["checkpoint"] = contract
    if "receipt" in trees:
        values = constants(trees["receipt"])
        base, line = values.get("DEFAULT_STORE", (None, 0))
        statuses, status_line = values.get("TICKET_CLOSED", (set(), 0))
        receipt_line = find("receipt", 'receipt_rel = "receipts/"') or find("receipt", 'receipt_rel =') or find("receipt", "receipts/")
        result["contracts"]["receipt"] = {"base": base, "source": ref("receipt", line),
            "path": str(base) + "/receipts/", "receiptSource": ref("receipt", receipt_line or 1),
            "closedStates": sorted(statuses), "statesSource": ref("receipt", status_line),
            "exitCodes": {k: {"value": v, "source": ref("receipt", n)}
                          for k, (v, n) in values.items() if k.startswith("EXIT_")}}
    if "paths" in texts:
        lines = texts["paths"].splitlines()
        paths = {}
        for i, line in enumerate(lines):
            match = re.match(r"^  (handoff\.root|journal\.base):\s*$", line)
            if match:
                next_path = next(((n + 1, re.match(r"\s+path:\s*(.+)", lines[n]))
                                  for n in range(i + 1, min(i + 6, len(lines)))
                                  if re.match(r"\s+path:\s*(.+)", lines[n])), None)
                if next_path:
                    n, m = next_path
                    paths[match[1]] = {"path": m.group(1).strip(), "source": ref("paths", n)}
        result["contracts"]["configuredPaths"] = paths
    return result


def overlap(root: Path):
    lines = ["# Existing overlap (source inspection only)", "",
             "Tool | Reads | Writes / output | Source", "--- | --- | --- | ---"]
    for name, (reads, writes) in OVERLAPS.items():
        path = root / "scripts" / (name + ".py")
        if not path.is_file():
            lines.append(f"{name} | 확인 필요 | 확인 필요 | 확인 필요")
            continue
        text = source_text(path)
        anchor = next((i for i, line in enumerate(text.splitlines(), 1)
                       if re.match(r"(def |ROOT|BASE|DEFAULT)", line)), 1)
        lines.append(f"{name} | {reads} | {writes} | scripts/{name}.py:{anchor}")
    lines.extend(["", "These describe overlapping inputs; no helper is executed by this probe.",
                  "Optional modes remain the existing tool's authority; this map grants no runtime/mutation authority."])
    return "\n".join(lines) + "\n"


def out_path(value):
    path = Path(os.path.abspath(ROOT / value))
    path.relative_to(ROOT / "var")
    if path == ROOT / "var" or path.exists():
        raise ValueError("output-must-be-new-under-var")
    for parent in path.parents:
        if parent.is_symlink() or (parent.exists() and getattr(parent.lstat(), "st_file_attributes", 0) & 1024):
            raise ValueError("reparse-output")
    return path


class ProbeTests(unittest.TestCase):
    def test_ast_map_and_no_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "scripts").mkdir()
            (root / "configs").mkdir()
            (root / SOURCES["journal"]).write_text('BASE="data/journals"\ndef add_note():\n return {"at":1,"kind":2,"refs":[],"text":"PRIVATE"}\nf="journal.json"\n')
            (root / SOURCES["checkpoint"]).write_text('def f(name,data,hashes):\n name.startswith("data/agent-handoff/codex-autonomy/")\n name.startswith("autonomy-checkpoints/")\n row={"preimageSha256": digest(data)}\n state.update(postimages=hashes)\n')
            (root / SOURCES["receipt"]).write_text('DEFAULT_STORE="data/coop"\nTICKET_CLOSED={"VERIFIED_PASS","INVALIDATED"}\nEXIT_PASS=0\nreceipt_rel = None\n')
            (root / SOURCES["paths"]).write_text('  handoff.root:\n    path: data/handoff\n  journal.base:\n    path: data/journals\n')
            j = root / "data/journals/a/journal.json"
            j.parent.mkdir(parents=True)
            j.write_text('{"events":[{"at":1,"kind":"info","text":"PRIVATE","refs":[]}]}')
            data = probe(root)
            self.assertEqual(data["missing"], [])
            self.assertEqual(data["contracts"]["journal"]["events"][0]["keys"], ["at", "kind", "refs", "text"])
            self.assertEqual(data["contracts"]["receipt"]["closedStates"], ["INVALIDATED", "VERIFIED_PASS"])
            self.assertEqual(data["journalInventory"]["files"], 1)
            self.assertNotIn("PRIVATE", json.dumps(data))
            self.assertTrue(data["contracts"]["checkpoint"]["postimages"]["observed"])
    def test_missing_is_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(len(probe(Path(tmp))["missing"]), 4)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, default=ROOT)
    p.add_argument("--out", default="var/codex-assist-session-context/contract-map.json")
    p.add_argument("--self-test", action="store_true")
    args = p.parse_args(argv)
    if args.self_test:
        r = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ProbeTests))
        return 0 if r.wasSuccessful() else 1
    try:
        data = probe(args.root.resolve())
        path = out_path(args.out)
        overlaps = path.with_name("overlap.md")
        if overlaps.exists():
            raise ValueError("overlap-output-exists")
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
        with overlaps.open("x", encoding="utf-8") as f:
            f.write(overlap(args.root.resolve()))
        print(json.dumps({"status": "PASS" if not data["missing"] else "FAIL",
                          "contracts": len(data["contracts"]), "journals": data["journalInventory"].get("files", 0),
                          "missingCount": len(data["missing"])}))
        return 0 if not data["missing"] else 1
    except (ValueError, OSError):
        print(json.dumps({"status": "FAIL", "reason": "contract-probe-or-output-boundary"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
