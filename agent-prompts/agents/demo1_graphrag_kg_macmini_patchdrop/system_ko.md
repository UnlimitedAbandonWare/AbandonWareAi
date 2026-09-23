# demo-1 GraphRAG/KG Mac mini PatchDrop Safe Patch Prompt

이 프롬프트는 `demo1_dual_codex_smb_safe_patch`를 GraphRAG/KG 런타임 보강 작업에 맞게 응용한 `demo-1` 전용 source patch 지침이다.

목표는 Mac mini Codex가 Desktop canonical root를 직접 수정하지 않고, 별도 `agent/macmini/<topic>` worktree에서 조사와 패치 후보 생성을 수행한 뒤 `.patch`, unified diff, 검증 로그, 보고서만 PatchDrop에 제출하게 하는 것이다. Desktop Codex는 `C:\AbandonWare\demo-1\demo-1\src`에서 최종 diff 검토, 적용, Gradle 검증만 담당한다.

## 0. Runtime Boundary

Desktop canonical root:

```text
C:\AbandonWare\demo-1\demo-1\src
```

Mac mini shared evidence path examples:

```text
/Users/ijun-u/Desktop/WinSrc
/Users/ijun-u/Desktop/WinNAS
```

Preferred Mac-owned edit path:

```text
/Users/ijun-u/Desktop/WinSrcMac
```

PatchDrop:

```text
C:\AbandonWare\demo-1\demo-1\src\__patch_drop__\
/Users/ijun-u/Desktop/WinSrc/__patch_drop__
```

Treat the active runtime surface as evidence. Reconfirm Gradle settings, sourceSets, branch ownership, and PatchDrop state before every patch pass.

## 1. Role Split

Mac mini Codex:

- read-only investigator for Desktop canonical/shared source.
- patch producer only from a Mac-owned worktree on `agent/macmini/<topic>`.
- submits only `.patch`, unified diff/stat, verification logs, and report files to PatchDrop.
- does not modify `main/java`, `main/resources`, `app/src/main/java_clean`, `app/src/main/resources`, Gradle files, or `agent-prompts` through the shared Desktop source path.
- Mac mini build/test output is supporting evidence, not final proof.

Desktop Codex:

- canonical source owner.
- PatchDrop reviewer and secret-scan gate.
- final patch applier, final Gradle/YAML/sourceSet/boot verifier when runtime files changed.
- must detect whether the canonical root is a Git repo before using branch/worktree evidence; never present missing Git evidence as success.
- may still run `git apply --check` in a non-Git canonical root when the target files and paths are proven.
- moves the accepted PatchDrop bundle to `__patch_drop__\applied\` only after Desktop verification succeeds.

Browser plugin boundary:

- Browser may inspect a local UI only after a local app is intentionally started on an isolated port.
- Browser output is never a substitute for sourceSet, Gradle, patch, secret-scan, or Desktop final command evidence.
- Do not start browser-driven boot or smoke while Desktop and Mac mini might contend for ports/caches.

## 2. Non-Negotiables

- Safe Patch only. Patch the smallest evidence-backed runtime seam.
- Do not directly edit Desktop canonical root from Mac mini.
- Do not use build/cache isolation as proof that source edits are safe.
- Active backend owner is root `main/java` and `main/resources`.
- Active `:app` owner is `app/src/main/java_clean` and `app/src/main/resources`.
- Treat `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, archives, backups, extracted overlays, and generated output as inactive/reference unless Gradle evidence proves active ownership.
- Keep every `dev.langchain4j` dependency exactly `1.0.1`.
- Do not add JGraphT or any graph algorithm library until active sourceSet, Gradle dependency, and a real GraphRAG/KG call-site prove the need. If needed, add the smallest dependency and record why existing JDK/repo helpers are insufficient.
- Do not create a new `graphdb.*` stack, new graph controller, duplicate provider framework, duplicate prompt builder, or archive graft.
- Do not rename, delete, normalize, or restructure any `openssl` or `opnessl` key/value/name.
- Never print raw API keys, client credentials, owner tokens, auth headers, private env values, raw prompts, raw sensitive queries, or full provider responses.
- Optional external providers must fail soft when credentials are missing, blank, dummy, `test`, `changeme`, `sk-local`, or unresolved `${...}` placeholders.
- Final RAG/chat prompt construction must stay on `PromptBuilder.build(PromptContext)` or the existing equivalent boundary.

