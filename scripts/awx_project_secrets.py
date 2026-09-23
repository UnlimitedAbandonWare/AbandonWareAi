"""Project-only shared values. No value is returned by the CLI or written to evidence.

All cleartext (including three-way baselines and recovery) stays in .secrets.
OS access/transport checks run before every read or write; receipts are not grants.
"""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

try:
    from scripts.awx_shared_state import Change, Conflict, apply_changes, plain_path, parse_config
except ModuleNotFoundError:
    from awx_shared_state import Change, Conflict, apply_changes, plain_path, parse_config

DEVICES = {'desktop', 'notebook', 'macmini', 'macbook'}
SECURITY_REASONS = {'secrets-access-evidence-needed', 'smb-client-security-evidence-needed',
    'device-enrollment-evidence-needed', 'smb-encryption-required', 'smb-firewall-evidence-needed',
    'smb-device-scope-unproven', 'client-encrypted-smb-unverified', 'client-device-not-enrolled',
    'server-security-evidence-needed', 'access-transport-or-device-policy-unverified'}


def catalog(root):
    value = parse_config((plain_path(Path(root))/'config/project-resources.json').read_bytes(), 'json')
    if value.get('schemaVersion') != 'awx.project-resources.v1':
        raise Conflict('resource-schema-invalid')
    for provider, names in value['providers'].items():
        if not re.fullmatch(r'[a-z][a-z0-9-]{0,30}', provider):
            raise Conflict('provider-name-invalid')
        for name in names:
            if not re.fullmatch(r'[A-Z][A-Z0-9_]{1,80}', name) or re.search('openssl|opnessl', name, re.I):
                raise Conflict('protected-or-invalid-env-name')
    for name in value.get('runtimeEnvironmentNames', []):
        if not re.fullmatch(r'CONVERSATE_[A-Z0-9_]{1,69}', name) or re.search('openssl|opnessl', name, re.I):
            raise Conflict('protected-or-invalid-env-name')
    return value


def enabled(root):
    return (Path(root)/'config/project-resources.json').is_file() and catalog(root).get('enabled') is True


def environment(names, scope='persistent'):
    values = {} if scope == 'persistent-only' else {name: os.environ[name] for name in names if name in os.environ}
    if scope in ('persistent', 'persistent-only') and os.name == 'nt':
        import winreg
        # Read only project allowlisted names, never enumerate credentials.
        for hive, subkey in [(winreg.HKEY_LOCAL_MACHINE, r'SYSTEM\CurrentControlSet\Control\Session Manager\Environment'),
                             (winreg.HKEY_CURRENT_USER, 'Environment')]:
            try:
                with winreg.OpenKey(hive, subkey) as handle:
                    for name in names:
                        try:
                            value, kind = winreg.QueryValueEx(handle, name)
                            if kind in (winreg.REG_SZ, winreg.REG_EXPAND_SZ):
                                values[name] = value  # Exact bytes after JSON decoding; no expansion.
                        except FileNotFoundError:
                            pass
            except FileNotFoundError:
                pass
    return values


def usable(value):
    return isinstance(value, str) and bool(value.strip()) and value.strip().lower() not in {
        'test', 'dummy', 'changeme', 'sk-local', 'your-api-key'} and '${' not in value


def manual_values_active(root):
    marker = os.environ.get('AWX_PROJECT_KEYS_SOURCE_ROOT')
    return bool(marker) and os.path.normcase(os.path.abspath(marker)).rstrip('\\/') == os.path.normcase(os.path.abspath(root)).rstrip('\\/')


def verify_security(root):
    if os.name != 'nt':
        raise Conflict('smb-client-security-evidence-needed')
    try:
        result = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                                 str(Path(root)/'scripts/awx_secrets_acl.ps1'), '-Action', 'Verify', '-Root', str(root)],
                                capture_output=True, timeout=12, check=False)
        proof = json.loads(result.stdout.decode('utf-8-sig'))
        if result.returncode != 0 or proof.get('status') != 'available':
            reason = proof.get('reason')
            raise Conflict(reason if reason in SECURITY_REASONS else 'secrets-access-evidence-needed')
    except Conflict:
        raise
    except (OSError, ValueError, subprocess.TimeoutExpired):
        raise Conflict('secrets-access-evidence-needed') from None


