# Secret-safe agent spend helper for demo-1 smoke/probe scripts.
# Dot-source: . .\scripts\agent_api_spend_guard.ps1
$script:AgentSpendSuccess = @{}

function Test-AgentSpendMode {
    if ($env:AWX_AGENT_SPEND_GUARD -match '^(1|true|yes|on)$') { return $true }
    if (-not [string]::IsNullOrWhiteSpace($env:AWX_AGENT_HOST)) { return $true }
    return $false
}

function Get-AgentSpendFingerprint {
    param([string]$Purpose,[string]$Provider,[string]$Model,[string]$Caller,[string]$ProbeId)
    return ($Purpose,$Provider,$Model,$Caller,$ProbeId) -join '|'
}

function Write-AgentSpendLog {
    param(
        [string]$Purpose,[string]$Provider,[string]$Model,[string]$Tier,[string]$Why,
        [string]$Caller,[string]$Cache,[string]$HttpStatus='n/a',[string]$ErrorClass='n/a',
        [string]$PromptTokens='n/a',[string]$CompletionTokens='n/a',[string]$EstCostClass='n/a'
    )
    $session = if ($env:AWX_AGENT_SESSION) { $env:AWX_AGENT_SESSION } elseif ($env:AWX_AGENT_HOST) { "agent-host:$($env:AWX_AGENT_HOST)" } else { 'default' }
    Write-Host ("[AWX][api-spend] session={0} agentMode={1} purpose={2} provider={3} model={4} tier={5} why={6} caller={7} cache={8} httpStatus={9} errorClass={10} promptTokens={11} completionTokens={12} estCostClass={13}" -f `
        $session,(Test-AgentSpendMode),$Purpose,$Provider,$Model,$Tier,$Why,$Caller,$Cache,$HttpStatus,$ErrorClass,$PromptTokens,$CompletionTokens,$EstCostClass)
}

function Assert-AgentSpendAllow {
    param([string]$Purpose,[string]$Provider,[string]$Model,[string]$Caller,[string]$ProbeId='',[switch]$ExplicitPaid)
    $fp = Get-AgentSpendFingerprint $Purpose $Provider $Model $Caller $ProbeId
    if (-not (Test-AgentSpendMode)) {
        Write-AgentSpendLog -Purpose $Purpose -Provider $Provider -Model $Model -Tier 'n/a' -Why 'user_request' -Caller $Caller -Cache 'miss'
        return $true
    }
    if ($script:AgentSpendSuccess.ContainsKey($fp)) {
        Write-AgentSpendLog -Purpose $Purpose -Provider $Provider -Model $Model -Tier 'n/a' -Why 'verification_replay_blocked' -Caller $Caller -Cache 'hit_skip' -ErrorClass 'skipped' -EstCostClass 'local0'
        return $false
    }
    $m = [string]$Model
    if (-not $ExplicitPaid -and $m -match '^(gpt-4|gpt-4o|gpt-5)([-$]|$)') {
        Write-AgentSpendLog -Purpose $Purpose -Provider $Provider -Model $Model -Tier 'paid_quality' -Why 'model_auto_blocked_stale' -Caller $Caller -Cache 'forced' -ErrorClass 'blocked' -EstCostClass 'llm_paid'
        return $false
    }
    Write-AgentSpendLog -Purpose $Purpose -Provider $Provider -Model $Model -Tier 'n/a' -Why 'verification_required' -Caller $Caller -Cache 'miss'
    return $true
}

function Register-AgentSpendSuccess {
    param([string]$Purpose,[string]$Provider,[string]$Model,[string]$Caller,[string]$ProbeId='')
    $fp = Get-AgentSpendFingerprint $Purpose $Provider $Model $Caller $ProbeId
    $script:AgentSpendSuccess[$fp] = 'ok'
}
