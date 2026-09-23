"""Read a hash-pinned local JUnit result; never emit test names or failure bodies."""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from labio import file_hash, safe_path


def measure(root, case):
    path = safe_path(root, case['reportPath'])
    if path.stat().st_size > 5_000_000 or file_hash(path) != case['reportHash']:
        raise ValueError('changed-or-oversize-junit-report')
    raw = path.read_bytes()
    if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
        raise ValueError('xml-entities-not-allowed')
    document = ET.fromstring(raw)
    tests = list(document.iter('testcase'))
    if not tests:
        raise ValueError('empty-junit-report')
    if 'tests' in document.attrib and int(document.attrib['tests']) != len(tests):
        raise ValueError('inconsistent-junit-denominator')
    failed = sum(any(t.find(tag) is not None for tag in ('failure', 'error', 'skipped')) for t in tests)
    return {'success': failed == 0, 'quality': (len(tests) - failed) / len(tests), 'debugVerified': failed == 0, 'status': 'ok', 'errorClass': None if failed == 0 else 'test-failed'}


if __name__ == '__main__':
    try:
        result = measure(Path.cwd(), json.load(sys.stdin))
    except (OSError, ValueError, KeyError, ET.ParseError):
        result = {'success': False, 'quality': 0.0, 'debugVerified': False, 'status': 'error', 'errorClass': 'adapter-error'}
    print(json.dumps(result))
