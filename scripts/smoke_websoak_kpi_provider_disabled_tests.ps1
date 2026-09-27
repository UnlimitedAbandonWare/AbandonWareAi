$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$Root = (Resolve-Path (Join-Path $ScriptsRoot "..")).Path
$SmokeScript = Join-Path $ScriptsRoot 'smoke_websoak_kpi_provider_disabled.ps1'
$SecurityConfig = Join-Path $Root 'main\java\com\example\lms\config\AppSecurityConfig.java'
$PowerShellExe = $null
try {
    $PowerShellExe = (Get-Process -Id $PID).Path
} catch {
    $PowerShellExe = $null
}
if ([string]::IsNullOrWhiteSpace($PowerShellExe)) {
    $PowerShellExe = 'powershell'
}
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[websoak-kpi-smoke-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[websoak-kpi-smoke-test][FAIL] $Name :: $Message"
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = 'assertion failed'
    )
    if ($Condition) { Write-Pass $Name } else { Write-Fail $Name $Message }
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name ($Text.Contains($Needle)) "expected source to contain '$Needle'"
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name (-not $Text.Contains($Needle)) "expected source not to contain '$Needle'"
}

function Get-SmokeBaseUrlResolver {
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $SmokeScript,
        [ref]$tokens,
        [ref]$parseErrors
    )
    if (@($parseErrors).Count -ne 0) {
        throw 'smoke resolver source parse failed'
    }
    $resolverAst = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq 'Resolve-SmokeBaseUrl'
    }, $true)
    if ($null -eq $resolverAst) {
        throw 'Resolve-SmokeBaseUrl function missing'
    }
    return [scriptblock]::Create($resolverAst.Extent.Text)
}

function Invoke-SmokeBaseUrlResolver {
    param(
        [AllowEmptyString()][string]$ExplicitBaseUrl,
        [int]$RuntimePort,
        [bool]$UseAssumeRunning,
        [AllowNull()][string]$AppPublicBaseUrl,
        [AllowNull()][string]$PublicBaseUrl
    )
    $definition = Get-SmokeBaseUrlResolver
    return & {
        param($FunctionDefinition, $Explicit, $PortValue, $Assume, $AppUrl, $PublicUrl)
        . $FunctionDefinition
        $oldAppPublicBaseUrl = $env:APP_PUBLIC_BASE_URL
        $oldPublicBaseUrl = $env:PUBLIC_BASE_URL
        try {
            [Environment]::SetEnvironmentVariable('APP_PUBLIC_BASE_URL', $AppUrl, 'Process')
            [Environment]::SetEnvironmentVariable('PUBLIC_BASE_URL', $PublicUrl, 'Process')
            $Port = $PortValue
            Resolve-SmokeBaseUrl $Explicit $PortValue $Assume
        } finally {
            [Environment]::SetEnvironmentVariable('APP_PUBLIC_BASE_URL', $oldAppPublicBaseUrl, 'Process')
            [Environment]::SetEnvironmentVariable('PUBLIC_BASE_URL', $oldPublicBaseUrl, 'Process')
        }
    } $definition $ExplicitBaseUrl $RuntimePort $UseAssumeRunning $AppPublicBaseUrl $PublicBaseUrl
}

