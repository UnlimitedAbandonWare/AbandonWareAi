# 검색 교정 재발방지 스킬 보고서 — 2026-10-07

작업: search-recovery-skill-20261007-fb1553f6  
범위: repo 개발용 스킬 생성·등록·오프라인 검증. 이 작업의 제품 Java/UI 패치, 재시작, kill, commit, push, 유료 API, 새 패키지 설치, production 모델 호출은 모두 0.  
판정: 새 repo 스킬 등록 및 현재 파일을 이용한 정적/오프라인 검증 완료. 실제 앱의 검색·취소 복구와 새 스킬 자동 선택은 NOT_RUN.

검색 count/Sources/메타데이터 완료와 관련 본문의 실제 모델 전달을 분리하지 않으면 교정 완료를 잘못 판단할 수 있다는 반복 누락을 확인했다. 다만 특정 스킬이 호출되지 않았거나 사용 빈도가 낮았다는 호출 로그는 없으며, 그 주장은 하지 않는다. 기존 evidence-debugging의 첫 두 턴 계약과 SelfAsk/taskpacket을 복제하거나 덮어쓰지 않았다.

**읽은 자료와 관측 범위**

최근 2일 범위는 KST 10/6–7, UTC 2026-10-05T15:00:00–2026-10-07T15:00:00으로 해석했다. 아래 명시된 Downloads 문서와 관련 repo journal만 좁게 읽었다. 접근이 거부된 Codex sessions 저장소, 우회 복사, 비밀 파일, 전체 환경 덤프는 사용하지 않았다.

| Downloads 원본 | bytes / SHA12 / UTF-8 | 확인한 사실과 한계 |
|---|---|---|
| [SESSION469](C:/Users/nninn/Downloads/PASTE_CODEX_SESSION469_FOLLOWUP_SEARCH_CANCEL_RECOVERY_20261007.md:59) | 34986 / 1fbf26c057a6 / strict PASS | A1948/B1952 근거 count 7/6이 실제 본문 수신을 입증하지 않는다. |
| [SESSION469 본문 관계](C:/Users/nninn/Downloads/PASTE_CODEX_SESSION469_FOLLOWUP_SEARCH_CANCEL_RECOVERY_20261007.md:83) | 위와 동일 | B 근거에는 필요한 이름이 없지만 영상 W6에는 이름·관계 문장이 있다. qB와 extraction/packing/dispatch의 결속은 미확인; 이전 대화 오염은 가설이다. |
| [SESSION469 취소](C:/Users/nninn/Downloads/PASTE_CODEX_SESSION469_FOLLOWUP_SEARCH_CANCEL_RECOVERY_20261007.md:109) | 위와 동일 | 첫 token 전 timer/lock 조건부 source-extracted RED와 실제 영상 Stop/복구 사건을 구분해야 한다. 영상 Stop은 NOT_OBSERVED, 앱 회귀 NOT_RUN, 분석 ZIP NOT_READ. 추가 [검증 경계:247](C:/Users/nninn/Downloads/PASTE_CODEX_SESSION469_FOLLOWUP_SEARCH_CANCEL_RECOVERY_20261007.md:247). |
| [SESSION449](C:/Users/nninn/Downloads/PASTE_CODEX_SESSION449_WEB_CONTEXT_INJECTION_FIX_20261007.md:29) | 29632 / 802639e8d373 / strict PASS | 제공 출처가 있어도 prompt web/citable0, candidate/promoted0가 가능하다. Sources/metadata completion/unbound release는 본문 전달 증거가 아니다. 추가 [69](C:/Users/nninn/Downloads/PASTE_CODEX_SESSION449_WEB_CONTEXT_INJECTION_FIX_20261007.md:69). |
| [SESSION413](C:/Users/nninn/Downloads/PASTE_CODEX_SESSION413_EVIDENCE_AND_ANSWER_RECOVERY_20261007.md:47) | 38049 / 2e28c84c54da / strict PASS | builder web/citable4, credibility REJECT/한 줄 출력 기록과 실제 dispatch 메시지·draft 부재를 분리해야 한다. [136](C:/Users/nninn/Downloads/PASTE_CODEX_SESSION413_EVIDENCE_AND_ANSWER_RECOVERY_20261007.md:136)의 22 JS 실패는 assertion 전 fixture 오류여서 제품 RED로 쓰지 않는다. |

