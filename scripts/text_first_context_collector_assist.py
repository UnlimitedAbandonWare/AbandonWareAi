#!/usr/bin/env python3
"""Read-only companion checks for the Codex brief
TEXT-FIRST-CONTEXT-COLLECTOR-20261007-v1:
  Preserve same owner/session user conditions, corrections, and verified
  original-source evidence on the existing deterministic context path; an
  optional one-shot Gemini Lite text selection is reviewed only under measured
  pressure/contradiction. WP0-WP4, offline fixtures first; Lite live
  activation stays HOLD (REMOTE_PRIVACY_ADMISSION / LITE_VALUE_NOT_PROVEN).

Devin assist lane (task devin-text-first-context-collector-assist-f2d89b5f):
  hashes      - brief SHA12 pins vs live tree (SAME/DRIFTED/MISSING)
  pins        - brief file:line anchors vs live tree (SAME/MOVED/ABSENT)
  state       - contract phase classify (PINNED_PRE_BRIEF / IN_FLIGHT / cues)
  cover       - acceptance slots A1-A10 -> evidence found in live file/diff
  diff-review - allowed/forbidden path + forbidden-line cues (`git diff`
                or --diff <unified file>); flags are review cues, not verdicts
  lease       - fresh target-scoped lease status with CURRENT hashes
  holds       - contract HOLD ledger (brief section 7) - static card
  snapshot    - write var/.../baseline-hashes.json (all watched files)
  journals    - active work journals intersecting watched paths (timing gate)
  status      - combined card (hashes + pins + state + journals + cover)

Product source stays with the owning Codex session (live lease topic
text-first-context-collector-b6a14853 at assist-build time). ChatHistoryServiceImpl
was additionally leased by browser10-summary-boundary - a foreign live lease
there is a per-target BLOCKED_LEASE per brief section 7, never forced.
scripts/browser10_support_setup.py + its test are hard-forbidden for this
contract (read/write/run/reuse). OBSERVED_IN_DIFF means a seam exists in the
working tree; it is NOT a product verdict - the owning session's focused
Gradle runs (C1-C7) decide.
"""
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.text-first-context-collector-assist.v1"
CONTRACT = "TEXT-FIRST-CONTEXT-COLLECTOR-20261007-v1"
VAR_DIR = "var/codex-assist-text-first-context-collector-20261007"

# ---------------------------------------------------------------- paths ----
SETTINGS = "settings.gradle"
SETTINGS_KTS = "settings.gradle.kts"
BUILD_KTS = "build.gradle.kts"
API_CONTROLLER = "main/java/com/example/lms/api/ChatApiController.java"
ACCESS_GUARD = "main/java/com/example/lms/api/ChatSessionAccessGuard.java"
CHAT_WORKFLOW = "main/java/com/example/lms/service/ChatWorkflow.java"
CHAT_SERVICE = "main/java/com/example/lms/service/ChatService.java"
HISTORY_SVC = "main/java/com/example/lms/service/ChatHistoryServiceImpl.java"
MEMORY_HANDLER = "main/java/com/example/lms/service/rag/handler/MemoryHandler.java"
COMPRESSOR = "main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java"
PROMPT_BUILDER = "main/java/com/example/lms/prompt/StandardPromptBuilder.java"
PROMPT_CONTEXT = "main/java/com/example/lms/prompt/PromptContext.java"
PACKET = "main/java/com/example/lms/ensemble/PreparedContextPacket.java"
DSO = "main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java"
SCRAPER = "main/java/com/example/lms/service/rag/extract/PageContentScraper.java"
WEB_RETRIEVER = "main/java/com/example/lms/service/rag/WebSearchRetriever.java"
HYBRID_RETRIEVER = "main/java/com/example/lms/service/rag/HybridRetriever.java"
JEV_SIGNAL = "main/java/com/example/lms/assist/JevCandidateSignal.java"
GEMINI_GW = "main/java/com/example/lms/learning/gemini/GeminiGateway.java"
ROUTER_ASPECT = "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"
MODEL_FACTORY = "main/java/com/example/lms/llm/DynamicChatModelFactory.java"
BROWSER10_SETUP = "scripts/browser10_support_setup.py"
BROWSER10_SETUP_TEST = "scripts/test_browser10_support_setup.py"

# --------------------------------------------- brief SHA-12 fingerprints ----
# Brief section 3 snapshot read 2026-10-07 ~18:07-18:18 KST. DRIFTED vs pin is
# expected while the owning session patches; it is not an alarm.
SNAPSHOT = {
    SETTINGS: "bcc1da452154",
    SETTINGS_KTS: "d9905ba3d88c",
    BUILD_KTS: "39a07d7b1c16",
    API_CONTROLLER: "1295fcfe300c",
    ACCESS_GUARD: "b57a668c3854",
    CHAT_WORKFLOW: "fd8fd72e064b",
    CHAT_SERVICE: "a75901725845",
    HISTORY_SVC: "cb0ee3d5d782",
    MEMORY_HANDLER: "5f265f5ec385",
    COMPRESSOR: "f9d0835a107f",
    PROMPT_BUILDER: "496d4281ab57",
    PROMPT_CONTEXT: "904905b60609",
    PACKET: "c3147ac67612",
    DSO: "ed8f19a97af7",
    SCRAPER: "544b48913c01",
    WEB_RETRIEVER: "ad8bf1a502a9",
    HYBRID_RETRIEVER: "f9c5b8e48c0b",
    JEV_SIGNAL: "18810d5fda45",
    GEMINI_GW: "82de3e133b82",
    ROUTER_ASPECT: "9d28e004012e",
    MODEL_FACTORY: "914fb056574a",
}