try {
    Assert-True 'websoak smoke script exists' (Test-Path -LiteralPath $SmokeScript) 'missing scripts\smoke_websoak_kpi_provider_disabled.ps1'
    if (Test-Path -LiteralPath $SmokeScript) {
        $source = Get-Content -Raw -LiteralPath $SmokeScript
        Assert-Contains 'smoke starts bootRun' $source 'bootRun'
        Assert-Contains 'smoke hides runtime window' $source '-WindowStyle Hidden'
        Assert-Contains 'smoke exposes isolated Netty port' $source '[int]$NettyPort = 18090'
        Assert-Contains 'smoke restores Netty port env' $source '"NETTY_PORT"'
        Assert-Contains 'smoke sets Netty port env' $source '$env:NETTY_PORT = [string]$NettyPort'
        Assert-Contains 'smoke checks Netty port conflicts' $source '@($Port, $ManagementPort, $NettyPort)'
        Assert-Contains 'smoke passes explicit Netty port to bootRun' $source '--args=--netty.port=$NettyPort'
        Assert-Contains 'smoke forces desktop gradle user home' $source '.gradle-awx-desktop'
        Assert-Contains 'smoke forces desktop project cache' $source 'awx-gradle-project-cache\desktop'
        Assert-Contains 'smoke enables probe by env' $source 'PROBE_WEBSOAK_KPI_ENABLED'
        Assert-Contains 'smoke requires header key' $source 'X-Probe-Key'
        Assert-Contains 'smoke avoids query-param key' $source 'PROBE_WEBSOAK_KPI_ALLOW_QUERY_PARAM_KEY'
        Assert-Contains 'smoke clears Naver keys for disabled path' $source 'NAVER_KEYS'
        Assert-Contains 'smoke clears Brave key for disabled path' $source 'BRAVE_API_KEY'
        Assert-Contains 'smoke clears SerpApi key for disabled path' $source 'SERPAPI_API_KEY'
        Assert-Contains 'smoke checks provider disabled fields' $source 'providerDisabled'
        Assert-Contains 'smoke checks cache only fields' $source 'cacheOnly.merged.count'
        Assert-Contains 'smoke checks rescue merge fields' $source 'rescueMerge.used'
        Assert-Contains 'smoke checks starvation trigger fields' $source 'starvationFallback.trigger'
        Assert-Contains 'smoke scans high confidence secret hits' $source 'secretPatternHits'
        Assert-Contains 'smoke scans raw query leakage' $source 'rawQueryHits'
        Assert-Contains 'smoke emits evidence needed' $source 'evidence_needed'
        Assert-Contains 'smoke supports APP_PUBLIC_BASE_URL fallback' $source '$env:APP_PUBLIC_BASE_URL'
        Assert-Contains 'smoke supports PUBLIC_BASE_URL fallback' $source '$env:PUBLIC_BASE_URL'
        Assert-Contains 'smoke saves APP_PUBLIC_BASE_URL env' $source '"APP_PUBLIC_BASE_URL"'
        Assert-Contains 'smoke saves PUBLIC_BASE_URL env' $source '"PUBLIC_BASE_URL"'
        Assert-Contains 'smoke saves SECURITY_FORCE_HTTPS env' $source '"SECURITY_FORCE_HTTPS"'
        Assert-Contains 'smoke defines local HTTP rollback base' $source '$localHttpBaseUrl = "http://127.0.0.1:$Port"'
        Assert-Contains 'smoke forces APP_PUBLIC_BASE_URL to local HTTP' $source '$env:APP_PUBLIC_BASE_URL = $localHttpBaseUrl'
        Assert-Contains 'smoke forces PUBLIC_BASE_URL to local HTTP' $source '$env:PUBLIC_BASE_URL = $localHttpBaseUrl'
        Assert-Contains 'smoke disables forced HTTPS for local run' $source '$env:SECURITY_FORCE_HTTPS = "false"'
        Assert-Contains 'smoke emits redacted local HTTP rollback log' $source 'localHttpRollback=true baseScheme=http host=loopback'
        $appPublicIndex = $source.IndexOf('$env:APP_PUBLIC_BASE_URL')
        $publicIndex = $source.IndexOf('$env:PUBLIC_BASE_URL')
        $localFallbackIndex = $source.IndexOf('"http://127.0.0.1:$Port"')
        Assert-True 'smoke checks APP_PUBLIC_BASE_URL before local fallback' ($appPublicIndex -ge 0 -and $localFallbackIndex -gt $appPublicIndex) 'public deployment base URL must precede local fallback'
        Assert-True 'smoke checks PUBLIC_BASE_URL before local fallback' ($publicIndex -ge 0 -and $localFallbackIndex -gt $publicIndex) 'public deployment base URL must precede local fallback'
        Assert-NotContains 'smoke does not dump full environment' $source 'Get-ChildItem Env:'
        Assert-NotContains 'smoke does not put key in URL' $source '?key='

        $localWithoutAssume = Invoke-SmokeBaseUrlResolver `
            -ExplicitBaseUrl '' `
            -RuntimePort 18086 `
            -UseAssumeRunning $false `
            -AppPublicBaseUrl 'http://127.0.0.1:19991/' `
            -PublicBaseUrl 'http://127.0.0.1:19992/'
        Assert-True 'resolver ignores inherited URLs without assume-running' `
            ($localWithoutAssume -eq 'http://127.0.0.1:18086') `
            "expected local loopback base, got '$localWithoutAssume'"

        $explicitWithAssume = Invoke-SmokeBaseUrlResolver `
            -ExplicitBaseUrl 'http://127.0.0.1:19193/' `
            -RuntimePort 18086 `
            -UseAssumeRunning $true `
            -AppPublicBaseUrl 'http://127.0.0.1:19991/' `
            -PublicBaseUrl 'http://127.0.0.1:19992/'
        Assert-True 'resolver gives explicit BaseUrl precedence in assume-running mode' `
            ($explicitWithAssume -eq 'http://127.0.0.1:19193') `
            "expected explicit loopback base, got '$explicitWithAssume'"

        $localStartMismatchMessage = ''
        try {
            Invoke-SmokeBaseUrlResolver `
                -ExplicitBaseUrl 'http://127.0.0.1:19999' `
                -RuntimePort 18086 `
                -UseAssumeRunning $false `
                -AppPublicBaseUrl $null `
                -PublicBaseUrl $null | Out-Null
        } catch {
            $localStartMismatchMessage = $_.Exception.Message
        }
        Assert-Contains 'resolver rejects explicit BaseUrl that disagrees with local-start port' `
            $localStartMismatchMessage `
            'invalid-base-url reason=local-start-mismatch'

        $invalidSchemeMessage = ''
        try {
            Invoke-SmokeBaseUrlResolver `
                -ExplicitBaseUrl 'https://127.0.0.1:18086' `
                -RuntimePort 18086 `
                -UseAssumeRunning $true `
                -AppPublicBaseUrl $null `
                -PublicBaseUrl $null | Out-Null
        } catch {
            $invalidSchemeMessage = $_.Exception.Message
        }
        Assert-Contains 'resolver rejects non-http effective URL' $invalidSchemeMessage 'invalid-base-url reason=scheme'

        $nonLoopbackMessage = ''
        try {
            Invoke-SmokeBaseUrlResolver `
                -ExplicitBaseUrl 'http://example.invalid:18086' `
                -RuntimePort 18086 `
                -UseAssumeRunning $true `
                -AppPublicBaseUrl $null `
                -PublicBaseUrl $null | Out-Null
        } catch {
            $nonLoopbackMessage = $_.Exception.Message
        }
        Assert-Contains 'resolver rejects non-loopback effective URL' $nonLoopbackMessage 'invalid-base-url reason=non-loopback'

        $probeRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-websoak-base-url-test-' + [guid]::NewGuid().ToString('N'))
        $probeRunner = Join-Path $probeRoot 'invoke-smoke-with-fake-http.ps1'
        $probeCapture = Join-Path $probeRoot 'captured-uri.txt'
        $probeOutput = Join-Path $probeRoot 'output'
        $oldAppPublicBaseUrl = $env:APP_PUBLIC_BASE_URL
        $oldPublicBaseUrl = $env:PUBLIC_BASE_URL
        $oldCapturePath = $env:AWX_TEST_CAPTURE_PATH
        try {
            New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null
            $probeRunnerSource = @'
param([string]$SmokeScript, [string]$OutputDir)
function Invoke-WebRequest {
    param(
        [string]$Uri,
        [string]$Method,
        [hashtable]$Headers,
        [string]$ContentType,
        [object]$Body,
        [switch]$UseBasicParsing,
        [int]$TimeoutSec
    )
    [IO.File]::WriteAllText($env:AWX_TEST_CAPTURE_PATH, $Uri, [Text.UTF8Encoding]::new($false))
    [pscustomobject]@{
        StatusCode = 200
        Content = '{"samples":[{"kpi":{"web.naver.providerDisabled":true,"provider.naver":"disabled","outCount":0,"rawInputCount":0,"cacheOnly.merged.count":0,"rescueMerge.used":false,"starvationFallback.trigger":"none"}}]}'
    }
}
& $SmokeScript -AssumeRunning -OutputDir $OutputDir -TimeoutSec 5 -Iterations 1
'@
            [IO.File]::WriteAllText($probeRunner, $probeRunnerSource, [Text.UTF8Encoding]::new($false))
            $env:APP_PUBLIC_BASE_URL = 'http://127.0.0.1:19191/'
            $env:PUBLIC_BASE_URL = 'http://127.0.0.1:19192/'
            $env:AWX_TEST_CAPTURE_PATH = $probeCapture
            $probeLines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $probeRunner `
                -SmokeScript $SmokeScript `
                -OutputDir $probeOutput 2>&1 |
                ForEach-Object { $_.ToString() }
            $probeExit = $LASTEXITCODE
            $capturedUri = if (Test-Path -LiteralPath $probeCapture) {
                Get-Content -LiteralPath $probeCapture -Raw
            } else {
                ''
            }
            Assert-True 'assume-running smoke reaches measurement through fake HTTP boundary' `
                ($probeExit -eq 0) `
                "exit=$probeExit output=$($probeLines -join ' | ')"
            Assert-True 'assume-running smoke preserves inherited loopback base URL' `
                ($capturedUri -eq 'http://127.0.0.1:19191/internal/probe/websoak-kpi/run') `
                "capturedUri=$capturedUri"
        } finally {
            [Environment]::SetEnvironmentVariable('APP_PUBLIC_BASE_URL', $oldAppPublicBaseUrl, 'Process')
            [Environment]::SetEnvironmentVariable('PUBLIC_BASE_URL', $oldPublicBaseUrl, 'Process')
            [Environment]::SetEnvironmentVariable('AWX_TEST_CAPTURE_PATH', $oldCapturePath, 'Process')
            if (Test-Path -LiteralPath $probeRoot) {
                Remove-Item -LiteralPath $probeRoot -Recurse -Force -ErrorAction SilentlyContinue
            }
        }

        $tempOut = Join-Path ([IO.Path]::GetTempPath()) ('awx-websoak-kpi-smoke-test-' + [guid]::NewGuid().ToString('N'))
        $lines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
            -StaticOnly `
            -OutputDir $tempOut 2>&1 |
            ForEach-Object { $_.ToString() }
        $invokeOutput = $lines -join "`n"
        Assert-Contains 'static smoke exits cleanly' $invokeOutput '[AWX][websoak-smoke] staticOnly=true'
        Assert-Contains 'static smoke reports script path' $invokeOutput 'script='
        Assert-NotContains 'static smoke does not print probe key value' $invokeOutput 'websoak-smoke-'
        if (Test-Path -LiteralPath $tempOut) {
            Remove-Item -LiteralPath $tempOut -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    Assert-True 'app security config exists' (Test-Path -LiteralPath $SecurityConfig) 'missing AppSecurityConfig.java'
    if (Test-Path -LiteralPath $SecurityConfig) {
        $securitySource = Get-Content -Raw -LiteralPath $SecurityConfig
        Assert-Contains 'security permits internal websoak probe chain' $securitySource '"/internal/probe/**"'
        Assert-Contains 'security still permits api probe chain' $securitySource '"/api/probe/**"'
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
}

if ($Failures -gt 0) {
    Write-Host "[websoak-kpi-smoke-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[websoak-kpi-smoke-test][SUMMARY] failed=0'
exit 0
