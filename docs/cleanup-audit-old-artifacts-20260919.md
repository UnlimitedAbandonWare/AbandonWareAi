# 오래된 산출물 정리 감사 (읽기 전용)

- taskId: `cleanup-audit-old-artifacts-844675f5`
- 기준 루트: `C:\AbandonWare\demo-1\demo-1\src`
- 조사일: 2026-09-19
- 판정 기준: **현재 로컬 파일 내용 + 실제 실행/빌드/지침 참조**. Git 이력은 사용하지 않음.
- 이번 단계: **삭제 0건**. 후속 지시가 오면 `DELETE` 중 참조 재확인된 항목부터 묶음 정리.

선행 작업과의 관계:

- 2026-09-18 `docs/repo-hygiene-audit-20260918.md` — 죽은 Java 트리 1,099파일 삭제(외부 rescue).
- 2026-09-19 `repo-residue-cleanup-8ff47167` — 루트 잔재 168항목 삭제.
- 2026-09-19 Codex 홈 세션 정리 — `~\.codex` 쪽이며 본 보고서 범위 밖.

본 감사는 **남은 보고서·인계·로그·임시 사본·재생성 캐시**에 한정한다. 활성 `main/java` 알고리즘 본체, `.secrets`, `.env*`, `apikey.txt`, openssl/opnessl, 인증서는 삭제 후보에서 제외한다.

용량은 MiB(1 MiB = 1,048,576 B). 날짜는 파일 `LastWriteTime`(로컬).

---

## 0. 조사 방법

1. 루트·`docs/`·`data/agent-handoff/`·`var/`·`logs/`·`verification/`·`output/`·`__reports__`를 날짜·용량으로 집계.
2. 후보 본문을 열어 용도를 확인(파일명만으로 판정하지 않음).
3. 경로 문자열을 현재 `AGENTS.md`, `application.yml`, `build.gradle.kts`, `scripts/`, `.agents/skills`에서 검색.
4. 라이브 런타임 경로(`Start-RAG`/`Debug-*`, DevWatch, `var/abnadon`)는 KEEP.
5. 같은 제목이라도 내용·역할이 다르면 중복으로 묶지 않음.

현재 큰 덩어리(참고):

| 위치 | 파일 수 | MiB | 최신 |
|---|---:|---:|---|
| `var/` | 40,736 | 10,799 | 2026-09-19 |
| `data/agent-handoff/` | 27,496 | 4,746 | 2026-09-19 |
| `app/build/` | 142,883 | 211 | 생성물 |
| `frontend/node_modules/` | 9,701 | 309 | 생성물 |
| `docs/` | 335 | 6 | 2026-09-19 |

---

## 1. 후보 목록 (오래된 것 우선)

각 행: `경로 / 날짜 또는 최근 수정일 / 용도 / 현재 참조 여부 / 최신 대체 파일 / 판단 근거 / 권장 조치`

### 1-A. 루트 구형 지시·패치 보고서 (2026-01-31 ~ 2026-06-26)