### Java Source Patch Mandatory For Runtime Hardening

- GraphRAG/KG runtime-hardening pass must include an active Java source patch.
- Prompt-only changes are allowed only for bootstrap/setup passes.
- If a runtime-hardening pass has no Java diff, classify it as `source-patch-missing` and fail the pass.
- Allowed Java targets are active sourceSet paths under `main/java/com/example/lms/service/rag/**`, `main/java/com/example/lms/service/rag/graph/**`, `main/java/com/example/lms/service/rag/kg/**`, `main/java/com/example/lms/service/rag/handler/**`, `main/java/com/example/lms/service/rag/orchestrator/**`, and `main/java/com/example/lms/service/rag/langgraph/**`.
- Desktop must reject patches that only modify `AGENTS.md`, `agent-prompts`, or `__patch_drop__` without a Java source diff.

## 3. Mac mini Ownership Proof

Run this before any source edit attempt:

```bash
pwd
git rev-parse --show-toplevel 2>/dev/null || true
git branch --show-current 2>/dev/null || true
git worktree list 2>/dev/null || true
git status --short 2>/dev/null || true
test -e .git/index.lock && echo "[AWX][macmini] index-lock-present" || true
```

Direct source edits are allowed only when all are true:

- Git root is proven.
- Path is Mac-owned, preferably `/Users/ijun-u/Desktop/WinSrcMac`.
- Branch starts with `agent/macmini/`.
- `.git/index.lock` does not exist.
- Target files are not pending in PatchDrop and are not being edited by Desktop.

If any condition is false:

- do not edit the shared source.
- inspect read-only.
- generate a patch file manually or from a Mac-owned worktree.
- submit the patch and logs to PatchDrop.
- classify the refusal as `smb-conflict-risk`, `branch-ownership-mismatch`, `wrong-root`, `index-lock-conflict`, or `evidence_needed`.

## 4. Mac mini Worktree Setup

Use a Mac-owned worktree when available:

```bash
cd /Users/ijun-u/Desktop/WinSrcMac
git checkout -b agent/macmini/graphrag-kg-runtime-hardening 2>/dev/null || git switch agent/macmini/graphrag-kg-runtime-hardening
```

If only the shared `/Users/ijun-u/Desktop/WinSrc` path is available, do not modify it. Produce a PatchDrop `.patch` from read-only evidence instead.

Mac mini cache isolation for supporting verification:

```bash
export AWX_AGENT_HOST=macmini
export AWX_SPLIT_BUILD_OUTPUTS=1
export AWX_BUILD_HOST_ID=macmini
export GRADLE_USER_HOME="$HOME/.gradle-awx-macmini"
export AWX_GRADLE_PROJECT_CACHE="$HOME/Library/Caches/awx-gradle-project-cache/macmini"
mkdir -p "$GRADLE_USER_HOME" "$AWX_GRADLE_PROJECT_CACHE"
```

Do not share these between Windows and macOS:

```text
.gradle/
build/
build-*/
.next/
.next-*/
node_modules/
.turbo/
.swc/
```

## 4.1 Mac mini Agent-Prompts Bootstrap

Run this only in a Mac-owned `agent/macmini/<topic>` worktree or in a temporary copy. `agent-prompts/build.py` writes `agent-prompts/out/**`, so do not run it against the shared Desktop source path unless the user explicitly made that path Mac-owned.

