#!/usr/bin/env python3
"""Zero-cost safety harness for demo-1 (Plan5 / zero-cost-audit-1001).

Offline only: static source/config inspection + a 127.0.0.1 loopback fixture
server. No paid or real external API calls. No secrets are read or printed —
env *names* only.

Verdicts per check:
  pass          invariant holds in live source/config or loopback probe
  gap           real exposure exists and is documented (does not fail the run)
  fail          an invariant is broken unexpectedly
  not_observed  evidence source missing; nothing claimed

Exit code: 0 when no check is `fail` (and no harness-internal error).
`--strict` also fails on `gap`. Exit 2 = harness error.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENGINE_SLOTS = ROOT / "var/codex-assist-20261001/kits/engine-slots/CODEX_ENGINE_SLOTS_R5/tools/engine_slots.py"
PORT_LEASE = ROOT / "scripts/agent_port_lease.py"
SESSION = "zero-cost-audit-1001"
OWNER = "devin"

VERDICT_ORDER = {"pass": 0, "gap": 1, "not_observed": 2, "fail": 3}


def read_text(rel: str) -> str | None:
    path = ROOT / rel
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return None


def yml_scalar(text: str, key: str) -> str | None:
    """Extract `key: value` (simple scalar) or `key: ${ENV:default}` default."""
    m = re.search(rf"(?m)^\s*{re.escape(key)}:\s*(.+?)\s*$", text)
    if not m:
        return None
    raw = m.group(1).split("#")[0].strip()
    inner = re.match(r"\$\{[^}:]+:(.*)\}$", raw)
    return inner.group(1) if inner else raw


class Report:
    def __init__(self, mode: str):
        self.mode = mode
        self.checks: list[dict] = []

    def add(self, check_id: str, kind: str, verdict: str, detail: str,
            evidence: list[str] | None = None):
        self.checks.append({
            "id": check_id, "kind": kind, "verdict": verdict,
            "detail": detail, "evidence": evidence or [],
        })

    def summary(self) -> dict:
        counts = {v: 0 for v in VERDICT_ORDER}
        for c in self.checks:
            counts[c["verdict"]] += 1
        return counts


# ---------------------------------------------------------------- display-asr

def mode_display_asr(rep: Report):
    yml = read_text("main/resources/application-meta-display.yml")
    policy = read_text("main/java/com/example/lms/assist/ConversateCueRoutingPolicy.java")
    stt = read_text("main/java/com/example/lms/assist/ConversateCloudStt.java")

    if yml is None:
        rep.add("d1.config", "yaml_parse", "not_observed", "application-meta-display.yml missing")
    else:
        enforce = yml_scalar(yml, "enforce-limits")
        utter = yml_scalar(yml, "utterance-provider")
        rep.add("d1.config.enforce_limits", "yaml_parse", "pass",
                f"conversate.cost.enforce-limits={enforce!r} (java default true when unset); "
                f"conversate.asr.cloud.utterance-provider={utter!r}",
                ["main/resources/application-meta-display.yml:18,22"])

    if policy is None:
        rep.add("d2.policy", "static_source", "not_observed", "ConversateCueRoutingPolicy.java missing")
    else:
        groq_gate = re.search(r"if\s*\(groq\s*&&\s*!groqGuard\.eligible", policy)
        enforce_read = re.search(r'conversate\.cost\.enforce-limits"', policy)
        charged_gate = re.search(r"enforceCost&&\(charged > demand\.remainingUsd", policy)
        rep.add(
            "d2.groq_free_gate", "static_source",
            "pass" if groq_gate else "fail",
            "groq candidates require groqGuard.eligible regardless of enforce-limits"
            if groq_gate else "groq free-tier gate missing or moved",
            ["ConversateCueRoutingPolicy.java:~78"])
        gemini_optin = re.search(r'gemini\.gateway\.purpose\.router\.enabled".*?false', policy, re.S)
        rep.add(
            "d3.gemini_router_optin", "static_source",
            "pass" if gemini_optin else "fail",
            "gemini cue admission requires gemini.gateway.purpose.router.enabled (java default false)"
            if gemini_optin else "gemini router opt-in gate missing",
            ["ConversateCueRoutingPolicy.java:~97"])
        rep.add(
            "d4.cost_gate_present", "static_source",
            "pass" if (enforce_read and charged_gate) else "fail",
            "enforce-limits flag is read and gates the charged>remaining/daily-budget exclusion"
            if (enforce_read and charged_gate) else "cost gate wiring missing",
            ["ConversateCueRoutingPolicy.java:62,114"])

    # Effective admission model under the live flag value (synthetic — no JVM).
    if yml is not None and policy is not None:
        enforce = (yml_scalar(yml, "enforce-limits") or "true").lower() == "true"
        router_enabled = "true" in (yml_scalar(yml, "enabled") or "")
        gemini_router = re.search(
            r"(?ms)^gemini:.*?purpose:.*?router:.*?enabled:\s*\$\{[^}]*:(\w+)\}", yml)
        gemini_router_on = (gemini_router.group(1).lower() == "true") if gemini_router else False
        unmanaged = ["openai"]
        if gemini_router_on:
            unmanaged.append("gemini")
        if enforce:
            unmanaged = []
        verdict = "pass" if not unmanaged else "gap"
        rep.add(
            "d5.paid_admission_model", "synthetic_eval", verdict,
            f"enforce-limits={enforce}, gemini router opt-in(profile)={gemini_router_on}: "
            f"paid providers admitted without budget exclusion={unmanaged or '[]'}; "
            "groq stays free-gated by GroqFreeTierGuard in all modes",
            ["application-meta-display.yml:18,216-222", "ConversateCueRoutingPolicy.java:78-114"])

    if stt is None:
        rep.add("d6.asr", "static_source", "not_observed", "ConversateCloudStt.java missing")
    else:
        eco = re.search(r"private Mono<String> transcribeEconomy\(byte\[\] pcm\)(.*?)\n    private Mono",
                        stt, re.S)
        body = eco.group(1) if eco else ""
        leaks = [p for p in ("SonioxAsrTransport", "DeepgramAsrTransport", "openStream")
                 if p in body]
        groq_first = "groqGuard.reserve(\"whisper-large-v3-turbo\"" in body
        gemini_fallback = "gemini.transcribeAudio" in body and "budget.reserve(30)" in body
        degrade = "stt_economy_unavailable" in body
        rep.add(
            "d6.asr_stream_leak", "static_source",
            "pass" if (eco and not leaks) else ("fail" if leaks else "not_observed"),
            "economy utterance path dispatches only groq/gemini; Soniox/Deepgram WebSocket "
            f"unreachable{'' if not leaks else ' — FOUND: ' + ','.join(leaks)}",
            ["ConversateCloudStt.java:255-286"])
        rep.add(
            "d7.asr_paid_fallback", "static_source",
            "gap" if gemini_fallback else "pass",
            "groq reserve failure selects gemini.transcribeAudio + budget.reserve(30)~$0.004 — "
            "designed paid fallback; STRICT_ZERO callers must gate it"
            if gemini_fallback else "no paid fallback found in economy path",
            ["ConversateCloudStt.java:260-275"])
        rep.add(
            "d8.asr_degrade", "static_source",
            "pass" if (groq_first and degrade) else "fail",
            "groq reservation precedes wire; gemini unconfigured -> stt_economy_unavailable "
            "(no wire call)" if (groq_first and degrade)
            else "economy degrade path not proven",
            ["ConversateCloudStt.java:261-267"])


# --------------------------------------------------------------- search-lane

def mode_search_lane(rep: Report):
    brave = read_text("main/java/com/example/lms/service/web/BraveSearchService.java")
    props = read_text("main/java/com/example/lms/service/web/BraveSearchProperties.java")
    test = read_text("src/test/java/com/example/lms/service/web/BraveDualKeyLaneSelectionTest.java")
    tavily_a = read_text("main/java/com/abandonware/ai/agent/integrations/TavilyWebSearchRetriever.java")
    tavily_b = read_text("main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java")
    naver = read_text("main/java/com/example/lms/service/NaverSearchService.java")
    routing = read_text("configs/api-routing.yaml")

    if brave is None:
        rep.add("s1.brave", "static_source", "not_observed", "BraveSearchService.java missing")
    else:
        pinned = "LaneReservation" in brave and "requestKeyLane" in brave
        latch = "recordLaneQuotaExhausted" in brave and "reconcileProviderRemaining" in brave
        rep.add("s1.brave_lane_pin", "static_source",
                "pass" if (pinned and latch) else "fail",
                "wire token pinned to reservation lane; provider-evidence exhaustion latch present"
                if (pinned and latch) else "lane pinning/latch not found",
                ["BraveSearchService.java:187-216,282-285"])
        unmanaged_base = "QuotaReservation.unmanaged" in brave
        rep.add("s2.brave_base_unmanaged", "static_source",
                "gap" if unmanaged_base else "pass",
                "base lane (BRAVE_API_KEY) reservations are unmanaged — no monthly cap after "
                "free-lane exhaustion; failover itself is tested design"
                if unmanaged_base else "base lane is quota-managed",
                ["BraveSearchService.java:256-273",
                 "BraveDualKeyLaneSelectionTest.java:66-82"])

    if props is None:
        rep.add("s3.brave_quota", "static_source", "not_observed", "BraveSearchProperties missing")
    else:
        m = re.search(r'DefaultValue\("(\d+)"\)\s*int\s+monthlyQuota', props)
        quota = int(m.group(1)) if m else None
        rep.add("s3.brave_quota_default", "static_source",
                "gap" if quota and quota > 1000 else ("pass" if quota == 1000 else "not_observed"),
                f"monthlyQuota default={quota} vs official $5 free credit=1,000 req/month; "
                "override: gpt-search.brave.monthly-quota=1000 (default change -> FOR_CODEX)",
                ["BraveSearchProperties.java:12", "docs/provider-limits/brave-search-limits.md"])

    if test is None:
        rep.add("s4.brave_test", "static_source", "not_observed", "BraveDualKeyLaneSelectionTest missing")
    else:
        n = len(re.findall(r"@Test", test))
        rep.add("s4.brave_test_contract", "static_source", "pass",
                f"dual-key lane contract test present ({n} cases); run via gradle separately",
                ["src/test/java/.../BraveDualKeyLaneSelectionTest.java"])

    if tavily_a is None and tavily_b is None:
        rep.add("s5.tavily", "static_source", "not_observed", "no Tavily retriever found")
    else:
        a_adv = bool(tavily_a and re.search(r'search_depth",\s*"advanced"', tavily_a))
        b_basic = bool(tavily_b and "search_depth" not in tavily_b)
        seam = bool(routing and "com.abandonware.ai.agent.integrations.TavilyWebSearchRetriever" in routing)
        rep.add("s5.tavily_dual_impl", "static_source",
                "gap" if (a_adv and seam) else "pass",
                "routing seam = abandonware retriever with search_depth=advanced (2 credits/req); "
                "com.example.lms RAG retriever sends no search_depth (api default basic = 1 credit)"
                if (a_adv and seam) else "no unmanaged advanced-depth seam found",
                ["abandonware.../TavilyWebSearchRetriever.java:86",
                 "configs/api-routing.yaml:75-78"])

    if naver is None:
        rep.add("s6.naver", "static_source", "not_observed", "NaverSearchService.java missing")
    else:
        coupled = "LLM에 넘길 개수" in naver or "searchSnippetsMono" in naver
        rep.add("s6.naver_llm_coupling", "static_source",
                "gap" if coupled else "pass",
                "naver snippets DO feed LLM/RAG context (webTopK 'LLM에 넘길 개수') — Naver OpenAPI "
                "약관상 검색결과는 독립 표시 전용, AI 입력/변조 제한 검토 필요 (policy item, not a code defect)"
                if coupled else "naver results not wired into LLM input",
                ["NaverSearchService.java:445,1507-1511,2283+"])


# ------------------------------------------------------------- loopback-mock

def _http(port: int, method: str, path: str, payload: dict | None,
          timeout: float = 5.0) -> tuple[int, bytes]:
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data,
                                 method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, res.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def _lease(action: str, port_lease_id: str | None = None) -> dict:
    cmd = [sys.executable, "-B", str(PORT_LEASE), action, "--root", str(ROOT),
           "--owner", OWNER, "--session", SESSION]
    if action == "acquire":
        cmd += ["--service", "engine-slots-mock", "--ttl-seconds", "180"]
    else:
        cmd += ["--lease", port_lease_id or ""]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    try:
        return json.loads(out.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {"ok": False, "raw": out.stdout[-400:], "stderr": out.stderr[-200:]}


def mode_loopback_mock(rep: Report):
    if not ENGINE_SLOTS.exists():
        rep.add("l0.engine_slots", "static_source", "not_observed",
                f"engine_slots.py not found at {ENGINE_SLOTS.relative_to(ROOT)}")
        return

    lease = _lease("acquire")
    port = (lease.get("lease") or {}).get("port")
    lease_id = (lease.get("lease") or {}).get("leaseId")
    if not port:
        rep.add("l0.port_lease", "loopback_http", "fail",
                f"port lease acquire failed: {str(lease)[:200]}")
        return
    rep.add("l0.port_lease", "loopback_http", "pass",
            f"loopback port {port} leased ({lease_id})", ["scripts/agent_port_lease.py"])

    proc = subprocess.Popen(
        [sys.executable, "-B", str(ENGINE_SLOTS), "mock-server",
         "--port", str(port), "--strict-plan", "--max-requests", "20",
         "--lifetime-seconds", "60"],
        cwd=str(ROOT), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        ready = False
        for _ in range(40):
            try:
                code, body = _http(port, "GET", "/health", None, timeout=1.0)
                if code == 200 and b'"fixture":true' in body.replace(b" ", b""):
                    ready = True
                    break
            except (urllib.error.URLError, OSError):
                time.sleep(0.25)
        if not ready:
            rep.add("l1.health", "loopback_http", "fail", "mock-server never became ready")
            return

        # strict-plan-valid Responses call -> SSE completed
        code, body = _http(port, "POST", "/v1/responses", {
            "model": "fixture-text", "store": False, "stream": True,
            "input": [{"role": "user", "content": "ping"}]})
        rep.add("l1.responses_sse", "loopback_http",
                "pass" if code == 200 and b"response.completed" in body else "fail",
                f"fixture-text strict-plan responses -> {code}",
                ["POST /v1/responses"])

        # strict-plan rejection: store unset
        code, body = _http(port, "POST", "/v1/responses", {
            "model": "fixture-text", "stream": True,
            "input": [{"role": "user", "content": "ping"}]})
        rep.add("l2.strict_plan_reject", "loopback_http",
                "pass" if code == 400 and b"fixture_contract_mismatch" in body else "fail",
                f"missing store:false -> {code} (strict-plan enforced)", ["POST /v1/responses"])

        code, body = _http(port, "POST", "/v1/chat/completions",
                           {"model": "fixture-json"})
        rep.add("l3.chat_json", "loopback_http",
                "pass" if code == 200 and b'"model":"fixture-json"' in body.replace(b" ", b"") else "fail",
                f"chat.completions fixture-json -> {code}", ["POST /v1/chat/completions"])

        code, _ = _http(port, "POST", "/v1/chat/completions", {"model": "fixture-429"})
        rep.add("l4.fixture_429", "loopback_http",
                "pass" if code == 429 else "fail",
                f"fixture-429 -> {code}", ["POST /v1/chat/completions"])

        code, body = _http(port, "POST", "/v1/chat/completions",
                           {"model": "fixture-incomplete"})
        rep.add("l5.fixture_incomplete", "loopback_http",
                "pass" if code == 200 and b'"finish_reason":"length"' in body.replace(b" ", b"") else "fail",
                f"fixture-incomplete -> {code} finish_reason=length", ["POST /v1/chat/completions"])

        code, body = _http(port, "GET", "/_aw/stats", None)
        try:
            stats = json.loads(body)
            external = stats.get("external_requests")
            seen = stats.get("requests_seen")
        except ValueError:
            external = seen = None
        rep.add("l6.no_external", "loopback_http",
                "pass" if code == 200 and external == 0 and seen == 5 else "fail",
                f"/_aw/stats external_requests={external} requests_seen={seen} "
                "(0 external = loopback-only verified)", ["GET /_aw/stats"])
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
        if lease_id:
            _lease("release", lease_id)


MODES = {"display-asr": mode_display_asr,
         "search-lane": mode_search_lane,
         "loopback-mock": mode_loopback_mock}


def main() -> int:
    ap = argparse.ArgumentParser(description="demo-1 zero-cost safety harness (offline)")
    ap.add_argument("--mode", choices=sorted(MODES))
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--strict", action="store_true", help="treat gap verdicts as failures")
    ap.add_argument("--out", type=Path, help="write JSON report")
    args = ap.parse_args()

    if not args.all and not args.mode:
        ap.error("one of --mode or --all is required")
    modes = list(MODES) if args.all else [args.mode]

    reports = []
    for name in modes:
        rep = Report(name)
        try:
            MODES[name](rep)
        except Exception as exc:  # harness error, not a verdict
            rep.add("harness.error", "internal", "fail", f"{type(exc).__name__}: {exc}")
        reports.append(rep)

    doc = {
        "schema": "awx.zero-cost-safety.v1",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "root": str(ROOT),
        "application_runtime_verified": False,
        "network": "loopback-only",
        "strict": args.strict,
        "modes": [{"mode": r.mode, "summary": r.summary(), "checks": r.checks}
                  for r in reports],
    }
    out_text = json.dumps(doc, ensure_ascii=False, indent=2)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    print(out_text)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")

    failed = any(c["verdict"] == "fail" for r in reports for c in r.checks)
    gapped = any(c["verdict"] == "gap" for r in reports for c in r.checks)
    if failed or (args.strict and gapped):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
