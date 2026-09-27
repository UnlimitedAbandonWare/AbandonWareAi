# 데빈 전달 셋업 점검 — Nova / Fold6 폴드 카메라 사진 힌트 (2026-09-24)

- 기준 정본: `C:\AbandonWare\demo-1\demo-1\src` (preflight가 `matchesCanonical: true`, class `canonical`로 확인)
- 확인 시각: 2026-09-24T08:29Z (KST 17:29)
- 성격: **읽기 전용 점검**. 소스/리포는 한 바이트도 수정하지 않았습니다. 이 폴더의 브리프 파일 1개만 새로 만들었습니다.

---

## 0. 결론 3줄

1. **지시서 내용은 정본과 정확히 일치합니다.** 지시서가 전제한 8개 파일의 SHA-256이 지금 정본 바이트와 모두 동일 → "이미 수정됐거나 다른 버전"일 걱정 없이 그대로 써도 됩니다.
2. **문제는 전달 형식입니다.** @스킬 15개 나열, `@demo1-devin-source-orchestrator` 누락, 브리프 파일 경로 미지정. 리포의 라우터 규칙상 이 칩 라인은 "라우팅 실패"로 분류되고, 실제 라우터는 이 작업을 카메라/노바 seam이 아니라 `demo1-meta-display-simple-caption`(렌즈 캡션)으로 보냅니다.
3. **시작 전에 풀어야 할 살아있는 리스 2건이 있습니다.** 같은 작업 리스(`nova-oneshot-camera`, owner `devin-cli`, ~09:22Z)와 `ChatWorkflow.java`를 잡고 있는 다른 소유자 리스(~09:47Z). 정책상 **빼앗는 것은 금지**이고, 겹치는 파일만 건너뛰거나 release 요청입니다.

---

## 1. 지시서 전제 = 정본 ✔ (검증됨)

| 파일 | 지시서 주장 해시(앞 12자) | 정본 현재 해시(앞 12자) | 판정 |
|---|---|---|---|
| `main/resources/static/assets/display/index.html` | `24befd635548` | `24befd635548` | 일치 |
| `.../display/display-snapshot.js` | `c6d501319f67` | `c6d501319f67` | 일치 |
| `.../display/display-focus-controls.js` | `e4b9273895bb` | `e4b9273895bb` | 일치 |
| `.../display/display-voice.js` | `50a7d9d904e5` | `50a7d9d904e5` | 일치 |
| `assist/NovaFocusSettings.java` | `debe7d9dec01` | `debe7d9dec01` | 일치 |
| `assist/NovaFocusState.java` | `ec1257c109c5` | `ec1257c109c5` | 일치 |
| `assist/NovaFocusAnswerService.java` | `95ff6b814370` | `95ff6b814370` | 일치 |
| `service/ChatWorkflow.java` | `2b415829f3e9` | `2b415829f3e9` | 일치 |

추가 확인: `src/test/js/display-snapshot.test.cjs`(3,176 bytes)와 `NovaFocusSnapshotStateTest.java`가 **이미 존재**합니다. 즉 "회귀 테스트를 새로 만들라"가 아니라 "기존 테스트를 확장하라"가 맞습니다.

---

## 2. 보낸 메시지 진단

### 2-1. @스킬 15개 나열 → 리포 규칙상 "라우팅 실패"
`AGENTS.md`의 `DEMO1-VIBE-SKILL-ROUTER` 절 원문:
> Every vibe/daily ask starts by resolving ONE primary skill … Listing 5+ `@skill` mentions in one directive is a routing failure, not thoroughness.
> `forbid_families` (counter-evidence, macsrc-patchdrop, triad) stay off the default path unless the user's own wording names them.

