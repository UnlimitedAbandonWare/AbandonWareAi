# DEVIN HANDOFF — Plan6 설정·역할별 모델 라우팅 구현 브리프 (v3)

대상 에이전트: Devin (제품 소스 구현 전담). 스캐폴드(문서·룰·검증기·테스트
하네스)는 배치 완료 — 이 브리프는 **제품 소스 구현 범위만** 인계한다.
생성 근거 지시서: `PASTE_CODEX_PLAN6_SCAFFOLD_20261002`, 설계 원본:
`C:\Users\nninn\Downloads\Plan6.txt`.

## 읽기 순서 (먼저 읽고 시작)

1. `docs/SETTINGS_ROUTING_CONTRACT.md` — 계약 SSOT (6역할·0-call·리비전·
   폐쇄 fallback·5단계 관측·보안 경계).
2. `.agents/rules/devin-settings-source-guard.md` — 7대 절대 금지.
3. `src/test/resources/fixtures/settings_routing_specs/settings_routing_96_specs.json`
   — 96개 회귀 명세 (kind=invariant 지금 검증 / pending 구현 후 활성화).
4. `src/test/java/com/example/lms/service/routing/SettingsRoutingContractTest.java`
   — 계약 테스트 하네스 (pending 구간은 구현 클래스가 생기면 활성화).

## 수정 허용 파일 (화이트리스트)

| 파일 | 작업 |
|---|---|
| `main/java/com/example/lms/api/SettingsPageController.java` | 신규 — `/settings` 페이지 서빙 |
| `main/java/com/example/lms/api/SettingsRoutingController.java` | 신규 또는 분기 — `POST /api/settings/routing/read|preview|save` (기존 관리자 POST 보호 재사용) |
| `main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java` | 6역할 중 MAIN_DEFAULT/LIGHT/HIGH 소비 연결 |
| `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java` | CallArgs 역할 인자 해석 추가(기존 5/7/8 보존) + 명시 프로필 시 fallback 허용 목록 바운드 |
| `main/java/com/example/lms/service/chat/ChatRunRegistry.java` | Run 리비전 고정(실행 시작 스냅숏 귀속) |
| `main/java/com/example/lms/service/rag/SelfAskPlanner.java` | lane 설정이 저장 프로필을 읽는 경로(필요 시) |
| `main/java/com/example/lms/service/routing/` 하위 신규 클래스 | 프로필 모델·리비전·역할 카탈로그 등 |
| `main/resources/templates/chat-ui.html` | 설정 링크 + 브리지 로드 **10줄 이내 diff만** |
| `main/resources/static/js/settings-bridge.js` | 신규 — chat-ui 브리지 |
| `main/resources/static/js/settings-api.js` | 신규 — `/api/settings/routing/*` 클라이언트 |

## 절대 금지 파일/영역

- `main/resources/static/js/chat.js` — 0줄 수정 (sha256 `4225d944…` 불변).
- `main/java/com/example/lms/config/AppSecurityConfig.java` — permitAll 확대
  금지. 라우팅 API는 기존 POST 관리자 보호 아래.
- DB DDL/새 테이블 — `configuration_settings` 1행 JSON + `ChatRunRegistry.Run`
  메모리 리비전만.
- `SettingsController.PUBLIC_SETTING_KEYS`에 `SYSTEM_PROMPT` 추가 금지.
- `SettingsControllerSecretMaskAspect.isSensitiveKey` 완화/예외 금지 —
  `nova.security.settings.allowSecretUpdate` 활성화 금지.
- `RetrieverChainConfig`의 Jev `wrapIfEnabled` 외 신규 래핑/분류기 추가 금지.
- `DisplayConversateController` Focus 설정의 무검증 GET 신설 금지.
- `model-strategy.js`의 `manual/auto-moe/backend-default` 체계와 메인
  `preferred/strict/auto`를 임의 대응시키지 않는다.

## 5단계 구현 순서 (각 단계 RED→GREEN)

1. **WP1 역할 카탈로그 + Preview 0-call** — 6역할 키·등록 경로 검증·
   preview가 실제 모델 호출 0회로 매핑만 반환.
2. **WP2 리비전 저장** — ConfigurationSetting 1행 JSON + 리비전·409 충돌 +
   재조회 판정.
3. **WP3 6역할 소비 + AOP** — PolicyBasedModelRouter 3분기·SelfAskPlanner
   3lane 소비 + CallArgs 역할 해석 + 명시 프로필 fallback 바운드
   (LlmRouterAspect:725 widening은 프로필 실행에서만 제한).
4. **WP4 실행 관측** — 5단계 분리(선택→정책 경로→호출 시도→응답 관측→채택),
   Run 리비전 고정, 미관측은 null 표기.
5. **WP5 프론트 연결** — 설정 페이지·브리지·api js + chat-ui 10줄 diff.

## 검증 명령 (작업 전·후 동일)

```powershell
cd C:\AbandonWare\demo-1\demo-1\src
python -B scripts/check_settings_routing_invariants.py --root .   # PASS 유지
.\gradlew.bat :test --tests com.example.lms.service.routing.SettingsRoutingContractTest --no-daemon --console=plain
```

- 구현 진행 중 `pending` 명세가 하나씩 활성화되어야 한다. skipped가 남으면
  완료가 아니라 pending이다.
- chat.js 해시가 바뀌면 불변식 스크립트가 즉시 FAIL — 되돌린다.
- `sessionStorage` draft, PUBLIC_SETTING_KEYS, Jev 이중 래핑, Focus POST
  검증은 어느 단계에서도 깨지면 안 된다.
- 빌드 노트: `build.gradle.kts` `test.resources`는 이미 `setSrcDirs`로 수정됨
  (2026-10-02, 이 스캐폴드 작업). Kotlin DSL `srcDirs()`는 "추가"라 기본
  `src/test/resources` 트리가 중복되어 `:processTestResources`가 해당 디렉터리의
  임의 파일을 duplicate로 실패시키던 잠복 버그였음. 같은 패턴이 `test.java`
  (`srcDirs("src/test/java")`)에도 잠복해 있으나 javac가 파일 수준 중복을
  흡수해 무해 — 필요 시 동일하게 `setSrcDirs`로 정리 (현재는 미수정 유지).

## 보고 시

- 돌린 범위만 PASS; pending/미실행은 `NOT_RUN`/`skipped` 그대로.
- `실호출 0·비밀 열람 0·금지 파일 diff 0`을 보고 상단에 명시.
