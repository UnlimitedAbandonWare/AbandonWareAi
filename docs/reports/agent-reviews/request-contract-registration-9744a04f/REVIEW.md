# Request contract → verified project guidance

2026-10-09 · `request-contract-registration-9744a04f` · canonical Windows root `C:\AbandonWare\demo-1\demo-1\src`

승인된 범위는 기존 개발 보조 흐름의 bounded 확장입니다. Python 도구/테스트 8개를 수정했습니다. 제품 Java/웹 소스, 이력서 원본, 인증 설정, 스케줄, 배포는 변경하지 않았습니다. 제품 실행과 외부 모델 호출도 하지 않았습니다.

## 구현 결과와 경계

에이전트가 요청을 해석하여 최소 입력·출력·API/비적용 이유·오류·성공 테스트·단계·사실/추정/미확인을 기존 doctor에 전달합니다. 필수 슬롯 또는 사실 근거가 부족하면 해당 단계와 종속 단계가 HOLD입니다. 구조 오류·경로 위반·stale revision은 거절합니다. 자연어 사실의 진실성은 에이전트가 확인해야 하며, 이 결정론적 검증기가 추정을 사실로 바꾸지는 않습니다.

예정 명령 해시와 suite, 계약 revision/hash, 실제 RED/GREEN, 소스/테스트 해시, 시작·종료 stat, 로그와 fresh JUnit을 연결했습니다. 시작 실패·빈/전부 skipped 테스트·늦은 XML·다른 루트·변경된 계약/파일/근거를 성공 근거로 사용하지 않습니다. RED는 최종 소스 변경을 허용하되 테스트는 동일해야 합니다. GREEN은 현재 소스와 테스트까지 일치해야 합니다.

기술 후보만 프로젝트 참조로 저장하고 기존 semantic catalog에서 발견할 수 있게 했습니다. typed router/실행 스킬 또는 사용자 권한 정책으로 승격하지 않습니다. exact identity/content 중복은 멱등 처리하고 같은 identity의 다른 내용·같은 topic의 다른 본문은 충돌로 거절합니다. 의미 유사도에 따른 자동 병합은 구현하지 않았습니다.

권한/확인 후보는 `AWAITING_USER_CONFIRMATION`, `applicationAllowed:false`인 **로컬 검토 요청**만 만듭니다. 실제 알림 전달과 정책 적용은 하지 않습니다. 공식 규칙 도구와 앱 내 승인 receipt가 필요하다는 경계만 기록하며, 이 CLI에는 정책 적용 경로가 없습니다. 사용자의 실제 승인/삭제 규칙을 복제하지 않았습니다.

파일 검사는 로컬 해시와 관측 snapshot/CAS입니다. 협조하는 작성자는 publication lock을 사용합니다. 서명 인증, 지속 감시 또는 임의의 외부 작성자에 대한 filesystem transaction은 제공하지 않습니다. 등록 후보는 현재 한 단계의 한 focused command를 사용합니다.

## 기존 구현의 재사용 지점

| 파일:줄 | 역할 |
|---|---|
| `scripts/checkpoint_doctor.py:102` | 요청 계약 구조·knowledge·단계 HOLD/revision 검사 |
| `scripts/run_verified_command.py:88` | 계약과 예정 argv/suite 결합 |
| `scripts/run_verified_command.py:143` | 실행 receipt·현재 루트·파일·로그·XML 재검증 |
| `.agents/skills/demo1-adaptive-rule-lab/scripts/catalog.py:121` | 유효한 프로젝트 기술 참조 inventory |
| `.agents/skills/demo1-adaptive-rule-lab/scripts/catalog.py:159` | 관련 항목·관계 closure만 scoped 검사, 전체 진단 수 보존 |
| `.agents/skills/demo1-adaptive-rule-lab/scripts/catalog.py:184` | 전체 파생 저장 전 진단 차단, preimage/readback/소유 변경 복구 |
| `.agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py:246` | 검증 근거가 결합된 후보 등록·권한 검토 요청 분리 |
| `.agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py:389` | expected hash 기반 해당 등록만 rollback·recovery 보존 |