실제 라우터를 **보낸 칩 라인 그대로** 돌린 결과(정본 `scripts/demo1_vibe_skill_router.py`):
```
intent: meta-display
primary: demo1-meta-display-simple-caption
optional: demo1-meta-display-webapp
forbidden_skipped: [counter-evidence, macsrc-patchdrop, triad]
score: 3
```
→ 즉 15칩은 "여러 스킬"이 아니라 **하나로 뭉개져서 카메라/노바와 무관한 렌즈 캡션 스킬로 귀결**됩니다. 같은 지시서 문구(`…소스 수정 지시서대로 소스를 패치해줘`)는 `source-write → demo1-work-ledger`(파일 변경 절차 스킬)로 갑니다. 칩을 늘리는 것이 오히려 라우팅을 흐립니다.

참고(리포 내 불일치, 그대로 보고): 리포 자신의 복붙 카드 `.devin/PROMPTS/meta-display-paste-daily.md`는 6칩, `meta-display-paste-debug.md`는 9칩을 씁니다. 그래서 "5+ = 실패" 규칙과 카드가 서로 다릅니다. **이번 브리프는 멀티 seam이므로 오케스트레이터 2칩 경로를 권합니다.**

### 2-2. 오케스트레이터 칩 누락
`AGENTS.md` `DEMO1-DEVIN-SOURCE-ORCHESTRATOR` + `.agents/skills/demo1-devin-source-orchestrator/SKILL.md`의 "Devin paste" 원문:
> `@objective-executor @demo1-devin-source-orchestrator` plus the brief. Run `plan` before editing.

보낸 메시지에는 `@objective-executor`만 있고 오케스트레이터가 없어서, 여러 seam(JS 수명 + Java 상태 + AI wire + 설정 UI)을 한 스킬로 뭉개려는 형태가 됐습니다.

### 2-3. `@positive-negative-neutral-judge`는 기본 금지 가족(triad)
`.agents/skills-intent-index.yaml`의 `families.triad.skills`에 `positive-negative-neutral-judge`가 있고, `default_forbid_families`에 `triad`가 들어 있습니다. 라우터 실행 3회 모두 `forbidden_skipped`에 triad가 찍혔습니다.
결정적: 이 브리프로 `plan`을 돌리면 리포가 스스로 `skip: ["demo1-triad-deliberation"]`을 넣습니다 → **이 작업은 심의 대상이 아니다**가 정본 답변입니다.

### 2-4. 첨부 이름 불일치 + 프로브 스크립트 부재
- 메시지는 `m132sain(1).zip`을 언급하지만 Downloads에는 `m132sain.zip`(16:38), `maswain.zip`(16:44)만 있습니다. 데빈이 파일 이름으로 찾으면 실패합니다.
- 지시서가 실행하라고 한 `repro/snapshot_observations.cjs`, `repro/probe_state.py`(및 `snapshot-probe-results.json`, `state-probe-results.json`)가 **이 PC 어디에도 없습니다**(Downloads 재귀 검색 0건). 데빈이 "스크립트가 없다"며 멈추거나 즉석 재작성할 위험이 큽니다 → 브리프에는 "부재 시 기존 `src/test/js/display-snapshot.test.cjs`를 확장"으로 대체 경로를 넣어 두었습니다.

### 2-5. 같은 주제 문서 4중 버전 혼재
| 시각 | 파일 | 상태 |
|---|---|---|
| 14:51 | `Devin_Nova_OneShot_Camera_Instructions.txt` | 구버전(이미 구현된 1차분을 "처음부터 만들라"는 톤) |
| 14:59 | `Devin_Nova_OneShot_Camera_Instructions (1).txt` | 구버전 |
| 17:24 | `Nova_Fold6_Camera_Source_Instructions_2026-09-24.md/.txt` | **정본(이번용)** |
| 17:25 | `Fold6_Camera_Hints_Instructions.md/.txt` | **정본(이번용)** |

오늘 codex가 이미 1·2단계(ChatRequestDto.snapshotSource, ChatWorkflow frame, NovaFocus 이미지 경로, controller `/focus/snapshot`, 프런트 캡처 모듈+UI)를 구현했고 **그 결과가 지금 정본**입니다(`fold-meta-snapshot-vision-0a1d87c6` 저널). 14:51/14:59본을 같이 주면 중복 신설·되돌림 사고가 납니다.

