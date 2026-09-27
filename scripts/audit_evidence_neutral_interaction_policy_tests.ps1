[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$AuditScript = Join-Path $PSScriptRoot 'audit_evidence_neutral_interaction_policy.ps1'

if (-not (Test-Path -LiteralPath $AuditScript -PathType Leaf)) {
    Write-Error '[AWX][eni-audit-tests] RED: audit implementation missing'
    exit 1
}

$script:Passed = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw "assertion_failed: $Message"
    }
    $script:Passed++
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $parent = Split-Path -Parent $Path
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function New-CleanFixture {
    param([string]$Root)
    Write-Utf8NoBom (Join-Path $Root 'main/java/com/example/lms/guard/InteractionEvidencePolicy.java') @'
package com.example.lms.guard;
public final class InteractionEvidencePolicy {
  enum ResponseStyle { STANDARD, COLLABORATIVE }
  enum SecurityStance { NEUTRAL, DEFENSIVE }
  enum EvidenceMode { BASELINE, STRICT }
  enum MemoryWriteMode { NORMAL, SUPPRESS }
  enum FailureMode { LEGACY_FAIL_SOFT, FAIL_CLOSED }
  record Decision(ResponseStyle responseStyle, SecurityStance securityStance,
      EvidenceMode evidenceMode, MemoryWriteMode memoryWriteMode, FailureMode failureMode) {}
}
'@
    Write-Utf8NoBom (Join-Path $Root 'main/java/com/example/lms/prompt/PromptContext.java') @'
package com.example.lms.prompt;
import com.example.lms.guard.InteractionEvidencePolicy;
class PromptContext { InteractionEvidencePolicy.Decision interactionPolicyDecision; }
'@
    Write-Utf8NoBom (Join-Path $Root 'main/java/com/example/lms/prompt/PromptBuilder.java') @'
package com.example.lms.prompt;
interface PromptBuilder { String build(PromptContext context); }
'@
    Write-Utf8NoBom (Join-Path $Root 'main/java/com/example/lms/service/guard/GuardContext.java') @'
package com.example.lms.service.guard;
import com.example.lms.guard.InteractionEvidencePolicy;
class GuardContext {
  InteractionEvidencePolicy.Decision interactionPolicyDecision;
  void recordInteractionPolicyFact(Object fact, String id) {}
  GuardContext copy() { GuardContext c = new GuardContext(); c.interactionPolicyDecision = interactionPolicyDecision; return c; }
}
'@
    Write-Utf8NoBom (Join-Path $Root 'main/java/com/example/lms/service/ChatWorkflow.java') @'
package com.example.lms.service;
class ChatWorkflow {
  void run() {
    context.getInteractionPolicyFacts();
    context.getInteractionSuspectEvidenceIds();
    filterSuspectPromptContents();
    PromptContext ctx = PromptContext.builder().interactionPolicyDecision(interactionPolicyDecision).build();
    String prompt = promptBuilder.build(ctx);
  }
}
'@
    Write-Utf8NoBom (Join-Path $Root 'main/java/com/example/lms/telemetry/MlaBreadcrumb.java') @'
package com.example.lms.telemetry;
class MlaBreadcrumb {
  static void appendInteractionPolicyTransition(Decision decision) {
    TraceStore.put("interaction.policy.featureMode", decision.featureMode().name());
  }
  private static void helper() {}
}
'@
    Write-Utf8NoBom (Join-Path $Root 'main/java/com/example/lms/service/AttachmentService.java') @'
package com.example.lms.service;
class AttachmentService {
  void observeInteractionEvidence() {
    context.recordInteractionPolicyFact(DIGEST_MISMATCH);
    context.recordInteractionPolicyFact(PROVENANCE_MISMATCH);
  }
}
'@
    Write-Utf8NoBom (Join-Path $Root 'main/java/com/example/lms/api/ChatSessionAccessGuard.java') @'
package com.example.lms.api;
class ChatSessionAccessGuard {
  void deny() { context.recordInteractionPolicyFact(AUTHORIZATION_DENIED); }
}
'@
}

function Invoke-Audit {
    param([string]$Root)
    $lines = @(& powershell -NoProfile -ExecutionPolicy Bypass -File $AuditScript -Root $Root 2>&1)
    [pscustomobject]@{
        Code = $LASTEXITCODE
        Text = ($lines -join "`n")
    }
}

