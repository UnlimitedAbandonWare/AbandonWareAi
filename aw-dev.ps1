# aw-dev: repo-local dev tool dispatcher (PowerShell 5.1 compatible).
# All logic lives in tools\local_dev\aw_dev.py; this wrapper only finds an
# interpreter and forwards argv + the exit code unchanged.
# If ExecutionPolicy blocks this script, use aw-dev.cmd instead; do not
# relax the policy.
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Resolution order: AWX_PYTHON > bindings.local.json > uv cpython > PATH.
$Py = $env:AWX_PYTHON
if (-not $Py) {
    $bindings = Join-Path $Root 'var\local_dev\bindings.local.json'
    if (Test-Path -LiteralPath $bindings -PathType Leaf) {
        try {
            $bound = (Get-Content -LiteralPath $bindings -Raw -Encoding UTF8 | ConvertFrom-Json).python
            if ($bound -and (Test-Path -LiteralPath $bound -PathType Leaf)) { $Py = $bound }
        } catch { }
    }
}
if (-not $Py) {
    $uv = Join-Path $env:USERPROFILE '.local\bin\python3.11.exe'
    if (Test-Path -LiteralPath $uv -PathType Leaf) { $Py = $uv }
}
if (-not $Py) {
    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) { $Py = $cmd.Source }
}
if (-not $Py) {
    [Console]::Error.WriteLine('aw-dev: no Python interpreter found; set AWX_PYTHON or run bootstrap')
    exit 10
}

& $Py -B (Join-Path $Root 'tools\local_dev\aw_dev.py') @args
exit $LASTEXITCODE