---

## 3. 살아있는 리스 / 충돌 (2026-09-24T08:29Z 실측)

`__patch_drop__/source-edit-locks/*/lease.json` 4건:

| 리스 | 소유자(ownerId) | 만료(UTC) | 타깃 | 이 작업과 겹침 |
|---|---|---|---|---|
| `nova-oneshot-camera.lock` | `devin-cli` | 2026-09-24T09:22:22Z | 17 | **사실상 동일 작업 전체**(NovaFocusState/Settings/Service/History, DisplayConversateController, display-snapshot.js, display-focus-controls.js, display-conversate.js, display-focus.js, index.html, 테스트 6개+`display-snapshot.test.cjs`) |
| `chat-release-admin-fix-devin.lock` | `devin-desktop` | 2026-09-24T09:47:10Z | 10 | **`main/java/com/example/lms/service/ChatWorkflow.java`** (= 지시서 P0-D 대상), `main/resources/static/js/chat.js` |
| `chat-release-admin-fix-devin.ce011f15a7a0aaaa.lock` | `devin-desktop-chat-release-admin-fix-devin-0924-7355c8ae` | 2026-09-24T09:30:02Z | 1 | 없음(AdminTokenGuardInterceptor) |
| `answer-hold-devin-dock.lock` | `grok-answer-hold-devin-dock-24627788` | 2026-09-24T09:46:39Z | 7 | 없음(skills/prompts/scripts) |

정책(정본 근거):
- `AGENTS.md` Concurrent Editing 절: *A foreign live lease on an overlapping target = **skip that file, continue independent work** — never delete/override/steal.*
- 오케스트레이터 plan `forbidden` 목록에도 `steal-foreign-lease`가 들어 있습니다.
- stale 회수는 TTL 만료 + 소유자 생존 미증명일 때만: `python -B scripts/lease_conflict_autoflow.py reclaim …`(또는 `agent_scope_lease.py reclaim`).

⚠️ 추가로 확인된 사실: `nova-oneshot-camera` 리스가 기록한 preimage(`__patch_drop__/nova-snapshot-targets.json`)는 **현재 정본 바이트와 다릅니다.**
- `NovaFocusSettings.java`: 리스 기록 `ac6d05d5…` vs 현재 `debe7d9d…`
- `NovaFocusState.java`: 리스 기록 `e81c7ec7…` vs 현재 `ec1257c1…`
- `display-snapshot.js`: 리스 기록 `d364d79c…` vs 현재 `c6d50131…`
- `index.html`: 리스 기록 `96a8b938…` vs 현재 `24befd63…`

→ 그 리스로 `apply/restore`를 시도하면 preimage 불일치로 거부됩니다(=정상 동작). **데빈은 자기 taskId로 새로 `begin`** 해야 합니다.

참고: `data/agent-handoff/codex-autonomy/fold-meta-snapshot-vision-0a1d87c6/journal.json`은 아직 `in_progress`이고 이벤트에 "User authorized takeover by Devin after stopping the prior Codex work"가 있습니다. 규칙상 **남의 저널은 닫지 않습니다**(데빈은 자기 저널만). foreign `in_progress`는 "진행 여부 미확인"이지 완료가 아니라는 점만 유의.

---

## 4. 붙여넣기 (권장안)

### 칩 라인
```
@objective-executor @demo1-devin-source-orchestrator
```

