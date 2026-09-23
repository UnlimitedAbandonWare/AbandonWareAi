#!/usr/bin/env python3
"""
broad_catch_classifier.py
=========================
Java 소스에서 catch 블록을 3가지로 분류합니다:

  INTENTIONAL_IGNORE  — 변수명/주석으로 의도적 무시 명시
  HAS_BREADCRUMB      — traceSuppressed / TraceStore.put / SafeRedactor 등 존재
  GENUINE_SILENT      — 아무 처리도 없는 진짜 무음 catch

Usage:
  python broad_catch_classifier.py --root main/java [--output data/broad-catch-report/] [--top N]
"""

import argparse
import ast
import json
import pathlib
import re
import sys
from collections import defaultdict
from datetime import datetime

# ---------------------------------------------------------------------------
# 패턴 상수
# ---------------------------------------------------------------------------

# 의도적 ignore 변수명 패턴 (catch (Exception __ignoreFoo) 등)
INTENTIONAL_VAR_PATTERN = re.compile(
    r'catch\s*\(\s*\w+\s+(?:'
    r'ignore|__ignore|_unused|noop|__noop|__ignore\w*|ignored\w*'
    r')\s*\)',
    re.IGNORECASE,
)

# breadcrumb가 있는 catch (traceSuppressed, TraceStore.put, SafeRedactor, logger calls)
BREADCRUMB_PATTERN = re.compile(
    r'traceSuppressed|(?<![\w.])traceChatSuppressed\s*\(|'
    r'(?<![\w.])traceContextFallbackSkipped\s*\(|TraceStore\.put|SafeRedactor|safeTrace|'
    r'traceTelemetrySkipped|traceSkipped|traceFailure|traceCancelFailure|'
    r'traceSearchPolicyFailure|logSuppressed|recordDebugEventEmitFailure|'
    r'traceContextPropagationSkipped|recordDebugEventResolveFailure|recordError|'
    r'traceAspectError|traceParseSkipped|recordRunOnceFailure|recordFailure|'
    r'[A-Za-z0-9_]+TraceSuppressions\.trace|'
    r'EmbeddingTraceSuppressions\.trace|DegradedStorageTraceSuppressions\.trace|'
    r'recordNoiseFilterFallback|'
    r'(?<![\w.])(?:log|LOG|logger|LOGGER)\.(?:info|warn|error|debug|trace)\s*\('
)

# 진짜 silent catch 를 가리키는 패턴:
# catch 블록 바디가 비거나 단순 return/continue/break/throw 이후 처리 없음
BROAD_EXCEPTION_PATTERN = re.compile(
    r'catch\s*\(\s*(?:Exception|Throwable|RuntimeException|Error)\s+\w+'
)


# ---------------------------------------------------------------------------
# 핵심 분류 로직
# ---------------------------------------------------------------------------

class CatchBlock:
    """단일 catch 블록 정보"""
    __slots__ = ('file', 'line', 'raw_catch_header', 'body', 'category', 'exception_type', 'var_name')

    def __init__(self, file, line, raw_catch_header, body):
        self.file = file
        self.line = line
        self.raw_catch_header = raw_catch_header.strip()
        self.body = body.strip()
        self.category = None
        # exception_type, var_name 파싱
        m = re.search(r'catch\s*\(\s*(\w+(?:\s*\|\s*\w+)*)\s+(\w+)\s*\)', raw_catch_header)
        if m:
            self.exception_type = m.group(1).strip()
            self.var_name = m.group(2).strip()
        else:
            self.exception_type = 'unknown'
            self.var_name = 'unknown'

    def classify(self) -> str:
        """
        분류 순서:
        1. 변수명이 intentional ignore 패턴 → INTENTIONAL_IGNORE
        2. 바디에 breadcrumb 존재 → HAS_BREADCRUMB
        3. 나머지 → GENUINE_SILENT
        """
        # 1. 변수명 패턴
        var_lower = self.var_name.lower()
        if (var_lower.startswith('__ignore') or
                var_lower == 'ignore' or
                var_lower.startswith('ignored') or
                var_lower.startswith('_unused') or
                var_lower == 'noop' or
                var_lower.startswith('__noop')):
            self.category = 'INTENTIONAL_IGNORE'
            return self.category

        # 2. 바디가 비어있고 변수가 일반적이면 → GENUINE_SILENT
        body = self.body
        if not body or body in ('{}', '{ }'):
            self.category = 'GENUINE_SILENT'
            return self.category

        # 3. breadcrumb 패턴
        if BREADCRUMB_PATTERN.search(body):
            self.category = 'HAS_BREADCRUMB'
            return self.category

        # 4. Propagating or converting the error is not a silent swallow.
        if re.search(r'\bthrow\b', body):
            self.category = 'INTENTIONAL_IGNORE'
            return self.category

        # 5. 광범위 exception인데 단순 처리
        if BROAD_EXCEPTION_PATTERN.search(self.raw_catch_header):
            # 바디가 단순 return/continue/break 이거나 매우 짧음
            body_stripped = re.sub(r'\s+', ' ', body).strip()
            if len(body_stripped) < 80 and not BREADCRUMB_PATTERN.search(body_stripped):
                # log가 없으면 GENUINE_SILENT
                if not re.search(r'\blog\b|\bLOG\b|\blogger\b|\bLOGGER\b', body_stripped):
                    self.category = 'GENUINE_SILENT'
                    return self.category

        # 5. 기타 — 뭔가 있지만 breadcrumb 미확인 → INTENTIONAL_IGNORE로 보수적 처리
        self.category = 'INTENTIONAL_IGNORE'
        return self.category