# ------------------------------------------------------------- anchors ----
# (file, line, regex, label). line 0 = whole-file search.
PINS = [
    # --- active runtime / chat flow (brief 1-1) ---
    (SETTINGS, 15, r"rootProject|src111_merge15", "rootProject + :app declaration"),
    (SETTINGS_KTS, 5, r"(?i)(active|sentinel|groovy)", "kts sentinel note"),
    (BUILD_KTS, 775, r"src/test/java|main/java|sourceSets", "active sourceSets"),
    (API_CONTROLLER, 1708, r"(?i)(accessGuard|owner|session)", "session owner delegate"),
    (ACCESS_GUARD, 50, r"(?i)(owner|session|equal|match)", "owner verification"),
    (CHAT_SERVICE, 34, r"ChatWorkflow|facade|delegate", "facade -> ChatWorkflow"),
    (CHAT_WORKFLOW, 3048, r"(?i)(summary|recent|lastAssistant|history)", "summary/recent/last-assistant intake"),
    (CHAT_WORKFLOW, 3113, r"(?i)(compressor|compress|compose)", "DynamicContextCompressor call"),
    (CHAT_WORKFLOW, 3185, r"PromptContext", "PromptContext assembly"),
    (CHAT_WORKFLOW, 3300, r"(?i)(evidence_pack|evidencePack)", "evidence_pack gate"),
    (CHAT_WORKFLOW, 3449, r"(?i)(candidate|count|size)", "candidate count"),
    (CHAT_WORKFLOW, 3520, r"PromptBuilder|\.build\(", "PromptBuilder.build call"),
    (CHAT_WORKFLOW, 3740, r"(?i)(original|current|question|user)", "original user message last"),
    (CHAT_WORKFLOW, 3826, r"(?i)(late|deadline|bounded|accept|revision|owner)", "WP3 late/bounded accept region"),
    (CHAT_WORKFLOW, 7621, r"DISPATCHED", "pre-call marker (not wire proof)"),
    (HISTORY_SVC, 671, r"(?i)snapshot", "history snapshot"),
    (HISTORY_SVC, 738, r"(?i)(summary|anchor|importantSentence)", "summary/anchors/importantSentences"),
    (HISTORY_SVC, 982, r"(?i)(assignment|correction|pin|userFact)", "user assignment/correction pin"),
    (MEMORY_HANDLER, 70, r"(?i)(memory|session)", "memory config role"),
    (COMPRESSOR, 255, r"(?i)(belowThreshold|threshold|keep|short|pressure)", "short/no-pressure keep"),
    (COMPRESSOR, 331, r"(?i)(balanced|anchor|probe|select)", "balanced selection/anchor probe"),
    (COMPRESSOR, 369, r"(?i)(fail|original|fallback|return)", "failure -> original return"),
    (COMPRESSOR, 425, r"(?i)(memory|compress)", "memory compression"),
    (COMPRESSOR, 2021, r"(?i)(url|body|budget|length)", "URL/body joint budget boundary"),
    (COMPRESSOR, 2151, r"(?i)(dedup|provenance|citation)", "body+citation+provenance dedup"),
    (PROMPT_BUILDER, 61, r"(?i)(packet|quoted|conversation)", "packet-then-conversation boundary"),
    (PROMPT_BUILDER, 135, r"(?i)(correct|user|latest|constraint)", "latest user correction priority"),
    (PROMPT_BUILDER, 160, r"(?i)(history|lastAssistant|clip)", "history/last-assistant clip"),
    (PROMPT_BUILDER, 196, r"safeWebSnippet", "safeWebSnippet call site"),
    (PROMPT_BUILDER, 233, r"(?i)(render|boundary|append)", "render boundary"),
    (PROMPT_BUILDER, 457, r"(?i)(citation|metadata|source)", "citation metadata"),
    (PROMPT_BUILDER, 705, r"(?i)(conflict|web|memory)", "web-vs-memory conflict note"),
    (PROMPT_BUILDER, 1007, r"safeWebSnippet", "safeWebSnippet def (URL len vs body budget)"),
    (SCRAPER, 142, r"(?i)(article|main|noise|boiler)", "main/article noise removal"),
    (SCRAPER, 190, r"(?i)(rowspan|colspan|caption|th|table)", "table row-header/caption keep"),
    (WEB_RETRIEVER, 363, r"(?i)(serp|reuse|cached)", "request SERP reuse"),
    (WEB_RETRIEVER, 450, r"cache_obs|not_observed", "cache_obs=not_observed honesty"),
    (WEB_RETRIEVER, 635, r"480|passage|SERP", "query-aware 480-char passage/SERP fallback"),
    (WEB_RETRIEVER, 707, r"(?i)(afterFilter|empty|disabled|provider)", "empty/after-filter/disabled split"),
    (WEB_RETRIEVER, 1036, r"(?i)provider", "provider interface call boundary"),
    (WEB_RETRIEVER, 1537, r"(?i)(boundary|sentence|split)", "sentence boundary"),
    (WEB_RETRIEVER, 1591, r"(?i)(subject|support|direct)", "direct subject support"),
    (PACKET, 15, r"sourceId|revision|chunkId|locator", "packet identity fields"),
    (PACKET, 51, r"(?i)(helper|memory|history|input)", "helper inputs (no memory/history)"),
    (PACKET, 89, r"(?i)(2000|2_000|16|anchor|span)", "2000-char / 16-span anchor cap"),
    (PACKET, 101, r"(?i)(original|derived|render)", "all-original render + derived"),
    (PACKET, 125, r"(?i)(reject|schema|execute|missing)", "reject missing ID/schema/execute"),
    (DSO, 159, r"(?i)(resident|sampler|gate|ollama)", "resident sampler gate"),
    (DSO, 184, r"(?i)(reuse|reserve|helper|invalid|fallback)", "request-local reuse/answer reserve/one-helper"),
    (JEV_SIGNAL, 38, r"selection_context_missing|pass", "selection_context_missing pass-through"),
    (JEV_SIGNAL, 44, r"admitted", "admitted overload"),
    (JEV_SIGNAL, 64, r"(?i)(owner|scope|digest|candidate|id)", "owner/scope/digest/ID"),
    (JEV_SIGNAL, 94, r"(?i)(reorder|cancel|timeout|fallback|order)", "reorder + cancel/timeout fallback"),
    (HYBRID_RETRIEVER, 2057, r"jevCandidateSignal\.rerank|jevCandidateSignal != null",
     "conditional Jev call (presence != every request)"),
    (GEMINI_GW, 41, r"gemini-2\.5-flash|DEFAULT_MODEL", "default model constant"),
    (GEMINI_GW, 51, r"gemini-3\.5-flash-lite|speech", "speech lite model"),
    (GEMINI_GW, 72, r"(?i)(temperature|thinkingLevel|minimal|speech)", "speech temp0/thinking minimal"),
    (GEMINI_GW, 157, r"generate\(String prompt, Purpose purpose", "generate(prompt,purpose) wire"),
    (GEMINI_GW, 166, r"(?i)(purpose|disabled|credential|enabled|guard)", "purpose/disabled/credential guard"),
    (GEMINI_GW, 211, r"(?i)(rescue|remaining|once|retry|timeout)", "rescue contract: once/no retry"),
    (GEMINI_GW, 395, r"(?i)(openai|sampling|penalty|reasoning)", "OpenAI-compat sampling omit branch"),
    (GEMINI_GW, 417, r"(?i)(cueJSON|schema|reasoningEffort)", "cueJSON schema/reasoningEffort"),
    (GEMINI_GW, 479, r"(?i)(quota|circuit|429|timeout|error)", "quota/circuit/timeout/error"),
    (GEMINI_GW, 492, r"generateContent|payload", "native generateContent"),
    (GEMINI_GW, 701, r"gemini\.gateway\.models", "per-purpose model override property"),
    (GEMINI_GW, 754, r"enum Purpose", "Purpose enum (no context-selector value yet)"),
    (ROUTER_ASPECT, 1596, r"RouterSpec", "GeminiGateway.RouterSpec main-model entry"),
    (MODEL_FACTORY, 468, r"(?i)(RouterSpec|owner|main.?model|gemini)", "RouterSpec owner note"),
]