### 본문
```
Project Root: C:\AbandonWare\demo-1\demo-1\src  (다른 워크트리·복사본 아님)

브리프 파일: C:\Users\nninn\Downloads\Fold6_Camera_Hint_Setup_20260924\Fold6_Camera_Hint_Brief_20260924.txt
원문 지시서(첨부, 이번용 정본만): Nova_Fold6_Camera_Source_Instructions_2026-09-24.md,
  Fold6_Camera_Hints_Instructions.md, Fold6_Camera_Source_Evidence.md, SOURCE_AUDIT.md
  ※ 14:51 / 14:59 Devin_Nova_OneShot_Camera_* 는 구버전이니 쓰지 마라.
  ※ m132sain(1).zip 은 없다. 실제 파일명은 m132sain.zip 이다.

0) 먼저 plan만 만들어라. 소스 수정 금지.
   python -B scripts/devin_task_orchestrate.py plan --brief-file <위 브리프>
   plan의 write 목록은 playbook 일반값이다. 브리프의 대상 파일 목록으로 좁혀서 네가 쓸 파일만 확정해 보고해라.
1) python -B scripts/agent_preflight.py --root .
   python -B scripts/work_journal.py list --active
   결과 JSON을 그대로 붙여 보고해라(요약 금지).
2) 타깃 리스: 아래 겹침이 있다. live 리스는 절대 빼앗지 마라.
   - nova-oneshot-camera (owner devin-cli, ~09:22Z) → 내 것이면 renew, 아니면 내 taskId로 새 begin
   - chat-release-admin-fix-devin (owner devin-desktop, ~09:47Z) → ChatWorkflow.java 는 그 파일만 보류/request-release
   겹치는 파일은 건너뛰고 나머지를 진행한 뒤, 보류 목록을 한 줄로 보고해라.
3) 수정 전에 실패 테스트를 먼저 고정해라(기존 테스트 확장, 새 하네스 신설 금지):
   src/test/js/display-snapshot.test.cjs, NovaFocusStateTest, NovaFocusSnapshotStateTest 등
4) 라이브 반영 증명은 [DEV-RELOAD] socket ready 또는 ForceRestart 결과로만 말해라.
   구 PID 재시작 / -CheckOnly / 옛 프로세스 HTTP 200 은 증거가 아니다.
5) 폴드 실기기 촬영·안경 렌즈 표시는 별도 등급이다. 못 했으면 NOT_RUN으로 남겨라.
6) 금지: 새 카메라 시스템·새 업로드 서버·새 AI 프록시·visionEnabled 중복 플래그,
   전역 harmony ENFORCE, 인증/예산 가드 완화, 사진 base64 로그·저장, 마이크/ASR 중단, 유료 모델.
```

이후 턴에서는 이렇게만: `plan 대로 phase 1 진행하고 stop 조건 증거 붙여줘.`

---

## 5. 데빈이 첫 턴에 실행할 명령 (복붙용)

```powershell
# 0) 위치·프리플라이트
cd C:\AbandonWare\demo-1\demo-1\src
python -B scripts/agent_preflight.py --root .
python -B scripts/work_journal.py list --active

# 1) 내 작업 범위/계획 (쓰기 없음)
python -B scripts/devin_task_orchestrate.py plan --brief-file "C:\Users\nninn\Downloads\Fold6_Camera_Hint_Setup_20260924\Fold6_Camera_Hint_Brief_20260924.txt"

# 2) 타깃 리스 겹침 확인 (읽기 전용). -TargetManifest 는 JSON **파일 경로**이고
#    형태는 { "targets": [ { "path": "repo-relative", "sha256": "<64-hex 또는 null>" } ] } 다.
#    sha256 null = 아직 없는 새 파일. 배열만 넘기면 target-scope-unproven / exit 6.
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 -Action status -Root . -Json -TargetManifest <manifest.json>
#    exit 7 = blocking(겹치는) 리스 있음. exit 6 + preimage-changed = 그 리스의 기록 바이트가 현재와 다름.
#    -Action 후보: begin, end, status, verify, bind-scope, heartbeat, recover (help 없음)
#    begin 예: -Action begin -Root . -Role desktop -Topic <topic> -OwnerId <agent-topic-taskid> -TaskId <taskId> -TargetManifest <manifest.json> -Json

# 3) 폴드/안경 재현 상태 스냅샷 (읽기 전용, 재시작 없음)
python -B scripts/devin_task_orchestrate.py capture --role wear --invoke
#    → data/agent-handoff/display-debug/latest.json 의 patchHints 를 읽고 seam 결정
```
주의(실행하며 확인한 것): 플래그 이름을 추측하지 말고 먼저 확인하세요 —
`python -B scripts/work_journal.py --help`, `python -B scripts/codex_work_checkpoint.py --help`.
`source_edit_session.ps1` 은 `-Action` 값이 `begin, end, status, verify, bind-scope, heartbeat, recover` 뿐이고(`-Action help` 없음), 계약은 스크립트 상단 `.SYNOPSIS` 를 읽습니다.

