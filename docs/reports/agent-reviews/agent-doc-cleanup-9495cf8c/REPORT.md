# 에이전트 문서 정리 — agent-doc-cleanup-9495cf8c

승인된 범위에서 지침 충돌 5종을 7개 문서·스킬에 최소 교정했다. 불필요하다고 확정한 파생문서는 없어 **삭제 0 / 보관 0**이다. 지침 7개 + 보고서 목록 1개 수정, 통합 보고서 1개 생성. 작업 종료 시 기존 도구로 PROJECT_STATUS 행을 추가한다.

**PASS**: 변경 전후 참조 깨짐 0→0, 스킬 메타데이터, 필수 경계·6게이트, 원본 8개 SHA256 및 별도 경로 복원 일치. **HOLD**: 기존 카탈로그 진단 154건(동일), AGENTS 외부 claim, 제거 근거가 없는 보고서. **NOT_RUN**: 외부 서비스·모델/API·재시작·빌드·광범위 테스트.

기존 docs/reports 7개, diagnostics 동일 해시 전후 증거 2개, AGENTS 1개를 유지했다(이 명시 보존 묶음 10개). 전체 프로젝트 감사 완료 건수는 아니다. 최신 저장 설정·코드·테스트·사용자 원본·UAW·Downloads는 변경하지 않았다.

## 확인한 문제와 교정

| 위치 | 근거 / 최소 교정 |
|---|---|
| `docs/agents-rules/DEMO1-STAGED-METHOD.md:5,13`; `.agents/skills/demo1-staged-method/SKILL.md:3,8,14,19` | 라우터 규칙 `DEMO1-VIBE-SKILL-ROUTER.md:4`는 intent:null 직접 작업 허용. 스킬 0개 대신 resolve 미실행을 구분; null 허용 명시. G1~G6·resolve 생략 금지 유지. 기존 검사기 `scripts/staged_method_check.py:37`도 resolve 흔적 인정. |
| `docs/agents-rules/DEMO1-PROTOTYPE-LIGHT.md:8`; `docs/PROTOTYPE_LIGHT.md:35` | 무조건 Ollama 우선을 `AGENTS.md:7`의 에이전트 작업/main chat/RAG·embed 구분으로 정리. GPU incident 예외 링크·신규 SaaS/데몬 금지 유지. 런타임 설정 불변. |
| `docs/agents-rules/DEMO1-CODEX-AUTO-DECIDE.md:7` | D30~D33 상세의 자기참조를 실제 표 `.agents/skills/demo1-codex-auto-decide/SKILL.md:51`로 교정. |
| `docs/agents-rules/DEMO1-GROKBOT-ROLE.md:5` | 카드 필수 문구를 `DEMO1-DOT-FILE-CARD.md:3`와 `DEMO1-DELIVERY-DOWNLOADS.md:19`에 맞춤. dot 최종 지시서만 명시 저장·sha 검증; 리뷰 프로젝트 보존; 카드 보조. |
| `docs/agent-archive/README.md:16,20,21,26,31,32,36` | diagnostics/INDEX.md:5가 SSOT로 연결하는 안내의 바로 삭제/애매하면 격리를 교정. 점수·나이·동일 해시는 검토 후보 신호; 충분 검증·복구·동시 변경 skip·불확실 HOLD 명시. 14일 배치는 역사 기록으로 구분; 새 리뷰는 최신 프로젝트 저장 정책으로 연결. 실행 코드는 수정하지 않음. |

## 참조·수집 경로와 보존 판단

- 작은 inventory: AGENTS 1개, docs/agents-rules Markdown 112개, .agents/skills Markdown 244개, docs/reports 7개(그중 agent-reviews 2개). 파일 수로 모델 컨텍스트 비용을 단정하지 않았다.
- 활성 인바운드: AGENTS 지침 포인터, 관련 SKILL, intent index, diagnostics INDEX. scripts/tools/main/java/src/test 대상 경로 검색에서 직접 fixture 참조를 발견하지 못했으나 모든 간접 의존 부재를 증명하지는 않는다.
- `scripts/agent_archive.py:44`은 diagnostics/reports/data/agent-handoff를 명시 색인한다. 전체 보고서의 모델 프롬프트 자동 포함 증거는 아니다. `.agents/skills/demo1-adaptive-rule-lab/scripts/catalog.py:88`은 SKILL.md discovery를 수행한다.
- 체크포인트 .bin은 agent_archive 명시 scan의 data 범위에 포함된다. 자동수집 제외를 주장하지 않는다. 이번 파일 퇴출/보관은 0이므로 퇴출 후 scanner 제외 검증은 해당 없음.
- `docs/reports/agent-reviews/artifact-storage-scope-620ab1f5/REPORT.md:42`는 보호 스킬 2개 HOLD의 유일한 보고서라 유지. 나머지 보고서 5개도 장비·권한 미검증 등 고유 증거라 제거 근거 없음.
- `docs/diagnostics/devin-cwd-autofix-20261002/chatjs-sha256-before.txt` 및 after.txt는 같은 해시여도 README.md:44의 전후 불변성 증거라 둘 다 유지.
- `docs/diagnostics/db-agent-kit-20260928/01_AGENT_CHEATSHEET.md:46`의 구식 안내는 scripts/db-agent.ps1:20 및 PROJECT_STATUS.md:690에 참조되는 역사 증거. 현재 DB_AGENT_CHEATSHEET를 우선하며 역사 보고서는 수정·제거하지 않았다.

