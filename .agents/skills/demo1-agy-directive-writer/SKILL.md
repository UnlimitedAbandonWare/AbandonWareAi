---
name: demo1-agy-directive-writer
description: "demo-1 첨부 소스·보고·요청을 분석해 실행자용 수정 지시서 또는 Codex·Devin 분담 지시서를 작성할 때. 직접 구현·완료 증거 판정·일반 요약에는 쓰지 않음."
---

# demo1 agy directive writer

Observable workflow the user expects when asking "X한테 지시서 써줘". Project rules
are NOT restated here; follow the pointers. For DRAFT/REVIEW/CLOSE mode, evidence
tiers and test accounting use `demo1-devin-directive-loop` (read it first).
Facts: [durable-facts](references/durable-facts.md). Skeleton: [template](references/directive-template.md).
GrokBot workflow shapes (triage, PASTE anatomy, session texts): [grokbot-playbook](references/grokbot-playbook.md).
Fragmentary-idea amplification (burst → adversarial → triad → THE ONE): [idea-burst-rubric](references/idea-burst-rubric.md).

## 0. Posture
- Read-only on the repo and user files. The ONLY writes: the directive file in
  %USERPROFILE%\Downloads\ (+ optional copies of referenced source docs next to it).
- agy is a pure amplifier (STRICT_ZERO): never edits product source — ideas go
  out as directives for Codex/Devin/Grok/Clean. See `agy-korean-grokbot-role.md`.
- No gradle, no app start, no paid/live API, no git mutation, no lease claim
  (`agent_scope_lease.py who|check` only), no secrets. Times in KST.

## 1. Input gate (stop early)
- List every attachment/path the user gave. For each: `Get-Item <p> | select Length,LastWriteTime`,
  `(Get-FileHash <p>).Hash.Substring(0,12)`, then read it fully (page through large files;
  PDF via `pdftotext` if available).
- If a CORE attachment cannot be opened or is truncated → stop, list which one and why,
  ask for re-attach (or a text export). Do not write a directive from a partial input.
- Record an input table: name | bytes | sha12 | opened fully (Y/N).

## 2. Verify against live source (source beats docs)
- Fleet signal first: `python -B scripts/agent_signal_digest.py` (~1s, $0) for
  live leases, in-progress journals, fresh handoffs, git dirt, Grok asks, events.
- Tool placement check: `python -B scripts/demo1_tool_placement_scan.py --root . "<ask>"`
  ranks the already-existing script/bat/skill calls for this ask and flags known
  router misroutes — advisory only, confirm before naming commands in the directive.
- `Get-FileHash` for every file the directive will name, `Select-String` for each cited line,
  `python -B scripts/agent_scope_lease.py who` for active leases and their expiry (convert Z → KST).
- Git is conditional, never the default probe: a DRAFT/REVIEW verified on current bytes +
  hashes needs no Git at all, and Devin-targeted directives exclude Git entirely
  (demo1-devin-directive-loop §Provenance — reads included). Codex keeps conditional local
  `git status`/`diff` only under AGENTS.md Local Source First; ordinary verification opens
  with files/hashes, not Git.
- Check the newest handoff/journal dirs under data/agent-handoff/ for the topic.
- Check GrokBot precedent for the same topic before writing:
  `python -B scripts/grok_to_agy_memory_bridge.py search "<topic>"` matches
  sessions, memory topics, and past user asks (`prompts` lists recent asks).
  A matching precedent's claims are re-verified before reuse — never copied stale.
- A stale doc, memory or earlier directive never beats a re-read file.

## 3. Reconcile the input
- Table: claim | RIGHT / WRONG / STALE / UNVERIFIED | evidence (file:line or command+output).
- WRONG/STALE claims are corrected in the directive, never passed through.

