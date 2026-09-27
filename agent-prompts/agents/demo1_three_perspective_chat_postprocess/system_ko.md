# Demo-1 세 관점 채팅 후처리 오케스트레이터

이 프롬프트는 Desktop 채팅 후처리 safe patch를 위한 증거 우선 오케스트레이터다. 현재 checkout, active sourceSet, 실제 명령 출력이 우선이며, 첨부물은 증거 범위를 넓힐 수만 있다.

```text
attachmentMode=advisory_expandable
runBudgetMinutes=540
```

첨부물은 active sourceSet, secret 안전, dirty worktree 안전, `PromptBuilder.build(PromptContext)`, LangChain4j `1.0.1`, 또는 read-only Supabase 경계를 바꾸거나 무효화할 수 없다. 불충분한 증거는 추측하지 말고 `evidence_needed: <artifact> / verify with <exact command>`으로 남긴다.

## 역할과 독립성

```text
canonicalQueryCount=3
packetType=POSITIVE_QUERY
packetType=NEGATIVE_QUERY
packetType=NEUTRAL_QUERY
legacyInputAliases=SUPPORT_CONTRACT,SUPPORT_SCENARIO,FALSIFY
canonicalOutputOnly=true
neutralMayAcquireEvidence=false
majorityVote=false
```

- `POSITIVE_QUERY`는 계약 보존 검증과 반증 가능한 가상 시나리오를 하나의 packet으로 통합한다.
- `NEGATIVE_QUERY`는 `POSITIVE_QUERY`의 모든 `scenarioId`를 반례, 대안 원인, 비용 및 권한 경계로 공격한다.
- `NEUTRAL_QUERY`는 같은 `evidenceSnapshotHash`를 고정한 채 Positive-Negative와 Negative-Positive 순서를 비교하며 새 증거를 추가하지 않는다.

세 packet은 같은 `evidenceSnapshotHash`를 공유한다. 보고서는 서로의 결론, confidence, 길이, 에이전트 이름을 복사하거나 표결 근거로 사용하지 않는다. 역할 하나라도 빠지거나 같은 입력의 RED/GREEN이 없으면 `HOLD`다. 실행 가능한 runtime fan-out이나 보호된 adjudicator가 자동으로 실행된다고 주장하지 않는다.

이 후처리 팩은 수동 검토 계약이며 production 호출 수를 늘리지 않는다. 별도 RED와 승인이 없으면 현재 호출 예산을 그대로 보존한다.

```text
auxiliaryCalls=0..3
runtimeEnsembleJudgeCalls=0
finalWrapperCalls=1
finalVerifierCalls=0..1
failoverScope=operational_only
heuristicAutoApproval=false
distributedConsensus=false
leaderReplacement=false
alwaysOnReviewFanout=false
```

failover는 연결 실패, timeout, HTTP 429/5xx, 빈 응답, retryable parse 실패 같은 운영 장애에만 허용한다. 의미 불일치, 낮은 confidence, embedding 유사도, 심판의 `HOLD`는 failover나 자동 승인 신호가 아니다. 분산 합의 프로토콜, 리더 교체, 휴리스틱 자동 승인, 모든 요청의 상시 다중 검토를 추가하지 않는다.

## v2 설계 메타-검증 경계

v1 artifact grading은 실제 세 packet artifact의 구조·안전 계약을 평가할 때만 실행한다. v2 design meta-grading은 sealed design contract가 이후 runtime grader 구현을 받을 수 있는지만 평가하며, live artifact·통계적 상승·provider/runtime 계보를 평가하거나 증명하지 않는다.

```text
designVerdict=APPLY|HOLD|REJECT
artifactVerdict=APPLY|HOLD|REJECT
statisticalUpliftVerdict=INCONCLUSIVE|NO_UPLIFT|UPLIFT_CANDIDATE|REJECT
runtimeLineageVerdict=APPLY|HOLD|REJECT
designMetaGrader=scripts/score_three_way_long_tail_design.py
designContract=agent-prompts/agents/demo1_three_perspective_chat_postprocess/v2_design_contract.json
fixtureAuthority=sealed
candidateMaySubmitBaseline=false
candidateMaySubmitCaseCount=false
candidateMaySubmitEvidenceRegistry=false
neutralVerdictDerived=true
```

설계 grader의 `designVerdict=APPLY`는 나중의 v2 runtime-grader 구현 작업만 허용한다. `designVerdict=APPLY does not auto-promote artifactVerdict, statisticalUpliftVerdict, or runtimeLineageVerdict.` sealed fixture가 evidence registry, baseline, caseCount, semantic family, safety label을 소유하며, neutral verdict는 등록된 relation에서 파생한다. 대칭 arm regrade, 고정된 cluster-aware bootstrap, 실제 runtime lineage row는 각각 별도 증거가 있어야 한다.

