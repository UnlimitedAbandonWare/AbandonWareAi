# Meta Display verification

전체 음성·RAG·안경 연동 목표는 진행 중이다. 이 문서는 2026-09-13 현재 소스와 이번 Desktop 후처리에서 실행한 증거를 구분한다. 과거 보고서의 PASS, mock 응답, 문서 MCP 정상 응답을 실제 모델 또는 안경 성공으로 승격하지 않는다.

## 이번 실행 결과

| 항목 | 상태 | 실제 근거와 한계 |
| --- | --- | --- |
| 최신 동시 편집 지침 | PASS | 루트 AGENTS의 target-scoped 계약 재확인; 이전 턴의 48개 concurrency 검사는 이번에 재실행하지 않음 |
| E0 stale 기록 | PASS | AGENTS hash만 달랐음. 실제 Desktop resolver PASS/match 후 원본 보존 및 CAS 확인으로 E0 갱신 |
| 단계 선택기 | PASS / 다음 단계 미완료 | E0 갱신 후 acceptedStageCount=3, nextStage=E3 |
| Display client 계약 | PASS | `node scripts/meta_display_webapp_contract_tests.cjs`: 49 pass, 0 fail |
| Conversate UI·PCM | PASS | 아래 Node 명령: 36 pass, 0 fail |
| Java | PASS | startup doctor의 Java 17 확인 |
| Ollama daemon | PASS, 도달성만 | 기존 loopback `/api/version` 정상; 모델 준비·생성은 not_observed |
| Upstash 환경변수 | EVIDENCE_NEEDED | URL/token이 process/user/machine에 없음. 실제 전체 Spring 설정의 부재까지 의미하지 않음 |
| Meta MCP 설정 | PASS | 기존 metaWearables enabled, 공식 endpoint 일치; 중복 등록 없음 |
| Meta MCP 실제 조회 | PASS | Python MCP SDK initialize 및 tools/list 후 search_webapps_docs와 search_dat_docs 각각 1회, isError=false |
| Meta 도구의 현재 기본 catalog 노출 | NOT_EXPOSED | 실제 HTTP MCP 조회 성공과 현재 앱 도구 노출은 별도 |
| AWX Control Tower 호출 | FAIL, 해당 probe만 | run_pipeline 응답: pipeline-plan-timeout, exit124, elapsed45016ms. 반복 호출하지 않음 |
| 이전 Display runtime 3개 | STOPPED | 기록된 listener processId 8396/30036/11708 모두 현재 존재하지 않음; 기록 파일 보존 |
| 이번 전용 Spring 시작 | PASS | 기존 실행기 exit0, listener-ready; server18169 / management63145 / netty63146. 전용 build outputs와 두 cache 경로 사용 |
| 현재 소스 빌드 | PASS, 컴파일 범위 | BUILD SUCCESSFUL 2m44s; LangChain4j purity, sourceSet hygiene, root와 app compileJava. 전체 Java test 실행을 의미하지 않음 |
| Display 실제 제공 파일 | PASS | 6개 모두 HTTP200이며 현재 파일과 served SHA-256 일치 |
| 실제 브라우저 Display | PASS, 입력 범위 | 화면 렌더링, 합성 입력 후 전송 활성화, Escape 후 전송 버튼 포커스, 입력 제거 후 전송 비활성화. 요청 전송 0회 |
| Conversate 인증 경계 | EVIDENCE_NEEDED, 해당 요청만 | `/conversate` 및 `/api/assist/bootstrap` 비인증 요청은 302 → `/login`; 브라우저 Operator sign-in 관측. 이 서버의 인증된 세션 필요 |
| 현재 Display scoped status | PASS | 최신 6개 TargetManifest로 status exit0, conflictingCount=0, repositoryWideHold=false. 문서 후처리는 애플리케이션 소스 lease 불필요 |
| 실제 RAG/모델 응답 | NOT_TESTED | 문서 조회·합성 client tests는 모델 증거가 아님 |
| 실제 한국어 마이크·STT | NOT_TESTED | 자동 PCM/capture lifecycle tests와 실제 장치 입력을 구분 |
| 공식 Simulator·실기기 | NOT_TESTED | 실제 확장 사용·안경 렌즈 표시 관측 필요 |
| 기존 HTTPS 후보 요청 | FAIL, 해당 요청만 | 저장소 domain start 실행기의 기본 도메인에서 Display 경로 GET 1회 → HTTP502. TLS 검사 유지, 인증 전송·redirect 추적 없음. 원인 proxy/upstream 미판정 |
| 안경용 공개 HTTPS | NOT_TESTED | 작동하는 최종 URL·인증·실기 도달성 증거 없음; 공개 배포 수행 안 함 |
| 소유 runtime 신원 | PASS | continuation에서 기존 lifecycle의 현재 프로세스 대조 결과 owned-runtime-attributed / identity-match, 관리 대상 포트 3개. 중지 호출 없음 |

```powershell
node scripts/meta_display_webapp_contract_tests.cjs
node --test src/test/js/conversate-ui.test.cjs src/test/js/conversate-pcm.test.cjs
python -B .agents/skills/demo1-meta-display-verification/scripts/startup_doctor.py --probe
python -B .agents/skills/demo1-meta-display-webapp/scripts/next_step.py --root .
```