---

## 6. 모르고 있었을 룰 · 도구 (정본 근거)

| # | 항목 | 정본 위치 | 왜 중요한가 |
|---|---|---|---|
| 1 | 스킬 라우터(1스킬) | `AGENTS.md` DEMO1-VIBE-SKILL-ROUTER, `scripts/demo1_vibe_skill_router.py`, `.agents/skills-intent-index.yaml` | 매 요청 1차 스킬 1개 결정. 칩 남발은 오히려 오배송(실측: 15칩 → 렌즈 캡션 스킬) |
| 2 | Devin 오케스트레이터 | `AGENTS.md` DEMO1-DEVIN-SOURCE-ORCHESTRATOR, `.agents/skills/demo1-devin-source-orchestrator/SKILL.md` + `playbooks/playbooks.json` | 멀티 seam은 `plan` phase 순서로만. `skip`은 구속력 있음 |
| 3 | 복붙 카드·규칙 SSOT | `.devin/PROMPTS/`(daily/debug/minimal), `.devin/RULES_SSOT.md` | 규칙 SSOT는 `.windsurf/rules/`(4개: hard-constraints, meta-rayban-display-runtime, conditional-local-git, gpu-power-fallback) + `AGENTS.md`. 여기 미러는 금지 |
| 4 | Devin 훅 | `.devin/hooks.v1.json` → `.codex/hooks/source_edit_triage.ps1`, `scripts/devin_pre_edit_guard.ps1`, `scripts/agent_work_guard.ps1` | write/edit/apply_patch/exec마다 게이트가 돎. 훅은 **탐지**만, 집행은 checkpoint apply/restore |
| 5 | 타깃 스코프 리스 | `__patch_drop__/source_edit_session.ps1`, `source-edit-locks\|scopes\|heartbeats\|quarantine`, `scripts/lease_conflict_autoflow.py`, `scripts/agent_change_plane.py` | 세션은 리포 전체가 아니라 **선언한 파일**만 잡음. 남의 live 리스는 skip |
| 6 | 작업 원장 | `docs/PROJECT_STATUS.md`(유일한 전체 상태 입구), `scripts/work_journal.py`, `scripts/codex_work_checkpoint.py`, `scripts/status_doc.py --expect-sha256` | 변경 전 preimage, 변경 후 기록·검증이 규칙. 남의 journal은 close 금지 |
| 7 | 라이브 증명 | `scripts/start_rag_stack.ps1`, `var/rag-launcher/<ts>-*/result.json`(`status=ready`,`springReused=false`), `var/dev-reload/dev-reload.log`의 `[DEV-RELOAD] socket ready` | 컴파일 성공·구 프로세스 200·`-CheckOnly`는 증거 아님 |
| 8 | 디버그 판정 | `Debug-RAG.bat -Action verify`(exit 0/3/6, `skipped\|blocked\|not_observed` 유지), `Debug-Meta-Display.bat -Action status -Json`, `Read-RAG-Debug.bat` | "도구가 돌았다"와 "대상이 검증됐다"를 분리 |
| 9 | 실기기 캡처 | `devin_task_orchestrate.py capture --role wear --invoke` → `data/agent-handoff/display-debug/latest.json` | nova-focus 계열 브리프는 capture 단계가 **자동 삽입**(실측 plan에서 확인). 마지막 wear-test 이후면 `patchHints` 읽고 seam 결정 |
| 10 | 시크릿·유료 | `.secrets/` 절대 금지(출력·커밋·검색), `AWX_AGENT_ALLOW_PAID_MODELS=1` 없으면 유료 금지, `scripts/check-model-lock.ps1` | vision 모델도 설치 allowlist 안에서만. 무단 pull 금지 |
| 11 | Git | 로컬 한정 허용, 단일 입구 `scripts/agent_git_vibe_commit.py` | push/tag/버전파일/`reset --hard` 금지 |
| 12 | 종료·정리 | `$demo1-goal-complete-stop`, `.agents/skills/demo1-completed-directive-cleanup` | 완료 시 턴 종료 + 증거 바인딩된 정리 |
| 13 | 보고 답장 | `$demo1-devin-directive-loop`(DRAFT/REVIEW/CLOSE, REVIEW는 delta-only) | 데빈 보고서에 답할 때 재감사 금지 |
| 14 | **워크트리 함정** | `C:\Users\nninn\.cline\worktrees\56ac9\src` | 이 복사본엔 `display-snapshot.js`·`NovaFocusState.java`가 **없고** 나머지도 해시가 다름. 프롬프트에 정본 절대경로 고정 필수 |
| 15 | PowerShell 5.1 gotcha | `.agents/skills/demo1-devin-source-orchestrator/playbooks/playbooks.json` | `Get-Content -Raw \| ConvertFrom-Json`가 UTF-8(한글)에서 깨짐 → `-Encoding UTF8` 또는 python 사용 |