## 4. Role split (pointer: durable-facts §Roles)
- Default convention only — the task's explicit grant and each target file's kind decide
  the real owner, never this table. When nothing else is granted: Codex = product source ·
  Grok = tools/scripts/mocks · Clean(킴미) = red-team/rule proposals · Devin = runtime
  evidence / Display / yml / smoke scripts · user = decisions · agy = directive writing.
- agy/점 never edit product source directly (§0 STRICT_ZERO) — that survives any override.
- Put each work item under exactly one owner; cross-owner items become TOSS/FOR_* lines.

## 5. Write the directive (Korean, paste-ready; skeleton in references/directive-template.md)

Grok Bot 앱 규격 차이(2026-10-02 인계 팩, `references/grokbot-current/HANDOVER.md` §4;
단 팩의 다수 태그 기본 세트는 AGENTS.md Skill And Prompt Routing의 단계당 primary 하나가
이긴다 — 2026-10-02 skill-harmony 계약으로 폐기):
- `[ANTI-STOP]`은 위·아래 두 군데 둔다(맨 위 + 맨 아래).
- 섹션 순서 고정: 0 한 줄 목표 → 사실(KST 시각 포함) → 공통 규칙 → 항목(DV/WP) →
  HOLD → ASK_ONCE(기본값 포함) → 절대 금지 → Acceptance(미실행은 NOT_RUN+사유) → 보고 형식.
- **Devin 지시서**: 라우팅 표기는 단계당 resolved primary 하나(+ 독립 필요 시 보조 ≤1).
  `@<primary>` 줄 또는 명시 경로 `.agents/skills/<name>/SKILL.md` 참조 둘 다 인정된다 —
  태그 문법 지원이 미확인일 때 경로 참조로 충분하고, brief_lint/brief_save도 두 형태를
  받아들이며 참조 스킬의 실존을 검사한다. 5개 이상의 @태그 나열은 라우팅 실패다.
  첫 명령 `Set-Location C:\AbandonWare\demo-1\demo-1\src`, 보고는 `외부 API:` 줄로 시작.
- **Codex 지시서**: 첫 줄은 `$skill` 줄 — resolved primary 하나(+ 독립 필요 시 보조 ≤1),
  고정 세트 나열 금지. Codex는 목표 파일만 읽고 멈추는 버릇이 있어 [ANTI-STOP]이 필수.
Fragmentary user ideas go through `references/idea-burst-rubric.md` first:
3-hypothesis burst → adversarial check → triad deliberation → THE ONE mapped
into the skeleton. A fully specified brief skips the burst and verifies directly.
Must contain: goal (1 sentence) · live facts with timestamps · baseline sha12 table ·
lease steps · WPs ≤ 5 · one executable focused RED check per WP matched to the target's
file kind — JVM source → `.\gradlew.bat :test --tests <FQCN> --no-daemon --console=plain; "exit=$LASTEXITCODE"`,
script/validator → its existing `python -B scripts/test_*.py` entry, doc-only → the real
validator/lint or link check; never a token Gradle run ·
multi-session build rules only when a WP actually compiles (AWX_SPLIT_BUILD_OUTPUTS=1, AWX_BUILD_HOST_ID, focused only, no clean/full test) ·
no commit/push/add -A · no secrets · no live paid calls beyond an approved budget ·
legit end states (DONE_OFFLINE / ALREADY_COVERED / BLOCKED_LEASE / NOT_RUN_ENV / APPROVAL_REQUIRED) ·
loop (self-fix ≤ 3 → finite stop: hand off to the task's explicitly granted owner via
FOR_<owner>, or ASK — roles are the default convention, not fixed routing) ·
AUTO for local reversible in-contract work · ≤ 6 user decisions, each with a recommended value.
Verify every FQCN/path/script you cite exists (Test-Path / Select-String) before writing it.

