$ErrorActionPreference='Continue'
$root='C:\AbandonWare\demo-1\demo-1\src'
Set-Location $root
$git='F:\git\cmd\git.exe'
if(-not (Test-Path $git)){ $git='git' }
$report=@()

# name-risk dirty
$porc = & $git status --porcelain 2>&1
$risky = @($porc | Where-Object { $_ -match '(?i)(^.. )?(.*\.(env|pem|p12|keystore|sqlite)|.*secrets?.*|.*credentials?.*|auth\.toml|auth\.json|id_rsa|uploads/|\.sandbox-secrets)' })
$report += "dirty_total=$($porc.Count)"
$report += "risky_named=$($risky.Count)"
$report += ($risky | Select-Object -First 50)
$envish = @($porc | Where-Object { $_ -match '(?i)\.env' })
$report += "envish=$($envish.Count)"
$report += $envish

# staged
$staged = @(& $git diff --cached --name-only 2>&1)
$report += "staged_count=$($staged.Count)"
$report += $staged

# .env presence
$report += "dot_env_exists=$(Test-Path -LiteralPath '.env')"
$report += "dot_env_example=$(Test-Path -LiteralPath '.env.example')"
$gi = Select-String -Path '.gitignore' -Pattern '(?i)^\.env' -SimpleMatch:$false -ErrorAction SilentlyContinue
$report += "gitignore_env_rules=$($gi.Count)"

# content scan on modified tracked files (names + verdict only)
$patterns = @(
  'sk-[A-Za-z0-9_-]{20,}',
  'ghp_[A-Za-z0-9]{20,}',
  'github_pat_[A-Za-z0-9_]{20,}',
  'xai-[A-Za-z0-9]{20,}',
  'AIza[0-9A-Za-z\-_]{20,}',
  'AKIA[0-9A-Z]{16}',
  '-----BEGIN .*PRIVATE KEY-----'
)
$suspect=@(); $placeholder=@()
$files = @(& $git diff --name-only --diff-filter=AM 2>&1 | Where-Object { $_ -match '\.(md|txt|yml|yaml|properties|toml|json|js|ts|java|py|ps1|bat|sh|xml|gradle|env|example)$' } | Select-Object -First 500)
foreach($f in $files){
  if(-not (Test-Path -LiteralPath $f)){ continue }
  $item=Get-Item -LiteralPath $f -ErrorAction SilentlyContinue
  if(-not $item -or $item.Length -gt 1500000){ continue }
  $i=0
  Get-Content -LiteralPath $f -ErrorAction SilentlyContinue | ForEach-Object {
    $i++
    $line=$_
    foreach($p in $patterns){
      if($line -match $p){
        $isPh = $line -match '(?i)(YOUR_|CHANGE_ME|example|dummy|placeholder|xxx|TODO|REDACTED|\.\.\.|\*\*\*|insert.?key|sample)'
        $tag = if($isPh){'PLACEHOLDER'} else {'SUSPECT'}
        $patShort = ($p -split '\[')[0]
        $entry = "${f}:${i}:${tag}:${patShort}"
        if($isPh){ $placeholder += $entry } else { $suspect += $entry }
        break
      }
    }
  }
}
$report += "suspect_hits=$($suspect.Count)"
$report += ($suspect | Select-Object -First 40)
$report += "placeholder_hits=$($placeholder.Count)"

# untracked high-risk extensions sample
$ut = @($porc | Where-Object { $_ -match '^\?\?' } | ForEach-Object { $_.Substring(3) })
$utRisk = @($ut | Where-Object { $_ -match '(?i)\.(env|pem|key|p12|sqlite|db)$|secret|credential|token' })
$report += "untracked_risk_named=$($utRisk.Count)"
$report += ($utRisk | Select-Object -First 30)

# try conditional scan
if(Test-Path '.\scripts\conditional_local_git.py'){
  $pyCmd=$null
  foreach($c in @('py','python')){
    $which = & where.exe $c 2>$null | Select-Object -First 1
    if($which){ $pyCmd=$which; break }
  }
  $report += "python_launcher=$pyCmd"
  if($pyCmd){
    $scan = & $pyCmd -B .\scripts\conditional_local_git.py scan --repo . 2>&1 | Out-String
    $scan2 = $scan -replace '(?i)(sk-[A-Za-z0-9]{8})[A-Za-z0-9_-]+','$1***'
    $report += '=== conditional_local_git scan (truncated) ==='
    $report += $scan2.Substring(0, [Math]::Min(2000, $scan2.Length))
  }
}

$pathOut = Join-Path $root 'agent-prompts\_probe\git-secret-precommit-20260927.txt'
$report -join "`n" | Set-Content -Encoding utf8 $pathOut
$report -join "`n"
