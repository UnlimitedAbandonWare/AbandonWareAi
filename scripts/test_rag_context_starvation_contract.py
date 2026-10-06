#!/usr/bin/env python3
"""Synthetic contract tests: RAG context starvation / fail-soft release / cancel.

Pins the REQUIRED behavior from docs/design/UNHOOKED_SEAMS_CONTRACT_SPEC.md
sections R1-R4 as an executable Python model. Java stays Codex-owned; this file
is the acceptance contract for the FOR_CODEX gapfill blueprint.

  Trace 983/987: verifier markFailSoft (outcomeKnown=false) must RELEASE the
    answer body with an UNVERIFIED flag and deny memory write -- not HOLD the
    body behind evidence_needed / HELD_NOTICE.
  Trace 991: collected web/vector candidates must reach the prompt context even
    when the citation gate promotes zero of them -- demote to unverified
    snippets, never inject web:0 vector:0 while raw hits exist.
  Cancel ACK: after an exact-token cancel, non-terminal SSE frames are
    suppressed, the terminal event is emitted exactly once, and session/memory
    persist is skipped (cancelledBeforePersist).

Exits 0 with 'ALL PASS' on success, 1 otherwise. Pure stdlib, no Java runtime.
"""

import sys

RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(("PASS" if cond else "FAIL"), name, detail)


# ---------------------------------------------------------------- R1/R3 model
class Decision:
    def __init__(self, content, status, allowed, knowledge, flag=None):
        self.content = content
        self.release_status = status
        self.release_allowed = allowed
        self.knowledge_write_allowed = knowledge
        self.flag = flag


def release_gate(candidate, verification_required, status,
                 outcome_known, accepted_for_memory):
    """REQUIRED contract (spec R1): fail-soft never holds the body."""
    if not verification_required:
        return Decision(candidate, "NOT_REQUIRED", True, True)
    if not outcome_known:
        # 판정불능 = fail-soft: 본문 유지 + UNVERIFIED 플래그 + 메모리 기록 금지
        return Decision(candidate, "UNVERIFIED", True, False,
                        flag="verification_fail_soft")
    if accepted_for_memory and status in ("pass", "corrected"):
        return Decision(candidate, "APPROVE", True, True)
    if status == "insufficient":
        # 근거 부족 판정(known)은 본문 유지 + 근거부족 플래그 (zero-release 정책)
        return Decision(candidate, "INSUFFICIENT", True, False,
                        flag="insufficient_evidence")
    if status == "rejected":
        return Decision("Information unavailable: verification rejected.",
                        "REJECT", False, False)
    return Decision(candidate, "UNVERIFIED", True, False,
                    flag="verification_state_inconsistent")


def render_projection(answer, should_stop, table_marker="<!-- rag-control-projection:v1 -->"):
    """REQUIRED contract (spec R3): projection appends a status block; it never
    swaps the whole body for HELD_NOTICE. Hold is conveyed by flag rows."""
    body = "" if answer is None else str(answer)
    out = body
    if should_stop:
        out = ("[보류 플래그] 추가 근거 확인 필요 — 본문은 미검증 상태로 표시\n\n"
               + body)
    return out + "\n\n" + table_marker + "\n(status rows)"


# ------------------------------------------------------------------ R2 model
def assemble_prompt_context(raw_web, promoted):
    """REQUIRED contract (spec R2): collected candidates always reach the
    prompt. Citation-gate-blocked candidates demote to UNVERIFIED_SNIPPETS
    instead of starving the context."""
    injected = list(promoted)
    demoted = []
    if raw_web and not injected:
        demoted = [{"lane": d["lane"], "marked": "UNVERIFIED_SNIPPET",
                    "snippet": d["snippet"]} for d in raw_web]
        injected = demoted
    return {"web_rendered": sum(1 for d in injected if d.get("lane") == "web"),
            "injected": injected, "demoted": demoted,
            "starved": bool(raw_web) and not injected}


TRACE991_WEB = [
    {"lane": "web", "snippet": "루리웹 - 원신 보디냐챠 관련 토론 스니펫"},
    {"lane": "web", "snippet": "겜스고 - 보디냐챠 빌드 정보 스니펫"},
    {"lane": "web", "snippet": "아카라이브 - 보디냐챠 채널 스니펫"},
]


