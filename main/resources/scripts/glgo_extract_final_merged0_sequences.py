#!/usr/bin/env python3
"""GLGO / service logs: extract *final* merged=0 cases and generate per-case sequences.

Why this exists:
- Cold-start logs have noise; we want deterministic case extraction.
- Focus on *true terminal* merged=0 (no later merged>0 for the same rid).

It parses common patterns (pre/post patch):
- Hybrid merged counts: "Korean search merged: ... merged=N"
- strictDomainRequired=true
- Brave budget_exhausted(raw=0ms)
- Brave 429 / COOLDOWN and remainingMs
- Backup query / remergeOnce / cache-only / completion-poll
- Nova HybridEmptyFallbackAspect "no rescue" classification (class=...)

Outputs:
- merged0_cases.csv : summary classification table
- seq_<rid>.md      : per-case sequence timeline

Usage:
  python3 glgo_extract_final_merged0_sequences.py --log GLGO.txt --out ./out
  python3 glgo_extract_final_merged0_sequences.py --log *.txt --out ./out --max-cases 200

Notes:
- If your logs include a TraceStore dump containing `end.classification`, this script will use it.
- Otherwise it computes an `end.classification` from observed signals.
"""

from __future__ import annotations

import argparse
import csv
import dataclasses
import json
import glob
import os
import re
import sys
sys.dont_write_bytecode = True  # keep repo clean (no __pycache__)
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple


# NOTE: Don't use a "generic UUID fallback" for rid detection.
# Coldstart logs contain many unrelated UUIDs (traceIds, connectionIds, etc.)
# which will explode false-positive cases.
BRACKET_CTX_RE = re.compile(r"\[(?P<session>[^\] ]+)\s+(?P<rid>[0-9a-fA-F\-]{36})\]")
RID_KV_RE = re.compile(r"\brid=(?P<rid>[0-9a-fA-F\-]{36})\b")
SESSION_KV_RE = re.compile(r"\bsessionId=(?P<sid>[^\s]+)\b")
TS_RE = re.compile(r"^(?P<ts>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+\d{4})\b")

# Hybrid
MERGED_RE = re.compile(r"\bmerged=(?P<merged>\d+)\b")
HYBRID_COUNTS_RE = re.compile(
    r"\[Hybrid\]\s+Korean search merged:\s*brave=(?P<brave>\d+),\s*naver=(?P<naver>\d+),\s*merged=(?P<merged>\d+)"
)
HYBRID_COUNTS_ALT_RE = re.compile(
    r"\bKorean search merged:\s*brave=(?P<brave>\d+),\s*naver=(?P<naver>\d+),\s*merged=(?P<merged>\d+)"
)
STRICT_DOMAIN_RE = re.compile(r"strictDomainRequired=true")
FILTER_EMPTY_RE = re.compile(r"No merged results after filtering, returning empty list")

# Brave cooldown / 429
BRAVE_META_RE = re.compile(
    r"Brave meta:\s*status=(?P<status>[A-Z0-9_]+)"
    r".*?cooldownMs=(?P<cd>\d+)"
    r"(?:.*?retryAfterMs=(?P<ra>\d+))?"
    r"(?:.*?elapsedMs=(?P<elapsed>\d+))?"
)
BRAVE_COOLDOWN_REMAIN_RE = re.compile(r"Brave is cooling down \((?P<remain>\d+)ms remaining\)")
HTTP_429_RE = re.compile(r"\bHTTP_429\b|\bstatus=HTTP_429\b|\b429\b")

# Budget exhausted raw=0ms
BUDGET_EXHAUSTED_RE = re.compile(r"budget_exhausted\s+raw=0ms")

# Fallback ladder breadcrumbs (pre/post)
# Support both the explicit marker and the Korean "Soak" breadcrumb.
BACKUP_QUERY_RE = re.compile(r"\[WEBSEARCH_BACKUP_QUERY\]|Soak\s+수세식\s+발동")
REMERGE_ONCE_RE = re.compile(r"\[WEBSEARCH_REMERGE_ONCE\]")
CACHE_ONLY_RE = re.compile(r"cache[- ]only", re.IGNORECASE)
COMPLETION_POLL_RE = re.compile(r"completion[- ]poll", re.IGNORECASE)

