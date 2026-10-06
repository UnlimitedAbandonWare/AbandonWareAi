#!/usr/bin/env python3
"""Offline probe: unhooked-seams isolation invariants.

Contract under test (docs/design/UNHOOKED_SEAMS_CONTRACT_SPEC.md, Devin
companion directive 20261005; product Java stays Codex-owned):

  S1 Meta Ray-Ban Display - lens output only via server-scoped
     focusAnswerLengthChars path; display keys never leak into main /chat
     DTO/defaults/yml.
  S2 ASR - PCM only via /api/assist/display/audio/* behind audioGuard +
     paired_phone_required + requireProducer; lens endpoints stay text-only.
  S3 Graph RAG - vector/bm25/graph/web lanes fuse independently; one lane's
     failure contributes empty candidates, never aborts the fusion.
  S4 Jev - Vercel AI Gateway evaluate-only assist; narrowing hints only;
     every failure falls back to the deterministic baseline.

The probe asserts shipped invariants as hard checks and reports
not-yet-landed policy gaps (verifier HOLD-on-unknown, HELD_NOTICE total body
replacement, citation-gate prompt starvation) as warnings, never violations.

Modes:
  --dry-run           run the static invariant assertions (default)
  --scan-sources      emit per-seam findings (same checks, verbose listing)
  --simulate-seams    model lane/Jev/ASR/verifier failures on synthetic state
  --report [PATH]     write JSON report (default
                      data/agent-handoff/unhooked-seams-guard/report.json)
  --json              JSON stdout

Exit codes:
  0 = all hard invariants hold (warnings may be present)
  3 = a hard invariant is violated
  2 = indeterminate (required file missing / unrecognized shape)
  1 = usage error

Read-only and offline. Output carries only paths, line numbers, and marker
names -- never file contents beyond the matched markers.
"""

import argparse
import json
import re
import sys
from pathlib import Path

JAVA = Path("main/java/com/example/lms")
JEV_CLIENT = JAVA / "assist/JevGatewayClient.java"
JEV_GATE = JAVA / "service/rag/handler/JevRetrievalGateHandler.java"
DISPLAY_CTRL = JAVA / "assist/DisplayConversateController.java"
ASR_BRIDGE = JAVA / "assist/ConversateAsrBridge.java"
ORCH = JAVA / "service/rag/orchestrator/UnifiedRagOrchestrator.java"
FUSER = JAVA / "service/rag/fusion/WeightedReciprocalRankFuser.java"
CWF = JAVA / "service/ChatWorkflow.java"
RENDERER = JAVA / "orchestration/control/RagControlProjectionRenderer.java"
CCC = JAVA / "service/ChatConversationContext.java"
CHAT_DTO = JAVA / "dto/ChatRequestDto.java"
CHAT_DEFAULTS = JAVA / "config/ChatDefaultsProperties.java"
CANCEL_HANDLER = JAVA / "api/ChatCancellationCommandHandler.java"
RUN_REGISTRY = JAVA / "service/chat/ChatRunRegistry.java"
RUN_CTX = JAVA / "service/chat/ChatRunExecutionContext.java"
CHAT_API = JAVA / "api/ChatApiController.java"
PROMO = JAVA / "service/rag/RagEvidenceAttributionService.java"
SPB = JAVA / "prompt/StandardPromptBuilder.java"
LENS_PREFS = JAVA / "assist/LensDisplayPrefs.java"

APP_YML = Path("main/resources/application.yml")
APP_LLM = Path("main/resources/application-llm.yaml")
APP_META = Path("main/resources/application-meta-display.yml")

SPEC_DOC = Path("docs/design/UNHOOKED_SEAMS_CONTRACT_SPEC.md")
RULE_DOCS = (
    Path("docs/agents-rules/DEMO1-VOICE-TRANSCRIPTION-SEAM.md"),
    Path("docs/agents-rules/DEMO1-JEV-RERANK-BOUNDARY.md"),
    Path("docs/agents-rules/DEMO1-GRAPH-RAG-HYBRID-SEAM.md"),
)

# Markers that must never appear in the main /chat configuration surface.
DISPLAY_LEAK_PATTERNS = [re.compile(p) for p in (
    r"focusAnswerLengthChars", r"quickAnswerEnabled", r"lensSettings",
    r"hintTargetChars", r"hintTtlMs", r"transcriptTtlMs", r"autoPageMs",
    r"cueCooldownMs", r"triggerQuietMs", r"forceAfterMs")]


