#requires -Version 5.1
# brief_save.ps1 — scripts/brief_save.py 얇은 래퍼 (cwd를 레포 루트로 고정).
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
& python -B (Join-Path $root 'scripts\brief_save.py') @args
exit $LASTEXITCODE
