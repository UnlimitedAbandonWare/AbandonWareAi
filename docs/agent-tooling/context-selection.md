# 작업별 로컬 문맥 선별

기존 `scripts/gptpro_pack_context.py`의 `select` 명령은 checkpoint의 작업 계약과
명시한 파일·줄 구간만 JSON 근거 묶음으로 제공합니다. 기존 GPT Pro ZIP 프로필과
`build_all()` 호출 동작은 유지합니다. 이 명령은 원본 파일을 수정하지 않으며
저장소 전체 검색, 서버 조회, 개인 세션 수집, 외부 모델 호출을 하지 않습니다.

## 입력과 실행

작업 원장의 기존 `state.md`를 사용합니다. `continuity:` 한 줄은
`scripts/checkpoint_doctor.py`가 검증하는 `awx.task-continuity.v1` 계약입니다.
현재 사용자의 지시를 독립적으로 확인한 뒤 `--latest-instruction-ref`를 지정하세요.
그 값과 저장된 계약의 `instructionRef`가 다르면 명령은 실패합니다.
상태는 조회만 하며 paused/cancelled를 active로 바꾸거나 새 권한으로 해석하지 않습니다.

후보 manifest는 저장소 안의 명시적인 JSON 파일입니다. 예:

```json
{
  "allowed": [
    "scripts/gptpro_pack_context.py",
    "scripts/test_gptpro_context_selection.py"
  ],
  "query": "local context selection pinned contract source drift",
  "maxBytes": 65536,
  "candidates": [
    {
      "path": "scripts/gptpro_pack_context.py",
      "version": "current-worktree",
      "role": "current"
    },
    {
      "path": "scripts/test_gptpro_context_selection.py",
      "role": "acceptance"
    }
  ]
}
```

```powershell
python -B scripts/gptpro_pack_context.py select --root . `
  --state data/agent-handoff/codex-autonomy/<taskId>/state.md `
  --manifest data/agent-handoff/codex-autonomy/<taskId>/selection.json `
  --latest-instruction-ref <confirmedCurrentRef>
```

출력은 UTF-8 JSON stdout입니다. 리디렉션으로 파생 파일을 저장할 수 있지만
원본이나 checkpoint 경로에 덮어쓰지 마세요. 명령 자체에는 파일 쓰기가 없습니다.
코드 구간이 필요하면 후보에 1부터 시작하는 `start`, `end`를 넣으세요.
호출자가 완전한 함수·문서 절·코드 블록 경계를 선택해야 합니다. 선별기는 그 구간을
추가로 자르지 않습니다. JSON 자료는 전체 레코드만 허용합니다.

후보의 선택적 `sha256`은 기대한 원문 버전과 실제 바이트가 같은지 확인합니다.
`version`과 `source`는 사람이 붙인 출처 표기이며 최신성이나 독립 근거의 증명이
아닙니다. 최신 사실과 과거 문서는 서로 다른 파일·해시·버전으로 명시하세요.

## 보존·예산·읽기 경계

- 계약 전체, 미완료 항목, 전달 경로, stop/resume 상태, NOT_RUN을 먼저 고정합니다.
  `required: true`, 또는 `role: current|acceptance|counterevidence|failure`인
  구간은 점수와 관계없이 반드시 보존합니다.
- 나머지는 질의와 파일명·발췌의 단어 일치로 정렬합니다. 의미 모델이나 진실 확률로
  표현하지 않습니다. 동일 발췌는 한 번 담고, 별칭의 경로·버전·역할·해시를
  `excluded`의 `duplicateOf`로 남깁니다. 같은 경로·구간을 중복 선언하면 거부합니다.
- `selected`에는 경로, 원문 줄 범위, 원문/발췌 SHA-256, 수정 시각, 버전, 선택 이유와
  발췌가 있습니다. `excluded`에는 제외 사유와 다시 읽을 좌표가 있습니다.
  없는 선택적 파일은 `requery`에 남기며, 필수 파일이 없으면 실패합니다.
- 최종 JSON 전체 바이트가 `maxBytes` 안에 있어야 합니다. 필수 항목이나 계약·출처
  메타데이터가 넘으면 `HOLD: mandatory-budget-exceeded`, exit 2입니다.
  선택적 발췌와 코드 맵은 전체 단위로 제외하며 그 상태를 표시합니다.
  UTF-8 바이트 절단으로 코드를 줄이거나 JSON을 깨뜨리지 않습니다.
- `approxTokens`는 기존 `len(text)//4` 휴리스틱입니다. 한국어·코드의 정확한
  모델 토큰 수나 token budget 보장이 아닙니다. 실제 제한은 UTF-8 바이트입니다.