# ---------------------------------------------------------------------------
# Java 파일 파싱
# ---------------------------------------------------------------------------

def extract_catch_blocks(path: pathlib.Path) -> list:
    """
    단순 텍스트 파싱으로 catch 블록을 추출합니다.
    AST 파싱은 Java에 불가하므로 중괄호 카운팅 방식 사용.
    """
    try:
        text = path.read_text(encoding='utf-8', errors='replace')
    except Exception:
        return []

    text = strip_java_comments(text)
    lines = text.splitlines()
    results = []
    i = 0
    while i < len(lines):
        line = lines[i]
        # catch 헤더 탐지
        if re.search(r'\bcatch\s*\(', line):
            catch_line = i + 1  # 1-indexed
            # 헤더 수집 (한 줄 또는 여러 줄)
            header = line
            j = i
            # 여는 중괄호 찾기
            open_idx = catch_body_open_index(line)
            while j < len(lines) and open_idx < 0:
                j += 1
                if j < len(lines):
                    header += ' ' + lines[j]
                    line = lines[j]
                    open_idx = line.find('{')
            # 바디 수집 (중괄호 카운팅)
            body, k = capture_braced_body(lines, j, open_idx)
            cb = CatchBlock(
                file=str(path),
                line=catch_line,
                raw_catch_header=header,
                body=body,
            )
            cb.classify()
            results.append(cb)
            i = k + 1
            continue
        i += 1
    return results


def strip_java_comments(text: str) -> str:
    """Mask Java comments and literals while preserving structural newlines."""
    out = []
    i = 0
    in_block = False
    in_line = False
    in_string = False
    in_char = False
    in_text_block = False
    escaped = False

    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ''

        if in_block:
            if ch == '\n':
                out.append(ch)
            if ch == '*' and nxt == '/':
                in_block = False
                i += 2
            else:
                i += 1
            continue

        if in_line:
            if ch == '\n':
                in_line = False
                out.append(ch)
            i += 1
            continue

        if in_text_block:
            if ch == '"' and text.startswith('"""', i) and not escaped:
                out.extend('   ')
                in_text_block = False
                i += 3
                continue
            out.append('\n' if ch == '\n' else ' ')
            if escaped:
                escaped = False
            elif ch == '\\':
                escaped = True
            i += 1
            continue

        if in_string or in_char:
            out.append('\n' if ch == '\n' else ' ')
            if escaped:
                escaped = False
            elif ch == '\\':
                escaped = True
            elif in_string and ch == '"':
                in_string = False
            elif in_char and ch == "'":
                in_char = False
            i += 1
            continue

        if ch == '/' and nxt == '*':
            in_block = True
            i += 2
            continue
        if ch == '/' and nxt == '/':
            in_line = True
            i += 2
            continue

        if text.startswith('"""', i):
            out.extend('   ')
            in_text_block = True
            i += 3
            continue
        if ch == '"':
            out.append(' ')
            in_string = True
            i += 1
            continue
        if ch == "'":
            out.append(' ')
            in_char = True
            i += 1
            continue

        out.append(ch)
        i += 1

    return ''.join(out)