## 첨부 요구사항별 대조

| 첨부 항목 | 현재 증거 | 남은 수용 조건 |
| --- | --- | --- |
| 1. 기존 Java RAG 재사용 | 기존 assist, BM25, lexical reranker, Ollama native 구현 존재 | 실제 읽기 전용 검색·모델·출력의 동일 요청 증거 |
| 2. 소스·설정·보관 경계 | 현재 owner와 호출 관계 확인, 기존 chat sync의 persistence 구분 | 현재 변경된 범위의 빌드·실행 결과; 보호 설정 보존 |
| 3. 공식 문서 MCP | 설정 중복 없음, 실제 두 검색 도구 반환 | 현재 앱 catalog에서 직접 노출되는지는 미확인 상태 유지 |
| 4. API/인증/플랫폼 구분 | Meta DAT attestation, STT, 자체 인증, 문서 MCP 역할 분리 | 모바일 앱/기기별 실제 구성과 권한 검증 |
| 5.1 실제 카드 표시 | 두 웹클라이언트 소스, client 검사, 실제 Spring Display 파일 6개 hash 일치 및 브라우저 입력 증거 | 인증된 안경 출력, 공개 HTTPS |
| 5.2 휴대폰 수집기 | 명시적 시작/중지·장치·PCM·background 정리, 36개 UI/PCM 검사 | 실제 휴대폰 권한·잠금·통화·BT·장시간 녹음 |
| 5.3 초기 STT | 기존 로컬 ASR bridge 재사용 가능 | 승인된 실음성 전사; 유료 STT 선택 시 실제 키·예산·호출·오류 분류 |
| 6.1 휘발성 RAG | assist 메모리·ACL 준비 자료·제한 검색·검증 카드 존재 | 실질 응답 의미 및 부족/충돌/악성 입력의 실제 생성 결과 |
| 6.2 Ollama 준비 | daemon health, 선택적 stateless generator 존재 | 정확한 모델 readiness, cold/warm 구분, 실제 생성·취소 |
| 7. 전송/보안 | 인증된 assist API/SSE, CSRF, epoch·late-result 방어 존재 | 서로 다른 실제 사용자·기기의 접근/재접속/만료 시험 |
| 8. Android DAT 대안 | 공식 문서 조회 및 native attestation 역할 확인 | 필요성이 입증된 네이티브 앱 경로에서만 별도 구현·실기 시험 |
| 9. 관측 | count/reason/hash 위주 evidence, 원문 없는 진단 | 실제 단계별 latency·시도·성공률. 측정하지 않은 값은 null/미관측 |
| 10. 교차 검토 | native explorer로 호출 경계 수집, parent가 local generator 존재를 직접 재검증 | 애플리케이션 변경 시 기존 세 역할 preflight 하나만 적용 |
| 11. 완료/반례 | 현재 49+36개 client 검사는 명시된 클라이언트 범위만 증명 | 실기·실음성·동일 요청 모델·권한·전체 회귀의 요구별 증거 |
| 12. 제출물 | 이 문서와 integration 문서, 작업별 MCP/접수/빌드/HTTP/브라우저 evidence | 미완료 실음성·모델·실기 단계를 각각 계속 수행 |

## 실제 호출 수와 보관

이번 공식 Meta 문서 검색 호출은 2회다. 안경 제어 호출, Meta 자격 증명 변경, 유료 STT 호출은 0회다. 이번 client 검사는 합성 전송을 포함하므로 실제 모델 요청 수에 더하지 않는다. AWX pipeline probe는 1회이며 timeout 결과를 그대로 보존한다.

후속 continuation에서는 기존 HTTPS 후보에 무인증 GET을 1회 수행했다. 공식 MCP나 AWX의 기존 실패 호출은 반복하지 않았고, 추가 모델 생성 요청도 0회다. 이 후속 결과와 변경분 diff는 작업별 `continuation-02/`에 보관한다. 이전 summary의 파일 해시는 당시 snapshot이며 이후 문서 변경의 현재 해시로 사용하지 않는다.

작업별 증거: `data/agent-handoff/codex/report/display-postprocess-01a097f3-20260913/`. E0의 실제 resolver 증거는 기존 Display evidence 폴더의 `desktop-intake-01a097f3-postprocess.json`이다. 이 문서의 상태를 바꾸려면 해당 명령 또는 실제 관측 증거를 먼저 추가한다.

## 다음 검증

2026-09-13 후속 대조에서 별도 Desktop 작업 `01a09926-8585-7293-bbdd-2e41681cbcdf`가 남긴 로컬 통합 증거를 검사했다. `final-evidence.json`의 대상 13개는 현재 파일 해시와 모두 일치했고, 해당 bootJar 해시도 일치했다. 같은 작업의 실제 Gradle XML 9개에서 Java 테스트 54개, 실패·오류·생략 0개를 집계했다. `ui-final.log`의 Node 검사는 40개 통과·실패 0개다. 앞 표의 36개는 이번 작업이 먼저 실행한 시점의 검사이며, 40개와 더해 독립 테스트 개수로 계산하지 않는다.

