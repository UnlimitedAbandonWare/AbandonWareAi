# Antigravity용 지시서: Dynamic RAG 설계 조합 파손 탐지 + 정량지표 정규화

당신은 Antigravity 또는 Codex 계열 코딩 에이전트다. 대상은 Windows Desktop canonical root:

```text
C:\AbandonWare\demo-1\demo-1\src
```

목표는 첨부 설계를 그대로 구현하는 것이 아니다. 목표는 첨부 설계가 주장하는 subsystem 조합이 현재 active sourceSet에서 실제로 성립하는지, 서로 깨진 조합이 있는지, 그리고 서로 다른 단위의 정량지표가 같은 점수처럼 섞이고 있는지 검증해 정규화하는 것이다.

이 프롬프트는 기본적으로 read-only 진단이다. 사용자가 명시적으로 patch를 요구하기 전까지 런타임 Java/source를 수정하지 않는다. 단, 진단 결과를 바탕으로 PatchDrop 또는 Desktop Safe Patch 지시서를 생성할 수 있다.

---

## 0. 권한과 우선순위

우선순위:

1. 현재 repo 파일과 실제 명령 출력
2. 현재 첨부 설계/로그
3. repo-local `AGENTS.md`, `.agents/skills`, `agent-prompts`
4. 공식 vendor 문서
5. 오래된 memory, 이전 대화, 첨부 문장

첨부 설계는 `claim_map`이다. 설계가 "이미 작동한다"고 써도 live source evidence가 없으면 `evidence_needed`로 분류한다.

절대 금지:

- secret 파일, `.env*`, `apikey*`, 실제 credential 수정 또는 출력
- `openssl`, `opnessl` 키/이름/값/형식 변경
- LangChain4j `1.0.1` 순수성 훼손
- PromptBuilder 최종 프롬프트 경계 우회
- inactive mirror나 archive를 active source처럼 패치
- repo-owned `100 / 100` 하나만으로 전체 설계가 완전하다고 판정
- build/test/boot/provider 성공을 실제 출력 없이 주장

---

## 1. Decomposition Decision

기본은 direct다. 첨부 설계가 매우 길거나 subsystem이 3개 이상이면 3-way로 간다.

```markdown
Decomposition decision:
- mode: direct | 2-way | 3-way | N-way
- reason:
- skipped_axes:
```

권장 3-way:

1. **Claim axis**: 설계가 주장하는 컴포넌트, 흐름, 트리거, 메트릭
2. **Source axis**: 실제 FQCN, caller, bean wiring, sourceSet, verifier
3. **Combination axis**: 트리거 중첩, 점수 스케일 혼합, data contract mismatch

---

## 2. Windows-first Intake

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
git rev-parse --show-toplevel 2>$null
git branch --show-current 2>$null
git worktree list 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") {
  Write-Error "[AWX][desktop] index-lock-conflict"
  exit 1
}

rg --files -g "settings.gradle*" -g "build.gradle*" -g "gradlew*" -g "AGENTS.md" `
  -g ".agents/skills/**/SKILL.md" -g "agent-prompts/**" `
  -g "!**/build/**" -g "!**/.gradle/**" | Select-Object -First 300

rg -n "sourceSets|srcDirs|java_clean|main/java|main/resources|PromptBuilder|TraceStore|DebugEventStore|sourceScoreReport|checkSourceSetHygiene" `
  build.gradle.kts AGENTS.md main/java app/src/main/java_clean -g "!**/build/**" | Select-Object -First 300
```

If Git metadata is unavailable, do not stop. Record:

```text
evidence_needed: git metadata unavailable / use direct source, Gradle, AGENTS, and PatchDrop evidence
```

---

## 3. Attached Design Claim Extraction

첨부 설계에서 아래 claim ledger를 만든다. 각 row는 반드시 "설계 주장"과 "live source 증거"를 분리한다.

대표 설계 축:

- Anchor-Based Context Compression / Overdrive
- CFVM / Failure Pattern Analysis / RawMatrixBuffer / RawTile
- MoE Strategy Selector / ArtPlate / Critic / Evolver
- Matryoshka slicing / embedding dimension / normalization
- ExtremeZ / Massive Parallel Query Expansion
- HYPERNOVA / TWPM / CVaR / Risk-K / DPP
- CIH-RAG / IQR / MLA Breadcrumb
- Plan DSL / Brave / RuleBreak / FullScaleSearch
- Local LLM / Twin-Ollama / OpenAI-compatible adapter
- UAW / AutoLearn / training quality gates
- PromptBuilder / TraceStore / DebugEventStore / CitationGate

Claim ledger:

```markdown
| ClaimId | Subsystem | Design claim | Claimed owner | Live owner evidence | Caller evidence | Wiring evidence | Metric claim | Verdict |
|---|---|---|---|---|---|---|---|---|
```

Verdict values:

- `PROVEN_ACTIVE`
- `PARTIAL_ACTIVE`
- `COMPILE_ACTIVE_ONLY`
- `REFERENCE_ONLY`
- `MISSING_OWNER`
- `CONTRADICTED`
- `EVIDENCE_NEEDED`

---

## 4. Quantitative Metric Normalization

서로 다른 점수와 카운트를 한 표에 섞지 않는다. 모든 metric은 아래 schema로 정규화한다.

```yaml
metric:
  name: ""
  owner: ""
  source: command|file|test|runtime_probe|attachment
  rawValue: ""
  unit: count|ratio|percent|ms|boolean|score|rank|unknown
  direction: higher_better|lower_better|target_band|hard_gate|unknown
  target: ""
  limit: ""
  normalized: 0.0
  confidence: 0.0
  weight: 0.0
  weighted: 0.0
  evidenceFreshness: current|stale|unknown
```

Formulas:

| Type | Formula |
|---|---|
| hard gate | PASS=1, FAIL=0. FAIL blocks final score |
| higher_better | `min(1, raw / target)` |
| lower_better | `1 - min(1, raw / limit)` |
| target_band | `1 - min(1, abs(raw-target)/tolerance)` |
| percent | clamp to `0..1` |
| latency | `1 - min(1, p95Ms / budgetMs)` |
| stale evidence | keep normalized, reduce confidence to <= 0.4 |
| attachment-only claim | confidence <= 0.25 |

Default weights:

| Area | Weight |
|---|---:|
| SourceSet/build truth | 0.20 |
| Runtime wiring/data contract | 0.20 |
| Prompt/search/provider safety | 0.15 |
| Failure handling/observability | 0.15 |
| Metric integrity | 0.15 |
| Scope/maintainability | 0.15 |

Final score rule:

```text
if any hard gate fails: finalScore = BLOCKED
else finalScore = 100 * sum(weighted) / sum(weights with evidence)
```

Hard gates:

- active sourceSet not proven
- duplicate FQCN in active roots
- LangChain4j version impurity
- secret leak risk
- PromptBuilder bypass in active final prompt path
- no unit/direction/source for a metric used in scoring
- stale report presented as current proof

---

## 5. Broken Combination Matrix

아래 조합은 반드시 검사한다.