# Named test classes from the brief C1-C6 command block -> expected files.
NAMED_TESTS = [
    ("ai.abandonware.nova.orch.compress.DynamicContextCompressorTest",
     "src/test/java/ai/abandonware/nova/orch/compress/DynamicContextCompressorTest.java"),
    ("com.example.lms.service.ChatHistoryServiceImplConversationMemoryTest",
     "src/test/java/com/example/lms/service/ChatHistoryServiceImplConversationMemoryTest.java"),
    ("com.example.lms.prompt.StandardPromptBuilderConversationHistoryTest",
     "src/test/java/com/example/lms/prompt/StandardPromptBuilderConversationHistoryTest.java"),
    ("com.example.lms.service.rag.WebSearchRetrieverRelationEvidenceTest",
     "src/test/java/com/example/lms/service/rag/WebSearchRetrieverRelationEvidenceTest.java"),
    ("com.example.lms.service.PublicEvidenceLocatorIdentityTest",
     "src/test/java/com/example/lms/service/PublicEvidenceLocatorIdentityTest.java"),
    ("com.example.lms.service.rag.RagEvidenceUrlLengthBoundaryTest",
     "src/test/java/com/example/lms/service/rag/RagEvidenceUrlLengthBoundaryTest.java"),
    ("com.example.lms.service.ChatWorkflowPromptMessageRoleTest",
     "src/test/java/com/example/lms/service/ChatWorkflowPromptMessageRoleTest.java"),
    ("com.example.lms.prompt.StandardPromptBuilderSourceContractTest",
     "src/test/java/com/example/lms/prompt/StandardPromptBuilderSourceContractTest.java"),
    ("com.example.lms.service.ChatWorkflowEnsembleCitationSourceWiringTest",
     "src/test/java/com/example/lms/service/ChatWorkflowEnsembleCitationSourceWiringTest.java"),
    ("com.example.lms.ensemble.PreparedContextPacketTest",
     "src/test/java/com/example/lms/ensemble/PreparedContextPacketTest.java"),
    ("com.example.lms.llm.ContextPreparationAttemptBudgetTest",
     "src/test/java/com/example/lms/llm/ContextPreparationAttemptBudgetTest.java"),
    ("com.example.lms.service.ChatConversationEvidenceBudgetTest",
     "src/test/java/com/example/lms/service/ChatConversationEvidenceBudgetTest.java"),
    ("com.example.lms.service.rag.WebSearchRetrieverTraceStandardizationTest",
     "src/test/java/com/example/lms/service/rag/WebSearchRetrieverTraceStandardizationTest.java"),
    ("com.example.lms.learning.gemini.GeminiGatewayContractTest",
     "src/test/java/com/example/lms/learning/gemini/GeminiGatewayContractTest.java"),
    ("com.example.lms.learning.gemini.GeminiSearchRescueCapabilityTest",
     "src/test/java/com/example/lms/learning/gemini/GeminiSearchRescueCapabilityTest.java"),
    ("com.example.lms.learning.gemini.GeminiSearchRescueProjectionTest",
     "src/test/java/com/example/lms/learning/gemini/GeminiSearchRescueProjectionTest.java"),
    ("com.example.lms.assist.JevCandidateSignalContractTest",
     "src/test/java/com/example/lms/assist/JevCandidateSignalContractTest.java"),
    ("com.example.lms.assist.JevPromptEvidenceContractTest",
     "src/test/java/com/example/lms/assist/JevPromptEvidenceContractTest.java"),
    ("com.example.lms.assist.JevPurposeIsolationTest",
     "src/test/java/com/example/lms/assist/JevPurposeIsolationTest.java"),
    ("com.example.lms.service.ChatWorkflowCancellationContractTest",
     "src/test/java/com/example/lms/service/ChatWorkflowCancellationContractTest.java"),
]