| 경로 | 날짜 | 용도 | 현재 참조 | 최신 대체 | 판단 근거 | 권장 조치 |
|---|---|---|---|---|---|---|
| `HOW_TO_APPLY.md` | 2026-01-31 | 에이전트 모듈을 기존 Spring 서비스에 병합하는 가이드 | 실행 경로 0. `docs/PATCH_NOTES.md`가 존재만 언급 | `README.md`, `Start-RAG.bat`, `docs/PROJECT_STATUS.md` | 본문: `AgentConfiguration` import, 샘플에 REST 컨트롤러 없음. 현재는 `Start-RAG.bat` + interview UI가 진입점 | ARCHIVE |
| `GPT_PRO_AGENT_INSTRUCTIONS.md` | 2026-01-31 | GPT Pro용 게스트 방/로그인 승계/Admin Redis 지시 | 실행 0. `docs/multi-agent-coordination-analysis-20260919.md`가 오독 위험으로 지적 | `AGENTS.md` 익명-first, `demo.interview.enabled=true` | 본문이 Spring Boot 3.1.x + Redis 방 승계를 전제. 현재 익명 인터뷰 경로와 충돌하는 **구형 실행 지시** | ARCHIVE (루트에서 치워 오독 차단) |
| `CHANGELOG.md` | 2026-01-31 | `src54core` v54t ONNX/ChatService 분할 기록 | 실행 0 | `docs/PROJECT_STATUS.md` | 2025-08-22 src54 변경 로그. 현재 운영 현황이 아님 | ARCHIVE |
| `AUTO_ERROR_PATTERN_SNAPSHOT.md` | 2026-06-26 | 빌드 에러 카운트 스냅샷(11줄) | 실행 0 | `tools/build_error_miner.py` 산출 | `java.cannot_find_symbol: 1201` 등 당시 집계. 재생성 가능 | DELETE |
| `amp-playbook.md` | 2026-06-26 | `src54core` 운영 플레이북 | 실행 0. `docs/legacy/amp-playbook.md`에 동계열 사본 | `AGENTS.md` | Path-alignment, MoE 라우팅 주의. 현재 런처/스킬이 대체 | ARCHIVE |
| `AUTO_BUILD_FIX_REPORT__src111_merge15__CHATGPT.md` | 2026-06-26 | src111_merge15 자동 빌드 픽스 보고서 | `scripts/build_error_mitigator.py`가 **다른 경로** `src/AUTO_BUILD_FIX_REPORT__latest.json`에 쓰도록 되어 있음(현 파일 직접 참조 아님) | 없음(역사) | 본문: Java 21 + `:app` only + `java_clean` stub. 현재는 Java 17, 활성 루트 `main/java` | ARCHIVE |
| `AUTO_BUILD_FIX_REPORT__src111_merge15__2025-11-01__chatgpt.md` | 2026-06-26 | 위와 같은 계열의 날짜 접미사 사본 | 실행 0 | 위 파일과 동일 계열 | 같은 merge15 자동픽스 반복본 | ARCHIVE (둘 중 대표 1개만 남기고 나머지 DELETE 가능) |
| `verify_boot.sh` | 2026-06-26 | bash 부트 검증 | 프롬프트/도구 존재 확인용으로 다수 언급. Windows 실사용은 `verify_boot.ps1` | `verify_boot.ps1` | 내용이 다른 3형제(`ps1`/`sh`/`plus.sh`). 이름만 같고 역할이 다름 → 중복 단정 금지 | CHECK |
| `verify_boot_plus.sh` | 2026-06-26 | bash 부트 검증 확장 | 구형 에이전트 프롬프트가 호출 예로 인용 | `verify_boot.ps1` | 위와 동일. Desktop 실경로는 PowerShell | CHECK |
| `gradlew-real` | 2026-01-31 | 구 gradle wrapper 실체 | `tools/build_matrix.yaml`이 `gradlew-real: not found` 패턴으로 언급 | `gradlew.bat` | 현재 빌드는 `gradlew.bat`. 파일 자체는 작음 | CHECK |
| `-` (0바이트 파일) | 2026-09-19 | 빈 파일, 이름 `-` | 참조 0 | 없음 | 산출물 아님. 실수 생성으로 보임 | DELETE |
| `hs_err_pid52096.log`, `hs_err_pid70324.log`, `replay_pid52096.log`, `replay_pid70324.log` | 2026-09-17 | JVM crash/replay 덤프 ~1.1 MiB | 실행/지침 0 | 없음(재현 시 재생성) | 2026-09-17 크래시 잔여. `.gitignore`에 `hs_err_pid*.log` 이미 있음 | DELETE |
| `ONNX` (0바이트) | 2026-06-03 | 빈 플레이스홀더 | 이전 정리에서 ACL 거부로 보류 | 없음 | `PROJECT_STATUS` §5 보류 항목. 삭제 시도하지 말 것 | KEEP |
| `apikey.txt` | 2026-09-13 | 키 파일 | 다수 스킬이 **건드리지 말 것**으로 명시 | `.env` / 디바이스 시크릿 | 인증정보. 본 감사에서 내용 미개방 | KEEP |
| `.env`, `shared.env` | 2026-09-17 | 로컬 환경 | 런타임 사용 | `.env.example`(템플릿) | 운영 설정. 삭제 금지 | KEEP |
| `EXTERNAL_SKILLS.md` | 2026-09-08 | 외부 스킬 목록 | `AGENTS.md` 참조 | 자체 | 현재 지침 | KEEP |
| `PATCH_NOTES_api_routing.md` | 2026-09-17 | API 라우팅 패치 메모 | 구현은 `docs/API_ROUTING_SPEC.md` + `configs/api-routing.yaml`이 SSOT | `docs/API_ROUTING_SPEC.md` | 최근 메모라 내용은 유효. SSOT에 이미 흡수된 변경 목록 | MERGE |
| `PATCH_NOTES_agent_api_spend_guard.md` | 2026-09-17 | spend guard 패치 메모 | `docs/AGENT_API_SPEND_GUARD.md` + 스킬이 SSOT | `docs/AGENT_API_SPEND_GUARD.md` | 위와 같음 | MERGE |
| `GPT_PRO_AGENT_INSTRUCTIONS.md` 외 루트 지시 | — | — | — | — | `docs/multi-agent-coordination-analysis-20260919.md` P1과 일치 | (위 표) |

### 1-B. `docs/` 구형 패치·빌드·src91/src111 문서 (mtime 2026-06-26, 내용 연대는 더 이전)

이 묶음은 날짜 스탬프가 같아 한꺼번에 복사된 역사 문서다. 실행 경로 참조 없음. 대표 1개만 ARCHIVE하고 반복본은 DELETE 가능.