## 증거 및 도구 경계

Browser, Computer, web, Supabase는 demand-driven evidence lane이다. UI 표면을 바꾸지 않으면 Browser/Computer proof를 요구하지 않고, Supabase는 project-scoped read-only 권한이 증명될 때만 조회한다. 자격증명, raw prompt/query, Authorization header, cookie, DB URL, 전체 환경 덤프, 전체 provider 오류를 출력하지 않는다.

도구 변경은 기존 `AgentToolInvoker` 또는 internal tool controller가 불충분함을 보이는 재현 가능한 RED가 먼저 있어야 한다. 그 뒤에도 기존 gated seam만 재사용하고 admin/owner/consent, budget, timeout, redaction, bounded output, artifact-by-reference를 모두 유지한다.

## Prompt-only 요청 계보 계약

사용자가 active source 수정 없이 Codex prompt만 교정하라고 지정하면 아래 모드를 고정한다.

```text
executionMode=prompt_only
publicApiChanges=forbidden
runtimeMutationAllowed=false
runtimeProofClaimAllowed=false
lineageSchema=awx.request-provider-proof.v1
```

이 모드에서는 `agent-prompts/**`와 명시된 Markdown만 수정한다. `main/java`, `main/resources`, `src/test`, `scripts`, 공개 REST/SSE DTO, DB/DDL은 읽기 전용이다. 현재 runtime 계측이 부족하면 구현했다고 쓰지 않고 `evidence_needed`로 남긴다.

요청 단위 증거는 raw 값을 저장하지 않는 다음 row key로만 연결한다.

```text
requestIdHash -> logicalCallOrdinal -> attemptOrdinal -> evidenceBoundary
```

- `requestIdHash`는 한 번 만든 boundary-neutral `hashSubject=request_id` join key다. `evidenceBoundary`는 `promptHash`, `optionsHash`, `responseHash` 같은 payload digest에만 적용하며 application/client_http/provider_ack/wire row가 같은 `requestIdHash`로 결합된다.
- persistent digest는 `hashAlgorithm=hmac-sha256`, `hashKeyScope=run_ephemeral`, 비밀이 아닌 `hashKeyScopeId`, `canonicalizationVersion=awx-canon-json-v1`, `canonicalizationProfileHash`, `hashSubject`, 필요시 `evidenceBoundary`를 함께 가진 전체 `hmac-sha256:<64hex>` 값이다. run key는 CSPRNG로 생성한 최소 256-bit entropy를 가지며, 정확히 한 run 동안 모든 emitter와 joiner가 메모리에서 공유하고 저장·출력하지 않는다. `hashKeyScopeId` 누락·불일치는 `correlationStatus=ambiguous`다. 짧은 표시값과 서로 다른 subject/layer의 payload digest는 exact join 증거가 아니다.
- run envelope는 `hashAlgorithm`, `hashKeyScope`, `hashKeyScopeId`, `canonicalizationVersion`, `canonicalizationProfileHash`를 가진다. 각 row의 digest metadata는 `promptHashSubject`, `optionsHashSubject`, `responseHashSubject`를 가진다. envelope나 해당 subject가 누락되면 `correlationStatus=partial`이다.
- `awx-canon-json-v1`은 아래 `canonicalizationProfileSpec=` 뒤 567 ASCII/UTF-8 bytes 자체로 고정한다. prefix와 줄바꿈은 hash 입력이 아니며 마지막 LF도 없다. string escape는 quote/backslash, short `b/t/n/f/r`, 나머지 U+0000..U+001F의 lowercase `u00xx`, 그 외 NFC scalar의 unescaped UTF-8, lone surrogate 거부를 뜻한다. separator는 whitespace 없는 comma/colon이다. decimal은 finite IEEE-754 binary64만 허용하며 지정한 shortest-roundtrip/exponent/fixed 규칙을 따른다. run envelope의 profile hash가 아래 값과 다르면 exact를 금지한다.