- 후보는 allowlist에 있는 정확한 상대 파일 경로만 허용합니다. 최대 allowlist 64개,
  후보 128개, 파일당 2 MiB, 총 원문 16 MiB입니다. 모든 원문·control 입력·마지막
  무결성 확인은 상한보다 한 바이트만 더 읽어 초과를 판정합니다.
- 경로 탈출, 링크/reparse/hardlink, 비밀·개인 세션·실행 로그·빌드 디렉터리를
  거부합니다. 실제 설정 파일은 읽지 않으며 공개 example/sample/template 설정만
  명시적으로 허용합니다. 내용을 기존 checkpoint secret scanner로 검사합니다.
- 선택 모드의 `build_code_map`은 검증한 원문 스냅샷을 재사용합니다. 설정 기본값
  수집과 추가 디렉터리 탐색을 하지 않습니다. 마지막에 원문을 다시 비교해 변경을
  감지합니다. 원문과 출처가 맞지 않는 팩을 성공으로 내보내지 않습니다.
- `cacheKey`는 계약, 질의, 원문 해시·좌표·버전, 규칙 버전, allowlist, 예산에
  묶입니다. 매번 다시 읽고 계산하며 별도 캐시나 상태 원장을 만들지 않습니다.

## 선택적 평가와 Codex 연결

기본 외부 호출은 0입니다. `comparison`은 이미 확보한 **오프라인** 평가 결과의
선택적 순서일 뿐입니다. `status: ok`, 같은 `cacheKey`, 모든 선택적 후보 ID를
한 번씩 포함한 `rankedIds`가 있어야 적용합니다. 필수 항목을 제외하거나 계약을
바꾸지 않습니다. timeout/401/403/429/빈 결과/잘못된 schema/낡은 키는
`local-fallback`으로 기록합니다. 이 명령은 JEV endpoint·인증·요금을 추정하거나
외부 API를 호출하지 않습니다. 실제 JEV 연결은 현재 provider 계약과 승인된
비민감 입력, 호출 상한, 효과가 확인된 별도 단계입니다.

훅은 설치하지 않습니다. 먼저 위 수동 명령으로 검증합니다. 공식 Codex 문서에서
PreCompact의 일반 stdout은 무시되고, SessionStart(compact)의
`hookSpecificOutput.additionalContext`는 추가 개발자 맥락으로 전달됩니다.
설치판 지원·신뢰·1회 재주입·실제 효과를 확인하기 전에는 자동 연결을 완료로
표현하지 않습니다. 공식 계약 확인일: 2026-10-08, 실행 CLI: 0.144.1.
[PreCompact](https://learn.chatgpt.com/docs/hooks#precompact),
[SessionStart](https://learn.chatgpt.com/docs/hooks#sessionstart).

## 검증

```powershell
python -B scripts/test_gptpro_context_selection.py
python -B scripts/test_gptpro_pack.py
python -B scripts/test_gptpro_pack_evidence.py
```

합성 검사는 필수 계약, 현재/과거 버전, 불리한 근거, 원문 좌표·해시, UTF-8/JSON,
예산 초과, 중복, 입력 변경, 선별 범위, 설정 값 배제, bounded read, 원문 변경,
오프라인 평가 장애를 검증합니다. 팩이 작아졌다는 사실만으로 모델 응답 속도나
수정 품질 향상을 선언하지 않습니다. 같은 작업·모델·추론 설정의 실제 비교는
별도 측정 결과가 필요합니다.