# ------------------------------------------------------------------ R4 model
class StreamRun:
    """REQUIRED contract (spec R4): exact cancel -> one terminal event, sink
    completes, non-terminal late frames suppressed, persist skipped."""

    def __init__(self):
        self.sink_open = True
        self.terminal_claimed = False
        self.cancelled = False
        self.frames = []
        self.persisted = False
        self.generation_aborted = False

    def throw_if_cancelled(self):
        if self.cancelled:
            self.generation_aborted = True
            raise RuntimeError("exact chat run cancelled")

    def cancel_exact(self):
        self.cancelled = True
        if not self.terminal_claimed:
            self.terminal_claimed = True
            self.frames.append(("terminal", "cancelled"))
        self.sink_open = False

    def emit(self, kind, payload):
        if not self.sink_open or self.cancelled:
            return False  # late non-terminal frame suppressed
        self.frames.append((kind, payload))
        return True

    def persist_session(self):
        if self.cancelled:
            return False  # cancelledBeforePersist
        self.persisted = True
        return True


def main():
    # ---- Trace 983/987: fail-soft release contract
    rel = release_gate("을지대 이창화 교수는 을지대학교 교수이다.",
                       True, "unknown", outcome_known=False,
                       accepted_for_memory=False)
    check("t983 fail-soft releases body",
          rel.release_allowed and rel.content.startswith("을지대"),
          "content preserved, not HELD/evidence_needed")
    check("t983 fail-soft denies memory",
          not rel.knowledge_write_allowed
          and rel.flag == "verification_fail_soft")
    check("t983 fail-soft not HELD",
          "evidence_needed" not in rel.content
          and rel.release_status == "UNVERIFIED")
    rel2 = release_gate("body", True, "pass", True, True)
    check("known pass approves", rel2.release_status == "APPROVE"
          and rel2.knowledge_write_allowed)
    rel3 = release_gate("body", True, "insufficient", True, False)
    check("insufficient still releases body",
          rel3.release_allowed and rel3.content == "body"
          and rel3.flag == "insufficient_evidence")

    # ---- HELD projection contract (R3)
    proj = render_projection("근거 기반 본문", should_stop=True)
    check("projection keeps body under hold",
          "근거 기반 본문" in proj and "보류 플래그" in proj,
          "hold becomes a flag, not a body swap")
    proj_ok = render_projection("본문", should_stop=False)
    check("projection appends marker",
          "rag-control-projection:v1" in proj_ok
          and proj_ok.startswith("본문"))

    # ---- Trace 991: starvation contract
    ctx = assemble_prompt_context(TRACE991_WEB, promoted=[])
    check("t991 blocked gate still injects",
          ctx["web_rendered"] == 3 and not ctx["starved"],
          "raw hits demoted to UNVERIFIED_SNIPPET, not dropped")
    check("t991 demoted marked unverified",
          all(d["marked"] == "UNVERIFIED_SNIPPET" for d in ctx["demoted"]))
    ctx2 = assemble_prompt_context(TRACE991_WEB,
                                   promoted=[{"lane": "web", "snippet": "promoted one"}])
    check("t991 promoted path unchanged",
          ctx2["web_rendered"] == 1 and ctx2["demoted"] == [])
    ctx3 = assemble_prompt_context([], promoted=[])
    check("empty collection is not starvation",
          ctx3["web_rendered"] == 0 and not ctx3["starved"],
          "zero collected is legitimate empty, not a gate artifact")

    # ---- Cancel ACK contract
    run = StreamRun()
    run.emit("chunk", "partial-1")
    run.cancel_exact()
    check("cancel emits terminal once",
          run.frames.count(("terminal", "cancelled")) == 1)
    run.cancel_exact()  # duplicate cancel (mobile double-tap)
    check("duplicate cancel no extra terminal",
          run.frames.count(("terminal", "cancelled")) == 1)
    check("late chunk suppressed",
          run.emit("chunk", "late-partial") is False
          and ("chunk", "late-partial") not in run.frames)
    check("persist skipped after cancel",
          run.persist_session() is False and not run.persisted)
    try:
        run.throw_if_cancelled()
        aborted = False
    except RuntimeError:
        aborted = True
    check("generation checkpoint aborts", aborted and run.generation_aborted)

    failed = [n for n, ok, _ in RESULTS if not ok]
    print("ALL PASS" if not failed else "FAILED: %s" % ", ".join(failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
