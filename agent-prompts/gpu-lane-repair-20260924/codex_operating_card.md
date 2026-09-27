# Codex Operating Card — gpu-lane-repair-20260924

스터프1(GPT-Pro)의 "플러그인을 전부 쓰지 말고 역할을 제한" 지시를 이 프로젝트 계약에 맞게 번역한 운영 카드. brief.md와 함께 사용.

## 원칙

- 한 번에 원인 후보 하나 — 재현 가능한 실패 테스트나 구조 탐침부터.
- production 코드 수정 후에는 해당 회귀 테스트 + 전체 검증을 새로 실행하고 `verification-before-completion` 기준으로 보고.
- 기존 구현을 우회하는 중복 서비스·새 계층 금지.
- 플러그인 전부 사용 금지 — 아래 역할 한정.

## 도구별 역할 제한

### 진단 절차 (systematic-debugging 해당)
1. 영상 증상 ↔ 현재 소스 연결부터.
2. 원인 후보 1개 → 실패 재현/탐침 → 패치 → 회귀+전체 검증.
3. 검증 언어: `사실`/`추정`/`근거 부족` + `run=skipped|blocked|not_observed` 구분. 도구 실행·HTTP 200·설정 파일 존재는 타겟-건강 판정이 아님.

### Browser (해당 시에만)
- 새 세션 재현 대상: `안녕?` 응답 HOLD 여부 / 일반 질문 `backend_unavailable` 여부 / admin 로그인→보호 URL / 잘못된 계정 차단 / 로그아웃 후 재차단.
- UI 문구만 보지 말고 HTTP 상태·서버 reasonCode·요청 식별자와 연결.
- 저장된 인증 상태 로드만으로 로그인 성공 증명 금지.
- 인증 상태·trace·쿠키·토큰은 Git/공개 자료에 남기지 않음.
- 프로젝트 참고: 익명 우선 프로젝트 — 로그인 검증은 합성 픽스처만. `demo.interview.enabled`/`demo.auth.proto-open` 무단 변경 금지.

### Git / GitHub
- 로컬 조건부 Git만 허용: 진입점 `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file>` — staged-blob 시크릿 스캔 통과 후 로컬 커밋 1회.
- 금지: push/pull/fetch/merge/rebase/reset/clean/history rewrite/`add -A`/`add .`/`commit -a`/`--no-verify`/외부 staged 경로 편입.
- GitHub 원격 SHA/diff/CI는 로컬 현재 소스의 보조 증거로만 — 오래된 snapshot을 라이브 소스보다 우선 금지. 우선 확인 대상: RagControl, ChatWorkflow, SecurityConfig, AdminTokenGuard, chat.js.

### Exa / 웹 조사
- 공식 규격 검증 전용: `docs.spring.io`, `playwright.dev`, `docs.github.com`, `docs.ollama.com`, `docs.nvidia.com`, provider 공식 문서.
- Spring Security 세션/CSRF/인증, Playwright 로그인/trace, Ollama GPU/keep_alive/num_ctx/API 필드처럼 소스만으로 확정 불가한 부분만.
- 블로그 예제를 Java 17·LangChain4j 1.0.1 코드에 바로 이식 금지. 확인 날짜+적용 버전을 짧게 기록.

### AWX Control Tower
- 실제 `compileJava`/test/기동 실패 시 생성된 정제 빌드 로그 경로만 `build_error_mine`에 전달.
- AWX 분류는 원인 후보를 좁히는 증거일 뿐 — 원본 stack trace와 현재 코드 경로를 다시 확인.
- 기본 AWX 정상이면 복구용 AWX 사용 금지.

### Computer
- Browser로 못 여는 localhost/Windows 콘솔/로컬 전용 UI의 실제 조작에만.
- 소스 탐색·코드 수정 목적 사용 금지 — Git/파일/테스트 도구 우선.

### glm_worker (독립 검토)
- 1차 수정 완료 후에만: "패치가 증상을 숨겼나, 근본 원인을 없앴나" / "관리자 권한을 과도하게 넓혔나" / "검증 불필요와 검증 실패를 재혼동하지 않나"를 반박 관점으로 검사.
- glm_worker의 동의 자체를 검증 성공으로 취급 금지.
- 시크릿·credentials·`.secrets/` 경로는 어떤 외부 검토자에게도 전송 금지.

## 프로젝트 고유 계약 (요약 — 원문은 AGENTS.md/스킬)

- Work Ledger: status→journal open→lease→checkpoint begin/patch/seal/verify/finish→note→status_doc. `begin` 실패 = 변경 시작 금지.
- Model lock: `ollama ls`/`ollama show` 라이브 인벤토리가 SSOT; banned 태그는 alias 맵 사용, 무단 pull/기본값 적용 금지. `scripts/check-model-lock.ps1`로 검증.
- Mutable spec: 포트·모델·타이밍·UI 문구는 SSOT에서 재읽는 변수 — 주석/구문서 숫자 신뢰 금지.
- Spend guard: 로컬 우선, 유료 API 호출은 라우팅 정책 내에서만; 검증 목적 반복 호출 자제.
- Prototype auth: 로그인/권한 게이트 강화 요구 없음 — 명시적 "harden" 지시 전까지 prod 수준 인증 시험 금지.
- openssl 관련 키 이름/값/형식/구조: 불변.
- 한국어 reasoning/code comments, 설계/제작/검토/실험 중 하나로 호출 성격 표기 + 한 줄 이유.
