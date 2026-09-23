"""Lossless-value configuration merges and recoverable, cooperative write batches.

No implicit conflict resolution. Multi-file atomicity is rollback/journal based;
individual publication uses same-directory os.replace. Unknown writers require
the repository source lease in addition to these cooperative file locks.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timezone
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import stat
import tempfile
try:
    import tomllib
except ModuleNotFoundError:
    raise SystemExit('awx-requires-python-3.11-or-newer') from None
import uuid


class Conflict(ValueError):
    """A redacted reason code; never include configuration values."""


def digest(data: bytes | None) -> str | None:
    return hashlib.sha256(data).hexdigest() if data is not None else None


def plain_path(path: Path) -> Path:
    path = Path(os.path.abspath(path))
    for candidate in (path, *path.parents):
        try:
            info = candidate.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise Conflict('reparse-or-symlink-target')
    if path.exists() and not path.is_file() and not path.is_dir():
        raise Conflict('unsupported-target-kind')
    return path


def read_optional(path: Path) -> bytes | None:
    path = plain_path(path)
    return path.read_bytes() if path.exists() else None


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Conflict('duplicate-json-key')
        result[key] = value
    return result


def parse_config(raw: bytes, kind: str) -> dict:
    try:
        text = raw.decode('utf-8-sig')
        if kind == 'json':
            value = json.loads(text, object_pairs_hook=_pairs,
                               parse_constant=lambda _: (_ for _ in ()).throw(Conflict('nonfinite-json')))
        elif kind == 'toml':
            value = tomllib.loads(text)
        else:
            raise Conflict('unsupported-config-format')
    except (UnicodeError, ValueError) as error:
        if isinstance(error, Conflict):
            raise
        raise Conflict('invalid-config') from None
    if not isinstance(value, dict):
        raise Conflict('config-object-required')
    return value


def merge_values(current: dict, incoming: dict) -> dict:
    """Only add missing keys. Arrays/scalars are indivisible personal values."""
    result = copy.deepcopy(current)
    for key, value in incoming.items():
        if key not in result:
            result[key] = copy.deepcopy(value)
        elif isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = merge_values(result[key], value)
        elif type(result[key]) is not type(value) or result[key] != value:
            raise Conflict('config-value-conflict')
    return result


def _toml_value(value):
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, (datetime, date, time)):
        return value.isoformat()
    if isinstance(value, (int, float)):
        return str(value).lower()
    if isinstance(value, list):
        return '[' + ', '.join(_toml_value(item) for item in value) + ']'
    if isinstance(value, dict):
        return '{' + ', '.join(json.dumps(str(k)) + ' = ' + _toml_value(v) for k, v in value.items()) + '}'
    raise Conflict('unsupported-toml-value')


def merge_three_way(current: dict, baseline: dict, incoming: dict) -> dict:
    """Advance unchanged managed keys while retaining independent personal edits.

    Removal never deletes a current key. Arrays are indivisible, so competing
    array edits need reconciliation rather than a guessed order or identity.
    """
    result=copy.deepcopy(current)
    for key,value in incoming.items():
        if key not in current:
            if key not in baseline:result[key]=copy.deepcopy(value)
            elif baseline[key]!=value:raise Conflict('config-delete-modify-conflict')
        elif all(isinstance(tree.get(key,{}),dict) for tree in (current,baseline,incoming)):
            result[key]=merge_three_way(current[key],baseline.get(key,{}),value)
        elif key in baseline and type(current[key]) is type(baseline[key]) and current[key]==baseline[key]:
            result[key]=copy.deepcopy(value)
        elif type(current[key]) is type(value) and current[key]==value:
            continue
        elif key in baseline and type(value) is type(baseline[key]) and value==baseline[key]:
            continue
        else:raise Conflict('config-value-conflict')
    return result


def encode_config(value: dict, kind: str) -> bytes:
    if kind == 'json':
        return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode()
    if kind == 'toml':
        return (''.join(json.dumps(str(key)) + ' = ' + _toml_value(item) + '\n'
                        for key, item in value.items())).encode()
    raise Conflict('unsupported-config-format')


def merge_config(existing: bytes, incoming: bytes, kind: str) -> bytes:
    old = parse_config(existing, kind) if existing.strip() else {}
    new = merge_values(old, parse_config(incoming, kind))
    if old == new:
        return existing
    result = encode_config(new, kind)
    if kind == 'toml':
        # Preserve original comments/layout when adding whole new tables or root
        # keys. Refuse a layout we cannot safely extend instead of rewriting it.
        prefix=[]; suffix=[]
        def additions(before, after, parts=()):
            for key,value in after.items():
                path=(*parts,key)
                if key in before:
                    if isinstance(value,dict):additions(before[key],value,path)
                    continue
                if not parts:
                    prefix.append(json.dumps(key)+' = '+_toml_value(value)+'\n')
                elif isinstance(value,dict):
                    suffix.append('\n['+'.'.join(json.dumps(p) for p in path)+']\n'+''.join(json.dumps(k)+' = '+_toml_value(v)+'\n' for k,v in value.items()))
                else:raise Conflict('toml-layout-requires-explicit-merge')
        additions(old,new)
        result=(''.join(prefix)+existing.decode('utf-8-sig')+'\n'+''.join(suffix)).encode()
    # Round-trip the complete tree, including unrelated nested settings.
    if parse_config(result, kind) != new:
        raise Conflict('config-roundtrip-mismatch')
    return result


@dataclass(frozen=True)
class Change:
    path: Path
    before: bytes | None
    after: bytes


def _sync_directory(path: Path):
    if os.name != 'nt':
        fd = os.open(path, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def _create_synced(path: Path, data: bytes):
    with path.open('xb') as stream:
        os.chmod(path, 0o600)
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    _sync_directory(path.parent)


def _journal(path: Path, data: dict):
    temporary = path.with_name(path.name + '.tmp')
    if temporary.exists():
        raise Conflict('journal-temporary-conflict')
    _create_synced(temporary, encode_config(data, 'json'))
    os.replace(temporary, path)
    _sync_directory(path.parent)


def apply_changes(changes: list[Change], state_root: Path) -> dict:
    state_root = plain_path(state_root)
    normalized = set()
    planned = []
    for item in changes:
        path = plain_path(item.path)
        key = str(path).casefold()
        if key in normalized:
            raise Conflict('duplicate-normalized-target')
        normalized.add(key)
        if read_optional(path) != item.before:
            raise Conflict('preimage-changed')
        if item.before != item.after:
            planned.append(Change(path, item.before, item.after))
    if not planned:
        return {'status':'unchanged', 'changedCount':0, 'backupCount':0}
    state_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    for journal in state_root.glob('transactions/*/journal.json'):
        status = parse_config(journal.read_bytes(), 'json').get('status')
        if status not in {'committed', 'rolled-back'}:
            raise Conflict('unfinished-transaction-recovery-required')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    transaction = state_root / 'transactions' / (stamp + '-' + uuid.uuid4().hex[:8])
    transaction.mkdir(parents=True, mode=0o700)
    locks, staged, published = [], [], []
    record = {'status':'preparing', 'entries':[]}
    journal = transaction/'journal.json'
    try:
        _journal(journal,record)
        for item in sorted(planned, key=lambda item:str(item.path).casefold()):
            item.path.parent.mkdir(parents=True, exist_ok=True)
            plain_path(item.path)
            lock = item.path.with_name('.awx-' + digest(str(item.path).casefold().encode())[:24] + '.lock')
            try:
                _create_synced(lock, uuid.uuid4().hex.encode())
            except FileExistsError:
                raise Conflict('target-write-lock-present') from None
            locks.append((lock, lock.read_bytes()))
        for index, item in enumerate(planned):
            if read_optional(item.path) != item.before:
                raise Conflict('preimage-changed')
            backup = transaction/(str(index) + '.bak_' + stamp)
            _create_synced(backup, item.before if item.before is not None else b'{"originallyAbsent":true}\n')
            fd, name = tempfile.mkstemp(prefix='.awx-', dir=item.path.parent)
            stage = Path(name)
            staged.append(stage)
            with os.fdopen(fd,'wb') as stream:
                stream.write(item.after); stream.flush(); os.fsync(stream.fileno())
            if item.path.exists():
                os.chmod(stage, stat.S_IMODE(item.path.stat().st_mode))
            record['entries'].append({'path':str(item.path), 'before':digest(item.before),
                                      'after':digest(item.after), 'backup':str(backup), 'stage':str(stage)})
        record['status'] = 'prepared'
        _journal(journal, record)
        for item, stage in zip(planned, staged):
            if read_optional(item.path) != item.before:
                raise Conflict('preimage-changed')
            os.replace(stage, item.path)
            published.append(item)
            _sync_directory(item.path.parent)
        record['status'] = 'committed'
        _journal(journal, record)
        return {'status':'committed','changedCount':len(planned),'backupCount':len(planned),
                'transactionDir':str(transaction),'journalPath':str(journal)}
    except Exception:
        rollback_conflict = False
        for item in reversed(published):
            if read_optional(item.path) != item.after:
                rollback_conflict = True
                continue
            if item.before is None:
                item.path.unlink()
            else:
                fd, name = tempfile.mkstemp(prefix='.awx-restore-',dir=item.path.parent)
                with os.fdopen(fd,'wb') as stream:
                    stream.write(item.before);stream.flush();os.fsync(stream.fileno())
                os.chmod(name,stat.S_IMODE(item.path.stat().st_mode))
                os.replace(name,item.path)
            _sync_directory(item.path.parent)
        record['status'] = 'recovery-required' if rollback_conflict else 'rolled-back'
        _journal(journal,record)
        raise
    finally:
        for path in staged:
            if path.exists():
                path.unlink()
        for path, identity in reversed(locks):
            if read_optional(path) == identity:
                path.unlink()
