#!/usr/bin/env python3
"""선택된 새 JUnit XML의 완료 주장 전제만 검사한다. Python 3.10+.

제품 테스트 실행기·샌드박스·서명 검증기가 아니다. 명령/시각의 진실성, 테스트
단언의 타당성, 네트워크 격리는 호출자가 따로 증명한다. 원본을 수정하지 않고
명시된 JSON 한 개만 배타적으로 생성한다. XML 원문/실패 메시지는 출력하지 않는다.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import xml.etree.ElementTree as ET

MAX_FILES = 32
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_CASES = 100_000
BAD_TAGS = {'failure', 'error', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'}


class EvidenceInputError(ValueError):
    """외부로 노출해도 되는 고정 이유 코드만 담는다."""


def checked_path(value: Path | str) -> Path:
    """링크/재분석 지점을 거절한다. 적대적 동시 교체의 OS 격리는 아니다."""
    p = Path(os.path.abspath(value))
    for part in (p, *p.parents):
        try:
            s = part.lstat()
        except FileNotFoundError:
            continue
        except OSError as exc:
            raise EvidenceInputError('PATH_UNREADABLE') from exc
        if stat.S_ISLNK(s.st_mode) or getattr(s, 'st_file_attributes', 0) & 0x400:
            raise EvidenceInputError('LINK_PATH_REJECTED')
    return p


def read_xml(path: Path) -> tuple[ET.Element, bytes, int]:
    if path.suffix.lower() != '.xml':
        raise EvidenceInputError('XML_EXTENSION_REQUIRED')
    try:
        with path.open('rb') as f:
            before = os.fstat(f.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_FILE_BYTES:
                raise EvidenceInputError('FILE_LIMIT_OR_TYPE')
            data = f.read(MAX_FILE_BYTES + 1)
            after = os.fstat(f.fileno())
        current = path.stat()
    except OSError as exc:
        raise EvidenceInputError('XML_UNREADABLE') from exc
    signature = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns)
    if signature(before) != signature(after) or signature(after) != signature(current):
        raise EvidenceInputError('FILE_CHANGED_DURING_READ')
    if len(data) > MAX_FILE_BYTES:
        raise EvidenceInputError('FILE_LIMIT_OR_TYPE')
    try:
        text = data.decode('utf-8-sig')
    except UnicodeError as exc:
        raise EvidenceInputError('UTF8_REQUIRED') from exc
    if '<!DOCTYPE' in text.upper() or '<!ENTITY' in text.upper():
        raise EvidenceInputError('DTD_OR_ENTITY_REJECTED')
    try:
        root = ET.fromstring(text)
    except (ET.ParseError, ValueError) as exc:
        raise EvidenceInputError('XML_INVALID') from exc
    for node in root.iter():
        if isinstance(node.tag, str):
            node.tag = node.tag.rsplit('}', 1)[-1]
    if root.tag not in {'testsuite', 'testsuites'}:
        raise EvidenceInputError('JUNIT_ROOT_REQUIRED')
    return root, data, after.st_mtime_ns


def assess(xml_paths: list[Path | str], started_ns: int, finished_ns: int,
           command_exit: int, required_cases: list[str]) -> dict:
    """허용된 실행 구간·정확한 테스트 식별자와 보고서를 대조한다."""
    if type(started_ns) is not int or type(finished_ns) is not int or not 0 < started_ns <= finished_ns:
        raise EvidenceInputError('INVALID_RUN_INTERVAL')
    if type(command_exit) is not int:
        raise EvidenceInputError('INTEGER_EXIT_REQUIRED')
    if not required_cases or len(required_cases) > 256:
        raise EvidenceInputError('EXACT_REQUIRED_CASES_NEEDED')
    for value in required_cases:
        if not isinstance(value, str) or '#' not in value or len(value) > 512 or any(ord(c) < 32 for c in value):
            raise EvidenceInputError('INVALID_REQUIRED_CASE')
        cls, name = value.split('#', 1)
        if not cls or not name:
            raise EvidenceInputError('INVALID_REQUIRED_CASE')
    if len(xml_paths) > MAX_FILES:
        raise EvidenceInputError('REPORT_COUNT_LIMIT')
    paths = [checked_path(p) for p in xml_paths]
    if len({os.path.normcase(str(p)) for p in paths}) != len(paths):
        raise EvidenceInputError('DUPLICATE_REPORT_PATH')

    reasons: set[str] = set()
    if command_exit != 0:
        reasons.add('COMMAND_NONZERO')
    if not paths:
        reasons.add('NO_REPORTS')
    seen: Counter[str] = Counter()
    passed_cases: set[str] = set()
    total = passed = skipped = failed = byte_count = 0
    reports = []
    for number, path in enumerate(paths, 1):
        root, raw, modified = read_xml(path)
        byte_count += len(raw)
        if byte_count > MAX_TOTAL_BYTES:
            raise EvidenceInputError('TOTAL_SIZE_LIMIT')
        fresh = started_ns <= modified <= finished_ns
        if not fresh:
            reasons.add('REPORT_OUTSIDE_RUN')
        cases = list(root.iter('testcase'))
        total += len(cases)
        if total > MAX_CASES:
            raise EvidenceInputError('CASE_COUNT_LIMIT')
        if any(node.tag in BAD_TAGS for node in root.iter()):
            reasons.add('REPORTED_FAILURE')
        for case in cases:
            class_name, name = case.get('classname', ''), case.get('name', '')
            if not class_name or not name:
                reasons.add('CASE_ID_MISSING')
            identity = class_name + '#' + name
            seen[identity] += 1
            tags = {node.tag for node in case.iter()}
            is_failed = bool(tags & BAD_TAGS)
            is_skipped = 'skipped' in tags or case.get('status', '').lower() in {'notrun', 'skipped', 'disabled'}
            if is_failed:
                failed += 1
            elif is_skipped:
                skipped += 1
            else:
                passed += 1
                passed_cases.add(identity)
        for suite in root.iter():
            if suite.tag not in {'testsuite', 'testsuites'}:
                continue
            local_cases = list(suite.iter('testcase'))
            expected = {
                'tests': len(local_cases),
                'failures': sum(any(n.tag == 'failure' for n in c.iter()) for c in local_cases),
                'errors': sum(any(n.tag == 'error' for n in c.iter()) for c in local_cases),
                'skipped': sum(any(n.tag == 'skipped' for n in c.iter()) for c in local_cases),
            }
            for key, count in expected.items():
                if key in suite.attrib:
                    try:
                        declared = int(suite.attrib[key])
                    except ValueError as exc:
                        raise EvidenceInputError('INVALID_JUNIT_COUNTER') from exc
                    if declared != count:
                        reasons.add('DECLARED_COUNTS_MISMATCH')
        reports.append({'report_index': number, 'sha256': hashlib.sha256(raw).hexdigest(),
                        'bytes': len(raw), 'modified_ns': modified, 'within_run': fresh,
                        'testcases': len(cases)})
    if total == 0:
        reasons.add('ZERO_TESTS')
    if skipped:
        reasons.add('SKIPPED_TESTS')
    if any(n > 1 for n in seen.values()):
        reasons.add('DUPLICATE_TEST_CASE')
    missing = set(required_cases) - passed_cases
    if missing:
        reasons.add('REQUIRED_CASE_MISSING')
    return {
        'schema': 'aw-junit-evidence-v1',
        'status': 'EVIDENCE_REJECTED' if reasons else 'EVIDENCE_ACCEPTED',
        'reasons': sorted(reasons),
        'command_exit_supplied': command_exit,
        'started_ns_supplied': started_ns,
        'finished_ns_supplied': finished_ns,
        'total': total, 'passed': passed, 'failed': failed, 'skipped': skipped,
        'required_cases': len(set(required_cases)), 'required_cases_missing': len(missing),
        'reports': reports,
        'scope': 'selected_junit_reports_only',
        'not_proven': ['command_authenticity', 'source_revision_binding', 'assertion_quality',
                       'network_isolation', 'integration_correctness', 'all_suite_coverage'],
    }


def write_exclusive(path: Path | str, result: dict) -> None:
    p = checked_path(path)
    if p.suffix.lower() != '.json' or not p.parent.is_dir():
        raise EvidenceInputError('EXISTING_PARENT_AND_JSON_OUTPUT_REQUIRED')
    data = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    try:
        with p.open('x', encoding='utf-8', newline='\n') as f:
            f.write(data)
    except FileExistsError as exc:
        raise EvidenceInputError('OUTPUT_EXISTS') from exc
    except OSError as exc:
        raise EvidenceInputError('OUTPUT_UNWRITABLE') from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description='선택 JUnit XML의 새 실행·대상 테스트·실패/skip 여부 검사')
    parser.add_argument('--xml', action='append', default=[], help='명시한 XML 파일. 여러 번 지정 가능')
    parser.add_argument('--started-ns', required=True, type=int, help='명령 직전 time.time_ns()')
    parser.add_argument('--finished-ns', required=True, type=int, help='종료 직후 time.time_ns()')
    parser.add_argument('--command-exit', required=True, type=int, help='테스트 명령의 원래 종료 코드')
    parser.add_argument('--require-case', action='append', required=True, help='정확한 classname#name; 여러 번 지정 가능')
    parser.add_argument('--out', required=True, help='존재하지 않는 결과 JSON 경로')
    args = parser.parse_args(argv)
    try:
        result = assess(args.xml, args.started_ns, args.finished_ns, args.command_exit, args.require_case)
        write_exclusive(args.out, result)
    except EvidenceInputError as exc:
        print(json.dumps({'status':'INPUT_ERROR','reason':str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2
    print(json.dumps({'status':result['status'], 'reasons':result['reasons'],
                      'total':result['total'], 'passed':result['passed'], 'skipped':result['skipped']},
                     ensure_ascii=False))
    return 0 if result['status'] == 'EVIDENCE_ACCEPTED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