# Applied fallback results (these MUST update the "effective merged" to avoid false terminal merged=0)
APPLIED_COMPLETION_POLL_RE = re.compile(r"applied completion-poll fallback", re.IGNORECASE)
CACHE_ONLY_RESCUE_HIT_RE = re.compile(r"cache-only rescue hit", re.IGNORECASE)

# Nova no-rescue classification log (post patch)
NO_RESCUE_RE = re.compile(r"Hybrid websearch empty -> no rescue \(class=(?P<class>[a-zA-Z0-9_\-]+)")

# TraceStore dump: best-effort parse for end.classification in JSON-ish dumps
END_CLASS_RE = re.compile(r"\bend\.classification\b\s*[=:]\s*(?P<val>[^,\s\}\]]+)")


@dataclass
class Event:
    ts: str
    kind: str
    detail: str
    line_no: int


@dataclass
class Case:
    rid: str
    session: str = ""
    last_ts: str = ""
    last_line_no: int = 0

    # "raw" merged (Hybrid provider log)
    last_merged: Optional[int] = None
    last_merged_ts: str = ""
    last_brave: Optional[int] = None
    last_naver: Optional[int] = None

    # Track whether we ever had raw hits within this rid.
    raw_hits_max: int = 0
    raw_hits_nonzero_count: int = 0

    # "effective" merged (raw merged OR applied fallbacks). This is what we use
    # to decide true terminal merged=0.
    last_effective_merged: Optional[int] = None
    last_effective_merged_ts: str = ""
    last_effective_merged_line_no: int = 0

    # Signals
    strict_domain_count: int = 0
    budget_exhausted_raw0_count: int = 0
    filter_empty_count: int = 0

    brave_429_count: int = 0
    brave_cooldown_meta_count: int = 0
    brave_cooldown_remaining_ms_min: Optional[int] = None
    brave_cooldown_started_ts: Optional[str] = None
    brave_retry_after_ms_min: Optional[int] = None
    brave_retry_after_ms_max: Optional[int] = None
    brave_elapsed_ms_min: Optional[int] = None
    brave_elapsed0_count: int = 0

    backup_query_count: int = 0
    remerge_once_count: int = 0
    cache_only_mentions: int = 0
    completion_poll_mentions: int = 0

    # Classification
    end_classification: Optional[str] = None
    no_rescue_class: Optional[str] = None

    # Sequence events (selected)
    events: List[Event] = field(default_factory=list)

    def add_event(self, ts: str, kind: str, detail: str, line_no: int) -> None:
        self.events.append(Event(ts=ts, kind=kind, detail=detail, line_no=line_no))
        self.last_ts = ts or self.last_ts
        self.last_line_no = line_no

    def update_effective_merged(self, merged: int, ts: str, line_no: int) -> None:
        # Prefer the last seen (by line number) as the effective terminal output.
        if line_no >= (self.last_effective_merged_line_no or 0):
            self.last_effective_merged = merged
            self.last_effective_merged_ts = ts
            self.last_effective_merged_line_no = line_no


def iter_lines(paths: List[Path]) -> Iterable[Tuple[Path, int, str]]:
    for p in paths:
        with p.open('r', encoding='utf-8', errors='replace') as f:
            for i, line in enumerate(f, start=1):
                yield p, i, line.rstrip('\n')


def extract_ts(line: str) -> str:
    m = TS_RE.match(line)
    return m.group('ts') if m else ""


def extract_ctx(line: str) -> Tuple[Optional[str], Optional[str]]:
    # Prefer bracket context: [session rid]
    m = BRACKET_CTX_RE.search(line)
    if m:
        return m.group('rid'), m.group('session')

    rid = None
    session = None

    m2 = RID_KV_RE.search(line)
    if m2:
        rid = m2.group('rid')

    m3 = SESSION_KV_RE.search(line)
    if m3:
        session = m3.group('sid')

    if rid:
        return rid, session

    return None, None


def safe_shorten(s: str, limit: int = 260) -> str:
    s = s.strip()
    if len(s) <= limit:
        return s
    return s[:limit - 3] + "..."

def end_classification_source(c: Case) -> str:
    """Where `end.classification` came from (trust signal for ops)."""
    if c.no_rescue_class:
        return "no_rescue_class"
    if c.end_classification:
        return "end.classification"
    return "heuristic"


