# Devin 지시서 — 바이브 코딩용 admin 표면: 「없는 게 낫다」vs 「그냥 열어두기」
날짜: 2026-09-27 KST  
수신: **Devin**  
역할: **감안·옵션 비교·권고 THE ONE** (대규모 삭제/하드닝 구현은 사용자 승인 전 보류)  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
Prototype Light · secrets 미출력 · push/dd -A 금지 · AbandonWare3 폐기

## 사용자 의도 (한 줄)
바이브 코딩할 때 admin 같은 건 **차라리 없는 게 낫지 않냐**, 아니면 **접근을 자유롭게** 두자는 쪽.  
직전 madasin Codex 작업에서 `demo.auth.proto-open=true` 유지 → 로그아웃 후에도 관리자 URL 200 → “차단 PASS”를 종료 조건으로 쓰지 않음.

## Self-Ask
1. **요청:** 바이브 DX 기준으로 admin을 어떻게 둘지 Devin이 정리·권고.
2. **증거 (AGENTS `DEMO1-PROTOTYPE-AUTH-LIGHT`):**  
   - Auth = **PROTO_OPEN** (`demo.auth.proto-open` / `DEMO_AUTH_PROTO_OPEN`, `application-meta-display.yml`).  
   - 프로토타입이라 로그인 기대치 **의도적으로 낮음**. 사용자가 “harden” 하기 전 프로덕션 auth 강요 금지.  
   - proto-open이면 `AdminTokenGuardFilter`가 요청에 `ROLE_ADMIN`을 부여 → `.hasRole("ADMIN")`·이중 가드가 **토큰 없이 통과**. 여기에 **추가 role gate / 두 번째 토큰 검사**를 얹어 데모를 다시 막지 말 것.  
   - CSRF를 “고치려고” 끄지 말 것.  
   - **proto-open 켠 채 deploy/push는 하드스톱.**  
   - AGENTS “Prototype light mode (no admin, minimal default surface)”: 바이브에서 admin 설치·방화벽·스케줄 등록 등 **불필요**.  
   - admin 관련 Java가 다수 존재 (`AdminController`, `AdminTokenGuard*`, `*AdminController` …) — **이미 있는 코드 제국을 통째 삭제하는 건 바이브 기본값이 아님.**
3. **모호:** “admin 없다” = (A) UI/메뉴에서 숨김 (B) 라우트 비활성 (C) 코드 삭제 (D) proto-open으로 누구나 admin 권한.
4. **바꾸면 안 됨:** secrets, 실계정 검증, proto-open 공개 배포, CSRF 끄기, madasin F01–F04 회귀 깨기, “로그아웃 후 차단”을 바이브 성공 조건으로 승격.
5. **가장 작은 seam:** 정책/문서/스킬 정렬 + (선택) 바이브 프로필에서 admin 내비·진단 링크만 숨김. **SecurityConfig fail-close는 기본 금지.**

---

## 역할
| Devin (너) | 산출 |
|---|---|
| 옵션 비교 + 바이브 권고 THE ONE | `RECOMMENDATION.md` + (필요 시) 최소 패치 화이트리스트 |
| 구현 | **기본은 문서/스킬/AGENTS 포인터만.** 코드 삭제는 THE ONE이 “옵션 C”이고 사용자가 따로 OK할 때만 |

---

## 반드시 읽을 것
1. `AGENTS.md` — `DEMO1-PROTOTYPE-AUTH-LIGHT`, Prototype light (no admin…)
2. `application-meta-display.yml` (또는 실제 proto-open 설정 파일) — 플래그 위치
3. `AdminTokenGuardFilter.java` / `AdminTokenGuardInterceptor.java` — proto-open 동작
4. SecurityConfig / admin URL matcher (어디에 `/admin/**`가 걸리는지)
5. 직전 CONTINUE: `agent-prompts/madasin-codex-design-recovery-continue-20260927/CODEX_CONTINUE.md` C4 (proto-open 유지)
6. (참고) Codex PARTIAL: 로그아웃 후에도 `/admin/trace-snapshots`·진단 API 200 = **정책 결과이지 버그로 단정하지 말 것**

---

