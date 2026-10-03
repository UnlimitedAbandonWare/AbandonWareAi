#!/usr/bin/env python3
"""settings_defaults_sse_observe -- extract the ACTUAL provider model from a
saved SSE stream or a chat_rag_golden_browser result.json.

Input: --input FILE (raw SSE text/bytes, or a result.json with a top-level
``results`` list). Output is one JSON object (or a list for result.json
input) with, per stream:

    sentModel         what the client asked for (requestedModel / --sent-model)
    observedModel     ONLY from provider-response model fields
                      (observedModel|actualModel|finalModel keys). Never
                      filled from requestedModel/resolvedModel/defaultChoice -
                      "MISSING" when no provider-response model field exists.
    finalAnswerModel  observed model key on the terminal/final event only
    auxModels         model-looking values under judge/verifier/scorer/embed
                      keys (auxiliary calls, never the final answer)
    declaredModels    requested/selected/configured/effective/resolved model
                      hints found in the stream (reference only)
    fallbackCount / fallbackReason / routeId
    firstToken        first CONTENT event (heartbeat/ACK/metadata-only events
                      excluded): event index + ms when the stream timestamps
    terminal          terminal event type/status seen (or none)
    sseError          error events/payloads embedded inside an HTTP 200 body
    diagnostics       eventCount/dataLineCount/parse counters (mirrors the
                      golden browser capture diagnostics)

This is a thin, dependency-free port of the metadata extraction already done
by scripts/chat_rag_golden_browser.js (safeMetadata/visit) - result.json
inputs are read, never re-implemented.

    observe --input FILE [--sent-model M] [--out FILE]
"""
from __future__ import annotations

import argparse
import codecs
import json
import re
import sys
from pathlib import Path

SCHEMA = "awx.settings-defaults-sse-observe.v1"

OBSERVED_MODEL_KEYS = ("observedModel", "actualModel", "finalModel")
DECLARED_MODEL_KEYS = ("requestedModel", "resolvedModel", "defaultChoice",
                       "selectedModel", "configuredModel", "effectiveModel",
                       "candidateModel", "targetModel", "modelId")
AUX_KEY_RE = re.compile(r"judge|verifier|scorer|grader|critic|aux|embed",
                        re.IGNORECASE)
CONTROL_TYPES = {"heartbeat", "ping", "ack", "metadata", "meta", "status",
                 "session", "init", "hello", "keepalive", "keep-alive",
                 "retry", "id"}
TERMINAL_TYPES = {"final", "done", "complete", "completed", "terminal",
                  "end", "finish", "close", "error"}
CONTENT_KEYS = ("delta", "text", "content", "answer", "body", "token",
                "chunk", "message")
TS_KEYS = ("ts", "time", "at", "timestamp", "epochMs", "epoch_ms")
SECRETISH_RE = re.compile(r"Bearer\s|sk-|token[=]", re.IGNORECASE)


# ---------------------------------------------------------------- sse parse

class SseParser:
    """Incremental SSE parser - feed raw bytes; events may split across
    chunks and multi-byte UTF-8 sequences may split mid-character."""

    def __init__(self):
        self._dec = codecs.getincrementaldecoder("utf-8")(errors="replace")
        self._buf = ""

    def feed(self, data: bytes) -> list[dict]:
        self._buf += self._dec.decode(data)
        events = []
        while True:
            idx = self._buf.find("\n\n")
            idx_rn = self._buf.find("\r\n\r\n")
            cut = -1
            if idx >= 0 and (idx_rn < 0 or idx < idx_rn):
                cut = idx + 2
            elif idx_rn >= 0:
                cut = idx_rn + 4
            if cut < 0:
                break
            block, self._buf = self._buf[:cut], self._buf[cut:]
            ev = _parse_block(block)
            if ev:
                ev["truncated"] = False
                events.append(ev)
        return events

    def flush(self) -> list[dict]:
        tail = self._dec.decode(b"", final=True)
        self._buf += tail
        if not self._buf.strip():
            self._buf = ""
            return []
        ev = _parse_block(self._buf)
        self._buf = ""
        if not ev:
            return []
        ev["truncated"] = True
        return [ev]