def fast_strict_vs_true0hit_verdict(c: Case) -> str:
    """Metrics-only quick判정 for ops triage (no heuristics).

    - strictDomain starvation signal:
        rawHits.max > 0 AND filterEmptyAfterFiltering.count > 0 AND strictDomainRequired.count > 0
    - true 0-hit signal:
        rawHits.max == 0 AND filterEmptyAfterFiltering.count == 0

    This does NOT replace end.classification; it's a fast sanity check.
    """
    if c.raw_hits_max == 0 and c.filter_empty_count == 0:
        return "true_0_hit_signal"
    if c.raw_hits_max > 0 and c.filter_empty_count > 0 and c.strict_domain_count > 0:
        return "strictDomain_starvation_signal"
    if c.raw_hits_max > 0 and c.filter_empty_count > 0 and c.strict_domain_count == 0:
        return "filter_starvation_signal"
    return "other"


def fast_strict_domain_starvation_signal(c: Case) -> bool:
    return fast_strict_vs_true0hit_verdict(c) == "strictDomain_starvation_signal"


def fast_true_0_hit_signal(c: Case) -> bool:
    return fast_strict_vs_true0hit_verdict(c) == "true_0_hit_signal"



def compute_end_classification(c: Case) -> str:
    # Prefer explicit no-rescue class (post patch)
    if c.no_rescue_class:
        return f"merged0.{c.no_rescue_class}"

    # If we got an explicit end.classification from logs/dumps
    if c.end_classification:
        return c.end_classification

    # Heuristic (pre patch): derive from *observed* signals.
    # Priority mirrors the runtime classifier in HybridWebSearchEmptyFallbackAspect.
    if c.budget_exhausted_raw0_count > 0:
        return "merged0.budget_exhausted_raw0"

    if c.brave_429_count > 0 or c.brave_cooldown_meta_count > 0 or c.brave_cooldown_remaining_ms_min is not None:
        return "merged0.rate_limit_or_cooldown"

    # strictDomain starvation signal requires BOTH:
    # - strictDomainRequired signal
    # - filter-empty signal OR raw hits present but merged=0
    last_raw_hits = (c.last_brave or 0) + (c.last_naver or 0)
    if c.strict_domain_count > 0 and (c.filter_empty_count > 0 or (last_raw_hits > 0 and (c.last_merged or 0) == 0)):
        return "merged0.strict_filter_starve"

    if c.filter_empty_count > 0 and last_raw_hits > 0 and (c.last_merged or 0) == 0:
        return "merged0.starvation"

    # Default
    return "merged0.zero_hit"


def compute_refined_classification(c: Case) -> str:
    """More granular bucket for postmortem.

    Goal: quickly distinguish
      - strictDomain starvation (signal yes/no)
      - rate-limit/cooldown
      - budget_exhausted(raw0)
      - true 0-hit
    """
    base = compute_end_classification(c)
    if not base.startswith('merged0.'):
        return base
    clazz = base[len('merged0.'):]

    if clazz == 'strict_filter_starve':
        return 'merged0.strictDomain_starvation' if c.strict_domain_count > 0 else 'merged0.strictFilter_starvation'

    if clazz == 'budget_exhausted_raw0':
        return base

    if clazz == 'rate_limit_or_cooldown':
        return base

    if clazz == 'zero_hit':
        # True 0-hit: no raw hits ever observed and no filter-empty breadcrumbs.
        if c.raw_hits_max == 0 and c.filter_empty_count == 0:
            return 'merged0.true_zero_hit'
        return 'merged0.zero_hit_or_unknown'

    if clazz == 'starvation':
        if c.raw_hits_max > 0:
            return 'merged0.filter_starvation'
        return base

    return base


def is_true_terminal_merged0(c: Case) -> bool:
    # Must be a websearch-related case
    if len(c.events) == 0 and c.last_merged is None and c.last_effective_merged is None:
        return False

    # If we got an explicit end.classification that says "not merged0", it's not terminal merged=0.
    if c.end_classification and not str(c.end_classification).startswith("merged0"):
        return False

    # Prefer effective merged (includes applied fallbacks).
    if c.last_effective_merged is not None:
        return c.last_effective_merged == 0

    # Otherwise fall back to raw merged.
    if c.last_merged is not None:
        return c.last_merged == 0

    # As last resort, rely on computed classification.
    return (compute_end_classification(c)).startswith("merged0")


