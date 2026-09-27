# demo-1 메모리 무결성 탐사와 MacSrc 자동패치 지시서

이 프롬프트의 권위 있는 위치는 `MacSrc` 저장소다. OneDrive 사본은 참고
자료일 뿐이며 소스 또는 패치 흔적의 기본 저장 위치로 사용하지 않는다.

## 역할과 모드

먼저 `$demo1-memory-integrity-autopatch`를 사용해 메모리 체크섬, 오염
컨텍스트, 결정적 표본 추출, 아블레이션 지표, 오토그레이더 탐침 상태를
읽기 전용으로 조사한다.

- 분석, 검토, 목표값, 지시서 요청은 `AUDIT_ONLY`다.
- 사용자가 소스 구현이나 자동패치를 명시하면 현재 root를 확인한다.
- Notebook에서 이 저장소의 소스 수정을 명시한 경우 기본 실행 모드는
  `MACSRC_SMB_DIRECT`다. 사용자가 PatchDrop 또는 로컬 worktree를 별도로
  선택한 경우에만 그 선택을 따른다.

## 접근과 쓰기 경계

읽기, 검색, 웹 조사, 외부 도구, 다른 드라이브의 증거 열람은 자유롭게
허용한다. 이 규칙은 접근 차단 정책이 아니다. 제한 대상은 애플리케이션
소스의 지속적인 쓰기뿐이다.

- 직접 소스 write는 검증된 `\\desktop-m5nov6k\MacSrc` 아래에만 남긴다.
- `Y:\`는 현재 PowerShell `DisplayRoot`가 같은 공유를 가리킬 때만 같은
  root로 인정한다.
- OneDrive나 임의의 로컬 폴더로 소스 패치를 조용히 우회하지 않는다.
- 임시 파일은 운영체제 임시 경로를 사용할 수 있다.
- 비소스 산출물은 사용자가 다른 경로를 명시하면 그 경로에 쓸 수 있다.
- 미래의 새로운 sourceSet은 현재 저장소의 build 또는 source manifest로
  경계를 증명하면 허용한다. 오늘의 Java 경로만 하드코딩하지 않는다.

Git이 `dubious ownership`을 보고해도 전역 `safe.directory`를 변경하지
않는다. `MACSRC_SMB_DIRECT`에서는 파일 preimage 체크섬과 공유 source
lease를 사용한다. Desktop 최종 런타임 증명이 필요한 항목은 Notebook
실행만으로 PASS 처리하지 않는다.

## 첫 탐침

명시적 MacSrc 직접패치 후보를 조사할 때 다음과 같이 실행한다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-memory-integrity-autopatch\scripts\scan_memory_integrity_feedback.ps1 `
  -Root . `
  -SourceWriteMode MacSrcSmbDirect `
  -OutputPath .\data\agent-handoff\memory-integrity\latest.json `
  -Seed 20260729
```

탐침 자체는 항상 읽기 전용이며 `mutationAllowed=false`다. `APPLY`는
직접 수정 권한이 아니라 다음 guard를 시작할 자격만 뜻한다.

## 정확히 세 개의 질의

하나의 EvidenceSnapshot에서 정확히 다음 세 패킷을 만든다.

1. `POSITIVE_QUERY`: 기존 seam과 재사용 가능한 도구, 가장 작은 성공
   경로, 사용자 가치를 찾는다.
2. `NEGATIVE_QUERY`: 소유권, 인과, 개인정보, 재현성, 비용, blast radius,
   false positive와 false negative로 목표를 반증한다.
3. `NEUTRAL_QUERY`: Positive-Negative와 Negative-Positive 순서를 모두
   비교한다. 순서에 따라 verdict가 바뀌거나 점수가 50 미만이거나 hard
   gate가 실패하면 `HOLD`한다.

`tri_query_directive_postprocess.ps1 -Mode Prepare`로 positive와 negative를
서로 다른 컨텍스트에서 병렬 평가하고, 두 packet이 생성된 뒤 neutral을
실행한다. `-Mode Finalize`는 원본 지시서를 덮어쓰지 않고 후보 지시서만
만든다.

## MacSrc 직접 자동패치 루프

**필수 하위 스킬:** `$demo1-macsrc-smb-direct-patch`를 사용한다.

1. scanner의 `writePolicy.macSrcRootMatch=true`,
   `patchAuthorizationEligible=true`, secret hit 0을 확인한다.
2. 활성 sourceSet 또는 call boundary를 현재 저장소 파일로 증명한다.
3. guard `Prepare`로 대상 상대경로, boundary 증거, 가장 좁은 `WatchRoots`,
   preimage SHA-256, lease, repo-local session을 만든다.
4. 실제 수정 직전에 guard `Verify`를 실행한다.
5. 선언한 파일에만 최소 `apply_patch`를 적용한다.
6. focused RED/GREEN 명령을 실행한다.
7. 성공하면 session 디렉터리에 실제 exit code, 명령, session SHA-256,
   target postimage SHA-256을 묶은 verification JSON을 만들고 guard
   `Complete -VerificationEvidenceFile`에 그 상대경로를 넘긴다. 실패하면
   필요한 rollback을 수행한 뒤 `Abort`로 lease를 해제한다. Abort는 선언한
   target뿐 아니라 watched source 전체가 baseline으로 돌아오기 전에는 lease를
   유지한다.
8. scanner를 같은 seed로 다시 실행하고 blocker 또는 failure class가
   바뀌면 다음 자동패치를 중지한다.

세션 흔적은
`data/agent-handoff/macsrc-smb-direct/<runId>/session.json`에 남긴다. 자동
commit, push, deploy, DB, credential, 환경 변수 이름, 공개 API 변경은 별도
승인 없이는 하지 않는다.

## 체크섬과 정량 지표

- SHA-256과 기록된 seed로 재현 가능한 파일 표본을 고른다.
- 경로는 `pathHash`, 내용은 `contentSha256`만 기록한다.
- JSONL ledger에서 `checksumMismatchRate`, `contextContaminationRate`,
  `falsePositiveRate`, `falseNegativeRate`, `desktopErrorRate`,
  `pairedIntegrityContribution`을 계산한다.
- null 지표는 0이 아니라 `evidence_needed`다.
- control과 integrity treatment는 같은 revision, query fixture, provider,
  seed, runtime 조건에서 `pairedRunId`로 비교한다.

## 소스 안전 규칙

- 활성 소스와 선언한 target만 최소 수정한다.
- 최종 RAG 프롬프트는 `PromptBuilder.build(PromptContext)` 경계를 유지한다.
- 모든 LangChain4j 의존성은 `1.0.1`을 유지한다.
- raw query, context, prompt, transcript, 키, 토큰, 헤더, 전체 환경 덤프를
  로그, TraceStore, 보고서에 남기지 않는다.
- count-only secret scan에는 `sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}`와
  `sbp_[A-Za-z0-9_-]{10,}`를 포함하고, 일치 원문은 출력하지 않는다.
- index lock, 변경된 preimage, 다른 source lease, reparse 경로, secret hit,
  검증 실패가 발생하면 즉시 `HOLD`한다.

## 최종 보고

실제 root와 alias 증거, host와 branch 또는 `filesystem-cas`, scanner
verdict와 failure class, 세 질의 결론, 선택한 queue 항목, 수정 파일,
pre/post hash, RED/GREEN 명령과 관찰값, rollback, guard session 경로,
`desktopFinalProof`, 남은 `evidence_needed`를 보고한다.
