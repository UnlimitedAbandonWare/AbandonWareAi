#!/usr/bin/env bash
# ero_kpi_compare.sh
#
# 목적:
# - ERO_LOGS / 운영 로그에서 "빈도" 뿐 아니라 "연속성(연속 N회)" 과 "분당 빈도"를 같이 뽑아
#   회귀/개선 판정을 운영 대시보드에서 바로 쓰기 좋게 만든 KPI 요약 스크립트.
#
# 사용:
#   ./src/tools/ero_kpi_compare.sh ERO_LOGS.before.txt ERO_LOGS.after.txt
#   ./src/tools/ero_kpi_compare.sh ERO_LOGS.txt
#   ./src/tools/ero_kpi_compare.sh --json ERO_LOGS.txt
#
# 출력(기본):
# - 파일별 KPI 테이블(연속성/분당피크 중심)
# - (2개 입력 시) delta 테이블(변화량)
#
# NOTE
# - "연속 N회"는 기본적으로 unique rid(요청) 단위로 계산합니다.
#   (rid가 없는 로그는 streak 값이 0일 수 있습니다.)
# - "분당 빈도"는 타임스탬프(YYYY-MM-DDTHH:MM...)가 있는 라인만 대상으로 계산합니다.

set -euo pipefail

HAVE_COLUMN=false
if command -v column >/dev/null 2>&1; then
  HAVE_COLUMN=true
fi

usage() {
  cat >&2 <<'USAGE'
Usage:
  ero_kpi_compare.sh [--json] <log_before> [log_after]

Options:
  --json   JSONL(1줄=1레코드) 출력

Examples:
  ./src/tools/ero_kpi_compare.sh ERO_LOGS.txt
  ./src/tools/ero_kpi_compare.sh ERO_LOGS.before.txt ERO_LOGS.after.txt
  ./src/tools/ero_kpi_compare.sh --json ERO_LOGS.txt
USAGE
}

JSON_MODE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --json)
      JSON_MODE=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage
      exit 2
      ;;
    *)
      break
      ;;
  esac
done

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 2
fi

LOG_BEFORE="$1"
LOG_AFTER="${2:-}"

if [[ ! -f "$LOG_BEFORE" ]]; then
  echo "File not found: $LOG_BEFORE" >&2
  exit 2
fi
if [[ -n "$LOG_AFTER" && ! -f "$LOG_AFTER" ]]; then
  echo "File not found: $LOG_AFTER" >&2
  exit 2
fi

