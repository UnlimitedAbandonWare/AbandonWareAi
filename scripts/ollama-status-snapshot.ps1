#requires -Version 5.1
<#
.SYNOPSIS
  ollama-status-snapshot.ps1 — Ollama/GPU 상태 읽기 전용 스냅샷
.DESCRIPTION
  목적 : 실행 중인 ollama serve 인스턴스(포트별), 로드된 모델, GPU별 util/VRAM,
         compute 앱 PID->GPU 매핑을 한 번에 출력한다.
  위험도: 없음 (읽기 전용, 프로세스/설정 변경 없음)
  전제 : ollama CLI, nvidia-smi 가 PATH 에 있을 것. 없으면 해당 섹션만 건너뜀.
  롤백 : 불필요 (아무것도 바꾸지 않음)
  주의 : API 키/토큰/시크릿은 출력하지 않는다.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
function Section($t) { Write-Host "`n===== $t =====" -ForegroundColor Cyan }

Section "1) ollama serve 인스턴스 / 리슨 포트"
$serve = @(Get-CimInstance Win32_Process -Filter "Name='ollama.exe'" -ErrorAction SilentlyContinue |
         Where-Object { $_.CommandLine -match 'serve' })
if (-not $serve) { Write-Host "ollama serve 프로세스 없음" }
$ports = @{}
foreach ($p in $serve) {
  $listen = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.OwningProcess -eq $p.ProcessId }
  foreach ($l in $listen) {
    $ports[$l.LocalPort] = $p.ProcessId
    $started = $p.CreationDate
    if ($started -isnot [datetime]) {
      try { $started = [Management.ManagementDateTimeConverter]::ToDateTime($started) } catch { }
    }
    Write-Host ("PID {0,-6} 포트 {1,-6} 시작 {2:yyyy-MM-dd HH:mm:ss}" -f $p.ProcessId, $l.LocalPort, $started)
  }
}
if ($serve.Count -gt 1) {
  Write-Host "`n[주의] ollama serve 가 $($serve.Count)개 동시 실행 중 — 모델 중복 상주/VRAM 분산 원인 후보" -ForegroundColor Yellow
}

Section "2) 포트별 로드된 모델 (/api/ps)"
foreach ($port in ($ports.Keys | Sort-Object)) {
  try {
    $ps = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/ps" -TimeoutSec 5
    Write-Host "-- 포트 $port : $($ps.models.Count)개 로드"
    foreach ($m in $ps.models) {
      $gb = if ($m.size_vram) { "{0:N1}GB" -f ($m.size_vram/1GB) } else { "?" }
      Write-Host ("   {0,-40} vram={1,-8} until={2}" -f $m.name, $gb, $m.expires_at)
    }
  } catch { Write-Host "-- 포트 $port : /api/ps 실패 ($($_.Exception.Message))" }
}

Section "3) ollama ps / ollama list (CLI)"
ollama ps   2>$null
ollama list 2>$null

Section "4) GPU별 util / VRAM (nvidia-smi)"
$gpuCsv = nvidia-smi --query-gpu=index,uuid,name,utilization.gpu,memory.used,memory.total --format=csv,noheader 2>$null
$gpuMap = @{}
if ($gpuCsv) {
  foreach ($line in $gpuCsv) {
    $c = $line -split ',\s*'
    # nvidia-smi csv values carry units ("12 %", "1379 MiB") — strip non-digits
    $gpuMap[$c[1].Trim()] = [pscustomobject]@{ Index=[int]($c[0] -replace '\D',''); Uuid=$c[1].Trim(); Name=$c[2].Trim();
      Util=[int]($c[3] -replace '\D',''); UsedMB=[int]($c[4] -replace '\D',''); TotalMB=[int]($c[5] -replace '\D','') }
    Write-Host ("index {0} | {1,-26} | util {2,3}% | vram {3,6}/{4} MiB | {5}" -f $c[0],$c[2],($c[3] -replace '\D',''),($c[4] -replace '\D',''),($c[5] -replace '\D',''),$c[1])
  }
  Write-Host "[참고] nvidia-smi index 순서는 작업 관리자 GPU 번호와 다를 수 있음 — 매핑은 UUID 기준으로 볼 것" -ForegroundColor DarkYellow
} else { Write-Host "nvidia-smi 실행 실패" }

Section "5) compute 앱 PID -> GPU 매핑"
$apps = nvidia-smi --query-compute-apps=pid,process_name,gpu_uuid,used_memory --format=csv,noheader 2>$null
if ($apps) {
  $apps | ForEach-Object {
    $c = $_ -split ',\s*'
    $g = $gpuMap[$c[2].Trim()]
    $gname = if ($g) { "$($g.Name) (idx $($g.Index))" } else { $c[2] }
    [pscustomobject]@{ PID=[int]$c[0]; Process=($c[1] | Split-Path -Leaf); GPU=$gname; VRAM_MiB=$c[3] }
  } | Sort-Object GPU, PID | Format-Table -AutoSize
  Write-Host "(* 모델 추론 프로세스는 보통 'ollama' 계열로 표시됨. 데스크톱/브라우저 앱은 디스플레이 GPU에 붙는 게 정상)"
  Write-Host "(* WDDM 환경에서 프로세스별 메모리가 'N/A'로 나올 수 있음 — N/A를 0이나 'GPU 없음'으로 해석 금지)" -ForegroundColor DarkYellow
} else {
  Write-Host "compute 앱 없음 또는 조회 실패 (WDDM 제한으로 N/A일 수 있음 — 부재를 'GPU 미사용'으로 단정 금지)"
}

Section "6) 불일치 휴리스틱 (참고용, 단정 아님)"
$big  = $gpuMap.Values | Sort-Object TotalMB -Descending | Select-Object -First 1
$small= $gpuMap.Values | Sort-Object TotalMB            | Select-Object -First 1
if ($big -and $small -and $big.Index -ne $small.Index) {
  if ($small.Util -ge 80 -and $big.Util -le 10) {
    Write-Host ("[추정] {0}(idx {1}) 바쁨({2}%) + {3}(idx {4}) 유휴({5}%) — 작업이 약한 GPU에 몰렸을 가능성. ollama-prefer-3090.ps1 점검 권장" -f
      $small.Name,$small.Index,$small.Util,$big.Name,$big.Index,$big.Util) -ForegroundColor Yellow
  } else { Write-Host "현재 뚜렷한 역전 패턴 없음" }
}