def _parse_block(block: str) -> dict | None:
    etype, data = "message", []
    for line in block.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if line.startswith("event:"):
            etype = line[6:].strip() or "message"
        elif line.startswith("data:"):
            data.append(line[5:].lstrip(" "))
    if not data:
        return None
    if not re.match(r"^[A-Za-z0-9_-]{1,48}$", etype):
        etype = "unknown"
    return {"event": etype, "data": "\n".join(data)}


def parse_sse(text: str) -> list[dict]:
    p = SseParser()
    events = p.feed(text.encode("utf-8"))
    events += p.flush()
    return events


# ------------------------------------------------------------- observation

def _visit(value, out: dict, parent: str = "", depth: int = 0):
    """Collect model-ish keys from a decoded JSON payload - same whitelist
    spirit as chat_rag_golden_browser.js safeMetadata()."""
    if depth > 8:
        return
    if isinstance(value, dict):
        for k, item in value.items():
            if not isinstance(k, str):
                continue
            if isinstance(item, (str, int, float, bool)):
                s = str(item)
                if len(s) > 400 or SECRETISH_RE.search(s):
                    continue
                if k in OBSERVED_MODEL_KEYS:
                    out["observed"].append(s)
                elif k in DECLARED_MODEL_KEYS:
                    out["declared"][k] = s
                elif k == "modelUsed":
                    out["claimed"] = s
                elif AUX_KEY_RE.search(k) and isinstance(item, str) and s:
                    out["aux"][k] = s
                elif k == "fallbackCount":
                    out["fallbackCount"] = item
                elif k == "fallbackReason":
                    out["fallbackReason"] = s
                elif k == "routeId":
                    out["routeId"] = s
                elif parent == "generationTermination" and k in (
                        "reason", "status"):
                    out["terminalReason" if k == "reason"
                        else "terminalStatus"] = s
                elif k in ("verificationStatus", "releaseReason",
                           "reasonCode", "sessionId", "traceTurnId"):
                    out[k] = item
                elif k in ("error", "errorMessage", "failureClass"):
                    out["errors"].append({k: s[:240]})
                elif k in TS_KEYS and isinstance(item, (int, float)):
                    out.setdefault("ts", item)
            elif isinstance(item, (dict, list)):
                _visit(item, out, k, depth + 1)
    elif isinstance(value, list):
        for item in value:
            _visit(item, out, parent, depth + 1)


def _payload_text(value) -> str | None:
    if isinstance(value, dict):
        for k in CONTENT_KEYS:
            v = value.get(k)
            if isinstance(v, str) and v.strip():
                return v
    return None


def observe_stream(text: str, sent_model: str | None = None) -> dict:
    events = parse_sse(text)
    diag = {"eventCount": len(events), "dataLineCount": 0,
            "jsonParseSucceeded": 0, "jsonParseFailed": 0,
            "truncatedEvents": 0, "eventTypes": {}}
    acc = {"observed": [], "declared": {}, "claimed": None, "aux": {},
           "fallbackCount": None, "fallbackReason": None, "routeId": None,
           "errors": []}
    first_token = None
    terminal = None
    final_model = None
    first_ts = None
    for i, ev in enumerate(events):
        et = ev["event"]
        diag["eventTypes"][et] = diag["eventTypes"].get(et, 0) + 1
        diag["dataLineCount"] += ev["data"].count("\n") + 1
        if ev.get("truncated"):
            diag["truncatedEvents"] += 1
        payload = None
        try:
            payload = json.loads(ev["data"])
            diag["jsonParseSucceeded"] += 1
        except json.JSONDecodeError:
            diag["jsonParseFailed"] += 1
            if et not in CONTROL_TYPES and isinstance(ev["data"], str) \
                    and ev["data"].strip() and first_token is None:
                first_token = {"eventIndex": i, "event": et, "ms": None}
        local = {"observed": [], "declared": {}, "claimed": None, "aux": {},
                 "errors": [], "ts": None}
        if payload is not None:
            _visit(payload, local)
            for k, v in local.items():
                if k == "observed":
                    acc["observed"].extend(v)
                elif k in ("declared", "aux"):
                    acc[k].update(v)
                elif k == "errors":
                    acc["errors"].extend(v)
                elif v is not None:
                    acc[k] = v
            ts = local.get("ts")
            if ts is not None and first_ts is None:
                first_ts = ts
            if first_token is None and et not in CONTROL_TYPES:
                if _payload_text(payload):
                    first_token = {"eventIndex": i, "event": et,
                                   "ms": (ts - first_ts
                                          if ts is not None
                                          and first_ts is not None else None)}
        if et in TERMINAL_TYPES:
            terminal = {"event": et, "eventIndex": i,
                        "status": acc.get("terminalStatus"),
                        "reason": acc.get("terminalReason"),
                        "verificationStatus": acc.get("verificationStatus")}
            if local["observed"]:
                final_model = local["observed"][-1]
            elif local["claimed"]:
                final_model = final_model or None
    observed = acc["observed"][-1] if acc["observed"] else "MISSING"
    return {
        "schema": SCHEMA,
        "sentModel": sent_model,
        "observedModel": observed,
        "finalAnswerModel": final_model,
        "claimedModel": acc["claimed"],
        "auxModels": acc["aux"],
        "declaredModels": acc["declared"],
        "fallbackCount": acc["fallbackCount"],
        "fallbackReason": acc["fallbackReason"],
        "routeId": acc["routeId"],
        "firstToken": first_token,
        "terminal": terminal,
        "sseError": acc["errors"] or None,
        "diagnostics": diag,
    }