def _read(root, rel):
    p = Path(root) / rel
    try:
        return p.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None


def _hits(text, patterns):
    """Return offending marker names (empty = clean)."""
    return [p.pattern for p in patterns if p.search(text)]


def check_jev(texts):
    """Hard assertions on the Jev boundary. Returns (checks, indeterminate)."""
    checks = []
    client = texts.get("jev_client")
    if client is None:
        return checks, True
    checks.append(("jev-eval-only-endpoint",
                   "/v1/evaluate" in client and "never requests text generation" in client,
                   "evaluate-only transport contract"))
    checks.append(("jev-no-generate-path",
                   "/chat/completions" not in client and "/v1/generate" not in client,
                   "no text-generation endpoint in the client"))
    checks.append(("jev-redirect-never",
                   "Redirect.NEVER" in client,
                   "Authorization header cannot leak via redirects"))
    gate = texts.get("jev_gate")
    if gate is None:
        return checks, True
    checks.append(("jev-delegate-passthrough",
                   "delegate.handle(query,accumulator)" in gate,
                   "disabled/absent-scope path always collects candidates"))
    narrow = "hints.put(\"allowWeb\",false)" in gate or "hints.put(\"useWebSearch\",false)" in gate
    promote = ("hints.put(\"allowWeb\",true)" in gate or "hints.put(\"useWebSearch\",true)" in gate
               or "hints.put(\"retrieval.web.enabled\",true)" in gate)
    checks.append(("jev-narrow-only",
                   narrow and not promote,
                   "hints only narrow permissions, never promote to true"))
    checks.append(("jev-baseline-failsoft",
                   "\"timeout\"" in gate and "\"budget_skip\"" in gate
                   and "\"ok\".equals(state.result.reasonCode())" in gate,
                   "non-ok observations keep the deterministic baseline"))
    checks.append(("jev-no-main-authority-for-focus",
                   "JevDecisionScope.capture()!=null" in gate,
                   "parent focus/cue requests never mint main authority"))
    return checks, False


def check_asr(texts):
    """Hard assertions on the ASR/lens boundary."""
    checks = []
    ctrl = texts.get("display_ctrl")
    if ctrl is None:
        return checks, True
    checks.append(("asr-audio-gated",
                   "audioGuard()" in ctrl and "display_audio_disabled" in ctrl
                   and "asr_disabled" in ctrl
                   and "CONVERSATE_DISPLAY_AUDIO_ENABLED:false" in ctrl,
                   "PCM endpoints double-gated, default off"))
    checks.append(("asr-producer-binding",
                   "paired_phone_required" in ctrl and "requireProducer" in ctrl,
                   "only the paired producer client can push PCM"))
    checks.append(("lens-text-endpoints",
                   "/api/assist/display/lens" in ctrl,
                   "lens poll/text endpoints exist"))
    pcm_lines = [i for i, ln in enumerate(ctrl.splitlines())
                 if re.search(r"\.pcm\(\)|request\.pcm\b", ln)]
    audio_start = ctrl.find('"/api/assist/display/audio/start"')
    checks.append(("lens-no-pcm-decode",
                   bool(pcm_lines) and audio_start >= 0 and all(
                       n > ctrl[:audio_start].count("\n") for n in pcm_lines),
                   "pcm() decode confined to the audio handler block"))
    bridge = texts.get("asr_bridge")
    if bridge is None:
        return checks, True
    checks.append(("asr-single-bridge",
                   "@ConditionalOnProperty" in bridge and "conversate.enabled" in bridge,
                   "one bridge owns ASR transport selection"))
    checks.append(("asr-input-loss-failsoft",
                   "ASR_INPUT_LOST" in bridge,
                   "audio loss degrades to text, never kills the session"))
    return checks, False