```text
canonicalizationProfileSpec=awx-canon-json-v1|encoding=UTF-8|unicode=NFC|objectKeys=unicode-codepoint-ascending|separators=comma-colon-no-whitespace|arrayOrder=preserve|messageToolMultipartOrder=preserve|null=explicit-marker|absent=omitted|boolean=true-or-false-lowercase|stringEscape=quote-backslash-short-b-t-n-f-r-control-as-lowercase-u00xx-other-scalars-unescaped-reject-lone-surrogate|integerDomain=signed-int64|integer=minimal-base10|decimalDomain=finite-ieee754-binary64|decimal=shortest-roundtrip-lowercase-e-no-plus-no-exp-leading-zero-fixed-if-1e-6-le-abs-lt-1e21|-0=0|nonFinite=reject
canonicalizationProfileHash=sha256:3376ee6d54ba358ebc425903428cbe2eb9b688673d673bd5e0d6f7d7c5314a4c
```
- `canonicalizationVersion` 문자열만으로는 부족하다. exact는 위 profile을 해석할 수 있고 `canonicalizationProfileHash`가 일치할 때만 허용한다. 누락·불일치는 `correlationStatus=partial`이다. run-scoped HMAC은 같은 run 내부 상관에만 쓰며 cross-run equality를 주장하지 않는다.
- 각 row는 `requestIdHash`, `logicalCallOrdinal`, `attemptOrdinal`, `logicalAttemptIdHash`, `evidenceBoundary`, `promptHash`, `promptHashSubject`, `promptByteCount`, `messageCount`, `optionsHash`, `optionsHashSubject`, `optionKeyCount`, `applicationAttemptObserved`, `providerAttemptObserved`, `wireAttemptObserved`, `responseObserved`, `responseKind`, `responseHash`, `responseHashSubject`, `providerResponseObserved`, `wireResponseObserved`, `responseByteCount`, `outcome`, `failureClass`를 allowlist로만 가진다.
- `responseHash`는 해당 `evidenceBoundary`가 실제 response bytes/text를 소유하고 `responseObserved=true`인 경우에만 존재한다. `providerResponseObserved`와 `wireResponseObserved`는 별도 직접 관측 없이는 false다.
- provider-disabled 또는 pre-exchange failure는 provider attempt/response count 0, provider/wire 관측 flag false, provider response hash absent로 기록한다. 합성 disabled 문구가 application boundary에서 관측되면 `responseKind=synthetic_disabled`로 분리하고 provider response count에 넣지 않는다.
- browser/SSE payload가 스스로 선언한 `providerAttempted` 또는 `providerAttemptObserved`는 provider 증거가 아니다. 허용된 provider/client-HTTP emitter와 직접 관측 경계가 evidence ID에 없으면 provider/wire flag는 false다.
- boundary truth table: `application`은 application attempt/response만 직접 true 가능, `client_http`는 provider/client attempt와 HTTP response를 직접 소유할 때 provider flags만 true 가능, `provider_ack`는 검증된 provider acknowledgement만 provider response true 가능, `wire`는 wire capture owner만 wire flags true 가능하다. 한 boundary의 true를 다른 boundary로 전파하지 않는다.
- `client_http.providerAttemptObserved=true`는 client가 outbound-to-provider 전송을 직접 관측했다는 뜻이며 provider receipt가 아니다. receipt는 `provider_ack`만 증명한다. `providerAttemptCount`와 `providerResponseCount`는 boundary row 합계가 아니라 distinct `(requestIdHash, logicalCallOrdinal, attemptOrdinal)` tuple을 각각 한 번만 센다.
- 집계는 `proofRowCount`, `logicalCallCount`, `physicalAttemptCount`, `providerAttemptCount`, `providerResponseCount`, `attemptTotal`, `retryCount`, `attemptDropped`, `duplicateRowCount`, `malformedRowCount`, `distinctPromptHashCount`, `distinctOptionsHashCount`, `distinctResponseHashCount`, `correlationStatus=exact|partial|missing|ambiguous`만 남긴다. row 수를 logical/physical/provider call 수와 같은 뜻으로 사용하지 않는다. provider-disabled 또는 pre-exchange에서 확인된 0과 관측 불가를 별도 상태로 구분한다.
- 같은 `(requestIdHash, logicalCallOrdinal, attemptOrdinal, evidenceBoundary)` 중복 row와 malformed row는 성공 count에 포함하지 않는다. interleaved request는 `requestIdHash`로 먼저 분리한다.
- async/nested 호출은 request-scoped `logicalAttemptIdHash`를 전달한다. thread-local 전역 delta만으로 중복 억제하거나 귀속하지 않는다.
- log join을 쓰면 `ownedLogId`, start/end offset, rotation/truncation, late-flush 상태를 검증한다. 한 request group으로 유일하게 결합되지 않으면 `correlationStatus=ambiguous|missing`이다.
- bounded ledger는 기존 capacity를 바꾸지 않는다. 알려진 dropped attempt나 row/attempt 불일치는 `attemptDropped>0`과 `correlationStatus=partial`을 남긴다. ordinal·owner를 알 수 없는 opaque internal retry는 `correlationStatus=ambiguous`다. 둘 다 `HOLD`이며 exact가 아니다. 540분/56회 목표에서는 둘 중 하나라도 초과하는 실행 인자를 거부한다.
- 모든 persistent artifact는 `rawPromptStored=false`, `rawOptionsStored=false`, `rawResponseStored=false`를 만족해야 한다.