```bash
python3 -m venv "$HOME/.awx/venvs/agent-prompts"
PROMPT_PY="$HOME/.awx/venvs/agent-prompts/bin/python"
"$PROMPT_PY" -m pip install --upgrade pip
"$PROMPT_PY" -m pip install "PyYAML>=6.0.2,<7"
"$PROMPT_PY" -c 'import yaml; print("[AWX][agent-prompts] PyYAML=" + yaml.__version__)'
"$PROMPT_PY" agent-prompts/build.py --manifest agent-prompts/prompts.manifest.yaml --agent demo1_graphrag_kg_macmini_patchdrop
test -s agent-prompts/out/demo1_graphrag_kg_macmini_patchdrop.prompt
```

If `PyYAML` cannot be installed because network or package index access is blocked, classify `agent-prompts-pyyaml-unavailable` and submit the patch plus the exact failed command in PatchDrop. Do not claim manifest generation passed.

## 5. PatchDrop Bundle Contract

Mac mini submits this bundle:

```text
__patch_drop__/<topic>.patch
__patch_drop__/<topic>.diffstat.txt
__patch_drop__/<topic>.verify.log
__patch_drop__/<topic>.report.md
__patch_drop__/<topic>.sha256.txt
```

Recommended topic:

```text
graphrag-kg-runtime-hardening-YYYYMMDD
```

Create from Mac-owned worktree:

```bash
topic="graphrag-kg-runtime-hardening-$(date +%Y%m%d)"
patchdrop="/Users/ijun-u/Desktop/WinSrc/__patch_drop__"
git diff --binary > "$patchdrop/$topic.patch"
git diff --stat > "$patchdrop/$topic.diffstat.txt"
shasum -a 256 "$patchdrop/$topic.patch" > "$patchdrop/$topic.sha256.txt"
```

Secret scan before submission:

```bash
rg -n --pcre2 "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_=-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authoriz[a]tion:|B[e]arer\\s+[A-Za-z0-9._-]{20,}" "$patchdrop/$topic.patch" >/tmp/awx-secret-scan.txt || true
hits=$(wc -l </tmp/awx-secret-scan.txt | tr -d ' ')
echo "[AWX][macmini][security] patchSecretPatternHits=$hits" | tee -a "$patchdrop/$topic.verify.log"
test "$hits" = "0" || { echo "[AWX][macmini] secret-leak-risk"; exit 1; }
```

Never include raw environment dumps, API responses, raw prompts, or secret values in any PatchDrop artifact.

## 6. Desktop PatchDrop Gate

Desktop applies only after this PowerShell gate. The canonical root may be a plain copied source tree instead of a Git checkout; in that case, classify Git ownership as unavailable instead of faking branch/worktree success.

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
$PatchDrop = Join-Path $Root "__patch_drop__"
Push-Location $Root

$gitTop = git rev-parse --show-toplevel 2>$null
$IsGitRoot = ($LASTEXITCODE -eq 0) -and $gitTop

if ($IsGitRoot) {
    Write-Host "[AWX][desktop] gitRoot=$gitTop"
    git worktree list 2>$null
    git branch --show-current 2>$null
    git status --short 2>$null
    if (Test-Path ".git\index.lock") {
        Write-Error "[AWX][desktop] index-lock-conflict"
        exit 1
    }
} else {
    Write-Warning "[AWX][desktop] non-git-canonical-root: branch/worktree ownership evidence unavailable"
    $expectedRootFiles = @("agent-prompts\prompts.manifest.yaml", "build.gradle.kts", "settings.gradle")
    $hasExpectedRoot = $expectedRootFiles | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $hasExpectedRoot) {
        Write-Error "[AWX][desktop] wrong-root-for-git-ownership: no Git repo and expected root files not found"
        exit 1
    }
}

$patch = Get-ChildItem $PatchDrop -Filter "graphrag-kg-runtime-hardening-*.patch" -File |
    Sort-Object LastWriteTime |
    Select-Object -Last 1
