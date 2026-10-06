#!/usr/bin/env python3
"""Offline unit test for probe_unhooked_seams_guard.py (stdlib only).

Builds synthetic repo fixtures in a temp dir:
  GREEN = all hard seam invariants hold            -> exit 0 (warnings allowed)
  REDx5 = one violated invariant per fixture       -> exit 3, named failure
  IND   = JevGatewayClient.java missing            -> exit 2
  SIM   = --simulate-seams on an empty root        -> exit 0 + divergence warning

Exits 0 with 'ALL PASS' on success, 1 otherwise. Never touches the live tree.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROBE = ROOT / "scripts" / "probe_unhooked_seams_guard.py"

RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(("PASS" if cond else "FAIL"), name, detail)


JEV_CLIENT = """package com.example.lms.assist;
/** Vercel AI Gateway native POST /v1/evaluate transport. Evaluation only —
 * never requests text generation. */
public class JevGatewayClient {
    void b() { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(); }
}
"""
JEV_CLIENT_BAD = JEV_CLIENT + "// POST /chat/completions fallback\n"
JEV_GATE = """package com.example.lms.service.rag.handler;
public final class JevRetrievalGateHandler implements RetrievalHandler {
    public void handle(Query q, List<Content> acc) {
        if (CURRENT.get()!=null || JevDecisionScope.capture()!=null) {
            delegate.handle(query,accumulator); return; }
        delegate.handle(query,accumulator);
    }
    void hints() {
        hints.put("allowWeb",false);hints.put("useWebSearch",false);
        hints.put("retrieval.web.enabled",false);
    }
    static String safeReason(String r) {
        return Set.of("ok","timeout","budget_skip","cancelled").contains(r)?r:"invalid_response";
    }
    Object observation(State s,String id) {
        return s.result!=null&&s.result.httpStatus()==200&&"ok".equals(state.result.reasonCode())?x:null;
    }
}
"""
DISPLAY_CTRL = """package com.example.lms.assist;
public class DisplayConversateController {
    @Value("${conversate.display.audio.enabled:${CONVERSATE_DISPLAY_AUDIO_ENABLED:false}}") boolean audioEnabled;
    @PostMapping("/api/assist/display/lens") void lens(){}
    @PostMapping("/api/assist/display/lens/text") void lensText(){}
    private void audioGuard(){if(!audioEnabled)throw error(NOT_FOUND,"display_audio_disabled");if(asr==null)throw error(UNAVAILABLE,"asr_disabled");}
    private Binding audioBinding(String o,String id){Binding b=bound(o,id);if(b.transcription&&!o.equals(b.phoneOwner))throw error(FORBIDDEN,"paired_phone_required");return b;}
    @PostMapping("/api/assist/display/audio/start") void audioStart(){audioGuard();requireProducer(b,request.clientId());}
    @PostMapping("/api/assist/display/audio/chunk") void audioChunk(){requireProducer(b,request.clientId());asr.chunk(b.owner,b.id,request.epoch(),request.sequence(),request.pcm());}
    private void requireProducer(Binding b,String c){}
}
"""
DISPLAY_CTRL_BAD = DISPLAY_CTRL.replace('throw error(FORBIDDEN,"paired_phone_required");', "")
ASR_BRIDGE = """package com.example.lms.assist;
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class ConversateAsrBridge { void w(){ c.fail("ASR_INPUT_LOST"); } }
"""
ORCH = """package com.example.lms.service.rag.orchestrator;
public class UnifiedRagOrchestrator {
    @Value("${retrieval.vector.required:false}") private boolean vectorRequired;
    public List<Doc> vector = new ArrayList<>();
    public List<Doc> bm25 = new ArrayList<>();
    public List<Doc> fused = new ArrayList<>();
    List<Doc> toDocsOrEmpty(Object r, Query q, int k, String lane){ return List.of(); }
    void emergency(){ toDocsOrEmpty(vectorLeaf(), emergencyQuery, emergencyK, "VECTOR-EMERGENCY"); }
}
"""
FUSER = """package com.example.lms.service.rag.fusion;
public class WeightedReciprocalRankFuser {
    void f(){ try{ traceRrf(n,0,"empty_input"); } catch(Exception e){ log.debug("fail-soft stage={}","rrf"); } }
}
"""
CWF = """package com.example.lms.service;
public class ChatWorkflow {
    void x(){
        FinalVerificationReleaseDecision d = applyFinalVerificationReleaseGate(out, true, status, known, mem);
        d = applyUnavailableVerificationRelease(d, unavailable, q, raw, promoted, confirmed, required, bound);
        String e = "verification_unavailable_excerpt";
    }
    static FinalVerificationReleaseDecision applyFinalVerificationReleaseGate(
            String candidate, boolean required, String status, boolean outcomeKnown, boolean mem){
        String safeCandidate = candidate == null ? "" : candidate;
        if (!outcomeKnown) {
            return new FinalVerificationReleaseDecision(
                    "evidence_needed: final verification outcome unknown",
                    "HOLD", "verification_outcome_unknown", false, false, false);
        }
        return null;
    }
}
"""
RENDERER = """package com.example.lms.orchestration.control;
public final class RagControlProjectionRenderer {
    public static final String TABLE_MARKER = "<!-- rag-control-projection:v1 -->";
    private static final String HELD_NOTICE = "held";
    static String renderProjection(String answer, RagActionPlan plan) {
        String semanticAnswer = stripExistingProjection(answer);
        StringBuilder out = new StringBuilder();
        out.append(plan.shouldStop() ? HELD_NOTICE : semanticAnswer);
        return out.toString();
    }
}
"""
CANCEL_HANDLER = "package com.example.lms.api; public class ChatCancellationCommandHandler { boolean f(){ return runRegistry.cancelExact(id,tok,m); } }"
REGISTRY = """package com.example.lms.service.chat;
public class ChatRunRegistry {
    public boolean cancelExact(Long s, String t){ return true; }
    private void finishCancellation(Run run){
        if(!run.terminalEventClaimed){ run.sink.tryEmitNext(e); }
        run.sink.tryEmitComplete();
    }
}
"""
RUN_CTX = "package com.example.lms.service.chat; public class ChatRunExecutionContext { public static void throwIfCancelled(){} }"
CHAT_API = "package com.example.lms.api; public class ChatApiController { boolean f(){ boolean cancelledBeforePersist=false; return cancelledBeforePersist; } }"
CHAT_DTO_CLEAN = "package com.example.lms.dto; public class ChatRequestDto { String query; }"
CHAT_DTO_BAD = CHAT_DTO_CLEAN + " /* Integer focusAnswerLengthChars; */"
CHAT_DEFAULTS = "package com.example.lms.config; public class ChatDefaultsProperties { int x; }"
APP_YML = "server:\n  port: 18180\n"
APP_LLM = "public:\n  request-budget: {}\n"
APP_META = "conversate:\n  display-ttl-ms: ${CONVERSATE_DISPLAY_TTL_MS:20000}\n"
CCC = """package com.example.lms.service;
/** Server-selected conversation data. Never deserialized from a public request. */
public record ChatConversationContext(Integer focusAnswerLengthChars) {
    public ChatConversationContext { if(focusAnswerLengthChars!=null&&(focusAnswerLengthChars<80||focusAnswerLengthChars>800))throw new IllegalArgumentException("invalid_focus_answer_length"); }
}
"""
LENS_PREFS = """package com.example.lms.assist;
public record LensDisplayPrefs(long forceAfterMs) {
    public static final long MIN_FORCE_AFTER_MS=1_000,MAX_FORCE_AFTER_MS=600_000;
    static LensDisplayPrefs defaults(){ return new LensDisplayPrefs(180_000); }
}
"""
PROMO = "package com.example.lms.service.rag; public class RagEvidenceAttributionService { enum P { CITATION_GATE_BLOCKED } }"
SPB = "package com.example.lms.prompt; public class StandardPromptBuilder {}"
DOC = "# doc\n"


def make_root(tmp, chat_dto=CHAT_DTO_CLEAN, jev_client=JEV_CLIENT,
              display_ctrl=DISPLAY_CTRL, fuser=FUSER, orch=ORCH):
    root = Path(tmp)
    j = root / "main/java/com/example/lms"
    for rel, text in (
            ("assist/JevGatewayClient.java", jev_client),
            ("service/rag/handler/JevRetrievalGateHandler.java", JEV_GATE),
            ("assist/DisplayConversateController.java", display_ctrl),
            ("assist/ConversateAsrBridge.java", ASR_BRIDGE),
            ("service/rag/orchestrator/UnifiedRagOrchestrator.java", orch),
            ("service/rag/fusion/WeightedReciprocalRankFuser.java", fuser),
            ("service/ChatWorkflow.java", CWF),
            ("orchestration/control/RagControlProjectionRenderer.java", RENDERER),
            ("api/ChatCancellationCommandHandler.java", CANCEL_HANDLER),
            ("service/chat/ChatRunRegistry.java", REGISTRY),
            ("service/chat/ChatRunExecutionContext.java", RUN_CTX),
            ("api/ChatApiController.java", CHAT_API),
            ("dto/ChatRequestDto.java", chat_dto),
            ("config/ChatDefaultsProperties.java", CHAT_DEFAULTS),
            ("service/ChatConversationContext.java", CCC),
            ("assist/LensDisplayPrefs.java", LENS_PREFS),
            ("service/rag/RagEvidenceAttributionService.java", PROMO),
            ("prompt/StandardPromptBuilder.java", SPB)):
        if text is None:
            continue
        (j / rel).parent.mkdir(parents=True, exist_ok=True)
        (j / rel).write_text(text, encoding="utf-8")
    r = root / "main/resources"
    r.mkdir(parents=True, exist_ok=True)
    (r / "application.yml").write_text(APP_YML, "utf-8")
    (r / "application-llm.yaml").write_text(APP_LLM, "utf-8")
    (r / "application-meta-display.yml").write_text(APP_META, "utf-8")
    for rel in ("docs/design/UNHOOKED_SEAMS_CONTRACT_SPEC.md",
                "docs/agents-rules/DEMO1-VOICE-TRANSCRIPTION-SEAM.md",
                "docs/agents-rules/DEMO1-JEV-RERANK-BOUNDARY.md",
                "docs/agents-rules/DEMO1-GRAPH-RAG-HYBRID-SEAM.md"):
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(DOC, "utf-8")
    return root


def run_probe(*args):
    return subprocess.run([sys.executable, "-B", str(PROBE), *args],
                          capture_output=True, text=True, cwd=ROOT)


def main():
    with tempfile.TemporaryDirectory() as tmp:
        green = make_root(Path(tmp) / "green")
        r = run_probe("--root", str(green), "--json")
        data = json.loads(r.stdout)
        check("green exit=0", r.returncode == 0,
              "exit=%s stderr=%s" % (r.returncode, r.stderr.strip()[:200]))
        check("green assertions>=25", data["assertions"] >= 25,
              "assertions=%d" % data["assertions"])
        check("green no failed", data["failed"] == [])
        check("green hold warning reported",
              any(w["kind"] == "outcome-unknown-hold-live"
                  for w in data["warnings"]))
        check("green starvation warning reported",
              any(w["kind"] == "promotion-starvation-window"
                  for w in data["warnings"]))

        red = make_root(Path(tmp) / "red", chat_dto=CHAT_DTO_BAD)
        r = run_probe("--root", str(red), "--json")
        data = json.loads(r.stdout)
        check("red leak exit=3", r.returncode == 3, "exit=%s" % r.returncode)
        check("red leak violation named",
              "no-display-leak-dto" in data["failed"])

        red2 = make_root(Path(tmp) / "red2", jev_client=JEV_CLIENT_BAD)
        r = run_probe("--root", str(red2), "--json")
        data = json.loads(r.stdout)
        check("red jev exit=3", r.returncode == 3, "exit=%s" % r.returncode)
        check("red jev violation named",
              "jev-no-generate-path" in data["failed"])

        red3 = make_root(Path(tmp) / "red3", display_ctrl=DISPLAY_CTRL_BAD)
        r = run_probe("--root", str(red3), "--json")
        data = json.loads(r.stdout)
        check("red asr exit=3", r.returncode == 3, "exit=%s" % r.returncode)
        check("red asr violation named",
              "asr-producer-binding" in data["failed"])

        red4 = make_root(Path(tmp) / "red4",
                         fuser=FUSER.replace("fail-soft stage=", "stage="))
        r = run_probe("--root", str(red4), "--json")
        data = json.loads(r.stdout)
        check("red fuser exit=3", r.returncode == 3, "exit=%s" % r.returncode)
        check("red fuser violation named",
              "fuser-stage-failsoft" in data["failed"])

        red5 = make_root(Path(tmp) / "red5",
                         orch=ORCH.replace("List<Doc> bm25", "List<Doc> sparse"))
        r = run_probe("--root", str(red5), "--json")
        data = json.loads(r.stdout)
        check("red lane exit=3", r.returncode == 3, "exit=%s" % r.returncode)
        check("red lane violation named",
              "graph-lane-separation" in data["failed"])

        ind = make_root(Path(tmp) / "ind", jev_client=None)
        r = run_probe("--root", str(ind), "--json")
        check("indeterminate exit=2", r.returncode == 2,
              "exit=%s" % r.returncode)

        empty = Path(tmp) / "empty"
        empty.mkdir()
        r = run_probe("--root", str(empty), "--simulate-seams", "--json")
        data = json.loads(r.stdout)
        check("simulate exit=0", r.returncode == 0, "exit=%s" % r.returncode)
        check("simulate divergence warning",
              any(w["kind"] == "sim-live-divergence"
                  for w in data["warnings"]))

        r = run_probe("--root", str(green), "--report", "--json")
        data = json.loads(r.stdout)
        rp = green / "data/agent-handoff/unhooked-seams-guard/report.json"
        check("report written", rp.is_file() and
              json.loads(rp.read_text(encoding="utf-8"))["exit"] == 0,
              "path=%s" % data.get("reportPath"))

        r = run_probe("--root", str(green), "--scan-sources")
        check("scan-sources exit=0", r.returncode == 0,
              "exit=%s" % r.returncode)

    failed = [n for n, ok, _ in RESULTS if not ok]
    print("ALL PASS" if not failed else "FAILED: %s" % ", ".join(failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
