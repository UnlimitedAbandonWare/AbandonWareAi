<#
.SYNOPSIS
  Start, sample, and stop the engine-slots loopback mock. One owned PID only.

.DESCRIPTION
  The engine-slots mock binds 127.0.0.1 inside its own process. This wrapper
  does not add an external address and does not call api.openai.com or any
  other provider.

  -Action Start  listens on -Port (default 18771) and writes a pid file.
  -Action Sample starts the server if needed, saves five local samples, then stops it.
  -Action Stop   stops only the pid recorded in the pid file when its command
                 line contains engine_slots.py and mock-server.

.PARAMETER Action
  Start, Sample, or Stop.

.PARAMETER Port
  Loopback port. Refuses 18180, 18181, and 18182.

.PARAMETER PidFile
  JSON file that records the owned pid. Stop reads this file and no other pid.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Start', 'Sample', 'Stop')]
    [string]$Action,
    [int]$Port = 18771,
    [string]$PidFile = '',
    [string]$KitRoot = '',
    [string]$SampleDir = '',
    [string]$GptFixtureDir = '',
    [int]$LifetimeSeconds = 180,
    [string]$Root = ''
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

if (-not $Root) {
    if ($PSScriptRoot) { $Root = Split-Path -Parent $PSScriptRoot } else { $Root = (Get-Location).Path }
}
$Root = (Resolve-Path -LiteralPath $Root).Path
if (-not $PidFile) { $PidFile = Join-Path $Root 'var\codex-assist-20261001\mock-sse.pid.json' }
if (-not $KitRoot) { $KitRoot = Join-Path $Root 'var\codex-assist-20261001\kits\engine-slots\CODEX_ENGINE_SLOTS_R5' }
if (-not $SampleDir) { $SampleDir = Join-Path $Root 'var\codex-assist-20261001\mock-samples' }
if (-not $GptFixtureDir) {
    $GptFixtureDir = Join-Path $Root 'var\codex-assist-20261001\kits\gpt-pro\m312221ain_codex_handoff\proposed-tests\fixtures'
}
if ($Port -in 18180, 18181, 18182) {
    Write-Error 'Refusing Meta Display ports 18180-18182.'
    exit 2
}
if ($LifetimeSeconds -lt 1 -or $LifetimeSeconds -gt 3600) {
    Write-Error 'LifetimeSeconds must be 1..3600.'
    exit 2
}

function Read-PidRecord {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Get-ProcessCommand {
    param([int]$ProcessId)
    $row = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if (-not $row) { return '' }
    return [string]$row.CommandLine
}

function Test-OwnedMock {
    param([int]$ProcessId)
    $command = Get-ProcessCommand -ProcessId $ProcessId
    return ($command -like '*engine_slots.py*' -and $command -like '*mock-server*')
}

function Stop-OwnedMock {
    param([string]$Path)
    $record = Read-PidRecord -Path $Path
    if (-not $record) {
        Write-Host 'STOP status=already-absent remaining=0'
        return 0
    }
    $processId = [int]$record.pid
    $alive = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $alive) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
        Write-Host "STOP status=already-exited pid=$processId remaining=0"
        return 0
    }
    if (-not (Test-OwnedMock -ProcessId $processId)) {
        Write-Host "STOP status=refused pid=$processId reason=command-line-mismatch remaining=1"
        return 2
    }
    Stop-Process -Id $processId -Force
    $deadline = (Get-Date).AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 200
        $alive = Get-Process -Id $processId -ErrorAction SilentlyContinue
    } while ($alive -and (Get-Date) -lt $deadline)
    $still = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($still) {
        Write-Host "STOP status=still-alive pid=$processId remaining=1"
        return 1
    }
    Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    Write-Host "STOP status=stopped pid=$processId remaining=0"
    return 0
}

