#requires -Version 5.1
<#
.SYNOPSIS
  ollama-prefer-3090.ps1 — Ollama가 3090을 쓰도록 유도하는 점검/안내 스크립트
.DESCRIPTION
  목적 : GPU 열거/UUID 확인, 현재 env 상태, 3060-바쁨/3090-유휴 불일치 탐지,
         3090 고정용 명령 출력. 기본은 읽기 전용이다.
  위험도: 기본 없음. -WriteUserEnv 는 사용자 env 1건 기록(되돌리기 방법 출력).
         -LaunchPinned 는 새 serve 1개를 별도 창으로 띄움(기존 서버 kill 없음).
  전제 : nvidia-smi, ollama in PATH
  롤백 : [Environment]::SetEnvironmentVariable('CUDA_VISIBLE_DEVICES',$null,'User')
         후 Ollama 재시작 / 또는 스크립트 출력의 되돌리기 절차 수행
  주의 : nvidia-smi index 는 PC마다 다르고 작업 관리자 번호와도 다를 수 있다.
         이 PC는 index 0 = 3060, index 1 = 3090 으로 확인됨(2026-09-24).
         index 대신 UUID 사용을 권장. 가짜 설정키 없음 — 실제 Ollama/CUDA 변수만 사용.
  참고 : 레인 생성/소유권 검증의 canonical 경로는
         scripts/desktop_dual_ollama_gpu_setup.ps1 (ValidateOnly|Start) 이다.
         이 스크립트는 그 진단/안내 보조 도구이며 대체가 아니다.
#>
[CmdletBinding(SupportsShouldProcess)]
param(
  [string]$PreferName = '3090',   # nvidia-smi 이름에 포함된 문자열
  [switch]$WriteUserEnv,          # 사용자 env 에 CUDA_VISIBLE_DEVICES=3090 UUID 기록
  [switch]$LaunchPinned,          # 포트가 비어 있을 때만 고정 serve 를 새 창으로 기동
  [int]$PinnedPort = 11436        # LaunchPinned 시 사용할 포트 (기존 11434/11435/11438과 분리)
)

function Get-GpuTable {
  nvidia-smi --query-gpu=index,uuid,name,utilization.gpu,memory.used,memory.total `
    --format=csv,noheader | ForEach-Object {
      $c = $_ -split ',\s*'
      # nvidia-smi csv values carry units ("12 %", "1379 MiB") — strip non-digits
      [pscustomobject]@{ Index=[int]($c[0] -replace '\D',''); Uuid=$c[1].Trim(); Name=$c[2].Trim()
        Util=[int]($c[3] -replace '\D',''); UsedMB=[int]($c[4] -replace '\D',''); TotalMB=[int]($c[5] -replace '\D','') }
    }
}

$gpus  = @(Get-GpuTable)
$pref  = $gpus | Where-Object Name -match $PreferName | Select-Object -First 1
if (-not $pref) { Write-Error "이름에 '$PreferName' 포함된 GPU를 찾지 못함. 수동 확인 필요"; return }
Write-Host "대상 GPU: $($pref.Name) | index $($pref.Index) | uuid $($pref.Uuid)" -ForegroundColor Cyan
Write-Host "전체 GPU:"; $gpus | Format-Table Index,Name,Util,UsedMB,TotalMB -AutoSize

Write-Host "== 현재 세션 env (있으면 표시) =="
foreach ($v in 'CUDA_VISIBLE_DEVICES','OLLAMA_HOST','OLLAMA_MAX_LOADED_MODELS','OLLAMA_KEEP_ALIVE','OLLAMA_NUM_PARALLEL') {
  $cur = [Environment]::GetEnvironmentVariable($v,'Process')
  $usr = [Environment]::GetEnvironmentVariable($v,'User')
  if ($null -eq $cur) { $cur = '-' }
  if ($null -eq $usr) { $usr = '-' }
  Write-Host ("{0,-26} session={1,-28} user={2}" -f $v, $cur, $usr)
}

Write-Host "`n== 불일치 탐지 =="
$busy3060 = $gpus | Where-Object { $_.Name -match '3060' -and $_.Util -ge 80 }
if ($busy3060 -and $pref.Util -le 10) {
  Write-Warning ("{0} util {1}% 인데 {2} util {3}% — 작업이 3060에 몰렸을 가능성(추정). " +
    "compute 앱 목록에서 ollama 계열이 어느 GPU UUID에 붙었는지 snapshot 스크립트로 확인 권장" -f
    $busy3060.Name,$busy3060.Util,$pref.Name,$pref.Util)
} else { Write-Host "현재 역전 패턴 없음" }

Write-Host "`n== 3090 고정 권장 절차 (수동 확인 포인트 포함) =="
Write-Host @"
1) 기존 ollama serve 들을 정리 (어떤 포트를 남길지 먼저 결정 — 지금 11434/11435/11438 세 개 관측됨)
2) 아래를 새 PowerShell 창에서 실행해 고정 serve 1개만 띄우기:
   `$env:CUDA_VISIBLE_DEVICES = '$($pref.Uuid)'   # UUID 사용 — index는 순서 바뀔 수 있음
   `$env:OLLAMA_MAX_LOADED_MODELS = '1'           # 동시 상주 1개로 제한 (중복 상주 방지)
   `$env:OLLAMA_KEEP_ALIVE = '5m'                 # 필요 시 조정
   `$env:OLLAMA_HOST = '127.0.0.1:11434'
   ollama serve
   ※ 레인 자동 구성은 scripts/desktop_dual_ollama_gpu_setup.ps1 -Mode ValidateOnly 로 먼저 검증할 것
3) 클라이언트(Spring 등)가 가리키는 OLLAMA_HOST/base-url 이 같은 포트인지 확인
4) 검증: .\ollama-status-snapshot.ps1 → compute 앱의 ollama 가 $($pref.Uuid) 에 붙는지 확인
"@ -ForegroundColor Gray

if ($WriteUserEnv) {
  if ($PSCmdlet.ShouldProcess("User env CUDA_VISIBLE_DEVICES", "set $($pref.Uuid)")) {
    [Environment]::SetEnvironmentVariable('CUDA_VISIBLE_DEVICES', $pref.Uuid, 'User')
    Write-Host "기록됨. 새 프로세스/재기동된 Ollama부터 적용. 되돌리기:" -ForegroundColor Green
    Write-Host "  [Environment]::SetEnvironmentVariable('CUDA_VISIBLE_DEVICES',`$null,'User')"
  }
}

if ($LaunchPinned) {
  $busy = Get-NetTCPConnection -State Listen -LocalPort $PinnedPort -ErrorAction SilentlyContinue
  if ($busy) { Write-Warning "포트 $PinnedPort 사용 중(PID $($busy.OwningProcess)) — 다른 포트 지정 필요"; return }
  if ($PSCmdlet.ShouldProcess("ollama serve pinned @ $PinnedPort", "start new window")) {
    $cmd = "`$env:CUDA_VISIBLE_DEVICES='$($pref.Uuid)'; `$env:OLLAMA_HOST='127.0.0.1:$PinnedPort'; `$env:OLLAMA_MAX_LOADED_MODELS='1'; ollama serve"
    Start-Process powershell -ArgumentList '-NoExit','-Command',$cmd
    Write-Host "고정 serve 를 별도 창으로 기동 요청. /api/version 응답 확인 후 사용할 것." -ForegroundColor Green
  }
}
