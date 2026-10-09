# DEMO1-SESSION-HARMONY — 세션 종료 관문 (session close gate)

SSOT for the shared safe-session-closure gate. "고쳐도 고쳐도 끝이 없는" 상태를 막는
네 가지 원인(같은 거대 파일로 몰림 · 되돌릴 기준점 없음 · 지시서 과잉 · 세션별
합격만 있고 공통 합격 없음)을 기존 도구의 조합 하나로 묶는다. 새 잠금 장치나 새
VCS를 만들지 않는다.

## 안전 종료와 목표 완료의 정의

- **안전 종료**는 선언 범위 변경이 보존됐고, 외부 lease를 침범하지 않았으며,
  journal이 closed이고 소유 lease가 끝난 상태다. `closureStatus=SAFE_CLOSED`로 표시한다.
  journal의 `result=partial/blocked`와 Acceptance HOLD도 정상 종료할 수 있다.
- **목표 완료**는 기존 exact-goal 요청·hash·완료 receipt가 검증된 경우에만 입증된다.
  legacy PASS와 단일 checkpoint 성공, 파일명, report의 완료 문구는 대체 근거가 아니다.
  현재 gate에는 안전한 read-only 전체 목표 receipt 검증 연결이 없어 그 연결은 HOLD이며,
  `goalCompletion=NOT_PROVEN`을 유지한다. gate가 completed/stopWork를 쓰거나 cleanup을 호출하지 않는다.
- `python -B scripts/session_close_gate.py close --task <taskId>`의 **legacy PASS는
  이 gate의 종료 조건 통과일 뿐, 전체 목표 성공이 아니다**. 기존 판정·종료코드 0/1/2를 보존한다.
- `close`는 5가지를 순서대로 본다:
  1. 선언 범위의 변경분이 `codex_work_checkpoint.py` sealed/verified cycle에 들어갔는가
     (안 됐으면 FAIL + begin/seal 명령 힌트).
  2. REPORT.md의 Acceptance heading 아래 bullet/plain/Markdown 표를 다음 동급 또는 상위
     heading까지 읽는다(60줄 잘림 없음, 기존 task-context 4 MiB 한도). 중복 ID는 더 나쁜 판정.
     PASS, 사유 있는 NOT_RUN(5자 이상), HOLD는 종료를 허용하되 성공 상태와 구분한다.
     원래 필수 ID는 `--required-acceptance A1,A2`로 바인딩하며, 누락은 FAIL이다.
     파일명/verify 이벤트는 증거 참조일 뿐이다. `run_verified_command` run.json과 기존
     `agent_code_evidence_gate`의 run/evidence envelope를 `--evidence-contract` 및
     `--evidence-contract-sha256`로 별도 pin한 contract와 검증한다. 실제 oracle hash,
     exit=0, command/run/cwd 바인딩, 실행 tests>0, 결과 파일과 현재 source hash가 모두 맞아야 VERIFIED.
     plain receipt·미지원 schema·directory binding은 REFERENCE_ONLY로 남는다.
  3. `--golden` 지정 시 `scripts/chat_rag_golden_browser.js` 를
     strict+`--max-sends` 상한(≤6)으로 실행 —
     서버 없으면 NOT_RUN(사유 포함), strict에서 `modelMatched=false`면 FAIL
     ("고른 모델로 답하지 않음").
     empty results, 요청 case 누락, nonzero rc도 FAIL이다. 미지정 golden은 호출하지 않는다.
  4. `git diff --name-only HEAD` + untracked 와 **다른 세션** lease targetPaths 의
     내 선언 범위에서 교집합이 0인가, 그리고 다른 토픽 lock에 쓰기가 없는가.
     귀속되지 않는 외부 dirty는 정보로만 표시한다.
  5. `work_journal.py` close 기록과 `-Action end` 로 lease가 해제됐는가.
- 출력은 한 줄 판정 `PASS / BLOCKED:<첫 실패> / NOT_RUN:<사유>` + `--json` 본문.
  종료코드 0/1/2.

| Additive 필드 | 의미 |
|---|---|
| closureStatus | SAFE_CLOSED / BLOCKED: 변경 보존·소유권·journal/lease 종료 |
| acceptanceStatus | ALL_PASS / HAS_HOLD / HAS_NOT_RUN / FAIL: 읽은 항목 판정, HOLD 우선; 원래 필수 ID는 별도 바인딩 |
| evidenceStatus | VERIFIED / REFERENCE_ONLY / MISSING: 검증된 scoped receipt / 참조만 / 참조 없음 |
| journalResult | journal의 기존 result를 그대로 출력(partial/blocked/verified 등) |
| goalCompletion | NOT_PROVEN: 기존 전체 목표 receipt가 입증되지 않음; scoped 증거로 승격하지 않음 |

`verifiedScope`는 현재 hash가 일치한 파일만 나열한다. 실패·zero tests·stale hash·command 불일치·
읽을 수 없는 receipt는 VERIFIED가 아니다. 증거 부족으로 닫힌 journal이나 lease를 다시 열지 않는다.

## 뜨거운 파일(hot files) — 한 번에 한 세션

- `configs/hot-files.yaml`에 나열된 파일(현재 ChatWorkflow.java,
  ChatApiController.java, DynamicChatModelFactory.java, application-llm.yaml +
  근거 첨부 3개)은 **live lease 소유자가 1명일 때만** 수정 가능하다.
- 다른 세션은 그 파일에 대해 "패치 준비 + HOLD"만 한다. 판정은
  `agent_scope_lease.py who` / `source_edit_session.ps1 -Action status -Json` 을
  읽어서 한다 — 새 잠금 장치 금지.
- 시작 전 점검: `python -B scripts/session_close_gate.py check-start
  --targets <manifest.json> --task <taskId>` → GO / HOLD.

## 되돌릴 기준점

- 수정 전 `codex_work_checkpoint.py`로 대상을 seal하고, 검증 실패 시 그 범위만
  postimage로 복원한다 (Git index/ref 미사용). 커밋 금지 규칙 아래에서는 이 seal이
  "되돌릴 기준점"이다.

## lease 표기

- `-Action` 허용값은 ps1의 ValidateSet뿐이다:
  `begin / end / status / verify / bind-scope / heartbeat / recover`.
  `acquire` / `release` 같은 값은 **없는 값**이다 — `brief_lint.py`가
  `lease-action-invalid` FAIL로 잡는다(ValidateSet을 ps1에서 읽어 비교).
- 주기: begin → heartbeat(30분) → end. end를 남기면 다른 세션의 stale 청소가 된다.

## 지시서 큐

- `python -B scripts/brief_queue_status.py` 가 Downloads의 `PASTE_*` 와
  `data/agent-handoff` 보고를 짝지어 OPEN/DONE/BLOCKED/SUPERSEDED/UNMATCHED를 센다.
- **에이전트당 OPEN이 3개를 넘으면 새 지시서를 보류**한다(도구가 권고 한 줄을 냄).
- 산출물 전달 규칙: `docs/agents-rules/DEMO1-DELIVERY-DOWNLOADS.md` — dot의 사용자 전달 최종 지시서만 Downloads;
  리뷰·로그·초안·중간 결과는 기존 프로젝트 보고 경로이며 Downloads 사본을 완료 조건으로 요구하지 않는다.

## 관련

- 절차: `.agents/skills/demo1-work-ledger/SKILL.md`,
  `.agents/skills/agent-scope-lease/SKILL.md`.
- 실패 분류·은폐 금지: `verify/report verdict separation (awx.debug.verify.v2)` —
  게이트의 PASS도 "그 범위를 실제로 돌렸다"에 한정한다.