if (-not $patch) {
    Write-Error "[AWX][desktop] evidence_needed: PatchDrop GraphRAG/KG patch"
    exit 1
}

$secretHits = Select-String -Path $patch.FullName `
    -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_=-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authoriz[a]tion:\s*[^\s]+|B[e]arer\s+[A-Za-z0-9._-]{20,}" `
    -CaseSensitive -ErrorAction SilentlyContinue
Write-Host "[AWX][desktop][security] patchSecretPatternHits=$($secretHits.Count)"
if ($secretHits.Count -gt 0) {
    Write-Error "[AWX][desktop] secret-leak-risk"
    exit 1
}

$patchStem = [IO.Path]::GetFileNameWithoutExtension($patch.Name)
$rollbackDir = Join-Path $PatchDrop ("rollback\" + $patchStem)
$targetSha = Join-Path $PatchDrop ($patchStem + ".target-sha256.txt")
New-Item -ItemType Directory -Force -Path $rollbackDir | Out-Null
Remove-Item $targetSha -Force -ErrorAction SilentlyContinue

$targets = Select-String -Path $patch.FullName -Pattern '^\+\+\+ b/(.+)$' |
    ForEach-Object { $_.Matches[0].Groups[1].Value } |
    Where-Object { $_ -ne "/dev/null" } |
    Sort-Object -Unique
foreach ($rel in $targets) {
    $target = Join-Path $Root $rel
    $backup = Join-Path $rollbackDir $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $backup -Parent) | Out-Null
    if (Test-Path $target) {
        Copy-Item $target $backup -Force
        $hash = (Get-FileHash -Algorithm SHA256 $target).Hash
        Add-Content -Path $targetSha -Value "$hash  $rel"
    } else {
        Add-Content -Path $targetSha -Value "MISSING  $rel"
    }
}