def check_graph(texts):
    """Hard assertions on the hybrid RAG lane boundary."""
    checks = []
    orch = texts.get("orch")
    if orch is None:
        return checks, True
    checks.append(("graph-lane-separation",
                   "List<Doc> vector" in orch and "List<Doc> bm25" in orch
                   and "List<Doc> fused" in orch,
                   "lane candidate lists recorded separately"))
    checks.append(("graph-lane-failsoft",
                   "toDocsOrEmpty" in orch and "VECTOR-EMERGENCY" in orch,
                   "lane failure converges to empty docs + fallback retry"))
    checks.append(("graph-vector-not-required-default",
                   "retrieval.vector.required" in orch
                   and "${retrieval.vector.required:false}" in orch,
                   "vector lane absence is fail-soft by default"))
    fuser = texts.get("fuser")
    if fuser is None:
        return checks, True
    checks.append(("fuser-stage-failsoft",
                   "fail-soft stage=" in fuser and "traceRrf" in fuser,
                   "fusion stages wrapped fail-soft with rrf tracing"))
    return checks, False


def check_release_cancel(texts):
    """Hard assertions on release-gate and cancel machinery presence."""
    checks = []
    cwf = texts.get("cwf")
    if cwf is None:
        return checks, True
    checks.append(("release-gate-anchor",
                   "applyFinalVerificationReleaseGate" in cwf
                   and "!outcomeKnown" in cwf
                   and "verification_outcome_unknown" in cwf,
                   "final verification release gate anchor"))
    checks.append(("unavailable-release-salvage",
                   "applyUnavailableVerificationRelease" in cwf
                   and "verification_unavailable_excerpt" in cwf,
                   "UNVERIFIED release pattern exists as precedent"))
    renderer = texts.get("renderer")
    if renderer is None:
        return checks, True
    checks.append(("projection-renderer-anchor",
                   "HELD_NOTICE" in renderer and "shouldStop()" in renderer
                   and "TABLE_MARKER" in renderer,
                   "rag-control projection anchor"))
    cancel = texts.get("cancel_handler")
    reg = texts.get("registry")
    ctx = texts.get("run_ctx")
    api = texts.get("chat_api")
    if cancel is None or reg is None or ctx is None or api is None:
        return checks, True
    checks.append(("cancel-exact-token",
                   "cancelExact" in cancel and "cancelExact" in reg,
                   "exact-token cancellation at the boundary"))
    checks.append(("cancel-terminal-single",
                   "finishCancellation" in reg and "terminalEventClaimed" in reg
                   and "tryEmitComplete" in reg,
                   "one terminal event then sink completion"))
    checks.append(("cancel-generation-checkpoint",
                   "throwIfCancelled" in ctx,
                   "generation checkpoints abort on cancel"))
    checks.append(("cancel-no-persist",
                   "cancelledBeforePersist" in api,
                   "session/memory persist suppressed after cancel"))
    return checks, False


def check_leak(texts):
    """Display settings must not leak into main /chat surfaces."""
    checks = []
    for key, name, rel in (
            ("chat_dto", "no-display-leak-dto", CHAT_DTO),
            ("chat_defaults", "no-display-leak-defaults", CHAT_DEFAULTS),
            ("app_yml", "no-display-leak-app-yml", APP_YML),
            ("app_llm", "no-display-leak-llm-yml", APP_LLM)):
        text = texts.get(key)
        if text is None:
            return checks, True
        checks.append((name, not _hits(text, DISPLAY_LEAK_PATTERNS),
                       "main /chat surface free of display/lens keys: %s" % rel))
    meta = texts.get("app_meta")
    if meta is None:
        return checks, True
    checks.append(("display-config-home",
                   "display-ttl-ms" in meta,
                   "display keys live in application-meta-display.yml"))
    ccc = texts.get("ccc")
    if ccc is None:
        return checks, True
    checks.append(("ccc-server-scoped",
                   "Never deserialized from a public request" in ccc
                   and "invalid_focus_answer_length" in ccc,
                   "focus length stays server-scoped and range-validated"))
    lens = texts.get("lens_prefs")
    if lens is None:
        return checks, True
    checks.append(("lens-prefs-bounded",
                   "MIN_FORCE_AFTER_MS" in lens and "MAX_FORCE_AFTER_MS" in lens
                   and "180_000" in lens,
                   "lens prefs keep factory cue defaults incl. 180s force-after"))
    return checks, False


def check_docs(root):
    checks = []
    checks.append(("spec-doc-present", (Path(root) / SPEC_DOC).is_file(),
                   str(SPEC_DOC)))
    for rel in RULE_DOCS:
        checks.append(("rule-doc-%s" % rel.stem.lower(),
                       (Path(root) / rel).is_file(), str(rel)))
    return checks, False