별도 작업의 `final-browser-audio.json`은 합성 한국어 PCM → 실제 로컬 ASR → 준비 자료 검색 → Ollama → 카드의 단일 시나리오를 통과했다. 검증 스크립트는 확정 전사 1건 이상, 정확한 예상 카드 문구와 자료 ID, 생성 시도 1회, `generationReason=GENERATED`를 요구한다. 관측값은 확정 전사 1건, 생성 시도 1회, 생성 2405ms, pause 응답 73ms다. 원음·전사 저장은 false, 실휴대폰·실안경 검증은 false다.

그 시험의 `endToCardMs=6471`은 시험 클라이언트의 HTTP 카드 관측 시각에서 합성 음성의 명목 길이 2750ms를 뺀 값이다. 실제 안경 렌더링·광학 출력 또는 정확히 측정한 휴대폰 발화 종료부터의 지연으로 해석하지 않는다. 이전 시도의 `GENERATION_TIMEOUT / 4012ms`도 원래 파일에 남아 있으며 정상 cold start가 항상 보장됐다는 증거가 아니다.

이 결과는 별도 loopback18089의 fixture 인증 환경에서 얻었다. 실제 브라우저 600×600 관측과 카드 receipt 기록은 로컬 표시 증거이며, 일반 운영 계정·공개 HTTPS·공식 Simulator·휴대폰 마이크·안경 렌즈 증거를 대체하지 않는다. 이번 후처리는 새 모델 호출 없이 실제 파일과 검사 조건을 대조했으며, 재사용한 증거의 경로·해시와 범위는 `continuation-02/peer-local-integration-evidence.json`에 고정했다.

이번 서버의 인증된 휘발성 Conversate 세션과 접근 가능한 준비 자료를 확보한 뒤 동일 요청의 입력→검색→실제 모델→출력 증거를 수집한다. 인증 구현은 기존 AdministratorRepository/UserService 계정을 사용한다. Meta DAT 자격 증명은 이 서버 로그인 자격 증명이 아니며 로그인 우회나 새 계정 발급으로 대체하지 않았다.

동시에 실행 중인 별도 Desktop 작업이 ASR와 로컬 모델의 실제 시험을 수행하는 것을 작업 상태에서 관측했다. 그 진행 메시지는 이번 서버의 성공 증거로 합산하지 않으며, 같은 GPU에 중복 생성 요청을 추가하지 않았다. 일반 chat sync를 호출하여 음성 전사의 무저장 요구를 대체하지 않는다.

현재 `holdScope=authenticated-assist-runtime-proof`, `firstBlockingRule=authenticated-session-required`, `blockingEvidence=HTTP302-and-browser-login`, `independentWorkCompleted=docs+mcp+compile+assets+browser-input`, `repositoryWideHold=false`다. 공식 Simulator, 공개 HTTPS, 실기기 증거 역시 각 표의 미완료 상태를 유지한다. E3/E4 완료 기록을 만들지 않았다.

## 세 번째 목표 턴의 중단 조건 재확인

2026-09-13 15:07~15:09 KST에 새 관측을 수집했다. 전용18169의 Display 파일은 HTTP200, `/conversate`와 bootstrap은 여전히302→login이었다. 기존 lifecycle은 현재 프로세스를 `owned-runtime-attributed / identity-match`로 확인했다. 새 Display TargetManifest의 scoped status는 전체 source lease가1개인 상황에서도 `conflictingCount=0`, `unrelatedCount=1`, exit0, `repositoryWideHold=false`였다.

현재 공유443의 Python gateway는 listener1개로 확인됐으며 예상 gateway 명령 경로가 관측됐다. 원래80 upstream listener는0개였다. 준비된 gateway 소스 세 파일의 postimage 일치는 적용 준비 증거이고, live 설정·공개 인증·실기 완료 증거가 아니다. 안경·Neural Band 페어링은 기존 설치 작업의 사용자 확인 기록이 있으므로 재요청하지 않는다. 그 기록의 Android 화면 등록·앱 로그인·실제 표시 결과는 여전히 별도 조건이다.

이번까지 세 번의 연속 목표 턴에서 기존 인증·외부 운영 조건이 해결되지 않았다. 독립적으로 가능한 소스 락 검증, 문서, 변경분 diff, 실제 MCP 문서 조회, 빌드·클라이언트 검사, 제공 파일·브라우저 검사와 다른 작업의 실제 로컬 음성 증거 대조는 수행했다. 목표 전체는 완료가 아니며, 해당 실행 단계는 `blocked`로 정리한다. 저장소 전체 HOLD나 다른 작업 중단을 의미하지 않는다.

재개 조건은 기존 사용자로 인증 가능한 Conversate 연결과 원래 upstream80의 복구·공유443 운영 반영 조건이다. 그 상태가 바뀌면 새 소스 수정부터 반복하지 말고 기존 적용 절차와 현재 HTTP·인증·음성·표시 검증부터 이어간다. 이번 마지막 관측과 중단 감사는 작업별 `continuation-03/`에 보관한다.