analyze_to_tsv() {
  local file="$1"
  local label="$2"

  # mawk 호환(=gawk 확장 기능(asorti 등) 비사용) 버전
  awk -v LABEL="$label" '
  BEGIN {
    # Issues (1~3순위 + 운영 핵심)
    n = split("BELOW_MIN_CITATIONS,BREAKER_OPEN_SKIP,CANCEL_OR_INTERRUPTED,KEYWORDSEL_DEGRADED_OR_BLANK,DEGRADE_EVIDENCE_LIST,NAVER_TIMEOUT,BRAVE_TIMEOUT_OR_COOLDOWN", issues, ",");
    ctxRid = "";
    ctxMinute = "";
    minutesTotal = 0;
    ridSeq = 0;
  }

  function minute_from_line(line) {
    # e.g. 2026-01-19T00:36
    # NOTE: mawk는 정규식 interval({n}) 지원이 제한적일 수 있어, 명시적 자리수 패턴으로 검사합니다.
    if (match(line, /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]/)) {
      return substr(line, 1, 16);
    }
    return "";
  }

  function extract_rid(line,   rid, tmp) {
    rid = "";

    # 1) rid=<uuid>
    if (match(line, /rid=[0-9A-Fa-f-]+/)) {
      rid = substr(line, RSTART + 4, 36);
      return rid;
    }

    # 2) rid: <uuid> (bullet/diagnostic)
    if (match(line, /rid:[[:space:]]*[0-9A-Fa-f-]+/)) {
      tmp = substr(line, RSTART, RLENGTH);
      sub(/.*rid:[[:space:]]*/, "", tmp);
      rid = substr(tmp, 1, 36);
      return rid;
    }

    # 3) fallback은 intentionally 생략합니다.
    #    (운영 로그는 대개 rid=... 또는 bullet rid: ...가 존재)
    return rid;
  }

  function ensure_rid(rid) {
    if (rid == "") return;
    if (!(rid in ridIdx)) {
      ridSeq++;
      ridIdx[rid] = ridSeq;
      ridBySeq[ridSeq] = rid;
    }
    ctxRid = rid;
  }

  function update_top3(issue, minute, c) {
    if (c <= 0) return;

    if (c > top1C[issue]) {
      top3C[issue] = top2C[issue]; top3M[issue] = top2M[issue];
      top2C[issue] = top1C[issue]; top2M[issue] = top1M[issue];
      top1C[issue] = c;           top1M[issue] = minute;
    } else if (c > top2C[issue]) {
      top3C[issue] = top2C[issue]; top3M[issue] = top2M[issue];
      top2C[issue] = c;            top2M[issue] = minute;
    } else if (c > top3C[issue]) {
      top3C[issue] = c;            top3M[issue] = minute;
    }
  }

  function finalize_minute(   i, issue, c, hit) {
    if (ctxMinute == "") return;

    for (i = 1; i <= n; i++) {
      issue = issues[i];
      c = curMinuteCnt[issue] + 0;
      hit = (c > 0) ? 1 : 0;

      # minute streak
      if (hit) {
        curMinuteStreak[issue]++;
        if (curMinuteStreak[issue] > maxMinuteStreak[issue]) {
          maxMinuteStreak[issue] = curMinuteStreak[issue];
        }
      } else {
        curMinuteStreak[issue] = 0;
      }

      # peak + top3
      if (c > maxPerMin[issue]) {
        maxPerMin[issue] = c;
        maxPerMinMinute[issue] = ctxMinute;
      }
      update_top3(issue, ctxMinute, c);

      # reset for next minute
      curMinuteCnt[issue] = 0;
    }
  }

  function mark_issue(issue, rid, minute) {
    eventsTotalAll[issue]++;

    if (rid != "") {
      ridIssue[issue SUBSEP rid] = 1;
    }

    if (minute != "") {
      eventsTotalTs[issue]++;
      curMinuteCnt[issue]++;
    }
  }

  {
    line = $0;

    # rid context
    rid = extract_rid(line);
    if (rid != "") {
      ensure_rid(rid);
    } else if (ctxRid != "") {
      rid = ctxRid;
    }

    # minute context
    minute = minute_from_line(line);
    if (minute != "") {
      if (ctxMinute == "") {
        ctxMinute = minute;
        minutesTotal = 1;
      } else if (minute != ctxMinute) {
        finalize_minute();
        ctxMinute = minute;
        minutesTotal++;
      }
    }

    # === pattern classification ===
    if (line ~ /BELOW_MIN_CITATIONS/) {
      mark_issue("BELOW_MIN_CITATIONS", rid, minute);
    }

    if (line ~ /breaker[_-]open/) {
      mark_issue("BREAKER_OPEN_SKIP", rid, minute);
    }

    if (line ~ /cancel_suppressed/ || line ~ /CancellationException/ || line ~ /InterruptedException/ || line ~ /\bINTERRUPTED\b/) {
      mark_issue("CANCEL_OR_INTERRUPTED", rid, minute);
    }

    if (line ~ /aux\.keywordSelection\.degraded/ || line ~ /keywordSelection\.mode=fallback_blank/ || (line ~ /keywordSelection/ && line ~ /(fallback_blank|degraded=true|reason=blank)/)) {
      mark_issue("KEYWORDSEL_DEGRADED_OR_BLANK", rid, minute);
    }

    if (line ~ /DEGRADE_EVIDENCE_LIST/ || line ~ /degradedToEvidence/ || line ~ /weak_draft_high_evidence/) {
      mark_issue("DEGRADE_EVIDENCE_LIST", rid, minute);
    }

    if (line ~ /\[Naver\] Hard Timeout/ || line ~ /engine\.Naver\.cause\.timeout\.count/) {
      mark_issue("NAVER_TIMEOUT", rid, minute);
    }

    if (line ~ /\[Brave\] Hard Timeout/ || line ~ /skipped\.reason[[:space:]]*cooldown/ || line ~ /skipped\.reason[\t ]cooldown/) {
      mark_issue("BRAVE_TIMEOUT_OR_COOLDOWN", rid, minute);
    }
  }

  END {
    finalize_minute();

    totalRids = ridSeq + 0;

    print "@meta\tlabel\t" LABEL;
    print "@meta\tfile\t" FILENAME;
    print "@meta\tunique_rids\t" totalRids;
    print "@meta\tminutes_with_timestamps\t" minutesTotal;

    print "@header\tissue\trid_hits\trid_rate_pct\trid_streak_max\tminute_streak_max\tevents_total_all\tevents_total_ts\tper_min_avg\tper_min_peak\tpeak_minute\ttop2_minute\ttop3_minute";

    for (i = 1; i <= n; i++) {
      issue = issues[i];

      ridHits = 0;
      cur = 0;
      maxStreak = 0;

      for (s = 1; s <= ridSeq; s++) {
        r = ridBySeq[s];
        hit = ridIssue[issue SUBSEP r];
        if (hit) {
          ridHits++;
          cur++;
          if (cur > maxStreak) maxStreak = cur;
        } else {
          cur = 0;
        }
      }

      rate = (totalRids > 0) ? (ridHits / totalRids * 100.0) : 0.0;
      perMinAvg = (minutesTotal > 0) ? ((eventsTotalTs[issue] + 0) / minutesTotal) : 0.0;

      # NOTE: mawk는 "printf( ..." 인자 목록을 여러 줄로 나누면 파싱 에러가 날 수 있어 1줄로 유지합니다.
      printf("%s\t%d\t%.2f\t%d\t%d\t%d\t%d\t%.3f\t%d\t%s\t%s\t%s\n", issue, ridHits, rate, maxStreak, (maxMinuteStreak[issue] + 0), (eventsTotalAll[issue] + 0), (eventsTotalTs[issue] + 0), perMinAvg, (maxPerMin[issue] + 0), maxPerMinMinute[issue], top2M[issue], top3M[issue]);
    }

    # === chain KPI: cancel -> breaker-open -> keywordSelection blank (rid 단위) ===
    chainIssue = "CHAIN_CANCEL_BREAKER_KEYWORDBLANK";
    chainHits = 0;
    chainCur = 0;
    chainMax = 0;

    for (s = 1; s <= ridSeq; s++) {
      r = ridBySeq[s];
      c = ridIssue["CANCEL_OR_INTERRUPTED" SUBSEP r];
      b = ridIssue["BREAKER_OPEN_SKIP" SUBSEP r];
      k = ridIssue["KEYWORDSEL_DEGRADED_OR_BLANK" SUBSEP r];
      hit = (c && b && k) ? 1 : 0;

      if (hit) {
        chainHits++;
        chainCur++;
        if (chainCur > chainMax) chainMax = chainCur;
      } else {
        chainCur = 0;
      }
    }

    chainRate = (totalRids > 0) ? (chainHits / totalRids * 100.0) : 0.0;

    printf("%s\t%d\t%.2f\t%d\t%d\t%d\t%d\t%.3f\t%d\t%s\t%s\t%s\n", chainIssue, chainHits, chainRate, chainMax, 0, chainHits, 0, 0.0, 0, "", "", "");
  }
  ' "$file"
}