# Acceptance slots -> evidence markers (file, regex, label).
COVER = [
    ("A1 captured final messages/roles; current question+conditions+corrections protected", [
        (CHAT_WORKFLOW, r"PromptContext|promptBuilder\.build|\.build\(ctx", "final message assembly seam"),
        (PROMPT_BUILDER, r"(?i)(correct|constraint|latest|user)", "correction/condition priority"),
        ("src/test/java/com/example/lms/service/ChatWorkflowPromptMessageRoleTest.java",
         r"(?i)class |@Test", "role boundary fixture"),
    ]),
    ("A2 same owner/session only; first2+10turn; new session/other user isolated", [
        (ACCESS_GUARD, r"(?i)(owner|session)", "owner check"),
        (HISTORY_SVC, r"(?i)(snapshot|session|owner)", "same-session snapshot"),
        ("src/test/java/com/example/lms/service/ChatHistoryServiceImplConversationMemoryTest.java",
         r"(?i)class |@Test", "conversation memory fixture"),
    ]),
    ("A3 sourceIDs/revisions/locators/spans + negation/numbers/units/time/counterevidence", [
        (PACKET, r"sourceId|revision|locator", "packet identity"),
        (SCRAPER, r"(?i)(rowspan|colspan|caption|th\b|table)", "table relation keep"),
        ("src/test/java/com/example/lms/service/PublicEvidenceLocatorIdentityTest.java",
         r"(?i)class |@Test", "locator identity fixture"),
        ("src/test/java/com/example/lms/service/rag/WebSearchRetrieverRelationEvidenceTest.java",
         r"(?i)class |@Test", "relation evidence fixture"),
    ]),
    ("A4 normal/long URL + body prefix/middle/end + table/caption/image-only honesty", [
        (PROMPT_BUILDER, r"safeWebSnippet", "citation trailer vs body split"),
        (COMPRESSOR, r"(?i)(url|body|budget)", "URL/body joint budget"),
        ("src/test/java/com/example/lms/service/rag/RagEvidenceUrlLengthBoundaryTest.java",
         r"(?i)class |@Test", "URL-length boundary fixture"),
    ]),
    ("A5 short/sufficient/OFF/disallowed/stale/cancelled -> Lite outbound 0", [
        (CHAT_WORKFLOW, r"(?i)(evidence_pack|evidencePack)", "existing gate seam"),
        (JEV_SIGNAL, r"selection_context_missing|admitted", "pass-through/admit seam"),
        (GEMINI_GW, r"(?i)(purpose|guard|disabled|credential)", "purpose guard (selector purpose absent = 0call)"),
    ]),
    ("A6 invalid ID/span/schema + 429/timeout/failure/overbudget/late -> baseline, retry0", [
        (PACKET, r"(?i)(reject|schema|missing|invalid)", "packet rejection"),
        (GEMINI_GW, r"(?i)(circuit|timeout|429|quota)", "failure handling"),
        (DSO, r"(?i)(invalid|fallback|helper)", "invalid->baseline fallback"),
    ]),
    ("A7 rendered messages + role overhead + answer reserve budget incl. fallback", [
        (CHAT_WORKFLOW, r"DISPATCHED|budget", "pre-call marker + budget guard"),
        ("src/test/java/com/example/lms/llm/ContextPreparationAttemptBudgetTest.java",
         r"(?i)class |@Test", "attempt budget fixture"),
        ("src/test/java/com/example/lms/service/ChatConversationEvidenceBudgetTest.java",
         r"(?i)class |@Test", "conversation evidence budget fixture"),
    ]),
    ("A8 request/run/source-bound attempt/cacheHit/retrieved/rendered/client capture", [
        (WEB_RETRIEVER, r"cache_obs|not_observed", "honest cache observation"),
        (CHAT_WORKFLOW, r"DISPATCHED", "marker = attempt, not success"),
        ("src/test/java/com/example/lms/service/rag/WebSearchRetrieverTraceStandardizationTest.java",
         r"(?i)class |@Test", "trace standardization fixture"),
    ]),
    ("A9 same fixture/budget baseline vs mock/recorded B", [
        ("src/test/java/ai/abandonware/nova/orch/compress/DynamicContextCompressorTest.java",
         r"(?i)class |@Test", "compressor fixture seam"),
        ("src/test/java/com/example/lms/ensemble/PreparedContextPacketTest.java",
         r"(?i)class |@Test", "packet fixture seam"),
        ("src/test/java/com/example/lms/learning/gemini/GeminiGatewayContractTest.java",
         r"(?i)class |@Test", "gateway contract fixture"),
    ]),
    ("A10 real approved model/account/budget real A/B (HOLD; report-only, no code evidence)", [
        (GEMINI_GW, r"enum Purpose", "no live selector purpose keeps HOLD consistent"),
    ]),
]

