# 레거시 긍정 계약 — 비활성 호환성 참조

status=legacy_reference_only
legacyPacketType=SUPPORT_CONTRACT
canonicalReplacement=POSITIVE_QUERY.validatedAssumptions
activeQueryRole=false

이 파일은 이전 입력을 해석하기 위한 참조이며 active query로 실행하거나 packet을 출력하지 않는다. 아래 계약 보존 항목은 canonical positive query의 `validatedAssumptions`와 `minimalVerification`으로 이동했다.

1. live checkout, active sourceSet, 실제 호출 경로, focused test 순서로 확인한다.
2. `PromptBuilder.build(PromptContext)`, LangChain4j `1.0.1`, final verifier `0..1`, deterministic postprocess `1` 계약이 유지되는지 센다.
3. `(requestIdHash, logicalCallOrdinal, attemptOrdinal, evidenceBoundary)` 유일성, ordinal 순서, 최소 256-bit CSPRNG run key의 단일 in-memory 공유와 run envelope, per-digest subject metadata, 해석 가능한 `canonicalizationProfileHash`, full `promptHash/optionsHash/responseHash`를 검증한다. provider count는 boundary row가 아니라 distinct 3-field attempt tuple을 한 번만 센다.
4. `providerAttemptObserved`, `responseObserved`, `providerResponseObserved`, `wireAttemptObserved`가 실제 소유 경계보다 강한 주장을 하지 않는지 truth table로 확인한다.
5. provider-disabled, pre-exchange failure, retry, interleaving, duplicate/malformed row, capacity drop을 같은 입력 RED/GREEN으로 비교한다.
6. 주장은 `claim / evidence_id / positive_evidence / counterexample / coherence / missing_proof`로 기록한다.
7. 소스에서 알 수 없는 외부 사실은 공식 문서만 검색하고 URL과 확인 날짜를 남긴다.
8. `artifactVerdict=APPLY`는 fresh output-equality 증거와 `requiredTokenMatrixId=awx-lineage-required-tokens-v1`의 literal 24-token/hash 재계산이 모두 있어야 한다. 기존 baseline prompt test만으로 runtime 또는 lineage artifact 회귀를 통과했다고 주장하지 않는다.

출력: 지지 가능한 주장 최대 5개, 각 주장에 가장 강한 반례 1개, 최종 `SUPPORT | HOLD`.
