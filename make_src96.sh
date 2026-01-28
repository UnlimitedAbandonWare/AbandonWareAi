#!/usr/bin/env bash
set -euo pipefail

ROOT="$(pwd)"
SRC="src_91"
DST="src_96"

if [ ! -d "$SRC" ]; then
  echo "ERROR: '$SRC' 폴더가 현재 디렉터리에 없습니다. (현재: $ROOT)"
  exit 1
fi

echo ">> src_91 → src_96 복사 중..."
rm -rf "$DST"
cp -a "$SRC" "$DST"

# 1) manifest 파일을 app 자원으로 포함
echo ">> 매니페스트를 app classpath로 포함..."
mkdir -p "$DST/app/src/main/resources/configs"

if [ -f "$DST/configs/models.manifest.yaml" ]; then
  cp -f "$DST/configs/models.manifest.yaml" "$DST/app/src/main/resources/configs/models.manifest.yaml"
else
  echo "WARN: '$DST/configs/models.manifest.yaml' 을 찾지 못했습니다."
  echo "      원본 위치가 다르면 수동으로 app/src/main/resources/configs/ 하위에 복사해 주세요."
fi

# 2) application.yml 수정/추가
APP_YML="$DST/app/src/main/resources/application.yml"
ADDED_BLOCK="$(cat <<'YAML'
# === Added by src96 patch ===
agent:
  models:
    path: ${AGENT_MODELS_PATH:classpath:/configs/models.manifest.yaml}

logging:
  level:
    com.example.lms.manifest: DEBUG
# === /Added ===
YAML
)"

if [ -f "$APP_YML" ]; then
  # path가 이미 있으면 갱신, 없으면 블록 추가
  if grep -qE '^\s*agent:\s*$' "$APP_YML" && grep -qE '^\s*models:\s*$' "$APP_YML"; then
    if grep -qE '^\s*path:\s*' "$APP_YML"; then
      # path 라인을 교체
      perl -0777 -i -pe 's/(agent:\s*\n(?:\s{2,}.+\n)*?\s*models:\s*\n(?:\s{4,}.+\n)*?\s*path:\s*).*\n/\1\$\{AGENT_MODELS_PATH:classpath:\/configs\/models.manifest.yaml\}\n/s' "$APP_YML" || true
      # 블록 추가 없이 끝
    else
      printf "\n%s\n" "$ADDED_BLOCK" >> "$APP_YML"
    fi
  else
    printf "\n%s\n" "$ADDED_BLOCK" >> "$APP_YML"
  fi
else
  mkdir -p "$(dirname "$APP_YML")"
  printf "%s\n" "$ADDED_BLOCK" > "$APP_YML"
fi

# 3) zip 생성
echo ">> src96.zip 생성 중..."
rm -f src96.zip
( cd "$DST/.." && zip -qr "../src96.zip" "$(basename "$DST")" )

echo "완료: $ROOT/src96.zip 생성"
