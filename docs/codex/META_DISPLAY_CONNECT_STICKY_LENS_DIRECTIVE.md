# Codex 지시: Meta Ray-Ban Display 연결 대기 고착 + 안경 링크 재사용

**Project Root:** `C:\AbandonWare\demo-1\demo-1\src`  
**작성:** 2026-09-18  
**증거:** 사용자 실기기 영상(첨부) + Fold6 쪽 힌트/기능은 정상, 안경만 연결 대기 고착.  
**스킬:** `$demo1-meta-display-webapp` / `$demo1-meta-display-sync-client` / `$demo1-meta-display-simple-caption` / `$demo1-meta-display-verification` / `$demo1-conversate-hint-evidence`(힌트 회귀 금지) / `$demo1-agent-api-spend-guard`

---

## 결론(사용자 확인)

1. **기능·힌트 송출은 된다.** Fold6/Conversate 파이프라인과 힌트 출력은 OK. 이번 작업에서 힌트/FAST/근거 로직을 건드리지 말 것.
2. **문제는 Meta Ray-Ban Display(안경) 쪽 연결.** 연결 과정 UI는 보이지만 안경은 **계속 연결 대기**이고 Connect가 완료되지 않는다.
3. **안경 링크(렌즈 연결 URL)를 매번 새로 만들기 싫다.** 디버깅 사용자는 1명. **맨 마지막에 발급했던 안경 링크를 이후에도 그대로 재사용**해 접속하면 자동/안정적으로 붙게 할 것.

---

## 목표 (DoD)

A. 안경 Web App이 Fold6 producer와 **연결 완료 상태**까지 도달한다 (대기/PAIRING/CONNECTING에 영구 고착되지 않음).  
B. **마지막 발급 안경 링크**(UI: 안경 연결 주소 / `meta/index.html#view=<64hex>` / `fb-viewapp://…`)를 **디버그 모드에서 sticky 재사용** 가능. 서버 재시작·짧은 만료 때문에 “매번 새 링크”가 강제되지 않게.  
C. 렌즈에는 `$demo1-meta-display-simple-caption` 계약 유지(대화·짧은 힌트만). ACK/CONNECTED를 광학 표시 성공으로 오인하지 말 것 (`meta/receiver.js` 주석과 동일).  
D. 힌트 회귀 없음. 유료 다모델/불필요 live soak 금지. 테스트 그린 후 사용자 자가검증이면 중단 가능.

---

## 관측된 코드 시임 (가설 — 검증 후 수정)

| 영역 | 경로 | 관련 동작 |
|------|------|-----------|
| 안경 링크 발급 | `DisplayConversateController.lens/link` | 매번 **새 64hex 토큰**, `expiresAt = now + 43_200_000` (12h). **같은 owner 기존 grant 삭제** 후 put → “마지막 링크 재사용”과 충돌 가능 |
| 링크 조회 | `lens/text` | grant 없거나 만료/`bindings` 불일치 → `404 lens_link_expired` |
| 클라이언트 캐시 | `display-conversate.js` `storedLensLink` / `lensLink` | `localStorage` `awx.display.lens.*`에 저장, 만료·403/404면 재발급 |
| 안경 셸 | `static/assets/display/meta/*`, `receiver.js` | 초기 `connection:'CONNECTING'`, poll 실패 시 `RECONNECTING` |
| Fold6 UI | `static/assets/display/app.js` | `PAIRING`=승인 대기, `linkPending`, `showLensLink`, `connect-lens` |
| 페어 | `link/code`, `link/join`, `link/approve` | 6자리/확인번호 페어링 — **렌즈 `#view=` 토큰 경로와 혼동 금지** |

**가설(비구속):** (1) 안경 URL의 token이 서버 메모리 `lensGrants`에서 이미 만료/교체됨 (2) 안경 페이지는 CONNECTING인데 producer binding/`linked`가 안 맞음 (3) Meta Web App은 HTTPS 공개 URL 필요 — 로컬/만료 deep link면 Connect만 돌고 세션이 안 붙음. 공식: https://wearables.developer.meta.com/docs/develop/webapps/test/ · setup · troubleshooting.

---

## 수정 지시

### 1) Sticky 안경 링크 (디버그/단일 사용자)

- `lens/link`: 디버그 플래그 또는 기존 유효 grant가 있으면 **동일 token·expires 연장(또는 긴 TTL)** 로 반환. owner당 “마지막 링크” 1개를 재사용.
- 클라이언트: `storedLensLink`가 서버에서도 유효하면 **새 발급 없이** 같은 URL 유지. UI 문구: “유효한 동안 같은 주소 재사용”.
- 서버 재시작으로 in-memory `lensGrants`가 날아가면: 개발용 durable store(기존 `.secrets`/로컬 파일 패턴 중 **이미 있는** 시임 우선) 또는 재연결 시 동일 token re-bind. 새 비밀 저장소 스택 신설 금지.
- **프로덕션 기본 보안을 깨지 말 것.** `AWX_DISPLAY_STICKY_LENS=1` / `gpt-search`식 기존 env 패턴 등 **명시적 디버그 opt-in**.

### 2) 안경 연결 대기 고착

- 영상·Fold6 진단에서 `connection` / `linkPending` / `linked` / `role` / relay subscribers / lens token validity를 대조.
- 안경 `meta/index.html#view=…` 경로가 `lens/text` 또는 poll/bootstrap 중 어디서 CONNECTING에 남는지 좁히기.
- PAIRING(승인 대기) vs CONNECTING(전송) vs linked=false 를 UI/로그에 구분해 오진 방지.
- Meta 쪽: 공개 HTTPS, Developer Mode, App Connections → Web Apps → Connect, 같은 URL 재등록. 코드만으로 Meta 앱 페어링을 대체하지 말 것.
- **최소 패치.** DAT/공식 Web App/custom relay를 한 변경에 섞지 말 것 (`demo1-core-request-router`).

### 3) 하지 말 것

- 힌트/ConversateQuestionPolicy/ApiCue “근거 부족” 패치 되돌리기
- 전사(ASR) 파이프라인 대규모 변경
- 소유하지 않은 18180 프로세스 무단 재시작 (승인·task-owned만)
- 유료 Exa/다모델 검증 남발

---

## 검증

1. 같은 안경 링크로 2회 이상 접속 → token 동일(디버그 모드), `lens/text` 200.
2. Fold6에서 힌트 생성 → 안경에 짧은 힌트/캡션 표시(또는 명확한 linked 상태). “연결 대기” 영구 고착 해소.
3. 기존 Conversate/Display 클라이언트 테스트 회귀 없음.
4. 라이브는 사용자 자가검증 가능하면 토큰 아끼고 중단 (`demo1-agent-api-spend-guard` self-verify stop).

## 종료 보고

`stickyLens=…`, `connectStateBefore/After=…`, `files=…`, `hintRegression=none`, `live=user-verify|owned-restart`.