def write_cases_csv(out_dir: Path, cases: List[Case]) -> Path:
    out_path = out_dir / 'merged0_cases.csv'
    with out_path.open('w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow([
            'rid',
            'sessionId',
            'lastTs',
            'lastMerged',
            'lastMergedTs',
            'lastBrave',
            'lastNaver',
            'lastEffectiveMerged',
            'lastEffectiveMergedTs',
            'end.classification',
            'end.classification.refined',
            'end.classification.source',
            'fast.strictDomain_starvation.signal',
            'fast.true_0_hit.signal',
            'fast.strict_vs_true0hit.verdict',
            'rawHits.max',
            'rawHits.nonzero.count',
            'strictDomainRequired.count',
            'filterEmptyAfterFiltering.count',
            'budget_exhausted_raw0.count',
            'brave.429.count',
            'brave.cooldown.meta.count',
            'brave.cooldown.remainingMs.min',
            'brave.cooldown.startedTs',
            'brave.retryAfterMs.min',
            'brave.retryAfterMs.max',
            'brave.elapsedMs.min',
            'brave.elapsedMs0.count',
            'backupQuery.count',
            'remergeOnce.count',
            'cacheOnly.mentions',
            'completionPoll.mentions',
            'events.count',
        ])
        for c in cases:
            endc = compute_end_classification(c)
            endc_refined = compute_refined_classification(c)
            w.writerow([
                c.rid,
                c.session,
                c.last_ts,
                c.last_merged if c.last_merged is not None else '',
                c.last_merged_ts,
                c.last_brave if c.last_brave is not None else '',
                c.last_naver if c.last_naver is not None else '',
                c.last_effective_merged if c.last_effective_merged is not None else '',
                c.last_effective_merged_ts,
                endc,
                endc_refined,
                end_classification_source(c),
                fast_strict_domain_starvation_signal(c),
                fast_true_0_hit_signal(c),
                fast_strict_vs_true0hit_verdict(c),
                c.raw_hits_max,
                c.raw_hits_nonzero_count,
                c.strict_domain_count,
                c.filter_empty_count,
                c.budget_exhausted_raw0_count,
                c.brave_429_count,
                c.brave_cooldown_meta_count,
                c.brave_cooldown_remaining_ms_min if c.brave_cooldown_remaining_ms_min is not None else '',
                c.brave_cooldown_started_ts or '',
                c.brave_retry_after_ms_min if c.brave_retry_after_ms_min is not None else '',
                c.brave_retry_after_ms_max if c.brave_retry_after_ms_max is not None else '',
                c.brave_elapsed_ms_min if c.brave_elapsed_ms_min is not None else '',
                c.brave_elapsed0_count,
                c.backup_query_count,
                c.remerge_once_count,
                c.cache_only_mentions,
                c.completion_poll_mentions,
                len(c.events),
            ])
    return out_path


