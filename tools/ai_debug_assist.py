#!/usr/bin/env python3
"""One bounded observation: local/MCP classification plus optional existing AI delegate.

AI findings remain hypotheses. This tool never runs proposed commands or patches.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MAX_LOG_BYTES = 1_000_000  # Also fits the existing MCP handler's character window.
MAX_REPORT_BYTES = 64 * 1024


def _module(name, path):
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


BUILD_MINER = _module('awx_ai_build_miner', ROOT / 'tools/build_error_miner.py')


def _load_delegate():
    return _module('awx_ai_existing_delegate', ROOT / 'tools/ox-alpha-delegate/ox_alpha_delegate.py')


def _mcp_result(reply):
    if not isinstance(reply, dict) or reply.get('isError') is not False:
        raise ValueError('mcp_tool_error')
    value = reply.get('structuredContent', {})
    classes = value.get('classes')
    known = {'cannot-find-symbol', 'duplicate-class-fqcn', 'spring-bean',
             'spring-bind', 'yaml-parse', 'langchain4j-version-purity',
             'gradle-distribution-network-cache'}
    if (not isinstance(classes, dict) or not set(classes) <= known
            or any(type(n) is not int or not 0 < n <= MAX_LOG_BYTES for n in classes.values())
            or type(value.get('outputCount')) is not int
            or value['outputCount'] != sum(classes.values())
            or value.get('primaryClass') not in (set(classes) if classes else {'other'})
            or value.get('decision') != 'build_log_mined'):
        raise ValueError('mcp_semantic_contract_failed')
    return {'classes': classes, 'primaryClass': value['primaryClass']}


def _mcp_classify(raw_bytes):
    # Reuse owned STDIO process containment; never start Spring or other services.
    script_dir = str(ROOT / 'scripts')
    if script_dir not in sys.path:
        sys.path.insert(0, script_dir)
    from awx_mcp_toolbox import RuntimeToolkit
    toolkit = RuntimeToolkit()
    try:
        toolkit.ensure_session()
        if toolkit.proof.get('protocolProbe') is not True:
            raise ValueError('mcp_initialize_failed')
        with tempfile.TemporaryDirectory(prefix='awx-ai-debug-') as directory:
            scratch = Path(directory)
            canary = scratch / 'canary.log'
            canary.write_text('error: cannot find symbol\nerror: cannot find symbol\nduplicate class\n', encoding='utf-8')

            def call(path):
                return toolkit.rpc('tools/call', {'name': 'build_error_mine',
                    'arguments': {'log_path': str(path), 'audit_log': str(scratch / 'audit.jsonl')},
                    '_meta': {'awx/executionTimeoutMs': 5000}})

            if _mcp_result(call(canary))['classes'] != {'cannot-find-symbol': 2, 'duplicate-class-fqcn': 1}:
                raise ValueError('mcp_canary_failed')
            missing = call(scratch / 'missing.log')
            if missing.get('isError') is not True:
                raise ValueError('mcp_missing_input_accepted')
            actual = scratch / 'snapshot.log'
            actual.write_bytes(raw_bytes)
            observed = _mcp_result(call(actual))
        return {'status': 'verified', 'reason': 'owned_stdio_canary_and_snapshot_pass',
                'scope': 'classification_only', 'probeCount': 3,
                'missingInputHandledAsError': True, **observed}
    finally:
        toolkit.close_pipe()


def _ai_empty(status, fallback=True):
    return {'status': status, 'fallbackUsed': fallback, 'findings': [],
            'evidence': [], 'proposedTests': []}


def diagnose(log_path, source_paths, *, root=ROOT, use_mcp=True, use_ai=True, timeout_seconds=30, proposal_path=None):
    result = {'schemaVersion': 'awx.ai-debug-observation.v1', 'status': 'rejected',
              'reason': 'input_invalid', 'claimsVerified': False,
              'nextAction': 'local_analysis_required',
              'mcp': {'status': 'disabled', 'reason': 'not_requested'},
              'ai': _ai_empty('disabled', False)}
    if type(timeout_seconds) is not int or not 1 <= timeout_seconds <= 60:
        result['reason'] = 'invalid_timeout'
        return result
    try:
        log_path = Path(log_path)
        if not log_path.is_file():
            raise OSError()
        with log_path.open('rb') as stream:
            raw = stream.read(MAX_LOG_BYTES + 1)
    except OSError:
        result['reason'] = 'log_unavailable'
        return result
    if len(raw) > MAX_LOG_BYTES:
        result['reason'] = 'log_size_limit'
        return result
    # No raw log text, normalized examples, paths, or guessed causes in the prompt.
    classes = {code: item['count'] for code, item in BUILD_MINER.scan_text(raw.decode('utf-8', errors='replace')).items()}
    snapshot = {'logSha256': hashlib.sha256(raw).hexdigest(), 'logBytes': len(raw),
                'classes': classes, 'sourceHashes': {}}
    result.update(status='observed', reason='bounded_log_classified', snapshot=snapshot)
    if use_mcp:
        try:
            result['mcp'] = _mcp_classify(raw)
        except Exception:
            result['mcp'] = {'status': 'fallback', 'reason': 'mcp_unavailable_or_contract_failed'}
    if not use_ai and not source_paths:
        return result
    request = {'prompt': ('Find at most three falsifiable debugging hypotheses in the explicitly supplied source. '
               'These observed error counts are symptoms, not root causes: ' + json.dumps(classes, sort_keys=True) +
               '. Log content SHA-256: ' + snapshot['logSha256'] +
               '. Cite real file and line evidence and propose focused counterexample tests. '
               'No commands or patches will be applied. Select the earliest failing boundary.'),
               'workingDirectory': str(Path(root)), 'allowlistedPaths': list(source_paths),
               'timeoutSeconds': timeout_seconds, 'requestId': 'debug-' + snapshot['logSha256'][:16]}
    try:
        delegate = _load_delegate()
        validated = delegate._validate_request(request)
        if len(validated.allowlisted_paths) > 8 or any(
                not (validated.working_directory / p).is_file() for p in validated.allowlisted_paths):
            result['ai'] = _ai_empty('source_allowlist_requires_one_to_eight_files')
            return result
        sources = delegate._collect_sources(validated)
        snapshot['sourceHashes'] = {s.relative_path: s.sha256 for s in sources}
        if not use_ai:
            return result
        if proposal_path is not None:
            with Path(proposal_path).open('rb') as stream:
                candidate_bytes = stream.read(16385)
            if len(candidate_bytes) > 16384:
                result['ai'] = _ai_empty('proposal_size_limit')
                return result
            candidate_text = candidate_bytes.decode('utf-8-sig')
            if delegate._redact_text(candidate_text)[1]:
                result['ai'] = _ai_empty('proposal_sensitive')
                return result
            ai = json.loads(candidate_text, parse_constant=delegate._reject_json_constant)
            # JSON escapes can hide a sensitive value from the raw-text scan.
            if delegate._redact_text(json.dumps(ai, ensure_ascii=False, allow_nan=False),
                                     (os.environ.get('OPENCODE_API_KEY', ''),))[1]:
                result['ai'] = _ai_empty('proposal_sensitive')
                return result
            if (ai.get('logSha256') != snapshot['logSha256']
                    or ai.get('sourceHashes') != snapshot['sourceHashes']):
                result['ai'] = _ai_empty('proposal_snapshot_mismatch')
                return result
            # These fields describe local validation, not an external model invocation.
            ai.update(status='ok', fallbackUsed=False, exitCode=0,
                      changedFiles=[], outputContractExceeded=False)
        else:
            ai = delegate.analyze_request(request)
        current = delegate._collect_sources(validated)
        if {s.relative_path: s.sha256 for s in current} != snapshot['sourceHashes']:
            result['ai'] = _ai_empty('source_changed')
            return result
        # The delegate intentionally exits zero on failure. Only its full contract counts.
        if (ai.get('status') != 'ok' or ai.get('fallbackUsed') is not False
                or ai.get('exitCode') != 0 or ai.get('changedFiles') != []
                or ai.get('outputContractExceeded') is not False):
            status = ai.get('status', '')
            allowed = {'cli_unavailable', 'unsupported_cli_version', 'input_rejected',
                       'timeout', 'rate_limited', 'workspace_changed', 'contract_exceeded',
                       'internal_error', 'permission_violation', 'sensitive_output'}
            result['ai'] = _ai_empty(status if status in allowed else 'fallback')
            return result
        # Reuse the existing schema/file/line validator; model text is never executable.
        summary, findings, evidence, proposed = delegate._normalize_model_result(
            ai, Path(root), frozenset(k.casefold() for k in snapshot['sourceHashes']))
        if len(evidence) < delegate.MIN_EVIDENCE:
            result['ai'] = _ai_empty('evidence_invalid')
            return result
        if delegate._redact_text(json.dumps([summary, findings, evidence, proposed],
                                             ensure_ascii=False, allow_nan=False),
                                 (os.environ.get('OPENCODE_API_KEY', ''),))[1]:
            result['ai'] = _ai_empty('candidate_sensitive')
            return result
        result['ai'] = {'status': 'ok', 'fallbackUsed': False, 'summary': summary,
                        'findings': findings, 'evidence': evidence, 'proposedTests': proposed,
                        'model': 'caller_supplied' if proposal_path is not None else delegate.MODEL,
                        'candidateOrigin': 'supplied_candidate' if proposal_path is not None else 'live_delegate',
                        'evidenceStatus': 'file_line_valid_cause_unverified'}
        result['nextAction'] = 'focused_test_required'
    except Exception:
        result['ai'] = _ai_empty('input_or_analyzer_failed')
    if len(json.dumps(result, ensure_ascii=True, allow_nan=False).encode('utf-8')) > MAX_REPORT_BYTES:
        result['ai'] = _ai_empty('report_size_limit')
        result['snapshot']['sourceHashes'] = {}
        result['nextAction'] = 'local_analysis_required'
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--log', required=True)
    parser.add_argument('--source', action='append', default=[], help='Exact repo-relative source file; repeat as needed')
    parser.add_argument('--out', required=True, help='New JSON report path; existing files are never overwritten')
    parser.add_argument('--mcp', choices=('auto', 'off'), default='auto')
    parser.add_argument('--ai', choices=('delegate', 'off'), default='delegate')
    parser.add_argument('--proposal', help='Optional AI candidate JSON bound to current log/source hashes; skips external AI')
    parser.add_argument('--timeout-seconds', type=int, default=30)
    args = parser.parse_args(argv)
    result = diagnose(args.log, args.source, use_mcp=args.mcp == 'auto',
                      use_ai=args.ai == 'delegate', timeout_seconds=args.timeout_seconds,
                      proposal_path=args.proposal)
    encoded = json.dumps(result, ensure_ascii=True, allow_nan=False, indent=2) + '\n'
    if len(encoded.encode('utf-8')) > MAX_REPORT_BYTES:
        print(json.dumps({'status': 'rejected', 'reason': 'report_size_limit'}))
        return 2
    try:
        # Exclusive creation protects the log, source, and earlier evidence alike.
        with Path(args.out).open('x', encoding='utf-8') as stream:
            stream.write(encoded)
    except OSError:
        print(json.dumps({'status': 'rejected', 'reason': 'output_unavailable_or_exists'}))
        return 2
    print(json.dumps({'status': result['status'], 'mcp': result['mcp']['status'],
                      'ai': result['ai']['status'], 'claimsVerified': False,
                      'nextAction': result['nextAction']}))
    return 2 if result['status'] == 'rejected' else 0


if __name__ == '__main__':
    raise SystemExit(main())