def catch_body_open_index(line: str) -> int:
    """Return the catch-body opening brace, ignoring earlier try-block braces."""
    m = re.search(r'\bcatch\s*\(', line)
    search_from = m.end() if m else 0
    return line.find('{', search_from)


def capture_braced_body(lines: list, start_line: int, open_idx: int = -1) -> tuple:
    """Return body text inside the catch braces and the closing line index."""
    if start_line >= len(lines):
        return '', start_line
    first = lines[start_line]
    if open_idx < 0:
        open_idx = first.find('{')
    if open_idx < 0:
        return '', start_line

    body_lines = []
    current = []
    depth = 1
    k = start_line
    text = first[open_idx + 1:]

    while k < len(lines):
        for ch in text:
            if ch == '{':
                depth += 1
                current.append(ch)
            elif ch == '}':
                depth -= 1
                if depth <= 0:
                    if ''.join(current).strip():
                        body_lines.append(''.join(current))
                    return '\n'.join(body_lines), k
                current.append(ch)
            else:
                current.append(ch)

        if ''.join(current).strip() or k > start_line:
            body_lines.append(''.join(current))
        current = []
        k += 1
        if k < len(lines):
            text = lines[k]

    return '\n'.join(body_lines), max(start_line, len(lines) - 1)


# ---------------------------------------------------------------------------
# 집계 + 리포트
# ---------------------------------------------------------------------------

def scan_root(root: pathlib.Path, exclude_test: bool = True) -> dict:
    """root 아래 모든 .java 파일을 스캔하여 집계 결과 반환"""
    by_file = defaultdict(lambda: {'INTENTIONAL_IGNORE': 0, 'HAS_BREADCRUMB': 0, 'GENUINE_SILENT': 0, 'total': 0})
    totals = {'INTENTIONAL_IGNORE': 0, 'HAS_BREADCRUMB': 0, 'GENUINE_SILENT': 0, 'total': 0}
    genuine_silent_top = []  # (count, file, list of CatchBlock)

    java_files = list(root.rglob('*.java'))
    if exclude_test:
        java_files = [f for f in java_files if not any(p in str(f) for p in ['/test/', '\\test\\', 'Test.java', 'Tests.java'])]

    print(f'Scanning {len(java_files)} Java files in {root}...', file=sys.stderr)

    for path in java_files:
        blocks = extract_catch_blocks(path)
        rel = str(path.relative_to(root) if path.is_relative_to(root) else path)
        file_genuine = []
        for cb in blocks:
            by_file[rel][cb.category] += 1
            by_file[rel]['total'] += 1
            totals[cb.category] += 1
            totals['total'] += 1
            if cb.category == 'GENUINE_SILENT':
                file_genuine.append(cb)
        if file_genuine:
            genuine_silent_top.append((len(file_genuine), rel, file_genuine))

    genuine_silent_top.sort(key=lambda x: -x[0])
    return {
        'scanned': len(java_files),
        'totals': totals,
        'by_file': dict(by_file),
        'genuine_silent_top': genuine_silent_top,
    }


