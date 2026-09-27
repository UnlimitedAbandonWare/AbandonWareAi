# Devin에 스킬 잔뜩 @하지 않는 법

## 왜
입력창에 스킬 30~40개를 붙이면 선택이 흐려지고 토큰만 늘어난다.
Devin은 Skill `description`만 보고 필요할 때 본문을 읽는다. AGENTS.md + `.windsurf/rules`는 이미 자동이다.

## 추천
1. **최소**: `@objective-executor @demo1-devin-source-orchestrator @meta-rayban-display` + 목적
2. **일상**: `meta-display-paste-daily.md`
3. **디버그**: `meta-display-paste-debug.md`
4. 붙여넣은 긴 제품 브리프(힌트 입력 맥락 + 수음 등 **이음새 2개 이상**): 목적 적기 전에 `python -B scripts/devin_task_orchestrate.py plan --brief-file <path>` 결과를 따른다.

## Meta 계열에 보통 포함
- demo1-devin-source-orchestrator (다이음새 단계 계획; 스킬 30개 @ 대체)
- meta-rayban-display / demo1-meta-display-simple-caption
- demo1-conversate-hint-context (과거 대화 **입력** 범위; 출력 길이·표시 TTL과 별개)
- demo1-meta-display-resume (이어하기)
- frontend-display-debug (UI/정적 깨짐 **및** Fold 백그라운드 수음)
- demo1-core-request-router
- positive-negative-neutral-judge
- demo1-agent-api-spend-guard
- safe-source-edit / compile-verify-smoke / start-rag-reload (필요할 때만)

## 평소 Meta 패치에 안 붙여도 되는 것 (지금 목록에서)
- archive-search / archive-restore
- patchdrop-* / macsrc-* / upload-secrets
- glm-offload / token-efficient-agents (전역 취향)
- ablation-harmony / forecasting-minority / awx-source-surgeon
- run-pipeline (스크립트 프로브 전용)
- 같은 스킬 중복 @

## 한 줄 요약
많이 고르지 말고, **오케스트레이터 + Meta 본체**만 붙이고 나머지는 자동 선택에 맡겨라.
