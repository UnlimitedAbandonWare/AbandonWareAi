# Meta Ray-Ban Display — 공개 보류 카드 (W1)

결정: 2026-10-13 공개 범위에서 Display는 **삭제 없이 보류**(dot 우선순위 ⑤: Meta Display는 10/13 뒤로 보류). 코드·리소스 삭제 금지, 수정 금지, 백엔드 필수 테스트(T1~T5) 대상에서 제외.

## 보류 대상 경로 (2026-10-09 인벤토리)

- `main/resources/static/assets/display/**` — 22 files (index/lens/receiver/companion/diagnostics + meta/* + css/js)
- `build/desktop-meta-display/**` — 4,280 files (빌드 산출 동기화본; 소스 아님)
- `main/java/com/example/lms/assist/DisplayConversateController.java`
- `main/java/com/example/lms/api/MetaDisplayDbQueryController.java`
- `main/java/com/example/lms/api/MetaDisplayDbAdminController.java`

## 기존 on/off 게이트 (신규 플래그 생성 없음 — 기록만)

- 활성화는 Spring profile opt-in: launcher가 `local,meta-display` 부여 — `main/resources/application-meta-display.yml:1`
- `conversate.display-ttl-ms` — application-meta-display.yml:29 (`CONVERSATE_DISPLAY_TTL_MS`, default 20000)
- `display.enabled: true` — :126-127
- `display.phone-test-enabled` — :132 (`CONVERSATE_DISPLAY_PHONE_TEST_ENABLED`)
- `display.audio.enabled` — :134 (`CONVERSATE_DISPLAY_AUDIO_ENABLED`)
- `focus.default-enabled` — :138 (`CONVERSATE_FOCUS_DEFAULT_ENABLED`)
- `interview.enabled: false` — :156 (meta-display 프로필 내 면접 화면 OFF)
- 라우터 토글 `LLMROUTER_*` — :204-253; `chatgpt.oauth.enabled` — :279
- 관련 게이트(비Display): `demo.interview.enabled` — 메인 vs 면접 화면 분기

## 규칙 (이 세션)

- 이 경로들에 대한 이 세션 쓰기 횟수 = 0 (journal/checkpoint로 증명).
- 다른 세션의 변경은 EXTERNAL_DRIFT로만 기록.