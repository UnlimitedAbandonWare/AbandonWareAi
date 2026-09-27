#requires -Version 5.1
<#
.SYNOPSIS
  ollama-unload-idle.ps1 — 로드된 모델을 사용자 확인 후 개별 unload
.DESCRIPTION
  목적 : 각 ollama 서버(포트)에 상주 중인 모델을 나열하고, 사용자가 승인한 것만
         unload 한다. '전부 강제 종료'는 기본 경로가 아니다.
  위험도: 낮음 (모델 unload만 수행. 프로세스 kill 없음. 모델 파일 삭제 없음)
  전제 : ollama 서버 실행 중, ollama CLI in PATH (0.1.33+ 의 'ollama stop' 사용)
  롤백 : ollama run <model> 또는 다음 요청 시 자동 재로드됨
  keep-alive 참고: 모델은 마지막 사용 후 keep-alive(기본 5분)까지 VRAM에 남는다.
                   OLLAMA_KEEP_ALIVE(예: 5m/0/-1)로 상주 시간을 조절할 수 있고,
                   이 스크립트는 만료 전 상주분을 수동으로 비우는 용도다.
  사용 : .\ollama-unload-idle.ps1              # 목록 후 개별 확인
         .\ollama-unload-idle.ps1 -WhatIf      # 변경 없이 무엇을 할지만 출력
         .\ollama-unload-idle.ps1 -Keep 'qwen3.5:9b'
#>
[CmdletBinding(SupportsShouldProcess)]
param(
  [int[]]$Ports,                 # 미지정 시 ollama serve 리슨 포트 자동 탐지
  [string[]]$Keep = @(),         # 절대 unload 하지 않을 모델명
  [switch]$Force                 # 개별 확인 생략 (기본값 아님)
)
$ErrorActionPreference = 'Stop'

if (-not $Ports) {
  $Ports = @(Get-CimInstance Win32_Process -Filter "Name='ollama.exe'" |
    Where-Object CommandLine -match 'serve' | ForEach-Object {
      (Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object OwningProcess -eq $_.ProcessId).LocalPort
    } | Sort-Object -Unique)
  if (-not $Ports) { $Ports = @(11434) }
}
Write-Host "대상 포트: $($Ports -join ', ')"

$targets = foreach ($port in $Ports) {
  try {
    (Invoke-RestMethod "http://127.0.0.1:$port/api/ps" -TimeoutSec 5).models |
      ForEach-Object { [pscustomobject]@{ Port=$port; Name=$_.name
        VramGB=[math]::Round($_.size_vram/1GB,1); Until=$_.expires_at } }
  } catch { Write-Warning "포트 $port /api/ps 실패: $($_.Exception.Message)" }
}

if (-not $targets) { Write-Host "로드된 모델 없음 — 할 일 없음"; return }
$targets | Format-Table Port, Name, VramGB, Until -AutoSize

foreach ($t in $targets) {
  if ($Keep -contains $t.Name) { Write-Host "SKIP(Keep): $($t.Name)"; continue }
  if (-not $Force) {
    $a = Read-Host "unload 할까? [$($t.Name) @ port $($t.Port), $($t.VramGB)GB] (y/N)"
    if ($a -notmatch '^(y|yes)$') { Write-Host "  건너뜀"; continue }
  }
  if ($PSCmdlet.ShouldProcess("$($t.Name) @ $($t.Port)", "ollama stop")) {
    # ollama stop 은 OLLAMA_HOST 를 따르므로 해당 서버를 명시 지정
    & { $env:OLLAMA_HOST = "127.0.0.1:$($t.Port)"; ollama stop $t.Name }
    if ($LASTEXITCODE -eq 0) { Write-Host "  unloaded: $($t.Name)" -ForegroundColor Green }
    else {
      Write-Warning "  ollama stop 실패 — HTTP keep_alive=0 폴백 시도"
      try {
        Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($t.Port)/api/generate" `
          -Body (@{ model=$t.Name; keep_alive=0; stream=$false } | ConvertTo-Json) `
          -ContentType 'application/json' -TimeoutSec 15 | Out-Null
        Write-Host "  unloaded(http): $($t.Name)" -ForegroundColor Green
      } catch { Write-Warning "  폴백도 실패: $($_.Exception.Message)" }
    }
  }
}
Write-Host "`n완료. 상태 재확인: .\ollama-status-snapshot.ps1"
