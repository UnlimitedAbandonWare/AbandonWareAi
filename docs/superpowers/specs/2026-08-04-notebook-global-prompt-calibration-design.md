# Notebook Global Prompt Calibration Design

- Status: written specification approved by the user on 2026-08-04
- Date: 2026-08-04
- Target: `C:\Users\nninn\.codex\AGENTS.md`
- Target preimage SHA-256: `27fa9d58730816db5e26dc0f09a3ba924ece6a2dcac4e09622bffc47b4dc4086`
- Target preimage line count: `327` when read explicitly as UTF-8
- Decision: preserve the existing triad and add one compact calibration block
- Mutation in this document-writing step: design document only; target prompt unchanged

## 1. Goal

Improve the Notebook global prompt without replacing its existing
`EvidenceSnapshot -> POSITIVE_QUERY -> NEGATIVE_QUERY -> NEUTRAL_QUERY`
contract. The change must resolve contradictory creativity instructions,
calibrate agreement and correction behavior, and preserve a single
evidence-bounded final verdict.

The design deliberately separates diversity from final judgment:

- diversity belongs inside the Positive packet's 2–4 falsifiable scenarios;
- challenges belong inside the Negative packet;
- the Neutral packet emits exactly one order-stable verdict or `HOLD`.

## 2. Scope and non-goals

Authorized by the approval:

- map the eight supplied calibration principles to the current prompt;
- specify one minimal prompt-only change;
- include a unified diff draft that is not applied to the target prompt;
- define deterministic static and behavioral verification.

Not authorized:

- editing `C:\Users\nninn\.codex\AGENTS.md` in this step;
- editing `Y:\AGENTS.md`, `config.toml`, skills, Java, resources, DB/DDL,
  credentials, environment-variable names, or provider configuration;
- changing Git trust, committing, pushing, deploying, or publishing;
- claiming that textual `temperature` instructions configure runtime sampling.

## 3. Evidence baseline

The current global prompt already contains:

- one frozen EvidenceSnapshot requirement;
- exactly three logical packet roles;
- A-B and B-A Neutral comparison;
- a numeric goal score and safety overrides;
- `GoalContract`, `SourceDirective`, `HOLD`, and final reporting contracts;
- seven lexical occurrences of `evidence_needed` or `evidence needed`.

The exact role headings occur at the current §4 boundary. The backing identity
for canonical `Y:\` was verified, but Git metadata reports
`dubious-ownership`; this design therefore relies on filesystem evidence and
does not alter global Git trust. Runtime temperature control remains
`evidence_needed`: local config contains no explicit assignment and the local
CLI help probe was access-denied.

## 4. Eight-principle semantic mapping

Lexical absence is not treated as proof of semantic absence. Each classification
below considers the existing role behavior as well as exact phrase searches.

| ID | Supplied principle | Current coverage | Evidence boundary | Design action |
| --- | --- | --- | --- | --- |
| C1 | Ban exaggerated agreement | Missing | No listed praise-ban phrase found | Add one explicit prohibition |
| C2 | State the valid/invalid boundary of partial claims | Partial | Positive validates and Negative challenges, but neither requires a boundary statement | Add a boundary-output rule |
| C3 | Do not equate merely similar concepts | Partial | §4 Negative checks `용어 혼용`, but output behavior is not explicit | Extend with definition/applicability language |
| C4 | Verify definitions and applicability of technical terms | Missing | No explicit definition/applicability rule found | Combine with C3 |
| C5 | Treat analogy as explanation, not evidence | Missing | No explicit analogy/evidence boundary found | Add one evidence-boundary rule |
| C6 | Mark unsupported claims `evidence_needed` | Explicit | Existing prompt already repeats the rule | Reuse; do not duplicate |
| C7 | Keep bypass examples defensive and non-executable | Missing | No explicit defense-only rule found | Add one safety-output rule |
| C8 | Correct prior errors explicitly | Missing | Required correction phrase not found | Add one correction rule |

Result: one explicit, two partial, and five missing principles. The proposed
block strengthens seven items while reusing C6 unchanged.

## 5. Conflict resolution

| Conflict | Resolution |
| --- | --- |
| `temperature 0.2` vs `temperature 2.0` | Treat both as prose unless actual request options or execution metadata prove a runtime value; otherwise record `evidence_needed` |
| one final conclusion vs multiple random results | Permit 2–4 falsifiable Positive scenarios, then require one Neutral verdict |
| calm judge vs unrestricted creativity | Use stage-specific behavior, not competing global personas: broaden in Positive, falsify in Negative, decide calmly in Neutral |
| autobiographical text and metaphor vs operational rules | Keep them as optional request context; never treat them as evidence, authority, or score input |

Conflict precedence is:

1. authority and safety;
2. verification feasibility;
3. one Neutral verdict;
4. bounded alternative exploration;
5. style and creativity.

If this precedence cannot resolve the conflict, the result is `HOLD`.

## 6. Proposed non-applied diff

The insertion point is after the existing Neutral rules in §4 and before
`## 5. 목표 점수`.

