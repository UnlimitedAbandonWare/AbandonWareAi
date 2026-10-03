# mAWSain assist 패킷 — Devin 조수 산출물 (2026-09-27)

Codex의 do01~do05 제품 패치를 돕는 **도구·룰·검증 레일** 패킷이다.
Devin은 제품 Java/JS/Spring 소스를 수정하지 않았다. 이 폴더의 문서만 산출물이다.

- 입력: `PASTE_DEVIN_mAWSain_assist.txt`, `mAWSain_Codex_Directives_2026-09-27.md`,
  `mAWSain_Source_Evidence_2026-09-27.md`, `AGENTS.fragment.md`, `static_checks.json` (모두 Downloads, read-only)
- 기준: live 트리 `C:\AbandonWare\demo-1\demo-1\src` 직접 확인. ZIP 증거(E01~E12)는 live와 일치 여부를 개별 대조했다.
- journal: `mawsain-assist-0927-5685121e` · checkpoint: `cycle-01`

## 상태표

| pkg | 내용 | 상태 | 산출 |
|---|---|---|---|
| A1 | root doctor 근거 + toolbox 존재/능력표 | green | `A1-toolbox-inventory.md` |
| A2 | 동일 요청 조회 계약 맵 (최우선) | green | `A2-same-request-contract-map.md` |
| A3 | route→handler 탐색 runbook 초안 | green | `A3-route-to-handler-runbook.md` |
| A4 | AGENTS.fragment ↔ live AGENTS.md 병합 제안 | green (제안만; 적용은 Codex/사용자) | `A4-agents-merge-proposal.md` |
| A5 | 진단 UI/ZIP 재현 체크리스트 + 검증 명령표 | green (실행칸은 템플릿) | `A5-repro-checklist.md`, `VERIFY_COMMANDS.md` |
| handoff | Codex 다음 1~3 패치 심 + 금지선 | green | `CODEX_HANDOFF.md` |

## Devin이 실제로 실행한 검증 (verified subset)

| 명령 | exit | 관찰 |
|---|---|---|
| `python -B scripts/agent_preflight.py --root . --agent devin` | 0 | root=canonical, `allowedOpen=true`, tools 존재 |
| 존재표 python `os.path.exists` 배치 | 0 | build.gradle.kts/settings.gradle(.kts)/gradlew.bat/AGENTS.md/scripts/awx_mcp_toolbox.py/main/java/main/resources/src/test/java/app/src/main/java_clean 존재; pom.xml·mvnw·AGENTS.override.md 부재 |
| `python -B scripts/awx_mcp_toolbox.py --help` | 0 | CLI: `tool` + `--input-json` + `--runtime-session` |
| manifest 파싱 (`awx-control-tower-tools.json`) | 0 | 선언 도구 30개, 전부 toolbox handler에 대응 |
| live 소스 read/grep (E01~E12 대상 파일) | 0 | ZIP 증거와 live 일치 — 아래 각 문서에 라인 근거 |
| `python -B scripts/work_journal.py open` | 0 | taskId 발급 |

**NOT_RUN**: Java 컴파일/JUnit/Spring 기동/브라우저/유료 API — 이 패킷은 문서 전용이라
실행할 제품 변경이 없다. 제품 테스트 실행은 Codex 작업 단계에서 `VERIFY_COMMANDS.md`를 채운다.

## 소유권 경계 (PASTE §2)

- 제품 `main/java`, `main/resources/static/js`, Spring config — **Codex 소유**. 이 패킷의
  라인 근거는 Codex가 패치 위치를 바로 찾게 하는 지도이지 수정 지시가 아니다.
- `AGENTS.md` — 통덮어쓰기 금지. A4는 **제안문**이다.
- web.search flag OFF 유지. Browser admin/HOLD Done 조건 금지 (PROTO_OPEN).
- 신규 MCP 서버/RAG 구조/trace DB/exporter 금지 — 기존 자산 재연결만.

## 파일 목록

- `A1-toolbox-inventory.md` — 빌드 루트 존재표, toolbox CLI + capability 표, root doctor 초안 명세
- `A2-same-request-contract-map.md` — store/HTTP/tool 계약 + 갭 표 + A/B 재현 절차 + 테스트명 스케치
- `A3-route-to-handler-runbook.md` — 탐색 순서 runbook 초안 + AGENTS 포인터 문안
- `A4-agents-merge-proposal.md` — 기존 블록 매핑 + 신규 블록 제안문 + 충돌 검토
- `A5-repro-checklist.md` — 권한/소멸/서버오류/네트워크오류 4갈래 재현 + ZIP 검증 체크리스트
- `VERIFY_COMMANDS.md` — 실행 확인된 명령/FQCN + 빈 exit 칸 템플릿
- `CODEX_HANDOFF.md` — 다음 패치 심, 금지선, evidence_needed 목록
