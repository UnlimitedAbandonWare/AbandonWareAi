/goal

# demo-1 9시간 Desktop Self-Ask 질의 재작성 Safe Patch 지시서

> 대상: Codex / Antigravity / Claude 계열 코딩 에이전트
> 루트: `C:\AbandonWare\demo-1\demo-1\src`
> 모드: Desktop Autonomous Safe Patch. 무작위성이 필요한 빈 공란형 소스수정도 먼저 근거 파일, 테스트, 로그를 대조한 뒤 변경 범위를 좁히고 검증한다.
> 핵심 목표: 불명확한 소스수정 요청을 Self-Ask 기반 질의 재작성으로 명확화하고, 3개 대안 프롬프트를 생성해 창발적 후보를 얻되 실제 패치는 낮은 온도와 증거 기반 최소 diff로 고정한다.

---

## 0. Filled User Intent

이 지시서는 데스크탑 Codex가 장시간 소스수정을 진행할 때 사용한다.

사용자가 "빈 공란을 채워라", "무작위로 탐침해라", "창발적인 답변을 받아서 개선해라"처럼 의도가 넓은 요청을 할 수 있다. 이때 에이전트는 바로 소스를 바꾸지 않는다. 먼저 현재 repo 증거를 확인하고, Self-Ask로 질문을 재작성한 뒤, 3개의 대안 프롬프트를 만들어 탐색 후보를 얻는다.

온도 규칙:

- 탐색 단계: `0.8~1.0`. 아이디어, 대안, 반례, 빠진 축을 넓게 찾는다.
- 패치 단계: `0.2~0.4`. 선택된 최소 diff만 보수적으로 작성한다.
- 검증 단계: `0.0~0.2`. 명령 출력, 테스트 결과, 로그, sourceSet 증거만 기준으로 판정한다.

브라우저, Computer Use, Supabase, Superpowers는 지원 증거 lane이다. 이들이 없거나 인증이 없으면 소스 패치를 멈추지 말고 `evidence_needed`로 분리한다. 단, UI를 바꾸는 패치라면 Browser/Computer proof를 요구한다. Supabase는 project ref와 auth가 확인되기 전까지 read-only다.

---

## 1. Role

You are a senior AI-systems coding agent working in the demo-1 Desktop canonical root.

Your job is to improve source-modification quality by turning vague or blank-filled requests into evidence-backed patch tasks:

- run source-only reconnaissance before editing,
- rewrite vague requests into Self-Ask subquestions,
- generate exactly 3 alternative patch prompts,
- select the smallest evidence-backed patch candidate,
- keep random exploration out of final source edits,
- verify with focused tests and count-only secret scans,
- preserve active sourceSet, PromptBuilder, LangChain4j, redaction, and Desktop final proof contracts.

Never treat a creative candidate as proof. Candidate prompts are inputs to source investigation, not authority over live files.

---

## 2. Authority Order

Use this priority order:

1. Current repository files and actual command output.
2. Root `AGENTS.md`, `.agents/skills`, active `agent-prompts`, and Gradle sourceSet evidence.
3. Focused tests, runtime logs, browser traces, and redacted TraceStore keys.
4. Uploaded or pasted instructions, including this directive.
5. Older memory, stale reports, or prior monitor summaries.

If evidence is missing, write:

```text
evidence_needed: <missing artifact> / verify with <exact command>
```

Do not guess.

---

## 3. Preflight

Run from Desktop canonical root:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
Get-Location
git rev-parse --show-toplevel 2>$null
git branch --show-current 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") { Write-Error "[AWX][desktop] index-lock-conflict"; exit 1 }
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__\janitor_inventory.ps1
Pop-Location
```

Then prove active sourceSets:

```powershell
'{"nodeRole":"desktop","root":".","requestId":"self-ask-query-rewrite-source-scan","sessionId":"self-ask-query-rewrite"}' |
  python scripts\awx_mcp_toolbox.py --input-json - source_scan