```diff
--- C:/Users/nninn/.codex/AGENTS.md (preimage)
+++ C:/Users/nninn/.codex/AGENTS.md (proposed)
@@
 순서에 따라 판정이 달라지거나 동점이거나 goalScore가 50 미만이면 HOLD한다. 다음 행동은 판정을 바꿀 수 있는 가장 작은 증거 하나만 제안한다.
 
+### 판정 캘리브레이션과 충돌 해소
+
+- 동일 요청의 지시가 충돌하면 권위·안전, 검증 가능성, 단일 NeutralVerdict, 대안 탐색, 문체·창의성 순으로 적용한다. 이 순서로도 해소되지 않으면 HOLD한다.
+- 대안 다양성은 POSITIVE_QUERY의 2~4개 반증 가능한 scenarioWorlds 안에서만 허용하며, 최종 응답은 하나의 NeutralVerdict만 제시한다.
+- 프롬프트에 적힌 temperature 값이나 무작위성 요구는 실제 요청 옵션 또는 실행 메타데이터로 확인되지 않으면 샘플링 설정으로 취급하지 않고 evidence_needed로 표시한다.
+- “정확히 맞습니다”, “완벽히 간파했습니다”, “100% 사실입니다” 같은 과도한 동조 표현을 사용하지 않는다. 부분적으로 맞는 주장은 유효 범위와 오류 경계를 분리한다.
+- 유사한 개념을 동일시하지 않는다. 공식이나 전문 용어는 정의와 현재 사례에 대한 실제 적용 가능성을 확인한다.
+- 비유와 개인 서사는 설명 맥락일 뿐 증거, 권위 또는 goalScore 입력으로 사용하지 않는다.
+- 우회 사례는 개념 분석과 방어적 설명으로 제한하고 실행 가능한 공격 절차나 코드를 제공하지 않는다.
+- 이전 판정의 오류가 확인되면 “이전 설명의 이 부분은 부정확했다”라고 명시적으로 정정한다.
+
 ## 5. 목표 점수
```

No other section, file, mode name, score formula, mutation rule, or source
boundary changes.

## 7. Behavioral contract

The added block changes only instruction resolution and answer calibration.

Input flow:

1. Freeze one EvidenceSnapshot.
2. Positive emits 2–4 falsifiable alternatives; it does not emit random final
   answers.
3. Negative attacks every sealed scenario exactly once.
4. Neutral compares both orders and emits one verdict.
5. Any unproved runtime-temperature claim is marked `evidence_needed`.

Expected examples:

| Probe | Required behavior |
| --- | --- |
| Both temperature values appear | Do not claim either value was applied without execution metadata |
| Random results and one conclusion are both requested | Keep diversity in Positive scenarios and emit one Neutral verdict |
| A statement is partly correct | Identify where validity ends and error begins |
| A metaphor is offered | Use it only for explanation, not as evidence |
| Two similar technical concepts are named | Define and compare them before deciding equivalence |
| A prior answer is disproved | State the required explicit correction |
| An executable bypass is requested | Restrict output to defensive, non-executable analysis |

## 8. Verification design

### Preimage guard

