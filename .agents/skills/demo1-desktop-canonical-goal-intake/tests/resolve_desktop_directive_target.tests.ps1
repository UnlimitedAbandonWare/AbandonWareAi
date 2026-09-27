$ErrorActionPreference = 'Stop'

$cases = @(
    @{ Name='empty'; Input=''; Reason='directive-target-empty'; Rel=$null },
    @{ Name='y-path'; Input='Y:\main\java\Example.java'; Reason='lexical-only'; Rel='main\java\Example.java' },
    @{ Name='relative-path'; Input='main/java/Example.java'; Reason='lexical-only'; Rel='main\java\Example.java' },
    @{ Name='canonical-c-path'; Input='C:\AbandonWare\demo-1\demo-1\src\main\java\Example.java'; Reason='lexical-only'; Rel='main\java\Example.java' },
    @{ Name='dot-segment'; Input='Y:\main\.\java\Example.java'; Reason='lexical-only'; Rel='main\java\Example.java' },
    @{ Name='relative-root-dot'; Input='.\'; Reason='directive-target-empty'; Rel=$null },
    @{ Name='y-root-dot'; Input='Y:\.\'; Reason='directive-target-empty'; Rel=$null },
    @{ Name='canonical-c-root-dot'; Input='C:\AbandonWare\demo-1\demo-1\src\.\'; Reason='directive-target-empty'; Rel=$null },
    @{ Name='alternate-c-root-dot'; Input='C:\.'; Reason='directive-path-not-y-rooted'; Rel=$null },
    @{ Name='alternate-c-root-dot-trailing'; Input='C:\.\'; Reason='directive-path-not-y-rooted'; Rel=$null },
    @{ Name='leading-whitespace'; Input=' main\java\Example.java'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='trailing-whitespace'; Input='Y:\main\java\Example.java '; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='parent-segment'; Input='Y:\..\outside.txt'; Reason='directive-path-escape'; Rel=$null },
    @{ Name='unc'; Input='\\server\share\file.txt'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='foreign-drive'; Input='D:\file.txt'; Reason='directive-path-not-y-rooted'; Rel=$null },
    @{ Name='alternate-c-root'; Input='C:\alternate\file.txt'; Reason='directive-path-not-y-rooted'; Rel=$null },
    @{ Name='mixed-drive'; Input='Y:\C:\file.txt'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='ads'; Input='Y:\main\file.txt:stream'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='invalid-character'; Input='Y:\main\bad?name.txt'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='reserved-device'; Input='Y:\main\NUL.txt'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='trailing-dot'; Input='Y:\main\file.txt.'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='segment-trailing-space'; Input='Y:\main \file.txt'; Reason='directive-path-unsupported'; Rel=$null },
    @{ Name='y-root-only'; Input='Y:\'; Reason='directive-target-empty'; Rel=$null },
    @{ Name='c-root-only-trailing-slash'; Input='C:\AbandonWare\demo-1\demo-1\src\'; Reason='directive-target-empty'; Rel=$null }
)

$expectedKeys = @(
    'canonicalExecutionRoot',
    'desktopTarget',
    'exists',
    'reason',
    'reparseRisk',
    'status',
    'targetRel',
    'withinRoot'
) | Sort-Object

$resolver = Join-Path $PSScriptRoot '..\scripts\resolve_desktop_directive_target.ps1'
if (-not (Test-Path -LiteralPath $resolver -PathType Leaf)) {
    Write-Error 'resolver-script-missing'
    exit 1
}

$failures = [System.Collections.Generic.List[string]]::new()
function Assert-ResultShape {
    param(
        [pscustomobject]$Result,
        [string]$Name
    )

    $actualKeys = @($Result.PSObject.Properties.Name | Sort-Object)
    if (($actualKeys -join ',') -ne ($expectedKeys -join ',')) { $failures.Add("${Name}:output-keys") }
    if ($Result.status -isnot [string] -or
        $Result.reason -isnot [string] -or
        $Result.canonicalExecutionRoot -isnot [string] -or
        $Result.withinRoot -isnot [bool] -or
        $Result.exists -isnot [bool] -or
        $Result.reparseRisk -isnot [bool]) {
        $failures.Add("${Name}:value-types")
    }
}

foreach ($case in $cases) {
    if ($case.Name -eq 'empty') {
        $json = & powershell -NoProfile -ExecutionPolicy Bypass -Command "& '$resolver' -DirectivePath '' -LexicalOnly"
    } else {
        $json = & powershell -NoProfile -ExecutionPolicy Bypass -File $resolver -DirectivePath $case.Input -LexicalOnly
    }
    if ($LASTEXITCODE -ne 0) { $failures.Add("$($case.Name):resolver-exit"); continue }
    try { $result = $json | ConvertFrom-Json -ErrorAction Stop }
    catch { $failures.Add("$($case.Name):json-invalid"); continue }
    Assert-ResultShape -Result $result -Name $case.Name
    if ($result.status -ne 'HOLD') { $failures.Add("$($case.Name):status") }
    if ($result.reason -ne $case.Reason) { $failures.Add("$($case.Name):reason") }
    if ($result.canonicalExecutionRoot -ne 'C:\AbandonWare\demo-1\demo-1\src') { $failures.Add("$($case.Name):canonical-execution-root") }
    if ($result.targetRel -ne $case.Rel) { $failures.Add("$($case.Name):target-rel") }
    if ($result.status -isnot [string] -or
        $result.reason -isnot [string] -or
        $result.canonicalExecutionRoot -isnot [string]) {
        $failures.Add("$($case.Name):string-types")
    }
    if ($result.withinRoot -isnot [bool] -or
        $result.exists -isnot [bool] -or
        $result.reparseRisk -isnot [bool]) {
        $failures.Add("$($case.Name):boolean-types")
    }
    if ($null -ne $case.Rel -and $result.withinRoot -ne $true) { $failures.Add("$($case.Name):within-root") }
    if ($null -ne $case.Rel) {
        if ($result.desktopTarget -isnot [string] -or
            -not $result.desktopTarget.StartsWith('C:\AbandonWare\demo-1\demo-1\src\', [StringComparison]::OrdinalIgnoreCase)) {
            $failures.Add("$($case.Name):desktop-target")
        }
        if ($result.exists -ne $false -or $result.reparseRisk -ne $false) {
            $failures.Add("$($case.Name):lexical-state")
        }
    } else {
        if ($result.withinRoot -ne $false) { $failures.Add("$($case.Name):invalid-within-root") }
        if ($null -ne $result.desktopTarget -or $result.exists -ne $false -or $result.reparseRisk -ne $false) {
            $failures.Add("$($case.Name):invalid-state")
        }
    }
}

$liveRel = '.agents\skills\demo1-desktop-canonical-goal-intake\SKILL.md'
$liveInputs = @(
    $liveRel,
    "Y:\$liveRel",
    "C:\AbandonWare\demo-1\demo-1\src\$liveRel",
    "C:\AbandonWare\demo-1\demo-1\src\.\$liveRel"
)
$liveResults = @()
foreach ($liveInput in $liveInputs) {
    $liveJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $resolver -DirectivePath $liveInput
    if ($LASTEXITCODE -ne 0) {
        $failures.Add('live-existing:resolver-exit')
    } else {
        try { $liveResult = $liveJson | ConvertFrom-Json -ErrorAction Stop }
        catch { $failures.Add('live-existing:json-invalid'); $liveResult = $null }
        if ($null -ne $liveResult) {
            Assert-ResultShape -Result $liveResult -Name 'live-existing'
            if ($liveResult.status -ne 'PASS') { $failures.Add('live-existing:status') }
            if ($liveResult.reason -ne 'match') { $failures.Add('live-existing:reason') }
            if ($liveResult.targetRel -ne $liveRel -or
                $liveResult.withinRoot -ne $true -or
                $liveResult.exists -ne $true -or
                $liveResult.reparseRisk -ne $false) {
                $failures.Add('live-existing:target-state')
            }
            $liveResults += $liveResult
        }
    }
}

if ($liveResults.Count -eq $liveInputs.Count) {
    $candidateSet = @($liveResults.desktopTarget | Sort-Object -Unique)
    if ($candidateSet.Count -ne 1) { $failures.Add('live-existing:candidate-convergence') }
}

$missingRel = '.agents\skills\demo1-desktop-canonical-goal-intake\tests\missing-target-for-resolver-contract.txt'
$missingJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $resolver -DirectivePath $missingRel
try { $missingResult = $missingJson | ConvertFrom-Json -ErrorAction Stop }
catch { $failures.Add('missing-target:json-invalid'); $missingResult = $null }
if ($null -ne $missingResult) {
    Assert-ResultShape -Result $missingResult -Name 'missing-target'
    if ($missingResult.status -ne 'HOLD' -or
        $missingResult.reason -ne 'directive-target-missing' -or
        $missingResult.withinRoot -ne $true -or
        $missingResult.exists -ne $false -or
        $missingResult.reparseRisk -ne $false) {
        $failures.Add('missing-target:state')
    }
}

$unavailableJson = & {
    function Test-Path { param([string]$LiteralPath, [object]$PathType) $false }
    & $resolver -DirectivePath $liveRel
}
try { $unavailableResult = $unavailableJson | ConvertFrom-Json -ErrorAction Stop }
catch { $failures.Add('unavailable-root:json-invalid'); $unavailableResult = $null }
if ($null -ne $unavailableResult -and
    ($unavailableResult.status -ne 'HOLD' -or $unavailableResult.reason -ne 'c-canonical-unavailable')) {
    $failures.Add('unavailable-root:state')
}
if ($null -ne $unavailableResult) { Assert-ResultShape -Result $unavailableResult -Name 'unavailable-root' }

$gitMismatchJson = & {
    function git {
        $global:LASTEXITCODE = 0
        'C:\alternate-checkout'
    }
    & $resolver -DirectivePath $liveRel
}
try { $gitMismatchResult = $gitMismatchJson | ConvertFrom-Json -ErrorAction Stop }
catch { $failures.Add('git-root-mismatch:json-invalid'); $gitMismatchResult = $null }
if ($null -ne $gitMismatchResult) {
    Assert-ResultShape -Result $gitMismatchResult -Name 'git-root-mismatch'
    if ($gitMismatchResult.status -ne 'HOLD' -or $gitMismatchResult.reason -ne 'c-canonical-unavailable') {
        $failures.Add('git-root-mismatch:state')
    }
}

$canonicalRoot = 'C:\AbandonWare\demo-1\demo-1\src'
$liveTarget = Join-Path $canonicalRoot $liveRel
$disappearedAfterLeafCheckJson = & {
    function Test-Path {
        param([string]$LiteralPath, [object]$PathType)
        if ($LiteralPath.Equals($liveTarget, [StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
        if ($null -eq $PathType) {
            Microsoft.PowerShell.Management\Test-Path -LiteralPath $LiteralPath
        } else {
            Microsoft.PowerShell.Management\Test-Path -LiteralPath $LiteralPath -PathType $PathType
        }
    }
    function Get-Item {
        param([string]$LiteralPath, [switch]$Force)
        if ($LiteralPath.Equals($liveTarget, [StringComparison]::OrdinalIgnoreCase)) {
            throw [System.Management.Automation.ItemNotFoundException]::new('simulated-disappearance')
        }
        Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath -Force:$Force
    }
    & $resolver -DirectivePath $liveRel
}
try { $disappearedAfterLeafCheckResult = $disappearedAfterLeafCheckJson | ConvertFrom-Json -ErrorAction Stop }
catch { $failures.Add('disappeared-after-leaf-check:json-invalid'); $disappearedAfterLeafCheckResult = $null }
if ($null -ne $disappearedAfterLeafCheckResult) {
    Assert-ResultShape -Result $disappearedAfterLeafCheckResult -Name 'disappeared-after-leaf-check'
    if ($disappearedAfterLeafCheckResult.status -ne 'HOLD' -or
        $disappearedAfterLeafCheckResult.reason -ne 'directive-target-missing' -or
        $disappearedAfterLeafCheckResult.exists -ne $false -or
        $disappearedAfterLeafCheckResult.reparseRisk -ne $false) {
        $failures.Add('disappeared-after-leaf-check:state')
    }
}

$descendantReparseJson = & {
    function Get-Item {
        param([string]$LiteralPath, [switch]$Force)
        if ($LiteralPath.Equals($liveTarget, [StringComparison]::OrdinalIgnoreCase)) {
            return [pscustomobject]@{ Attributes = [IO.FileAttributes]::ReparsePoint }
        }
        Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath -Force:$Force
    }
    & $resolver -DirectivePath $liveRel
}
try { $descendantReparseResult = $descendantReparseJson | ConvertFrom-Json -ErrorAction Stop }
catch { $failures.Add('descendant-reparse:json-invalid'); $descendantReparseResult = $null }
if ($null -ne $descendantReparseResult -and
    ($descendantReparseResult.reason -ne 'reparse-traversal-risk' -or
     $descendantReparseResult.exists -ne $true -or
     $descendantReparseResult.reparseRisk -ne $true)) {
    $failures.Add('descendant-reparse:state')
}
if ($null -ne $descendantReparseResult) { Assert-ResultShape -Result $descendantReparseResult -Name 'descendant-reparse' }

$ancestorPath = 'C:\AbandonWare'
$ancestorReparseJson = & {
    function Get-Item {
        param([string]$LiteralPath, [switch]$Force)
        if ($LiteralPath.Equals($ancestorPath, [StringComparison]::OrdinalIgnoreCase)) {
            return [pscustomobject]@{ Attributes = [IO.FileAttributes]::ReparsePoint }
        }
        Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath -Force:$Force
    }
    & $resolver -DirectivePath $liveRel
}
try { $ancestorReparseResult = $ancestorReparseJson | ConvertFrom-Json -ErrorAction Stop }
catch { $failures.Add('ancestor-reparse:json-invalid'); $ancestorReparseResult = $null }
if ($null -ne $ancestorReparseResult -and
    ($ancestorReparseResult.reason -ne 'reparse-traversal-risk' -or
     $ancestorReparseResult.exists -ne $true -or
     $ancestorReparseResult.reparseRisk -ne $true)) {
    $failures.Add('ancestor-reparse:state')
}
if ($null -ne $ancestorReparseResult) { Assert-ResultShape -Result $ancestorReparseResult -Name 'ancestor-reparse' }

$missingTarget = Join-Path $canonicalRoot $missingRel
$missingUnderReparseJson = & {
    function Test-Path {
        param([string]$LiteralPath, [object]$PathType)
        if ($LiteralPath.Equals($missingTarget, [StringComparison]::OrdinalIgnoreCase)) { return $false }
        if ($null -eq $PathType) {
            Microsoft.PowerShell.Management\Test-Path -LiteralPath $LiteralPath
        } else {
            Microsoft.PowerShell.Management\Test-Path -LiteralPath $LiteralPath -PathType $PathType
        }
    }
    function Get-Item {
        param([string]$LiteralPath, [switch]$Force)
        if ($LiteralPath.Equals($ancestorPath, [StringComparison]::OrdinalIgnoreCase)) {
            return [pscustomobject]@{ Attributes = [IO.FileAttributes]::ReparsePoint }
        }
        Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath -Force:$Force
    }
    & $resolver -DirectivePath $missingRel
}
try { $missingUnderReparseResult = $missingUnderReparseJson | ConvertFrom-Json -ErrorAction Stop }
catch { $failures.Add('missing-under-reparse:json-invalid'); $missingUnderReparseResult = $null }
if ($null -ne $missingUnderReparseResult -and
    ($missingUnderReparseResult.reason -ne 'reparse-traversal-risk' -or
     $missingUnderReparseResult.exists -ne $false -or
     $missingUnderReparseResult.reparseRisk -ne $true)) {
    $failures.Add('missing-under-reparse:state')
}
if ($null -ne $missingUnderReparseResult) { Assert-ResultShape -Result $missingUnderReparseResult -Name 'missing-under-reparse' }

$danglingRel = '.agents\skills\demo1-desktop-canonical-goal-intake\tests\mock-dangling\child.txt'
$danglingLink = Join-Path $canonicalRoot '.agents\skills\demo1-desktop-canonical-goal-intake\tests\mock-dangling'
$danglingTarget = Join-Path $canonicalRoot $danglingRel
$danglingReparseJson = & {
    function Test-Path {
        param([string]$LiteralPath, [object]$PathType)
        if ($LiteralPath.Equals($danglingLink, [StringComparison]::OrdinalIgnoreCase) -or
            $LiteralPath.Equals($danglingTarget, [StringComparison]::OrdinalIgnoreCase)) {
            return $false
        }
        if ($null -eq $PathType) {
            Microsoft.PowerShell.Management\Test-Path -LiteralPath $LiteralPath
        } else {
            Microsoft.PowerShell.Management\Test-Path -LiteralPath $LiteralPath -PathType $PathType
        }
    }
    function Get-Item {
        param([string]$LiteralPath, [switch]$Force)
        if ($LiteralPath.Equals($danglingLink, [StringComparison]::OrdinalIgnoreCase)) {
            return [pscustomobject]@{ Attributes = [IO.FileAttributes]::ReparsePoint }
        }
        Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath -Force:$Force
    }
    & $resolver -DirectivePath $danglingRel
}
try { $danglingReparseResult = $danglingReparseJson | ConvertFrom-Json -ErrorAction Stop }
catch { $failures.Add('dangling-reparse:json-invalid'); $danglingReparseResult = $null }
if ($null -ne $danglingReparseResult) {
    Assert-ResultShape -Result $danglingReparseResult -Name 'dangling-reparse'
    if ($danglingReparseResult.reason -ne 'reparse-traversal-risk' -or
        $danglingReparseResult.exists -ne $false -or
        $danglingReparseResult.reparseRisk -ne $true) {
        $failures.Add('dangling-reparse:state')
    }
}

$skillPath = Join-Path $PSScriptRoot '..\SKILL.md'
$referencePath = Join-Path $PSScriptRoot '..\references\directive-rebinding-contract.md'
$openaiPath = Join-Path $PSScriptRoot '..\agents\openai.yaml'
foreach ($requiredPath in @($skillPath, $referencePath, $openaiPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        $failures.Add('contract:required-file-missing')
    }
}
if (-not ($failures -contains 'contract:required-file-missing')) {
    $skill = Get-Content -LiteralPath $skillPath -Raw -Encoding UTF8
    $reference = Get-Content -LiteralPath $referencePath -Raw -Encoding UTF8
    $openai = Get-Content -LiteralPath $openaiPath -Raw -Encoding UTF8
    if ($skill -notmatch '(?m)^originEvidenceRoot=Y:\\$') { $failures.Add('contract:origin-evidence-root') }
    if ($skill -notmatch '(?m)^sourceOwner=desktop$') { $failures.Add('contract:source-owner') }
    if ($skill -notmatch 'two declared RED tests') { $failures.Add('contract:two-red-tests') }
    if ($skill -notmatch 'immediate C preimage recheck') { $failures.Add('contract:stage-one-preimage-recheck') }
    if ($skill -notmatch 'changed-path subset') { $failures.Add('contract:stage-changed-path-subset') }
    if ($skill -notmatch 'Stage 2:.*active sourceSet candidate.*exact three-query preflight' ) {
        $failures.Add('contract:stage-two-preflight')
    }
    foreach ($owner in @(
        'demo1-source-edit-three-way-preflight',
        'repository-owned source-edit guard',
        'demo1-desktop-only-proof-loop',
        'demo1-skill-family-postprocessor',
        'demo1-demand-driven-external-proof'
    )) {
        if ($skill -notmatch [regex]::Escape($owner)) { $failures.Add("contract:owner:$owner") }
    }
    if ($skill -notmatch 'no new verification framework') { $failures.Add('contract:no-new-verification-framework') }
    if ($reference -notmatch '(?m)^\| Notebook input \| Desktop result \|$') { $failures.Add('contract:rewrite-table') }
    if ($reference -notmatch 'directive-path-not-y-rooted') { $failures.Add('contract:not-y-reason') }
    if ($reference -notmatch 'after live C proof') { $failures.Add('contract:proven-root-promotion') }
    if ($reference -notmatch '`lexical-only`.*non-authorizing HOLD routing state') { $failures.Add('contract:lexical-only-state') }
    foreach ($boundary in @(
        'no DB or public API changes',
        'no credential, secret, or environment-name changes',
        'authorization headers',
        'no commit, push, deploy, or external message',
        'per-stage verified preimage',
        'no reset hard or broad checkout'
    )) {
        if ($reference -notmatch [regex]::Escape($boundary)) { $failures.Add("contract:boundary:$boundary") }
    }
    if ($openai -notmatch 'SMB' -or $openai -notmatch 'mixed Y/C' -or $openai -notmatch 'source ownership') {
        $failures.Add('contract:openai-trigger-parity')
    }
}

$tokens = $null
$parseErrors = $null
$resolverAst = [Management.Automation.Language.Parser]::ParseFile(
    $resolver,
    [ref]$tokens,
    [ref]$parseErrors
)
if ($parseErrors.Count -ne 0) { $failures.Add('resolver:parse-errors') }

function Get-UnsafeMemberOrAssignment {
    param([Management.Automation.Language.ScriptBlockAst]$Ast)

    $issues = [System.Collections.Generic.List[string]]::new()
    $allowedStaticMembers = @(
        '[System.InvalidOperationException]::new',
        '[string]::IsNullOrWhiteSpace',
        '[IO.Path]::GetInvalidFileNameChars',
        '[IO.Path]::GetFullPath',
        '[IO.Path]::Combine',
        '[IO.Path]::GetPathRoot',
        '[Uri]::new',
        '[Uri]::UnescapeDataString'
    )
    $allowedInstanceMemberPairs = @(
        '$drive|Equals', '$gitRoot|Equals', '$gitRoot|StartsWith',
        '$pathFull|Equals', '$pathFull|StartsWith', '$rawValue|Equals',
        '$rawValue|Replace', '$rawValue|Trim', '$RelativePath|Split',
        '$relativeUri|ToString', '$Result.desktopTarget|Substring',
        '$rootFull|TrimEnd', '$rootUri|MakeRelativeUri', '$roundTrip|Equals',
        '$segment|Equals', '$segment|IndexOfAny', '$segment|TrimEnd',
        '$suffix|Split', '$value.TrimEnd(''\'')|Equals', '$value|StartsWith',
        '$value|Substring', '$value|TrimEnd', '([string]$gitRootRows[0])|Trim',
        '[Uri]::UnescapeDataString($relativeUri.ToString()).Replace(''/'', ''\'')|TrimEnd',
        '[Uri]::UnescapeDataString($relativeUri.ToString())|Replace'
    )
    $memberCalls = @($Ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.InvokeMemberExpressionAst]
    }, $true))
    foreach ($memberCall in $memberCalls) {
        $memberName = $memberCall.Member.Extent.Text
        if ($memberCall.Static) {
            $staticKey = $memberCall.Expression.Extent.Text + '::' + $memberName
            if ($staticKey -notin $allowedStaticMembers) { $issues.Add('static-member-not-allowlisted') }
        } else {
            $instanceKey = $memberCall.Expression.Extent.Text + '|' + $memberName
            if ($instanceKey -notin $allowedInstanceMemberPairs) {
                $issues.Add('instance-member-not-allowlisted')
            }
        }
    }

    $allowedVariables = @(
        'ErrorActionPreference', 'canonicalRoot', 'segments', 'invalidFileNameChars',
        'deviceBase', 'rootFull', 'rootBase', 'rootPrefixLocal', 'pathFull',
        'rootUri', 'pathUri', 'relativeUri', 'relative', 'roundTrip', 'rawValue',
        'value', 'relativeInput', 'relativeRoot', 'outsideReason', 'drive',
        'candidateInput', 'candidate', 'canonicalRelative', 'result', 'volumeRoot',
        'components', 'current', 'suffix', 'item', 'gitRootRows', 'gitRoot', 'reason'
    )
    $allowedMemberTargets = @(
        '$result.targetRel', '$result.desktopTarget', '$result.withinRoot',
        '$Result.exists', '$Result.reparseRisk', '$result.status', '$result.reason'
    )
    $assignments = @($Ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.AssignmentStatementAst]
    }, $true))
    foreach ($assignment in $assignments) {
        $left = $assignment.Left
        if ($left -is [Management.Automation.Language.VariableExpressionAst]) {
            if ($left.VariablePath.UserPath -notin $allowedVariables) {
                $issues.Add('variable-assignment-not-allowlisted')
            }
        } elseif ($left -is [Management.Automation.Language.MemberExpressionAst]) {
            if ($left.Extent.Text -notin $allowedMemberTargets) {
                $issues.Add('member-assignment-not-allowlisted')
            }
        } else {
            $issues.Add('assignment-target-not-allowlisted')
        }
    }
    $mutatingUnaryKinds = @('PlusPlus', 'MinusMinus', 'PostfixPlusPlus', 'PostfixMinusMinus')
    $unaryExpressions = @($Ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.UnaryExpressionAst]
    }, $true))
    foreach ($unaryExpression in $unaryExpressions) {
        if ($unaryExpression.TokenKind.ToString() -in $mutatingUnaryKinds) {
            $issues.Add('mutating-unary-not-allowlisted')
        }
    }
    $issues
}

