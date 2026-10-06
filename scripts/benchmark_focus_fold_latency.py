#!/usr/bin/env python3
"""Mock latency benchmark for NovaFocus Fold early-sentence streaming.

Measures the provider -> Fold first-useful latency contract without paid API
calls or physical devices:

  * firstProviderDeltaMs  - ms from request start to first provider delta
  * firstUsefulPublishedMs- ms from request start to first useful sentence
  * terminalMs            - ms from request start to terminal completion
  * sequence dedupe       - cumulative/replayed snapshots append once

Input modes (mutually exclusive):
  --dry-run / --simulate   built-in deterministic synthetic stream (default)
  --events <file.json>     [{"tMs":int,"event":str,"seq":int?,"text":str?}...]
  --sse-file <path>        raw SSE capture ("event:"/"data:" lines; arrival
                           time from data.atMs or first column "ms|line")

Output: data/agent-handoff/devin/BENCHMARK_FOCUS_FOLD_LATENCY.json
(override with --out). Exit 0 on success, 2 on parse/contract failure.

Device/real-provider measurement is out of scope here: this tool only turns an
observed or simulated event stream into receipt metrics. NOT_RUN for Fold6 /
lens hardware is declared by the caller, not by this script.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

SCHEMA = "awx.benchmark-focus-fold-latency.v1"
DEFAULT_OUT = "data/agent-handoff/devin/BENCHMARK_FOCUS_FOLD_LATENCY.json"

# Event vocabulary: names match the display-focus-flow.js contract and the
# NovaFocus provider stream seam. Extra names are preserved, not fatal.
EV_PROVIDER_DELTA = "provider_delta"      # raw model delta (cumulative text)
EV_FIRST_USEFUL = "first_useful"          # first complete sentence boundary
EV_FIRST_VISIBLE = "first_visible"        # first painted unit on the surface
EV_PRESENTATION_DONE = "presentation_done"  # display queue drained
EV_TERMINAL = "terminal"                  # provider stream finished
TERMINAL_ALIASES = {EV_TERMINAL, "done", "complete", "presentation_done"}


def load_events_file(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if isinstance(data, dict):
        data = data.get("events", [])
    if not isinstance(data, list):
        raise ValueError("events file must be a JSON array or {events:[...]}")
    out = []
    for i, row in enumerate(data):
        if not isinstance(row, dict) or "tMs" not in row or "event" not in row:
            raise ValueError(f"event[{i}] missing tMs/event")
        out.append(dict(row))
    return out


def load_sse_file(path: Path) -> list[dict]:
    """Parse a raw SSE capture into the canonical event list.

    Arrival time: `atMs`/`tMs` inside the JSON data payload, else a leading
    `ms|` line prefix, else monotonically assigned 0 (only relative gaps
    remain meaningful and are flagged).
    """
    events: list[dict] = []
    pending_name = None
    saw_timing = False
    for lineno, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw.rstrip("\n")
        if not line or line.startswith(":"):
            continue
        arrival = None
        if "|" in line and line.split("|", 1)[0].strip().isdigit():
            arrival_s, line = line.split("|", 1)
            arrival = int(arrival_s.strip())
            saw_timing = True
        if line.startswith("event:"):
            pending_name = line[6:].strip()
            continue
        if line.startswith("data:"):
            payload = line[5:].strip()
            row = {"event": pending_name or "message"}
            pending_name = None
            try:
                body = json.loads(payload)
                if isinstance(body, dict):
                    row.update(body)
            except json.JSONDecodeError:
                row["text"] = payload
            if arrival is not None:
                row["tMs"] = arrival
            elif "tMs" in row:
                saw_timing = True
            elif "atMs" in row:
                row["tMs"] = row["atMs"]
                saw_timing = True
            else:
                row["tMs"] = 0
            events.append(row)
    if not saw_timing:
        for row in events:
            row["tMsEstimated"] = True
    return events


def synthetic_events() -> list[dict]:
    """Deterministic mock stream mirroring the Codex patch contract.

    Provider emits cumulative snapshots (each `text` is the full answer so
    far). One seq is replayed to prove dedupe. First useful sentence boundary
    lands before terminal. All times are ms since request start.
    """
    full = "노바 포커스 스트리밍 첫 문장입니다. 두 번째 문장이 이어집니다. 마지막 문장입니다."
    events = [
        {"tMs": 0, "event": "request_start"},
        {"tMs": 412, "event": EV_PROVIDER_DELTA, "seq": 1, "text": full[:18]},
        {"tMs": 486, "event": EV_PROVIDER_DELTA, "seq": 2, "text": full[:33]},
        {"tMs": 486, "event": EV_PROVIDER_DELTA, "seq": 2, "text": full[:33], "replayed": True},
        {"tMs": 560, "event": EV_FIRST_USEFUL, "seq": 2, "graphemes": 17},
        {"tMs": 575, "event": EV_FIRST_VISIBLE, "seq": 2},
        {"tMs": 698, "event": EV_PROVIDER_DELTA, "seq": 3, "text": full[:52]},
        {"tMs": 940, "event": EV_PROVIDER_DELTA, "seq": 4, "text": full},
        {"tMs": 940, "event": EV_TERMINAL, "seq": 4, "status": "completed"},
        {"tMs": 940 + len(full) * 80, "event": EV_PRESENTATION_DONE, "seq": 4},
    ]
    return events


def compute_metrics(events: list[dict]) -> dict:
    seen_delta_seqs: set = set()
    all_seqs: set = set()
    applied_text_len = 0
    dedupe_dropped = 0
    ttft = useful = first_visible = terminal = presentation_done = None
    provider_deltas = 0
    for e in events:
        name = e.get("event")
        t = e.get("tMs", 0)
        seq = e.get("seq")
        if seq is not None:
            all_seqs.add(seq)
        if name == EV_PROVIDER_DELTA:
            provider_deltas += 1
            if seq is not None:
                if seq in seen_delta_seqs:
                    dedupe_dropped += 1  # replayed cumulative delta must not re-append
                    continue
                seen_delta_seqs.add(seq)
            applied_text_len += len(e.get("text") or "")
            if ttft is None:
                ttft = t
        elif name == EV_FIRST_USEFUL and useful is None:
            useful = t
        elif name == EV_FIRST_VISIBLE and first_visible is None:
            first_visible = t
        elif name in TERMINAL_ALIASES:
            if name == EV_PRESENTATION_DONE:
                if presentation_done is None:
                    presentation_done = t
            elif terminal is None:
                terminal = t
    ordered = sorted(v for v in (ttft, useful, terminal) if v is not None)
    monotonic = ordered == sorted((ttft or 0, useful or 0, terminal or 0))
    return {
        "firstProviderDeltaMs": ttft,
        "firstUsefulPublishedMs": useful,
        "firstVisibleMs": first_visible,
        "terminalMs": terminal,
        "presentationDoneMs": presentation_done,
        "providerToUsefulMs": (useful - ttft) if ttft is not None and useful is not None else None,
        "usefulToTerminalMs": (terminal - useful) if useful is not None and terminal is not None else None,
        "providerDeltaCount": provider_deltas,
        "sequenceDedupe": {
            "distinctSeqs": len(all_seqs),
            "distinctDeltaSeqs": len(seen_delta_seqs),
            "replayedDeltasDropped": dedupe_dropped,
            "appliedDeltaChars": applied_text_len,
        },
        "ordering": {"usefulBeforeTerminal": monotonic},
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--dry-run", action="store_true", help="synthetic stream (default)")
    src.add_argument("--simulate", action="store_true", help="alias of --dry-run")
    src.add_argument("--events", type=Path, help="JSON event array")
    src.add_argument("--sse-file", type=Path, help="raw SSE capture")
    ap.add_argument("--out", type=Path, default=Path(DEFAULT_OUT))
    ap.add_argument("--runs", type=int, default=1, help="dry-run repetitions")
    args = ap.parse_args()

    mode = "synthetic"
    if args.events:
        events = load_events_file(args.events)
        mode = "events-file"
    elif args.sse_file:
        events = load_sse_file(args.sse_file)
        mode = "sse-capture"
    else:
        events = synthetic_events()

    runs = []
    for _ in range(max(1, args.runs)):
        runs.append(compute_metrics(events))
    metrics = runs[0]
    if len(runs) > 1:
        metrics["runCount"] = len(runs)
        metrics["runsIdentical"] = all(r == runs[0] for r in runs)

    ok = (
        metrics["firstProviderDeltaMs"] is not None
        and metrics["firstUsefulPublishedMs"] is not None
        and metrics["terminalMs"] is not None
        and metrics["ordering"]["usefulBeforeTerminal"]
    )
    receipt = {
        "schemaVersion": SCHEMA,
        "generatedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "mode": mode,
        "eventCount": len(events),
        "metrics": metrics,
        "verdict": "PASS" if ok else "FAIL",
        "scope": {
            "paidApiCalls": 0,
            "productSourceDiff": 0,
            "deviceE2E": "NOT_RUN",
            "note": "mock/receipt metrics only; Fold6+ lens hardware measurement remains NOT_RUN",
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"verdict": receipt["verdict"], "out": str(args.out),
                      "ttft": metrics["firstProviderDeltaMs"],
                      "firstUseful": metrics["firstUsefulPublishedMs"],
                      "terminal": metrics["terminalMs"]}, ensure_ascii=False))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