| 경로 | 날짜 | 용도 | 현재 참조 | 최신 대체 | 판단 근거 | 권장 조치 |
|---|---|---|---|---|---|---|
| `docs/PATCH_NOTES.md` | 2026-06-26 | `src_47.zip`←`src_46` 에이전트 모듈 추가 노트 | 실행 0 | `docs/PROJECT_STATUS.md` | Spring Boot 3.1, Kakao tool stub, Grafana placeholder. 현재 스택 설명 아님 | ARCHIVE |
| `docs/PATCH_NOTES__src111_merge1sw5.md` | 2026-06-26 | merge15 계열 패치 노트 | 실행 0 | 없음 | 파일명만 다른 merge15 반복 | DELETE |
| `docs/HOW_TO_APPLY.md` | 2026-06-26 | 루트 `HOW_TO_APPLY.md`와 동계열 | 실행 0 | `README.md` | 루트본과 역할 중복 | ARCHIVE 또는 루트본과 묶어 1개만 남김 |
| `docs/INJECTION_REPORT.md` (58 KiB) | 2026-06-26 | 과거 주입/패치 장문 | 실행 0 | 없음 | 대용량 역사 서술. 검색 노이즈 | ARCHIVE |
| `docs/PAST_TRAJECTORY_INDEX.md` (63 KiB) | 2026-06-26 | 옛 CHANGELOG/BUILD_LOG 선두 100줄 자동 인덱스 | 실행 0. `docs/STUFF3_NOTE.md`가 가리킴 | `docs/PROJECT_STATUS.md` | 인덱스가 가리키는 `BUILD_LOG.txt`, `CHANGELOG_src_54.md` 등은 이미 루트에서 사라짐(선행 삭제). **깨진 포인터** | DELETE |
| `docs/STUFF3_NOTE.md` | 2026-06-26 | stuff3 메모, trajectory 인덱스 안내 | 실행 0 | 없음 | 위 깨진 인덱스에 의존 | DELETE |
| `docs/JamminiMemory_src111_merge15_POINTER.md` | 2026-06-26 | Jammini 메모리 포인터 | 실행 0 | `docs/JAMMINI_MEMORY__src111_merge15.md` | 포인터 문서. 본편은 아래 | MERGE |
| `docs/JAMMINI_MEMORY__src111_merge15.md` | 2026-06-26 | merge15 메모리 노트 | 활성 소스는 `demo-1/.../JamminiMemory.atomic.yaml`(비활성 모듈) | 활성 RAG는 `main/java` | 비활성 `demo-1/` 모듈 문서 | ARCHIVE |
| `docs/SRC91_AC_CHECKLIST.md`, `SRC91_COMMITS.md`, `SRC91_RUNBOOK.md` | 2026-06-26 | src91 체크리스트/커밋/런북 | 실행 0 | `docs/PROJECT_STATUS.md` | 옛 브랜치 산출. Git을 기준으로 쓰지 않더라도 현재 Start-RAG와 무관 | ARCHIVE |
| `docs/INTEGRATION_9X.md`, `docs/INTEGRATION_GUIDE_SAmerge16_patch.md` | 2026-06-26 | 9x/merge16 통합 가이드 | 실행 0 | `AGENTS.md` | 역사 통합 노트 | ARCHIVE |
| `docs/QUICKFIX_SUMMARY.md`, `docs/README_patch_summary.md` | 2026-06-26 | 퀵픽스/패치 요약 | 실행 0 | `docs/PROJECT_STATUS.md` | 반복 요약 | DELETE |
| `docs/PR1_telemetry_virtual_point.md` ~ `docs/PR4_autowiring_aop.md` | 2026-06-26 | PR1–PR4 설계 메모 | 코드 존재 여부와 별개로 **문서 자체는 실행 경로가 아님** | 해당 Java가 `main/java`에 있으면 소스가 SSOT | 짧은 설계 쪽지 | ARCHIVE |
| `docs/BUILD_ERRORS.md`, `docs/BUILD_ERROR_PATTERNS.md`, `docs/BUILD_ERROR_MINER.md`, `docs/build_error_history.md`, `docs/build_error_patterns.report.md`, `docs/build_error_memory__*.md`, `docs/BUILD_FIX_NOTES.md`, `docs/BUILD_FIX_NOTES__src111_msaerge15.md`, `docs/BUILD_FIX_SUMMARY.json`, `docs/build_error_history.json` | 2026-06-26 | 빌드에러 마이너/픽스 문서 군 | 도구는 `tools/build_error_miner.py` + `.github/workflows/build-error-miner.yml`이 살아 있음. **docs 사본은 구형** | `docs/BUILD_ERROR_MINER.md` 중 miner 사용법만 최신 도구와 맞는지 확인 후 1개 유지 | 본문 예: Maven, `analysis/` 출력, Java 21 혼재. 실제 miner는 유지 | MERGE (`BUILD_ERROR_MINER.md` 대표) / 나머지 ARCHIVE 또는 DELETE |
| `docs/CHANGELOG.md` | 2026-06-26 | 373B 짧은 changelog | 실행 0 | `docs/PROJECT_STATUS.md` | 루트 `CHANGELOG.md`와도 다름(다른 내용) | ARCHIVE |
| `docs/OPERATIONAL_DEFAULTS.md` | 2026-06-26 | 운영 기본값 쪽지 | 현재 기본은 YAML/`application-meta-display.yml` | 해당 YAML | 짧은 구형 기본값 | CHECK |
| `docs/PLAN_CATALOG.md` | 2026-06-26 | 100B 거의 빈 카탈로그 | 실행 0 | `docs/PROJECT_STATUS.md` | 내용 없음에 가까움 | DELETE |
| `docs/acceptance-checklist.md` | 2026-06-26 | 구 수용 체크 | 실행 0 | 현재 스킬/테스트 | 구형 | ARCHIVE |
| `docs/EN_V1_KPI_CHECKLIST.md` | 2026-06-26 | KPI 체크리스트 | 실행 0 | 없음 | 역사 지표 | ARCHIVE |
| `docs/gptpro_agent_instructions_ko.md` | 2026-06-26 | GPT Pro 한글 지시 | 실행 0 | `AGENTS.md` | 루트 `GPT_PRO_AGENT_INSTRUCTIONS.md`와 계열은 같으나 본문이 다를 수 있음 → 중복 단정하지 않음 | ARCHIVE |
| `docs/system_prompt__kchat_gpt_pro.md`, `.multimodel.md`, `docs/tool_manifest__kchat_gpt_pro.json` | 2026-06-26 | kchat 시스템 프롬프트/툴 매니페스트 | 현재 프롬프트 경계는 `PromptBuilder` + `agent-prompts/` | 활성 프롬프트 팩 | 샘플 리소스. 런타임이 이 경로를 읽는지 미확인 | CHECK |
| `docs/kakao_ask.yaml`, `docs/kakao_consent_card.basic.json`, `docs/README_kakao_consent_templates.md` | 2026-06-26 | Kakao 동의 카드 샘플 | `docs/PATCH_NOTES.md`가 샘플로 언급. 라이브 인터뷰 UI는 `/assets/interview/` | 인터뷰 자산 | 이름 비슷한 활성 플로우가 있을 수 있어 삭제 보류 | CHECK |
| `docs/sigmoid_orchestration_results.json` | 2026-06-26 | 실험 결과 23 KiB | 실행 0 | 없음 | 재생성 가능한 실험 JSON | DELETE |
| `docs/DIAG_API_SAMPLE.json`, `docs/errors_input.txt`, `docs/workflow_followup.json`, `docs/patch_changelog.json`, `docs/dependency-tree.txt` | 2026-06-26 | 샘플/잔여 | 실행 0 | 없음 | 짧은 잔여 산출 | DELETE |
| `docs/EMBEDDING_FAILOVER_ADVANCED.md` | 2026-06-26 | 임베딩 페일오버 고급 설명 | 현재 페일오버는 코드+`docs/API_ROUTING_SPEC.md` | `docs/API_ROUTING_SPEC.md` | 내용이 라우팅 스펙과 겹칠 수 있음 | MERGE |
| `docs/legacy/` (4파일) | 2026-06-26 | 이미 옮겨 둔 레거시 README/playbook | 실행 0 | — | 이미 archive 성격. `legacy/README.md`는 1000줄 LMS 개요 사본 | ARCHIVE (현 위치 유지) |
| `docs/patch/` (4파일) | 2026-06-26 | 패치 로그/에러 분석 | 실행 0 | `docs/PROJECT_STATUS.md` | merge15 패치 로그 | ARCHIVE |
| `docs/build-errors/KNOWN_PATTERNS.md` | 2026-06-26 | 패턴 1파일 | miner 도구와 별개 사본 | `tools/build_error_miner.py` | 위 BUILD_ERROR 군과 묶음 | MERGE |
| `docs/AGENT_GUIDE.md`, `AGENT_HEADER_*`, `AGENT_SERVICE_GUIDE.md` | 2026-06-26 | 구 에이전트 가이드 | 현재 에이전트 지침은 `AGENTS.md` + `.agents/skills` | `AGENTS.md` | 헤더 스펙 v2 등 구형 | ARCHIVE |