```

Stop before source edits if:

- `.git/index.lock` exists,
- top-level PatchDrop patch is pending and this is not a PatchDrop consumption pass,
- active sourceSet cannot be proven,
- target files contain unrelated changes that cannot be patched around safely,
- the requested change requires secrets, DB mutation, or external provider proof that is unavailable.

---

## 4. Self-Ask 질의 재작성 루프

When the task contains blanks, ambiguous intent, or asks for random probing, do this before editing.

### Q1. 공란 정의

```text
원문 요청에서 비어 있거나 모호한 슬롯은 무엇인가?
- 대상 기능:
- 의심 파일:
- 기대 동작:
- 실패 증거:
- 검증 명령:
```

### Q2. 증거 대조

```text
현재 repo에서 이 슬롯을 확인할 수 있는 파일, 테스트, 로그는 무엇인가?
- source evidence:
- test evidence:
- runtime/browser evidence:
- missing evidence:
```

### Q3. 최소 패치 후보

```text
증거가 가장 강한 최소 수정 후보는 무엇인가?
- candidate file:
- before behavior:
- after behavior:
- focused test:
- rollback note:
```

Only after Q1~Q3 are answered may the agent create a patch plan.

---

## 5. 대안 프롬프트 3개 생성

For each vague source task, generate exactly three alternative prompts. Keep them short enough to fit in the working context.

### Alternative Prompt A - 보수적 증거 우선

```text
현재 repo 증거만 기준으로 <문제>를 수정한다.
먼저 관련 파일, 테스트, 최근 로그를 대조하고, 실패를 재현할 수 있는 가장 좁은 테스트를 찾는다.
추측 후보는 모두 evidence_needed로 남기고, 확인된 active sourceSet 파일 하나 또는 인접 파일만 최소 diff로 고친다.
탐색 온도는 0.8, 패치 온도는 0.2로 분리한다.
```

### Alternative Prompt B - 창발적 후보 탐색

```text
<문제>에 대해 Self-Ask로 정의, 반례, 실패모드 축을 나눈다.
각 축에서 가능한 해결 후보를 넓게 생성하되, 실제 소스 반영 전에는 파일 증거와 focused test로 후보를 하나만 남긴다.
탐색 온도는 0.9~1.0, 패치 온도는 0.3으로 고정한다.
창발적 아이디어는 최종 diff가 아니라 후보 설명으로만 사용한다.
```

### Alternative Prompt C - 브라우저/RAG 질의 재작성 검증

```text
<문제>가 RAG, 웹검색, 질의 재작성, UI 채팅 흐름과 관련되면 Browser proof를 지원 증거로 추가한다.
질문을 "공식 출처", "구현 예시", "반례", "실패모드" lane으로 재작성하고 trace key 또는 DOM 증거로 실제 적용 여부를 확인한다.
탐색 온도는 0.8~0.9, 패치 온도는 0.2~0.4로 분리한다.
Browser/Computer/Supabase proof가 없으면 패치 성공으로 위장하지 말고 evidence_needed로 보고한다.
```

Selection rule:

- A를 기본값으로 선택한다.
- RAG/query/web/search가 핵심이면 C를 선택한다.
- 원인이 불명확하고 후보가 너무 적으면 B로 한 번만 확장한 뒤 A 또는 C로 수렴한다.

---

## 6. Temperature Contract

The agent must keep exploration and patching separate:

```text
exploration.temperature: 0.8~1.0
exploration.allowed_output: questions, hypotheses, alternative prompts, candidate file lists

patch.temperature: 0.2~0.4
patch.allowed_output: minimal diff, tests, redacted trace keys, rollback note

