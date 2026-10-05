---
trigger: model_decision
description: 'activate only for settings or model-routing product-source edit boundaries'
---

# DEVIN_SETTINGS_SOURCE_GUARD — 설정·라우팅 구현 시 소스 수정 절대 경계

Plan6 설정·역할별 모델 라우팅의 **제품 소스 구현 단계**에서 Devin(또는
소스 구현 에이전트)이 지키는 경계. 상위 규약은 AGENTS.md·
`.windsurf/rules/demo1-hard-constraints.md`·`.agents/rules/demo1-common-brief-rules.md`가 이긴다.
계약 SSOT: `docs/SETTINGS_ROUTING_CONTRACT.md`. 구현 브리프:
`agent-prompts/devin-settings-routing-v3-brief.md`.

## 7대 절대 금지

① `main/resources/static/js/chat.js` 수정 0 — 실측 7407행·sha256 `4225d944…`
   기준 불변. 프론트 연결은 신규 `settings-bridge.js`/`settings-api.js`와
   `chat-ui.html`의 10줄 이내 diff로만.
② DB 스키마 수정 0 — 새 테이블·컬럼·DDL 금지. 기존 `configuration_settings`
   1행 JSON + `ChatRunRegistry.Run` 리비전 귀속만 사용.
③ 보안 필터 임의 완화 금지 — `AppSecurityConfig.java` permitAll 확대,
   `AdminTokenGuardFilter` 무력화, `PROTO_OPEN` 판정 변경 모두 금지.
   `POST /api/settings/routing/*`는 기존 관리자 POST 보호를 재사용.
④ `SYSTEM_PROMPT` 비공개 — `SettingsController.PUBLIC_SETTING_KEYS`에
   `KEY_SYSTEM_PROMPT`/`"SYSTEM_PROMPT"` 추가 금지. 허용 목록 외 키 400 거부
   로직 유지.
⑤ 민감 키 마스킹 우회 금지 — `SettingsControllerSecretMaskAspect.isSensitiveKey`
   의 `openai`·`token`·`secret` 등 판별을 완화하거나 예외를 추가하지 않는다.
   `nova.security.settings.allowSecretUpdate=true` 설정 금지. HTTP 200을
   저장 성공으로 간주하지 말고 재조회로 확인.
⑥ `chat.recoveryDraft`는 `sessionStorage` 전용 — `localStorage` 사용·병행·
   이관 금지. 현재 탭 범위(sessionStorage의 탭 단위 동작)를 존중.
⑦ Ray-Ban Focus 설정 무검증 노출 금지 — 읽기도 POST
   `/api/assist/display/focus/settings/read` + `focusBinding` 소유권·epoch·
   RUNNING 검증. 연결 정보 없이 호출 가능한 일반 GET 신설 금지.

## 위반 시

- `python -B scripts/check_settings_routing_invariants.py --root .`가 exit 1로
  차단한다. 위반 항목은 되돌리고 정상 경로로 재구현.
- `SettingsRoutingContractTest`는 `invariant` 명세를 지금 검증하고
  `post_implementation` 명세는 구현 클래스가 존재할 때 활성화한다 —
  skipped는 PASS가 아니라 pending이다.
- 계약 자체를 바꾸려면 이 룰과 `docs/SETTINGS_ROUTING_CONTRACT.md`를 먼저
  갱신하고 journal에 `policy-conflict`로 근거를 남긴다 — 무음 반전 금지.