### 부록 — 실측 `plan` 결과 (그대로 실행한 출력)

`python -B scripts/devin_task_orchestrate.py plan --brief-file "...\Fold6_Camera_Hint_Brief_20260924.txt"` → exit 0
```
matchedPlaybooks: ["nova-focus"]
phases: preflight → capture-before → nova-focus-contract → nova-focus-implement → nova-focus-verify → capture-after
  preflight            : skill demo1-work-ledger            guard demo1-project-root
  capture-before/after : skill demo1-evidence-debugging     (nova-focus는 alwaysCaptureFor에 포함)
  nova-focus-contract  : skill demo1-nova-focus             guard demo1-core-request-router, skip demo1-triad-deliberation
  nova-focus-implement : skill demo1-nova-focus             write = playbook 일반 목록(브리프 파일 목록으로 좁힐 것)
  nova-focus-verify    : skill demo1-toolchain-auto-select
forbidden: [... , "steal-foreign-lease", "patch-without-latest-snapshot", ...]
skipUnion: [claim-from-http-200-or-dom-only, demo1-triad-deliberation, focus-answers-in-hint-field,
            new-mic-or-asr, page-number-ui-for-focus, shared-cancelWork-for-focus]
```
읽는 법: 정본은 이 작업을 **Nova Focus 플레이북**으로 처리하고, 도메인 스킬로 `demo1-nova-focus`를 씁니다(15칩 라우터가 고른 `demo1-meta-display-simple-caption`이 아님). `plan.write`는 플레이북의 일반 선언이므로 브리프의 파일 목록으로 좁히는 것이 맞습니다.

---

## 7. 데빈에게 요구할 증거 등급 표 (그대로 복사해 쓰세요)

| 단계 | 데빈이 흔히 하는 주장 | 이번 작업에서 요구할 증거 |
|---|---|---|
| 코드 확인 | "고쳤습니다" | 변경 파일 목록 + diff(정본 경로 기준) |
| 단위·JS | "테스트 통과" | 실행 명령 + exit code + 테스트 이름. OFF 경합 / 늦은 권한 승인 / 프레임 대기 중 stop / 중복 claim / 취소 후 재전송 0회 케이스 포함 |
| 정본 빌드 | "빌드 성공" | `.\gradlew.bat :compileJava :processResources -x test` exit code |
| 라이브 반영 | "서버에 반영됨" | `[DEV-RELOAD] socket ready` 또는 `result.json status=ready, springReused=false` |
| 실제 이미지 전달 | "사진이 AI로 갔음" | provider 직렬화 직전 image count=1 + MIME/bytes(사진 원문·base64는 보고서에 넣지 않음) |
| 폴드 실기기 | "촬영됨" | 후면 렌즈 확인 + 촬영 후 소유 트랙 ended + 기존 수음 유지 관찰 |
| 안경 표시 | "렌즈에 떴음" | 기존 relay 경로 표시 확인(연결 ACK·publish ACK만으로는 불가) |