### 1-C. `docs/` 비교적 최근 문서 — 중복이어도 역할이 다름

| 경로 | 날짜 | 용도 | 현재 참조 | 최신 대체 | 판단 근거 | 권장 조치 |
|---|---|---|---|---|---|---|
| `docs/PROJECT_STATUS.md` | 2026-09-19 | 유일한 종합 현황 SSOT | `AGENTS.md` work-ledger가 지정 | 자체 | 현재 운영 지침 | KEEP |
| `docs/API_ROUTING_SPEC.md` | 2026-09-18 | 모델/검색 라우팅 SSOT | `AGENTS.md` Ollama lock | 자체 | 라이브 정책 | KEEP |
| `docs/codex-autonomous-work.md` | 2026-09-15 | checkpoint/lease 계약 | work-ledger 스킬 | 자체 | 절차 문서 | KEEP |
| `docs/AGENT_API_SPEND_GUARD.md` | 2026-09-17 | 과금 가드 | 스킬+코드 | 자체 | 현재 사용 | KEEP |
| `docs/TOOLCHAIN_AUTO_SELECT.md` | 2026-09-18 | 툴체인 | `AGENTS.md` | 자체 | 현재 사용 | KEEP |
| `docs/meta-display-*.md`, `docs/META_DISPLAY_CUE_LLM_SPEC.md` | 2026-09-13~19 | Display 검증/통합/캡션 | Display 스킬 | 스킬이 길이/수명 정책 SSOT | 문서와 스킬이 역할 분담. 이름 비슷해도 통합/검증/E3 정리는 내용이 다름 | KEEP (E3 cleanup는 완료 보고 → ARCHIVE 가능) |
| `docs/repo-hygiene-audit-20260918.md` | 2026-09-19 | 이전 위생 감사 | 본 감사의 선행 증거 | 본 파일 | 삭제 이력 근거. 지우면 선행 삭제를 재조사해야 함 | KEEP |
| `docs/codex-session-cleanup-audit-20260919.md` + `directive` + `execution` | 2026-09-19 | Codex 홈 세션 정리 3종 | 실행 결과 기록 | 3파일이 감사/지시/결과로 **역할이 다름** | 중복 아님 | KEEP |
| `docs/multi-agent-coordination-analysis-20260919.md` | 2026-09-19 | 4에이전트 공조 분석 | 현황 §5가 인용 | 자체 | 현재 참고 | KEEP |
| `docs/superpowers/` (208파일, 4.3 MiB) | 2026-07-12 ~ 2026-09-19 | 설계/플랜/리포트/스펙 | `.agents/skills` 직접 경로 참조 0. `agent-prompts` 역사 지시가 인용 | 구현된 스킬 `SKILL.md` | 설계 원본으로서 이력 가치. 실행 SSOT는 스킬 | ARCHIVE |
| `docs/audits/` (21파일, 0.9 MiB) | 2026-08-14 ~ 2026-09-07 | 소스 감사 원장 + 컴파일 프로브 class | 실행 0 (`CoreProbe.class` 등 생성물 포함) | `docs/PROJECT_STATUS.md` | 프로브 class/로그는 DELETE, 원장 md는 ARCHIVE | ARCHIVE + 생성물 DELETE |
| `docs/reports/` (5파일) | 2026-09-13~15 | 멀티디바이스/리소스/STT 필요성 | 일부 주제는 후속 코드에 반영 | 해당 구현 | 최근 주제별 보고. 아직 대체 문서가 완전하지 않을 수 있음 | CHECK |
| `docs/codex/*.md` (Brave/Display/RAG 지시) | 현재 | 라우팅/렌즈 지시 | `AGENTS.md`가 Brave 지시 인용 | 자체 | 현재 정책 | KEEP |