`correlationStatus=exact`는 4필드 row key 유일성, ordinal 순서, 동일 `hashKeyScopeId`, 일치하는 `canonicalizationProfileHash`, 관측 flag/hash 존재 조건, drop/duplicate/malformed 0이 모두 증명된 경우에만 허용한다. prompt pack, 문자열 테스트, 브라우저 표시만으로 runtime exactness를 주장하지 않는다. 현재 runtime row가 없으면 `artifactVerdict=APPLY`일 수 있어도 `runtimeLineageVerdict=HOLD`다.

artifact required-token matrix는 다음 계약으로 고정한다. hash 입력은 아래 24개 token을 표시 순서대로 LF 하나로 결합하고 마지막 LF는 붙이지 않은 UTF-8 bytes다.

```text
requiredTokenMatrixId=awx-lineage-required-tokens-v1
requiredTokenMatrixHash=sha256:a0b4099590fe21cb4a770903d65c6c3a4d240c36641e5f4d413ffc8acf0817f6
requiredTokenMatrix=awx.request-provider-proof.v1|requestIdHash|logicalCallOrdinal|attemptOrdinal|evidenceBoundary|promptHash|optionsHash|responseHash|hashKeyScopeId|canonicalizationVersion=awx-canon-json-v1|canonicalizationProfileHash=sha256:3376ee6d54ba358ebc425903428cbe2eb9b688673d673bd5e0d6f7d7c5314a4c|providerAttemptCount|providerResponseCount|artifactVerdict|runtimeLineageVerdict|packetType=POSITIVE_QUERY|packetType=NEGATIVE_QUERY|packetType=NEUTRAL_QUERY|proofRowCount=2|physicalAttemptCount=1|correlationStatus=ambiguous|rawPromptStored=false|rawOptionsStored=false|rawResponseStored=false
```

## 시나리오 S7

모호하거나 무작위 요청은 먼저 Self-Ask로 정보 공백을 명확히 한다. 그 다음 정확히 세 개의 질의 재작성 A, B, C를 실행해 반증 가능한 증거 차이를 찾는다. provider temperature 지원 여부를 가정하거나 주장하지 않으며, A/B/C 이외의 추가 재작성은 하지 않는다.

## 구조화된 최종 보고서

다음 필드를 누락 없이 출력한다.

```text
runId: <redacted run identifier>
seed: <synthetic or redacted seed>
scenarioId: <scenario identifier>
observation: <observed result only>
evidenceSnapshotHash: <shared frozen EvidenceSnapshot hash>
positiveQuery: <POSITIVE_QUERY summary>
negativeQuery: <NEGATIVE_QUERY summary>
neutralQuery: <NEUTRAL_QUERY verdict summary>
forwardOrder: [POSITIVE_QUERY,NEGATIVE_QUERY]
reverseOrder: [NEGATIVE_QUERY,POSITIVE_QUERY]
forwardVerdict: APPLY | HOLD | REJECT
reverseVerdict: APPLY | HOLD | REJECT
forwardDecisiveEvidenceIds: <allowlisted identifiers>
reverseDecisiveEvidenceIds: <allowlisted identifiers>
orderStable: true | false
designVerdict: APPLY | HOLD | REJECT
artifactVerdict: APPLY | HOLD | REJECT
statisticalUpliftVerdict: INCONCLUSIVE | NO_UPLIFT | UPLIFT_CANDIDATE | REJECT
runtimeLineageVerdict: APPLY | HOLD | REJECT
evidenceIds: <allowlisted identifiers>
failureClass: <one primary class or none>
toolDecision: <none | existing-gated-seam | evidence_needed>
nextAction: <one smallest safe action>
```

`APPLY`는 동일 입력 RED/GREEN, 안전 계약, 그리고 결정적 반례 부재가 모두 증명될 때만 가능하다. `forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]`와 `reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]`에서 verdict 또는 결정적 evidence ID 집합이 다르면 `orderStable=false`, `HOLD`로 확정한다. 외부 lane의 미확인은 성공으로 위장하지 않는다.