function Start-OwnedMock {
    param([int]$ListenPort, [string]$Path, [string]$Kit, [int]$Lifetime)
    $tool = Join-Path $Kit 'tools\engine_slots.py'
    if (-not (Test-Path -LiteralPath $tool)) {
        Write-Error "engine_slots.py missing: $tool"
        exit 2
    }
    $existing = Read-PidRecord -Path $Path
    if ($existing) {
        $alive = Get-Process -Id ([int]$existing.pid) -ErrorAction SilentlyContinue
        if ($alive -and (Test-OwnedMock -ProcessId ([int]$existing.pid))) {
            Write-Host "START status=already-running pid=$($existing.pid) port=$($existing.port)"
            return [int]$existing.port
        }
    }
    $log = Join-Path (Split-Path -Parent $Path) ("mock-sse-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
    $err = $log + '.err'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $env:PYTHONUNBUFFERED = '1'
    $proc = Start-Process -FilePath 'python' -PassThru -WindowStyle Hidden -WorkingDirectory $Kit `
        -RedirectStandardOutput $log -RedirectStandardError $err `
        -ArgumentList @('-B', $tool, 'mock-server', '--port', "$ListenPort", '--lifetime-seconds', "$Lifetime")
    $ready = $false
    $seenPort = $ListenPort
    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 200
        if (Test-Path -LiteralPath $log) {
            $text = Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue
            if ($text -and $text.Contains('"port"')) {
                $ready = $true
                $match = [regex]::Match($text, '"port"\s*:\s*(\d+)')
                if ($match.Success) { $seenPort = [int]$match.Groups[1].Value }
            }
        }
        if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) { break }
    } while (-not $ready -and (Get-Date) -lt $deadline)
    if (-not $ready) {
        $errText = ''
        if (Test-Path -LiteralPath $err) { $errText = Get-Content -LiteralPath $err -Raw -ErrorAction SilentlyContinue }
        if (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        Write-Error ("mock-server did not report a port. stderr=" + $errText)
        exit 1
    }
    $record = [ordered]@{
        pid        = $proc.Id
        port       = $seenPort
        bind       = '127.0.0.1'
        log        = $log
        started_at = [DateTimeOffset]::UtcNow.ToString('o')
        tool       = $tool
    }
    [System.IO.File]::WriteAllText($Path, (($record | ConvertTo-Json) + "`n"), [System.Text.UTF8Encoding]::new($false))
    Write-Host "START status=running pid=$($proc.Id) port=$seenPort bind=127.0.0.1"
    return $seenPort
}

