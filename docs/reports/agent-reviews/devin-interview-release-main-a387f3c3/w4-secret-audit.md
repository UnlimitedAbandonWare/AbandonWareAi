# W4 — 공개 파일·비밀값 점검 (값 출력 없음)

작업: devin-interview-release-main-a387f3c3 / journal devin-interview-release-main-04829e0c
기준 ref: HEAD 스캔 완료 + release/rc-20261013 (a3754a3) 스캔 완료

- RC ref 결과: 7,160 파일, 94 findings — synthetic-fixture 22 + test-fixture 72,
  needs-review **0**. HEAD와 동일 프로파일 (RC는 a3754a3 기준 97개 파일 적음).
- 결론: 공개 후보(RC)에 실제 자격증명 **0건**.

## 1. 추적 블롭 스캔 (scripts/release_rc_secret_scan.py)

- 대상: `git ls-files` 기준 추적 블롭 전체 (git cat-file --batch, 바이너리 스킵)
- HEAD: 7,257 파일 스캔, 94 findings
  - synthetic-fixture: 22 (테스트/픽스처/문서 예시 값 — 반복문자·`example`·`Bearer <sample>` 계열)
  - test-fixture: 72 (src/test, scripts/test_*, tests/ 경로의 의도된 샘플 키)
  - needs-review: **0**
  - 실제 자격증명으로 판정된 값: **0건**
- 출력 형식: `path:line | rule | 앞4글자마스킹` — 값 전체 출력 없음
- 스캐너 오탐 교정 이력: 초기 301건(`sk-`가 task-/risk-/mask- 부분문자열에 매칭) →
  토큰 경계 lookbehind 적용 후 94건; `apiKey = resolveApiKeyForBaseUrl(` 같은
  메서드 호출 RHS 오탐 → 리터럴 따옴표 요구로 제거.
- 관련 수정: `scripts/git_secret_guard.ps1` 비ASCII 파일명(git core.quotePath
  인용 `"...\352\270\260.lnk"`)이 Test-Path을 ArgumentException으로 크래시시키던
  결함 → 인용 해제 디코더 + try/catch 경고-건너뛰기. 회귀 테스트 2개 추가,
  prepush 스위트 17/17 통과 (verify-cycle-03, runId d9917a99, cycle-03 sealed+verified).

## 2. .env.example 계열

- `.env.example`, `frontend/.env.example` — 전부 빈 플레이스홀더
  (`KEY=` 형태, 값 없음). 이름 목록: UPSTASH_REDIS_URL/TOKEN, NAVER_CLIENT_ID/SECRET,
  BRAVE_API_KEY(_FREE), PINECONE_API_KEY, PROBE_ADMIN_TOKEN, DEEPGRAM_API_KEY.
- 실제 `.env` / `.secrets/**` — 추적 대상 아님, 열람하지 않음(규칙).

## 3. 이력 픽액스 (451 커밋 전체)

`git log -S<pattern> --oneline` 대상 패턴:
`sk-`, `ghp_`, `github_pat_`, `AKIA`, `AIza`, `xox`, `vck_`,
`client_secret`, `BEGIN PRIVATE KEY`, `BEGIN RSA`, `apikey`, `api_key=`

- 결과: 모든 패턴 0 커밋. 과거 이력에 자격증명이 들어간 적 없음.
- 결론: 공개 전 키 폐기·재발급 필요 목록 = **없음**.

## 4. 추적 중 불필요 파일 → `git rm --cached` 후보 (실행은 승인 후)

| 후보 경로 | 파일 수 | 성격 |
|---|---:|---|
| `app/quarantine/` | 76 | 격리된 구 코드 산출물 |
| `uploads/` | 34 | `uploads/chat/*.md` 대화 내보내기(개인 대화 로그) |
| `scratch/` | 16 | 초안/임시 파일 |
| `_patch_artifacts/` | 5 | 패치 산출물 |
| `__patch_drop__/` | 1 | 패치 드롭 잔여물 |
| `agent-prompts/madasin-codex-design-recovery-20260926/evidence/source_inventory.json` | 1 | ~1.86 MB 대용량 증거 스냅샷 |

- `frontend/node_modules/`, `var/`, `logs/`, `data/`, `build/`: 추적 후보 없음 (이미 무시됨).
- 주의: `uploads/chat/*.md`는 대화 내용이므로 rm --cached 전 내용 검토 권장(개인정보).
  이번 세션은 파일 내용을 열어보지 않았고, 목록만 파일명/수로 기록.

## 5. 결론

- 실제 비밀값 노출: **0건** (추적 블롭 + 이력 전체).
- 공개 차단 요소 없음. rm --cached 후보는 정리 권고 사항이며 실행은 사용자 승인 후.
