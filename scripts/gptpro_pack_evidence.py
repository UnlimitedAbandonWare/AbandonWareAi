#!/usr/bin/env python3
"""Bounded, read-only evidence copies for the user-authorized GPT Pro pack."""
from __future__ import annotations

import hashlib
import heapq
import json
import ntpath
import os
import platform
import re
import subprocess
import sys
import time
import io
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict, deque
from datetime import datetime, timedelta, timezone
from pathlib import Path

import gptpro_pack_context as context
from log_redact import redact_text, keep_gradle_spine
from quarantine_codex_rollout_mine import session_id_of
from codex_ask_audit import _parse_ts
from codex_session_friction import classify_line as classify_friction_line

KST = timezone(timedelta(hours=9))
PREFIX = "_GPTPRO_EVIDENCE/"
DEFAULTS = dict(evidenceDays=3, maxEvidenceMb=24, maxFileKb=512,
                evidenceHistoryDays=30, maxScanFiles=5000, maxScanMb=128, maxScanSeconds=30,
                rolloutDays=3, maxRollouts=40, maxRunDirs=10,
                maxHandoffReports=40, maxDebugFiles=80, maxFailureLogs=40,
                maxTimelineRows=4000, maxRolloutMb=64)
SIGNAL = re.compile(r"ERROR|WARN|Exception|timeout|fallback|circuit|[45]\d\d|"
                    r"provider|ollama|openai|gemini|grok|jev|naver|brave|"
                    r"rag|graphrag|session|restart|credential|FAILED|FAILURE|HOLD|PARTIAL|NOT_RUN", re.I)
FAIL = re.compile(r"ERROR|Exception|FAILED|FAILURE|timeout|"
                  r"denied|not permitted|patch.*fail|invalid patch|HOLD|PARTIAL|NOT_RUN", re.I)
DATE = re.compile(r"\d{4}-\d{2}-\d{2}[T ][0-9:.]+(?:Z|[+-]\d\d:\d\d)?")
BLOCKED = re.compile(r"credential|service[-_]?account|providers\.json|auth\.json|"
                     r"apikey|api-key|private[-_]?key|shared\.env|application-secrets", re.I)
SKIP_DIRS = {".secrets", ".git", ".gradle", "node_modules", ".next",
             "meta-display-db", "before", "staged"}
SAFE_EVENT_KEYS = {"timestamp", "time", "ts", "date", "level", "severity",
                   "event", "type", "kind", "status", "statusCode", "httpStatus",
                   "reason", "reasonCode", "error", "errorType", "exception",
                   "message", "stacktrace", "stackTrace", "model", "provider",
                   "duration", "durationMs", "exitCode", "count"}
COUNTS = {
    "ERROR": re.compile(r"\bERROR\b", re.I),
    "Exception": re.compile(r"\b\w*Exception\b"),
    "FAILED/FAILURE": re.compile(r"\bFAILED\b|\bFAILURE\b", re.I),
    "HTTP 4xx/5xx": re.compile(r"\b(?:HTTP[/\w.]*\s*|status(?:Code)?[=:\" ]+)[45]\d\d\b", re.I),
    "timeout": re.compile(r"timeout|timed out", re.I),
    "fallback": re.compile(r"fallback", re.I),
}
CONVERSATION = re.compile(r"(?i)\b(?:user\s+(?:prompt|query|message)|"
                          r"(?:raw|private)\s+(?:prompt|query|response)|transcript|"
                          r"chat\s+(?:message|history)|prompt\s*[:=]|query\s*[:=]|"
                          r"assistant\s+(?:response|message))")


def timestamp(value):
    result = _parse_ts(value)
    return result.replace(tzinfo=timezone.utc) if result and result.tzinfo is None else result


def kst(value):
    if isinstance(value, (int, float)):
        value = datetime.fromtimestamp(value, timezone.utc)
    value = timestamp(value) if not isinstance(value, datetime) else value
    return value.astimezone(KST).strftime("%Y-%m-%d %H:%M:%S") if value else "not_observed"


def command_status(code, command, explicit_error=False):
    if explicit_error:
        return 'failure', 'explicit_tool_error'
    if type(code) is not int:
        return 'unknown', 'exit_not_observed'
    if code == 1 and re.match(r'^\s*(?:&\s*)?git\s+(?:diff\b.*--(?:exit-code|quiet)|grep\b)', command):
        return 'success', 'differences_found' if 'diff' in command else 'no_matches'
    return ('success' if code == 0 else 'failure'), 'process_exit'


PRIVATE_FIELDS = {'prompt','query','messages','role','reasoning','user_prompt','system_prompt',
                  'developer_prompt','transcript','chat_history','raw_rollout','input'}


def diagnostic_value(value, depth=0):
    if depth > 12:
        return '[nested content omitted]'
    if isinstance(value,dict):
        return {k:diagnostic_value(v,depth+1) for k,v in value.items() if k.lower() not in PRIVATE_FIELDS}
    if isinstance(value,list):
        return [diagnostic_value(v,depth+1) for v in value]
    if isinstance(value,str):
        try:
            parsed=json.loads(value)
        except ValueError:
            parsed=None
        if isinstance(parsed,(dict,list)):
            return json.dumps(diagnostic_value(parsed,depth+1),ensure_ascii=False)
        if re.search(r'\\*"(?:'+ '|'.join(sorted(PRIVATE_FIELDS))+r')\\*"\s*:',value,re.I):
            return '[conversation envelope omitted]'
    return value


def test_receipt(command, output, code):
    runner='unittest' if re.search(r'\bpython(?:\.exe)?\b.*\s-m\s+unittest\b',command) else (
        'gradle' if re.search(r'\bgradlew(?:\.bat)?\b.*\btest\b',command) else None)
    if runner is None:
        return None
    count=re.search(r'(?m)^Ran (\d+) tests? in ',output) if runner=='unittest' else re.search(r'(\d+) tests completed',output)
    executed=int(count.group(1)) if count else None
    failures=re.search(r'failures=(\d+)',output)
    errors=re.search(r'errors=(\d+)',output)
    skipped=re.search(r'skipped[=:]?(\d+)',output)
    failed=int(failures.group(1)) if failures else 0
    error_count=int(errors.group(1)) if errors else 0
    skip_count=int(skipped.group(1)) if skipped else 0
    selected=re.findall(r'--tests[=\s]+[\'\"]?([^\s\'\"]+)',command) if runner=='gradle' else [
        t for t in command.split('unittest',1)[1].split() if not t.startswith('-')]
    status='failure' if failed+error_count or type(code) is int and code!=0 else (
        'not_run' if executed==0 or skip_count or 'NO-SOURCE' in output else 'unknown' if executed is None else 'success')
    return dict(runner=runner,selected_tests=selected,executed=executed,failures=failed,errors=error_count,
                skipped=skip_count,exit=code,report=None,receipt_status=status)


def safe_file(path, allow_templates=False):
    if any(p.lower() == ".secrets" for p in path.parts):
        return False
    if (path.name.lower().startswith(".env")
            and not (allow_templates and path.name.lower() in {".env.example", ".env.sample", ".env.template"})):
        return False
    # Implementation/class names can discuss credentials without being key files.
    if BLOCKED.search(path.name) and path.suffix.lower() not in {
            ".java", ".kt", ".js", ".ts", ".jsx", ".tsx", ".py", ".ps1", ".bat", ".md"}:
        return False
    if path.suffix.lower() in {".pem", ".key", ".pfx", ".p12", ".jks", ".db", ".zip", ".bin"}:
        return False
    for part in (path, *path.parents):
        try:
            if part.is_symlink() or getattr(part.lstat(), "st_file_attributes", 0) & 1024:
                return False
        except OSError:
            pass
    return True


def lines(path, max_line=256 * 1024, max_bytes=None):
    """Stream bounded lines, draining a huge line without loading it whole."""
    consumed = 0
    with path.open("rb") as stream:
        while max_bytes is None or consumed < max_bytes:
            remaining = max_line if max_bytes is None else min(max_line, max_bytes - consumed)
            raw = stream.readline(remaining)
            if not raw:
                break
            consumed += len(raw)
            truncated = not raw.endswith(b"\n") and len(raw) == remaining
            if truncated:
                while True:
                    remaining = max_line if max_bytes is None else min(max_line, max_bytes - consumed)
                    if remaining <= 0:
                        break
                    rest = stream.readline(remaining)
                    consumed += len(rest)
                    if not rest or rest.endswith(b"\n"):
                        break
            yield raw.decode("utf-8", "replace").rstrip("\r\n"), truncated


