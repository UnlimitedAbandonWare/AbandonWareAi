# AbandonWare AI — Dynamic RAG Orchestration Platform

Java Spring Boot 3 + Next.js BFF + RAG(LangChain4j·LangGraph4j·Lucene nori) — 로컬 Ollama와 OAuth·API 모델을 단일 라우팅 정책으로 오케스트레이션하는 대화형 검색·답변 서버.

## 실행

```bat
Start-RAG.bat
```

내부적으로 `scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -Preload`를 호출한다.
컴파일 → Spring 부팅 → 헬스 통과까지 자동이며, DevWatch가 소스 변경을 감지해 재빌드한다.

| 표면 | 주소 |
|---|---|
| 로컬 채팅 | http://127.0.0.1:18180/chat |
| 공개 채팅 (cloudflared) | https://abandonwareai.kro.kr/chat |
| 헬스 (management) | http://127.0.0.1:18181/actuator/health |

포트: Spring 18180 / management 18181 / display-relay 18182 / Ollama 11434.

## 필요한 환경 변수 (이름만 — 값은 `.env`에 보관, 커밋 금지)

`.env.example` 참조. 핵심:

- `CHATGPT_OAUTH_*` — OAuth 모델 경로 (기본 채팅 백엔드)
- `BRAVE_API_KEY`, `BRAVE_API_KEY_FREE` — 웹 검색
- `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` — 네이버 검색
- `PINECONE_API_KEY` — 벡터 스토어 (선택)
- `UPSTASH_REDIS_URL`, `UPSTASH_REDIS_TOKEN` — 세션/캐시 (선택)
- `DEEPGRAM_API_KEY` — 음성 전사 (선택)
- `PROBE_ADMIN_TOKEN` — 운영 프로브 (선택)

로컬 모델은 Ollama(11434)가 담당하며 `ollama ls`의 설치 모델만 사용한다.

## 테스트

```bat
:: 포커스 단위 테스트 (예: fail-soft/라우팅 회귀)
cmd /c gradlew.bat test --tests "ai.abandonware.nova.orch.aop.WebFailSoftSearchAspectTest" ^
  --tests "ai.abandonware.nova.orch.aop.FailSoftQueryAugmentAspectTest" ^
  --tests "ai.abandonware.nova.orch.aop.FallbackAwareChatModelApiFirstRoutingTest"

:: 시크릿 가드 / 스캔 회귀
python -B -m unittest scripts.test_git_secret_guard_prepush

:: 모델 정책 프로브 (라이브 1회 호출, 예산 ledger 기록)
python -B scripts/test_model_policy.py --local --model-purpose smoke --run <task-id>
```

PowerShell에서 Gradle native 명령을 직접 리다이렉트하면 종료코드가 왜곡될 수 있으니 `cmd /c`로 실행한다.

## 검증 요약 (T1–T5, 3회 연속 — 상세는 report.md)

- T1 부팅: Start-RAG ForceRestart → `actuator/health` UP — 3/3
- T2 채팅: `/api/chat/sync` `[devin-test]` — `modelUsed=gpt-5.6-luna`, 본문 비어있지 않음 — 3/3
- T3 RAG 근거: `searchMode=FORCE_DEEP` + `useWebSearch` — `ragUsed=true`, WEB 근거 3건 — 3/3
- T4 외부 API 실패 처리: fail-soft 포커스 JUnit 66/66 (0 fail) — 3/3
- T5 재시작 복구: 재부팅 후 T1+T2 재통과 — 3/3

## Meta Ray-Ban Display

Display 동기/렌즈 표시 코드는 이 트리에 그대로 있으나, 10/13 공개 범위에서는
**보류(hold)** — 삭제하지 않고, 백엔드 검증 대상에서도 제외했다.
상세: `display-freeze.md` (본 리포트 폴더).

## 저장소 메모

- 공개용 추적 파일 점검: 실제 비밀값 0건, 이력 451커밋 픽액스 0건.
- `git rm --cached` 권고 후보(실행 전): `app/quarantine/`, `uploads/`, `scratch/`,
  `_patch_artifacts/`, `__patch_drop__/` — 상세는 `w4-secret-audit.md`.
