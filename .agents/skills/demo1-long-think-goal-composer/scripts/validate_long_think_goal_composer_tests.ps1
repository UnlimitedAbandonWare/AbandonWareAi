param(
    [string]$Root = "."
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return (Resolve-Path -LiteralPath $Path).Path
    }
    return (Resolve-Path -LiteralPath (Join-Path (Get-Location) $Path)).Path
}

function Assert-True([string]$Name, [bool]$Condition, [string]$Detail = "") {
    if (-not $Condition) {
        if ([string]::IsNullOrWhiteSpace($Detail)) {
            throw "[long-think-tests] assertion failed: $Name"
        }
        throw "[long-think-tests] assertion failed: $Name :: $Detail"
    }
    Write-Output "[long-think-tests] PASS $Name"
}

$resolvedRoot = Resolve-RepoPath $Root
$skillRoot = Join-Path $resolvedRoot ".agents\skills\demo1-long-think-goal-composer"
$skillPath = Join-Path $skillRoot "SKILL.md"
$referencePath = Join-Path $skillRoot "references\long-think-goal-composer-reference.md"
$openaiYamlPath = Join-Path $skillRoot "agents\openai.yaml"

$skillText = Get-Content -Raw -LiteralPath $skillPath
$referenceText = Get-Content -Raw -LiteralPath $referencePath
$openaiYamlText = Get-Content -Raw -LiteralPath $openaiYamlPath

Assert-True "skill frontmatter names long-think composer" ($skillText -match "name:\s*demo1-long-think-goal-composer") "missing name"
Assert-True "skill routes external tags as branches" ($skillText -match "Browser, Computer, Supabase, and Superpowers tags into separate\s+evidence lanes") "missing external tag branch rule"
Assert-True "reference names composer as intake root branch" ($referenceText -match "intake/root branch" -and $referenceText -match "demo1-long-think-goal-composer") "missing intake root branch"
Assert-True "reference keeps compact validator command" ($referenceText -match "-CompactReport" -and $referenceText -match "-SummaryJson") "missing compact validator command"
Assert-True "reference reads next action first" ($referenceText -match 'Read `nextActionSummary` first') "missing nextActionSummary guidance"
Assert-True "reference carries repeat probe recommendation" ($referenceText -match "repeatProbeRecommendation" -and $referenceText -match "do_not_repeat_until_filesystem_state_changes") "missing repeat probe guidance"
Assert-True "reference consumes goal completion claim guard" ($referenceText -match "goalCompletionClaimAllowed") "missing goalCompletionClaimAllowed guidance"
Assert-True "reference consumes goal update recommendation" ($referenceText -match "goalUpdateRecommendation") "missing goalUpdateRecommendation guidance"
Assert-True "reference consumes full report recommendation" ($referenceText -match "fullReportRecommended") "missing fullReportRecommended guidance"
Assert-True "reference consumes full report action mode" ($referenceText -match "fullReportActionMode") "missing fullReportActionMode guidance"
Assert-True "reference consumes summary reuse condition" ($referenceText -match "summaryReuseCondition") "missing summaryReuseCondition guidance"
Assert-True "reference consumes compact report line" ($referenceText -match "compactReportLine" -and $referenceText -match "same blocker, no repeat probe") "missing compactReportLine guidance"
Assert-True "reference keeps supabase read-only" ($referenceText -match "Supabase is read-only") "missing supabase boundary"
Assert-True "reference blocks tag-based edit authorization" ($referenceText -match "Do not treat Browser/Computer/Supabase tags as edit authorization") "missing external edit-authorization guard"
Assert-True "openai metadata points to skill" ($openaiYamlText -match "demo1-long-think-goal-composer") "missing openai metadata"

Write-Output "[long-think-tests] ok=True"
