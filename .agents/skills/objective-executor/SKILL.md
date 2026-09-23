---
name: objective-executor
description: "Use for open-ended multi-step objectives needing investigation, implementation, test"
triggers:
  - user
  - model
---

# objective-executor

상위 조정 Skill. 본문을 복제하지 말고 아래 기존 Skill을 순서에 맞게 호출한다.

## Flow
1. 목적·성공 조건·하드 제약 추출 (사용자 명시 > 도메인 표준 > AGENTS/rules > 휴리스틱).
2. 붙여넣은 제품 브리프가 **두 개 이상 이음새**(힌트 입력 맥락 + Fold 백그라운드 수음, 영상+설정+늦은 응답 등)면 `python -B scripts/devin_task_orchestrate.py plan --brief-file <path>` (`$demo1-devin-source-orchestrator`)를 먼저 돌리고, 반환된 단계만 따른다. 스킬 30개를 @하지 말 것.
3. `source-inspection` / `demo1-project-root` 로 관련 코드만 조사.
4. 필요 시에만 전문 Skill 선택 (예: `demo1-conversate-hint-context`, `frontend-display-debug`, `rag-search-diagnosis`, `meta-rayban-display`, `safe-source-edit`). 단계 JSON의 `skill`/`tools`/`skip`이 이 목록보다 이긴다.
5. 애매하거나 비가역 위험이 있으면 `positive-negative-neutral-judge` (또는 `demo1-triad-deliberation`)로 가설→반례→심판 후 채택. 사용자가 이미 설정으로 조절하라고 한 이음새는 skip.
6. 최소 diff로 구현. 비밀값 출력 금지. openssl 관련 키 이름·값·형식·구조 변경 금지.
7. `spring-build-test` / `compile-verify-smoke` / `demo1-toolchain-auto-select` 로 검증. 힌트 지연/미반영은 `Debug-Meta-Display.bat -Action status`가 기본.
8. Java/Spring 라이브 반영이 필요하면 사용자가 재시작을 요청했을 때만 `force-restart-meta-display` / `start-rag-reload` / `demo1-dev-reload`. 작업 대상 서버의 Close/Start 쌍은 AGENTS DEMO1-SERVER-LIFECYCLE-VERIFY.
9. `regression-check` / `demo1-api-spec-drift-guard` 로 계약 드리프트 점검.
10. 성공 조건과 실제 결과를 비교하고, 충족 시 종료·변경·검증만 보고.

## Anti-patterns
- Skill을 전부 호출하지 말 것.
- Plan만 하고 끝내라는 요청이 아니면, Devin Local Plan은 구현 전 승인 단계일 뿐이므로 사용자가 실행을 원하면 승인 후 구현까지 이어갈 것.
- Fold 안경 표시 설정의 전사/힌트 유지·페이지 간격 **그리고 힌트 생성 주기**(20초 hold, ~2초 페이지 하한, 2.5초 quiet, 10초 cooldown, 180초 force-hint)는 **소스 패치**다. YAML/스킬에 적힌 숫자는 기본값일 뿐 불변이 아니다. 저장값을 기억하고, 저장 직후와 재접속 후 **실제 주기·만료·트리거가 바뀌어야** 한다. 마지막 페이지에 맞춘 간격 당김은 금지. `$demo1-meta-display-simple-caption` + AGENTS.md DEMO1-META-RAYBAN-DISPLAY-RUNTIME을 따른다. 보고서만 쓰지 말 것.
- Grok Bot 설정·`.agents` 원본을 삭제/덮어쓰지 말 것.

## Large patches
- Prefer Devin Local **New worktree** so Grok/Codex/main workspace stay untouched; Merge only when green.
- Use Subagents / Quick Review: investigate · implement · verify as separate contexts when the objective is large.
- For Meta Display objectives, bias skill picks toward `demo1-devin-source-orchestrator` (multi-seam paste), `meta-rayban-display`, `demo1-conversate-hint-context`, `frontend-display-debug`, `start-rag-reload` (only if live recycle requested), `positive-negative-neutral-judge` when tradeoffs are unclear.

