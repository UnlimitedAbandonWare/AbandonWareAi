"""Offline, report-only Codex export review. No session discovery or model execution.

Only the explicitly configured normalized export contract is supported; this is
not an assumed native Codex export format. See DEMO1-CODEX-NIGHTLY-REVIEW.md.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import date, datetime, time, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import statistics
import sys
import uuid

from codex_session_friction import TRUNC_TOKEN_RE, file_in_window
from log_redact import redact_text

VERSION = 'codex-nightly-review-v1'
KST = timezone(timedelta(hours=9))  # Does not require a Windows tzdata install.
KINDS = {'goal', 'correction', 'cancel', 'tool', 'error', 'change', 'verify'}
HUMAN_KINDS = {'goal', 'correction', 'cancel'}
EVENT_FIELDS = {'timestamp', 'sessionId', 'eventId', 'kind', 'origin', 'sanitized', 'text', 'outcome'}
MAX_STATE_BYTES = 16 * 1024 * 1024
OUTPUT_CAP_P90_WARN = 10000
PROJECT_ROOT = Path(__file__).resolve().parents[1]


class ReviewError(ValueError):
    """Reason codes only: never include a source line, path or exception text."""


def require(condition, reason):
    if not condition:
        raise ReviewError(reason)


def encoded(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(',', ':')) + '\n').encode()


def digest(value):
    return hashlib.sha256(value if isinstance(value, bytes) else encoded(value)).hexdigest()


def no_links(path):
    for component in (path, *path.parents):
        try:
            info = component.lstat()
        except FileNotFoundError:
            continue
        require(not stat.S_ISLNK(info.st_mode) and not (
            getattr(info, 'st_file_attributes', 0) & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0)),
            'linked_input')
        if stat.S_ISREG(info.st_mode):
            require(info.st_nlink == 1, 'linked_input')


def safe_path(value):
    require(isinstance(value, str) and bool(value), 'absolute_path_required')
    path = Path(value)
    require(path.is_absolute() and '..' not in path.parts and not value.startswith(('\\\\', '//')),
            'absolute_path_required')
    for part in path.parts:
        low = part.lower()
        require(low not in {'.git', '.secrets', '.ssh', 'secrets', 'auth.json'} and
                not low.startswith('.env') and not low.endswith(('.pem', '.key', '.pfx', '.p12', '.jks')),
                'sensitive_path')
    no_links(path)
    return path.resolve()


def contained(path, root):
    return path == root or root in path.parents


def stable_read(path, limit):
    no_links(path)
    with path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode), 'regular_file_required')
        require(before.st_size <= limit, 'file_budget')
        raw = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
    no_links(path)
    latest = path.stat()
    signature = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
    require(signature(before) == signature(after) == signature(latest), 'unstable_input')
    require(len(raw) <= limit, 'file_budget')
    return raw


def read_json(path, limit=MAX_STATE_BYTES):
    try:
        return json.loads(stable_read(path, limit).decode('utf-8-sig'))
    except (UnicodeError, json.JSONDecodeError):
        raise ReviewError('invalid_json') from None


def atomic_write(path, raw, *, replace=True):
    """Same-volume replace, with no raw source or AGY stream retained."""
    no_links(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    no_links(path)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temporary.open('xb') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        if replace:
            os.replace(temporary, path)
        else:
            try:
                if os.name == 'nt':
                    # Windows rename refuses an existing destination atomically.
                    os.rename(temporary, path)
                else:
                    os.link(temporary, path)  # POSIX exclusive publication.
                    temporary.unlink()
            except FileExistsError:
                raise ReviewError('existing_output_changed') from None
        require(stable_read(path, max(len(raw), 1)) == raw, 'storage_verification')
    finally:
        if temporary.exists():
            temporary.unlink()


def immutable_write(path, raw):
    if path.exists():
        require(stable_read(path, max(len(raw), 1)) == raw, 'existing_output_changed')
    else:
        atomic_write(path, raw, replace=False)


@contextmanager
def run_lock(root):
    root.mkdir(parents=True, exist_ok=True)
    lock = root / 'run.lock'
    no_links(lock)
    try:
        stream = lock.open('xb')
    except FileExistsError:
        raise ReviewError('run_locked') from None
    raw = encoded({'pid': os.getpid(), 'nonce': uuid.uuid4().hex})
    try:
        with stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        yield
    finally:
        # Never reclaim another owner or overwrite a lock with unknown contents.
        if lock.exists() and stable_read(lock, 4096) == raw:
            lock.unlink()


def load_config(path):
    cfg = read_json(safe_path(str(Path(path).absolute())), 65536)
    require(isinstance(cfg, dict) and cfg.get('schemaVersion') == 'codex-nightly-config-v1', 'config_schema')
    require(cfg.get('mode') == 'report-only' and cfg.get('timezone') == 'Asia/Seoul', 'config_mode')
    require(cfg.get('aiEnabled') is False and cfg.get('codexFallback') is False, 'live_execution_not_supported')
    require(type(cfg.get('enabled')) is bool, 'config_enabled')
    if not cfg['enabled']:
        return cfg
    for name, maximum in [('maxCatchupDays', 31), ('maxFileBytes', 16 * 1024 * 1024),
                          ('maxInputBytes', 64 * 1024 * 1024),
                          ('maxEvents', 100000), ('maxContextEvents', 100),
                          ('maxTextChars', 8000), ('maxPacketChars', 100000), ('maxCandidates', 100)]:
        require(type(cfg.get(name)) is int and 1 <= cfg[name] <= maximum, 'config_budget')
    try:
        date.fromisoformat(cfg['firstDate'])
    except (ValueError, TypeError, KeyError):
        raise ReviewError('first_date_required') from None
    model = cfg.get('requestedModel')
    require(model is None or (isinstance(model, str) and
            re.fullmatch(r'[A-Za-z0-9_.:/-]{1,120}', model)), 'requested_model_required')
    require(isinstance(cfg.get('allowedRoots'), list) and 0 < len(cfg['allowedRoots']) <= 16,
            'input_allowlist_required')
    roots = [safe_path(p) for p in cfg['allowedRoots']]
    require(all(p.is_dir() for p in roots), 'input_root_unavailable')
    manifest = safe_path(cfg.get('inputManifest'))
    require(any(contained(manifest, p) for p in roots), 'manifest_outside_allowlist')
    output = safe_path(cfg.get('outputRoot'))
    delivery = safe_path(cfg['deliveryRoot']) if cfg.get('deliveryRoot') else None
    for dest in [output] + ([delivery] if delivery else []):
        require(not any(contained(dest, p) or contained(p, dest) for p in roots), 'output_input_overlap')
    if delivery:
        require(not contained(delivery, output) and not contained(output, delivery), 'output_delivery_overlap')
    project = safe_path(str(PROJECT_ROOT))
    require(output != project and contained(output, project), 'review_output_outside_project')
    if delivery:
        require(delivery != project and contained(delivery, project), 'review_delivery_outside_project')
    return cfg


def timestamp(value):
    require(isinstance(value, str), 'event_timestamp')
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        raise ReviewError('event_timestamp') from None
    require(parsed.tzinfo is not None, 'event_timestamp')
    return parsed.astimezone(KST)


def clean_text(value, limit):
    require(isinstance(value, str) and len(value) <= limit, 'text_budget')
    require(not re.search(r'-----BEGIN .*PRIVATE KEY|<environment_dump>|<private_reasoning>', value, re.I),
            'unsafe_text')
    text, _ = redact_text(value)
    # Additional privacy reduction for deliberately prepared summaries.
    text = re.sub(r'https?://[^\s<>]+', '[URL_REDACTED]', text, flags=re.I)
    text = re.sub(r'[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}', '[EMAIL_REDACTED]', text)
    return text


def normalize_event(row, file_id, line, cfg):
    require(isinstance(row, dict) and not set(row) - EVENT_FIELDS, 'event_schema')
    kind = row.get('kind')
    require(kind in KINDS and row.get('sanitized') is True, 'event_not_approved')
    require(row.get('origin') == ('human' if kind in HUMAN_KINDS else 'tool'), 'event_provenance')
    for name in ('sessionId', 'eventId'):
        require(isinstance(row.get(name), str) and 0 < len(row[name]) <= 160, 'event_identity')
    outcome = row.get('outcome', 'UNKNOWN')
    require(outcome in {'PASS', 'FAIL', 'NOT_RUN', 'UNKNOWN'}, 'event_outcome')
    when = timestamp(row.get('timestamp'))
    summary = clean_text(row.get('text'), cfg['maxTextChars'])
    identity = digest([row['sessionId'], row['eventId']])
    content = digest([when.isoformat(), kind, summary, outcome])
    return {'key': identity, 'contentHash': content, 'session': digest(row['sessionId'])[:16],
            'timestamp': when.isoformat(), 'kind': kind, 'text': summary, 'outcome': outcome,
            'source': {'fileId': file_id, 'line': line, 'eventHash': identity}}


def collect(cfg, seen, start, end):
    """Validate boundaries before opening each file. Whole-file errors remain visible."""
    coverage = {'listedFiles': 0, 'readFiles': 0, 'indexedEvents': 0,
                'duplicates': 0, 'outsideWindow': 0, 'excludedEvents': 0, 'failedFiles': 0,
                'aiReviewedEvents': 0, 'files': []}
    try:
        raw = stable_read(safe_path(cfg['inputManifest']), 1024 * 1024)
        manifest = json.loads(raw.decode('utf-8-sig'))
        require(isinstance(manifest, dict) and manifest.get('schemaVersion') == 'codex-review-input-v1'
                and isinstance(manifest.get('files'), list) and len(manifest['files']) <= 500, 'manifest_schema')
    except (ReviewError, OSError, ValueError, UnicodeError) as error:
        reason = str(error) if isinstance(error, ReviewError) else (
            'input_unavailable' if isinstance(error, OSError) else 'manifest_schema')
        return digest(['manifest_unavailable', reason]), [], dict(seen), coverage, [reason]
    coverage['listedFiles'] = len(manifest['files'])
    holds, events, identities, total, input_bytes = [], [], dict(seen), 0, 0
    if not manifest['files']:
        holds.append('empty_input')
    roots = [safe_path(p) for p in cfg['allowedRoots']]
    for number, entry in enumerate(manifest['files']):
        file_id = digest(entry.get('fileId', number) if isinstance(entry, dict) else number)[:16]
        file_row = {'fileId': file_id, 'status': 'NOT_READ'}
        coverage['files'].append(file_row)
        try:
            require(isinstance(entry, dict), 'manifest_entry')
            require(entry.get('sourceType') == 'codex-session-export', 'source_type')
            require(entry.get('format') == 'codex-review-events-v1', 'unsupported_format')
            path = safe_path(entry.get('path'))
            require(any(contained(path, root) for root in roots), 'outside_allowlist')
            require(path.suffix == '.jsonl', 'unsupported_format')
            require(type(entry.get('sizeBytes')) is int and
                    0 <= entry['sizeBytes'] <= cfg['maxFileBytes'], 'file_budget')
            input_bytes += entry['sizeBytes']
            require(input_bytes <= cfg['maxInputBytes'], 'input_byte_budget')
            require(isinstance(entry.get('sha256'), str) and
                    re.fullmatch('[a-f0-9]{64}', entry['sha256']), 'manifest_hash')
            data = stable_read(path, cfg['maxFileBytes'])
            require(len(data) == entry['sizeBytes'] and digest(data) == entry['sha256'], 'snapshot_mismatch')
            coverage['readFiles'] += 1
            require(not data or data.endswith(b'\n'), 'partial_write')
            lines = data.decode('utf-8').splitlines()
            if not lines:
                holds.append('empty_input')
            for line, raw_event in enumerate(lines, 1):
                total += 1
                require(total <= cfg['maxEvents'], 'event_budget')
                try:
                    row = json.loads(raw_event)
                except ValueError:
                    raise ReviewError('invalid_json') from None
                event = normalize_event(row, file_id, line, cfg)
                identity, content = event['key'], event['contentHash']
                if identity in identities:
                    require(identities[identity] == content, 'event_identity_conflict')
                    coverage['duplicates'] += 1
                    continue
                when = timestamp(event['timestamp'])
                if when < start:
                    holds.append('late_event')
                    coverage['excludedEvents'] += 1
                    continue
                if when >= end:
                    coverage['outsideWindow'] += 1
                    continue
                identities[identity] = content
                events.append(event)
                coverage['indexedEvents'] += 1
            file_row['status'] = 'INDEXED'
        except (ReviewError, OSError, UnicodeError) as error:
            reason = str(error) if isinstance(error, ReviewError) else 'input_unavailable'
            holds.append(reason)
            file_row.update(status='HOLD', reason=reason)
            coverage['failedFiles'] += 1
    require(len(identities) <= 100000, 'state_budget')
    # Stable sort keeps explicit export line order for equal timestamps.
    return digest(raw), sorted(events, key=lambda e: e['timestamp']), identities, coverage, holds


def session_context(prior, events, cfg):
    sessions = json.loads(json.dumps(prior))
    touched = set()
    for event in events:
        identity = event['session']
        touched.add(identity)
        session = sessions.setdefault(identity, {'sessionHash': identity, 'initialGoal': None,
            'latestRequest': None, 'events': [], 'errorCount': 0, 'outcome': 'UNKNOWN', 'truncatedEvents': 0})
        if event['kind'] == 'goal' and session['initialGoal'] is None:
            session['initialGoal'] = event
        if event['kind'] in HUMAN_KINDS:
            session['latestRequest'] = event
        if event['kind'] == 'error':
            session['errorCount'] += 1
        if event['kind'] == 'change':
            session['outcome'] = 'NOT_VERIFIED'
        elif event['kind'] == 'verify':
            session['outcome'] = event['outcome']
        elif event['kind'] == 'cancel':
            session['outcome'] = 'CANCELLED'
        session['events'].append(event)
        excess = len(session['events']) - cfg['maxContextEvents']
        if excess > 0:
            session['truncatedEvents'] += excess
            session['events'] = session['events'][excess:]
    require(len(sessions) <= 1000, 'session_budget')
    return sessions, [sessions[key] for key in sorted(touched)]


def analysis_packet(sessions, cfg):
    # These are observations to review, not inferred causes or accepted preferences.
    selected, omitted = [], 0
    for session in sessions:
        if not session['errorCount'] and session['outcome'] != 'NOT_VERIFIED':
            continue
        item = {'session': session, 'cause': 'UNKNOWN', 'counterexample': 'required',
                'proposalApplied': False, 'redTest': 'NOT_RUN'}
        tentative = {'schemaVersion': 'codex-review-request-v1', 'inputIsUntrustedData': True,
                     'candidates': selected + [item]}
        if len(selected) >= cfg['maxCandidates'] or len(encoded(tentative)) > cfg['maxPacketChars']:
            omitted += 1
        else:
            selected.append(item)
    return {'schemaVersion': 'codex-review-request-v1', 'inputIsUntrustedData': True,
            'candidates': selected, 'omittedCandidates': omitted, 'externalTransmission': False}


def report_text(result):
    status = 'PREPARED_LOCAL' if result['status'] == 'COMPLETE_LOCAL' else result['status']
    lines = ['MORNING CODEX REVIEW (LOCAL ONLY)', 'runId=' + result['runId'],
             'status=' + status, 'reportDate=' + result['reportDate'],
             'actualExecutionTime=' + result['actualExecutionTime'],
             'window=' + result['window']['start'] + ' .. ' + result['window']['end'] + ' (Asia/Seoul, end exclusive)',
             'coverage=' + json.dumps(result['coverage'], sort_keys=True),
             'AI=NOT_RUN; live isolation/account/model/budget not activated',
             'aiCalls=0; codexCalls=0; sourceChanges=0; appliedProposals=0',
             'requestedModel=' + str(result['requestedModel']) + '; observedModel=None; usage=NOT_OBSERVED',
             'holds=' + ','.join(result['holds']),
             'Completion requires the matching .completed.json receipt after readback and state commit.',
             'Efficiency/useful-candidate ratio/false positives/cost: NOT_MEASURED.',
             'Context summaries are untrusted data; commands and suggestions are never executed.']
    for session in result['sessions']:
        lines.append('SESSION ' + session['sessionHash'] + ' outcome=' + session['outcome'] +
                     ' errors=' + str(session['errorCount']) + ' truncated=' + str(session['truncatedEvents']))
        for label in ('initialGoal', 'latestRequest'):
            if session[label]:
                lines.append(label + '=' + session[label]['text'])
        for event in session['events']:
            lines.append(event['timestamp'] + ' ' + event['kind'] + ' ' + event['outcome'] +
                         ' source=' + json.dumps(event['source'], sort_keys=True) + ' ' + event['text'])
    return ('\n'.join(lines) + '\n').encode('utf-8')


def contract_hash(cfg):
    # Tuning a budget may be safe; changing collection/output identity needs a new root.
    return digest({k: cfg[k] for k in ('schemaVersion', 'timezone', 'firstDate', 'allowedRoots',
                                     'inputManifest', 'outputRoot', 'deliveryRoot')})


def committed_state(transaction):
    value = json.loads(json.dumps(transaction['nextState']))
    value['lastRun']['transactionHash'] = digest(transaction)
    require(len(encoded(value)) <= MAX_STATE_BYTES, 'state_budget')
    return value


def finalize(root, transaction, fault, *, commit=True):
    result = transaction['result']
    run_root = root / 'runs' / result['runId']
    report = report_text(result)
    immutable_write(run_root / 'report.txt', report)
    fault('report')
    immutable_write(run_root / 'coverage.json', encoded(result['coverage']))
    immutable_write(run_root / 'analysis-request.json', encoded(transaction['packet']))
    immutable_write(run_root / 'receipt.json', encoded({'reportHash': digest(report), 'runId': result['runId']}))
    fault('receipt')
    if result['deliveryPath']:
        immutable_write(safe_path(result['deliveryPath']), report)
    fault('delivery')
    if result['status'] == 'COMPLETE_LOCAL':
        fault('state')
        next_state = committed_state(transaction)
        if commit:
            atomic_write(root / 'state.json', encoded(next_state))
        completion = {'status': 'COMPLETE_LOCAL', 'runId': result['runId'],
                      'reportHash': digest(report), 'stateHash': digest(next_state), 'aiCalls': 0}
        immutable_write(run_root / 'completed.json', encoded(completion))
        immutable_write(safe_path(result['deliveryPath'] + '.completed.json'), encoded(completion))
    immutable_write(run_root / 'validation.json', encoded(result))
    return result


def run(config_path, *, now=None, fault=None):
    cfg = load_config(config_path)
    if not cfg['enabled']:
        return {'status': 'DISABLED', 'aiCalls': 0, 'codexCalls': 0, 'schedulerRegistered': False}
    now = now or datetime.now(KST)
    require(now.tzinfo is not None, 'aware_time_required')
    now = now.astimezone(KST)
    fault = fault or (lambda stage: None)
    root = safe_path(cfg['outputRoot'])
    with run_lock(root):
        boundary = contract_hash(cfg)
        state_path = root / 'state.json'
        state = read_json(state_path) if state_path.exists() else {
            'schemaVersion': VERSION, 'contractHash': boundary, 'watermark':
            datetime.combine(date.fromisoformat(cfg['firstDate']), time(), KST).isoformat(),
            'seen': {}, 'sessions': {}, 'lastRun': None}
        require(isinstance(state, dict) and state.get('schemaVersion') == VERSION and
                state.get('contractHash') == boundary and isinstance(state.get('seen'), dict) and
                isinstance(state.get('sessions'), dict), 'state_contract_changed')
        last = state.get('lastRun')
        saved = None
        if last:
            require(isinstance(last, dict) and isinstance(last.get('runId'), str) and
                    re.fullmatch('[a-f0-9]{24}', last['runId']), 'state_schema')
            saved = read_json(root / 'runs' / last['runId'] / 'transaction.json')
            require(digest(saved) == last.get('transactionHash') and
                    committed_state(saved) == state, 'transaction_changed')
            # Finish a committed run's receipts before starting any later interval.
            # Never replace current state from a historical transaction here.
            finalize(root, saved, lambda stage: None, commit=False)
        cutoff = datetime.combine(now.date(), time(), KST)
        config_hash = digest(cfg)
        start = timestamp(state['watermark'])
        require(start <= cutoff, 'future_watermark')
        end = min(cutoff, start + timedelta(days=cfg['maxCatchupDays']))
        manifest_hash, events, seen, coverage, holds = collect(cfg, state['seen'], start, end)
        if last and not holds and last['manifestHash'] == manifest_hash and last['configHash'] == config_hash \
                and last['cutoff'] == cutoff.isoformat() and start == cutoff:
            return dict(saved['result'], status='ALREADY_COMPLETE')
        sessions, touched = session_context(state['sessions'], events, cfg)
        packet = analysis_packet(touched, cfg)
        if not cfg.get('deliveryRoot'):
            holds.append('delivery_not_configured')
        # Budget-limited context is visible independently of whole local indexing.
        identity = digest([VERSION, config_hash, manifest_hash, start.isoformat(), end.isoformat(),
                           coverage, sorted(set(holds))])[:24]
        run_root = root / 'runs' / identity
        transaction_path = run_root / 'transaction.json'
        result = {'schemaVersion': VERSION, 'status': 'HOLD' if holds else 'COMPLETE_LOCAL',
                'runId': identity, 'reportDate': (end - timedelta(microseconds=1)).date().isoformat(),
                'actualExecutionTime': now.isoformat(), 'scheduledTime': 'NOT_CONFIGURED',
                'window': {'start': start.isoformat(), 'end': end.isoformat(), 'cutoff': cutoff.isoformat()},
                'catchupRemainingDays': max(0, (cutoff - end).days), 'coverage': coverage,
                'holds': sorted(set(holds)), 'sessions': touched, 'aiStatus': 'NOT_RUN',
                'aiCalls': 0, 'codexCalls': 0, 'requestedModel': cfg.get('requestedModel'),
                'observedModel': None, 'usage': None, 'sourceChanges': 0, 'proposalApplied': False,
                'schedulerRegistered': False, 'runRoot': str(run_root), 'deliveryPath':
                str(Path(cfg['deliveryRoot']) / ('MORNING_CODEX_REVIEW_' +
                    (end - timedelta(microseconds=1)).date().isoformat() + '_' + identity + '.txt'))
                if cfg.get('deliveryRoot') else None}
        next_state = {'schemaVersion': VERSION, 'contractHash': boundary,
                'watermark': end.isoformat(), 'seen': seen, 'sessions': sessions,
                'lastRun': {'runId': identity, 'manifestHash': manifest_hash, 'configHash': config_hash,
                            'cutoff': cutoff.isoformat()}}
        require(len(encoded(next_state)) <= MAX_STATE_BYTES, 'state_budget')
        transaction = {'result': result, 'packet': packet, 'nextState': next_state}
        if transaction_path.exists():
            previous = read_json(transaction_path)
            require(isinstance(previous, dict) and isinstance(previous.get('result'), dict),
                    'transaction_changed')
            actual = previous['result'].get('actualExecutionTime')
            require(timestamp(actual) <= now, 'transaction_changed')
            transaction['result']['actualExecutionTime'] = actual
            require(previous == transaction, 'transaction_changed')
        else:
            immutable_write(transaction_path, encoded(transaction))
        return finalize(root, transaction, fault)


def validate_agy_stream(text, requested_model, *, exit_code=0):
    """Offline contract check only; never runs AGY or retains its raw stream.

    Official NDJSON uses event/init/result, rather than a guessed type key.
    Full schema plus model metadata is required even when the process exits 0.
    """
    require(isinstance(text, str) and len(text) <= 1024 * 1024, 'stream_budget')
    require(isinstance(requested_model, str) and re.fullmatch(r'[A-Za-z0-9_.:/-]{1,120}', requested_model),
            'requested_model_required')
    require(exit_code == 0, 'analyzer_exit_failed')
    observed, terminal, init_count = None, None, 0
    for line in text.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            raise ReviewError('stream_schema') from None
        require(isinstance(event, dict), 'stream_schema')
        kind = event.get('event')
        require(kind in {'init', 'step_update', 'result'} and terminal is None, 'stream_schema')
        if kind == 'init':
            require(init_count == 0 and isinstance(event.get('init'), dict), 'stream_schema')
            init_count += 1
            observed = event['init'].get('model')
        elif kind == 'step_update':
            require(init_count == 1 and isinstance(event.get('step_update'), dict), 'stream_schema')
            step = event['step_update']
            require(not (isinstance(step.get('tool_info'), dict) and step['tool_info'].get('error')),
                    'analyzer_permission_or_tool_failure')
        else:
            require(init_count == 1 and isinstance(event.get('result'), dict), 'stream_schema')
            terminal = event['result']
    require(observed == requested_model, 'model_mismatch')
    require(terminal is not None and terminal.get('status') == 'SUCCESS', 'analyzer_result_failed')
    response = terminal.get('structured_output')
    require(isinstance(response, dict) and response.get('schemaVersion') == 'codex-review-analysis-v1'
            and isinstance(response.get('candidates'), list) and len(response['candidates']) <= 100, 'analysis_schema')
    candidates = []
    for item in response['candidates']:
        fields = {'observation', 'hypothesis', 'counterexample', 'minimalProposal', 'redTest', 'sourceHashes'}
        require(isinstance(item, dict) and set(item) == fields, 'analysis_schema')
        require(isinstance(item['sourceHashes'], list) and 0 < len(item['sourceHashes']) <= 100 and
                all(isinstance(s, str) and re.fullmatch('[a-f0-9]{64}', s) for s in item['sourceHashes']),
                'analysis_evidence')
        candidates.append({**{k: clean_text(item[k], 8000) for k in fields - {'sourceHashes'}},
                           'sourceHashes': item['sourceHashes']})
    usage = terminal.get('usage', {})
    require(isinstance(usage, dict), 'usage_schema')
    allowed_usage = {k: v for k, v in usage.items() if k in {'input_tokens', 'output_tokens', 'total_tokens',
                    'thinking_tokens', 'cache_read_tokens'} and type(v) is int and v >= 0}
    return {'status': 'VALIDATED_OFFLINE', 'requestedModel': requested_model, 'observedModel': observed,
            'usage': allowed_usage, 'candidates': candidates, 'aiCalls': 0, 'codexCalls': 0,
            'billingAccountVerified': False, 'sourceEvidenceBound': False, 'proposalApplied': False}


def output_cap_metrics(sessions_dir, days):
    """Count truncated tool-output markers in rollout *.jsonl; counts only."""
    counts = []
    root = Path(sessions_dir)
    if root.is_dir():
        for path in root.rglob('*.jsonl'):
            if not path.is_file() or not file_in_window(path, days):
                continue
            try:
                stream = open(path, 'rb')
            except OSError:
                continue
            with stream:
                for line in stream:
                    for match in TRUNC_TOKEN_RE.finditer(line):
                        counts.append(int(match.group(1)))
    counts.sort()
    return {'count': len(counts), 'token_sum': sum(counts),
            'median': statistics.median(counts) if counts else 0,
            'p90': counts[int(len(counts) * 0.9)] if counts else 0,
            'max': counts[-1] if counts else 0, 'measured': True}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='action', required=True)
    local = subs.add_parser('run', help='Local indexing and report only; never calls an agent')
    local.add_argument('--config')
    local.add_argument('--sessions-dir')
    local.add_argument('--days', type=int, default=3)
    local.add_argument('--json-out')
    check = subs.add_parser('validate-stream', help='Offline validation; no invocation or raw stream storage')
    check.add_argument('--input', required=True)
    check.add_argument('--model', required=True)
    check.add_argument('--exit-code', type=int, default=0)
    args = parser.parse_args(argv)
    try:
        if args.action == 'run':
            require(args.config or args.sessions_dir, 'config_or_sessions_dir_required')
            require(1 <= args.days <= 366, 'days_range')
            if args.config:
                result = run(args.config)
                # Do not print user summaries, paths or original session identifiers.
                public = {k: result[k] for k in ('status', 'aiCalls', 'codexCalls', 'schedulerRegistered')}
                public.update({k: result[k] for k in ('runId', 'holds', 'coverage') if k in result})
            else:
                public = {'status': 'METRICS_LOCAL', 'aiCalls': 0, 'codexCalls': 0,
                          'schedulerRegistered': False}
            public['truncated_output'] = (output_cap_metrics(args.sessions_dir, args.days)
                                          if args.sessions_dir else
                                          {'count': 0, 'token_sum': 0, 'median': 0, 'p90': 0,
                                           'max': 0, 'measured': False})
            if args.json_out:
                out_path = Path(args.json_out)
                out_path.parent.mkdir(parents=True, exist_ok=True)
                out_path.write_text(json.dumps(public, sort_keys=True) + '\n', encoding='utf-8')
        else:
            checked = validate_agy_stream(stable_read(safe_path(str(Path(args.input).absolute())),
                                          1024 * 1024).decode('utf-8'), args.model, exit_code=args.exit_code)
            public = {k: checked[k] for k in ('status', 'requestedModel', 'observedModel', 'usage', 'aiCalls', 'codexCalls')}
            public['candidateCount'] = len(checked['candidates'])
        print(json.dumps(public, sort_keys=True))
        truncated = public.get('truncated_output') or {}
        if truncated.get('measured') and truncated.get('p90', 0) > OUTPUT_CAP_P90_WARN:
            print('WARN_OUTPUT_CAP: p90=%d' % truncated['p90'])
        return 2 if public['status'] == 'HOLD' else 0
    except (ReviewError, OSError, UnicodeError, KeyError, TypeError) as error:
        reason = str(error) if isinstance(error, ReviewError) else 'local_io_or_schema_failure'
        print(json.dumps({'status': 'HOLD', 'reason': reason, 'aiCalls': 0, 'codexCalls': 0}))
        return 2


if __name__ == '__main__':
    sys.exit(main())
