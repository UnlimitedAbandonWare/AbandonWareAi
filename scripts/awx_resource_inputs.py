"""Read existing project inputs in memory; publish names/references, never values.

The two legacy dotenv files are read-only fallbacks. Configuration inventories
describe declarations, not Spring's effective profile or remote authentication.
"""
from __future__ import annotations
import re
from pathlib import Path

try:
    from scripts.awx_project_secrets import catalog, plain_path, usable, Conflict
except ModuleNotFoundError:
    from awx_project_secrets import catalog, plain_path, usable, Conflict

LIMIT = 2 * 1024 * 1024
OPENAI_SECRET_REF = '.secrets/providers.json#/values/OPENAI_API_KEY'


def read_text(root, relative):
    path = plain_path(Path(root)/relative)
    if not path.is_file():
        return None
    with path.open('rb') as stream:
        raw = stream.read(LIMIT + 1)
    if len(raw) > LIMIT:
        raise Conflict('resource-input-too-large')
    return raw.decode('utf-8-sig')


def dotenv(text, allowed):
    result = {}
    for line in text.splitlines():
        match = re.fullmatch(r'\s*(?:export\s+)?([A-Z][A-Z0-9_]+)\s*=\s*(.*?)\s*', line)
        if not match or match[1] not in allowed:
            continue
        value = match[2]
        if value[:1] in ('"', "'"):
            quote = value[0]
            # Preserve literal bytes; never evaluate shell escapes or substitutions.
            end = value.rfind(quote)
            if end == 0 or (value[end+1:].strip() and not value[end+1:].lstrip().startswith('#')):
                continue
            value = value[1:end]
        else:
            value = re.split(r'\s+#', value, maxsplit=1)[0].rstrip()
        if usable(value) and not any(token in value for token in ('$(', '`')):
            result[match[1]] = value
    return result


def project_inputs(root, current):
    config = catalog(root)
    names = {n for ns in config['providers'].values() for n in ns}
    values, sources, legacy = {}, {}, {}
    openai_reference = False
    for relative in ('shared.env', '.env'):
        text = read_text(root, relative)
        if text is None:
            continue
        entries = dotenv(text, names)
        openai = entries.get('OPENAI_API_KEY', '')
        if any(part in openai for part in ('/', '\\', '#')):
            if openai != OPENAI_SECRET_REF:
                raise Conflict('openai-secret-reference-invalid')
            openai_reference = True
            del entries['OPENAI_API_KEY']
        legacy[relative] = entries
        for name, value in entries.items():
            values[name], sources[name] = value, relative
    for name, value in current.items():
        if name in names:
            values[name], sources[name] = value, 'environment'
    if openai_reference:
        # An exact reference explicitly selects the existing manual snapshot.
        # Reread on every launch so inherited peer environments cannot pin an old key.
        try:
            try:
                from scripts.awx_project_keys import manual_read_access
                from scripts.awx_project_secrets import SecretStore
            except ModuleNotFoundError:
                from awx_project_keys import manual_read_access
                from awx_project_secrets import SecretStore
            value = SecretStore(root, security=manual_read_access).load({}).get('OPENAI_API_KEY')
            if not usable(value) or any(part in value for part in ('/', '\\', '#')):
                raise Conflict('openai-secret-reference-unavailable')
        except (OSError, ValueError):
            raise Conflict('openai-secret-reference-unavailable') from None
        values['OPENAI_API_KEY'], sources['OPENAI_API_KEY'] = value, 'shared-store-reference'
    # Retain original files even when their values have drifted from Windows.
    drift = {relative: sorted(n for n, v in entries.items() if n in current and current[n] != v)
             for relative, entries in legacy.items()}
    return values, sources, {'legacyDifferentNames': drift, 'originalFilesModified': False,
                             'environmentPrecedence': ['User', 'Machine', 'Process', '.env', 'shared.env']}


def configuration_refs(root):
    config = catalog(root)
    result = {provider: [] for provider in config['providers']}
    allowed_names = {n for ns in config['providers'].values() for n in ns}
    # Inventory only declared project sources; no recursive filesystem/secret scan.
    for relative in config.get('configurationFiles', []):
        if not re.fullmatch(r'main/resources/application(?:-[a-z0-9-]+)?\.(?:yml|yaml|properties)', relative):
            raise Conflict('configuration-reference-invalid')
        text = read_text(root, relative)
        if text is None:
            continue
        keys, env_names, stack = set(), set(), []
        for line in text.splitlines():
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            env_names.update(n for n in re.findall(r'\$\{([A-Z][A-Z0-9_]+)', line) if n in allowed_names)
            match = re.match(r'^( *)([a-zA-Z][a-zA-Z0-9_.-]*)\s*[:=]', line)
            if not match:
                continue
            if relative.endswith('.properties'):
                keys.add(match[2])
            else:
                indent = len(match[1])
                while stack and stack[-1][0] >= indent:
                    stack.pop()
                stack.append((indent, match[2]))
                keys.add('.'.join(k for _, k in stack))
        for provider, names in config['providers'].items():
            prefixes = config.get('configurationPrefixes', {}).get(provider, [provider + '.'])
            matched = sorted(k for k in keys if any(k.startswith(p) for p in prefixes))
            referenced = sorted(set(names) & env_names)
            if matched or referenced:
                # Property names are selected by fixed catalog prefixes. No values/defaults.
                result[provider].append({'path': relative, 'propertyNames': matched[:64],
                    'envNames': referenced, 'activation': 'declaration-only-profile-not-evaluated'})
    return result
