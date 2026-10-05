#requires -Version 5.1
<#
.SYNOPSIS
  ollama-light-preload.ps1 — 가벼운 기본 모델만 preload
.DESCRIPTION
  목적 : 영상에서 실제로 보인 가벼운 모델(qwen3.5:9b)만 warm-up 상주시키는 예시.
         대형 모델(gemma4:26b, smtek/Qwen3.8-27B:Q3_K_XL)은 의도적으로 제외.
  위험도: 낮음 (pull + 소량 warm-up 생성만). 미설치 모델은 확인 후 pull.
  전제 : ollama 서버 동작 중(권장: 3090 고정 serve), ollama CLI in PATH
  롤백 : ollama stop <model> 로 unload / ollama rm <model> 로 삭제 가능
  비고 : 임베딩 모델은 설치 확인된 qwen3-embedding:4b (2.5GB, api-routing embed pref[0]) 기본값
  주의 : 모델 태그는 mutable spec — 쓰기 전 `ollama ls` / docs/API_ROUTING_SPEC.md
         의 installed allowlist와 대조하고 banned 태그는 alias 매핑을 따를 것.
#>
[CmdletBinding(SupportsShouldProcess)]
param(
  [string[]]$ChatModels = @('qwen3.5:9b'),     # 영상에서 확인된 유일한 경량 모델
  [string]$EmbeddingModel = 'qwen3-embedding:4b',   # ollama ls 실측 태그 (2.5GB)
  [int]$Port = 11434,
  [string]$KeepAlive = '60m',
  [switch]$Force
)
$ErrorActionPreference = 'Stop'

$installed = (ollama list 2>$null) -join "`n"
$want = @($ChatModels) + @($EmbeddingModel | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

foreach ($m in $want) {
  if ($installed -notmatch [regex]::Escape(($m -split ':')[0])) {
    $a = if ($Force) { 'y' } else { Read-Host "'$m' 미설치 — pull 할까? (y/N)" }
    if ($a -match '^(y|yes)$' -and $PSCmdlet.ShouldProcess($m,'ollama pull')) {
      $env:OLLAMA_HOST = "127.0.0.1:$Port"; ollama pull $m
    } else { continue }
  }
  if ($PSCmdlet.ShouldProcess($m, "warm-up keep_alive=$KeepAlive @ $Port")) {
    # Embedding-only models reject /api/generate; warm them via /api/embed.
    $warmUri = "http://127.0.0.1:$Port/api/generate"
    $warmBody = @{ model=$m; prompt='ping'; stream=$false; keep_alive=$KeepAlive
                   options=@{ num_predict=1 } }
    if ($m -eq $EmbeddingModel) {
      $warmUri = "http://127.0.0.1:$Port/api/embed"
      $warmBody = @{ model=$m; input='ping'; keep_alive=$KeepAlive }
    }
    Invoke-RestMethod -Method Post -Uri $warmUri -TimeoutSec 60 `
      -ContentType 'application/json' -Body ($warmBody | ConvertTo-Json) | Out-Null
    Write-Host "warmed: $m" -ForegroundColor Green
  }
}
Write-Host "`n상주 확인:"; Invoke-RestMethod "http://127.0.0.1:$Port/api/ps" -TimeoutSec 5 |
  Select-Object -ExpandProperty models | Format-Table name,@{n='vramGB';e={[math]::Round($_.size_vram/1GB,1)}},expires_at -AutoSize