후속 기록이 앞선 보고서의 판정을 바꿀 수 있으므로 날짜·소스·실행본·버전을 각각 분리했다.

- [449 live-body journal:75](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/session449-live-body-proof-03/journal.json:75): wire89/SSE 성공 뒤에도 original prompt0, candidates2/policyDenied2를 기록.
- [449 default-plan journal:52](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/session449-default-plan-fix-04/journal.json:52) 및 79: 이후 default-safe seed 문제에 RED4→GREEN30, affected602, fresh prompt6/citable5 기록이 있다. Downloads449의 IMPLEMENTATION_NOT_RUN을 후속 작업 전체로 확대하지 않았다. fail_soft 판정도 별도다.
- [449 proof audit:18](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/session449-proof-audit-02/journal.json:18), 36, 45: 완료 표시와 body receipt, starvation4/0을 나누고 새 근거 없는 반복 감사를 경계.
- [413 recovery:83](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/session413-recovery-910ee375/journal.json:83), 95 및 [postreboot:149](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/session413-postreboot-564043e3/journal.json:149): HTTP200/Verify0와 model timeline·provider receipt·quality gaps를 분리.
- [469 directive:32](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/session469-directive-df3491b5/journal.json:32) 및 48: doc lint PASS는 앱 PASS가 아니다. [별도 recovery:14](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/session469-recovery-52e48f2a/journal.json:14), 23은 읽은 시점의 in_progress/lease 기록이며 완료 증거가 아니다.

이 journal의 숫자·live 결과는 기록을 확인한 것이며 이 작업에서 해당 원시 receipt나 제품 실행을 재검증한 결과가 아니다. “수정이 전혀 없었다” 또는 “현재 앱이 해결됐다”로 승격하지 않았다.

첨부 Library 식별자 libfile_31ba5ec6351881918771d5a942c3ce94의 권위 있는 제목은 UAW(7).txt, 크기는 317295B, version_id=null, modified_at=2026-10-07T03:08:52.210070Z였다. 현재 지원 read로 1–40, 2593–2605, 2872–2882, 3327–3346의 관련 부분을 확인했다. read snapshot은 [uaw-library-read-snapshot.json](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/search-recovery-skill-20261007-fb1553f6/uaw-library-read-snapshot.json)에 있다. 전체 문서의 나머지는 NOT_READ, 원본 로컬 bytes는 NOT_MATERIALIZED여서 원본 SHA를 주장하지 않는다. 로컬 같은 이름 파일을 추측하지 않았다.

UAW는 옛 설계 참고다. 1–40은 정적/실행 증거의 차이와 stale 가정을 경고하고, 2593/2872는 상황별 전략, 3327은 한 결정적 근거에 집중하는 탐침을 설명한다. SAFE/BRAVE/NEEDLE의 조건 선택만 참고하고 TRIAD는 현재 사용자 지시와 기존 SelfAsk 절차를 따른다. 옛 sourceSet·배선·확률 수치·권한·임계값을 현재 사실이나 새 정책으로 옮기지 않았다.

**생성·등록한 스킬**