def observe_result_json(doc: dict) -> dict:
    """Thin reader over chat_rag_golden_browser result.json - the extraction
    there is already done; we re-shape, never re-parse."""
    out = []
    for r in doc.get("results") or []:
        meta = r.get("metadata") or {}
        observed = None
        for k in OBSERVED_MODEL_KEYS:
            if meta.get(k):
                observed = meta[k]
        aux = {k: v for k, v in meta.items()
               if AUX_KEY_RE.search(str(k)) and isinstance(v, str)}
        declared = {k: meta[k] for k in DECLARED_MODEL_KEYS if k in meta}
        out.append({
            "schema": SCHEMA,
            "id": r.get("id"), "round": r.get("round"),
            "verdict": r.get("verdict"),
            "sentModel": r.get("requestedModel"),
            "observedModel": observed or "MISSING",
            "finalAnswerModel": observed,
            "claimedModel": meta.get("claimedModel") or meta.get("modelUsed"),
            "auxModels": aux,
            "declaredModels": declared,
            "fallbackCount": meta.get("fallbackCount"),
            "fallbackReason": meta.get("fallbackReason"),
            "routeId": meta.get("routeId"),
            "firstToken": {"ms": r.get("firstBodyMs"), "event": "body"},
            "terminal": {"status": meta.get("terminalStatus"),
                         "reason": meta.get("terminalReason"),
                         "verificationStatus": meta.get("verificationStatus"),
                         "releaseReason": meta.get("releaseReason")},
            "sseError": None,
            "traceProof": r.get("traceProof", {}).get("matched")
            if isinstance(r.get("traceProof"), dict) else None,
            "reasons": r.get("reasons"),
        })
    return {"schema": SCHEMA, "source": "result.json",
            "base": doc.get("base"), "sends": doc.get("sends"),
            "results": out}


def cmd_observe(args) -> int:
    p = Path(args.input)
    try:
        raw = p.read_bytes()
    except OSError as e:
        print(json.dumps({"error": f"input-unreadable:{e}"}))
        return 2
    doc = None
    if p.suffix.lower() == ".json":
        try:
            doc = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            doc = None
    if isinstance(doc, dict) and isinstance(doc.get("results"), list):
        result = observe_result_json(doc)
    else:
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            text = raw.decode("utf-8", errors="replace")
        result = observe_stream(text, sent_model=args.sent_model)
    if args.out:
        Path(args.out).write_text(json.dumps(result, indent=2,
                                             ensure_ascii=False),
                                  encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("observe")
    p.add_argument("--input", required=True)
    p.add_argument("--sent-model")
    p.add_argument("--out")
    args = ap.parse_args(argv)
    return {"observe": cmd_observe}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
