---
doc_id: TRI-CTX-01
title: Architectural Invariants — Tri-Agent Shared Context
created_at: "2026-10-05T09:00:00+09:00"
expires_at: "PERPETUAL"
ttl_days: null
lifecycle: INVARIANT
validity_basis: "AGENTS.md + docs/agents-rules/* SSOT와 live tree 대조 (2026-10-05 KST); 사용자 명시 변경 전까지 불변으로 취급"
---

# 01. 아키텍처 불변 규격서 (Tri-Agent 공유)

> 이 문서는 demo-1 체크아웃의 **불변(INVARIANT) 운영 상수**만 모은다.
> 값이 바뀔 수 있는 스펙(모델·포트·한도)은 [03_LIVE_LLM_RAG_REGISTRY_90D.md](03_LIVE_LLM_RAG_REGISTRY_90D.md)으로 분리했다.

## Project Root SSOT

- **Project Root = `C:\AbandonWare\demo-1\demo-1\src`** — 이 체크아웃 하나가 유일한 코드 루트다.
  시작 cwd(`C:\Users\nninn` 등)에서 루트를 재유도하지 않는다; 착오 시
  `Set-Location -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src'` 선행.
- 활성 소스셋: 루트 `main/java`, `main/resources`, `src/test/java`
  (`build.gradle.kts` sourceSets). `:app` 모듈은 2026-10-01 이후 빈 상태.
  `project/src`, `app/src/main`, `demo-1`, `lms-core`, 백업·아카이브·빌드 산출물은 참조 전용.
- 툴체인: Java 17 + Gradle + Spring Boot. 기동 `Start-RAG.bat`, 재반영 DevWatch/ForceRestart,
  진단 `Debug-RAG.bat`/`Debug-Meta-Display.bat`. spring-boot-devtools 도입 금지.

## PROTO_OPEN 보안 경계

- 인증 모드 = `PROTO_OPEN` (플래그 `demo.auth.proto-open`, env `DEMO_AUTH_PROTO_OPEN`).
  프로토타입이므로 로그인/역할 게이트를 임의로 추가·강화하지 않는다 — "harden" 지시가 있을 때만.
- `proto-open`에서 `AdminTokenGuardFilter`가 모든 요청에 `ROLE_ADMIN`을 부여한다.
  추가 역할 게이트·이중 토큰 검사는 데모를 재차 막으므로 금지.
- **`proto-open` 상태로 공개 배포/푸시 절대 금지** — 경고가 아니라 하드 스톱.
- 비밀은 env/`.secrets/`/gitignore된 `application-local.yml`만. 평문 비밀 커밋·로그 출력 금지.
  상세: `docs/PROTOTYPE_AUTH.md`, `docs/agents-rules/DEMO1-PROTOTYPE-AUTH-LIGHT.md`.

## Git Local-First 원칙

- 기준선 = 현재 working tree + 활성 sourceSets + 통과한 compile/test/runtime.
  과거 커밋/HEAD는 복원 소스가 아니다. clean tree는 완료 조건이 아니다.
- 허용: `git status`/`diff`, 소유 경로 선택적 `add`, 스테이징 비밀 스캔 통과 후 로컬 커밋 1회
  — 유일 커밋 경로 `python -B scripts/agent_git_vibe_commit.py`
  (정책 `scripts/conditional_local_git.py`).
- 금지: push/pull/fetch/merge/rebase/reset/clean/history rewrite, `add -A`·`add .`·`commit -a`·
  `--no-verify`, 타 세션 스테이징 인수/해제, 비밀·대화·DB·모델·대형 로그 커밋, `.git`/`index.lock` 삭제.
- 복구 증거는 Git이 아니라 체크포인트 preimage 바이트+SHA-256
  (`data/agent-handoff/codex-autonomy/<taskId>/<cycle>/`).

## H2 lmsdb 단일 접근

- 로컬 DB = 파일 H2 `lmsdb` (`MODE=MariaDB`는 호환 모드, MariaDB 서버 아님).
- 유일 진입점: `python -B scripts/db_agent.py` (`status|tables|schema|query|apply|upsert-admin` …).
- **exit 3 = locked**: Start-RAG JVM이 `lmsdb.mv.db`를 점유 중이라는 뜻 — 서버 kill/파일 삭제 금지.
  읽기는 `--via auto` 라이브 폴백, 쓰기는 `--dry-run` 후 `--i-mean-it --allow-tables` 또는 승인.
- 비밀 env-only (`LMS_LOCAL_ADMIN_PASSWORD`, `LMS_DB_*`); 출력은 해시 마스킹.

## 셸·인코딩 계약

- 명령은 Windows PowerShell 5.1: `&&`/`||`/`2>/dev/null` 없음 — `;`와 `if ($LASTEXITCODE -eq 0)`.
- 콘솔 기본 CP949: UTF-8 파일·출력은 `[IO.File]::WriteAllText` + `UTF8Encoding($false)`로
  no-BOM을 유지한다. `data/agent-handoff`는 gitignore 영역 — 편집기 write 도구가 건너뛸 수 있다.

## 검증-판정 분리 (awx.debug.verify.v2)

- "도구가 돌았다" ≠ "타깃이 검증됐다" ≠ "빌드/테스트가 돌았다" ≠ "전체 검증 완료" — 4개 필드를 분리.
- 안 돌린 항목은 `run=skipped|blocked|not_observed`, PASS 카운트에 넣지 않는다.
- 로컬 서버 401/403은 `auth-blocked`이지 DOWN이 아니다.
- `fullVerification`은 선언 범위의 build+tests가 실제로 돌았을 때만.
- 주력 표면 = 메인 `/chat` chat-ui (로컬 `http://127.0.0.1:18180/chat`, 공개 `https://abandonwareai.kro.kr/chat`).
  면접 화면(`static/assets/interview/*`)은 로컬 디버그 전용 — 보조 증거이지 메인 판정이 아니다.

## 관련 문서

- [02_TRI_AGENT_ROLES_AND_HANDOFF.md](02_TRI_AGENT_ROLES_AND_HANDOFF.md) — 역할·리스·핸드오프 절차
- [03_LIVE_LLM_RAG_REGISTRY_90D.md](03_LIVE_LLM_RAG_REGISTRY_90D.md) — 가변 모델/라우팅 레지스트리
- [README.md](README.md) — 카탈로그 인덱스와 TTL 정책