[demo1-search-recovery/SKILL.md:1](C:/AbandonWare/demo-1/demo-1/src/.agents/skills/demo1-search-recovery/SKILL.md:1)는 481 words의 검색 교정 전용 entry다. [lifecycle-contract.md:5](C:/AbandonWare/demo-1/demo-1/src/.agents/skills/demo1-search-recovery/references/lifecycle-contract.md:5)는 provider execution/admission/alias/timeout/empty/filter/fetch/mainbody/packing/dispatch/citation/cancel recovery를 나누며, 동일 request/turn의 최초 확인된 단절 하나와 다음 구별 검사를 고르게 한다. [26](C:/AbandonWare/demo-1/demo-1/src/.agents/skills/demo1-search-recovery/references/lifecycle-contract.md:26)은 RED→최소 fix→같은 A/B 및 cancel→새 검색 회귀, [36](C:/AbandonWare/demo-1/demo-1/src/.agents/skills/demo1-search-recovery/references/lifecycle-contract.md:36)은 현재 installed hash/provenance/actual runtime과 검증 등급의 분리를 다룬다.

기존 [evidence-debugging:27](C:/AbandonWare/demo-1/demo-1/src/.agents/skills/demo1-evidence-debugging/SKILL.md:27), [case-contract:30](C:/AbandonWare/demo-1/demo-1/src/.agents/skills/demo1-evidence-debugging/references/case-contract.md:30)의 5-slot/첫 두 턴 계약을 링크로 재사용했다. 기존 resilience는 empty/filter를 이미 소유하고, search-zero-result-recovery 및 rag-search-diagnosis는 deprecated aliases이므로 전부 신규 발명이라고 주장하지 않는다. 기존 스킬에 작은 reference만 붙여도 원칙은 표현할 수 있지만, 명확한 검색 교정 entry를 원한 사용자 요청에 맞춰 좁은 새 entry와 최소 등록을 택했다.

[INDEX.md:130](C:/AbandonWare/demo-1/demo-1/src/.agents/skills/INDEX.md:130)에 typed skill row, [skills-intent-index.yaml:752](C:/AbandonWare/demo-1/demo-1/src/.agents/skills-intent-index.yaml:752)에 좁은 intent를 추가했다. agents/openai.yaml은 implicit invocation 허용 선언을 유지한다. 실제 파일 3개와 source/pairedArtifact가 존재하고 offline resolver가 이를 선택하므로 문서만 작성한 상태가 아니다. 현재 자동 선택 엔진의 실제 호출 receipt는 없으므로 자동 호출은 NOT_RUN이다.

새 matcher는 반복 교정·후속 검색 실패·취소 복구 표현을 다루고, 일반 로그 검색·문구 수정·Display/외부 검색 도구는 기존 owner를 보존한다. 기존 tier2 승격은 유지하며 “search failure loop”는 최종 triad, autoPromotedFrom은 search-recovery로 구분한다. 전역 orchestration/router 코드는 바꾸지 않았다.

**압력 사례와 전후 판단**

작성 전에 기존 evidence-debugging을 허용한 baseline 6사례를 실행했다. 기본 안전 판단 대부분은 이미 통과했다. 실패를 꾸며 스킬 효과를 주장하지 않았다. 실제 누락 RED는 전용 entry/등록 부재(8 failing assertions)와 독립 리뷰가 재현한 새 matcher의 범위 오류였다.

| 사례 | baseline 관측 | 새 스킬을 읽은 fresh-context 결과 |
|---|---|---|
| T1 첫 2턴/실행본 불명 | A/B request와 served build를 먼저 요구; 원인 미확정 | 5 slots로 동일 owner/session A→B, source/runtime 신원 및 미관측 단계를 유지 |
| T2 empty vs filter | E=executed_empty, F=8→0 filter starvation; 교체 미확정 | qF rejection reason을 다음 관측으로 선택; old disabled 분리, 도메인 계약 보존 |
| T3 출처 본문/packing | raw excerpt: “Obtain the authoritative qC model-input receipt…” | packing count7의 span 포함이 미확인이므로 qC packed-span을 먼저 확인; 이후 실제 dispatch |
| T4 cancel | unit RED와 영상 Stop 미관측, fresh request NOT_RUN 분리 | 새 검색의 provider→citation은 N/A가 아닌 NOT_RUN; pre-token/관련 after-token, cleanup/late overwrite 확인 |
| T5 old trace/빈 근거 | count6/영상/dispatch를 분리, paid 모델 거절 | qB video extraction/selection의 최초 미확인 경계 관측; old qA trace로 qB를 단정하지 않음 |
| T6 설치/배포 | hash/version 확인, 이전 subject 오염은 가설 | 현재 artifact/serving runtime 결속 후 downstream 재현; exit0를 배포 PASS로 쓰지 않음 |