class SecretStore:
    def __init__(self, root, security=verify_security):
        self.root = plain_path(Path(root))
        self.directory = plain_path(self.root/'.secrets')
        self.path = self.directory/'providers.json'
        self.security = security
        self.names = {n for names in catalog(root)['providers'].values() for n in names}

    def read(self):
        self.security(self.root)
        plain_path(self.directory)
        plain_path(self.path)
        raw = self.path.read_bytes() if self.path.exists() else None
        data = parse_config(raw, 'json') if raw else {'version': 1, 'values': {}, 'baselines': {}}
        if data.get('version') != 1 or not isinstance(data.get('values'), dict):
            raise Conflict('secret-store-invalid')
        if not set(data['values']) <= self.names:
            raise Conflict('secret-store-name-outside-allowlist')
        for entry in data['values'].values():
            if not isinstance(entry, dict) or not isinstance(entry.get('value'), str):
                raise Conflict('secret-store-value-invalid')
        return raw, data

    def sync(self, device, incoming):
        if device not in DEVICES or not set(incoming) <= self.names:
            raise Conflict('secret-sync-scope-invalid')
        if any(not isinstance(value, str) for value in incoming.values()):
            raise Conflict('secret-value-must-be-string')
        raw, data = self.read()
        baseline = data['baselines'].setdefault(device, {})
        changed, conflicts = [], []
        for name, value in incoming.items():
            current = data['values'].get(name)
            previous = baseline.get(name)
            if not usable(value):
                continue  # Absence never deletes a shared value.
            if current is None or current['value'] == value:
                if current is None:
                    data['values'][name] = {'value': value, 'revision': uuid.uuid4().hex, 'sourceDevice': device}
                    changed.append(name)
                baseline[name] = value
            elif previous == value:
                continue  # Stale local value must not overwrite a peer's update.
            elif previous == current['value']:
                data['values'][name] = {'value': value, 'revision': uuid.uuid4().hex, 'sourceDevice': device}
                baseline[name] = value
                changed.append(name)
            else:
                conflicts.append(name)
        if conflicts:
            return {'status': 'conflict', 'changedCount': 0, 'conflictingNames': sorted(conflicts)}
        encoded = (json.dumps(data, ensure_ascii=True, indent=2)+'\n').encode()
        if raw != encoded:
            self.security(self.root)
            # Existing transactional owner keeps all recovery copies in the protected directory.
            apply_changes([Change(self.path, raw, encoded)], self.directory/'recovery')
        return {'status': 'updated' if changed else 'unchanged', 'changedCount': len(changed), 'conflictingNames': []}

    def references(self):
        _, data = self.read()
        return {name: '.secrets/providers.json#/values/'+name for name in data['values']}

    def save_discovery(self, device, discovery):
        if device not in DEVICES:
            raise Conflict('device-role-required')
        self.security(self.root)
        path = plain_path(self.directory/'discovery'/device/(uuid.uuid4().hex+'.json'))
        data = (json.dumps(discovery, ensure_ascii=True)+'\n').encode()
        apply_changes([Change(path, None, data)], self.directory/'recovery')
        return str(path.relative_to(self.root)).replace('\\', '/')

    def load(self, local):
        _, data = self.read()
        result = dict(local)
        # Scoped child-only injection; never alter persistent environment or original .env.
        for name, entry in data['values'].items():
            result[name] = entry['value']
        return result


def runtime_environment(root, local):
    if not enabled(root):
        return dict(local)
    # Device-local runtime settings never enter the shared provider store.
    runtime = environment(catalog(root).get('runtimeEnvironmentNames', []), scope='persistent-only')
    local = {**local, **runtime}
    try:
        from scripts.awx_resource_inputs import project_inputs
        from scripts.awx_host_runtime import host_facts
    except ModuleNotFoundError:
        from awx_resource_inputs import project_inputs
        from awx_host_runtime import host_facts
    if manual_values_active(root):
        # Explicit manual selection survives User settings, but an inherited
        # marker must never pin the bytes loaded before a store rotation.
        try:
            from scripts.awx_project_keys import manual_read_access
        except ModuleNotFoundError:
            from awx_project_keys import manual_read_access
        return SecretStore(root, security=manual_read_access).load(local)
    names = {n for ns in catalog(root)['providers'].values() for n in ns}
    persistent = environment(names, scope='persistent-only')
    current = dict(local)
    current.update(persistent)
    inputs, _, _ = project_inputs(root, current)
    # Legacy references select a fallback store, not authority over a freshly
    # read Windows value. An explicitly blank value stays disabled.
    inputs.update(persistent)
    effective = dict(local)
    effective.update(inputs)
    if not (Path(root)/'.secrets/providers.json').exists():
        return effective
    try:
        store = SecretStore(root)
        synced = store.sync(host_facts(root)['role'], inputs)
        if synced['status'] == 'conflict':
            raise Conflict('local-shared-value-conflict')
        result = store.load(effective)
        result.update(persistent)
        return result
    except Conflict as error:
        if str(error) not in SECURITY_REASONS:
            raise
        # Optional automatic sharing must not disable existing local runtimes or
        # discard values explicitly loaded into the process by the manual loader.
        # The device registry separately reports the missing sharing evidence.
        return effective