# Diff scope. Touches outside these product paths are flags (review cue, not a
# verdict). settings.gradle*/build.gradle.kts are fingerprint-only evidence in
# this contract - an edit there is out of scope.
ALLOWED = {
    API_CONTROLLER, ACCESS_GUARD, CHAT_WORKFLOW, CHAT_SERVICE, HISTORY_SVC,
    MEMORY_HANDLER, COMPRESSOR, PROMPT_BUILDER, PROMPT_CONTEXT, PACKET, DSO,
    SCRAPER, WEB_RETRIEVER, HYBRID_RETRIEVER, JEV_SIGNAL, GEMINI_GW,
    ROUTER_ASPECT, MODEL_FACTORY, BUILD_KTS,
} | {p for _, p in NAMED_TESTS}
PRODUCT_PREFIX = ("main/java/", "main/resources/", "src/test/")

# Hard-forbidden paths for this contract (brief section 8): read/write/run/
# reuse all banned. Touching one is a real flag, not an info cue.
FORBIDDEN_PATHS = {
    BROWSER10_SETUP: "browser10 support setup is a separate parallel work - banned entirely",
    BROWSER10_SETUP_TEST: "browser10 support test is a separate parallel work - banned entirely",
}

# Files with known foreign/parallel leases at assist-build time -> touching
# them is an INFO cue ("check current lease"), not a violation.
FOREIGN_LEASE_CANDIDATES = {
    HISTORY_SVC: "browser10-summary-boundary held this file at assist-build time "
                 "(per-target BLOCKED_LEASE cue; this brief's WP1 also targets it)",
    API_CONTROLLER: "prior parallel sessions held api/controller paths on 2026-10-07",
}

FORBIDDEN_LINES = [
    (r"(sk-|AIza|eyJ)[A-Za-z0-9_\-]{12,}", "secret-shaped literal"),
    (r"permitAll", "auth relaxation"),
    (r"System\.exit", "process exit in product code"),
    (r"class\s+\w*(Orchestrator|Scheduler|Daemon)\b", "new resident orchestrator/scheduler/daemon cue"),
    (r'"(?:GeminiLite|geminiLite|gemini-lite)"', "alias model pin instead of official model ID"),
    (r"google_search|googleSearch|googleSearchRetrieval", "search tool on text selector (brief excludes)"),
    (r"thinking_budget|thinkingBudget", "thinking_budget field send (both-fields 400 risk)"),
    (r"webGrounding\s*=\s*true|webGrounding\(true\)|true\s*.*webGrounding",
     "grounded wire on selector path (GROUNDED_RESULT_REUSE_TERMS HOLD)"),
    (r"(?i)DELETE\s+FROM|drop\s+table|truncate\s+table|deleteAll",
     "stored-original deletion cue (prompt exclusion is not deletion)"),
]
FORBIDDEN_LINES_PRODUCT = [
    (r"Thread\.sleep", "blocking sleep in request path"),
    (r"\.retryWhen\(|RetrySpec|retry\s*\(\s*[1-9]", "selector retry cue (contract: retry0)"),
    (r"@Scheduled|new\s+Thread\s*\(", "resident thread/scheduler cue"),
]

