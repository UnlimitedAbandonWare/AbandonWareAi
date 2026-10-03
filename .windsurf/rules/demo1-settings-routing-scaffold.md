---
trigger: glob
globs: "**/settings*.html,**/settings-page.js,**/settings-routing.js,**/SETTINGS_ROUTING*,**/settings_routing*,**/SettingsPageController.java,**/RoutingSettings*.java"
---
# demo1-settings-routing-scaffold

Plan6 설정·역할별 모델 라우팅 구현 시 적용되는 안전 가드레일 (always-on).
계약 SSOT: `docs/SETTINGS_ROUTING_CONTRACT.md`. 회귀 명세:
`src/test/resources/fixtures/settings_routing_specs/settings_routing_96_specs.json`.
사전 검증: `python -B scripts/check_settings_routing_invariants.py --root .`.

## 절대 금지 경계 (7개)

1. `main/resources/static/js/chat.js` 수정 0 — 스캐폴드 기준 실측 7407행
   (지시서 표기 7408과 1행 차이는 카운팅 관례 차이; sha256 `4225d944…`가 기준).
   설정 브리지는 신규 파일(`settings-bridge.js` 등)로만 연결한다.
2. DB 스키마 수정 0 — 새 테이블 금지. 라우팅 설정은 기존
   `configuration_settings` 1행(JSON) + 실행 리비전은 `ChatRunRegistry.Run`
   메모리에 귀속.
3. 보안 완화 금지 — `AppSecurityConfig.java`의 permitAll 확대·필터 무력화 금지.
   라우팅 설정 API는 기존 POST 관리자 보호 경계 안에 둔다.
4. `SYSTEM_PROMPT`는 `SettingsController.PUBLIC_SETTING_KEYS`에 추가 금지 —
   GET 응답·POST 저장 모두 비공개 유지(허용 목록 외 키는 400 거부 유지).
5. `OPENAI_MODEL` 등 민감 키는 `SettingsControllerSecretMaskAspect`의
   `isSensitiveKey` 마스킹/제거 대상 — 직접 수정하거나 마스킹 예외를 추가하지
   않는다. `nova.security.settings.allowSecretUpdate` 활성화 금지.
   서버 모델 변경은 전용 라우팅 저장 경로로만.
6. `chat.recoveryDraft`는 `sessionStorage`만 사용한다 — `localStorage`로
   옮기거나 병행 저장 금지 (chat.js:545 저장, :7392-7397 복구·삭제).
7. Ray-Ban Focus 설정은 **POST `/api/assist/display/focus/settings/read`** +
   `focusBinding`(기기 소유권·epoch·RUNNING) 검증 경로만 사용 — 소유권 검증
   없는 일반 GET 엔드포인트 신설 금지.

## 위반 탐지

- 사전: `python -B scripts/check_settings_routing_invariants.py --root .`
  (위반 시 exit 1, JSON 리포트 출력).
- 회귀: `.\gradlew.bat :test --tests com.example.lms.service.routing.SettingsRoutingContractTest`.
- 구현 브리프: `agent-prompts/devin-settings-routing-v3-brief.md`.