### 1-D. 재생성 가능한 로그·검증·실험 산출 (별도 분류)

라이브 디버그 경로와 섞이지 않게, **현재 `application.yml`이 쓰는 디렉터리는 제외**했다.

| 경로 | 날짜 | 용도 | 현재 참조 | 최신 대체 | 판단 근거 | 권장 조치 |
|---|---|---|---|---|---|---|
| `logs/` (217파일, 15.8 MiB) | 2026-05-14 ~ 2026-09-19 | 옛 bootRun/soak/verify_boot 로그, 포트 8081~8090 잔여 | `scripts/awx_mcp_toolbox.py`가 `logs/failure-pattern-memory.jsonl`을 읽음. MCP 타워가 `logs/awx-mcp-audit.ndjson`을 audit_log로 언급 | `var/rag-launcher/`, `var/debug/` | **대부분** 재생성 가능. 단 `failure-pattern-memory.jsonl`, `awx-mcp-audit.ndjson`은 도구가 읽음 | 대량 DELETE 가능 / 위 2파일 KEEP 또는 CHECK |
| `verification/` (1,746파일, 39 MiB) | 2026-06-20 ~ 2026-09-16 | compile-after-*, bootjar-after-*, runtime-domain-18080 로그 | 구 감사 프롬프트가 경로를 인용. 현재 Start-RAG는 미사용 | 새 Gradle 로그 | 패치 전후 컴파일 로그 더미. `.gitignore`에 `/verification/` | DELETE |
| `output/` (193파일, 3.0 MiB) | 2026-07-05 ~ 2026-09-17 | Playwright 스크린샷/브라우저 프루프 | 실행 경로 아님 | 필요 시 재촬영 | 실험 스크린샷 | DELETE |
| `__reports__/` (53파일, 1.7 MiB) | 2026-05-28 ~ 2026-08-30 | 2026-06-05 노트북 지시 더미 + 위생 TSV | `build.gradle.kts`가 `__reports__/context-purity-decisions.tsv`를 **출력 경로로 사용** | Gradle이 재생성 | 노트북 지시 md/txt는 만료. TSV는 빌드가 다시 씀 | 지시문 DELETE / `context-purity-decisions.tsv` KEEP(재생성) |
| `analysis/README.md` | 2026-06-26 | stuff4 분석 산출 안내 | 선행 정리로 본문은 거의 비움 | miner | 빈 안내 + 빈 `_auto_patch4/` | DELETE |
| `autoevolve_debug/`, `_patch_artifacts/`, `pki-validation/` | — | 빈 디렉터리 | 0 | 없음 | 파일 0 | DELETE (빈 폴더) |

### 1-E. `var/` — 라이브 vs 버려진 캐시

| 경로 | 날짜 | 용도 | 현재 참조 | 최신 대체 | 판단 근거 | 권장 조치 |
|---|---|---|---|---|---|---|
| `var/abnadon/` (77파일, 72.8 MiB) | ~2026-09-19 | 디버그 NDJSON + 이미지 잡 매니페스트 | `application.yml` `abandonware.debug.ndjson-dir=${ABNADON_DEBUG_DIR:var/abnadon/debug}`, `image.jobs.manifest-dir=var/abnadon/images` | 자체 | **라이브 런타임 경로**. 오타 `abnadon`이 설정 키 | KEEP (오래된 일자 파일 로테이션만 CHECK) |
| `var/rag-launcher/` (808파일, 109.9 MiB) | 2026-09-14 ~ 2026-09-19 | Start-RAG/Start-Meta-Display 런 결과 | `AGENTS.md` freshness 증거 (`result.json`) | 자체 | 현재 런처 산출. 상한 로테이션은 별 이슈 | KEEP |
| `var/debug/` (19파일, 0.7 MiB) | 2026-09-19 | Debug BAT threads/jfr | `AGENTS.md` DEMO1-DEBUG-ENTRYPOINTS, 스크립트 상한 40개/20MB | 자체 | 현재 진단 산출 | KEEP |
| `var/dev-reload/` | 2026-09-18 ~ 19 | DevWatch 소켓 로그 | `AGENTS.md` `[DEV-RELOAD] socket ready` | 자체 | 현재 재빌드 증거 | KEEP |
| `var/gradle-home/` (19,021파일, 5,616.6 MiB) | 2026-08-18 ~ 2026-09-08 | 구 스모크용 Gradle 유저 홈 | 현재 권장 경로는 `%USERPROFILE%\.gradle-awx-desktop`. `start_rag_stack.ps1`이 이 경로를 쓰지 않음 | 호스트 Gradle 홈 | 최종 쓰기 2026-09-08, 재생성 가능 캐시 | DELETE |
| `var/gradle-user-home/` (8,100파일, 2,460.6 MiB) | ~2026-09-01 | 위와 다른 이름의 구 Gradle 홈 | 동일 | 호스트 Gradle 홈 | 2026-09-01 이후 미갱신 | DELETE |
| `var/gradle-task4-canonical-user-home/` (1,923파일, 600.6 MiB) | 2026-09-01 only | 일회 태스크 Gradle 홈 | 0 | 호스트 Gradle 홈 | 하루치 캐시 | DELETE |
| `var/gradle-project-cache/` (352파일, 142.2 MiB) | ~2026-09-03 | 구 프로젝트 캐시 | 권장 `--project-cache-dir`는 `%USERPROFILE%\.awx-gradle-project-cache\desktop` | 그 경로 | 레포 내부 캐시 잔여 | DELETE |
| `var/codex-smoke/` (9,834파일, 1,692.9 MiB) | 1980-01-01 ~ 2026-09-16 | 수개월치 bootRun/브라우저 스모크, chrome-cdp 프로필 | `.gitignore`가 `chrome-cdp-*`를 비밀 캐시로 취급 | 새 스모크 | 재생성 가능. 브라우저 프로필은 토큰 잔여 가능 → **삭제 시 내용 재확인, 외부로 복사 금지** | DELETE (로컬 폐기, archive 금지) |
| `var/codex-tools/` 64 MiB, `var/codex/` 7.6, `var/codex-runtime/` 7.4, `var/codex-run/` 4.2 | 6~7월 | 구 Codex 런 산출 | 현재 런처 미사용 | `var/rag-launcher` | 오래된 런 로그 | DELETE |
| `var/hygiene-audit-20260918/` | 2026-09-19 | 위생 감사 사본 | 외부 rescue가 본 증거 | `docs/repo-hygiene-audit-20260918.md` + `C:\AbandonWare\_rescue\...` | 작음. 이력 | ARCHIVE 또는 KEEP |