def scan(root):
    texts = {k: _read(root, rel) for k, rel in (
        ("jev_client", JEV_CLIENT), ("jev_gate", JEV_GATE),
        ("display_ctrl", DISPLAY_CTRL), ("asr_bridge", ASR_BRIDGE),
        ("orch", ORCH), ("fuser", FUSER), ("cwf", CWF),
        ("renderer", RENDERER), ("cancel_handler", CANCEL_HANDLER),
        ("registry", RUN_REGISTRY), ("run_ctx", RUN_CTX),
        ("chat_api", CHAT_API), ("chat_dto", CHAT_DTO),
        ("chat_defaults", CHAT_DEFAULTS), ("app_yml", APP_YML),
        ("app_llm", APP_LLM), ("app_meta", APP_META),
        ("ccc", CCC), ("lens_prefs", LENS_PREFS),
        ("promo", PROMO), ("spb", SPB))}
    checks, warnings, ind = [], [], False
    for fn in (lambda: check_jev(texts), lambda: check_asr(texts),
               lambda: check_graph(texts), lambda: check_release_cancel(texts),
               lambda: check_leak(texts), lambda: check_docs(root)):
        c, i = fn()
        checks.extend(c)
        ind = ind or i

    cwf = texts.get("cwf")
    if cwf is not None:
        m = re.search(r"if \(!outcomeKnown\)\s*\{([\s\S]{0,800}?)\}", cwf)
        block = m.group(1) if m else ""
        if m and "verification_outcome_unknown" in block and "safeCandidate" not in block:
            warnings.append({
            "kind": "outcome-unknown-hold-live",
            "detail": "!outcomeKnown branch returns HOLD/releaseAllowed=false; "
                      "policy DEMO1-EVIDENCE-ZERO-RELEASE expects body release "
                      "+ UNVERIFIED flag (FOR_CODEX gapfill pending)",
            "expected": "Codex patch; contract pinned by "
                        "test_rag_context_starvation_contract.py"})
    renderer = texts.get("renderer")
    if renderer is not None and "shouldStop() ? HELD_NOTICE" in renderer:
        warnings.append({
            "kind": "held-notice-total-replacement",
            "detail": "plan.shouldStop() replaces the whole body with "
                      "HELD_NOTICE instead of appending a hold flag",
            "expected": "append-only projection; Codex rag-nonmodel-release "
                        "lane owns the seam"})
    promo = texts.get("promo")
    if promo is not None and "CITATION_GATE_BLOCKED" in promo:
        warnings.append({
            "kind": "promotion-starvation-window",
            "detail": "citation gate can empty promoted evidence while raw "
                      "web/vector hits exist -> prompt sees web:0 vector:0",
            "expected": "demoted-as-unverified injection per spec R2"})
    reg = texts.get("registry")
    if reg is not None:
        warnings.append({
            "kind": "cancel-late-frame-window",
            "detail": "non-terminal frames between cancelExact and "
                      "finishCancellation are not individually fenced",
            "expected": "verify with Codex codex-timeout-split lane"})
    return checks, warnings, ind