function Save-Samples {
    param([int]$ListenPort, [string]$Dest, [string]$Fixtures)
    if (Test-Path -LiteralPath (Join-Path $Dest 'SAMPLE_INDEX.json')) {
        $Dest = $Dest + '-001'
    }
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    $env:AWX_MOCK_PORT = "$ListenPort"
    $env:AWX_SAMPLE_DIR = $Dest
    $env:AWX_GPT_FIXTURES = $Fixtures
    $capturePath = Join-Path ([System.IO.Path]::GetTempPath()) ('codex-mock-samples-' + [guid]::NewGuid().ToString('n') + '.py')
    $capture = @'
import json, os, urllib.request, urllib.error, hashlib
from pathlib import Path
port = int(os.environ["AWX_MOCK_PORT"])
dest = Path(os.environ["AWX_SAMPLE_DIR"])
fixtures = Path(os.environ["AWX_GPT_FIXTURES"])
host = "127.0.0.1"

def post(model, stream):
    body = json.dumps({"model": model, "stream": stream, "input": [{"role": "user", "content": "fixture"}]}).encode("utf-8")
    req = urllib.request.Request(
        f"http://{host}:{port}/v1/responses",
        data=body,
        headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            return resp.status, resp.headers.get("Content-Type", ""), resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.headers.get("Content-Type", "") if exc.headers else "", exc.read()

def write(name, data):
    path = dest / name
    path.write_bytes(data)
    return {"name": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}

health_req = urllib.request.Request(f"http://{host}:{port}/health")
with urllib.request.urlopen(health_req, timeout=5) as resp:
    health = resp.read()
    health_status = resp.status

completed_status, completed_type, completed = post("fixture-text", True)
truncated_status, truncated_type, truncated = post("fixture-incomplete", True)
limit_status, limit_type, limit_body = post("fixture-429", False)
unknown_status, unknown_type, unknown_body = post("oauth-401", False)
rows = []
rows.append({**write("completed.sse", completed), "http_status": completed_status, "content_type": completed_type,
             "source": "engine-slots-mock", "model": "fixture-text", "note": "Loopback SSE for a completed fixture response."})
rows.append({**write("truncated.sse", truncated), "http_status": truncated_status, "content_type": truncated_type,
             "source": "engine-slots-mock", "model": "fixture-incomplete",
             "note": "Kit incomplete/max_output_tokens stream. This is the mock truncation sample, not the GPT Pro truncated.sse event."})
limit_text = (
    f"HTTP {limit_status}\nContent-Type: {limit_type}\n\n".encode("utf-8") + limit_body
    + b"\n\n# The engine mock emits rate_limit_exceeded for fixture-429.\n"
    + b"# It does not emit subscription_sharing_usage_limit_exceeded.\n"
    + b"# ResponsesTerminalStatusContractTest already posts that code on HTTP 429 to its own loopback.\n"
)
rows.append({**write("usage-limit.http", limit_text), "http_status": limit_status, "content_type": limit_type,
             "source": "engine-slots-mock", "model": "fixture-429",
             "note": "Received 429 from the mock. Subscription usage-limit wording is not produced by this server."})
failed_src = fixtures / "failed-with-usage.sse"
failed = failed_src.read_bytes() if failed_src.is_file() else b""
failed_row = {**write("failed-with-usage.sse", failed), "http_status": None, "source": "gpt-pro-fixture-file",
              "note": "Copied from the GPT Pro fixture. The engine mock has no response.failed event with usage. Not an account observation."}
failed_row["fixture_present"] = failed_src.is_file()
rows.append(failed_row)
oauth_text = (
    f"HTTP {unknown_status}\nContent-Type: {unknown_type}\n\n".encode("utf-8") + unknown_body
    + b"\n\n# engine-slots mock-server has no HTTP 401 route.\n"
    + b"# The status above is the mock rejection for model oauth-401.\n"
    + b"# Shape already used by ResponsesTerminalStatusContractTest, synthetic only:\n"
    + b'# HTTP 401 application/json\n{"error":{"code":"invalid_api_key"}}\n'
)
rows.append({**write("oauth-401.http", oauth_text), "http_status": unknown_status, "content_type": unknown_type,
             "source": "engine-slots-mock-plus-live-test-shape", "model": "oauth-401",
             "note": "Mock did not return 401. The file records the observed rejection and the synthetic 401 shape from the existing test."})
index = {
    "schema": "awx.codex-mock-samples.v1",
    "bind": host,
    "port": port,
    "external_provider_calls": 0,
    "health_status": health_status,
    "health_body_sha256": hashlib.sha256(health).hexdigest(),
    "samples": rows,
    "application_verified": False,
}
(dest / "SAMPLE_INDEX.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"sample_dir": str(dest), "count": len(rows), "statuses": [row.get("http_status") for row in rows]}))
'@
    [System.IO.File]::WriteAllText($capturePath, $capture, [System.Text.UTF8Encoding]::new($false))
    try {
        python -B $capturePath
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Remove-Item -LiteralPath $capturePath -Force -ErrorAction SilentlyContinue
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

switch ($Action) {
    'Start' {
        $null = Start-OwnedMock -ListenPort $Port -Path $PidFile -Kit $KitRoot -Lifetime $LifetimeSeconds
        exit 0
    }
    'Stop' {
        exit (Stop-OwnedMock -Path $PidFile)
    }
    'Sample' {
        $listen = Start-OwnedMock -ListenPort $Port -Path $PidFile -Kit $KitRoot -Lifetime $LifetimeSeconds
        try {
            Save-Samples -ListenPort $listen -Dest $SampleDir -Fixtures $GptFixtureDir
        } finally {
            $stopCode = Stop-OwnedMock -Path $PidFile
            if ($stopCode -ne 0) { exit $stopCode }
        }
        exit 0
    }
}