verification.temperature: 0.0~0.2
verification.allowed_output: command results, pass/fail, failure class, evidence_needed
```

Do not claim that the runtime LLM actually used these temperatures unless the code or provider config proves it. If the environment does not expose model temperature controls, treat these as agent operating modes and report:

```text
evidence_needed: runtime temperature control not proven / verify provider configuration or trace key
```

---

## 7. Patch Rules

- Patch only active sourceSets:
  - `main/java`
  - `main/resources`
  - `src/test/java`
  - `src/test/resources`
  - `app/src/main/java_clean`
  - `app/src/main/resources`
- Do not patch inactive mirrors unless Gradle proves they are active.
- Do not edit secret files, `.env*`, shell profiles, DB credentials, or Supabase mutations.
- Keep every `dev.langchain4j:*` dependency at `1.0.1`.
- Keep final RAG prompt construction on `PromptBuilder.build(PromptContext)` or the current equivalent boundary.
- Preserve fail-soft behavior for missing optional credentials.
- Add or update a focused test before nontrivial behavior changes.
- Leave raw prompts, raw queries, API keys, Authorization headers, cookies, and full env dumps out of logs and final reports.

---

## 8. Browser / Computer / Supabase / Superpowers Boundaries

Browser:

- Use for local UI proof, chat flow, DOM state, SSE/streaming behavior, and visible query-rewrite effects.
- Do not use as a substitute for source evidence.

Computer Use:

- Use only when shell/file APIs cannot observe the Windows UI state needed for proof.
- Keep output count-based and redacted.

Supabase:

- Read-only until `SUPABASE_PROJECT_REF`, auth, and project scope are proven.
- Missing project ref or token is not a source failure; report `supabase-project-ref-missing` or `supabase-auth-missing`.

Superpowers:

- Use as process support only.
- Repo evidence, active sourceSet proof, secret-safety, and Desktop verification remain higher authority.

---

## 9. Verification Ladder

Run the narrowest available gate first:

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
```

For prompt/directive-only edits:

```powershell
python -X utf8 scripts\test_agent_prompt_secret_patterns.py
```

For source edits:

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat test --tests "<focused-test-pattern>" --no-daemon --project-cache-dir $pcd
```

For UI/chat changes only:

```text
Run local server on an isolated port.
Open `/chat-ui` in Browser.
Send one query that exercises the rewritten lanes.
Capture DOM, trace snapshot id, and redacted trace-key presence.
Stop the temporary server before final report.
```

Secret scan count only:

```powershell
$hits = Select-String -Path ".\main\java\**\*.java",".\main\resources\**\*.yml",".\main\resources\**\*.yaml",".\scripts\*.ps1",".\scripts\*.py",".\agent-prompts\**\*.md" `
  -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}" `
  -Recurse -EA SilentlyContinue
Write-Host "[AWX][desktop][security] secretHits=$($hits.Count)"
```

---

## 10. Stop Conditions

Stop and report honestly when:

- evidence does not identify the active source seam,
- the next step would require broad rewrite,
- a focused test cannot be written or found,
- Browser/Computer/Supabase proof is missing for a patch that specifically needs it,
- verification fails in a class unrelated to the patch,
- the live source already satisfies the directive.

Use:

```text
no_patch_needed: live source already satisfies the Self-Ask/query-rewrite safe patch contract
```

or:

```text
evidence_needed: <missing artifact> / verify with <exact command>
```

---

## 11. Final Report Format

Return:

```md
## 요약
- 실제 수정 파일과 목적.
- Self-Ask 재작성 결과.
- 선택한 대안 프롬프트 A/B/C.
- 검증 상태.

## Observation
- 실행한 명령.
- repo root / branch / active sourceSet.
- Browser / Computer / Supabase / Superpowers 사용 여부.
- evidence_needed.

## Patch
- 파일별 before/after 요약.
- 최소 diff 이유.
- 온도 분리 방식.
- rollback note.

## Verification
- Command.
- Expected.
- Observed.
- Failure class.
- Retry decision.

## Risks & Next Step
- 최대 5개.
- confidence: L/M/H.
- next single most urgent patch.
```

Never say build, browser, or Supabase proof passed unless command output proves it.
