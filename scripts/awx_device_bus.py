"""Immutable per-event SMB queue, shared-secret sync, and task-entry capability CLI."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import uuid

try:
    from scripts.awx_project_secrets import SecretStore, DEVICES, catalog, enabled, environment, Conflict, plain_path, manual_values_active
    from scripts.awx_device_capabilities import probe
    from scripts.awx_host_runtime import host_facts
    from scripts.awx_resource_inputs import project_inputs, configuration_refs
except ModuleNotFoundError:
    from awx_project_secrets import SecretStore, DEVICES, catalog, enabled, environment, Conflict, plain_path, manual_values_active
    from awx_device_capabilities import probe
    from awx_host_runtime import host_facts
    from awx_resource_inputs import project_inputs, configuration_refs

EVENTS = {'task_started', 'patch_verified', 'verification_failed', 'proposal_created', 'acknowledged'}
STATUSES = {'started', 'verified', 'failed', 'proposed', 'acknowledged'}
BASE = 'data/device-resources'


def require_device(root, device):
    facts = host_facts(root)
    # The existing host detector distinguishes OS/root, not Mac form factor.
    # An explicit Mac mini designation may refine Darwin; never spoof Windows.
    if device == 'macmini' and facts.get('system') == 'Darwin' and facts['role'] == 'macbook':
        return dict(facts, role=device, hostId=facts['hostId'].replace('macbook-', 'macmini-', 1))
    if device not in DEVICES or device != facts['role']:
        raise Conflict('device-role-mismatch')
    return facts


def identifier(value):
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, AttributeError):
        raise Conflict('uuid-required') from None


def relative(value):
    if not isinstance(value, str) or len(value) > 240 or not re.fullmatch(r'[A-Za-z0-9_./-]+', value):
        raise Conflict('public-relative-path-invalid')
    parts = value.split('/')
    if any(p in ('', '.', '..') for p in parts) or re.search(r'(^|/)(\.secrets|secrets|\.env[^/]*|shared\.env[^/]*)(/|$)', value, re.I):
        raise Conflict('secret-or-traversal-path')
    if re.search(r'(sk-|gsk_|AIza|password|credential|session|token)', value, re.I):
        # Filenames in source can legitimately contain these terms. Omit them, never weaken the gate.
        raise Conflict('sensitive-path-omitted')
    return value


def publish(root, folder, record, identity=None):
    if folder not in {kind+'/'+d for kind in ('events', 'registry') for d in DEVICES}:
        raise Conflict('public-record-directory-invalid')
    directory = plain_path(Path(root)/BASE/folder)
    directory.mkdir(parents=True, exist_ok=True)
    plain_path(directory)
    event_id = identifier(identity or uuid.uuid4())
    destination = directory/(event_id+'.json')
    data = (json.dumps(record, ensure_ascii=True, sort_keys=True)+'\n').encode()
    if len(data) > 262144:
        raise Conflict('public-record-too-large')
    if destination.exists():
        if destination.read_bytes() == data:
            return str(destination.relative_to(root)).replace('\\', '/')
        raise Conflict('event-id-conflict')
    stage = directory/('.'+uuid.uuid4().hex+'.pending')
    try:
        with stage.open('xb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        # Atomic no-replace publication: hard links are supported by NTFS/SMB.
        # Never fall back to os.replace, which could discard another writer's event.
        os.link(stage, destination)
    except FileExistsError:
        raise Conflict('event-id-conflict') from None
    finally:
        if stage.exists():
            stage.unlink()
    return str(destination.relative_to(root)).replace('\\', '/')


def records(root, kind, device):
    result = []
    directory = plain_path(Path(root)/BASE/kind/device)
    for path in directory.glob('*.json'):
        try:
            identifier(path.stem)
            plain_path(path)
            result.append(path)
        except Conflict:
            continue
    return result


def render_result(action, result):
    if action == 'hook':
        # Follow the existing Codex hook contract; never echo hook stdin.
        return json.dumps({'hookSpecificOutput': {'hookEventName': 'UserPromptSubmit',
            'additionalContext': 'AWX project resources: '+json.dumps(result, ensure_ascii=True)}})
    return json.dumps(result, ensure_ascii=True)


def registry_status(root):
    """Reference-only view of the latest valid observation per device.

    Peers control their documents: report freshness, not authority/authentication.
    Never echo arbitrary peer fields, host names or endpoint/credential values.
    """
    now = datetime.now(timezone.utc)
    result = {'status': 'observed', 'devices': {}, 'remoteUsability': 'not_attested'}
    for device in sorted(DEVICES):
        selected = None
        for path in records(root, 'registry', device):
            try:
                if path.stat().st_size > 262144:
                    continue
                data = json.loads(path.read_text(encoding='utf-8'))
                if data.get('schemaVersion') != 'awx.device-capabilities.v1' or data.get('device') != device:
                    continue
                stamp = datetime.fromisoformat(data['timestamp'])
                if stamp.tzinfo is None or stamp > now:
                    continue
                ttl = data['ttlSeconds']
                if type(ttl) is not int or not 1 <= ttl <= 3600:
                    continue
                if selected is None or stamp > selected[0]:
                    selected = (stamp, path, ttl)
            except (OSError, ValueError, TypeError, KeyError, AttributeError):
                continue
        row = {'freshness': 'not_observed', 'registryRef': None}
        if selected:
            stamp, path, ttl = selected
            age = int((now-stamp).total_seconds())
            row.update(freshness='fresh' if age < ttl else 'stale', ageSeconds=age, ttlSeconds=ttl,
                       registryRef=str(path.relative_to(root)).replace('\\', '/'))
        result['devices'][device] = row
    return result


def emit(root, task_id, source, target, event_type, files=(), status='started', exit_code=None, event_id=None):
    if source not in DEVICES or target not in DEVICES or event_type not in EVENTS or status not in STATUSES:
        raise Conflict('event-enum-invalid')
    if exit_code is not None and (type(exit_code) is not int or not -255 <= exit_code <= 255):
        raise Conflict('event-exit-code-invalid')
    if len(files) > 128:
        raise Conflict('event-file-limit')
    record = {'taskId': identifier(task_id), 'sourceDevice': source, 'targetDevice': target,
              'eventType': event_type, 'changedFiles': sorted({relative(f) for f in files}),
              'status': status, 'timestamp': datetime.now(timezone.utc).isoformat(),
              'verification': {'exitCode': exit_code, 'scope': 'declared-files'},
              'authority': 'notification-only-source-gate-required'}
    return publish(root, 'events/'+target, record, event_id)


def checkpoint_event(root, manifest, state):
    if not enabled(root):
        return
    source = host_facts(root)['role']
    if source not in DEVICES:
        return
    goal = manifest['decision'].get('goalId', '')
    # Identity hash allows arbitrary existing goal IDs without copying them into the queue.
    task_id = str(uuid.uuid5(uuid.NAMESPACE_URL, goal))
    files = []
    omitted = 0
    for target in manifest['targets']:
        if target['preimageSha256'] == state['postimages'].get(target['path']):
            continue
        try:
            files.append(relative(target['path']))
        except Conflict:
            omitted += 1
    target = 'notebook' if source == 'desktop' else 'desktop'
    ref = emit(root, task_id, source, target,
               'patch_verified' if state['status'] == 'verified' else 'verification_failed', files,
               'verified' if state['status'] == 'verified' else 'failed', state.get('verificationExitCode'))
    state['deviceEvent'] = {'path': ref, 'omittedSensitivePathCount': omitted, 'delivery': 'queued-not-acknowledged'}


def task_start(root, device, sync=True):
    facts = require_device(root, device)
    config = catalog(root)
    names = {n for ns in config['providers'].values() for n in ns}
    manual = manual_values_active(root)
    values, sources, input_summary = project_inputs(root, environment(names, scope='process' if manual else 'persistent'))
    input_summary['inputMode'] = 'manual-project-settings' if manual else 'current-environment'
    store = SecretStore(root)
    refs = {}
    secret_status = {'status': 'evidence_needed', 'reason': 'secrets-access-not-ready'}
    try:
        if sync:
            secret_status = store.sync(device, values)
        if secret_status.get('status') == 'conflict':
            raise Conflict('local-shared-value-conflict')
        local_values = values
        values = store.load(values)
        refs = store.references()
        for name in refs:
            if values.get(name) != local_values.get(name) or name not in sources:
                sources[name] = 'shared-store'
    except Conflict as error:
        if secret_status.get('status') != 'conflict':
            secret_status = {'status': 'evidence_needed', 'reason': str(error)}
    discovery = {}
    registry = probe(root, device, values, refs, private_discovery=discovery)
    file_refs = configuration_refs(root)
    for row in registry['capabilities']:
        row['valueSources'] = {n: sources[n] for n in row.get('configuredNames', []) if n in sources}
        row['configurationRefs'] = file_refs.get(row['provider'], [])
        row['generationStatus'] = 'not_observed'
        if row['status'] == 'not_configured' and row['configurationRefs']:
            row.update(status='not_probed', reason='configuration_declared_runtime_not_attested')
    registry['inputSummary'] = input_summary
    registry['hostId'] = facts['hostId']
    registry['modelDiscoveryRef'] = None
    if refs and secret_status.get('status') != 'conflict':
        try:
            registry['modelDiscoveryRef'] = store.save_discovery(device, discovery)
        except Conflict:
            pass
    registry['sharedSecrets'] = secret_status
    ref = publish(root, 'registry/'+device, registry)
    return {'status': 'observed', 'registryRef': ref, 'sharedSecretsStatus': secret_status['status'],
            'sharedSecretsReason': secret_status.get('reason'),
            'counts': {s: sum(c['status'] == s for c in registry['capabilities'])
                       for s in sorted({c['status'] for c in registry['capabilities']})}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['start', 'sync', 'inbox', 'emit', 'hook', 'status'])
    parser.add_argument('--root', default=str(Path(__file__).absolute().parents[1]))
    parser.add_argument('--device', choices=sorted(DEVICES))
    parser.add_argument('--target', choices=sorted(DEVICES), default='desktop')
    parser.add_argument('--task-id')
    parser.add_argument('--event-type', choices=sorted(EVENTS), default='proposal_created')
    parser.add_argument('--status', choices=sorted(STATUSES), default='proposed')
    parser.add_argument('--file', action='append', default=[])
    args = parser.parse_args()
    try:
        root = plain_path(Path(args.root))
        if args.action == 'status':
            print(render_result(args.action, registry_status(root)))
            return 0
        device = args.device or host_facts(root)['role']
        require_device(root, device)
        # Always reread inputs. A denied sync cannot attest unchanged inputs, and
        # an old registry's mtime cannot attest current credentials/configuration.
        # Hook stdin contains raw user data and is deliberately never read.
        if args.action in ('start', 'hook', 'sync'):
            result = task_start(root, device)
        elif args.action == 'emit':
            result = {'status': 'queued', 'eventRef': emit(root, args.task_id, device, args.target, args.event_type, args.file, args.status)}
        else:
            # Inventory references only; never echo arbitrary peer-supplied JSON.
            files = sorted(records(root, 'events', device))
            result = {'status': 'observed', 'eventCount': len(files), 'eventRefs': [str(p.relative_to(root)).replace('\\', '/') for p in files[-100:]]}
        print(render_result(args.action, result))
        blocked = result.get('status') in ('conflict', 'evidence_needed') or (args.action == 'sync' and
            result.get('sharedSecretsStatus') in ('conflict', 'evidence_needed'))
        return 2 if blocked else 0
    except (Conflict, OSError, ValueError, KeyError):
        print(render_result(args.action, {'status': 'evidence_needed', 'reason': 'resource-operation-rejected'}))
        return 0 if args.action == 'hook' else 2


if __name__ == '__main__':
    raise SystemExit(main())
