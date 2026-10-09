외부 API: 세션 유료 호출 6건(모두 chatgpt-oauth:gpt-5.6-luna, generation) / 상한 20 — 잔여 14. 401/403/429: 0건. 공개 /chat 전송: 0건(로컬만 사용, [devin-test] 프리픽스).

# Release 준비 최종 보고 — devin-interview-release-main-a387f3c3

**결론: PASS (준비 완료) — 10/13 공개 마감 가능.** main 교체·push는 미실행, 사용자 승인 대기.

## A1. W0 기준값 (fetch exit 0, 2026-10-09 확인)

| 항목 | 값 |
|---|---|
| 현재 브랜치 | codex/owned-runtime-browser-restart |
| 로컬 HEAD | `62c83392` (auto-commit 11:28; 작업 시작 시 `6a8d0cd2`, +1 자동커밋 DRIFT) |
| origin/main | `b2eaba4679f7` |
| origin/codex/owned-runtime-browser-restart | `a3754a3f8faa` |
| merge-base(HEAD,b2eaba4) | 없음 (공통 조상 없음, merge 불가) |
| 원격 대비 ahead | 시작 시 3 (6a8d0cd2, a95fc15e, 1c57582e) → 현재 4 (+62c83392) |
| status --porcelain | 시작 101 → 현재 115 (자동커밋·외부세션·본 작업 파일) |
| 총 커밋 | 451, 루트 `aa43b786` |

## A2. Display 보류

- `display-freeze.md` 작성 — 10/13까지 보류, 삭제 금지, 백엔드 검증 제외 명기.
- 본 세션이 Display 경로에 쓴 횟수: **0** (lease·checkpoint·journal에 기록).
- EXTERNAL_DRIFT: 외부 세션(nova-focus-keepalive lease 보유)이
  `display-focus*.js`, `NovaFocusState.java`, `display/index.html`을 편집 —
  DevWatch가 11:22 자동 재빌드 → 11:27 복구. 본 작업 산출물에 포함하지 않음.

## A3. T1–T5 3회 연속

| 회차 | T1 부팅 | T2 채팅 | T3 RAG 근거 | T4 fail-soft | T5 복구 |
|---|---|---|---|---|---|
| R1 | PASS ready 11:11 pid56252 | PASS 200/gpt-5.6-luna | PASS ragUsed=T 3×WEB | PASS 66/66 | PASS |
| R2 | PASS ready 11:51 pid22212 | PASS 200/gpt-5.6-luna | PASS ragUsed=T 3×WEB 2967c | PASS cmd/c exit0 66/66 | PASS |
| R3 | PASS ready 12:19 health UP | PASS 200/gpt-5.6-luna | PASS ragUsed=T 3×WEB 3147c | PASS cleanTest exit0 66/66 (12:24) | PASS |

- T4 대상: WebFailSoftSearchAspectTest(46) + FailSoftQueryAugmentAspectTest(8)
  + FallbackAwareChatModelApiFirstRoutingTest(12) = 66건, 0 fail, 3회 모두.
- 관찰된 건전한 fail-soft (PASS와 별개 기록): zero-result 시
  `RETRIEVAL ZERO_RESULT → DEGRADE` + `citation_miss → ISOLATE_EVIDENCE` —
  HTTP 200 유지, 서버 불사.
- 관찰된 이슈: 라운드 간 세션 기본 모델 드리프트(응답 gpt-5.5) →
  `strictModelSelection`+`model` 고정으로 해결, 기록됨.

## A4. 비밀 스캔 (값 출력 없음)

- 추적 블롭: HEAD 7,257파일 / RC 7,160파일 — findings 94 전부 픽스처
  (synthetic 22 + test 72), **needs-review 0, 실제 키 0건**.
- 이력 픽액스 451커밋 × 12 패턴: **0건**. 재발급 필요 목록 없음.
- `.env.example` ×2: 빈 플레이스홀더만. `.env`/`.secrets` 미열람.
- 상세: `w4-secret-audit.md`. 스캐너: `scripts/release_rc_secret_scan.py`.
- 부수 수정(lease scope): git_secret_guard.ps1 비ASCII 경로 크래시 수정 +
  회귀 테스트 2개 (cycle-03 verified, prepush 17/17).