### 1-F. `data/agent-handoff/` — 인계·복구 vs 실수로 들어간 런타임 복사

작업 저널/checkpoint `before/*.bin`은 work-ledger 복구본이다. **통째 삭제 금지.** 아래는 그 안의 **재생성 가능한 초대형 이물질**만 분리한다.

| 경로 | 날짜 | 용도 | 현재 참조 | 최신 대체 | 판단 근거 | 권장 조치 |
|---|---|---|---|---|---|---|
| `.../01a09f60-whisper/runtime/venv/` (5,880파일, 2,006.4 MiB) | 2026-09-14 | Whisper 실험용 Python venv 통째 복사 | 라이브 STT/Spring이 이 경로를 쓰지 않음 | 필요 시 새 venv | site-packages + `python.exe`. 인계 폴더에 런타임 환경을 넣은 것 | DELETE |
| `.../01a09f60-whisper/runtime/models/` (13파일, 1,546.5 MiB) | 2026-09-14 | `turbo/model.bin` 등 로컬 STT 모델 사본 | 라이브 모델 경로와 일치 여부 미확인 | 설치된 모델 디렉터리 | 바이너리 가중치. 재다운로드 비용 있음. 보고서가 아님 | CHECK |
| `.../01a09f60-whisper/` 나머지 json/cycle | 2026-09-14 | whisper 작업 저널·checkpoint | work-ledger 복구 | 자체 | 저널은 이력 | ARCHIVE/KEEP |
| `data/agent-handoff/conversate/20260912-01a09365/runtime/` (5,802파일, 361.7 MiB) | 2026-09-12 | Conversate 작업 중 복사된 런타임(venv 성격, py+pyc 대량) | 라이브 서버 미사용 | 새 환경 | whisper와 같은 패턴 | DELETE |
| `data/agent-handoff/codex/report/` (11,928파일, 272.4 MiB) | 2026-06-26 ~ 2026-09-19 | 과거 Codex 리포트/로그/JUnit XML 더미 | AutoLearn SSOT `manifest.json`/`cycles.jsonl`은 **부재**(AGENTS.md가 이미 지적) | `docs/PROJECT_STATUS.md` + 최근 task 저널 | 반복 리포트. 최근 완료 리포트 일부는 이력 가치 | ARCHIVE (전부 DELETE는 성급) |
| `data/agent-handoff/codex/meta-minimal-20260917/tls-compat/*.pem` | 2026-09-17 | TLS 체인 작업 사본 | 인증서 | 운영 인증서 위치 | **인증정보. 삭제 후보 아님.** 본문 미개방 | KEEP |
| `data/agent-handoff/codex-autonomy/<최근 taskId>/` | 2026-09-19 | 오늘 작업 저널·preimage | work-ledger | 자체 | `repo-residue-cleanup`, `debug-bat-diagnostics`, 본 감사 등 | KEEP |
| `data/agent-handoff/desktop-notebook-pack-test/` (2,030 txt, 0.6 MiB) | 2026-08-19 ~ 09-13 | 팩 테스트 픽스처 | 테스트 하네스가 쓰면 KEEP | 해당 테스트 | 대량 txt. 테스트 참조 확인 전 삭제 금지 | CHECK |
| `data/agent-handoff/runtime-toolkit/` 69.3 MiB | 2026-09-05 | 런타임 툴킷 증거 | 실행 0으로 보임 | 없음 | 하루치 덤프 | ARCHIVE |

### 1-G. 생성 산출·모듈 캐시