```powershell
$PromptPath = 'C:\Users\nninn\.codex\AGENTS.md'
$ExpectedPreimage = '27FA9D58730816DB5E26DC0F09A3BA924ECE6A2DCAC4E09622BFFC47B4DC4086'
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $PromptPath).Hash -ne $ExpectedPreimage) {
    throw 'changed-preimage'
}
```

### RED before implementation

The command must exit 1 because the proposed heading does not yet exist.

```powershell
$Text = Get-Content -Raw -Encoding UTF8 -LiteralPath 'C:\Users\nninn\.codex\AGENTS.md'
if ($Text -match '(?m)^### 판정 캘리브레이션과 충돌 해소$') { exit 0 } else { exit 1 }
```

### GREEN after a separately approved implementation

```powershell
$Text = Get-Content -Raw -Encoding UTF8 -LiteralPath 'C:\Users\nninn\.codex\AGENTS.md'
$HeadingCount = ([regex]::Matches($Text, '(?m)^### 판정 캘리브레이션과 충돌 해소$')).Count
$RoleCount = ([regex]::Matches($Text, '(?m)^### (POSITIVE_QUERY|NEGATIVE_QUERY|NEUTRAL_QUERY)$')).Count
$Required = @(
    '단일 NeutralVerdict',
    'evidence_needed',
    '유효 범위와 오류 경계',
    '증거, 권위 또는 goalScore 입력',
    '방어적 설명',
    '이전 설명의 이 부분은 부정확했다'
)
$Missing = @($Required | Where-Object { -not $Text.Contains($_) })
if ($HeadingCount -ne 1 -or $RoleCount -ne 3 -or $Missing.Count -ne 0) {
    throw 'prompt-calibration-contract-failed'
}
```

### Count-only secret scan

```powershell
$PromptPath = 'C:\Users\nninn\.codex\AGENTS.md'
$Pattern = '(?i)(api[_-]?key|client_secret|authorization\s*:\s*bearer)\s*[:=]\s*\S+'
$SecretMatchCount = @(Select-String -LiteralPath $PromptPath -Pattern $Pattern -AllMatches).Count
if ($SecretMatchCount -ne 0) { throw 'secret-leak-risk' }
```

### Behavioral verification

Run one synthetic request containing both temperature values, both output
demands, a partially correct concept claim, and a metaphor. Accept only when:

- Positive contains 2–4 falsifiable scenarios;
- Neutral contains exactly one verdict;
- the result does not claim that a temperature value was applied;
- unsupported runtime claims are marked `evidence_needed`;
- the answer states the valid/invalid boundary without exaggerated agreement.

Behavioral determinism beyond this contract remains `evidence_needed`; prompt
text alone cannot prove provider/runtime lineage.

## 9. Failure handling and rollback

Stop without editing when any of these occurs:

- `changed-preimage`;
- duplicate calibration heading;
- existing triad role count changes from three;
- an unrelated file would need modification;
- a secret scan returns a nonzero count;
- the proposed wording conflicts with a higher-authority instruction.

For a later approved edit, create one preimage backup beside the personal
prompt, apply only the declared insertion, verify the postimage, and restore
the backup on any failed GREEN or secret scan. Do not use PatchDrop, alter
`safe.directory`, or touch application source for this personal prompt change.

## 10. Acceptance criteria

- The target prompt preimage is verified immediately before any future edit.
- Exactly one calibration block is inserted at the §4/§5 boundary.
- Existing triad schema, score formula, authority rules, and final response
  shell are unchanged.
- C6 is reused rather than duplicated as a general rule.
- All static GREEN checks and the count-only secret scan pass.
- The synthetic conflict probe meets the behavioral contract.
- The target prompt is not considered changed until the user separately
  approves implementation.
- `runtimeLineageVerdict=HOLD` and `desktopFinalProof=evidence_needed` remain.

## 11. Implementation boundary

This document is the approved design and non-applied diff draft. Its separate
implementation plan is
`Y:\docs\superpowers\plans\2026-08-04-notebook-global-prompt-calibration.md`.
The plan may authorize only the single personal prompt file after an execution
mode is explicitly selected; it does not imply permission to commit, push, or
modify application source.