## A5. rm --cached 후보 + RC diff

후보(실행 전, 승인 필요): `app/quarantine/`(76), `uploads/`(34, 대화로그 포함),
`scratch/`(16), `_patch_artifacts/`(5), `__patch_drop__/`(1),
`agent-prompts/.../source_inventory.json`(1.86MB) — 총 132.

- RC worktree: `C:\AbandonWare\demo-1\rc-worktree`, 브랜치 `release/rc-20261013` @ `a3754a3`.
- diff vs b2eaba4: 3,087파일 +388,031/−34,983. main-only 121건 = stale 스킬·레거시
  SelfAsk 중복 — **살려야 할 파일 없음** (README/LICENSE/빌드 파일 양쪽 존재).
- 상세: `w5-rc-snapshot.md`, `var/w5-rc-diff-{stat,names}.txt`.

## A6. 교체 제안서 (force push 없는 경로 1순위)

- **A(권장)**: `archive/main-b2eaba4` 브랜치로 옛 main 보존 → 기본 브랜치를
  `release/rc-20261013`로 지정. force push 0.
- **B**: archive 보존 후 `push :main`(삭제, force 아님) → RC를 main으로 push.
  공백 짧음, 보호규칙 확인 필요.
- **C**: 10/13까지 교체 보류 (데모는 로컬 터널로 가능).
- 상세: `w5-rc-snapshot.md` §3. **어떤 push도 아직 실행하지 않음.**

## A7. 보호 파일 쓰기 = 0

- Display/static-assets·build/desktop-meta-display·Display 컨트롤러·
  NaverSearchService·.env·.secrets·git_ship_easy.py — 본 세션 쓰기 0건.
- 외부 세션 변경은 EXTERNAL_DRIFT로 기록(A2).
- 수정한 파일(lease scope 내): `scripts/test_model_policy.py`(+import os, cycle-02),
  `scripts/git_secret_guard.ps1`, `scripts/test_git_secret_guard_prepush.py`,
  `scripts/release_rc_secret_scan.py` (cycle-03). 전부 checkpoint verified.

## A8. git push 실행 횟수 = 0

fetch 1회(W0, 읽기). push/merge/reset/rebase/이력재작성 0건.

## 데모 증거 (비밀값·개인정보 제거)

- T2: POST /api/chat/sync `{message:"[devin-test] ...", model:"chatgpt-oauth:gpt-5.6-luna",
  strictModelSelection:true}` → 200, `modelUsed=gpt-5.6-luna`, 본문 존재.
- T3: 동일 + `useRag:true, useWebSearch:true, searchMode:"FORCE_DEEP"` →
  200, `ragUsed:true`, evidence 3건 kind=WEB, 본문 ~3,000자.
- T5: `start_rag_stack.ps1 -ForceRestart` ×3 → 매회 `actuator/health` UP,
  부팅 로그 `READY` (runs 110813/114814/121232).

## ASK_ONCE — 사용자 선택 필요 (2건)

**Q1. main 교체 방식**
- (A) archive 보존 + 기본 브랜치를 release/rc-20261013로 — 권장, force push 0
- (B) archive 보존 + 승인된 main 교체(delete→재생성 경로, force 아님)
- (C) 10/13까지 교체 보류

**Q2. RC 기준 커밋**
- (a) `a3754a3` — 현재 RC 브랜치(원격과 동일, 검증 기준과 일치) — 권장
- (b) 로컬 HEAD `62c83392` — 미푸시 자동커밋 4건 포함
- (c) 추가 수정 후 새 커밋

## 산출물

```
docs/reports/agent-reviews/devin-interview-release-main-a387f3c3/
  BASELINE.md  display-freeze.md  w4-secret-audit.md
  w5-rc-snapshot.md  README.draft.md  report.md
data/agent-handoff/codex-autonomy/devin-interview-release-main-04829e0c/
  journal.json  cycle-01..03/  verify-cycle-02,03/
var/w4-secret-scan-{head,rc}.json  var/w5-rc-diff-{stat,names}.txt
var/t{2,4}-round*-*.log
C:\AbandonWare\demo-1\rc-worktree  (branch release/rc-20261013)
```