| 경로 | 날짜 | 용도 | 현재 참조 | 최신 대체 | 판단 근거 | 권장 조치 |
|---|---|---|---|---|---|---|
| `app/build/` (142,883파일, 211.2 MiB) | 생성물 | `:app` Gradle 출력 | 현재 권장 부트 증명은 `build/desktop/...` (split outputs) | Gradle 재빌드 | `.gitignore` `build/` | DELETE (재생성) |
| `frontend/node_modules/` (9,701파일, 308.7 MiB) | 2026-07 | Next BFF 의존성 | `frontend/package.json`이 있으면 `npm install`로 복구. **기본 Start-RAG는 static `assets/`** | `npm install` | 소스가 아니라 캐시. Next BFF를 당장 돌릴 계획이면 유지 | CHECK |
| `main/java/.../ChatService_copy.java` | 2026-06-26 | “outdated OpenAI client” 대체 심 | FQCN/파일명 참조 0. 활성 `main/java`에 있어 **컴파일은 됨** | `ChatService.java` | 본문이 3줄 주석 심. 이름에 copy가 있으나 역할은 죽은 심 | CHECK (소스셋 파일이라 보고서 묶음 삭제와 분리) |
| `ops/` (10파일) | 2026-06-26 | Grafana/Prometheus/WSL 치트시트 | `docs/PATCH_NOTES.md`가 placeholder 모니터링으로 언급. 라이브 Start-RAG 미사용 | 없음 | 이름만 운영처럼 보임. 실제 대시보드 연결 증거 없음 | CHECK |
| `demo-1/`, `lms-core/`, `cfvm-raw/` | 2026-06-26~ | 레거시 Gradle 모듈 | `AGENTS.md` Runtime Boundary: includeLegacyModules 게이트. 비활성이나 **모듈 자체는 소스** | 활성 `main/java` | 이번 감사(보고서/로그) 범위 밖 | KEEP |
| `__patch_drop__/` | ~2026-09-19 | PatchDrop 교환 | `AGENTS.md` 계약 | 자체 | 운영 큐. 이번 범위에서 삭제 금지 | KEEP |
| `scripts/` 현재 BAT가 부르는 것 | 2026-09-19 | 런처/정지/디버그/저널 | Start/Close/Debug BAT | 자체 | 사용 중 | KEEP |

---

## 2. 중복 리포트/문서 묶음 (대표 1개 + 통합 가능 여부)

같은 이름 ≠ 같은 내용. 아래만 “반복 보고서”로 묶었다.

| 묶음 | 대표로 남길 것 | 나머지 | 통합? |
|---|---|---|---|
| src111_merge15 자동 빌드 픽스 | `AUTO_BUILD_FIX_REPORT__src111_merge15__CHATGPT.md` 1개 ARCHIVE | 날짜 접미사 사본, `docs/BUILD_FIX_NOTES__src111_*`, `docs/PATCH_NOTES__src111_*` | 내용이 Java 21/` :app` only라 현재에 통합할 가치 낮음 → ARCHIVE 후 반복본 DELETE |
| 빌드에러 패턴 문서 | `tools/build_error_miner.py` + miner 사용법 1문서 | `docs/BUILD_ERROR_*`, `docs/build_error_*`, `__reports__/build_error_patterns.json` 2개 | MERGE: 사용법만 최신 miner에 맞게 1페이지 |
| src54/src91 changelog | `docs/PROJECT_STATUS.md` | 루트 `CHANGELOG.md`, `docs/CHANGELOG.md`, `docs/PAST_TRAJECTORY_INDEX.md` | 통합 불필요. 현황 문서가 대체 |
| GPT Pro 구 지시 | 없음(현행 `AGENTS.md`) | 루트 `GPT_PRO_AGENT_INSTRUCTIONS.md`, `docs/gptpro_agent_instructions_ko.md` | 오독 방지가 목적. 본문 통합하지 말고 ARCHIVE |
| 2026-09-17 패치 메모 | `docs/API_ROUTING_SPEC.md`, `docs/AGENT_API_SPEND_GUARD.md` | 루트 `PATCH_NOTES_api_routing.md`, `PATCH_NOTES_agent_api_spend_guard.md` | MERGE 가능(이미 SSOT에 존재하면 루트 메모 DELETE) |
| Codex 세션 정리 3종 | 각각 유지 | audit / directive / execution | **중복 아님** (역할 분리) |
| Display 문서 여러 개 | 스킬 `demo1-meta-display-simple-caption` + `docs/PROJECT_STATUS.md` | integration / verification / e3-cleanup / cue spec | 역할이 다름. 한 파일로 합치지 말 것 |
| `__reports__` 2026-06-05 notebook-*-directive 약 30개 | 없음(만료) | 동날짜 지시 더미 | 통합 가치 없음. DELETE |

---

## 3. 요약 (요청 순서)

### 3-1. 가장 오래되고 삭제 안전성이 높은 후보

다음 묶음은 내용 확인 + 현재 실행 경로 비참조 + 재생성/역사 가치 낮음.

1. 루트 `hs_err_pid*.log` / `replay_pid*.log` (2026-09-17, ~1.1 MiB)
2. 루트 빈 파일 `-`
3. `AUTO_ERROR_PATTERN_SNAPSHOT.md`
4. `docs/PAST_TRAJECTORY_INDEX.md` + `docs/STUFF3_NOTE.md` (깨진 포인터)
5. `docs/PLAN_CATALOG.md`, `docs/QUICKFIX_SUMMARY.md`, `docs/README_patch_summary.md`, `docs/sigmoid_orchestration_results.json`, `docs/DIAG_API_SAMPLE.json` 등 짧은 잔여
6. `verification/` 전체 (39 MiB, 2026-09-16 이전 컴파일 로그)
7. `output/` Playwright 실험 (3 MiB)
8. `__reports__/` 노트북 지시 md/txt (TSV 제외)
9. `analysis/README.md` 및 빈 `autoevolve_debug/`, `_patch_artifacts/`, `pki-validation/`
10. `logs/` 중 808x soak/bootRun 잔여 (단 `failure-pattern-memory.jsonl`, `awx-mcp-audit.ndjson` 제외하고 재확인)

이 10번은 **합쳐도 수십 MiB**다. 용량의 대부분은 아래 캐시다.

### 3-2. 중복 리포트/문서 묶음

위 §2 표. 실행에 영향 없이 줄일 수 있는 문서 군은 merge15/BUILD_ERROR/src91/GPT-Pro 지시/`__reports__` 지시 더미.

### 3-3. ARCHIVE로 돌릴 후보