def build_markdown(result: dict, top_n: int = 15) -> str:
    t = result['totals']
    lines = [
        '# Broad Catch Classifier Report',
        f'Generated: {datetime.utcnow().isoformat()}Z',
        '',
        '## Summary',
        f'| Category | Count | % |',
        f'|----------|-------|---|',
        f'| **GENUINE_SILENT** (진짜 무음) | **{t["GENUINE_SILENT"]}** | **{100*t["GENUINE_SILENT"]/max(t["total"],1):.1f}%** |',
        f'| HAS_BREADCRUMB (breadcrumb 있음) | {t["HAS_BREADCRUMB"]} | {100*t["HAS_BREADCRUMB"]/max(t["total"],1):.1f}% |',
        f'| INTENTIONAL_IGNORE (의도적 무시) | {t["INTENTIONAL_IGNORE"]} | {100*t["INTENTIONAL_IGNORE"]/max(t["total"],1):.1f}% |',
        f'| **Total** | **{t["total"]}** | 100% |',
        '',
        f'## Top {top_n} Files with GENUINE_SILENT Catches',
        '',
        '| # | File | GENUINE_SILENT | HAS_BREADCRUMB | INTENTIONAL |',
        '|---|------|---------------|----------------|-------------|',
    ]
    top = result['genuine_silent_top'][:top_n]
    for rank, (cnt, fname, blocks) in enumerate(top, 1):
        short = fname.split('\\')[-1].split('/')[-1]
        bf = result['by_file'].get(fname, {})
        lines.append(f'| {rank} | {short} | **{cnt}** | {bf.get("HAS_BREADCRUMB",0)} | {bf.get("INTENTIONAL_IGNORE",0)} |')

    lines += ['', '## GENUINE_SILENT Detail (Top 5 Files)', '']
    for cnt, fname, blocks in top[:5]:
        short = fname.split('\\')[-1].split('/')[-1]
        lines.append(f'### {short} ({cnt} genuine silent catches)')
        lines.append('')
        for cb in blocks[:10]:
            lines.append(f'- Line {cb.line}: `catch ({cb.exception_type} {cb.var_name})` — body_len={len(cb.body)}')
        if len(blocks) > 10:
            lines.append(f'- ... {len(blocks)-10} more')
        lines.append('')

    return '\n'.join(lines)


def build_json_output(result: dict, top_n: int = 30) -> dict:
    t = result['totals']
    top_files = []
    for cnt, fname, blocks in result['genuine_silent_top'][:top_n]:
        short = fname.split('\\')[-1].split('/')[-1]
        top_files.append({
            'file': short,
            'path': fname,
            'genuine_silent': cnt,
            'sample_lines': [cb.line for cb in blocks[:5]],
        })
    return {
        'generated_at': datetime.utcnow().isoformat() + 'Z',
        'scanned_files': result['scanned'],
        'totals': t,
        'genuine_silent_ratio': round(t['GENUINE_SILENT'] / max(t['total'], 1), 4),
        'top_genuine_silent_files': top_files,
    }


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description='Classify Java catch blocks')
    parser.add_argument('--root', default='main/java', help='Java source root')
    parser.add_argument('--output', default='data/broad-catch-report/', help='Output directory')
    parser.add_argument('--top', type=int, default=15, help='Top N files to show')
    parser.add_argument('--include-test', action='store_true', help='Include test files')
    args = parser.parse_args()

    root = pathlib.Path(args.root)
    if not root.exists():
        print(f'ERROR: root {root} does not exist', file=sys.stderr)
        sys.exit(1)

    result = scan_root(root, exclude_test=not args.include_test)

    # 출력
    out_dir = pathlib.Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    md = build_markdown(result, args.top)
    md_path = out_dir / 'broad_catch_report.md'
    md_path.write_text(md, encoding='utf-8')

    jdata = build_json_output(result, top_n=30)
    json_path = out_dir / 'broad_catch_report.json'
    json_path.write_text(json.dumps(jdata, indent=2, ensure_ascii=False), encoding='utf-8')

    t = result['totals']
    print('=' * 60)
    print('  Broad Catch Classifier Results')
    print('=' * 60)
    print(f'  Scanned: {result["scanned"]} files')
    print(f'  Total catches: {t["total"]}')
    print(f'  GENUINE_SILENT:     {t["GENUINE_SILENT"]:>5}  ({100*t["GENUINE_SILENT"]/max(t["total"],1):.1f}%)')
    print(f'  HAS_BREADCRUMB:     {t["HAS_BREADCRUMB"]:>5}  ({100*t["HAS_BREADCRUMB"]/max(t["total"],1):.1f}%)')
    print(f'  INTENTIONAL_IGNORE: {t["INTENTIONAL_IGNORE"]:>5}  ({100*t["INTENTIONAL_IGNORE"]/max(t["total"],1):.1f}%)')
    print('=' * 60)
    print(f'  Top files (GENUINE_SILENT):')
    for cnt, fname, _ in result['genuine_silent_top'][:10]:
        short = fname.split('\\')[-1].split('/')[-1]
        print(f'    {cnt:>4}  {short}')
    print(f'\n  MD  → {md_path}')
    print(f'  JSON→ {json_path}')


if __name__ == '__main__':
    main()
