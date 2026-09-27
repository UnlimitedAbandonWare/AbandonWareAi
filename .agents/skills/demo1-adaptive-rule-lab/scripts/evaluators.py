"""Bounded adapters. Persist normalized counts and timings, never raw stdout."""
import json
import subprocess
import sys
import time
from pathlib import Path

from catalog import search
from labio import file_hash, read_json, safe_path


def pin(root, spec):
    if spec.get('kind') == 'catalog':
        if spec.get('mode') not in ('title', 'semantic'):
            raise ValueError('invalid-catalog-mode')
        source = safe_path(root, spec['index'])
    elif spec.get('kind') == 'python':
        source = safe_path(root, spec['script'])
        if source.suffix != '.py' or spec.get('localExecutionAuthorized') is not True:
            raise ValueError('adapter-execution-not-authorized')
    else:
        raise ValueError('unknown-evaluator')
    result = dict(spec, artifactHash=file_hash(source))
    result['adapterHash'] = file_hash(__file__)
    result['searchImplementationHash'] = file_hash(Path(__file__).with_name('catalog.py'))
    return result


def check_pin(root, spec):
    current = pin(root, spec)
    if any(current[k] != spec[k] for k in ('artifactHash', 'adapterHash', 'searchImplementationHash')):
        raise ValueError('changed-evaluator-preimage')


def evaluate(root, spec, case, timeout_seconds):
    check_pin(root, spec)
    started = time.perf_counter()
    row = {'caseId': case['caseId'], 'attemptId': case['caseId'], 'success': False, 'quality': 0.0, 'debugVerified': False, 'status': 'error', 'errorClass': 'adapter-error'}
    try:
        if spec['kind'] == 'catalog':
            result = subprocess.run([sys.executable, '-B', '-X', 'utf8', str(Path(__file__).resolve()), '--catalog-case'], input=json.dumps({'root': str(root), 'spec': spec, 'case': case}, ensure_ascii=False), text=True, encoding='utf-8', capture_output=True, cwd=root, timeout=timeout_seconds, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            if result.returncode:
                raise ValueError('catalog-worker-failed')
            row.update(json.loads(result.stdout))
        else:
            # Only an explicit local Python adapter; no shell or arbitrary command string.
            result = subprocess.run([sys.executable, '-B', '-X', 'utf8', str(safe_path(root, spec['script']))], input=json.dumps(case, ensure_ascii=False), text=True, encoding='utf-8', capture_output=True, cwd=root, timeout=timeout_seconds, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            if result.returncode != 0:
                row['errorClass'] = 'nonzero-exit'
            elif len(result.stdout) > 8192:
                row['errorClass'] = 'output-limit'
            else:
                value = json.loads(result.stdout)
                if set(value) not in ({'success', 'quality', 'debugVerified', 'status', 'errorClass'}, {'success', 'quality', 'debugVerified', 'status', 'errorClass', 'capabilityLoss'}):
                    raise ValueError('adapter-contract')
                if value['errorClass'] not in (None, 'timeout', 'test-failed', 'assertion', 'unavailable', 'adapter-error'):
                    raise ValueError('nonallowlisted-error-class')
                row.update(value)
    except subprocess.TimeoutExpired:
        row.update(status='timeout', errorClass='timeout')
    except (OSError, ValueError, KeyError, TypeError):
        row.update(status='error', errorClass='adapter-contract')
    row['latencyMs'] = (time.perf_counter() - started) * 1000
    if row['latencyMs'] > timeout_seconds * 1000:
        row.update(success=False, quality=0.0, debugVerified=False, status='timeout', errorClass='timeout')
    # Malformed measurements become an observed failed attempt, not a dropped case.
    from metrics import validate_rows
    try:
        validate_rows([row])
    except ValueError:
        row.update(success=False, quality=0.0, debugVerified=False, status='error', errorClass='adapter-contract')
    return row


if __name__ == '__main__':
    packet = json.load(sys.stdin)
    spec, case = packet['spec'], packet['case']
    index = read_json(safe_path(packet['root'], spec['index']))
    found = search(index, case['query'], limit=case.get('topK', 1), mode=spec['mode'])
    ranks = [i + 1 for i, row in enumerate(found) if row['id'] in case['expectedIds']]
    print(json.dumps({'success': bool(ranks), 'quality': 1 / min(ranks) if ranks else 0.0, 'debugVerified': False, 'status': 'ok', 'errorClass': None}))
