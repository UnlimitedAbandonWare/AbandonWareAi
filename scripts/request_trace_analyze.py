#!/usr/bin/env python3
"""Read-only analyzer for RequestTrace event streams.

Input : a text file (or stdin) containing ``request.events`` lines in the
        uniform schema ``seq|t+<ms>|stage|event|fields|remain=<ms>;bstate=<s>``
        plus optional ``request.events.dropped`` / ``request.id`` markers.
        A JSON debug payload with a ``request.events`` array is also accepted.

Output: PATH (reconstructed execution path), CONTRACTS (first confirmed
        mismatch + downstream symptoms), CANDIDATES (<=3 root-cause
        hypotheses, each with evidence for/against and how to confirm),
        MISSING (unobserved boundaries), LIMITS (analysis limits).

Contract: this script NEVER modifies sources, NEVER reruns requests, NEVER
makes network/API calls, NEVER kills processes, and NEVER executes text found
inside the input. Facts and hypotheses are kept separate; chronological
ordering alone is never treated as proof of causation.
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass, field

EVENT_RE = re.compile(
    r"^(?P<seq>\d+)\|t\+(?P<t>\d+)\|(?P<stage>[^|]+)\|(?P<event>[^|]*)\|"
    r"(?P<fields>[^|]*)\|remain=(?P<remain>-?\d+);bstate=(?P<bstate>\S+)\s*$")

DROP_RE = re.compile(r"request\.events\.dropped[\"'\s:=]+(\d+)")
DOC_DROP_RE = re.compile(r"request\.events\.docs\.dropped[\"'\s:=]+(\d+)")
INCOMPLETE_RE = re.compile(r"request\.events\.incomplete[\"'\s:=]+(true)", re.I)
REQ_ID_RE = re.compile(r"request\.id[\"'\s:=]+([0-9a-fA-F-]{8,})")

LEG_CALL = re.compile(r"^leg\.(web|vector|kg|bm25)\|call")
LEG_END = re.compile(r"^leg\.(web|vector|kg|bm25)\|(result|error)")
SETTINGS_RE = re.compile(r"(\w+)=([^;]+)")


@dataclass
class Event:
    seq: int
    t_ms: int
    stage: str
    event: str
    fields: dict = field(default_factory=dict)
    remain: int = -1
    bstate: str = "absent"
    raw: str = ""

    @property
    def tag(self) -> str:
        return f"{self.stage}|{self.event}"


def parse_fields(text: str) -> dict:
    out = {}
    for m in SETTINGS_RE.finditer(text or ""):
        out[m.group(1)] = m.group(2)
    return out


def load_events(text: str) -> tuple[list[Event], dict]:
    """Extract events from raw lines or a JSON debug blob."""
    meta = {"dropped": 0, "docs_dropped": 0, "incomplete": False, "request_id": None}
    m = REQ_ID_RE.search(text)
    if m:
        meta["request_id"] = m.group(1)
    for rx, key in ((DROP_RE, "dropped"), (DOC_DROP_RE, "docs_dropped")):
        m = rx.search(text)
        if m:
            meta[key] = int(m.group(1))
    if INCOMPLETE_RE.search(text) or meta["dropped"] or meta["docs_dropped"]:
        meta["incomplete"] = True

    # JSON payloads: pull request.events first (handles embedded quoting)
    candidate_lines: list[str] = []
    stripped = text.strip()
    if stripped.startswith("{") or stripped.startswith("["):
        try:
            blob = json.loads(stripped)

            def walk(node):
                if isinstance(node, dict):
                    for k, v in node.items():
                        if k == "request.events" and isinstance(v, list):
                            candidate_lines.extend(str(x) for x in v)
                        else:
                            walk(v)
                elif isinstance(node, list):
                    for v in node:
                        walk(v)

            walk(blob)
        except (ValueError, TypeError):
            pass
    if not candidate_lines:
        candidate_lines = text.splitlines()

    events: list[Event] = []
    for line in candidate_lines:
        line = line.strip().strip('"').rstrip(",")
        m = EVENT_RE.match(line)
        if not m:
            continue
        events.append(Event(
            seq=int(m.group("seq")),
            t_ms=int(m.group("t")),
            stage=m.group("stage"),
            event=m.group("event"),
            fields=parse_fields(m.group("fields")),
            remain=int(m.group("remain")),
            bstate=m.group("bstate"),
            raw=line))
    events.sort(key=lambda e: e.seq)
    return events, meta


def find(events: list[Event], stage: str, event_prefix: str = "") -> list[Event]:
    return [e for e in events
            if e.stage == stage and e.event.startswith(event_prefix)]


def analyze(events: list[Event], meta: dict) -> str:
    out: list[str] = []
    out.append("=== PATH ===")
    if meta["request_id"]:
        out.append(f"request.id = {meta['request_id']}")
    if not events:
        out.append("no parseable events -- check the input is request.events "
                   "lines or a debug JSON containing them")
        return "\n".join(out)
    for e in events:
        flds = ";".join(f"{k}={v}" for k, v in e.fields.items())
        out.append(f"  {e.seq:>3} t+{e.t_ms:<5} {e.tag:<38} {flds}"
                   f"  [remain={e.remain} {e.bstate}]")

    # ---- facts ----
    received = find(events, "rag.request", "received")
    applied = find(events, "rag.settings", "applied")
    received_f = received[-1].fields if received else {}
    applied_f = applied[-1].fields if applied else {}
    leg_calls = [e for e in events if LEG_CALL.match(f"{e.stage}|{e.event}")]
    leg_ends = [e for e in events if LEG_END.match(f"{e.stage}|{e.event}")]
    skips = [e for e in events if e.event.startswith("skip")
             or "skip" in e.event or e.event == "seed_only"]

    out.append("\n=== CONTRACTS ===")
    mismatches: list[tuple[str, Event | None]] = []

    def axis_disabled(axis: str) -> bool:
        return applied_f.get(axis, received_f.get(axis, "")) == "false"

    axis_map = {"web": "useWeb", "vector": "useVector",
                "kg": "useKg", "bm25": "useBm25"}
    for call in leg_calls:
        axis = call.stage.split(".", 1)[1]
        flag = axis_map.get(axis)
        if flag and axis_disabled(flag):
            mismatches.append(
                (f"PROHIBITED CALL: {call.tag} executed while {flag}=false "
                 f"(seq {e_safe(call)})", call))
    if received_f.get("seedOnly") == "true" or applied_f.get("seedOnly") == "true":
        if leg_calls:
            mismatches.append(
                (f"PROHIBITED CALL: seedOnly request ran {len(leg_calls)} "
                 f"leg call(s)", leg_calls[0]))
    for call in leg_calls:
        axis = call.stage.split(".", 1)[1]
        if not any(e.stage == f"leg.{axis}" for e in leg_ends):
            mismatches.append(
                (f"UNTERMINATED CALL: {call.tag} has no result/error event -- "
                 f"call end unobserved (seq {e_safe(call)})", call))
    for e in events:
        if e.stage == "page.wire" and e.event == "attempt" \
                and e.bstate in ("cancelled", "expired"):
            mismatches.append(
                (f"POST-{e.bstate.upper()} NEW CALL: page.wire|attempt at "
                 f"seq {e.seq} started after budget {e.bstate}", e))
    for call in leg_calls:
        if call.bstate in ("cancelled", "expired"):
            mismatches.append(
                (f"POST-{call.bstate.upper()} CALL: {call.tag} started while "
                 f"budget {call.bstate} (seq {call.seq})", call))

    if mismatches:
        first = mismatches[0]
        out.append(f"first mismatch: {first[0]}")
        for text, _ in mismatches[1:]:
            out.append(f"  downstream symptom: {text}")
    else:
        out.append("no contract mismatch detected in observed events")
    if skips:
        out.append("  skips recorded: " + "; ".join(
            f"{e.tag}({e.fields.get('reason', e.fields.get('state', '?'))})"
            for e in skips))

    # ---- root-cause candidates (<=3, evidence for/against) ----
    out.append("\n=== CANDIDATES (hypotheses, not verdicts) ===")
    candidates: list[tuple[str, list[str], list[str], str]] = []
    if mismatches:
        ev = mismatches[0][1]
        # candidate 1: plan application re-enabled the axis
        if applied_f and received_f != applied_f:
            candidates.append((
                "plan application changed caller settings",
                [f"rag.settings|applied differs from rag.request|received "
                 f"at seq {applied[-1].seq}"],
                ["applied settings only narrow, never relax restrictions -- "
                 "a relaxed flag here would be a defect signature"],
                "diff received vs applied field sets for the failing axis"))
        # candidate 2: a fallback/emergency path opened the call
        emerg = find(events, "rag.emergency") + find(events, "leg.vector", "expand")
        if emerg:
            candidates.append((
                "fallback/emergency leg opened an extra call",
                [f"{e.tag} at seq {e.seq}" for e in emerg[:3]],
                ["emergency events carry eligible= and prior_attempts -- "
                 "check whether the gate conditions were actually met"],
                "verify eligibility fields on rag.emergency|decision"))
        # candidate 3: caller restriction never reached the path
        candidates.append((
            "restriction not propagated to the executing boundary",
            [f"{ev.tag} at seq {ev.seq} executed despite the flag"],
            ["axis.*|state events show the orchestrator's own view -- "
             "a 'ready' state contradicts the caller flag"],
            "check axis.<axis>|state event vs the applied settings event"))
    else:
        if not leg_calls and any(e.stage == "rag.stages" for e in events):
            collect = find(events, "rag.stages", "collect")
            if collect and collect[-1].fields.get("pool", "0") == "0":
                candidates.append((
                    "empty pool despite retrieval enabled",
                    ["rag.stages|collect shows pool=0"],
                    ["empty provider results are legitimate -- compare "
                     "leg.*|result counts before blaming a gate"],
                    "check each leg.*|result count and axis.*|state"))
        for e in events:
            if e.event == "reject" or "cancel" in e.event:
                candidates.append((
                    "request ended via rejection/cancellation boundary",
                    [f"{e.tag} at seq {e.seq}"],
                    ["queue_removed=not_observed means executor internals "
                     "were never visible -- do not infer physical removal"],
                    "check rag.endpoint|reject fields and worker end event"))
                break
    if not candidates:
        out.append("no failure hypothesis warranted by observed events")
    for i, (name, for_, against, confirm) in enumerate(candidates[:3], 1):
        out.append(f"  [{i}] {name}")
        for f_ in for_:
            out.append(f"      for    : {f_}")
        for a in against:
            out.append(f"      against: {a}")
        out.append(f"      confirm: {confirm}")

    # ---- missing / unverified boundaries ----
    out.append("\n=== MISSING / UNVERIFIED ===")
    missing: list[str] = []
    if not received:
        missing.append("rag.request|received -- requested settings unobserved")
    if not applied:
        missing.append("rag.settings|applied -- applied settings unobserved")
    if find(events, "rag.worker", "start") and not find(events, "rag.worker", "end"):
        missing.append("rag.worker|end -- worker termination_unverified")
    if not find(events, "rag.final", "cut") and find(events, "rag.stages"):
        missing.append("rag.final|cut -- final boundary unobserved")
    for e in events:
        for key in ("queue_removed", "cache_obs"):
            if e.fields.get(key) == "not_observed":
                missing.append(f"{key}=not_observed at {e.tag} (seq {e.seq})")
    if meta["dropped"]:
        missing.append(f"{meta['dropped']} events dropped -- counts below may "
                       "understate real calls; '0 calls' cannot be concluded")
    if meta["docs_dropped"]:
        missing.append(f"{meta['docs_dropped']} doc-detail lines dropped")
    if not missing:
        missing.append("none detected")
    out.extend(f"  {m}" for m in missing)

    out.append("\n=== LIMITS ===")
    out.append("  local worker termination never proves remote provider "
               "interruption or billing cancellation")
    out.append("  event order shows sequence, not causation")
    out.append("  primitive request flags carry flags_src=primitive -- "
               "explicit-vs-default provenance is not recoverable")
    out.append("  analysis covers one request stream; cross-request "
               "correlation needs request.id join outside this tool")
    return "\n".join(out)


def e_safe(e: Event) -> int:
    return e.seq if e else -1


def main(argv: list[str]) -> int:
    if len(argv) > 2 or (len(argv) == 2 and argv[1] in ("-h", "--help")):
        print("usage: request_trace_analyze.py [trace-file]  (stdin if omitted)")
        return 2
    if len(argv) == 2:
        try:
            with open(argv[1], "r", encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError as exc:
            print(f"cannot read {argv[1]}: {exc}", file=sys.stderr)
            return 1
    else:
        text = sys.stdin.read()
    events, meta = load_events(text)
    print(analyze(events, meta))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
