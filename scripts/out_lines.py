#!/usr/bin/env python3
"""번호 붙은 파일 창(window) 읽기 — `Get-Content | Select-Object -Skip/-First`,
`for($i=N;$i -le M){...}`, `$s[A..B]` 손 반복을 한 호출로 대체.

out_peek.py(head/tail/grep/json-keys)가 없는 "중간 창" 뷰만 담당한다.
출력은 --max-chars 로 상한(기본 6000), 각 줄은 `n| 내용` 줄번호 형태라
file:line 인용이 바로 된다. 큰 파일도 단일 스트리밍 패스로 읽는다
(창 안 줄만 보관, --tail 은 deque 고정 길이).

사용:
    python -B scripts/out_lines.py <file>
        [--lines A:B]      1기준 inclusive 창; 반복 가능. "A"=한 줄, "A:"=EOF까지
        [--head N]         처음 N줄
        [--tail N]         마지막 N줄
        [--max-chars N]    출력 상한 (기본 6000)
        [--line-width N]   한 줄 최대 글자 (기본 500; 초과분은 …[line-truncated])
        [--no-numbers]     줄번호 생략
        [--encoding NAME]  기본 utf-8 (errors=replace)
        [--json]           {"file","total_lines","size_bytes","windows",...}

exit 0 = 읽기 성공(잘려도 0), exit 2 = 사용/읽기 오류.
"""
from __future__ import annotations

import argparse
import io
import json
import sys
from collections import deque
from pathlib import Path

LINE_CAP = 500


def parse_windows(specs):
    """--lines 인자 리스트 → (start,end) 1기준 inclusive 튜플들."""
    wins = []
    for s in specs:
        s = s.strip()
        if not s:
            continue
        if ":" in s:
            a, b = s.split(":", 1)
        else:
            a, b = s, s
        try:
            start = int(a)
            end = int(b) if b else 0  # "A:" → end=0 → EOF 의미
        except ValueError:
            raise SystemExit("invalid --lines spec: %r" % s)
        if start < 1:
            raise SystemExit("--lines start must be >= 1: %r" % s)
        wins.append((start, end))
    return wins


def merge_windows(wins):
    """겹치거나 인접한 창을 합친다(출력 중복 방지). end=0 은 EOF."""
    if not wins:
        return []
    wins = sorted(wins)
    out = []
    cs, ce = wins[0]
    for s, e in wins[1:]:
        if ce == 0 or s <= ce + 1:
            ce = 0 if (ce == 0 or e == 0) else max(ce, e)
        else:
            out.append((cs, ce))
            cs, ce = s, e
    out.append((cs, ce))
    return out


def clip(line, width):
    if len(line) <= width:
        return line
    return line[:width] + "...[line-truncated]"


def collect(path, windows, tail_n, head_n, encoding):
    """단일 스트리밍 패스: 필요한 창/head/tail 줄만 보관 + total_lines/size."""
    kept = {i: [] for i in range(len(windows))}
    head = []
    tail = deque(maxlen=tail_n) if tail_n else None
    total = 0
    with io.open(path, "r", encoding=encoding, errors="replace") as fh:
        for total, raw in enumerate(fh, 1):
            line = raw.rstrip("\r\n")
            if head_n and total <= head_n:
                head.append((total, line))
            for wi, (s, e) in enumerate(windows):
                if total < s:
                    continue
                if e and total > e:
                    continue
                kept[wi].append((total, line))
            if tail is not None:
                tail.append((total, line))
    return kept, head, list(tail) if tail is not None else [], total


def emit(path, kept, windows, head, tail, total, size, args):
    budget = args.max_chars
    out = []
    jwins = []

    def push(header, rows):
        nonlocal out
        out.append(header)
        for n, line in rows:
            text = clip(line, args.line_width)
            out.append(("%d| " % n + text) if args.numbers else text)

    if head:
        push("@@ head 1-%d @@" % len(head), head)
        jwins.append({"kind": "head", "start": 1, "end": len(head)})
    for wi, (s, e) in enumerate(windows):
        rows = kept[wi]
        last = e if e else total
        push("@@ %d-%d @@" % (s, last), rows)
        jwins.append({"kind": "lines", "start": s, "end": last, "shown": len(rows)})
    if tail:
        start = total - len(tail) + 1
        push("@@ tail %d-%d @@" % (start, total), tail)
        jwins.append({"kind": "tail", "start": start, "end": total})

    header = "== %s (total_lines=%d size=%dB) ==" % (path, total, size)
    body = "\n".join(out)
    truncated = False
    if len(header) + 1 + len(body) > budget:
        room = budget - len(header) - 80
        if room < 200:
            room = 200
        body = body[:room]
        truncated = True
    body = body.rstrip("\n")
    summary = "[out_lines] shown_lines=%d total_lines=%d truncated=%s" % (
        sum(w.get("shown", w["end"] - w["start"] + 1) for w in jwins), total,
        "yes" if truncated else "no")
    if args.json:
        print(json.dumps({
            "file": str(path), "total_lines": total, "size_bytes": size,
            "windows": jwins, "truncated": truncated, "text": header + "\n" + body,
        }, ensure_ascii=False))
    else:
        print(header)
        print(body)
        print(summary)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--lines", action="append", default=[],
                    help="A:B 1기준 inclusive (반복 가능), A=한 줄, A:=EOF까지")
    ap.add_argument("--head", type=int, default=0)
    ap.add_argument("--tail", type=int, default=0)
    ap.add_argument("--max-chars", type=int, default=6000)
    ap.add_argument("--line-width", type=int, default=LINE_CAP)
    ap.add_argument("--no-numbers", dest="numbers", action="store_false")
    ap.add_argument("--encoding", default="utf-8")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    try:
        windows = merge_windows(parse_windows(args.lines))
    except SystemExit as e:
        print("out_lines: %s" % e, file=sys.stderr)
        return 2
    if not windows and not args.head and not args.tail:
        args.head = 80  # 뷰 지정 없으면 head 80 (out_peek과 동일 기본)
    path = Path(args.file)
    try:
        size = path.stat().st_size
        kept, head, tail, total = collect(path, windows, args.tail, args.head, args.encoding)
    except OSError as e:
        print("out_lines: cannot read %s: %s" % (path, e), file=sys.stderr)
        return 2
    emit(path, kept, windows, head, tail, total, size, args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
