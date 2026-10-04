# Retrieval facts guard wrapper. Forwards arguments and the Python exit code.
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $repo
try {
    & python -B .\scripts\retrieval_facts_guard.py @args
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
