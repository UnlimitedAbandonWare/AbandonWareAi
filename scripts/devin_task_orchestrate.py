"""Plan Devin/Grok/Codex source-edit phases from a pasted brief.

JSON only. Playbooks live next to the skill. Does not grant lease or APPLY.
capture/compare persist redacted Fold/wear Debug BAT status for later patches.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys

SCHEMA = "awx.devin-task-orchestrate.v1"
SNAP_SCHEMA = "awx.display-debug-snapshot.v1"
EXTRACT_ALLOW = {
    "cueDecision", "decisionReason", "ragNeeded", "searchNeeded", "ragDocuments",
    "retrievalMs", "hintGenerationMs", "hintGenerated", "selectedProvider",
    "selectedModel", "apiAttempts", "status", "totalLatencyMs", "fallbackReason",
    "hintPath", "evidenceStatus", "noCue", "cue", "ragCue", "other",
}
SAFE_VALUE = re.compile(r"^[A-Za-z0-9_.:/=+\-]{0,80}$")
SECRET_KEY = re.compile(r"token|secret|password|authorization|cookie|apikey|pcm|transcript", re.I)
CAPTURE_CMD = "python -B scripts/devin_task_orchestrate.py capture --role wear --invoke"


def default_playbooks_path() -> Path:
    here = Path(__file__).resolve()
    if here.parent.name == "scripts":
        return here.parents[1] / ".agents/skills/demo1-devin-source-orchestrator/playbooks/playbooks.json"
    sibling = here.with_name("playbooks.json")
    if sibling.is_file():
        return sibling
    return here.parents[1] / ".agents/skills/demo1-devin-source-orchestrator/playbooks/playbooks.json"


def repo_root() -> Path:
    here = Path(__file__).resolve()
    if here.parent.name == "scripts":
        return here.parents[1]
    return here.parents[1] if (here.parent / "playbooks.json").is_file() else here.parents[4]


def load_playbooks(path: Path | None = None) -> dict:
    target = path or default_playbooks_path()
    if not target.is_file():
        alt = Path(__file__).resolve().with_name("playbooks.json")
        if alt.is_file():
            target = alt
        else:
            raise FileNotFoundError("playbooks-missing")
    data = json.loads(target.read_text(encoding="utf-8-sig"))
    if data.get("schemaVersion") != SCHEMA:
        raise ValueError("playbooks-schema")
    return data


def normalize(text: str) -> str:
    return " ".join((text or "").casefold().replace("〜", "~").split())


def matched_playbooks(book: dict, brief: str) -> list[dict]:
    hay = normalize(brief)
    hits = []
    for row in book.get("playbooks", []):
        if row.get("fallback"):
            continue
        if any(normalize(signal) and normalize(signal) in hay for signal in row.get("signals", [])):
            hits.append(row)
    hits.sort(key=lambda row: int(row.get("order", 100)))
    suppressed = {item for row in hits for item in row.get("suppress", [])}
    hits = [row for row in hits if row.get("id") not in suppressed]
    if hits:
        return hits
    if any(normalize(signal) in hay for signal in book.get("sourceEditSignals", [])):
        fallback = next((row for row in book["playbooks"] if row.get("fallback")), None)
        if fallback:
            return [fallback]
    return []


def phase_list(book: dict, playbooks: list[dict]) -> list[dict]:
    seen = set()
    ordered = []
    for name in book.get("alwaysFirst", []):
        if name not in seen:
            ordered.append(name)
            seen.add(name)
    capture_ids = set(book.get("alwaysCaptureFor", []))
    wants_capture = any(row.get("id") in capture_ids for row in playbooks)
    if wants_capture:
        before = book.get("captureBefore", "capture-before")
        if before not in seen:
            ordered.append(before)
            seen.add(before)
    for row in playbooks:
        for name in row.get("phases", []):
            if name not in seen:
                ordered.append(name)
                seen.add(name)
    if wants_capture:
        after = book.get("captureAfter", "capture-after")
        if after not in seen:
            ordered.append(after)
            seen.add(after)
    catalog = book.get("phases", {})
    phases = []
    for name in ordered:
        item = catalog.get(name)
        if not item:
            raise KeyError("unknown-phase:" + name)
        phases.append(dict(item))
    return phases


def plan(brief: str, book: Path | None = None) -> dict:
    data = load_playbooks(book)
    hits = matched_playbooks(data, brief)
    phases = phase_list(data, hits)
    return {
        "schemaVersion": SCHEMA,
        "matchedPlaybooks": [row["id"] for row in hits],
        "phases": phases,
        "nextPhase": phases[0] if phases else None,
        "forbidden": list(data.get("forbidden", [])),
        "skipUnion": sorted({item for phase in phases for item in phase.get("skip", [])}),
        "reason": "matched" if hits else "preflight-only",
    }


def next_phase(plan_doc: dict, done: list[str]) -> dict:
    remaining = [phase for phase in plan_doc.get("phases", [])
                 if phase.get("id") not in set(done)]
    return {
        "schemaVersion": SCHEMA,
        "done": list(done),
        "remaining": [phase["id"] for phase in remaining],
        "nextPhase": remaining[0] if remaining else None,
        "complete": not remaining,
    }


def self_test() -> dict:
    data = load_playbooks()
    cap = ["capture-before"]
    after = ["capture-after"]
    cases = [
        ("hint", "과거 대화 참고 범위를 Fold 설정에서 조절하고 늦은 응답이 되살아나지 않게 소스 수정해줘",
         ["hint-input-context"], ["preflight"] + cap + ["trace-hint-path", "select-hint-input",
                                  "stale-response-gate", "verify-hint-context"] + after),
        ("listen", "다른 창으로 가면 1~2분 뒤 백그라운드 수음이 끊깁니다. visibilitychange 를 확인하고 수정해줘",
         ["fold-background-listen"], ["preflight"] + cap + ["trace-listen", "patch-listen", "verify-listen"] + after),
        ("both", "힌트가 과거 주제에 끌려가고 지금부터 새 맥락 버튼이 필요하며 Fold에서 다른 탭이면 수음이 끊깁니다. 소스 수정해줘",
         ["hint-input-context", "fold-background-listen"],
         ["preflight"] + cap + ["trace-hint-path", "select-hint-input", "stale-response-gate",
          "verify-hint-context", "trace-listen", "patch-listen", "verify-listen"] + after),
        ("nova", "기존 전사에서 '노바' 호출어로 집중 대화를 열고, 늦은 응답이 닫힌 activation을 다시 열지 않게 하며, 집중창 표시는 pagehide 없이 overlay로 소스 수정해줘",
         ["nova-focus"], ["preflight"] + cap + ["nova-focus-contract", "nova-focus-implement",
                          "nova-focus-verify"] + after),
        ("typo", "README typo only", [], ["preflight"]),
        ("generic", "이 버그를 최소 수정으로 패치해줘", ["investigate-then-patch"],
         ["preflight", "investigate-then-patch"]),
        ("debug-only", "폴드로 재현했으니 디버깅 내역을 남겨 주세요",
         ["fold-wear-debug-record"], ["preflight"] + cap + after),
    ]
    failures = []
    for name, brief, expect_books, expect_phases in cases:
        result = plan(brief)
        books = result["matchedPlaybooks"]
        ids = [phase["id"] for phase in result["phases"]]
        if books != expect_books or ids != expect_phases:
            failures.append({"case": name, "books": books, "phases": ids,
                             "expectBooks": expect_books, "expectPhases": expect_phases})
        nxt = next_phase(result, [expect_phases[0]] if expect_phases else [])
        if expect_phases[1:] and nxt.get("nextPhase", {}).get("id") != expect_phases[1]:
            failures.append({"case": name + "-next", "next": nxt.get("nextPhase")})
    if "wipe-stored-transcripts" not in data.get("forbidden", []):
        failures.append({"case": "forbidden-missing"})
    return {"ok": not failures, "cases": len(cases), "failures": failures,
            "phaseCount": len(data.get("phases", {}))}


def clean_value(value):
    if isinstance(value, bool) or value is None:
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return value
    if isinstance(value, str) and SAFE_VALUE.fullmatch(value):
        return value[:80]
    return None


def clean_map(raw: dict, allow: set[str] | None = None) -> dict:
    out = {}
    if not isinstance(raw, dict):
        return out
    for key, value in raw.items():
        if not isinstance(key, str) or SECRET_KEY.search(key):
            continue
        if allow is not None and key not in allow:
            continue
        cleaned = clean_value(value)
        if cleaned is not None:
            out[key] = cleaned
    return out


def redact_status(raw: dict) -> dict:
    pipeline = []
    for stage in raw.get("pipeline") or []:
        if not isinstance(stage, dict):
            continue
        pipeline.append({
            "stage": clean_value(stage.get("stage")) or "unknown",
            "count": stage.get("count") if isinstance(stage.get("count"), int) else 0,
            "lastAt": clean_value(stage.get("lastAt")) or "",
            "extracted": clean_map(stage.get("extracted") or {}, EXTRACT_ALLOW),
            "counts": clean_map(stage.get("counts") or {}, EXTRACT_ALLOW),
        })
    freshness = raw.get("freshness") or {}
    served = freshness.get("servedAsset") or {}
    runtimes = []
    for row in raw.get("runtimes") or []:
        if not isinstance(row, dict):
            continue
        own = row.get("ownership") or {}
        runtimes.append({
            "processId": row.get("processId") if isinstance(row.get("processId"), int) else None,
            "role": clean_value(row.get("role")),
            "verboseApplied": bool(row.get("verboseApplied")),
            "ownershipOk": bool(own.get("ok")) if isinstance(own, dict) else False,
        })
    return {
        "schemaVersion": SNAP_SCHEMA,
        "sourceSchema": clean_value(raw.get("schemaVersion")),
        "role": clean_value(raw.get("role")),
        "action": clean_value(raw.get("action")),
        "ok": bool(raw.get("ok")),
        "status": clean_value(raw.get("status")),
        "runtimes": runtimes,
        "readiness": clean_map(raw.get("readiness") or {}),
        "freshness": {
            "sourcesNewer": clean_value(freshness.get("sourcesNewer")),
            "newestSource": clean_value(str(freshness.get("newestSource") or "").replace("\\", "/")),
            "servedMatch": clean_value((served or {}).get("match") if isinstance(served, dict) else None),
            "sourceHash": clean_value((served or {}).get("sourceHash") if isinstance(served, dict) else None),
            "servedHash": clean_value((served or {}).get("servedHash") if isinstance(served, dict) else None),
        },
        "pipeline": pipeline,
        "listenNote": "lastAudioReceivedAt/lastTranscriptReceivedAt live on Fold poll/status, not in Debug BAT JSON",
    }


def patch_hints(redacted: dict) -> list[dict]:
    stages = {row["stage"]: row for row in redacted.get("pipeline") or []}
    hints = []
    transcript = stages.get("transcript-input") or {}
    cue = stages.get("cue-gate") or {}
    generate = stages.get("hint-generate") or {}
    delivery = stages.get("display-delivery") or {}
    if int(transcript.get("count") or 0) == 0:
        hints.append({"seam": "fold-background-listen",
                      "why": "transcript-input count 0 — prove mic/ASR/session before hint-input"})
    extracted = cue.get("extracted") or {}
    if int(cue.get("count") or 0) > 0 and int(generate.get("count") or 0) == 0:
        hints.append({"seam": "cue-gate-or-hold",
                      "why": "cue-gate fired but hint-generate count 0 (hold/cooldown/NO_CUE)"})
    if extracted.get("hintPath") and extracted.get("cueDecision") == "NO_CUE":
        hints.append({"seam": "demo1-conversate-hint-context",
                      "why": "NO_CUE with a hintPath — leftover display vs skipped generation"})
    if generate.get("lastAt") and delivery.get("lastAt") and generate.get("lastAt") < delivery.get("lastAt"):
        hints.append({"seam": "stale-response-gate",
                      "why": "display-delivery lastAt after hint-generate — late/old card risk"})
    freshness = redacted.get("freshness") or {}
    if str(freshness.get("sourcesNewer") or "").startswith("true"):
        hints.append({"seam": "stale-jvm",
                      "why": "sources newer than runtime; live Fold is not this tree"})
    if freshness.get("servedMatch") and freshness.get("servedMatch") != "match":
        hints.append({"seam": "frontend-display-debug",
                      "why": "served receiver.js hash mismatch"})
    if not hints:
        hints.append({"seam": "read-snapshot",
                      "why": "no automatic seam; still record this snapshot before patching"})
    return hints


def latest_status_json(root: Path, role: str) -> Path | None:
    folder = root / "var" / "debug"
    if not folder.is_dir():
        return None
    files = [path for path in folder.glob(role + "-*-status.json") if path.is_file()]
    if not files:
        return None
    return max(files, key=lambda path: path.stat().st_mtime)


def invoke_debug(root: Path, role: str) -> int:
    env = os.environ.copy()
    env["AWX_RAG_NO_PAUSE"] = "1"
    bat = "Debug-Meta-Display.bat" if role == "wear" else "Debug-RAG.bat"
    try:
        proc = subprocess.run(
            ["cmd", "/c", bat, "-Action", "status", "-Json"],
            cwd=str(root), env=env, capture_output=True, timeout=90, check=False,
        )
        return proc.returncode
    except (OSError, subprocess.TimeoutExpired):
        return 1


def prune_snaps(folder: Path, keep: int = 20) -> None:
    files = sorted([path for path in folder.glob("*-status.json") if path.is_file()],
                   key=lambda path: path.stat().st_mtime)
    for path in files[:-keep]:
        path.unlink(missing_ok=True)


def capture(root: Path, role: str = "wear", task: str | None = None,
            invoke: bool = False, source: Path | None = None) -> dict:
    invoked = None
    if invoke and source is None:
        invoked = invoke_debug(root, role)
    src = source or latest_status_json(root, role)
    if src is None:
        packet = {
            "schemaVersion": SNAP_SCHEMA,
            "status": "not-running" if invoked == 3 else "evidence_needed",
            "reason": "debug-status-json-missing",
            "verifyWith": "Debug-Meta-Display.bat -Action status -Json" if role == "wear"
                          else "Debug-RAG.bat -Action status -Json",
            "invokeExit": invoked,
            "patchHints": [{"seam": "runtime-missing",
                            "why": "no Debug BAT JSON; do not invent Fold audio/hint state"}],
        }
        raw = packet
        redacted = packet
    else:
        raw = json.loads(src.read_text(encoding="utf-8-sig"))
        redacted = redact_status(raw)
        redacted["sourceFile"] = str(src.relative_to(root)).replace("\\", "/") if src.is_relative_to(root) else src.name
        redacted["invokeExit"] = invoked
        redacted["patchHints"] = patch_hints(redacted)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    shared = root / "data" / "agent-handoff" / "display-debug"
    shared.mkdir(parents=True, exist_ok=True)
    name = f"{stamp}-{role}-status.json"
    dest = shared / name
    dest.write_text(json.dumps(redacted, ensure_ascii=True, indent=2), encoding="utf-8")
    (shared / "latest.json").write_text(dest.read_text(encoding="utf-8"), encoding="utf-8")
    prune_snaps(shared)
    task_path = None
    if task:
        task_dir = root / "data" / "agent-handoff" / "codex-autonomy" / task / "debug-snapshots"
        task_dir.mkdir(parents=True, exist_ok=True)
        task_path = task_dir / name
        task_path.write_text(dest.read_text(encoding="utf-8"), encoding="utf-8")
        (task_dir / "latest.json").write_text(dest.read_text(encoding="utf-8"), encoding="utf-8")
    return {
        "schemaVersion": SNAP_SCHEMA,
        "ok": redacted.get("status") not in ("evidence_needed", "not-running"),
        "path": str(dest.relative_to(root)).replace("\\", "/"),
        "taskPath": None if task_path is None else str(task_path.relative_to(root)).replace("\\", "/"),
        "patchHints": redacted.get("patchHints"),
        "status": redacted.get("status"),
        "invokeExit": invoked,
    }


def compare(before: dict, after: dict) -> dict:
    def index(doc):
        return {row["stage"]: row for row in doc.get("pipeline") or []}
    left, right = index(before), index(after)
    stages = sorted(set(left) | set(right))
    deltas = []
    for stage in stages:
        a, b = left.get(stage) or {}, right.get(stage) or {}
        if a.get("count") != b.get("count") or a.get("lastAt") != b.get("lastAt"):
            deltas.append({
                "stage": stage,
                "countBefore": a.get("count"),
                "countAfter": b.get("count"),
                "lastAtBefore": a.get("lastAt") or "",
                "lastAtAfter": b.get("lastAt") or "",
                "hintPathBefore": (a.get("extracted") or {}).get("hintPath"),
                "hintPathAfter": (b.get("extracted") or {}).get("hintPath"),
            })
    return {
        "schemaVersion": SNAP_SCHEMA,
        "changedStages": deltas,
        "hintsBefore": before.get("patchHints") or patch_hints(before),
        "hintsAfter": after.get("patchHints") or patch_hints(after),
        "freshnessBefore": before.get("freshness"),
        "freshnessAfter": after.get("freshness"),
    }


def read_brief(args) -> str:
    if args.brief_file:
        return Path(args.brief_file).read_text(encoding="utf-8-sig")
    if args.brief:
        return args.brief
    return sys.stdin.read()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "next", "self-test", "capture", "compare"))
    parser.add_argument("--brief")
    parser.add_argument("--brief-file")
    parser.add_argument("--plan")
    parser.add_argument("--done", action="append", default=[])
    parser.add_argument("--playbooks")
    parser.add_argument("--role", default="wear", choices=("wear", "dev"))
    parser.add_argument("--task")
    parser.add_argument("--invoke", action="store_true")
    parser.add_argument("--source-json")
    parser.add_argument("--before")
    parser.add_argument("--after")
    parser.add_argument("--root")
    args = parser.parse_args(argv)
    book = Path(args.playbooks) if args.playbooks else None
    root = Path(args.root).resolve() if args.root else repo_root()
    if args.action == "self-test":
        result = self_test()
        print(json.dumps(result, ensure_ascii=True))
        return 0 if result["ok"] else 1
    if args.action == "plan":
        print(json.dumps(plan(read_brief(args), book), ensure_ascii=True))
        return 0
    if args.action == "capture":
        source = Path(args.source_json) if args.source_json else None
        result = capture(root, role=args.role, task=args.task, invoke=args.invoke, source=source)
        print(json.dumps(result, ensure_ascii=True))
        return 0 if result.get("ok") or result.get("status") == "not-running" else 1
    if args.action == "compare":
        if not args.before or not args.after:
            raise SystemExit("before-and-after-required")
        before = json.loads(Path(args.before).read_text(encoding="utf-8-sig"))
        after = json.loads(Path(args.after).read_text(encoding="utf-8-sig"))
        print(json.dumps(compare(before, after), ensure_ascii=True))
        return 0
    if not args.plan:
        raise SystemExit("plan-json-required")
    doc = json.loads(Path(args.plan).read_text(encoding="utf-8-sig"))
    done = []
    for item in args.done:
        done.extend(part for part in item.split(",") if part)
    print(json.dumps(next_phase(doc, done), ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