추가로 같은 복합 압력 packet을 무안내 5개 컨텍스트와 새 스킬 안내 5개 fresh 컨텍스트에서 각각 실행했다. packet: C의 unit RED→GREEN, Stop 영상 미관측, serving runtime 불명; 같은 세션 N은 본문에 관계 span이 있지만 packing에서 빠짐, dispatch 불명, URL6/HTTP200/releaseAllowed만 있고 unrelated old trace가 있다.

무안내 5개는 최초 관측을 주로 source→installed 또는 Stop 미확인에 두었다. 예: “C lacks same-request Stop dispatch evidence; installed runtime is unknown.” 안내 5개는 이미 확인된 N의 mainbody→packing 누락을 결정적 재현 대상으로 골랐다. 예: “mainbody → packing is the earliest demonstrated discontinuity. Missing dispatch receipt does not establish a dispatch defect.” 각 flagged 판단을 직접 읽었다. 양쪽 모두 완료를 거짓 선언하지 않았다. 이 5/5는 해당 표본의 횟수이며 성공 확률, 일반 효과 수치, 실제 자동 호출 빈도가 아니다. 선택한 verbatim excerpts와 현재 read hashes는 [offline-behavior-evidence.json](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/search-recovery-skill-20261007-fb1553f6/offline-behavior-evidence.json)에 있다.

독립 리뷰는 처음에 넓은 explicit matcher의 로그검색/Display/문구수정 침범을 P2로 재현했다. RED4→GREEN 뒤 언어별 external-tool 제외 불일치와 복합 follow-up 가중치/“displays” 경계도 RED4로 확인해 고쳤다. 최종 f83d9f745db4 스냅샷에 대해 독립 reviewer가 6/6 PASS 및 남은 material blocker 없음으로 수용했다. SKILL/reference/metadata는 이 라우팅 교정 동안 바뀌지 않아 앞선 fresh-context read hash와 일치한다.

**명령과 검증 범위**

아래 명령은 canonical root에서 실행했다. T=data/agent-handoff/codex-autonomy/search-recovery-skill-20261007-fb1553f6.

| 실행 | 관측 결과 |
|---|---|
| python -B T/test_search_recovery_registration.py | 최초 RED8 → 초기 GREEN4 tests → scope RED4 → GREEN5 tests → parity RED4 → 최종 GREEN6 tests. 최종 8 positives, 10 negative cases, 3 기존 route 및 tier2/typed registration 포함 |
| python -B C:/Users/nninn/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/demo1-search-recovery | Skill is valid |
| python -B scripts/skill_frontmatter_lint.py --path .agents/skills/demo1-search-recovery/SKILL.md --json | violations 0 |
| python -B scripts/awx_skill_router.py lint | ok=true, errors=[], intents86/referencedSkills91; 기존 unindexed64 warnings 유지 |
| python -B -m unittest scripts.tests.test_vibe_router_golden scripts.test_demo1_vibe_skill_router_cases | combined exit1. golden은 48/52로 기존 ≥90% threshold PASS지만 g24/g44/g50/g52 mismatches 유지. 별도 directive-writer body-hash assertion FAIL |
| preimage YAML vs 현재 YAML을 실제 router.resolve로 비교 | golden 52개 전체 결과가 byte/field 수준 동일. [final-golden-comparison.json](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/search-recovery-skill-20261007-fb1553f6/final-golden-comparison.json) |
| source lease/parallel preflight/checkpoint | OWNER/CLEAR; 외부 live 파일을 수정하지 않음. exact targets의 preimage bytes 보존, guarded apply 및 현재 postimage 검증 |

