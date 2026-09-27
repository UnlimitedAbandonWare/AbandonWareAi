# Codex 킥오프 — madasin 설계·UI 연결 규격 복구 (2026-09-26)

## 한 줄로 말할 때 (유저 → Codex)
> Project Root `C:\AbandonWare\demo-1\demo-1\src`에서 `C:\Users\nninn\Downloads\madasin_Codex_design_recovery_2026-09-26\Abandon.txt` + `ANALYSIS_REPORT.md`를 읽고 **do00부터 패치·검증까지** 진행해. 옛 소스 통째 이식/새 트레이스 엔진 금지. 이미 있는 기능은 보존하고, 보고서의 재현 간극만 최소 수정. **완료 = do09 종료 조건 PASS + fresh 테스트/빌드 증거. “파일만 읽음”은 완료가 아님.** push 금지.

## 완료 정의 (필수 — 이전 Codex “Read만 하고 종료” 금지)
- 완료 ≠ Abandon.txt를 읽음 / goal-objective만 읽음
- 완료 = do00–do09 중 **적용 가능한 패치 반영** + 해당 failing→green 테스트 + do09 검증 기록
- 이미 Desktop에서 해결된 항목은 no-op + 테스트 증거만 남기고 스킵

## Project Root / 패키지 SSOT
- 편집 루트: `C:\AbandonWare\demo-1\demo-1\src` (실제 Gradle root·sourceSet은 do00에서 확정)
- 지시서 패키지: `C:\Users\nninn\Downloads\madasin_Codex_design_recovery_2026-09-26\`
  - `Abandon.txt` (do00–do09)
  - `ANALYSIS_REPORT.md`
  - `evidence/EVIDENCE.md` (E01–E31)
  - `evidence/browser_probe_results.json` (10 fixture: 7 PASS / 3 REPRO)
  - `evidence/source_inventory.json`
- 미러: `agent-prompts/madasin-codex-design-recovery-20260926/CODEX_KICKOFF.md`

## 핵심 원칙
1. **옛 소스 통째 복원 금지.** 현재 madasin 계보 유지 + 연결 규격만 맞춤.
2. **트레이스 UI/엔진 신규 금지.** WeakMap 답변별 패널·DOMPurify·fail-closed·SSE 재연결 보존.
3. HTML 속 스크립트 재실행 방식으로 되돌리지 말 것 (안전한 정적 JS 컨트롤만).
4. secrets/원문 prompt 로그·출력 금지. push/`add -A`/force-push 금지. dirty/foreign staging 보존.
5. Jev `budget_skip`을 오류로 없애지 말 것. 유료/무료 기간 임의 전환 금지.
6. Soft-auto git only (`conditional_local_git`). AbandonWare3 재등록 금지.

## 재현된 간극 (우선 패치 — do01–do03)
| ID | 문제 | 방향 |
|----|------|------|
| F01 | metadata-only `<section>` 저장되는데 UI는 `details.search-trace`만 수용 → “트레이스 표시 불가” | producer/consumer shape 호환 (저장소 rewrite 금지) |
| F02 | HTML upsert는 OK, signal/score는 append 중복 | assistant 귀속 + summary upsert + event-id dedupe |
| F03 | 옛 filter/sort가 sanitizer에 제거됨 | 정적 JS 안전 컨트롤로 복원 (script 재실행 금지) |
| F04 | pointer↔assistant 연결 애매 | 명시적 assistantMessageId + v1 호환 reader; 불명확 시 임의 붙이기 금지 |

이어서 do04–do05 필터·관리자 발견성, do06–do07 Plan UNKNOWN / 가상표본 승격 분리, do08 회귀 fence, do09 문서·종료.

## 작업 순서
do00 SourceMap+baseline → do01 metadata 호환 → do02 typed diagnostics → do03 pointer 연결 → do04 안전 필터/정렬 → do05 관리자/설정 표시 → do06 Plan UNKNOWN → do07 전략 승격 가드 → do08 memory/attach/graph/embed fence → do09 계약 문서 + fresh 증거 후 **멈춤**

## Must NOT
- 새 RAG/DSL/트레이스 엔진, 옛 chat.js 통째 덮기
- DOMPurify/raw HTML subscriber/snapshot cap/AbortController 제거
- Plan을 무조건 전부 OFF, MoE 기본 전략 선택 자체 삭제
- UAW 포트폴리오 서술을 현재 사실로 채택
- mgain 다른 lease와 충돌 시 steal; CoW alternate-path
- “읽기만 하고 완료” 선언

## 최종 보고 (Abandon.txt 형식)
요약 / doXX 결과(Observation·Patch·Commands·Verification exit) / 긍정·부정·중립 / 재현·회귀 증거 / evidence_needed / 종료 판단