WATCH = sorted(set(SNAPSHOT) | {rel for rel, *_ in PINS} |
               {p for _, p in NAMED_TESTS})


def sha256(path: Path) -> str:
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_lines(path: Path):
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def cmd_hashes(root: Path):
    rows, drifted, missing = [], 0, 0
    for rel, pin in SNAPSHOT.items():
        p = root / rel
        if not p.exists():
            rows.append({"path": rel, "status": "MISSING", "pin": pin})
            missing += 1
            continue
        cur = sha256(p)
        same = cur.startswith(pin)
        if not same:
            drifted += 1
        rows.append({"path": rel, "status": "SAME" if same else "DRIFTED",
                     "pin": pin, "current": cur[:12],
                     "bytes": p.stat().st_size})
    base_file = root / VAR_DIR / "baseline-hashes.json"
    base_rows = []
    if base_file.exists():
        try:
            base = json.loads(base_file.read_text(encoding="utf-8"))
            for rel, rec in base.get("files", {}).items():
                p = root / rel
                if not p.exists():
                    base_rows.append({"path": rel, "status": "MISSING"})
                    continue
                cur = sha256(p)
                base_rows.append({"path": rel,
                                  "status": "SAME" if cur == rec.get("sha256") else "DRIFTED_SINCE_BASELINE",
                                  "baseline": str(rec.get("sha256"))[:12], "current": cur[:12]})
        except Exception as e:  # noqa: BLE001
            base_rows.append({"error": str(e)})
    print(json.dumps({"schema": SCHEMA, "cmd": "hashes",
                      "note": "DRIFTED vs brief pin = expected while owner session patches; re-read before judging",
                      "productPass": False,
                      CONTRACT: {"drifted": drifted, "missing": missing, "rows": rows},
                      "baseline": {"file": str(base_file), "present": base_file.exists(),
                                   "rows": base_rows}}, indent=2))
    return 0


def _find(lines, idx, pat, window, whole_file):
    rx = re.compile(pat)
    if not whole_file and idx < len(lines) and rx.search(lines[idx]):
        return idx + 1
    lo = 0 if whole_file else max(0, idx - window)
    hi = len(lines) if whole_file else min(len(lines), idx + window + 1)
    return next((i + 1 for i in range(lo, hi) if rx.search(lines[i])), None)


def cmd_pins(root: Path, window=60):
    rows, bad = [], 0
    for rel, line, pat, label in PINS:
        p = root / rel
        rec = {"anchor": f"{rel}:{line or '*'}", "label": label}
        if not p.exists():
            rec["status"] = "MISSING_FILE"
            bad += 1
            rows.append(rec)
            continue
        lines = read_lines(p)
        try:
            found = _find(lines, line - 1, pat, window, line == 0)
        except re.error as e:
            rec["status"] = "BAD_REGEX"
            rec["error"] = str(e)
            bad += 1
            rows.append(rec)
            continue
        if found is None:
            rec["status"] = "ABSENT"
            bad += 1
        elif found == line:
            rec["status"] = "SAME"
        else:
            rec["status"] = "MOVED"
            rec["nowLine"] = found
        rows.append(rec)
    print(json.dumps({"schema": SCHEMA, "cmd": "pins",
                      "productPass": False,
                      CONTRACT: {"anchors": len(rows), "problems": bad, "rows": rows}},
                     indent=2))
    return 1 if bad else 0


def _git_diff(root: Path, paths):
    try:
        out = subprocess.run(["git", "diff", "--"] + list(paths), cwd=root,
                             capture_output=True, encoding="utf-8",
                             errors="replace", timeout=90).stdout or ""
    except Exception:
        return [], []
    return _parse_unified(out)


def _parse_unified(text: str):
    added, touched = [], set()
    cur = None
    for ln in text.splitlines():
        if ln.startswith("+++ b/"):
            cur = ln[6:]
            touched.add(cur)
        elif ln.startswith("diff --git"):
            m = re.search(r" b/(\S+)", ln)
            if m:
                touched.add(m.group(1))
        elif ln.startswith("+") and not ln.startswith("+++"):
            added.append((cur, ln[1:]))
    return added, sorted(touched)