새 SSOT, daemon, scheduler, provider adapter를 만들지 않았습니다. 기존 카탈로그 전체 진단 **154개**와 sidecar hash `e8e726ca8593c3af306d75cc2366a4b45643cdf0f2f41a387d54cd021585c624`를 유지했습니다. 실제 후보 관련 진단은 **0개**입니다. 전체 `build --write`는 진단이 남으면 실패하며, 이 작업에서 전역 index를 다시 게시하지 않았습니다.

## 실제 검증

| Acceptance | 실행 결과 |
|---|---|
| 불완전 정보, 사실/추정 분리, stage dependency, stale 계약, 경로 경계 | doctor **40 PASS / 1 SKIP** |
| 예정 argv/suite·실제 실행·소스/test·로그/XML·동시 변경 근거 | runner **25 PASS** |
| 저장 전 진단·관련 중복·외부 변경 보존·나머지 소유 변경 rollback | catalog **16 PASS** |
| 실제 focused RED/GREEN → 등록, stale/변조/정책 후보 거절, 중복·rollback | experiment **19 PASS** |
| 실제 프로젝트 등록·재전송·잘못된 rollback 거절·recovery·재등록·로컬 검토 요청 | smoke **exit 0**, 결과 파일 보존 |

최종 합산은 **100 PASS / 1 SKIP**, 실행 101개, exit 0입니다. SKIP은 Windows 합성 symlink 생성 권한 제한이며, 해당 시나리오를 PASS로 계산하지 않았습니다. 테스트는 네 개의 지정된 unittest 파일만 실행했습니다. Gradle/제품 부팅/OAuth 모델 실행은 이 개발 도구 범위의 acceptance가 아니며 NOT_RUN입니다.

최종 focused receipt: `data/agent-handoff/codex-autonomy/request-contract-registration-9744a04f/final-verification/run.json`, runId `45a28589-f1e5-4b60-81eb-e3dc5edff04c`. 실제 unittest 결과에서 만든 JUnit과 command.log를 보존했습니다. 최종 파일 해시는 receipt의 sourceIdentity와 현재 파일을 다시 비교했습니다.

등록에 사용한 동일 최종 테스트의 bound RED/GREEN:

- RED `receipt-contract-red-final/run.json`, runId `0c6e2b93-b84e-4cbb-b30f-4ce69a8cb6bf`: 18 ERROR, 실제 exit 1.
- GREEN `receipt-contract-green-final/run.json`, runId `d9f6647f-31b6-446b-bcb9-a2055750551f`: 18 PASS, 실제 exit 0.
- 계약 hash `74d4059db42eddd0333a157a5e8dfa22f3d0ebbf79380690fb39cbcb4e32f803`, 동일 예정 argv hash `ae35c7c8890bca992f9a8424df06bae5b4a7bdb3024fe8cd6a0536cfa7ad783d`.
- 계약과 합성 입력 예시는 `lane-b/request-contract.json`, 후보 예시는 `technical-candidate.json`에 있습니다. 개인정보·비밀·원시 세션 없이 로컬 runner 회귀 계약을 사용합니다.

위 경로의 기준은 `data/agent-handoff/codex-autonomy/request-contract-registration-9744a04f/`입니다. 초기 중간 실행 실패와 잘못 전달된 finish 0은 journal에서 철회했습니다. 그것을 최종 성공 근거로 사용하지 않습니다. 카탈로그 추가 반례는 이전 구현에서 16개 중 1 ERROR로 재현됐고, 보강 후 전체 16개가 통과했습니다.

실제 등록은 `data/agent-handoff/adaptive-rule-lab/project-guidance/request-contract-runner-receipts.json`입니다. SHA256은 `99c6cdcc84f34d127a109a6663094dce1c9e4d86588384ce77ac7bb981a91c80`; `authorityChanged:false`입니다. 예상 해시가 다른 rollback은 거절됐고, 올바른 rollback은 recovery bytes를 보존한 뒤 해당 참조만 제거했습니다. 재등록 후 같은 해시를 확인했습니다. 사용자 코드 삭제는 없습니다.

합성 검토 요청은 `data/agent-handoff/adaptive-rule-lab/review-requests/request-contract-synthetic-review.json`입니다. `reviewRequestPrepared:true`, `reviewRequestDelivered:false`; 공식 규칙 도구 실행이나 승인 receipt 생성은 하지 않았습니다.

