Param()
$ErrorActionPreference = "Stop"

$ROOT = Get-Location
$SRC = "src_91"
$DST = "src_96"

if (-not (Test-Path $SRC -PathType Container)) {
  Write-Error "ERROR: '$SRC' 폴더가 현재 디렉터리에 없습니다. (현재: $ROOT)"
}

Write-Host ">> src_91 → src_96 복사 중..."
if (Test-Path $DST) { Remove-Item -Recurse -Force $DST }
Copy-Item $SRC -Destination $DST -Recurse -Force

# 1) manifest 파일을 app 자원으로 포함
Write-Host ">> 매니페스트를 app classpath로 포함..."
New-Item -ItemType Directory -Force -Path "$DST/app/src/main/resources/configs" | Out-Null

if (Test-Path "$DST/configs/models.manifest.yaml" -PathType Leaf) {
  Copy-Item "$DST/configs/models.manifest.yaml" "$DST/app/src/main/resources/configs/models.manifest.yaml" -Force
} else {
  Write-Warning "WARN: '$DST/configs/models.manifest.yaml' 을 찾지 못했습니다.`n      원본 위치가 다르면 수동으로 app/src/main/resources/configs/ 하위에 복사해 주세요."
}

# 2) application.yml 수정/추가
$APP_YML = "$DST/app/src/main/resources/application.yml"
$ADDED_BLOCK = @"
# === Added by src96 patch ===
agent:
  models:
    path: ${AGENT_MODELS_PATH:classpath:/configs/models.manifest.yaml}

logging:
  level:
    com.example.lms.manifest: DEBUG
# === /Added ===
"@

if (Test-Path $APP_YML -PathType Leaf) {
  $content = Get-Content $APP_YML -Raw

  if ($content -match "^\s*agent:\s*$" -and $content -match "^\s*models:\s*$") {
    if ($content -match "^\s*path:\s*" ) {
      # path 라인 교체 (단순 치환)
      $content = [regex]::Replace($content, "(agent:\s*\r?\n(?:\s{2,}.+\r?\n)*?\s*models:\s*\r?\n(?:\s{4,}.+\r?\n)*?\s*path:\s*).*\r?\n", "`${1}\${AGENT_MODELS_PATH:classpath:/configs/models.manifest.yaml}`r`n", "Singleline")
      Set-Content -LiteralPath $APP_YML -Value $content -Encoding UTF8
    } else {
      Add-Content -LiteralPath $APP_YML -Value "`r`n$ADDED_BLOCK" -Encoding UTF8
    }
  } else {
    Add-Content -LiteralPath $APP_YML -Value "`r`n$ADDED_BLOCK" -Encoding UTF8
  }
} else {
  New-Item -ItemType Directory -Force -Path (Split-Path $APP_YML) | Out-Null
  Set-Content -LiteralPath $APP_YML -Value $ADDED_BLOCK -Encoding UTF8
}

# 3) zip 생성
Write-Host ">> src96.zip 생성 중..."
if (Test-Path "src96.zip") { Remove-Item "src96.zip" -Force }
Compress-Archive -Path "$DST" -DestinationPath "src96.zip"

Write-Host "완료: $ROOT\src96.zip 생성"