def _load_diff_arg(root: Path, diff_file):
    if diff_file is None:
        return _git_diff(root, WATCH)
    p = Path(diff_file)
    if not p.is_absolute():
        p = root / p
    try:
        return _parse_unified(p.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        return [], []


def _file_has(root: Path, rel: str, pat: str) -> bool:
    p = root / rel
    return p.exists() and re.search(pat, p.read_text(encoding="utf-8", errors="replace")) is not None


def cmd_cover(root: Path):
    added, _ = _git_diff(root, WATCH)
    items = []
    for name, checks in COVER:
        evid = []
        for rel, pat, label in checks:
            try:
                in_file = _file_has(root, rel, pat)
            except re.error:
                in_file = False
            in_diff = any(f == rel and re.search(pat, ln) for f, ln in added)
            evid.append({"label": label, "file": rel,
                         "inFile": bool(in_file), "inAddedDiff": bool(in_diff)})
        items.append({"acceptance": name, "evidence": evid})
    named_missing = [{"fqcn": fq, "path": p} for fq, p in NAMED_TESTS
                     if not (root / p).exists()]
    print(json.dumps({"schema": SCHEMA, "cmd": "cover",
                      "productPass": False,
                      "note": "inFile/inAddedDiff are seam-presence cues; focused Gradle C1-C6 runs are the verdict",
                      CONTRACT: items,
                      "namedTestsMissing": named_missing}, indent=2))
    return 0


def cmd_diff_review(root: Path, diff_file=None):
    added, touched = _load_diff_arg(root, diff_file)
    flags = []
    for rel in touched:
        if rel in FORBIDDEN_PATHS:
            flags.append({"file": rel, "line": "<file touched>",
                          "why": FORBIDDEN_PATHS[rel], "info": False})
            continue
        if not rel.startswith(PRODUCT_PREFIX):
            continue
        if rel not in ALLOWED:
            note = FOREIGN_LEASE_CANDIDATES.get(rel)
            pin = SNAPSHOT.get(rel)
            p = root / rel
            if note is None and pin is not None and p.exists() \
                    and sha256(p).startswith(pin):
                note = "pre-brief diff vs HEAD, brief pin still unchanged"
            flags.append({"file": rel, "line": "<file touched>",
                          "why": note or "product file outside contract scope",
                          "info": bool(note)})
        elif rel in FOREIGN_LEASE_CANDIDATES:
            flags.append({"file": rel, "line": "<file touched>",
                          "why": FOREIGN_LEASE_CANDIDATES[rel], "info": True})
    for f, ln in added:
        if f in FORBIDDEN_PATHS:
            continue  # already flagged at file level
        rules = list(FORBIDDEN_LINES)
        if (f or "").startswith("main/"):
            rules += FORBIDDEN_LINES_PRODUCT
        for pat, why in rules:
            if re.search(pat, ln):
                flags.append({"file": f, "line": ln.strip()[:140], "why": why})
    real = [f for f in flags if not f.get("info")]
    print(json.dumps({"schema": SCHEMA, "cmd": "diff-review",
                      "verdict": "CLEAN" if not real else "FLAGS",
                      "productPass": False,
                      "touchedPaths": touched,
                      "note": "flags are review cues, not verdicts; info=known foreign-lease candidate",
                      "flags": flags}, indent=2))
    return 1 if real else 0


def cmd_state(root: Path):
    drifted = sum(1 for rel, pin in SNAPSHOT.items()
                  if (root / rel).exists() and not sha256(root / rel).startswith(pin))
    seam_markers = []
    for rel, pat, label in [
            (GEMINI_GW, r"(?i)(context_selector|contextSelector|TEXT_FIRST|textFirst)",
             "gateway context-selector purpose seam"),
            (CHAT_WORKFLOW, r"(?i)(contextSelector|liteSelect|textFirst|selectorResult)",
             "workflow selector-call seam"),
            (PACKET, r"(?i)(selectorOutput|selectedSpans|whySelected)",
             "packet selector-output seam")]:
        if _file_has(root, rel, pat):
            seam_markers.append(label)
    if drifted == 0 and not seam_markers:
        phase = "PINNED_PRE_BRIEF"
    elif seam_markers:
        phase = "IN_FLIGHT_SELECTOR_SEAM"
    else:
        phase = "IN_FLIGHT"
    print(json.dumps({"schema": SCHEMA, "cmd": "state",
                      "productPass": False,
                      CONTRACT: {"phase": phase, "briefPinsDrifted": drifted,
                                 "newSeamMarkers": seam_markers,
                                 "ownerLeaseAtBuild": "text-first-context-collector-b6a14853",
                                 "foreignLeaseOverlap": "browser10-summary-boundary -> "
                                                        + HISTORY_SVC},
                      "note": "phase is a file-state classify; GREEN verdict = owning session's focused C1-C7 runs"},
                     indent=2))
    return 0


def cmd_lease(root: Path):
    targets = sorted(set(ALLOWED) | set(SNAPSHOT))
    manifest = {"targets": [{"path": rel,
                             "sha256": (sha256(root / rel) if (root / rel).exists() else None)}
                            for rel in targets]}
    out_dir = root / VAR_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    mpath = out_dir / "targets-current.json"
    mpath.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    ps1 = root / "__patch_drop__" / "source_edit_session.ps1"
    cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1),
           "-Action", "status", "-Json", "-TargetManifest", str(mpath)]
    try:
        res = subprocess.run(cmd, cwd=root, capture_output=True, encoding="utf-8",
                             errors="replace", timeout=120)
        print(res.stdout.strip() or res.stderr.strip())
        return res.returncode
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"schema": SCHEMA, "cmd": "lease", "error": str(e)}))
        return 2