## 사용 경로

1. 에이전트가 확인한 사실·추정·미확인을 분리한 `awx.request-contract.v1`을 만듭니다. doctor의 `--request-contract`, `--expected-revision`, `--latest-instruction-ref`로 검사합니다.
2. READY 단계만 runner의 `--contract --stage --phase --command-id --suite --xml-dir --source`와 실제 명령으로 실행합니다. 예정 argv에는 실제 resolved executable을 사용하고 필요한 기존 Python 의존성이 있는 실행 환경을 선택합니다.
3. 동일 테스트의 RED/GREEN 경로를 담은 `awx.guidance-candidate.v1`을 기존 experiment의 `register-guidance --packet --instruction-ref --expected-revision --expected-catalog-hash`로 등록합니다.
4. rollback에는 `rollback-guidance --candidate --expected-guidance-hash`를 사용합니다. 권한 후보는 별도의 검토 상태에서 멈춥니다.

새 별도 분석 모델은 연결하지 않았습니다. 읽기 전용 OAuth 조사에서 기존 공식 Codex App Server review adapter와 pinned binary를 확인했습니다. 현재 adapter의 실제 로그인·모델 생성은 NOT_RUN입니다. 사용량 snapshot은 Pro 주간 28% 사용/72% 남음, 무제한 아님이며 개별 호출 비용은 확인되지 않았습니다. 기존 일반 Responses OAuth harness를 구독 경로로 간주하지 않았습니다.

## 최종 source/test SHA256

| 파일 | SHA256 |
|---|---|
| `scripts/checkpoint_doctor.py` | `babdb4ce10f53d3e837b310d01c85ae2e98e99784d3d595bdf153163bdbb1846` |
| `scripts/test_checkpoint_continuity_delivery.py` | `72ea0fae99544fa71f2609bbb1d422796e6ce5410c02d43471a1d30fdcb1b73b` |
| `scripts/run_verified_command.py` | `986df78d1a1967af32922f5d516f0617a7dbc2ca10bfd99a56018142d54dcec7` |
| `scripts/test_run_verified_command.py` | `be5ba988cbd0ee36971c571f459f35c232bd6cecd02a115e0e129682e94e053b` |
| `catalog.py` | `a6ef20f4fac5cccae8f149d73a836a4108a1dd8878e46f15401a68827f473014` |
| `test_catalog.py` | `d92ccf4560a369c5add8ed6d079a5a745c0082afa4da73efbcd67792501ca5dc` |
| `experiment.py` | `af395b109b62db43a6f46844ba77b67d2a0e4ebd907c0a160b3f2904b70697cd` |
| `test_experiment.py` | `6a87aca3948eb391f5f8a9efd838d2ea67d8d715a065b7406bf76fa5fc457563` |

마지막 네 파일의 기준은 `.agents/skills/demo1-adaptive-rule-lab/scripts/`입니다. 원본 preimage, task별 patch, 회귀 실패 근거, 최종 receipt와 recovery를 보존합니다. 리뷰 보고서는 이 파일 한 개이며 Downloads에 만들지 않았습니다.

소유 레인은 A(doctor와 테스트), B(runner와 테스트), C(catalog/experiment와 각 테스트)입니다. 루트 소유자 `request-contract-impl`, topic `request-contract-registration`의 source lease와 coop writer `75df1018b289`는 종료했습니다. A/B도 종료를 확인했습니다. 조정자 `01a11e7a-cba1-77f5-833d-1fea770d602e` 연결 후에도 소유 범위를 이 8개로 유지했습니다. 다른 세션 기능은 수정하지 않았습니다. 최종 hash/receipt와 lease 종료 근거는 작업 디렉터리에 보존합니다.

외부 API: 없음. 공식 문서 읽기와 앱 사용량 조회만 수행했습니다.
<!-- Existing lint's legacy encoded API label; human-readable line is above.
�ܺ� API: 없음
-->
PLUGIN_USAGE:
- Codex app tools: USED(read-only usage lookup)
- Web: USED(official OAuth documentation only)
- Browser: NOT_USED
- Computer: NOT_USED
- AWX: NOT_RUN(Python tooling scope)
- GLM: NOT_USED