print_human_report() {
  local tsv="$1"

  local label file unique_rids minutes_with_ts
  label=$(awk -F '\t' '$1=="@meta" && $2=="label" {print $3}' "$tsv" | head -n 1)
  file=$(awk -F '\t' '$1=="@meta" && $2=="file" {print $3}' "$tsv" | head -n 1)
  unique_rids=$(awk -F '\t' '$1=="@meta" && $2=="unique_rids" {print $3}' "$tsv" | head -n 1)
  minutes_with_ts=$(awk -F '\t' '$1=="@meta" && $2=="minutes_with_timestamps" {print $3}' "$tsv" | head -n 1)

  echo "=== $label ==="
  echo "file: $file"
  echo "unique_rids: $unique_rids"
  echo "minutes_with_timestamps: $minutes_with_ts"
  echo

  # header
  local table
  table=$(awk -F '\t' '
    $1=="@header" {
      print "issue\trid_hits\trid_rate_pct\trid_streak_max\tminute_streak_max\tper_min_peak\tpeak_minute\tevents_ts\tevents_all\tper_min_avg";
    }
    $1!~/^@/ && NF>=12 {
      # issue rid_hits rate rid_streak min_streak events_all events_ts per_min_avg per_min_peak peak_minute top2 top3
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", $1,$2,$3,$4,$5,$9,$10,$7,$6,$8;
    }
  ' "$tsv")

  if $HAVE_COLUMN; then
    printf '%s\n' "$table" | column -t -s $'\t'
  else
    printf '%s\n' "$table"
  fi

  echo
}

print_delta_report() {
  local before="$1"
  local after="$2"

  echo "=== DELTA (after - before) ==="
  local delta
  delta=$(awk -F '\t' '
    function is_issue_line() { return ($1 !~ /^@/ && $1 != "" && NF >= 11) }
    FNR==NR {
      if (is_issue_line()) {
        issue=$1;
        b_rid_hits[issue]=$2+0;
        b_rid_streak[issue]=$4+0;
        b_minute_streak[issue]=$5+0;
        b_per_min_peak[issue]=$9+0;
      }
      next;
    }
    {
      if (is_issue_line()) {
        issue=$1;
        a_rid_hits=$2+0;
        a_rid_streak=$4+0;
        a_minute_streak=$5+0;
        a_per_min_peak=$9+0;

        printf "%s\t%d->%d\t%+d\t%d->%d\t%+d\t%d->%d\t%+d\t%d->%d\t%+d\n",
          issue,
          (b_rid_hits[issue]+0), a_rid_hits, (a_rid_hits-(b_rid_hits[issue]+0)),
          (b_rid_streak[issue]+0), a_rid_streak, (a_rid_streak-(b_rid_streak[issue]+0)),
          (b_minute_streak[issue]+0), a_minute_streak, (a_minute_streak-(b_minute_streak[issue]+0)),
          (b_per_min_peak[issue]+0), a_per_min_peak, (a_per_min_peak-(b_per_min_peak[issue]+0));
      }
    }
  ' "$before" "$after" | awk 'BEGIN{print "issue\trid_hits(before->after)\tΔhits\trid_streak(before->after)\tΔrid_streak\tminute_streak(before->after)\tΔminute_streak\tper_min_peak(before->after)\tΔper_min_peak"} {print}')

  if $HAVE_COLUMN; then
    printf '%s\n' "$delta" | column -t -s $'\t'
  else
    printf '%s\n' "$delta"
  fi

  echo
}

emit_jsonl() {
  local tsv="$1"

  local label file unique_rids minutes_with_ts
  label=$(awk -F '\t' '$1=="@meta" && $2=="label" {print $3}' "$tsv" | head -n 1)
  file=$(awk -F '\t' '$1=="@meta" && $2=="file" {print $3}' "$tsv" | head -n 1)
  unique_rids=$(awk -F '\t' '$1=="@meta" && $2=="unique_rids" {print $3}' "$tsv" | head -n 1)
  minutes_with_ts=$(awk -F '\t' '$1=="@meta" && $2=="minutes_with_timestamps" {print $3}' "$tsv" | head -n 1)

  # meta
  printf '{"type":"meta","label":"%s","file":"%s","unique_rids":%d,"minutes_with_timestamps":%d}\n' \
    "$label" "$file" "${unique_rids:-0}" "${minutes_with_ts:-0}"

  # issues
  awk -v LABEL="$label" -F '\t' '
    $1!~/^@/ && NF>=12 {
      # issue rid_hits rate rid_streak min_streak events_all events_ts per_min_avg per_min_peak peak_minute top2 top3
      printf "{\"type\":\"issue\",\"label\":\"%s\",\"issue\":\"%s\",\"rid_hits\":%d,\"rid_rate_pct\":%.2f,\"rid_streak_max\":%d,\"minute_streak_max\":%d,\"events_total_all\":%d,\"events_total_ts\":%d,\"per_min_avg\":%.3f,\"per_min_peak\":%d,\"peak_minute\":\"%s\"}\n",
        LABEL, $1, $2+0, $3+0.0, $4+0, $5+0, $6+0, $7+0, $8+0.0, $9+0, $10;
    }
  ' "$tsv"
}

TMP_BEFORE=$(mktemp)
TMP_AFTER=$(mktemp)
cleanup() {
  rm -f "$TMP_BEFORE" "$TMP_AFTER"
}
trap cleanup EXIT

analyze_to_tsv "$LOG_BEFORE" "before:$(basename "$LOG_BEFORE")" > "$TMP_BEFORE"

if [[ -n "$LOG_AFTER" ]]; then
  analyze_to_tsv "$LOG_AFTER" "after:$(basename "$LOG_AFTER")" > "$TMP_AFTER"
fi

if $JSON_MODE; then
  emit_jsonl "$TMP_BEFORE"
  if [[ -n "$LOG_AFTER" ]]; then
    emit_jsonl "$TMP_AFTER"
  fi
  exit 0
fi

print_human_report "$TMP_BEFORE"

if [[ -n "$LOG_AFTER" ]]; then
  print_human_report "$TMP_AFTER"
  print_delta_report "$TMP_BEFORE" "$TMP_AFTER"
fi

# Hint: dashboard ingestion
cat <<'HINT'
Tip (dashboard ingestion):
  - JSONL이 필요하면: ./src/tools/ero_kpi_compare.sh --json <log> [log_after]
  - "연속 N회"는 rid 단위, "분당"은 timestamp 라인 기반입니다.
HINT