class Evidence:
    def __init__(self, root, cfg, scan, now, package):
        self.root, self.scan, self.now, self.package = root, scan, now, package
        self.cfg = {**DEFAULTS, **cfg}
        history = self.cfg['evidenceHistoryDays']
        if type(history) is not int or not 3 <= history <= 90 or history < self.cfg['evidenceDays']:
            raise ValueError('evidence-history-days must be 3..90 and >= evidence-days')
        for key in ('evidenceDays', 'maxEvidenceMb', 'maxFileKb', 'maxScanFiles', 'maxScanMb', 'maxScanSeconds'):
            if not 0 < float(self.cfg[key]) < float('inf'):
                raise ValueError('invalid evidence bound: '+key)
        self.history_cutoff = now.timestamp()-history*86400
        self.scan_started, self.scan_bytes, self.scan_files = time.monotonic(), 0, 0
        self.captured, self.scan_reasons = {}, Counter()
        self.opened_paths, self.incomplete, self.discovered_files = set(), set(), 0
        self.changed = set()
        self.section_start=None
        self.limit = max(1024, int(float(self.cfg["maxEvidenceMb"]) * 1024 * 1024))
        self.file_limit = max(512, int(float(self.cfg["maxFileKb"]) * 1024))
        self.cutoff = now.timestamp() - float(self.cfg["evidenceDays"]) * 86400
        self.files, self.attrs = {}, {}
        self.redactions, self.warnings, self.excluded, self.excerpts = Counter(), [], [], []
        self.timeline, self.trace_counts, self.trace_paths = [], Counter(), {}
        self.test_failures = self.codex_failures = self.unrelated_sessions = 0
        self.codex_tool_failures = 0
        self.events = []

    def begin_section(self):
        self.section_start=(self.discovered_files,self.scan_bytes,time.monotonic())

    def remaining_bytes(self):
        global_remaining=self.cfg['maxScanMb']*1048576-self.scan_bytes
        return min(global_remaining,self.cfg['maxScanMb']*1048576/3-(self.scan_bytes-self.section_start[1])) if self.section_start else global_remaining

    def record(self, kind, when, source, coordinate, payload, status='unknown', **facts):
        payload=diagnostic_value(payload)
        clean = self.clean(source, json.dumps(payload, ensure_ascii=False))
        if clean is None:
            return None
        observed = timestamp(when)
        if observed and observed.timestamp() < self.history_cutoff:
            self.scan_reasons['outside_window'] += 1
            return None
        identity = json.dumps([kind, source, facts.get('call_id', coordinate), clean, status], ensure_ascii=False, sort_keys=True)
        event_id = 'evt-' + hashlib.sha256(identity.encode()).hexdigest()[:24]
        if any(row['event_id'] == event_id for row in self.events):
            return event_id
        row = dict(event_id=event_id, event_type=kind, observed_status=status,
                   occurred_at=dict(original=str(when) if when else None,
                                    utc=observed.astimezone(timezone.utc).isoformat() if observed else None),
                   source_ref=dict(root_alias='codex_sessions' if source.startswith('Codex/') else 'project',
                                   relative_path=source.removeprefix('Codex/'), original_lines=[coordinate, coordinate],
                                   observed_at=self.now.isoformat(), excerpt_path=None, excerpt_lines=None),
                   relations=[], selection_reason='direct_failure' if status == 'failure' else 'verification_receipt',
                   payload=json.loads(clean) if clean.startswith('{') else {'diagnostic': clean}, **facts)
        row['payload_sha256']=hashlib.sha256(serialize(row['payload']).encode()).hexdigest()
        self.events.append(row)
        return event_id

    def scan_available(self, path=None):
        allowed = ((path in self.opened_paths or self.scan_files < self.cfg['maxScanFiles']) and
                   self.scan_bytes < self.cfg['maxScanMb']*1048576 and
                   time.monotonic()-self.scan_started < self.cfg['maxScanSeconds'])
        if self.section_start:
            allowed=allowed and (self.discovered_files-self.section_start[0]<max(1,self.cfg['maxScanFiles']//3)
                    and self.remaining_bytes()>0 and time.monotonic()-self.section_start[2]<self.cfg['maxScanSeconds']/3)
        if not allowed:
            self.scan_reasons['scan_limit'] += 1
        return allowed

    def metadata_line(self,path):
        if not self.scan_available(path) or not safe_file(path):
            return None
        if path not in self.opened_paths:
            self.opened_paths.add(path)
            self.scan_files+=1
        cap=min(256*1024,int(self.remaining_bytes()))
        with path.open('rb') as stream:
            raw=stream.readline(cap)
        self.scan_bytes+=len(raw)
        if len(raw)==cap and not raw.endswith(b'\n'):
            self.scan_reasons['scan_limit']+=1
            return None
        return raw.decode('utf-8')

    def capture(self, path, cap=None):
        if path in self.captured:
            return self.captured[path]
        if not self.scan_available(path) or not safe_file(path):
            return None
        if path not in self.opened_paths:
            self.opened_paths.add(path)
            self.scan_files += 1
        cap = min(int(cap or self.cfg['maxRolloutMb']*1048576),
                  int(self.remaining_bytes()))
        for attempt in range(2):
            try:
                if cap*2 > self.remaining_bytes():
                    cap=int(self.remaining_bytes()//2)
                if cap<=0:
                    self.scan_reasons['scan_limit']+=1
                    return None
                before = path.stat()
                with path.open('rb') as stream:
                    raw = stream.read(cap)
                with path.open('rb') as stream:
                    check=stream.read(cap)
                self.scan_bytes += len(raw)+len(check)
                after = path.stat()
                if raw==check and (before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns):
                    self.captured[path] = raw
                    if after.st_size > cap:
                        self.incomplete.add(path)
                        self.scan_reasons['scan_limit'] += 1
                    return raw
            except PermissionError:
                self.scan_reasons['permission_denied'] += 1
                return None
            except OSError:
                self.scan_reasons['source_missing'] += 1
                return None
        self.exclude(path.name, 'source_changed_during_read')
        self.scan_reasons['source_missing'] += 1
        return None

    def stream_lines(self, path, max_line=256*1024, max_bytes=None):
        raw = self.capture(path, max_bytes)
        if raw is None:
            return
        for line in io.BytesIO(raw):
            # A truncated record is never evidence; raw prefixes may split a secret.
            partial=len(line)>max_line or (path in self.incomplete and not line.endswith(b'\n'))
            try:
                text='' if partial else line.decode('utf-8').rstrip('\r\n')
            except UnicodeDecodeError:
                text,partial='',True
                self.scan_reasons['parse_error'] += 1
            yield text,partial

    def warn(self, section, reason):
        self.warnings.append(f"{section}: {reason}")

    def exclude(self, path, reason):
        self.excluded.append((str(path), reason))

    def clean(self, name, text):
        text=diagnostic_value(str(text))
        # Selection policy: conversation lines are omitted, never exported as diagnostic text.
        body = str(text).splitlines()
        omitted = sum(bool(CONVERSATION.search(line)) for line in body)
        if omitted:
            self.exclude(name, f"conversation text lines omitted: {omitted}")
            text = "\n".join(line for line in body if not CONVERSATION.search(line))
        if re.search(r'"(?:role|messages|reasoning|user_prompt|system_prompt|developer_prompt)"\s*:', str(text), re.I):
            text = '[conversation envelope omitted]'
        text, counts = redact_text(str(text))
        self.redactions.update(counts)
        for _ in range(64):
            hits = self.scan("evidence.txt", text)
            if not hits:
                return text
            if any(pid == "private-key" for _, pid in hits):
                self.exclude(name, "secret-suspect private-key block; file omitted")
                return None
            body = text.splitlines()
            for line, pattern in hits:
                body[line - 1] = f"<REDACTED:{pattern}>"
                self.redactions["rescan_" + pattern] += 1
            text = "\n".join(body) + "\n"
        self.exclude(name, "secret-suspect residual; file omitted")
        return None

    def add(self, name, text, priority=50, mtime=0, source=None, header=None):
        from gptpro_pack import archive_path
        archive_path(name,{n.casefold() for n in self.files if n != name})
        clean = self.clean(name, text)
        if clean is None:
            return
        data = clean.encode("utf-8")
        if len(data) > self.file_limit:
            all_lines = len(clean.splitlines())
            kept_lines = data[:max(0, self.file_limit - 256)].count(b"\n") + 1
            provenance = header or f"# EXCERPT from {source or name} lines 1-{kept_lines} of {all_lines} (file byte cap)"
            prefix = provenance + "\n"
            data = data[:max(0, self.file_limit - len(prefix.encode("utf-8")) - 32)]
            clean = prefix + data.decode("utf-8", "ignore") + "\n[truncated: file byte cap]\n"
            self.exclude(source or name, "large original; retained bounded excerpt")
            self.excerpts.append((name, provenance))
        elif header:
            clean = header + "\n" + clean
            self.excerpts.append((name, header))
        # Include the provenance header in the same byte budget.
        if len(clean.encode("utf-8")) > self.file_limit:
            clean = clean.encode("utf-8")[:self.file_limit].decode("utf-8", "ignore")
        self.files[name] = clean
        self.attrs[name] = (priority, mtime)
        if name.startswith(PREFIX):
            for label, pattern in COUNTS.items():
                count = len(pattern.findall(clean))
                self.trace_counts[label] += count
                if count:
                    self.trace_paths.setdefault(label, name)

    def event(self, when, kind, path, summary):
        self.timeline.append((kst(when), kind, str(path), str(summary).replace("\n", " ")[:250]))

    def recent(self, base, patterns, limit):
        if not base.is_dir():
            self.warn(base.relative_to(self.root).as_posix(), "not observed: directory missing")
            return []
        found = []
        for parent, dirs, leaves in os.walk(base, followlinks=False):
            dirs[:] = sorted(d for d in dirs if d.lower() not in SKIP_DIRS
                       and not d.startswith("gradle-") and not Path(parent, d).is_symlink()
                       and self.package.get("taskId", "") != d)
            for leaf in sorted(leaves):
                if self.discovered_files >= self.cfg['maxScanFiles'] or not self.scan_available():
                    self.scan_reasons['scan_limit'] += 1
                    return sorted(found,key=lambda p:(-p.stat().st_mtime,p.relative_to(self.root).as_posix()))[:limit]
                self.discovered_files += 1
                path = Path(parent, leaf)
                if not any(path.match(p) for p in patterns):
                    continue
                if not safe_file(path):
                    self.exclude(path.relative_to(self.root).as_posix(), "protected name/reparse; unopened")
                    continue
                try:
                    if path.stat().st_mtime >= self.history_cutoff:
                        found.append(path)
                    else:
                        self.scan_reasons['outside_window'] += 1
                except OSError:
                    continue
        found.sort(key=lambda p: (-p.stat().st_mtime, p.relative_to(self.root).as_posix().casefold()))
        for p in found[limit:]:
            self.exclude(p.relative_to(self.root).as_posix(), "recent file count cap")
        return found[:limit]

    def copy_excerpt(self, path, destination, priority=40, json_events=False):
        rel = path.relative_to(self.root).as_posix()
        selected, seen, previous = {}, Counter(), deque(maxlen=40)
        heap, selected_bytes = [], 0
        total = 0
        pending = -1
        def offer(number, text, priority):
            nonlocal selected_bytes
            current = selected.get(number)
            if current and current[0] >= priority:
                return
            clean = self.clean(rel, text)
            if clean is None or not clean:
                return
            if len(clean.encode("utf-8")) > self.file_limit:
                clean = clean.encode("utf-8")[:self.file_limit].decode("utf-8", "ignore")
            size = len(clean.encode("utf-8")) + 1
            if current:
                selected_bytes -= current[2]
            selected[number] = (priority, clean, size)
            selected_bytes += size
            heapq.heappush(heap, (priority, -number, number))
            while selected_bytes > max(128, self.file_limit - 180) and heap:
                score, _, victim = heapq.heappop(heap)
                row = selected.get(victim)
                if row and row[0] == score:
                    selected_bytes -= row[2]
                    del selected[victim]
        for total, (line, huge) in enumerate(self.stream_lines(path), 1):
            if huge:
                self.exclude(rel, 'oversized line omitted; no raw prefix exported')
                self.scan_reasons['parse_error'] += 1
                continue
            if json_events:
                try:
                    row = json.loads(line)
                except ValueError:
                    row = None
                if isinstance(row, dict):
                    when = next((timestamp(row.get(k)) for k in ("timestamp", "time", "ts", "date")
                                 if timestamp(row.get(k))), None)
                    if when and when.timestamp() < self.history_cutoff:
                        self.scan_reasons['outside_window'] += 1
                        continue
                    if when and when.timestamp() < self.cutoff and not any(
                            target in str(row) for target in self.changed):
                        self.exclude(rel,'outside_window: historical event has no current target')
                        self.scan_reasons['outside_window'] += 1
                        continue
                    row = {k: v for k, v in row.items() if k in SAFE_EVENT_KEYS
                           and isinstance(v, (str, int, float, bool, type(None)))}
                    for key in list(row):
                        if isinstance(row[key], str) and CONVERSATION.search(row[key]):
                            del row[key]
                            self.exclude(rel, "conversation field omitted: " + key)
                    line = json.dumps(row, ensure_ascii=False)
                else:
                    when_match = DATE.search(line)
                    when = timestamp(when_match.group()) if when_match else None
                    if when and when.timestamp() < self.history_cutoff:
                        continue
            if self.scan("evidence.txt", line) and "PRIVATE KEY" in line:
                self.exclude(rel, "secret-suspect private-key block; file omitted")
                return
            is_signal = bool(SIGNAL.search(line))
            is_failure = bool(FAIL.search(line))
            if is_signal:
                signature, _ = redact_text(line)
                signature = DATE.sub("", signature)
                seen[signature] += 1
                if seen[signature] > 1:
                    continue
                if is_failure:
                    offer(total, line, 100)
                    for number, text in previous:
                        offer(number, text, 50)
                    pending = total + 40
                else:
                    offer(total, line, 20)
            if total <= pending:
                offer(total, line, 100 if is_failure else 60)
            elif total <= 15:
                offer(total, line, 10)
            previous.append((total, line))
        for number, text in previous:
            offer(number, text, 15)
        unique = {number: row[1] for number, row in selected.items()}
        body = []
        for number, line in sorted(unique.items()):
            safe, _ = redact_text(line)
            repeats = seen.get(DATE.sub("", safe), 0)
            body.append(line + (f" [x{repeats} repetitions]" if repeats > 1 else ""))
        first, last = min(unique, default=0), max(unique, default=0)
        header = f"# EXCERPT from {rel} lines {first}-{last} of {total} (signals and +/-40 context; gaps possible)"
        self.add(destination, "\n".join(body), max(priority, 95) if FAIL.search("\n".join(body)) else priority,
                 path.stat().st_mtime, rel, header)


def collect_git(e, git, changed):
    branch = git("branch", "--show-current")
    head = git("rev-parse", "HEAD")
    status = git("status", "--short")
    e.package.update(branch=(branch or "not_observed").strip(), head=(head or "not_observed").strip(),
                     dirty=bool(status) if status is not None else "not_observed")
    if head is None:
        e.warn("git", "not observed: git unavailable/non-repository")
    e.add(PREFIX + "git/git-head.txt",
          "\n".join(f"{k}: {v}" for k, v in e.package.items() if k in {"branch", "head", "dirty"})
          + "\ndescribe: " + (git("describe", "--always", "--dirty") or "not_observed"), 100)
    for name, args in {
        "git-status.txt": ("status", "--short"),
        "git-diff-stat.txt": ("diff", "HEAD", "--stat"),
        "git-log.txt": ("log", "-30", "--stat", "--date=iso"),
        "recent-commits-files.txt": ("log", "-30", "--name-only", "--format=%h %cI"),
    }.items():
        value = status if name == "git-status.txt" else git(*args)
        if value is None:
            e.warn("git/" + name, "not observed: command unavailable")
        e.add(PREFIX + "git/" + name, value or "not_observed\n", 95)
    # Reuse the context diff policy and caps; never read protected paths via Git.
    from gptpro_pack import name_block_reason, dir_block_reason
    safe_changed = [p for p in changed if not name_block_reason(p.lower())
                    and not dir_block_reason(p.lower()) and not BLOCKED.search(Path(p).name)]
    diff, meta = context.build_changes(git, e.root, lambda p: p in {x.lower() for x in safe_changed},
                                       safe_changed, 2.0, context.CAP_DIFF_TOTAL)
    e.add(PREFIX + "git/git-diff.patch", diff, 95)
    for path in meta["diffTruncated"]:
        e.exclude(path, "context diff byte cap")
    for path in changed:
        if path not in safe_changed:
            e.exclude(path, "protected diff omitted; unopened")
        elif (e.root / path).is_file():
            e.event((e.root / path).stat().st_mtime, "source mtime", path, "changed path; timestamp observation")
            e.record('source_change', None, path, 1, {'path': path}, 'unknown',
                     source_anchor=dict(kind='current', path=path, line=1,
                                        sha256=hashlib.sha256((e.root / path).read_bytes()).hexdigest()))
    commit_times = git("log", "-30", "--format=%cI%x09%h")
    for line in (commit_times or "").splitlines():
        parts = line.split("\t", 1)
        if len(parts) == 2:
            e.event(parts[0], "git commit", PREFIX + "git/git-log.txt", parts[1])


def collect_debug(e):
    import chat_session_debug_export as chat_trace
    sources = e.recent(e.root / "logs", ["*.ndjson", "failure-pattern*.jsonl"], e.cfg["maxDebugFiles"])
    for path in sources:
        e.copy_excerpt(path, PREFIX + "debug/" + path.relative_to(e.root).as_posix() + ".txt", 75, True)
    base = e.root / "var/rag-launcher"
    if base.is_dir():
        runs = sorted((p for p in base.iterdir() if p.is_dir() and safe_file(p)
                       and p.stat().st_mtime >= e.history_cutoff), key=lambda p: (-p.stat().st_mtime,p.name))
        for run in runs[e.cfg["maxRunDirs"]:]:
            e.exclude(run.relative_to(e.root).as_posix(), "run directory count cap")
        for run in runs[:e.cfg["maxRunDirs"]]:
            for p in e.recent(run, ["result.json", "*.out.log", "*.err.log"], 12):
                rel = p.relative_to(e.root).as_posix()
                if p.name == "result.json" and p.stat().st_size <= e.file_limit:
                    raw=e.capture(p)
                    if raw is None:
                        continue
                    text = raw.decode('utf-8','replace')
                    try:
                        result = json.loads(text)
                        summary = str(result.get("status", "not_observed"))
                    except ValueError:
                        summary = "invalid JSON"
                    priority = 90 if FAIL.search(text) else 20
                    e.add(PREFIX + "debug/" + rel, text, priority, p.stat().st_mtime, rel)
                    e.event(p.stat().st_mtime, "rag-launcher", rel, "status=" + summary)
                else:
                    e.copy_excerpt(p, PREFIX + "debug/" + rel + ".txt", 85 if "err" in p.name else 20)
    else:
        e.warn("debug/rag-launcher", "not observed: directory missing")
    for folder, patterns in (("var/debug", ["dev-*-verify.json"]),
                             ("var/dev-reload", ["*.json", "*.log"]),
                             ("var/agent-work-guard", ["*.json"])):
        for p in e.recent(e.root / folder, patterns, e.cfg["maxDebugFiles"]):
            rel = p.relative_to(e.root).as_posix()
            e.copy_excerpt(p, PREFIX + "debug/" + rel + ".txt", 75)
    # The producer's sanitized v2 export is the only chat trace input. Never
    # scan raw traces/JSONL or exports other than the latest pointer's target.
    trace_base = e.root / "var/debug/chat-session-traces/export"
    latest = trace_base / "latest.json"
    try:
        if not safe_file(latest) or not latest.is_file():
            raise ValueError("latest pointer missing or unsafe")
        raw = e.capture(latest, e.file_limit)
        if raw is None or latest in e.incomplete:
            raise ValueError("latest pointer unavailable or oversized")
        pointer = chat_trace.read_latest_pointer(e.root)
        if not isinstance(pointer, dict) or pointer.get("schema") != "awx.chat-session-trace-latest.v2":
            raise ValueError("latest pointer is not v2")
        relative = Path(pointer.get("exportDir", ""))
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("unsafe export target")
        export = e.root / relative
        if (not safe_file(export) or not export.is_dir() or export == trace_base
                or not export.resolve().is_relative_to(trace_base.resolve())):
            raise ValueError("export target outside trace export directory")
        manifest_file = export / "manifest.json"
        if not safe_file(manifest_file) or not manifest_file.is_file():
            raise ValueError("export manifest unavailable")
        manifest_raw = e.capture(manifest_file, e.file_limit)
        if manifest_raw is None or manifest_file in e.incomplete:
            raise ValueError("export manifest unavailable or oversized")
        manifest = chat_trace.STRICT_JSON.decode(manifest_raw.decode("utf-8"))
        if (not isinstance(manifest, dict) or manifest.get("schema") != "awx.chat-session-trace-export.v2"
                or any(manifest.get(k) != pointer.get(k) for k in
                       ("queryHash", "queryForm", "recordCount", "exportedAtUtc"))
                or Path(manifest.get("exportDir", "")) != relative):
            raise ValueError("export manifest does not bind latest v2 pointer")
        selected = [latest, manifest_file, export / "records.json"]
        for p in selected[:e.cfg["maxDebugFiles"]]:
            if not safe_file(p) or not p.is_file() or p.stat().st_mtime < e.history_cutoff:
                continue
            rel = p.relative_to(e.root).as_posix()
            e.copy_excerpt(p, PREFIX + "debug/" + rel + ".txt", 75)
    except (OSError, ValueError, TypeError):
        e.warn("debug/chat-session-traces", "not observed: safe latest v2 export unavailable")


def collect_tests(e):
    summary = ["# Test summary", "", "| Build directory | Class | tests | failures | errors | skipped | Time (KST) |",
               "|---|---|---:|---:|---:|---:|---|"]
    observations = defaultdict(list)
    xmls = e.recent(e.root / "build", ["TEST-*.xml"], 4000)
    for p in xmls:
        if "test-results" not in p.parts:
            continue
        rel = p.relative_to(e.root).as_posix()
        try:
            raw = e.capture(p)
            if raw is None:
                continue
            tree = ET.fromstring(raw)
        except (ET.ParseError, OSError):
            e.warn("tests", "invalid/unreadable XML: " + rel)
            continue
        suites = [tree] if tree.tag == "testsuite" else list(tree.iter("testsuite"))
        for suite in suites:
            name = suite.get("name", p.stem)
            when = timestamp(suite.get("timestamp")) or datetime.fromtimestamp(p.stat().st_mtime, timezone.utc)
            if when.timestamp() < e.history_cutoff:
                continue
            counts = [suite.get(k, "0") for k in ("tests", "failures", "errors", "skipped")]
            tests, failed, errors, skipped = [int(n) for n in counts]
            selection = sorted(case.get('classname', name)+'.'+case.get('name', '?') for case in suite.findall('testcase'))
            properties={p.get('name'):p.get('value') for p in suite.findall('properties/property')
                        if p.get('name') in {'run_id','workdir','argv_hash','source_before','source_after'}}
            state = 'failure' if failed+errors else 'not_run' if tests == 0 or skipped else 'success'
            e.record('test_result', suite.get('timestamp'), rel, 1,
                     dict(runner='junit_xml', selected_tests=selection, executed=tests, failures=failed,
                          errors=errors, skipped=skipped, exit=None, report=rel), state,
                     source_anchor=dict(kind='historical', snapshot=suite.get('source_sha256'),
                                        status='historical_source_missing'),verification_context=properties)
            summary.append("| " + " | ".join([rel.split("/test-results")[0], name, *counts, kst(when)]) + " |")
            for case in suite.findall("testcase"):
                failures = list(case.findall("failure")) + list(case.findall("error"))
                state = "RED" if failures else "SKIPPED" if case.find("skipped") is not None else "GREEN"
                key = (case.get("classname", name), case.get("name", "?"))
                observations[key].append((when.timestamp(), state, rel))
                if failures:
                    e.test_failures += 1
                    title = key[0] + "." + key[1]
                    output = ["# Test failure", "test: " + title, "source: " + rel, "time KST: " + kst(when)]
                    for failure in failures:
                        output += ["message: " + failure.get("message", ""), failure.text or ""]
                    output.extend(node.text or "" for node in list(case.findall("system-err")) + list(suite.findall("system-err")))
                    slug = hashlib.sha256((rel + title).encode()).hexdigest()[:16]
                    e.add(PREFIX + "tests/failures/" + slug + ".txt", "\n".join(output), 100, when.timestamp(), rel)
                e.event(when, "test " + state, rel, ".".join(key))
    e.add(PREFIX + "tests/test-summary.md", "\n".join(summary) + "\n", 100)
    transitions = ["# RED to GREEN observations", "", "Timestamps only; no causal attribution.", "",
                   "| Class.method | RED (KST) | GREEN (KST) | Evidence |", "|---|---|---|---|"]
    for key, events in observations.items():
        red = None
        for when, state, rel in sorted(events):
            if state == "RED":
                red = (when, rel)
            elif state == "GREEN" and red and when > red[0]:
                transitions.append(f"| {'.'.join(key)} | {kst(red[0])} | {kst(when)} | {red[1]} -> {rel} |")
                red = None
    e.add(PREFIX + "tests/red-green.md", "\n".join(transitions) + "\n", 100)
    for p in e.recent(e.root / "data/agent-handoff", ["red.log", "*verify*.log", "*verify*.json"],
                      e.cfg["maxFailureLogs"]):
        rel = p.relative_to(e.root).as_posix()
        e.copy_excerpt(p, PREFIX + "tests/handoff/" + rel + ".txt", 90)


def normal_cwd(value):
    return ntpath.normcase(ntpath.normpath(str(value).replace("/", "\\")))


def output_failure(payload):
    """Only tool output; never conversation, reasoning, function input or raw JSONL."""
    item = payload.get("item") or {}
    if item.get("type") == "CommandExecution":
        code = item.get("exit_code")
        return code, str(item.get("stderr") or item.get("aggregated_output") or item.get("output") or "")
    output = payload.get("output", "")
    if isinstance(output, list):
        output = "\n".join(str(x.get("text", "")) for x in output if isinstance(x, dict))
    if not isinstance(output, str):
        output = json.dumps(output, ensure_ascii=False)
    try:
        obj = json.loads(output)
    except ValueError:
        obj = None
    if isinstance(obj, dict) and ("exit_code" in obj or "exitCode" in obj):
        code = obj.get("exit_code", obj.get("exitCode"))
        tail = obj.get("output", obj.get("stderr", ""))
        return code, str(tail)
    match = re.search(r"(?m)^(?:Process exited with code|Exit code:)\s*(-?\d+)\s*$", output, re.I)
    return (int(match.group(1)) if match else None), output


def collect_codex(e, sessions_dir):
    index = ["# Codex sessions index", "", "| Session | Start (KST) | End (KST) | cwd | commands | failures |",
             "|---|---|---|---|---:|---:|"]
    failures_doc = ["# Codex project failures", "", "Failure-only tool events; original rollout JSONL is never copied.",
                    "Fields: session_meta.payload.cwd; event_msg/item_completed item.exit_code;",
                    "function_call_output: JSON exit_code/exitCode or anchored process exit line.",
                    "Command fields: item.command / item.parsed_cmd[].cmd; function_call arguments.cmd.", ""]
    cutoff = e.history_cutoff
    candidates = []
    # Date directories only: never enumerate the whole Codex home.
    for day in range(int(e.cfg['evidenceHistoryDays']) + 1):
        folder = sessions_dir / (e.now - timedelta(days=day)).strftime("%Y/%m/%d")
        if folder.is_dir():
            for p in sorted(folder.glob('rollout-*.jsonl')):
                if e.discovered_files>=e.cfg['maxScanFiles'] or not e.scan_available():
                    e.scan_reasons['scan_limit']+=1
                    break
                e.discovered_files+=1
                if safe_file(p) and p.stat().st_mtime>=cutoff:
                    candidates.append(p)
    candidates.sort(key=lambda p: (-p.stat().st_mtime, p.relative_to(sessions_dir).as_posix()))
    selected = 0
    for p in candidates:
        try:
            first = e.metadata_line(p)
            if first is None:
                continue
            meta = json.loads(first)
            cwd = meta.get("payload", {}).get("cwd", "") if meta.get("type") == "session_meta" else ""
            exact = normal_cwd(cwd) == normal_cwd(e.root)
            worktree = bool(re.search(r"[\\/]\.codex[\\/]worktrees[\\/][^\\/]+[\\/]src$", str(cwd), re.I))
            if not exact and not worktree:
                e.unrelated_sessions += 1
                continue
            if worktree:
                # Confirm the project reference, without exporting body/message text.
                if not any("demo-1" in text.lower() for text, _ in
                           e.stream_lines(p, max_bytes=int(e.cfg["maxRolloutMb"] * 1024 * 1024))):
                    e.unrelated_sessions += 1
                    continue
            if selected >= int(e.cfg["maxRollouts"]):
                e.exclude("Codex/" + p.name, "project rollout count cap")
                continue
            selected += 1
            session = session_id_of(p)
            start, end = meta.get("timestamp") or meta.get("payload", {}).get("timestamp"), None
            pending, retries, records = {}, Counter(), {}
            commands = command_failures = 0
            byte_cap = int(e.cfg["maxRolloutMb"] * 1024 * 1024)
            direct = []
            direct_ids = set()
            tool_records = {}
            patch_calls = set()
            fallback = []
            for record_line, (raw, huge) in enumerate(e.stream_lines(p, max_bytes=byte_cap), 1):
                if huge:
                    e.warn("agent/Codex", "oversized event omitted: " + session)
                    continue
                try:
                    event = json.loads(raw)
                except ValueError:
                    continue
                end = event.get("timestamp") or end
                when = timestamp(event.get("timestamp"))
                if when and when.timestamp() < cutoff:
                    continue
                payload = event.get("payload") or {}
                if not isinstance(payload, dict):
                    continue
                kind = payload.get("type")
                if (event.get("type") == "response_item" and kind == "custom_tool_call"
                        and str(payload.get("name", "")).rsplit(".", 1)[-1] == "apply_patch"):
                    patch_calls.add(payload.get("call_id"))
                if event.get("type") == "response_item" and kind == "function_call":
                    try:
                        args = json.loads(payload.get("arguments", "{}"))
                    except (ValueError, TypeError):
                        args = {}
                    command = args.get("cmd", "") if isinstance(args, dict) else ""
                    if command:
                        pending[payload.get("call_id")] = command
                        retries[command] += 1
                item = payload.get("item") or {}
                if (event.get("type") == "event_msg" and kind == "item_completed"
                        and isinstance(item, dict) and item.get("type") == "McpToolCall"):
                    # Reuse the existing miner's explicit status/isError classification.
                    state = {"mcp_failures": {"by_tool": Counter(), "samples": defaultdict(list)}}
                    classify_friction_line(raw, state, 0, {})
                    if state["mcp_failures"]["by_tool"]:
                        identity = item.get("id") or hashlib.sha256(raw.encode()).hexdigest()
                        result = item.get("result") or {}
                        output = "\n".join(str(part.get("text", "")) for part in result.get("content", [])
                                           if isinstance(part, dict)) if isinstance(result, dict) else ""
                        tool_records[identity] = (event.get("timestamp"),
                                                  str(item.get("server", "?")) + "." + str(item.get("tool", "?")),
                                                  "\n".join(output.splitlines()[-20:]))
                        e.record('patch' if str(item.get('tool', '')).endswith('apply_patch') else 'command',
                                 event.get('timestamp'), 'Codex/'+p.name, record_line,
                                 {'tool': str(item.get('tool', '?')), 'output': e.clean('tool', output)}, 'failure',
                                 call_id=str(identity), link_status='observed_result')
                if (event.get("type") == "event_msg" and kind == "item_completed"
                        and item.get("type") == "FileChange" and item.get("status") == "failed"):
                    identity = item.get("id") or hashlib.sha256(raw.encode()).hexdigest()
                    # Changes contain source hunks, not diagnostic output; never export them.
                    tool_records[identity] = (event.get("timestamp"), "apply_patch",
                                              "FileChange status=failed; diagnostic output not_observed")
                    e.record('patch', event.get('timestamp'), 'Codex/'+p.name, record_line,
                             {'tool': 'apply_patch', 'output': 'FileChange status=failed'}, 'failure',
                             call_id=str(identity), link_status='observed_result')
                elif (event.get('type')=='event_msg' and kind=='item_completed' and item.get('type')=='FileChange'):
                    state='success' if item.get('status') in {'completed','succeeded'} else 'unknown'
                    e.record('patch',event.get('timestamp'),'Codex/'+p.name,record_line,
                             {'tool':'apply_patch','status':item.get('status')},state,
                             call_id=str(item.get('id',record_line)),link_status='observed_result')
                if event.get("type") == "event_msg" and kind == "item_completed" and item.get("type") == "CommandExecution":
                    if item.get("id") and item["id"] in direct_ids:
                        continue
                    direct_ids.add(item.get("id"))
                    command = [x.get("cmd", "") for x in item.get("parsed_cmd", [])] or item.get("command", "")
                    command = " ".join(command) if isinstance(command, list) else str(command)
                    code, output = output_failure(payload)
                    identity = item.get('call_id') or item.get('id') or str(record_line)
                    explicit_error=item.get('isError') is True or item.get('status') in {'failed','error'}
                    direct.append((event.get("timestamp"), command, code, output, identity, record_line, explicit_error))
                elif event.get("type") == "response_item" and kind in {"function_call_output", "custom_tool_call_output"}:
                    command = pending.get(payload.get("call_id"), "[command not_observed]")
                    code, output = output_failure(payload)
                    patch_error = code is None and re.search(r"(?m)^(?:invalid patch|apply_patch verification failed|"
                                            r"failed to find expected lines|failed to apply|permission denied)", output, re.I)
                    if kind == "custom_tool_call_output" and (payload.get("call_id") in patch_calls or patch_error):
                        if patch_error or (isinstance(code, int) and code != 0):
                            identity = payload.get("call_id") or hashlib.sha256(raw.encode()).hexdigest()
                            tool_records[identity] = (event.get("timestamp"), "apply_patch",
                                                      "\n".join(output.splitlines()[-20:]))
                            e.record('patch', event.get('timestamp'), 'Codex/'+p.name, record_line,
                                     {'tool': 'apply_patch', 'output': e.clean('patch', output)}, 'failure',
                                     call_id=str(identity), link_status='observed_result')
                        else:
                            e.record('patch',event.get('timestamp'),'Codex/'+p.name,record_line,
                                     {'tool':'apply_patch','exit':code,'output':e.clean('patch',output)},
                                     'success' if code==0 else 'unknown',call_id=str(payload.get('call_id',record_line)),
                                     link_status='observed_result')
                        continue
                    identity = payload.get('call_id')
                    if identity:
                        fallback.append((event.get("timestamp"), command, code, output, identity, record_line,
                                         payload.get('isError') is True or payload.get('status') in {'failed','error'}))
            # Same call is one attempt; uncorrelated fallback results remain visible.
            events = {row[4]: row for row in fallback}
            events.update({row[4]: row for row in direct})
            events = list(events.values())
            commands = len(events)
            for when, command, code, output, identity, record_line, explicit_error in events:
                retries[command] += 1 if direct else 0
                status, semantics = command_status(code, command, explicit_error)
                receipt=test_receipt(command,output,code)
                if receipt:
                    status=receipt.pop('receipt_status')
                    if explicit_error:
                        status='failure'
                e.record('test_result' if receipt else 'command', when, 'Codex/'+p.name, record_line,
                         {**(receipt or {}),'command': command, 'exit': code, 'output': e.clean('output', output)}, status,
                         call_id=str(identity), exit_semantics=semantics,
                         link_status='orphan_result' if command == '[command not_observed]' else 'observed_result')
                failed = status == 'failure'
                if not failed:
                    continue
                command_failures += 1
                key = (command, code, output[-2048:])
                if key not in records:
                    safe_command, command_counts = redact_text(command)
                    safe_output, output_counts = redact_text("\n".join(output.splitlines()[-20:]))
                    e.redactions.update(command_counts)
                    e.redactions.update(output_counts)
                    # Redact BEFORE truncating a token/assignment at the command boundary.
                    records[key] = (when, safe_command[:300], code, safe_output, command)
                e.event(when, "Codex command failure", PREFIX + "agent/codex-project-failures.md",
                        f"session={session}; exit={code if code is not None else 'not_observed'}")
            e.codex_failures += command_failures
            index.append(f"| {session} | {kst(start)} | {kst(end)} | {cwd} | {commands} | {command_failures} |")
            for when, command, code, output, original in records.values():
                failures_doc.extend([f"## Session {session}", f"time KST: {kst(when)}", f"cwd: {cwd}",
                                     f"exit code: {code if code is not None else 'not_observed'}",
                                     f"retry count: {max(0, retries[original] - 1)}",
                                     "command: " + command, "output tail (<=20 lines):", output, ""])
            e.codex_tool_failures += len(tool_records)
            for when, tool, output in tool_records.values():
                failures_doc.extend([f"## Tool failure session {session}", f"time KST: {kst(when)}",
                                     f"cwd: {cwd}", "tool: " + tool, "status: failed/isError",
                                     "exit code: not_observed (tool failure)", "output tail (<=20 lines):",
                                     output, ""])
                e.event(when, "Codex tool failure", PREFIX + "agent/codex-project-failures.md",
                        f"session={session}; tool={tool}")
            if p.stat().st_size > byte_cap:
                e.warn("agent/Codex", "scan byte cap reached: " + session)
        except (OSError, ValueError, StopIteration, TypeError) as error:
            e.warn("agent/Codex", type(error).__name__ + ": session metadata/event unavailable")
    if not candidates:
        e.warn("agent/Codex", "not observed: recent sessions directory/files missing")
    e.exclude("Codex global/unrelated cwd", f"unrelated sessions excluded: {e.unrelated_sessions}; raw rollout originals excluded")
    e.add(PREFIX + "agent/codex-sessions-index.md", "\n".join(index) + "\n", 100)
    e.add(PREFIX + "agent/codex-project-failures.md", "\n".join(failures_doc) + "\n", 100)


def collect_agent(e, sessions_dir, no_codex):
    if not no_codex:
        collect_codex(e,sessions_dir)
    reports = e.recent(e.root / "data/agent-handoff", ["report.md"], e.cfg["maxHandoffReports"] * 4)
    ranked = []
    for p in reports:
        rel = p.relative_to(e.root).as_posix()
        # A bounded head determines failure priority, without loading large reports.
        head = "\n".join(text for i, (text, _) in enumerate(e.stream_lines(p)) if i < 100)
        ranked.append((bool(FAIL.search(head)), p.stat().st_mtime, p, head))
    ranked.sort(key=lambda row: row[:2], reverse=True)
    for failed, when, p, head in ranked[:e.cfg["maxHandoffReports"]]:
        rel = p.relative_to(e.root).as_posix()
        if p.stat().st_size <= e.file_limit:
            raw=e.capture(p)
            if raw is None:
                continue
            e.add(PREFIX + "agent/" + rel, raw.decode('utf-8','replace'),
                  90 if failed else 30, when, rel)
        else:
            e.copy_excerpt(p, PREFIX + "agent/" + rel + ".txt", 90 if failed else 30)
        e.event(when, "handoff report", rel, "failure/HOLD/PARTIAL marker" if failed else "report timestamp")
    for _, _, p, _ in ranked[e.cfg["maxHandoffReports"]:]:
        e.exclude(p.relative_to(e.root).as_posix(), "handoff report count cap")
    if no_codex:
        e.warn("agent/Codex", "disabled by --no-codex; no sessions opened")
        e.add(PREFIX + "agent/codex-project-failures.md", "# Codex project failures\nNOT_RUN: --no-codex\n", 100)
        e.add(PREFIX + "agent/codex-sessions-index.md", "# Codex sessions index\nNOT_RUN: --no-codex\n", 100)


def collect_environment(e, probe):
    output = ["# Environment", "", "packing time (KST): " + kst(e.now), "project root: " + str(e.root),
              "OS: " + platform.platform(), "python: " + sys.version.split()[0]]
    wrapper = e.root / "gradle/wrapper/gradle-wrapper.properties"
    if safe_file(wrapper) and wrapper.is_file():
        for line, _ in e.stream_lines(wrapper):
            if line.startswith("distributionUrl="):
                output.append("Gradle wrapper " + line)
    if probe:
        for label, args in (("java", ["java", "-version"]), ("node", ["node", "-v"]), ("git", ["git", "--version"])):
            try:
                result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=8)
                output.append(label + ": " + ((result.stdout + result.stderr).strip() if result.returncode == 0 else "not_observed"))
            except (OSError, subprocess.TimeoutExpired):
                output.append(label + ": not_observed")
                e.warn("environment", label + " version unavailable")
        try:
            result = subprocess.run(["netstat", "-ano", "-p", "tcp"], capture_output=True, text=True, timeout=8)
            ports = {int(m.group(1)) for line in result.stdout.splitlines() if "LISTENING" in line
                     for m in [re.search(r"\S+:(\d+)\s", line)] if m}
            output.append("LISTEN ports: " + ", ".join(f"{p}={'yes' if p in ports else 'no'}"
                                                      for p in (18180, 18181, 18182, 11434, 11435)))
        except (OSError, subprocess.TimeoutExpired):
            e.warn("environment", "listen ports not_observed")
    else:
        e.warn("environment", "version/port commands NOT_RUN (fixture probe disabled)")
    output.append("Environment variable names only: " + ", ".join(sorted(
        name for name in os.environ if name.startswith(("AWX_", "DEMO_", "GPTPRO_")))))
    output.append("No environment values, actual .env files, credentials, DBs or model files were opened.")
    e.add(PREFIX + "environment/env.md", "\n".join(output) + "\n", 100)


def legacy_finalize(e, included):
    # The budget applies to evidence only; existing source/context are never removed.
    # Reserve room for the three root documents, then evict success/old run/report first.
    reserve = min(e.limit // 4, e.file_limit * 3)
    while sum(len(t.encode("utf-8")) for t in e.files.values()) > e.limit - reserve and e.files:
        victim = min(e.files, key=lambda name: (*e.attrs[name], name))
        e.exclude(victim, "evidence total byte cap; priority then oldest")
        del e.files[victim]
    counts, sizes = Counter(), Counter()
    for name, text in e.files.items():
        category = name[len(PREFIX):].split("/")[0]
        counts[category] += 1
        sizes[category] += len(text.encode("utf-8"))
    e.trace_counts, e.trace_paths = Counter(), {}
    for name, text in e.files.items():
        for label, pattern in COUNTS.items():
            n = len(pattern.findall(text))
            e.trace_counts[label] += n
            if n:
                e.trace_paths.setdefault(label, name)
    e.package["total files"] = len(included) + e.package.get("contextFiles", 0) + len(e.files) + 5
    document = ["# GPTPRO_EVIDENCE_MANIFEST", "", "## Package information", "",
                "generated (KST): " + kst(e.now), "project root: " + str(e.root)]
    document += [f"{k}: {v}" for k, v in e.package.items() if k not in {'external_payloads'}]
    document += [f"source files: {len(included)}", f"evidence files: {len(e.files) + 3}",
                 f"evidence bytes before root documents: {sum(sizes.values())}", "",
                 "## Included scope", "", "| Scope | Included | Files | Bytes |", "|---|---|---:|---:|"]
    config_files = [(p, n) for p, n in included if p.startswith(("configs/", "gradle/")) or p.endswith((".properties", ".yml", ".yaml"))]
    document.append(f"| source | yes | {len(included)} | {sum(n for _, n in included)} |")
    document.append(f"| configuration (source subset) | yes | {len(config_files)} | {sum(n for _, n in config_files)} |")
    for category in ("git", "debug", "tests", "agent", "environment"):
        document.append(f"| {category} | {'yes' if counts[category] else 'not_observed; see warnings'} | {counts[category]} | {sizes[category]} |")
    document += ["", "## Failure trace counts", "", "Text matches are observations, not root-cause findings.",
                 "", "| Trace | Count | Representative file |", "|---|---:|---|"]
    for label in COUNTS:
        document.append(f"| {label} | {e.trace_counts[label]} | {e.trace_paths.get(label, 'not_observed')} |")
    test_path=PREFIX+'tests/test-summary.md' if PREFIX+'tests/test-summary.md' in e.files else 'omitted: over_budget'
    codex_path=PREFIX+'agent/codex-project-failures.md' if PREFIX+'agent/codex-project-failures.md' in e.files else 'omitted: over_budget'
    document += [f"| test failure (observed) | {e.test_failures} | {test_path} |",
                 f"| Codex command failure (observed) | {e.codex_failures} | {codex_path} |",
                 f"| Codex tool failure (observed) | {e.codex_tool_failures} | {codex_path} |",
                 "", "## Excerpts", "", "| Copy | Provenance |", "|---|---|"]
    document += [f"| {name} | {header} |" for name, header in e.excerpts if name in e.files] or ["| none | none |"]
    document += ["", "## Exclusions and reasons", "", "| Path/category | Reason |", "|---|---|",
                 "| .env / .secrets / credential / service-account / providers.json / key material | unopened |",
                 "| node_modules / .gradle / .git / build binaries / DB / ZIP | ordinary exclusions remain |",
                 "| large original logs / global Codex rollouts | only selected sanitized excerpts; originals excluded |"]
    document += [f"| {p} | {reason} |" for p, reason in e.excluded]
    document += ["", "## Redaction counts", ""]
    document += [f"- {name}: {count}" for name, count in sorted(e.redactions.items())] or ["- total: 0"]
    document += ["", "## Start here", "", "1. Read this manifest and PACK_WARNINGS.txt.",
                 "2. Check tests/test-summary.md, failures and red-green.md.",
                 "3. Read agent/codex-project-failures.md and handoff reports.",
                 "4. Compare debug excerpts with DEBUG_TIMELINE.md timestamps.",
                 "5. Inspect git evidence, then the unchanged core sources.", ""]
    timeline = ["# DEBUG_TIMELINE", "", "Timestamp observations only; no causal attribution.", "",
                "| Time (KST) | Kind | Evidence path | One line |", "|---|---|---|---|"]
    for when, kind, path, summary in sorted(e.timeline, reverse=True)[:int(e.cfg["maxTimelineRows"])]:
        if path.startswith(PREFIX) and path not in e.files:
            continue
        timeline.append(f"| {when} | {kind} | {path} | {summary.replace('|', '/')} |")
    doc_limit=min(e.file_limit,max(512,e.limit//10))
    doc='\n'.join(document)
    e.add("GPTPRO_EVIDENCE_MANIFEST.md", doc.encode()[:doc_limit].decode('utf-8','ignore'), 110)
    e.add("DEBUG_TIMELINE.md", "\n".join(timeline) + "\n", 110)
    for category in ("debug", "tests", "agent", "environment"):
        if not counts[category]:
            e.warn(category, "no retained files; see budget/exclusions")
    warning_counts = Counter(e.warnings)
    warning_text = "\n".join(reason + (f" [x{count}]" if count > 1 else "")
                              for reason, count in warning_counts.items()) if e.warnings else "0 warnings"
    e.add("PACK_WARNINGS.txt", warning_text + "\n", 110)
    if sum(len(t.encode("utf-8")) for t in e.files.values()) > e.limit:
        raise ValueError("evidence document budget exceeded")
    e.meta = dict(files=len(e.files), bytes=sum(len(t.encode("utf-8")) for t in e.files.values()),
                  categories=dict(counts), redactions=sum(e.redactions.values()),
                  securityExcluded=sum("secret-suspect" in reason for _, reason in e.excluded),
                  warnings=len(e.warnings), testFailures=e.test_failures, codexFailures=e.codex_failures,
                  codexToolFailures=e.codex_tool_failures,
                  unrelatedSessions=e.unrelated_sessions, **e.package)
    e.meta['evidence_schema_version'] = 2
    e.meta['eventCounts'] = dict(observed=len(e.events), unique=len(e.events), retained=len(e.events),
                                 failures=sum(r['observed_status'] == 'failure' for r in e.events))
    return e


def serialize(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))+'\n'


def bounded_metadata(name,value,limit,files):
    """Page complete JSON values; references carry the exact child digest."""
    counter=[0]
    stem=hashlib.sha256(name.encode()).hexdigest()[:8]
    def child(value):
        counter[0]+=1
        path=PREFIX+'manifests/'+stem+'-'+str(counter[0])+'.json'
        emit(path,value)
        raw=files[path].encode()
        return dict(path=path,bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
    def emit(path,value):
        if len(serialize(value).encode())<=limit:
            files[path]=serialize(value)
            return
        if not isinstance(value,(dict,list)):
            raise ValueError('mandatory metadata scalar exceeds file budget')
        is_dict=isinstance(value,dict)
        chunks,part=[],{} if is_dict else []
        for key,item in value.items() if is_dict else enumerate(value):
            single={key:item} if is_dict else [item]
            if len(serialize(single).encode())>limit:
                item={'value_ref':child(item)}
            candidate={**part,key:item} if is_dict else part+[item]
            if part and len(serialize(candidate).encode())>limit:
                chunks.append(child(part))
                part={} if is_dict else []
                candidate={key:item} if is_dict else [item]
            part=candidate
        if part:
            chunks.append(child(part))
        kind='object_parts' if is_dict else 'list_parts'
        node={kind:chunks}
        if len(serialize(node).encode())>limit:
            node={kind+'_ref':child(chunks)}
        if len(serialize(node).encode())>limit:
            raise ValueError('mandatory metadata reference exceeds file budget')
        files[path]=serialize(node)
    emit(name,value)


def finalize(e, included):
    observed = sorted(e.events, key=lambda r: (r['occurred_at']['utc'] or '',
                      r['source_ref']['relative_path'], r['source_ref']['original_lines'][0], r['event_id']))
    included_paths={item[0] for item in included}
    captured_hashes={p['path']:p['sha256'] for p in e.package.get('external_payloads',[])}
    for row in observed:
        anchor=row.get('source_anchor')
        if anchor and anchor.get('kind')=='current':
            present=anchor['path'] in captured_hashes if 'external_payloads' in e.package else anchor['path'] in included_paths
            anchor['status']='present' if present else 'current_source_missing'
            anchor['sha256']=captured_hashes.get(anchor['path'],anchor.get('sha256')) if present else None
            if not present:
                e.scan_reasons['source_missing']+=1
    groups = {}
    for row in observed:
        key = serialize([row['event_type'],row['observed_status'],row['payload'],row.get('source_anchor'),row.get('verification_context')])
        if key not in groups:
            groups[key] = dict(row, occurrences=0, first_occurrence=row['occurred_at']['utc'], last_occurrence=row['occurred_at']['utc'])
        groups[key]['occurrences'] += 1
        groups[key]['last_occurrence'] = row['occurred_at']['utc']
    rows = list(groups.values())
    previous={}
    for row in rows:
        tests=row['payload'].get('selected_tests')
        if row['event_type']!='test_result' or not tests:
            continue
        key=serialize(tests)
        if row['observed_status']=='failure':
            previous[key]=row
        elif row['observed_status']=='success' and key in previous:
            red=previous[key]
            before,after=red.get('verification_context',{}),row.get('verification_context',{})
            proof=(all(before.get(k) and before.get(k)==after.get(k) for k in ('run_id','workdir','argv_hash')) and
                   before.get('source_after') and before.get('source_after')==after.get('source_before') and after.get('source_after'))
            row['relations'].append(dict(relation_type='retest',event_id=red['event_id'],
                basis='verified' if proof else 'candidate',evidence='same selection/workdir/arguments/source receipts' if proof else 'time and test selection only'))
    # Allocate one representative per kind/day before filling remaining capacity.
    buckets = defaultdict(list)
    for row in rows:
        day = (row['occurred_at']['utc'] or 'unknown')[:10]
        buckets[(row['event_type'],day)].append(row)
    ranked = []
    while buckets:
        for key in sorted(list(buckets)):
            bucket = buckets[key]
            bucket.sort(key=lambda r: (r['observed_status'] != 'failure', r['event_id']))
            ranked.append(bucket.pop(0))
            if not bucket:
                del buckets[key]
    base = dict(e.files)
    omitted = 0
    # Legacy documents stay for CLI compatibility; regenerate from the final base set.
    while True:
        e.files = dict(base)
        legacy_finalize(e, included)
        base = {n:t for n,t in e.files.items() if n.startswith(PREFIX)}
        generated = {}
        shards = []
        retained_ids = {r['event_id'] for r in ranked}
        shard_number, shard_lines=1,[]
        for i,row in enumerate(ranked):
            name = PREFIX+'events/e'+str(shard_number).zfill(4)+'.ndjson'
            row['source_ref']['excerpt_path'], row['source_ref']['excerpt_lines'] = name, [len(shard_lines)+1]*2
            anchor=row.get('source_anchor')
            if anchor and anchor.get('kind')=='current':
                anchor['snapshot']=e.package.get('snapshot_id')
            for relation in row['relations']:
                if relation.get('event_id') not in retained_ids:
                    relation.update(target_status='omitted', reason='over_budget_or_duplicate')
            # Redact full text before clipping individual diagnostic values.
            cap=max(64,e.file_limit//4)
            if any(isinstance(v,str) and len(v.encode('utf-8'))>cap for v in row['payload'].values()):
                row['truncated']=True
            row['payload'] = {k:(v.encode('utf-8')[:cap].decode('utf-8','ignore')
                                if isinstance(v,str) else v) for k,v in row['payload'].items()}
            text = serialize(row)
            if len(text.encode()) > e.file_limit:
                row['payload'] = {'diagnostic':'omitted: event metadata/file cap'}
                row['truncated'] = True
                text = serialize(row)
            if len(text.encode()) > e.file_limit:
                raise ValueError('mandatory event metadata exceeds file budget')
            if len((''.join(shard_lines)+text).encode()) > e.file_limit and shard_lines:
                generated[name]=''.join(shard_lines)
                shards.append(name)
                shard_number+=1
                name=PREFIX+'events/e'+str(shard_number).zfill(4)+'.ndjson'
                shard_lines=[]
                row['source_ref']['excerpt_path'], row['source_ref']['excerpt_lines']=name,[1,1]
                text=serialize(row)
            shard_lines.append(text)
        if shard_lines:
            generated[name]=''.join(shard_lines)
            shards.append(name)
        reasons = {k:0 for k in ('outside_window','over_budget','duplicate','parse_error','permission_denied',
                                  'source_missing','unsupported_schema','scan_limit')}
        reasons.update(e.scan_reasons)
        reasons.update(over_budget=omitted,duplicate=len(observed)-len(rows))
        def tally(predicate):
            found=sum(predicate(r) for r in observed)
            unique=sum(predicate(r) for r in rows)
            retained=sum(predicate(r) for r in ranked)
            return dict(discovered=found,eligible=found,selected=retained,deduplicated=found-unique,
                        omitted=unique-retained,truncated=sum(predicate(r) and bool(r.get('truncated')) for r in ranked),
                        unknown=None)
        def window(row):
            when=timestamp(row['occurred_at']['utc'])
            return 'unknown_time' if when is None else 'recent' if when.timestamp()>=e.cutoff else 'history'
        coverage = dict(unit='eligible_observed_events',
                        events={**tally(lambda r:True), 'reasons':reasons},
                        windows=dict(recent_days=e.cfg['evidenceDays'],history_days=e.cfg['evidenceHistoryDays']),
                        by_kind={k:tally(lambda r:r['event_type']==k) for k in ('command','patch','test_result','source_change')},
                        by_window={k:tally(lambda r:window(r)==k) for k in ('recent','history','unknown_time')},
                        scan=dict(discovered_files=e.discovered_files,files=e.scan_files,bytes=e.scan_bytes,
                                  partial=bool(e.scan_reasons['scan_limit'])),effective_settings=e.cfg)
        coverage['eventCounts']=dict(observed=len(observed),unique=len(rows),retained=len(ranked),
                                     retained_failures=sum(r['observed_status']=='failure' for r in ranked))
        coverage['legacyCounters']=dict(codexFailures=e.codex_failures,codexToolFailures=e.codex_tool_failures,
                                        testFailures=e.test_failures)
        generated['coverage.json'] = serialize(coverage)
        generated['00_START_HERE.md'] = ('# Evidence schema 2\n\nRead coverage.json, PACK_WARNINGS.txt, manifest.json, then ALL event shards.\n'
            'Follow object/list parts and value_ref pages; preview covers 40 events.\n'
            'Cite source:line, excerpt ranges and ZIP hashes. current_source_missing / historical_source_missing: request only the needed path/snapshot.\n'
            'Facts, candidate links, unknown and NOT_RUN differ; time proves no cause.\n'
            'Never reconstruct raw conversations or hidden reasoning.\n')
        if len(generated['coverage.json'].encode())>e.file_limit:
            bounded_metadata('coverage.json',coverage,e.file_limit,generated)
        combined = {**e.files, **generated}
        manifest = dict(evidence_schema_version=2, snapshot={k:v for k,v in e.package.items() if k!='external_payloads'},
                        settings_ref='coverage.json#effective_settings',
                        event_shards=shards,preview=[r['event_id'] for r in ranked[:40]],
                        nondeterministic_fields=['snapshot.captured_at','source_ref.observed_at','scan.time_limit_hit'],
                        legacy_counts='codexFailures: failed command attempts; codexToolFailures: explicit tool failures; testFailures: failed XML cases; observed collection counts, not retained counts',
                        payloads=[dict(path=n,bytes=len(t.encode()),sha256=hashlib.sha256(t.encode()).hexdigest(),
                                       snapshot=e.package.get('snapshot_id')) for n,t in sorted(combined.items())]+e.package.get('external_payloads',[]))
        if len(serialize(manifest).encode()) > e.file_limit:
            entries=manifest['payloads']
            manifest['payloads'],manifest['payload_shards']=[],[]
            chunk=[]
            for entry in entries:
                if chunk and len(serialize({'payloads':chunk+[entry]}).encode()) > e.file_limit:
                    name=PREFIX+'manifests/m'+str(len(manifest['payload_shards'])+1).zfill(4)+'.json'
                    bounded_metadata(name,{'payloads':chunk},e.file_limit,generated)
                    manifest['payload_shards'].append(dict(path=name,bytes=len(generated[name].encode()),sha256=hashlib.sha256(generated[name].encode()).hexdigest()))
                    chunk=[]
                chunk.append(entry)
            if chunk:
                name=PREFIX+'manifests/m'+str(len(manifest['payload_shards'])+1).zfill(4)+'.json'
                bounded_metadata(name,{'payloads':chunk},e.file_limit,generated)
                manifest['payload_shards'].append(dict(path=name,bytes=len(generated[name].encode()),sha256=hashlib.sha256(generated[name].encode()).hexdigest()))
        if len(serialize(manifest).encode())>e.file_limit:
            preview=manifest.pop('preview')
            name=PREFIX+'manifests/preview.json'
            bounded_metadata(name,{'preview':preview},e.file_limit,generated)
            manifest['preview_ref']=dict(path=name,bytes=len(generated[name].encode()),sha256=hashlib.sha256(generated[name].encode()).hexdigest())
        generated['manifest.json'] = serialize(manifest)
        for name,text in list(generated.items()):
            if len(text.encode())>e.file_limit:
                if not name.endswith('.json'):
                    raise ValueError('mandatory evidence metadata exceeds file budget')
                bounded_metadata(name,json.loads(text),e.file_limit,generated)
        total = sum(len(t.encode()) for t in {**e.files,**generated}.values())
        if total <= e.limit:
            break
        if base:
            victim = min(base, key=lambda n: (*e.attrs[n],n))
            raw=base[victim].encode('utf-8')
            if e.attrs[victim][0]>=75 and len(raw)>512:
                # Preserve a bounded failure receipt after lower-priority copies are gone.
                cap=max(512,len(raw)-(total-e.limit)-256)
                note='\n[truncated: evidence total byte cap; original redacted bytes='+str(len(raw))+']\n'
                base[victim]=raw[:max(0,cap-len(note.encode()))].decode('utf-8','ignore')+note
                e.exclude(victim,'over_budget: retained truncated redacted receipt')
                e.excerpts.append((victim,'total-byte-cap excerpt; source begins at line 1; omitted tail'))
            else:
                del base[victim]
                e.exclude(victim,'over_budget')
        elif ranked:
            ranked.pop()
            omitted += 1
        else:
            raise ValueError('mandatory evidence metadata exceeds budget')
    e.files.update(generated)
    e.events = ranked
    e.meta.update(evidence_schema_version=2, files=len(e.files), bytes=total,
                  eventCounts=dict(observed=len(observed),unique=len(rows),retained=len(ranked),
                                   failures=sum(r['observed_status']=='failure' for r in ranked)), coverage=coverage)
    counts=Counter(n[len(PREFIX):].split('/')[0] for n in e.files if n.startswith(PREFIX))
    e.meta['categories']=dict(counts)
    return e


def build(root, cfg, *, git, scan, included, changed, package=None,
          no_codex=False, sessions_dir=None, now=None, probe_environment=True):
    e = Evidence(Path(root), cfg, scan, now or datetime.now(KST), dict(package or {}))
    e.changed=set(changed)
    for section, call in (
        ("git", lambda: collect_git(e, git, changed)),
        ("agent", lambda: collect_agent(e, Path(sessions_dir or Path.home() / ".codex/sessions"), no_codex)),
        ("tests", lambda: collect_tests(e)),
        ("debug", lambda: collect_debug(e)),
        ("environment", lambda: collect_environment(e, probe_environment)),
    ):
        try:
            if section!='git':
                if section=='agent':
                    e.scan_started=time.monotonic()
                e.begin_section()
            call()
        except Exception as error:
            # Never print exception messages: they can contain source data or credentials.
            e.warn(section, type(error).__name__ + ": section collection failed")
    return finalize(e, included)