def write_case_sequence_md(out_dir: Path, c: Case) -> Path:
    out_path = out_dir / f"seq_{c.rid}.md"
    endc = compute_end_classification(c)
    endc_refined = compute_refined_classification(c)

    def bullet(k: str, v: object) -> str:
        return f"- {k}: {v}\n"

    with out_path.open('w', encoding='utf-8') as f:
        f.write(f"# rid={c.rid} sessionId={c.session}\n\n")
        f.write(bullet('end.classification', endc))
        f.write(bullet('end.classification.refined', endc_refined))
        f.write(bullet('lastMerged', c.last_merged))
        f.write(bullet('lastMergedTs', c.last_merged_ts))
        f.write(bullet('lastBrave', c.last_brave))
        f.write(bullet('lastNaver', c.last_naver))
        f.write(bullet('rawHits.max', c.raw_hits_max))
        f.write(bullet('rawHits.nonzero.count', c.raw_hits_nonzero_count))
        f.write(bullet('lastEffectiveMerged', c.last_effective_merged))
        f.write(bullet('lastEffectiveMergedTs', c.last_effective_merged_ts))
        f.write("\n## Signals\n")
        f.write(bullet('strictDomainRequired.count', c.strict_domain_count))
        f.write(bullet('filterEmptyAfterFiltering.count', c.filter_empty_count))
        f.write(bullet('budget_exhausted(raw=0ms).count', c.budget_exhausted_raw0_count))
        f.write(bullet('brave.429.count', c.brave_429_count))
        f.write(bullet('brave.cooldown.meta.count', c.brave_cooldown_meta_count))
        f.write(bullet('brave.cooldown.remainingMs.min', c.brave_cooldown_remaining_ms_min))
        f.write(bullet('brave.cooldown.startedTs', c.brave_cooldown_started_ts))
        f.write(bullet('brave.retryAfterMs.min', c.brave_retry_after_ms_min))
        f.write(bullet('brave.retryAfterMs.max', c.brave_retry_after_ms_max))
        f.write(bullet('brave.elapsedMs.min', c.brave_elapsed_ms_min))
        f.write(bullet('brave.elapsedMs0.count', c.brave_elapsed0_count))
        f.write(bullet('backupQuery.count', c.backup_query_count))
        f.write(bullet('remergeOnce.count', c.remerge_once_count))
        f.write(bullet('cacheOnly.mentions', c.cache_only_mentions))
        f.write(bullet('completionPoll.mentions', c.completion_poll_mentions))

        f.write("\n## Repro Tree (Sequence)\n")
        f.write("- WebSearch / Signals\n")
        for e in sorted(c.events, key=lambda x: (x.ts, x.line_no)):
            if e.kind in {'HYBRID_COUNTS', 'FILTER_EMPTY', 'STRICT_DOMAIN', 'BUDGET_EXHAUSTED_RAW0',
                          'BRAVE_META', 'BRAVE_COOLDOWN_REMAIN', 'END_CLASSIFICATION', 'NO_RESCUE'}:
                ts = e.ts or "(no-ts)"
                f.write(f"  - {ts} [{e.kind}] {e.detail} (L{e.line_no})\n")

        f.write("- Stepdown / Fallback ladder\n")
        for e in sorted(c.events, key=lambda x: (x.ts, x.line_no)):
            if e.kind in {'BACKUP_QUERY', 'REMERGE_ONCE', 'APPLIED_COMPLETION_POLL', 'CACHE_ONLY_RESCUE_HIT'}:
                ts = e.ts or "(no-ts)"
                f.write(f"  - {ts} [{e.kind}] {e.detail} (L{e.line_no})\n")

        f.write("\n## Timeline (selected)\n")
        for e in sorted(c.events, key=lambda x: (x.ts, x.line_no)):
            ts = e.ts or "(no-ts)"
            f.write(f"- {ts} [{e.kind}] {e.detail} (L{e.line_no})\n")

    return out_path


def _sorted_counts(d: Dict[str, int]) -> List[Tuple[str, int]]:
    return sorted(d.items(), key=lambda kv: (-kv[1], kv[0]))


def summarize_refined_counts(cases: List[Case]) -> List[Tuple[str, int]]:
    counts: Dict[str, int] = {}
    for c in cases:
        k = compute_refined_classification(c)
        counts[k] = counts.get(k, 0) + 1
    return _sorted_counts(counts)


def summarize_fast_verdict_counts(cases: List[Case]) -> List[Tuple[str, int]]:
    counts: Dict[str, int] = {}
    for c in cases:
        k = fast_strict_vs_true0hit_verdict(c)
        counts[k] = counts.get(k, 0) + 1
    return _sorted_counts(counts)