## 비교할 옵션 (이름 고정)

### Opt A — 「그냥 열어두기」(PROTO_OPEN 유지, 현행 강화)
- admin UI/API **존재**하지만 proto-open으로 자유 접근.
- 바이브 성공 조건에서 **로그인/로그아웃 차단 제거**.
- AGENTS/스킬에 “vibe: admin auth checks = N/A under proto-open” 명시.
- **장점:** 이미 SSOT와 일치, Codex/에이전트 DX 최소 마찰.  
- **단점:** “admin이 있다”는 착각·공개 배포 실수 위험 (하드스톱으로 완화).

### Opt B — 「없는 것처럼」(표면만 숨김, 코드·권한 유지)
- 바이브/meta-display 프로필에서 admin 내비·북마크·진단 링크 숨김 또는 `/chat` 중심 IA.
- API는 에이전트용으로 남기되 UI에서 “관리자 제품” 느낌 제거.
- proto-open은 유지 (접근은 자유, **눈에 안 띔**).
- **장점:** “없는 게 낫다”에 가깝고 삭제 리스크 낮음.  
- **단점:** URL 직접 치면 여전히 열림 (의도).

### Opt C — 「정말 없음」(라우트/빈 스텁, 대량 삭제 금지)
- prototype profile에서 `/admin/**`를 404 또는 chat으로 리다이렉트 **하거나** admin 페이지 템플릿만 비활성.
- **컨트롤러·가드 대량 삭제 금지** (회귀·다른 에이전트 경로 파괴).
- **장점:** 체감 “없음”.  
- **단점:** 에이전트 `/agent/db-context` 등 ADMIN-gated 경로와 충돌 가능 → 맵핑 표 필수.

### Opt D — 「harden」(로그인 필수·로그아웃 차단) — **바이브 기본 기각 후보**
- 사용자가 명시적으로 “harden” / “로그아웃 후 차단을 PASS로” 말하기 **전** 채택 금지.
- madasin 브라우저 종료 조건을 이걸로 “통과” 연출하지 말 것.

### Opt E — 하이브리드 (권고 후보)
- **바이브 기본 = A + B**: proto-open 유지 + admin 크롬 숨김 + 문서에 “auth exit criteria N/A”.  
- harden은 별도 프로필/플래그/사용자 한 마디 후에만.

---

## 산출물
폴더: `agent-prompts/devin-vibe-admin-surface-20260927/`

1. `RECOMMENDATION.md`
   - 옵션 표 (장단점·바이브 마찰·회귀 위험·공개배포 위험)
   - **THE ONE** (한 문장 + 이유)
   - “admin 없다”를 어떻게 정의했는지
   - 에이전트 경로(ADMIN-gated) 영향 표
   - Codex/Clean에게 넘길 문장 1개 (“바이브 검증에서 logout-block 쓰지 말 것” 등)
2. `AGENTS_PATCH_DRAFT.md` (선택) — `DEMO1-PROTOTYPE-AUTH-LIGHT`에 바이브 문장 3~5줄 **초안만** (적용은 사용자 OK 후 또는 THE ONE이 문서-only면 최소 diff)
3. 코드 패치: THE ONE이 B/C의 **최소 UI 숨김**일 때만 화이트리스트 파일 ≤5. Opt C 대량 삭제 금지.
4. 보고:
`	ext
DEVIN_VIBE_ADMIN: DONE|PARTIAL
THE_ONE: A|B|C|D|E
vibe_auth_exit: N/A-proto-open | ...
files: ...
NO_HARDEN_WITHOUT_ASK: confirmed
`

## 검증 (문서-only면 NOT_RUN OK)
- proto-open이 yml/AGENTS와 일치하는지만 확인.
- UI 숨김 패치 시: `/chat` 로드, admin 링크 부재 스크린/DOM — **로그아웃 차단 성공을 목표로 테스트하지 말 것.**

## 하지 말 것
- `demo.auth.proto-open=false`로 바꿔 “더 안전해 보이게” 하기
- CSRF 비활성
- Admin* Java 대량 삭제
- secrets / 실계정 / push
- madasin CONTINUE와 모순되게 logout-block을 PASS 조건으로 복원