def cmd_holds(root: Path):
    del root  # static card; no file reads
    holds = [
        {"id": "INPUT_NOT_PROVIDED",
         "what": "real user conversation not provided; per-answer cause not diagnosable",
         "release": "user provides actual conversation data"},
        {"id": "LITE_VALUE_NOT_PROVEN",
         "what": "offline contract != real Lite selection quality/cost/latency gain; "
                 "account/model access unverified",
         "release": "approved real A/B with fixed fixture/model/budget shows gain"},
        {"id": "REMOTE_PRIVACY_ADMISSION",
         "what": "no admission to send private history/originals to a remote provider",
         "release": "explicit transmission scope + approval; synthetic fixtures only until then"},
        {"id": "GROUNDED_RESULT_REUSE_TERMS",
         "what": "Google grounded output reuse as other-model input unverified",
         "release": "terms review; selector excludes grounded-result origin meanwhile"},
        {"id": "BLOCKED_LEASE",
         "what": "a foreign live lease pauses only that target, never the whole root",
         "release": "owner release; one request-release per fingerprint; never force"},
        {"id": "ALREADY_COVERED",
         "what": "focused fixture already preserves -> zero source patch on that seam",
         "release": "exit path, not a blocker"},
    ]
    print(json.dumps({"schema": SCHEMA, "cmd": "holds",
                      "productPass": False,
                      CONTRACT: {"holds": holds,
                                 "askOnce": "none (safe default = investigate/document only)",
                                 "adoptRule": "deterministic reuse/narrow repair/observability only; "
                                              "Lite option deferred"}},
                     indent=2))
    return 0


def cmd_snapshot(root: Path):
    out_dir = root / VAR_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    files = {}
    for rel in WATCH:
        p = root / rel
        if p.exists():
            files[rel] = {"sha256": sha256(p), "bytes": p.stat().st_size}
        else:
            files[rel] = {"sha256": None, "bytes": None, "missing": True}
    rec = {"schema": SCHEMA, "cmd": "snapshot",
           "takenAtUtc": datetime.now(timezone.utc).isoformat(),
           "files": files}
    mpath = out_dir / "baseline-hashes.json"
    mpath.write_text(json.dumps(rec, indent=2), encoding="utf-8")
    print(json.dumps({"schema": SCHEMA, "cmd": "snapshot",
                      "written": str(mpath), "files": len(files)}, indent=2))
    return 0


def cmd_journals(root: Path):
    jroot = root / "data" / "agent-handoff" / "codex-autonomy"
    watched = set(WATCH) | set(ALLOWED)
    hits = []
    if jroot.exists():
        for jd in sorted(jroot.iterdir()):
            jf = jd / "journal.json"
            if not jf.exists():
                continue
            try:
                j = json.loads(jf.read_text(encoding="utf-8", errors="replace"))
            except Exception:
                continue
            if j.get("status") not in ("in_progress", "blocked"):
                continue
            scope = [s.lower() for s in j.get("plannedScope", [])]
            overlap = sorted(w for w in watched if any(w.lower() == s or w.lower().endswith(s)
                                                     for s in scope))
            hits.append({"taskId": j.get("taskId"), "agent": j.get("agent"),
                         "status": j.get("status"), "purpose": j.get("purpose"),
                         "updatedAtUtc": j.get("updatedAtUtc"),
                         "watchedPathOverlap": overlap})
    print(json.dumps({"schema": SCHEMA, "cmd": "journals",
                      "productPass": False,
                      "note": "timing gate: Gradle/server/smoke only after the owning journal's final report",
                      "active": hits}, indent=2))
    return 0


def cmd_status(root: Path):
    print("== hashes ==")
    cmd_hashes(root)
    print("== pins ==")
    cmd_pins(root)
    print("== state ==")
    cmd_state(root)
    print("== journals ==")
    cmd_journals(root)
    print("== cover ==")
    cmd_cover(root)
    print("== holds ==")
    cmd_holds(root)
    return 0


def main(argv):
    root = Path(".")
    diff_file = None
    args = list(argv)
    if "--root" in args:
        i = args.index("--root")
        root = Path(args[i + 1])
        del args[i:i + 2]
    if "--diff" in args:
        i = args.index("--diff")
        diff_file = args[i + 1]
        del args[i:i + 2]
    cmd = args[0] if args else "status"
    if cmd == "diff-review":
        return cmd_diff_review(root, diff_file)
    fn = {"hashes": cmd_hashes, "pins": cmd_pins, "state": cmd_state,
          "cover": cmd_cover, "lease": cmd_lease, "snapshot": cmd_snapshot,
          "journals": cmd_journals, "holds": cmd_holds,
          "status": cmd_status}.get(cmd)
    if fn is None:
        print(__doc__)
        return 2
    return fn(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