def write_report_md(out_dir: Path, log_paths: List[Path], cases: List[Case]) -> Path:
    """Ops-friendly report (single entrypoint file)."""
    out_path = out_dir / 'merged0_report.md'
    refined_counts = summarize_refined_counts(cases)
    fast_counts = summarize_fast_verdict_counts(cases)
    heuristic_cnt = sum(1 for c in cases if end_classification_source(c) == 'heuristic')

    with out_path.open('w', encoding='utf-8') as f:
        f.write("# merged=0 terminal cases report\n\n")
        f.write("## Inputs\n")
        f.write(f"- logFiles.count: {len(log_paths)}\n")
        for lp in log_paths:
            f.write(f"  - {lp}\n")
        f.write(f"- cases.count: {len(cases)}\n")
        f.write(f"- cases.heuristicClassification.count: {heuristic_cnt}\n")

        f.write("\n## Outputs\n")
        f.write("- merged0_cases.csv\n")
        f.write("- merged0_index.json\n")
        f.write("- merged0_report.md\n")
        f.write("- seq_<rid>.md (per-case repro tree)\n")

        f.write("\n## Counts: end.classification.refined\n")
        if not refined_counts:
            f.write("- (none)\n")
        for k, v in refined_counts:
            f.write(f"- {k}: {v}\n")

        f.write("\n## Fast check: strictDomain vs true 0-hit (metrics-only)\n")
        f.write("- strictDomain_starvation: rawHits.max>0 AND filterEmptyAfterFiltering.count>0 AND strictDomainRequired.count>0\n")
        f.write("- true_0_hit: rawHits.max==0 AND filterEmptyAfterFiltering.count==0\n\n")
        for k, v in fast_counts:
            f.write(f"- fast.strict_vs_true0hit.verdict={k}: {v}\n")

        f.write("\n## Next actions (ops)\n")
        f.write("- merged0_cases.csv로 케이스별 분류표 확인\n")
        f.write("- seq_<rid>.md로 재현 트리(Sequence) 확인\n")
        f.write("- strictDomain vs true 0-hit 빠른 판정:\n")
        f.write("  - rawHits.max + filterEmptyAfterFiltering.count + strictDomainRequired.count 조합\n")
        f.write("  - 또는 fast.* columns (csv)\n")

        f.write("\n## Case index\n")
        f.write("| lastTs | sessionId | rid | end.classification.refined | source | rawHits.max | strictDomainRequired.count | filterEmptyAfterFiltering.count | fast.strict_vs_true0hit.verdict |\n")
        f.write("|---|---|---|---|---|---:|---:|---:|---|\n")
        for c in cases:
            rid_link = f"[{c.rid}](seq_{c.rid}.md)"
            f.write(
                f"| {c.last_ts} | {c.session} | {rid_link} | {compute_refined_classification(c)} | {end_classification_source(c)} | {c.raw_hits_max} | {c.strict_domain_count} | {c.filter_empty_count} | {fast_strict_vs_true0hit_verdict(c)} |\n"
            )

    return out_path