저장소 밖(`C:\AbandonWare\_rescue\...`)이 원칙(레포 안 `archive/` 트리 금지, AGENTS.md).

- 루트 `HOW_TO_APPLY.md`, `GPT_PRO_AGENT_INSTRUCTIONS.md`, `CHANGELOG.md`, `amp-playbook.md`, merge15 AUTO_BUILD 보고서
- `docs/` 2026-06-26 클러스터(PATCH_NOTES, SRC91, INTEGRATION_*, AGENT_GUIDE, INJECTION_REPORT, PR1–4, JAMMINI, BUILD_FIX 반복본)
- `docs/superpowers/` 208파일 (설계 원본, 4.3 MiB)
- `docs/audits/` 원장 md (프로브 class는 DELETE)
- `data/agent-handoff/codex/report/` (272 MiB, 통째 폐기보다 이력 보관)
- 완료된 구 task 저널(오늘 것 제외) — 저널/`before/*.bin`은 복구본이므로 외부 rescue 후 검토

### 3-4. 실제 소스·실행에서 참조되어 건드리면 안 되는 것

- `AGENTS.md`, `.agents/skills/**`, `agent-prompts/` 현재 팩, `configs/api-routing.yaml`, `docs/API_ROUTING_SPEC.md`, `docs/PROJECT_STATUS.md`
- `Start-*.bat` / `Close-*.bat` / `Debug-*.bat` → `scripts/start_rag_stack.ps1`, `stop_rag_stack.ps1`, `debug_rag_stack.ps1`, `dev_reload_watch.ps1`
- `main/java`, `main/resources`, `app/src/main/java_clean`, `app/src/main/resources`
- `application.yml` → `var/abnadon/debug`, `var/abnadon/images`
- `var/rag-launcher`, `var/debug`, `var/dev-reload`
- `.env`, `shared.env`, `.env.example`, `apikey.txt`, `.secrets/`, openssl/opnessl, `tls-compat/*.pem`
- `__patch_drop__/` (활성 큐·리스)
- `data/agent-handoff/codex-autonomy/<활성·오늘 task>/journal.json` 및 checkpoint `before/*.bin`
- `gradlew.bat`, `build.gradle.kts`, `settings.gradle`, `gradle/wrapper/gradle-wrapper.jar`
- `ONNX` (ACL 보류)
- `EXTERNAL_SKILLS.md`
- `frontend/src/**`, `frontend/package.json` (node_modules만 별개)

### 3-5. 정리 시 예상 감소

**이번 단계 삭제 없음.** 후속 승인 시 대략치(중복 없이):

| 등급 | 대상 | 파일 수(대략) | 용량 |
|---|---:|---:|---|
| A. 안전한 소형 DELETE (로그·구문서·verification/output/__reports__지시) | §3-1 | ~2,200 | **~60 MiB** |
| B. 재생성 캐시 DELETE (`var/gradle-*`, `var/codex-smoke`, whisper venv, conversate runtime venv, `app/build`) | §1-E/F/G | ~180,000 | **~12.7 GiB** |
| C. ARCHIVE (docs 구형 + superpowers + codex/report) | §3-3 | ~12,200 | **~280 MiB** (디스크 회수는 외부 드라이브로 옮길 때만) |
| D. CHECK (whisper models 1.5 GiB, frontend/node_modules 0.3 GiB, verify_boot.sh, kakao 샘플, ops/) | — | — | 포함하지 않음 |

실용적 1차(사용자 다음 지시 가정): **A만** → 체감은 검색 노이즈 감소. **A+B(캐시/venv만, 모델·node_modules·저널 제외)** → 약 **12.7 GiB / 18만 파일**. Whisper `model.bin`(1.5 GiB)과 `frontend/node_modules`는 재확인 전까지 빼 둔다.

---

## 4. 후속 삭제 묶음 제안 (실행하지 않음)

지시가 오면 이 순서로 **다시 grep한 뒤**만 삭제:

1. **묶음 A1** — 루트 crash 로그 + 빈 `-` + `AUTO_ERROR_PATTERN_SNAPSHOT.md`
2. **묶음 A2** — `verification/`, `output/`, `__reports__` 지시문(TSV 제외), `analysis/README.md`, 빈 폴더
3. **묶음 A3** — `docs/` 깨진 인덱스/빈 카탈로그/짧은 잔여 JSON (`PAST_TRAJECTORY_INDEX`, `STUFF3_NOTE`, `PLAN_CATALOG`, `sigmoid_orchestration_results.json` 등)
4. **묶음 B1** — `var/gradle-home`, `var/gradle-user-home`, `var/gradle-task4-canonical-user-home`, `var/gradle-project-cache` (현재 런처 비사용 재확인)
5. **묶음 B2** — `var/codex-smoke` (브라우저 프로필 외부 복사 금지, 로컬 폐기)
6. **묶음 B3** — whisper `runtime/venv` + conversate `runtime/` (저널/json/`models/`는 남김)
7. **묶음 C** — 구형 문서를 저장소 **밖** rescue 후 루트/`docs`에서 제거

각 묶음마다 삭제 직전 재확인: 경로 참조 grep, 라이브 `application.yml`/BAT, work-ledger 복구본 여부.

---

## 5. 이번 작업이 하지 않은 것

- 파일 삭제/이동 0
- Git 명령 0
- 비밀 값 출력 0
- Java 좀비 클래스 추가 삭제 제안 최소화(이미 2026-09-18 위생 감사 범위)

검증: 디렉터리 용량 실측, 대표 파일 본문 열람, `application.yml`/`AGENTS.md`/`build.gradle.kts` 경로 대조. 브라우저/서버 재시작 없음(문서 감사).
