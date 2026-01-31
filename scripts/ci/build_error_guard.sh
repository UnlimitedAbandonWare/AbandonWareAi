#!/usr/bin/env bash
# Build Error Guard (hardened)
# - Normalizes encodings/line endings
# - Strips ZWSP and other zero-width chars
# - Removes any variant of the '' placeholder (brackets optional, mixed case OK)
# - Redacts common secrets (AWS, GCP, phone, email)
# - Emits a compact frequency summary (txt + json)

set -Eeuo pipefail

IN_LOG="${1:-build.log}"
OUT_LOG="${2:-build.sanitized.log}"

mkdir -p "$(dirname "$OUT_LOG")"

# Normalize to UTF-8 and LF newlines (tolerate absence of iconv / dos2unix)
TMP_IN="${IN_LOG}.utf8"
if command -v iconv >/dev/null 2>&1; then
  iconv -f utf-8 -t utf-8 -c "$IN_LOG" > "$TMP_IN" || cp "$IN_LOG" "$TMP_IN"
else
  cp "$IN_LOG" "$TMP_IN"
fi
# Remove zero-width chars and normalize CRLF -> LF
# U+200B ZERO WIDTH SPACE, U+200C NON-JOINER, U+200D JOINER, U+FEFF BOM
if command -v perl >/dev/null 2>&1; then
  perl -CSD -pe 's/[\x{200B}\x{200C}\x{200D}\x{FEFF}]//g; s/\r\n/\n/g; s/\r/\n/g;' "$TMP_IN" > "${TMP_IN}.norm"
else
  tr -d '\r' < "$TMP_IN" > "${TMP_IN}.norm"
fi

# Strip  markers (Korean/English, with/without braces)
# Matches: , [], (), , , {}, etc.
if command -v sed >/dev/null 2>&1; then
  sed -E 's/[\{\[\(]?\s*(|[Ss][Tt][Uu][Ff][Ff]3)\s*[\}\]\)]?//g' "${TMP_IN}.norm" > "${OUT_LOG}.tmp"
else
  cp "${TMP_IN}.norm" "${OUT_LOG}.tmp"
fi

# Redact basic secrets
sed -E   -e 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/[email]/g'   -e 's/\b\+?\d{2,3}[- ]?\d{3,4}[- ]?\d{4}\b/[phone]/g'   -e 's/AKIA[0-9A-Z]{16}/[aws_key]/g'   -e 's/AIza[0-9A-Za-z\-_]{35}/[gcp_key]/g'   "${OUT_LOG}.tmp" > "$OUT_LOG"

# Build summary
SUM_TXT="${OUT_LOG%.log}.summary.txt"
SUM_JSON="${OUT_LOG%.log}.summary.json"

awk '{
  line=tolower($0);
  if (index(line,"error:")||index(line,"exception")||index(line,"failed")||index(line,"cannot find symbol")||index(line,"does not exist"))
    freq[line]++;
} END {
  for (k in freq) printf "%06d %s\n", freq[k], k;
}' "$OUT_LOG" | sort -r > "$SUM_TXT"

# Convert to JSON using embedded Python (no external deps)
python3 - <<'PY' "$SUM_TXT" "$SUM_JSON"
import sys, json, os
txt_path, out_path = sys.argv[1], sys.argv[2]
agg=[]
if os.path.exists(txt_path):
    with open(txt_path,'r',encoding='utf-8') as f:
        for line in f:
            line=line.rstrip('\n')
            parts=line.split(' ',1)
            try:
                cnt=int(parts[0])
            except:
                continue
            key=parts[1] if len(parts)>1 else ''
            agg.append({'count':cnt,'key':key})
with open(out_path,'w',encoding='utf-8') as f:
    json.dump({'summary':agg}, f, ensure_ascii=False, indent=2)
PY

echo "[build_error_guard] wrote: $OUT_LOG, $SUM_TXT, $SUM_JSON"
