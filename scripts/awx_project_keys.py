"""Explicit direct project snapshot selected by the user; never dump values.

This manual path preserves existing local ACLs and does not configure or attest
SMB encryption/device enrollment. Automatic SecretStore sync retains its gate.
"""
from __future__ import annotations
import argparse
import base64
import json
import os
import shutil
from pathlib import Path
import subprocess

try:
    from scripts.awx_project_secrets import SecretStore, Conflict, environment, plain_path, usable, manual_values_active
    from scripts.awx_resource_inputs import project_inputs
except ModuleNotFoundError:
    from awx_project_secrets import SecretStore, Conflict, environment, plain_path, usable, manual_values_active
    from awx_resource_inputs import project_inputs


def verify_direct_store(root):
    root = plain_path(Path(root))
    if os.name != 'nt' or os.path.normcase(str(root)) != os.path.normcase(r'C:\AbandonWare\demo-1\demo-1\src'):
        raise Conflict('direct-refresh-desktop-root-required')
    for command, expected in [(['git', '--no-optional-locks', 'ls-files', '--error-unmatch', '--', '.secrets/providers.json'], 1),
                              (['git', 'check-ignore', '-q', '--', '.secrets/providers.json'], 0)]:
        result = subprocess.run(command, cwd=root, capture_output=True, timeout=10)
        if result.returncode != expected:
            raise Conflict('direct-store-must-be-untracked-and-ignored')
    script = r'''
$ErrorActionPreference='Stop'
try {
    $current=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $allowed=@($current,'S-1-5-18','S-1-5-32-544')
    foreach($relative in @('.secrets','.secrets/providers.json')) {
        $item=Get-Item -LiteralPath $relative -Force
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'path' }
        $acl=Get-Acl -LiteralPath $item.FullName
        if($relative -eq '.secrets' -and -not $acl.AreAccessRulesProtected) { throw 'acl' }
        if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $current) { throw 'owner' }
        foreach($rule in $acl.Access) {
            $sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            if($rule.AccessControlType -eq 'Allow' -and $sid -notin $allowed -and $sid -ne 'S-1-3-4') { throw 'acl' }
        }
    }
    Write-Output '{"status":"verified"}'
} catch { Write-Output '{"status":"evidence_needed"}'; exit 2 }
'''
    shell = shutil.which('pwsh') or 'powershell'
    child_env = dict(os.environ)
    if shell == 'powershell':
        child_env.pop('PSModulePath', None)
    encoded = base64.b64encode(script.encode('utf-16-le')).decode('ascii')
    result = subprocess.run([shell, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encoded], cwd=root,
                            env=child_env, capture_output=True, timeout=10)
    if result.returncode != 0 or json.loads(result.stdout.decode('utf-8-sig')).get('status') != 'verified':
        raise Conflict('direct-store-local-acl-unverified')


def manual_read_access(root):
    path = plain_path(Path(root)/'.secrets/providers.json')
    if not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
        raise Conflict('manual-project-store-unavailable')


def refresh(root, incoming, apply=False, security=verify_direct_store):
    store = SecretStore(root, security=security)
    _, before = store.read()
    names = {n for n, value in incoming.items() if usable(value)}
    added = sorted(names - set(before['values']))
    different = sorted(n for n in names & set(before['values']) if before['values'][n]['value'] != incoming[n])
    result = {'status': 'planned', 'newNames': added, 'differentNames': different,
              'secretRef': '.secrets/providers.json', 'rawValuesPrinted': 0,
              'mode': 'user-selected-direct-snapshot', 'smbSecurityAttestation': 'not_observed'}
    if apply:
        result.update(store.sync('desktop', incoming))
        if result['status'] != 'conflict':
            _, after = store.read()
            result.update(storedNameCount=len(after['values']),
                          matchingInputCount=sum(after['values'].get(n, {}).get('value') == incoming[n] for n in names))
    return result


def check_loaded(root):
    store = SecretStore(root, security=manual_read_access)
    _, data = store.read()
    expected = {n: entry['value'] for n, entry in data['values'].items()}
    actual = environment(store.names, scope='process')
    missing = sorted(set(expected)-set(actual))
    different = sorted(n for n in set(expected) & set(actual) if expected[n] != actual[n])
    active = manual_values_active(root)
    return {'status': 'verified' if active and not missing and not different else 'evidence_needed',
            'scope': 'current-process-recognition-only', 'storedNameCount': len(expected),
            'matchingCount': len(expected)-len(missing)-len(different), 'missingNames': missing,
            'differentNames': different, 'manualLoaderActive': active, 'rawValuesPrinted': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['refresh', 'check'])
    parser.add_argument('--root', default=str(Path(__file__).absolute().parents[1]))
    parser.add_argument('--apply', action='store_true', help='Apply the explicitly selected Desktop snapshot; otherwise plan only.')
    args = parser.parse_args()
    try:
        root = plain_path(Path(args.root))
        if args.action == 'refresh':
            store = SecretStore(root)
            current = environment(store.names)
            values, _, _ = project_inputs(root, current)
            # Explicit refresh imports the current User environment, even when
            # legacy files select the previous saved snapshot for consumers.
            values.update(current)
            result = refresh(root, values, args.apply)
        else:
            result = check_loaded(root)
        print(json.dumps(result, ensure_ascii=True))
        return 2 if result['status'] in ('conflict', 'evidence_needed') else 0
    except (Conflict, OSError, ValueError, subprocess.TimeoutExpired):
        print(json.dumps({'status': 'evidence_needed', 'reason': 'direct-project-settings-rejected', 'rawValuesPrinted': 0}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