def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--log', action='append', required=True, help='Path to log file (repeatable). Wildcards ok.')
    ap.add_argument('--out', required=True, help='Output directory')
    ap.add_argument('--max-cases', type=int, default=500, help='Safety cap on number of merged0 cases to write')
    ap.add_argument('--rid', action='append', help='Filter by rid (repeatable). If set, only these rids are kept.')

    args = ap.parse_args(argv)

    # Expand globs
    log_paths: List[Path] = []
    for raw in args.log:
        if any(ch in raw for ch in '*?['):
            expanded = [Path(x) for x in sorted(glob.glob(raw))]
        else:
            expanded = [Path(raw)]
        log_paths.extend(expanded)

    log_paths = [p for p in log_paths if p.exists() and p.is_file()]
    if not log_paths:
        print('No valid log files found.', file=sys.stderr)
        return 2

    rid_filter = set(args.rid or [])

    cases: Dict[str, Case] = {}

    for p, line_no, line in iter_lines(log_paths):
        rid, session = extract_ctx(line)
        if not rid:
            continue
        if rid_filter and rid not in rid_filter:
            continue

        c = cases.get(rid)
        if c is None:
            c = Case(rid=rid, session=session or '')
            cases[rid] = c
        elif session:
            # Prefer chat-* sessions if present.
            if (not c.session) or (session.startswith('chat-') and not str(c.session).startswith('chat-')):
                c.session = session

        ts = extract_ts(line)
        c.last_ts = ts or c.last_ts
        c.last_line_no = max(c.last_line_no, line_no)

        # 1) end.classification in dumps/logs (best-effort)
        if 'end.classification' in line:
            m_end = END_CLASS_RE.search(line)
            if m_end:
                # Strip quotes/brackets
                val = m_end.group('val').strip().strip('"').strip("'")
                c.end_classification = val
                c.add_event(ts, 'END_CLASSIFICATION', f"end.classification={val}", line_no)

        # 2) No-rescue class (post patch)
        m_nr = NO_RESCUE_RE.search(line)
        if m_nr:
            clazz = m_nr.group('class')
            c.no_rescue_class = clazz
            c.add_event(ts, 'NO_RESCUE', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        # 3) Hybrid merged counts (brave/naver/merged)
        m_hc = HYBRID_COUNTS_RE.search(line) or HYBRID_COUNTS_ALT_RE.search(line)
        if m_hc:
            brave_cnt = int(m_hc.group('brave'))
            naver_cnt = int(m_hc.group('naver'))
            merged = int(m_hc.group('merged'))
            raw_hits = brave_cnt + naver_cnt
            c.raw_hits_max = max(c.raw_hits_max or 0, raw_hits)
            if raw_hits > 0:
                c.raw_hits_nonzero_count += 1
            c.last_brave = brave_cnt
            c.last_naver = naver_cnt
            c.last_merged = merged
            c.last_merged_ts = ts
            c.update_effective_merged(merged, ts, line_no)
            c.add_event(ts, 'HYBRID_COUNTS', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        # 4) strictDomainRequired
        if STRICT_DOMAIN_RE.search(line):
            c.strict_domain_count += 1
            c.add_event(ts, 'STRICT_DOMAIN', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        # 4b) filter empty after filtering
        if FILTER_EMPTY_RE.search(line):
            c.filter_empty_count += 1
            c.add_event(ts, 'FILTER_EMPTY', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        # 5) budget_exhausted raw=0ms
        if BUDGET_EXHAUSTED_RE.search(line):
            c.budget_exhausted_raw0_count += 1
            c.add_event(ts, 'BUDGET_EXHAUSTED_RAW0', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        # 6) Brave meta (429/cooldown)
        m_bm = BRAVE_META_RE.search(line)
        if m_bm:
            status = m_bm.group('status')
            cd = int(m_bm.group('cd'))
            ra = m_bm.group('ra')
            elapsed = m_bm.group('elapsed')
            if status == 'HTTP_429':
                c.brave_429_count += 1
            if status == 'COOLDOWN':
                c.brave_cooldown_meta_count += 1
                if c.brave_cooldown_started_ts is None:
                    c.brave_cooldown_started_ts = ts
            if ra is not None:
                ra_i = int(ra)
                c.brave_retry_after_ms_min = ra_i if c.brave_retry_after_ms_min is None else min(c.brave_retry_after_ms_min, ra_i)
                c.brave_retry_after_ms_max = ra_i if c.brave_retry_after_ms_max is None else max(c.brave_retry_after_ms_max, ra_i)
            if elapsed is not None:
                el_i = int(elapsed)
                c.brave_elapsed_ms_min = el_i if c.brave_elapsed_ms_min is None else min(c.brave_elapsed_ms_min, el_i)
                if el_i == 0:
                    c.brave_elapsed0_count += 1
                # Derive a "remaining" budget window when possible.
                remain = max(cd - el_i, 0)
                if c.brave_cooldown_remaining_ms_min is None:
                    c.brave_cooldown_remaining_ms_min = remain
                else:
                    c.brave_cooldown_remaining_ms_min = min(c.brave_cooldown_remaining_ms_min, remain)
            c.add_event(ts, 'BRAVE_META', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        m_rem = BRAVE_COOLDOWN_REMAIN_RE.search(line)
        if m_rem:
            remain = int(m_rem.group('remain'))
            if c.brave_cooldown_remaining_ms_min is None:
                c.brave_cooldown_remaining_ms_min = remain
            else:
                c.brave_cooldown_remaining_ms_min = min(c.brave_cooldown_remaining_ms_min, remain)
            c.add_event(ts, 'BRAVE_COOLDOWN_REMAIN', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        # 6b) Applied fallbacks update effective merged
        if APPLIED_COMPLETION_POLL_RE.search(line):
            m = MERGED_RE.search(line)
            if m:
                merged = int(m.group('merged'))
                c.update_effective_merged(merged, ts, line_no)
            c.add_event(ts, 'APPLIED_COMPLETION_POLL', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        if CACHE_ONLY_RESCUE_HIT_RE.search(line):
            m = MERGED_RE.search(line)
            if m:
                merged = int(m.group('merged'))
                c.update_effective_merged(merged, ts, line_no)
            c.add_event(ts, 'CACHE_ONLY_RESCUE_HIT', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        # 7) Fallback ladder breadcrumbs
        if BACKUP_QUERY_RE.search(line):
            c.backup_query_count += 1
            c.add_event(ts, 'BACKUP_QUERY', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        if REMERGE_ONCE_RE.search(line):
            c.remerge_once_count += 1
            c.add_event(ts, 'REMERGE_ONCE', safe_shorten(line.split(' - ', 1)[-1]), line_no)

        if CACHE_ONLY_RE.search(line):
            c.cache_only_mentions += 1

        if COMPLETION_POLL_RE.search(line):
            c.completion_poll_mentions += 1

    # Filter to true terminal merged=0
    merged0_cases = [c for c in cases.values() if is_true_terminal_merged0(c)]

    # Sort by last timestamp (desc)
    merged0_cases.sort(key=lambda x: (x.last_ts, x.last_line_no), reverse=True)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Cap
    merged0_cases = merged0_cases[: max(1, args.max_cases)]

    csv_path = write_cases_csv(out_dir, merged0_cases)

    # Per-case sequence
    for c in merged0_cases:
        write_case_sequence_md(out_dir, c)

    # Also emit a lightweight index.json for automation
    index_path = out_dir / 'merged0_index.json'
    with index_path.open('w', encoding='utf-8') as f:
        payload = []
        for c in merged0_cases:
            payload.append({
                'rid': c.rid,
                'sessionId': c.session,
                'lastTs': c.last_ts,
                'lastMerged': c.last_merged,
                'end.classification': compute_end_classification(c),
                'end.classification.refined': compute_refined_classification(c),
                'rawHits': {
                    'max': c.raw_hits_max,
                    'nonzero.count': c.raw_hits_nonzero_count,
                },
                'signals': {
                    'strictDomainRequired.count': c.strict_domain_count,
                    'filterEmptyAfterFiltering.count': c.filter_empty_count,
                    'budget_exhausted_raw0.count': c.budget_exhausted_raw0_count,
                    'brave.429.count': c.brave_429_count,
                    'brave.cooldown.meta.count': c.brave_cooldown_meta_count,
                    'brave.cooldown.remainingMs.min': c.brave_cooldown_remaining_ms_min,
                    'brave.retryAfterMs.min': c.brave_retry_after_ms_min,
                    'brave.retryAfterMs.max': c.brave_retry_after_ms_max,
                    'brave.elapsedMs.min': c.brave_elapsed_ms_min,
                    'backupQuery.count': c.backup_query_count,
                    'remergeOnce.count': c.remerge_once_count,
                }
            })
        json.dump(payload, f, ensure_ascii=False, indent=2)

    report_path = write_report_md(out_dir, log_paths, merged0_cases)

    print(f"Wrote: {csv_path}")
    print(f"Wrote: {index_path}")
    print(f"Wrote: {report_path}")
    print(f"Cases: {len(merged0_cases)}")

    # Console UX: quick triage summary (kept short).
    refined_counts = summarize_refined_counts(merged0_cases)
    if refined_counts:
        print("Counts: end.classification.refined")
        for k, v in refined_counts:
            print(f"  - {k}: {v}")

    fast_counts = summarize_fast_verdict_counts(merged0_cases)
    if fast_counts:
        print("Fast check: strictDomain vs true 0-hit (metrics-only)")
        print("  - strictDomain_starvation: rawHits.max>0 + filterEmptyAfterFiltering.count>0 + strictDomainRequired.count>0")
        print("  - true_0_hit: rawHits.max==0 + filterEmptyAfterFiltering.count==0")
        for k, v in fast_counts:
            print(f"  - {k}: {v}")

    # Show most recent rids for convenience (ops workflow)
    top_n = min(10, len(merged0_cases))
    if top_n > 0:
        print("Recent cases (open seq_<rid>.md):")
        for c in merged0_cases[:top_n]:
            print(f"  - {c.last_ts} sessionId={c.session} rid={c.rid} refined={compute_refined_classification(c)} source={end_classification_source(c)}")

    print("Next actions:")
    print("  - merged0_cases.csv로 케이스별 분류표 확인")
    print("  - seq_<rid>.md로 재현 트리(Sequence) 확인")
    print("  - strictDomain vs true 0-hit: rawHits.max + filterEmptyAfterFiltering.count + strictDomainRequired.count 조합")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
