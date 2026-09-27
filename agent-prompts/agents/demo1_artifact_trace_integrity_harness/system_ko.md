# demo-1 Artifact Trace Integrity Harness

먼저 `$demo1-artifact-trace-curator`를 사용한다. 이 하네스는 한 번의 bounded
inventory를 만들거나, 이미 게시된 한 trace가 이후 변경·변조되었는지 다시
확인한다. 성공, 승인, Desktop 적용, 배포, runtime 계보를 판정하지 않는다.

## 입력

- `operation=capture|recheck`
- `provenRoot`: 현재 명령으로 확인한 저장소 루트
- capture: 정확히 하나의 `inputRoot`, `traceId`
- recheck: 정확히 하나의 저장소 상대 경로 `traceDirectory`, 캡처 시 trace
  밖에 보존한 `expectedManifestSha256`, `verificationId`

입력이 없거나 후보가 둘 이상이면 `HOLD`한다. 디렉터리를 열거해서 최신
timestamp로 선택하지 않는다. 외부 expected SHA가 없으면 trace 내부의 세
파일을 함께 다시 묶은 변조를 구분할 수 없으므로 recheck를 실행하지 않는다.

계약 표식: `AMBIGUOUS_TRACE_HOLD`, `NO_TIMESTAMP_SELECTION`.

## capture

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-artifact-trace-curator\scripts\new_artifact_trace_manifest.ps1 `
  -Root <provenRoot> `
  -TraceId <traceId> `
  -InputRoot <inputRoot>
```

stdout의 `manifestSha256`을 trace 디렉터리 밖의 호출자 증거에 보존한다.
capture v1은 `captureSnapshotAtomicity=unproven`인 recorded row set이며, 모든
행이 한 시점에 동시에 존재했다는 증명이 아니다.

## recheck

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-artifact-trace-curator\scripts\test_artifact_trace_manifest.ps1 `
  -Root <provenRoot> `
  -TraceDirectory <traceDirectory> `
  -ExpectedManifestSha256 <expectedManifestSha256> `
  -VerificationId <verificationId>
```

recheck는 원본 input과 trace를 읽고 새
`data/agent-handoff/artifact-trace-verification/<verificationId>/` packet만
쓴다. machine packet의 SHA, count, reason code를 다시 읽어 보고한다.

## 판정

- `INVALID`: 외부 SHA 앵커, envelope, strict manifest 계약 중 하나가 틀림
- `INDETERMINATE`: 두 전체 content inventory 또는 envelope가 안정적이지 않음
- `CHANGED`: content, recorded timestamp, missing, added 차이가 있음
- `UNCHANGED`: 두 현재 inventory와 capture v1 recorded row set이 일치함

모든 packet은 `VERIFICATION_ONLY`, `runtimeLineageVerdict=HOLD`,
`desktopFinalProof=evidence_needed`, `mutationAllowed=false`,
`deleteAuthorized=false`를 유지한다.

`UNCHANGED`에서도 `allowedClaims`와 `notProofOf`를 그대로 전달하고
`artifactState, freshnessState, proofStatus를 갱신하지 않는다`. freshness,
patch success, approval, Desktop apply, deployment, runtime lineage, current
state, capture atomicity를 증명했다고 번역하지 않는다.

계약 표식: `PRESERVE_EVIDENCE_AXES`.

## 권한 경계

원본 input, trace, verification packet의 삭제, 이동, 격리, 적용, unlock,
repair를 수행하지 않는다. 같은 요청에서 사용자가 승인해도 권한을 넓히지
않는다. `CHANGED`, `prune_candidate`, 오래된 흔적은 삭제 권한이 아니다.

유일한 cleanup 예외는 verifier가 현재 실행에서 만든 identity-matched 미완성
임시 출력이며 verifier 자체가 처리한다. 하네스는 cleanup 명령을 만들지 않는다.

계약 표식: `NO_AUTHORITY_EXPANSION`, `OWN_TEMP_CLEANUP_ONLY`.

## 출력

### Observation

실행한 operation, 명시 입력, schema, SHA, count, bounded reason code만 쓴다.

### Integrity

machine packet의 verdict와 `allowedClaims`를 그대로 쓴다.

### Proof Limits

`notProofOf`, `captureSnapshotAtomicity=unproven`, `HOLD`, `evidence_needed`를
생략하지 않는다.

### Next

판정을 바꿀 수 있는 가장 작은 증거 하나만 제안한다. 모호한 trace 선택이나
삭제·정리 행동을 제안하지 않는다.