### 채팅 HOLD/검증 보류 지시서 체크 (2026-10-05 실수 재발 방지)
- `applyEvidenceReleasePolicy`(근거 0 → 공개)와 `applyFinalVerificationReleaseGate`(검증 결과)를 분리해 적는다.
- fail-soft/unknown(`markFailSoft`·`outcomeKnown=false`)은 "검증 실패"가 아니라 판정불능 — Codex 지시서에 `verification_unknown_release`(본문 유지·releaseAllowed=true·knowledgeWriteAllowed=false) 계약이 빠지면 반려. 상세 `docs/agents-rules/DEMO1-EVIDENCE-ZERO-RELEASE.md` fail-soft 절.
- Codex 지시서는 Downloads + `scripts/brief_save.py` 경로로만 저장 — `src\agent-prompts\...\BRIEF.txt` 사본 금지, Devin용 lint 규격으로 Codex 브리프를 재저장하지 않는다.

## 6. Save and verify
- Name: `PASTE_<TARGET>_<TOPIC>_<YYYYMMDD>.txt` (TARGET ∈ CODEX|DEVIN|GROK|CLEAN|GPTPRO|…, TOPIC UPPER_SNAKE).
  Never overwrite an existing file: if the name exists, add `_R2`, `_R3`.
- **저장은 `scripts/brief_save.py`가 한다** (2026-10-02 인계 팩 §3, 2026-10-03 R6 갱신):
  `python -B scripts/brief_save.py save --draft <파일> --agent DEVIN|CODEX|GROK|CLEAN|GPTPRO --topic <kebab>`.
  lint(FAIL 시 저장 거부) → Downloads 저장(UTF-8 no BOM) →
  `data/agent-handoff/brief-registry/briefs.jsonl` 기록까지 한 번에 처리한다.
  `agent-prompts\<agent>-<topic>-<date>\BRIEF.txt` 사본은 2026-10-03 사용자 결정(R6)으로
  폐기 — 산출물은 Downloads의 PASTE 파일 하나다. brief_save.py가 repo 사본을
  아직 쓰면 그 출력은 deprecated로 취급하고 보고에 남긴다.
  과거 지시서 검색: `brief_save.py list|latest|search <단어>`.
- 단, 사용자가 "Downloads에만"/"붙여넣을 텍스트만" 같은 **단독 산출**을 요청하면
  기록부 쓰기를 강제하지 않는다 — Downloads 파일 하나만 쓰고
  바이트·sha12를 보고한다. 저장+기록부(brief_save.py)는 지시서 저장이
  승인된 기본 흐름일 때만 쓴다.
- Write UTF-8 (no BOM). Then `Get-Item` Length + sha12; re-read the first and last 20 lines.
- Copy referenced source docs next to it only if the target cannot read the repo.

## 7. Reply (short, Korean)
- Grok Bot 답 형식(HANDOVER §1): 결론 한 줄 → 근거 2~4줄 → 지시서면 경로·바이트·sha12 →
  사용자가 그 에이전트에게 그대로 칠 문장 `말로: 「…」` → 끝에 `한 줄:` 요약.
- path + bytes + sha12 · 3-6 key live findings (with file:line) · role split one line each ·
  user decisions (≤ 6, recommended value first) · what was NOT verified.

## Stop conditions
- Core attachment unreadable → ask re-attach. Quota/credit error → record text, stop, no retry farm.

## 8. 깊이 판단
- 지시서 작업을 시작할 때 `demo1-agy-depth-router`로 L1~L3를 먼저 판정하고 첫 줄에 `[depth Lx: 이유]`를 쓴다.
- 지시서 작성은 기본 L2 이상이다: 확인할 file:line 주장이 많고 다른 에이전트가 그대로 실행할 문서라서다.
- L3이면 `fact-verifier`·`red-team-reviewer` 서브에이전트로 주장 묶음을 독립 재확인하고 초안을 적대 검토한다.
- 점수 기준·상한은 `configs/agy-depth.json` — 읽고 적용한다.
- Target file under a foreign lease → still write the directive, mark BLOCKED_LEASE for that file.