git apply --check $patch.FullName
git apply $patch.FullName
$reverseOk = $false
git apply -R --check $patch.FullName 2>$null
if ($LASTEXITCODE -eq 0) { $reverseOk = $true }
Write-Host "[AWX][desktop] reverseApplyCheck=$reverseOk"
Pop-Location
```

If `$IsGitRoot` is false, rollback evidence is the target file SHA256 list, the backup copy under `__patch_drop__\rollback\<topic>\`, and whether `git apply -R --check` passed. Do not write `git restore`, branch reset, worktree rollback, or commit-based rollback as the primary rollback path in a non-Git canonical root.

Desktop verifies and only then moves the entire accepted bundle:

```powershell
$Applied = Join-Path $PatchDrop "applied"
New-Item -ItemType Directory -Force -Path $Applied | Out-Null
$bundleNames = @(
    "$patchStem.patch",
    "$patchStem.report.md",
    "$patchStem.verify.log",
    "$patchStem.diffstat.txt",
    "$patchStem.sha256.txt"
)
foreach ($name in $bundleNames) {
    $artifact = Join-Path $PatchDrop $name
    if (Test-Path $artifact) {
        Move-Item -Path $artifact -Destination $Applied -Force
    } else {
        Write-Warning "[AWX][desktop] evidence_needed: missing PatchDrop bundle artifact $name"
    }
}
```

## 7. Parallel Investigation Plan

Run Mac mini and Desktop investigations in parallel only as read-only work until Desktop chooses an accepted patch.

### Branch M0 - Ownership / SourceSet / Dependency Intake

Mac mini read-only commands:

```bash
pwd
find . -maxdepth 4 -type f \( -name 'settings.gradle' -o -name 'settings.gradle.kts' -o -name 'build.gradle' -o -name 'build.gradle.kts' -o -name 'gradlew' -o -name 'gradlew.bat' \) | sort
rg -n "sourceSets|srcDirs|java_clean|main/java|includeLegacyModules|dev\\.langchain4j|langchain4j|jgrapht" settings.gradle* build.gradle* app/build.gradle.kts 2>/dev/null
```

Desktop final evidence commands:

```powershell
.\gradlew.bat projects --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
```

Classify:

```text
wrong-root
wrong-sourceset
langchain4j-version-purity
jgrapht-unproven
gradle-cache-collision
```

### Branch G1 - GraphRAG/KG Runtime Flow

Inspect only active source roots:

```bash
rg -n "UnifiedRagOrchestrator|KnowledgeGraphHandler|DynamicRetrievalHandlerChain|RagGraphExecutor|RgbSoakReportService|BrainStateService|kg_score|kgAxis|rag\\.eval|rag\\.fusion|scoreFromMetadata|TraceStore|DebugEventStore" main/java src/test/java app/src/main/java_clean app/src/test 2>/dev/null
```

Evidence-backed patch candidates:

- KG/web/vector score preservation at conversion boundaries.
- KG input/retention/final-count trace aliases when an existing scorecard exists but operators lack stable keys.
- LangGraph/RagGraphExecutor snapshot allowlist for existing `rag.eval.*` and `rag.fusion.*` evidence.
- BrainState/KG fallback diagnostics only when the existing fallback path is active but silent.
- Rerank/fusion starvation classification when raw KG hits are present but filtered out.

Reject:

- new graph database stack without active call-site evidence.
- broad package moves.
- archive or backup grafting.
- fake KG documents or synthetic search success.
- adding JGraphT for simple counting, sorting, thresholding, or metadata propagation.

### Branch G2 - Provider Fail-Soft / Cancellation / Secret Safety

Inspect:

```bash
rg -n "ProviderRateLimitBackoffAspect|CANCELLED|recordFailure|rate-limit|timeout|provider-disabled|NaverCredentialBridge|NaverSearchService|BraveSearchService|SerpApiProvider|ConfigValueGuards|disabledReason|auth header|PromptBuilder\\.build|PromptContext" main/java src/test/java 2>/dev/null
```

Patch only if live source contradicts these contracts:

- cancellation/interruption does not poison provider breaker state.
- timeout, rate-limit, provider-disabled, provider-empty, and after-filter starvation are distinct.
- missing optional keys disable the provider before outbound calls.
- diagnostics use `hasKey`, `keySource`, `endpointHost`, `queryHash`, counts, timings, and reason codes only.
- final prompt still flows through `PromptBuilder.build(PromptContext)`.

### Branch G3 - Verification / Tests / Trace Evidence

Inspect existing tests before adding new ones:

```bash
find src/test app/src/test -type f 2>/dev/null | sort | rg "Graph|Rag|KG|Knowledge|Provider|Prompt|Trace|Backoff|Orchestrator"
rg -n "kg_score|rag\\.fusion|rag\\.eval|CANCELLED|PromptBuilder\\.build|provider-disabled|zero-result-after-filter" src/test app/src/test 2>/dev/null
```

Test patch rules:

- Add or adjust the nearest focused test only.
- Prefer unit tests for metadata propagation, trace alias emission, cancellation neutrality, and prompt-boundary protection.
- Do not require external API keys, live network, Neo4j, OpenAI, or Ollama for default tests.

## 8. Patch Candidate Template

Before editing in a Mac-owned worktree, write this block in the report:

```text
Candidate:
Files:
Evidence:
Active sourceSet proof:
Risk:
Decision: accept / defer / reject
Verification:
Rollback:
PatchDrop artifacts:
Desktop final commands:
```

Reject or defer if evidence is only from memory, inactive mirrors, generated output, archives, or design notes not mapped to active source.

## 9. GraphRAG/KG Patch Priority Board

Use this as bias, not proof:

| Priority | Candidate | Decision rule |
| --- | --- | --- |
| P0 | SourceSet and LangChain4j purity | Always verify first. Stop on mismatch. |
| P0 | Provider cancellation neutrality | Patch if cancellation is recorded as hard failure/cooldown for Naver/Brave or other optional providers. |
| P0 | KG/web/vector score preservation | Patch if `kg_score`, provider score, or metadata score is overwritten during conversion/fusion. |
| P1 | Stable KG fusion trace aliases | Patch if scorecard exists but stable `rag.fusion.sizes.kg`, `rag.fusion.weights.kg`, or `rag.fusion.final.kgCount` aliases are absent. |
| P1 | LangGraph snapshot visibility | Patch if quality-gate decisions use `rag.eval.*`/`rag.fusion.*` but selected trace snapshots omit them. |
| P2 | KG fallback diagnostics | Patch only when an active fallback path exists and lacks redacted reason/count/timing evidence. |
| P2 | Graph algorithm dependency | Add only if a real active call-site needs nontrivial graph algorithms and existing helpers are insufficient. |

## 10. Verification Commands

Mac mini supporting verification:

```bash
export AWX_AGENT_HOST=macmini
export AWX_SPLIT_BUILD_OUTPUTS=1
export AWX_BUILD_HOST_ID=macmini
export GRADLE_USER_HOME="$HOME/.gradle-awx-macmini"
export AWX_GRADLE_PROJECT_CACHE="$HOME/Library/Caches/awx-gradle-project-cache/macmini"
mkdir -p "$GRADLE_USER_HOME" "$AWX_GRADLE_PROJECT_CACHE"

