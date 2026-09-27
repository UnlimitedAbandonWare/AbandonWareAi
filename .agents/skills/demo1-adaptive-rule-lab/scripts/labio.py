"""Local, bounded JSON storage. No provider, runtime or shared-rule mutation."""
import hashlib
import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def safe_path(root, relative):
    root = Path(root).resolve()
    if not isinstance(relative, str) or not relative or '\\' in relative or ':' in relative:
        raise ValueError('noncanonical-path')
    parts = relative.split('/')
    if any(p in ('', '.', '..') for p in parts) or relative.startswith('/'):
        raise ValueError('path-escape-or-alias')
    target = root.joinpath(*parts)
    cursor = target
    while cursor != root:
        if cursor.is_symlink() or (cursor.exists() and getattr(cursor.stat(), 'st_file_attributes', 0) & 1024):
            raise ValueError('reparse-path')
        cursor = cursor.parent
    if not target.resolve().is_relative_to(root):
        raise ValueError('path-escape')
    return target


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + '\n').encode('utf-8')


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'), parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite-json')))


def write_json(path, value, expected_hash=None):
    """None means create only; replacements require the exact current byte hash."""
    path = Path(path)
    safe_path(path.anchor, path.absolute().relative_to(path.anchor).as_posix())
    path.parent.mkdir(parents=True, exist_ok=True)
    lock = path.with_name(path.name + '.lock')
    fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    temporary = None
    try:
        os.close(fd)
        current = file_hash(path) if path.exists() else None
        if current != expected_hash:
            raise ValueError('changed-preimage')
        data = encoded(value)
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        lock.unlink(missing_ok=True)


def immutable(path, value):
    path = Path(path)
    if path.exists():
        if read_json(path) != value:
            raise ValueError('event-id-collision')
        return False
    write_json(path, value)
    return True


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,99}', value):
        raise ValueError('invalid-identifier')
    return value


def utcnow():
    return datetime.now(timezone.utc).isoformat()


def safe_note(value):
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= 600:
        raise ValueError('invalid-summary-length')
    if re.search(r'(?i)(bearer\s+\S+|(?:api[_-]?key|password|secret|token)\s*[:=]\s*\S+|sk-[A-Za-z0-9]{16,})', value):
        raise ValueError('sensitive-summary-pattern')
    return value.strip()