없는 항목은 PASS가 아니라 `NOT_RUN`으로 남기게 하세요.

---

## 8. 남은 확인 필요 (evidence_needed)

- `repro/snapshot_observations.cjs`, `repro/probe_state.py` 실물 — 이 PC에서 못 찾았습니다. 있으면 경로를 주시면 브리프에 추가합니다(없으면 기존 `src/test/js/display-snapshot.test.cjs` 확장으로 대체).
- `maswain.zip`(16:44)이 이 작업과 무관한 별건인지.
- 화면 잠금·백그라운드 촬영은 이번 1차 범위 밖(전경만) — 별도 지시서로 분리 권장.

---

## 9. 설치 기록 (이 문서가 리포에 들어온 경로)

이 점검 문서는 정본에 설치됐고, 설치 자체도 리포 규칙대로 **저널 + 타깃 스코프 리스**를 붙였습니다.

- 저널: `data/agent-handoff/codex-autonomy/fold6-camera-hint-setup-c6a4ca44/journal.json` (agent `cline`, purpose: setup-only, no application source edits)
- 리스: topic `fold6-camera-hint-setup`, ownerId `cline-fold6-camera-hint-setup-c6a4ca44`, role `desktop`
  - `begin` 결과(exit 0): `{"acquired":true,...,"writePaths":[".devin/PROMPTS/fold6-camera-hint-20260924.md","agent-prompts/devin-20260924-fold6-camera/brief.txt","agent-prompts/devin-20260924-fold6-camera/devin_operating_card.md","agent-prompts/devin-20260924-fold6-camera/setup_audit_20260924.md"]}`
  - 만료 2026-09-24T11:39:08Z / 매니페스트 `…/fold6-camera-hint-setup-c6a4ca44/scope-targets-fold6-camera-hint.json`
- 설치된 4개 문서(전부 소스 아님): `.devin/PROMPTS/fold6-camera-hint-20260924.md`(붙여넣기 카드), 이 문서, `brief.txt`, `devin_operating_card.md`
- 체크포인트(`codex_work_checkpoint.py`)는 **적용 대상 아님**: 4개 모두 새 파일(`sha256: null`)이라 되돌릴 preimage 가 없습니다. 기존 파일 편집이 필요해지면 그때 `begin` 합니다.

툴 실측 보강:
- `-Action status -Json -TargetManifest __patch_drop__/nova-snapshot-targets.json` → `{"reason":"preimage-changed","repositoryWideHold":false}`, exit 6.
  → `nova-oneshot-camera` 리스의 **기록 preimage 가 현재 바이트와 다름이 툴로 확인**됐습니다(§3의 해시 관찰과 일치). 그 리스로 `apply` 하면 거부됩니다.
- 리스 5건 모두 `ownerState: unknown` · `heartbeatState: absent` — TTL 은 살아 있으므로 **live 로 취급**하고 빼앗지 않습니다.
- `-Action status` 는 매니페스트를 줘도 소유자 신원을 모르므로 **자기 리스도 conflict 로 셉니다**(`targetConflict.conflictingLeaseCount: 1`, `conflictingPaths` = 내 4개 경로). 이는 홀드가 아니라 정상 표시입니다.
- 카메라 소스 수정용 리스는 **데빈이 자기 taskId로 새로 `begin`** 해야 합니다(이 문서용 리스는 소스 파일을 잡지 않습니다).

---
최초 점검은 읽기 전용이었고, 이후 위 문서 4개와 `data/agent-handoff/…` 기록만 생성했을 뿐 애플리케이션 소스(`main/`, `src/test/`)는 변경하지 않았습니다. 검증된 사실(파일 SHA-256, lease.json 값, 라우터·plan·lease 도구 실행 출력)과 미검증 항목(실기기, 프로브 부재)을 구분해 적었습니다.