function Reset-Fixture {
    param([string]$Root)
    if (Test-Path -LiteralPath $Root) {
        Remove-Item -LiteralPath $Root -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $Root | Out-Null
    New-CleanFixture $Root
}

$TempBase = Join-Path ([System.IO.Path]::GetTempPath()) ('eni-audit-tests-' + [guid]::NewGuid().ToString('N'))
try {
    Reset-Fixture $TempBase

    $clean = Invoke-Audit $TempBase
    Assert-True ($clean.Code -eq 0) 'clean fixture exit code'
    Assert-True ((ConvertFrom-Json $clean.Text).status -eq 'pass') 'clean fixture status'

    $repeat = Invoke-Audit $TempBase
    Assert-True ($clean.Text -ceq $repeat.Text) 'byte-identical JSON on same tree'

    $missingRoot = Invoke-Audit (Join-Path $TempBase 'does-not-exist')
    Assert-True ($missingRoot.Code -eq 2) 'invalid root exit code'

    Reset-Fixture $TempBase
    Remove-Item -LiteralPath (Join-Path $TempBase 'main/java/com/example/lms/prompt/PromptBuilder.java')
    $missing = Invoke-Audit $TempBase
    Assert-True ($missing.Code -eq 2) 'missing required file exit code'

    Reset-Fixture $TempBase
    Write-Utf8NoBom (Join-Path $TempBase 'app/src/main/java/z/InteractionEvidencePolicy.java') 'class InteractionEvidencePolicy {}'
    Write-Utf8NoBom (Join-Path $TempBase 'project/src/main/java/a/InteractionEvidencePolicy.java') 'class InteractionEvidencePolicy {}'
    $eni001 = Invoke-Audit $TempBase
    $json001 = ConvertFrom-Json $eni001.Text
    Assert-True ($eni001.Code -eq 1) 'ENI001 exit code'
    Assert-True ($json001.violations.id -contains 'ENI001') 'ENI001 identifier'
    $paths = @($json001.violations | Where-Object id -eq 'ENI001' | Select-Object -ExpandProperty paths)
    Assert-True (($paths -join '|') -ceq (($paths | Sort-Object) -join '|')) 'ENI001 sorted paths'

    Reset-Fixture $TempBase
    Add-Content -LiteralPath (Join-Path $TempBase 'main/java/com/example/lms/guard/InteractionEvidencePolicy.java') -Value 'double trustScore = citationCount + rankingScore + providerSuccess;'
    $eni002 = Invoke-Audit $TempBase
    Assert-True ($eni002.Code -eq 1 -and (ConvertFrom-Json $eni002.Text).violations.id -contains 'ENI002') 'ENI002 violation'

    Reset-Fixture $TempBase
    (Get-Content -Raw -LiteralPath (Join-Path $TempBase 'main/java/com/example/lms/service/ChatWorkflow.java')).Replace('promptBuilder.build(ctx)', 'new StandardPromptBuilder().build(ctx)') |
        Set-Content -LiteralPath (Join-Path $TempBase 'main/java/com/example/lms/service/ChatWorkflow.java') -Encoding UTF8
    $eni003 = Invoke-Audit $TempBase
    Assert-True ($eni003.Code -eq 1 -and (ConvertFrom-Json $eni003.Text).violations.id -contains 'ENI003') 'ENI003 violation'

    Reset-Fixture $TempBase
    Remove-Item -LiteralPath (Join-Path $TempBase 'main/java/com/example/lms/service/AttachmentService.java')
    $eni003Producer = Invoke-Audit $TempBase
    Assert-True ($eni003Producer.Code -eq 1 -and (ConvertFrom-Json $eni003Producer.Text).violations.id -contains 'ENI003') 'ENI003 runtime producer violation'

    Reset-Fixture $TempBase
    Add-Content -LiteralPath (Join-Path $TempBase 'main/java/com/example/lms/guard/InteractionEvidencePolicy.java') -Value 'String unstable = System.getenv("RAW_SECRET");'
    $eni004 = Invoke-Audit $TempBase
    Assert-True ($eni004.Code -eq 1 -and (ConvertFrom-Json $eni004.Text).violations.id -contains 'ENI004') 'ENI004 violation'

    Reset-Fixture $TempBase
    $sentinel = 'owner' + 'Token=' + 'secret-sentinel-must-not-appear'
    Add-Content -LiteralPath (Join-Path $TempBase 'main/java/com/example/lms/telemetry/MlaBreadcrumb.java') -Value "TraceStore.put(`"interaction.policy.rawQuery`", `"$sentinel`");"
    $eni005 = Invoke-Audit $TempBase
    Assert-True ($eni005.Code -eq 1 -and (ConvertFrom-Json $eni005.Text).violations.id -contains 'ENI005') 'ENI005 violation'
    Assert-True (-not $eni005.Text.Contains($sentinel)) 'secret sentinel absent from output'
    Assert-True (-not $eni005.Text.Contains($TempBase)) 'absolute root absent from output'

    Reset-Fixture $TempBase
    $policyPath = Join-Path $TempBase 'main/java/com/example/lms/guard/InteractionEvidencePolicy.java'
    Remove-Item -LiteralPath $policyPath
    New-Item -ItemType Directory -Path $policyPath | Out-Null
    $internal = Invoke-Audit $TempBase
    Assert-True ($internal.Code -eq 3) 'internal error exit code'

    Write-Output ("[AWX][eni-audit-tests] PASS assertions={0}" -f $script:Passed)
    exit 0
}
finally {
    if (Test-Path -LiteralPath $TempBase) {
        Remove-Item -LiteralPath $TempBase -Recurse -Force
    }
}