$unsafeResolverAst = @(Get-UnsafeMemberOrAssignment -Ast $resolverAst)
if ($unsafeResolverAst.Count -ne 0) { $failures.Add('resolver:member-or-assignment-not-allowlisted') }

$mutationProbes = @(
    '[IO.File]::AppendAllText($p,$v)',
    '[IO.Directory]::CreateDirectory($p)',
    '[Diagnostics.Process]::Start($exe)',
    '$env:AWX_REVIEW_PROBE=''changed''',
    '$env:AWX_REVIEW_PROBE++',
    '++$env:AWX_REVIEW_PROBE',
    '$result.unexpected=''changed''',
    '$result.unexpected--',
    '$item.Replace(''C:\dest'',''C:\backup'')',
    '$writer.Delete()'
)
foreach ($mutationProbe in $mutationProbes) {
    $probeTokens = $null
    $probeErrors = $null
    $probeAst = [Management.Automation.Language.Parser]::ParseInput(
        $mutationProbe,
        [ref]$probeTokens,
        [ref]$probeErrors
    )
    if ($probeErrors.Count -ne 0 -or @(Get-UnsafeMemberOrAssignment -Ast $probeAst).Count -eq 0) {
        $failures.Add('resolver:mutation-probe-false-green')
    }
}

$commandAsts = @($resolverAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.CommandAst]
}, $true))
$allowedCommands = @(
    'Assert-LexicalSegments', 'Assert-NoReparseTraversal', 'ConvertTo-Json',
    'Get-Item', 'Get-RootRelativePath', 'git', 'New-ResolverResult',
    'Resolve-LexicalTarget', 'Test-Path', 'Throw-Reason',
    'Write-ResolverResult'
)
foreach ($commandAst in $commandAsts) {
    $commandName = $commandAst.GetCommandName()
    if ([string]::IsNullOrWhiteSpace($commandName) -or $commandName -notin $allowedCommands) {
        $failures.Add('resolver:command-not-allowlisted')
    }
}
$resolverText = Get-Content -LiteralPath $resolver -Raw -Encoding UTF8
$gitCommands = @($commandAsts | Where-Object { $_.GetCommandName() -eq 'git' })
if ($gitCommands.Count -ne 1 -or
    $gitCommands[0].Extent.Text -ne '& git -C $canonicalRoot rev-parse --show-toplevel 2>$null') {
    $failures.Add('resolver:git-command-shape')
}
$redirections = @($resolverAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.FileRedirectionAst]
}, $true))
if ($redirections.Count -ne 1 -or $redirections[0].Extent.Text -ne '2>$null') {
    $failures.Add('resolver:redirection-shape')
}
if ($failures.Count -ne 0) {
    Write-Error ('resolver-tests-failed count=' + $failures.Count + ' cases=' + (($failures | Sort-Object) -join ','))
    exit 1
}

"PASS lexical=$($cases.Count) live=$($liveInputs.Count)"