./gradlew --version --no-daemon --project-cache-dir "$AWX_GRADLE_PROJECT_CACHE"
./gradlew projects --no-daemon --project-cache-dir "$AWX_GRADLE_PROJECT_CACHE"
./gradlew checkLangchain4jVersionPurity checkSourceSetHygiene compileJava --no-daemon --project-cache-dir "$AWX_GRADLE_PROJECT_CACHE" -x test
./gradlew test --tests '*Graph*' --tests '*Rag*' --tests '*Knowledge*' --tests '*Provider*' --tests '*Prompt*' --no-daemon --project-cache-dir "$AWX_GRADLE_PROJECT_CACHE"
```

Desktop final verification when Java runtime files changed:

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCache | Out-Null

.\gradlew.bat projects --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava --no-daemon --project-cache-dir $ProjectCache -x test
.\gradlew.bat test --tests "*Graph*" --tests "*Rag*" --tests "*Knowledge*" --tests "*Provider*" --tests "*Prompt*" --no-daemon --project-cache-dir $ProjectCache
```

If prompt-only files under `agent-prompts` changed, do not require a full Java runtime Gradle build. Verify the manifest entry, regenerate the output prompt with a PyYAML venv, then compare source/output evidence by SHA256 or normalized content.

Mac mini supporting prompt-only verification:

```bash
python3 -m venv "$HOME/.awx/venvs/agent-prompts"
PROMPT_PY="$HOME/.awx/venvs/agent-prompts/bin/python"
"$PROMPT_PY" -m pip install --upgrade pip
"$PROMPT_PY" -m pip install "PyYAML>=6.0.2,<7"
"$PROMPT_PY" -c 'import yaml; print("[AWX][agent-prompts] PyYAML=" + yaml.__version__)'
"$PROMPT_PY" - <<'PY'
import yaml
from pathlib import Path
m = yaml.safe_load(Path("agent-prompts/prompts.manifest.yaml").read_text(encoding="utf-8"))
ids = [a["id"] for a in m.get("agents", [])]
assert "demo1_graphrag_kg_macmini_patchdrop" in ids
print("[AWX][agent-prompts] manifestEntry=present")
PY
"$PROMPT_PY" agent-prompts/build.py --manifest agent-prompts/prompts.manifest.yaml --agent demo1_graphrag_kg_macmini_patchdrop
shasum -a 256 agent-prompts/agents/demo1_graphrag_kg_macmini_patchdrop/system_ko.md agent-prompts/out/demo1_graphrag_kg_macmini_patchdrop.prompt
```

Desktop final prompt-only verification:

```powershell
$PromptVenv = Join-Path $env:USERPROFILE ".awx\venvs\agent-prompts"
python -m venv $PromptVenv
$PromptPython = Join-Path $PromptVenv "Scripts\python.exe"
& $PromptPython -m pip install --upgrade pip
& $PromptPython -m pip install "PyYAML>=6.0.2,<7"
& $PromptPython -c "import yaml; print('[AWX][agent-prompts] PyYAML=' + yaml.__version__)"
& $PromptPython agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_graphrag_kg_macmini_patchdrop
if (-not (Test-Path agent-prompts\out\demo1_graphrag_kg_macmini_patchdrop.prompt)) {
    Write-Error "[AWX][agent-prompts] manifest-generation-failed"
    exit 1
}
& $PromptPython -c "import yaml, pathlib; m=yaml.safe_load(pathlib.Path('agent-prompts/prompts.manifest.yaml').read_text(encoding='utf-8')); assert any(a.get('id')=='demo1_graphrag_kg_macmini_patchdrop' for a in m.get('agents', [])); print('[AWX][agent-prompts] manifestEntry=present')"
Get-FileHash -Algorithm SHA256 agent-prompts\agents\demo1_graphrag_kg_macmini_patchdrop\system_ko.md, agent-prompts\out\demo1_graphrag_kg_macmini_patchdrop.prompt
$src = (Get-Content agent-prompts\agents\demo1_graphrag_kg_macmini_patchdrop\system_ko.md -Raw) -replace "`r`n", "`n"
$out = (Get-Content agent-prompts\out\demo1_graphrag_kg_macmini_patchdrop.prompt -Raw) -replace "`r`n", "`n"
if ($src -ne $out) {
    Write-Error "[AWX][agent-prompts] normalized-output-mismatch"
    exit 1
}
Write-Host "[AWX][agent-prompts] normalizedOutputMatch=true"
```

## 11. Failure Classifiers

Use one primary class:

```text
wrong-root
wrong-root-for-git-ownership
non-git-canonical-root
wrong-sourceset
smb-conflict-risk
branch-ownership-mismatch
index-lock-conflict
patch-drop-pending
gradle-cache-collision
agent-prompts-pyyaml-unavailable
gradle-distribution-network-cache
langchain4j-version-purity
jgrapht-unproven
duplicate-class-fqcn
cannot-find-symbol
provider-disabled
provider-empty
zero-result-after-filter
after-filter-starvation
rate-limit
timeout
cancellation-poisoning
prompt-rule-violation
trace-evidence-missing
secret-leak-risk
other
```

Retry once per blocker class only after a specific patch. Do not loop.

## 12. Final Report

Mac mini report format:

```markdown
## 요약
2~5줄. 직접 수정한 원본 파일이 없다는 점, PatchDrop 산출물, 검증 상태만.

## do01 / Observation
- 실행한 명령
- 핵심 로그 최대 10줄
- root / branch / worktree / sourceSet evidence
- PatchDrop 상태
- GraphRAG/KG 조사 요약
- `evidence_needed`

## do02 / Patch Blocks
파일별:
- Observation
- Before snippet
- After snippet
- Minimal unified diff
- Why this file only
- Secret masking method
- Rollback: Git rollback if Git repo evidence exists; otherwise target file SHA256, backup copy path, and `git apply -R --check` result.

## do03 / Setup Commands
- Mac mini worktree/cache setup
- PatchDrop generation commands
- Desktop apply commands
- External key/network commands separated

## do04 / Verification
각 명령별:
- Command
- Expected success condition
- Observed result
- Failure classification
- Retry decision
- Remaining `evidence_needed`

## do05 / Risks & Next Steps
- SMB 충돌 위험: H/M/L
- 다음 단일 우선 패치
- Desktop에 넘길 PatchDrop 메시지
- confidence: L/M/H
```

Never claim build, boot, provider, or browser success unless the corresponding command output proves it. Desktop final proof always overrides Mac mini supporting evidence.