combined regression의 body mismatch는 변경하지 않은 demo1-agy-directive-writer: actual 0028f456d172… vs fixture 9df1e10e448c…다. [preimage-regression-comparison.json](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/search-recovery-skill-20261007-fb1553f6/preimage-regression-comparison.json)에 기록했고 fixture/다른 owner 스킬을 완화하지 않았다. 읽기 audit가 실행한 기존 governance typed-route 검사도 archive canonical identity 23 failures를 보고했으며 본 신규 등록의 PASS로 숨기지 않는다.

초기 test harness 인자 순서/PowerShell 전달 오류는 수정하고 valid RED를 다시 얻었으며 제품 RED로 세지 않았다. 보호된 .agents 쓰기와 사용자 Downloads 전달은 자동 승인 검토를 거친 선언된 작업으로만 수행했다. local file date, exit0, mock/simulated PASS 또는 URL만으로 runtime PASS를 만들지 않았다.

정적: 명세/frontmatter/등록/source·pairedArtifact·UTF-8/current hash PASS.  
오프라인 행동: 6 pressure cases + 무안내5/안내5, 현재 내용 read hash 및 독립 리뷰 수행.  
제품 runtime: NOT_RUN. 검색·취소 사건의 실제 앱 수정/설치/restart는 이 작업 대상이 아니었다.  
자동 호출: NOT_RUN. allow_implicit_invocation=true와 offline resolver 선택만 확인했다.

**현재 파일과 provenance**

| 파일 | bytes | SHA12 | UTF-8 |
|---|---:|---|---|
| .agents/skills/demo1-search-recovery/SKILL.md | 3752 | 1f33e0f7f4ab | strict PASS |
| .agents/skills/demo1-search-recovery/references/lifecycle-contract.md | 6030 | a395cdeaf7d0 | strict PASS |
| .agents/skills/demo1-search-recovery/agents/openai.yaml | 317 | 7954341e6ee4 | strict PASS |
| .agents/skills/INDEX.md | 55017 | 828b13f923af | strict PASS |
| .agents/skills-intent-index.yaml | 50145 | f83d9f745db4 | strict PASS |

전체 SHA-256은 [current-hashes.json](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/search-recovery-skill-20261007-fb1553f6/current-hashes.json)에 있다. INDEX preimage=b7e60577cc85…, intent preimage=a7583cd2bc07…; cycle-02/03/05-route-parity에 restorable preimage와 diff/postimage receipt를 보존했다. 기존 evidence-debugging 3파일과 SelfAsk SKILL의 시작/종료 hash는 동일([protected-reference-hashes.json](C:/AbandonWare/demo-1/demo-1/src/data/agent-handoff/codex-autonomy/search-recovery-skill-20261007-fb1553f6/protected-reference-hashes.json)). 새 스킬 current hashes와 5회 behavior read hashes가 일치한다.

이 보고서 자체의 UTF-8/bytes/SHA12는 저장 후 output-receipt.json에 기록한다. canonical root Downloads 미러와 요청한 C:/Users/nninn/Downloads/SEARCH_RECOVERY_SKILL_REPORT_20261007.md는 동일 bytes로 전달하고 hash 일치를 확인한다. 소유 source/report lease와 cooperative writer를 종료한다. 앱 복구/자동 호출 미실행 및 기존 unrelated regression은 이 보고서의 명시된 한계이며 새 스킬 등록 완료와 혼동하지 않는다.

외부 API: 없음 (유료 API 및 production 모델 호출 0; 첨부 Library read만 수행)
PLUGIN_USAGE:
- skill-creator: USED(신규 repo 스킬 구조 및 validator)
- superpowers: USED(writing-skills와 TDD의 offline RED/GREEN; Git 배포는 사용자 금지로 실행 0)
- openai-library: USED(지정 첨부의 관련 부분 read)
- computer-use: NOT_USED
- GLM: NOT_USED