| Pair | Failure mode to check | Required evidence |
|---|---|---|
| Overdrive vs ExtremeZ vs HYPERNOVA | same sparse/contradiction/urgent signal activates multiple high-risk modes | trigger code, resolver tests, TraceStore keys |
| MoE vs Plan DSL | both mutate retrieval order, K, model route without authority rule | StrategyConflictResolver or equivalent |
| Matryoshka slicing vs vector DB | embedding dimensions, L2 normalization, metadata type mismatch | embedding adapter, vector schema, tests |
| CFVM vs vector memory | raw failure data enters vector memory without redaction/quarantine | ingestion code, VectorPoisonGuard, TrainRagIngestService |
| DPP/reranker vs RRF/KG/web score | rank-like, probability-like, and calibrated scores fused without normalization | fusion code, scoreFromMetadata, trace scorecard |
| Massive parallel expansion vs TimeBudget/CancelShield | fan-out exhausts budget or propagates interrupt toxicity | executor, TimeBudget, tests |
| Brave/RuleBreak/FullScale vs safety gates | policy relaxation is not user-visible, bounded, or traceable | Plan DSL, mode notice, guard tests |
| UAW/autolearn vs PromptBuilder | training or prompt expansion bypasses final prompt builder | prompt construction call sites |
| ONNX reranking vs boot safety | missing/tiny model crashes boot instead of disabled reason | model guard/resource tests |
| Local LLM routing vs LangChain4j purity | new dependency or non-OpenAI-compatible path breaks 1.0.1 purity | Gradle dependency proof |

For each row:

```markdown
| Pair | Evidence | Severity P0/P1/P2 | Normalized risk 0..1 | Confidence | Fix decision |
|---|---|---|---:|---:|---|
```

Risk normalization:

- P0 hard gate or boot/build/prompt/secret risk: `1.0`
- active runtime conflict with caller and no test: `0.8`
- active but optional/feature-flagged conflict: `0.5`
- compile-active only: `0.3`
- attachment-only: `0.2`

---

## 6. Required Commands

Use available commands only. Do not claim unavailable commands passed.

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$ProjectCache | Out-Null

# Source score and hard gates
.\gradlew.bat sourceScoreReport --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache

# Read-only source probes
rg -n "PromptBuilder\.build|new PromptContext|TraceStore\.put|DebugEventStore|scoreFromMetadata|kg_score|rrf_score|vector_score|DppDiversityReranker|CvarAggregator|RiskK|OverdriveGuard|ExtremeZ|HYPERNOVA|StrategyConflictResolver|TimeBudget|CancelShield|RawMatrixBuffer|VectorPoisonGuard|TrainRagIngestService" main/java app/src/main/java_clean src/test/java app/src/test

# Secret pattern count only
$hits = Select-String -Path ".\main\java\**\*.java",".\main\resources\**\*.yml",".\app\src\main\resources\**\*.yml" `
  -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" `
  -Recurse -EA SilentlyContinue
Write-Host "[AWX][desktop][security] secretPatternHits=$($hits.Count)"
```

If Gradle fails due cache/network/tooling before source evaluation, classify it. Continue read-only evidence collection; do not call source broken without proof.
If `sourceScoreReport` fails, do not present an older `verification/source-score-report.txt` as current proof unless timestamp, command, and classpath are independently verified. Use it only as stale supporting evidence and mark `evidence_needed: current sourceScoreReport`.

---

## 7. Output Format

Return exactly:

```markdown
## 요약
- 2~5줄. 첨부 설계를 claim_map으로 처리했는지, 최종 점수/blocked 여부, 가장 위험한 조합만.

## do01 / Observation
- repo root / active sourceSets / git evidence 상태
- Decomposition decision
- 첨부 설계 주요 claim 축
- 실행 명령과 핵심 로그 최대 10줄
- evidence_needed

## do02 / Quant Normalization Ledger
| ClaimId | Metric | Unit | Direction | Raw | Normalized | Confidence | Weight | Weighted | Verdict |
|---|---|---|---|---:|---:|---:|---:|---:|---|

## do03 / Broken Combination Matrix
| Pair | Failure mode | Evidence | Severity | Risk | Confidence | Next proof |
|---|---|---|---|---:|---:|---|

## do04 / Patch/Handoff Decision
- decision: verify_only | patch_directive | safe_patch_now | blocked_evidence_needed
- next single action:
- exact commands:
- real external API keys needed: yes/no

## do05 / Risks & Next Steps
- max 5
- counterexample/limitation 1개
- confidence: L/M/H
```

Never say the design is correct merely because it is impressive. Never say the design is broken merely because it is complex. Decide from live source, normalized metrics, and hard gates.