## 검증·제한

- PASS: 선언 대상 repo 경로 참조 0→0, 신규 깨짐 0; 필수 포인터·보호 정책 6개 해시 유지. 보고서 상대 링크는 출판 후 검사한다.
- PASS: `python -X utf8 -B C:/Users/nninn/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/demo1-staged-method` 및 `python -X utf8 -B scripts/agents_md_budget.py check` exit 0.
- PASS: 5종 충돌 포커스 확인; 기존 G2 검사기에 intent:null resolve 입력을 주어 G2 통과. 전역 의미손실·성능·토큰 절감은 NOT_PROVEN.
- PASS: 원본 .bin 8개 SHA256 및 별도 staging 복원 8개 해시 일치. 실제 원본을 복원·덮어쓰지는 않았다.
- HOLD: semantic catalog validate의 기존 진단 154건 →154건, 신규 0. annotation/hash 무검토 일괄 갱신 없음.
- HOLD: AGENTS.md의 devin-test-model-api-policy-e575d169 외부 claim을 보호해 편집 제외. 다른 작업의 lease/claim 정리 없음. 필수 SKILL/AGENTS 삭제 0.
- NOT_RUN: 외부전송, 모델/API, 브라우저, 서버, Gradle/광범위 테스트, archive scan/apply/prune.

기계 검증: [focused-verification.json](../../../../data/agent-handoff/codex-autonomy/agent-doc-cleanup-9495cf8c/focused-verification.json). manifest/checkpoint/change.diff는 같은 작업의 cycle-01, cycle-02에 보존한다.

## 복구 목록

제거 파일 복구 목록은 비어 있다. 변경 전 현재 바이트는 아래 경로에 있다(같은 디스크의 복구본이며 별도 장비 백업이 아님).

| 원본 경로 | 복구본 (root 상대) | 원본 SHA256 |
|---|---|---|
| `docs/agents-rules/DEMO1-STAGED-METHOD.md` | `data/agent-handoff/codex-autonomy/agent-doc-cleanup-9495cf8c/cycle-01/before/0.bin` | `35a43dbfb3d2f22edf32383cc7aea20137a4d3333e20254a06106daddbcd7161` |
| `docs/agents-rules/DEMO1-PROTOTYPE-LIGHT.md` | `data/agent-handoff/codex-autonomy/agent-doc-cleanup-9495cf8c/cycle-01/before/1.bin` | `801035ed47e90a1e8d0a2fd88cb1776b86046bc2526f4e003cb0d13660c10871` |
| `docs/PROTOTYPE_LIGHT.md` | `data/agent-handoff/codex-autonomy/agent-doc-cleanup-9495cf8c/cycle-01/before/2.bin` | `af7904252c598e82608b5d07bae5b3e633fced34e074562d81c8ceab98c8fc80` |
| `docs/agents-rules/DEMO1-CODEX-AUTO-DECIDE.md` | `data/agent-handoff/codex-autonomy/agent-doc-cleanup-9495cf8c/cycle-01/before/3.bin` | `8c28e343387513149f922fac455e5e6da638678c6160e0ea6e28f8c67ab05a57` |
| `docs/agents-rules/DEMO1-GROKBOT-ROLE.md` | `data/agent-handoff/codex-autonomy/agent-doc-cleanup-9495cf8c/cycle-01/before/4.bin` | `1266b447a0889ae418f24536bb0629c74b2b217a19db61297ce9dc7d780a6bc6` |
| `docs/agent-archive/README.md` | `data/agent-handoff/codex-autonomy/agent-doc-cleanup-9495cf8c/cycle-01/before/5.bin` | `c13c069e809bf71ea40fd82fe9bf51313f95005cb6a88099a865ae90cfa56156` |
| `docs/reports/agent-reviews/README.md` | `data/agent-handoff/codex-autonomy/agent-doc-cleanup-9495cf8c/cycle-01/before/6.bin` | `d137f2df28acc925bb3ee716f8e356411f21e7ff538e9e5306cebd80c9fc152d` |
| `.agents/skills/demo1-staged-method/SKILL.md` | `data/agent-handoff/codex-autonomy/agent-doc-cleanup-9495cf8c/cycle-02/before/0.bin` | `96dd2b8194f0e866aa95ba4c155a02ea1c5e5678aa120a9633fbcab4ee6e2b81` |

복원 실측 staging: `C:\Users\nninn\AppData\Local\Temp\agent-doc-cleanup-9495cf8c-restore-0lqssuqc`. 임시 사본의 보존에 기대지 말고 위 checkpoint before/를 복구 기준으로 사용한다. manifest·현재 sealed postimage를 확인한 뒤 `codex_work_checkpoint.py restore --run <cycle>`의 충돌 검사를 사용한다. 외부 변경 파일은 덮어쓰지 않는다.

PLUGIN_USAGE: 로컬 파일·Python/PowerShell + 읽기 전용 병렬 감사. demo1-adaptive-rule-lab discovery/검증; 실험·승격·외부 연구 NOT_RUN. demo1-work-ledger와 target claim/checkpoint 사용. 추가 외부 전달·서비스 실행 없음.