def simulate():
    """Pure-python model of the seam failure contracts.

    Returns (checks, warnings). Models the REQUIRED behavior; a live tree
    diverging from it is the FOR_CODEX gapfill, not a probe failure."""
    checks, warnings = [], []

    def fuse(lanes):
        out = []
        for lane in lanes:
            out.extend(lane.get("candidates", []))
        return out

    surviving = fuse([{"name": "vector", "candidates": []},
                      {"name": "bm25", "candidates": ["b1", "b2"]},
                      {"name": "web", "candidates": ["w1"]}])
    checks.append(("sim-lane-failure-preserves",
                   surviving == ["b1", "b2", "w1"],
                   "one lane down -> other lanes' candidates still fused"))

    def jev_route(evaluate, baseline):
        try:
            verdict = evaluate()
            if verdict is None:
                return baseline
            return verdict if verdict in ("NONE", "LIGHT", "DEEP") else baseline
        except Exception:
            return baseline

    checks.append(("sim-jev-down-keeps-baseline",
                   jev_route(lambda: 1 / 0, "BASELINE") == "BASELINE",
                   "evaluate() exception -> baseline decision executes"))
    checks.append(("sim-jev-invalid-keeps-baseline",
                   jev_route(lambda: "BOGUS", "BASELINE") == "BASELINE",
                   "invalid verdict -> baseline retained"))

    def lens_view(audio_enabled, has_producer):
        if not audio_enabled:
            return {"audio": "display_audio_disabled", "text": "delivered"}
        if not has_producer:
            return {"audio": "paired_phone_required", "text": "delivered"}
        return {"audio": "streaming", "text": "delivered"}

    checks.append(("sim-asr-off-text-survives",
                   lens_view(False, False)["text"] == "delivered",
                   "audio disabled -> lens text path unaffected"))
    checks.append(("sim-asr-guard-order",
                   lens_view(False, False)["audio"] == "display_audio_disabled"
                   and lens_view(True, False)["audio"] == "paired_phone_required",
                   "disabled flag and producer binding both gate"))

    def release_gate(outcome_known, status, body="ANSWER_BODY"):
        # REQUIRED contract (spec R1): unknown outcome releases the body with
        # an UNVERIFIED flag and denies memory write; known verdicts keep the
        # existing accept/reject shape.
        if not outcome_known:
            return {"content": body, "releaseStatus": "UNVERIFIED",
                    "releaseAllowed": True, "knowledgeWriteAllowed": False,
                    "flag": "fail_soft"}
        if status == "pass":
            return {"content": body, "releaseStatus": "APPROVE",
                    "releaseAllowed": True, "knowledgeWriteAllowed": True,
                    "flag": None}
        return {"content": "evidence_needed", "releaseStatus": "HOLD",
                "releaseAllowed": False, "knowledgeWriteAllowed": False,
                "flag": None}

    rel = release_gate(False, "unknown")
    checks.append(("sim-outcome-unknown-releases",
                   rel["releaseAllowed"] and rel["content"] == "ANSWER_BODY"
                   and not rel["knowledgeWriteAllowed"],
                   "required contract: fail-soft releases body, denies memory"))
    warnings.append({
        "kind": "sim-live-divergence",
        "detail": "live Java gate currently HOLDs on !outcomeKnown; the "
                  "release-body contract above is the FOR_CODEX target",
        "severity": "warning"})
    return checks, warnings


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--scan-sources", action="store_true")
    ap.add_argument("--simulate-seams", action="store_true")
    ap.add_argument("--report", nargs="?", const=str(
                        Path("data/agent-handoff/unhooked-seams-guard/report.json")))
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()

    if not (args.dry_run or args.scan_sources or args.simulate_seams):
        args.dry_run = True

    checks, warnings, ind = [], [], False
    if args.dry_run or args.scan_sources:
        c, w, ind = scan(root)
        checks.extend(c)
        warnings.extend(w)
    if args.simulate_seams:
        c, w = simulate()
        checks.extend(c)
        warnings.extend(w)

    failed = [n for n, ok, _ in checks if not ok]
    code = 2 if ind else (3 if failed else 0)
    out = {"schemaVersion": "awx.unhooked-seams-guard.v1",
           "root": str(root), "mode": {
               "dryRun": args.dry_run, "scanSources": args.scan_sources,
               "simulateSeams": args.simulate_seams},
           "assertions": len(checks), "failed": failed,
           "checks": [{"name": n, "ok": ok, "detail": d}
                      for n, ok, d in checks],
           "warnings": warnings, "exit": code}

    rep = args.report
    if rep is not None:
        rp = Path(rep)
        if not rp.is_absolute():
            rp = root / rp
        rp.parent.mkdir(parents=True, exist_ok=True)
        rp.write_text(json.dumps(out, indent=2, sort_keys=True),
                      encoding="utf-8")
        out["reportPath"] = str(rp)

    if args.json:
        print(json.dumps(out, indent=2, sort_keys=True))
    else:
        label = {0: "ISOLATED", 3: "VIOLATION", 2: "INDETERMINATE"}[code]
        print("%s assertions=%d failed=%d warnings=%d"
              % (label, len(checks), len(failed), len(warnings)))
        for n, ok, d in checks:
            if not ok:
                print("  FAIL %s -- %s" % (n, d))
            elif args.scan_sources:
                print("  ok   %s -- %s" % (n, d))
        for w in warnings:
            print("  WARN %s -- %s" % (w["kind"], w["detail"]))
        if rep:
            print("  report=%s" % out["reportPath"])
    return code


if __name__ == "__main__":
    sys.exit(main())
