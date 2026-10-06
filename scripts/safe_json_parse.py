#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""safe_json_parse — 노이즈 섞인 CLI 출력에서 첫 번째 유효 JSON 객체만 추출.

용도: 코덱스 exec 결과 앞에 붙는 `Warning: truncated output ...`,
PowerShell 경고, 진행 로그 등이 섞여 `JSON.parse`가
`SyntaxError: Unexpected token 'W'`로 깨지는 실패를 사전 차단한다.

입력 (우선순위): --text <문자열> > --file <경로> > 위치인자(존재하면 파일, 아니면 문자열) > stdin
출력: 파싱된 JSON을 포맷팅해 stdout으로 출력.
종료코드: 0=파싱 성공, 1=유효 JSON 없음, 2=입력/인자 오류.
자체점검: `--test` 실행 시 내장 노이즈 샘플로 검증 후 exit 0.
표준 라이브러리만 사용 (json/re/sys/argparse/pathlib).
"""
import argparse
import io
import json
import re
import sys
from pathlib import Path

# 흔한 노이즈 접두 패턴 (참고용 — 실제 추출은 브레이스 스캔이 담당)
_NOISE_PREFIX_RE = re.compile(
    r"^(?:warning|warn|error|verbose|debug|info|notice|progress|hint)\b",
    re.IGNORECASE,
)


def _read_stdin():
    # 콘솔 인코딩과 무관하게 바이트로 읽어 utf-8 우선 디코딩
    data = sys.stdin.buffer.read() if hasattr(sys.stdin, "buffer") else sys.stdin.read()
    if isinstance(data, bytes):
        return data.decode("utf-8", errors="replace")
    return data


def _read_input(args):
    if args.text is not None:
        return args.text
    path_text = args.file or args.input
    if path_text and path_text != "-":
        p = Path(path_text)
        if p.is_file():
            return p.read_bytes().decode("utf-8", errors="replace")
        if args.file:
            raise SystemExit(2)  # --file 로 준 경로가 없으면 인자 오류
        return path_text  # 위치인자가 파일이 아니면 리터럴 문자열로 취급
    return _read_stdin()


def _find_json_slice(text):
    """문자열/이스케이프를 고려한 브레이스 매칭으로 첫 유효 JSON 구간을 찾는다."""
    text = text.strip()
    if not text:
        return None
    # 빠른 경로: 전체가 이미 깨끗한 JSON
    try:
        json.loads(text)
        return text
    except (json.JSONDecodeError, ValueError):
        pass
    # 후보 시작 위치마다 브레이스 매칭 → json.loads 성공하는 첫 구간 채택
    for m in re.finditer(r"[{\[]", text):
        start = m.start()
        stack = []
        in_str = False
        esc = False
        i = start
        while i < len(text):
            ch = text[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            else:
                if ch == '"':
                    in_str = True
                elif ch in "{[":
                    stack.append(ch)
                elif ch in "}]":
                    if not stack:
                        break
                    open_ch = stack.pop()
                    if (open_ch == "{") != (ch == "}"):
                        break  # 짝이 안 맞음 → 이 후보 폐기
                    if not stack:
                        cand = text[start : i + 1]
                        try:
                            json.loads(cand)
                            return cand
                        except (json.JSONDecodeError, ValueError):
                            break
            i += 1
    return None


def _emit(obj_text, args):
    obj = json.loads(obj_text)  # 이미 검증된 구간
    if args.raw:
        sys.stdout.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")))
    else:
        sys.stdout.write(json.dumps(obj, ensure_ascii=False, indent=2))
    sys.stdout.write("\n")
    return 0


def _self_test():
    """노이즈/순수/중첩/문자열 내 브레이스/실패 케이스를 검증."""
    cases = [
        # (입력, 기대 exit, 기대 파싱값)
        ('Warning: truncated output (max 2000 lines)\n{"ok": true, "n": 3}\nDone.', 0, {"ok": True, "n": 3}),
        ("VERBOSE: running\n[1, 2, 3]\ntrail", 0, [1, 2, 3]),
        ('prefix {"a": "x}y"} {"b": 2}', 0, {"a": "x}y"}),  # 문자열 속 } 무시
        ('   {"k": [1, {"z": 0}]}   ', 0, {"k": [1, {"z": 0}]}),
        ("no json at all", 1, None),
        ('Warning: {not json} then {"real": 1}', 0, {"real": 1}),  # 가짜 브레이스 건너뛰기
        ("", 1, None),
    ]
    failures = 0
    for text, want_exit, want_obj in cases:
        sl = _find_json_slice(text)
        got_exit = 0 if sl is not None else 1
        ok = got_exit == want_exit
        if ok and want_obj is not None:
            ok = json.loads(sl) == want_obj
        if not ok:
            failures += 1
            print("FAIL case: %r (exit=%s)" % (text[:60], got_exit), file=sys.stderr)
    if failures:
        print("self-test failures=%d" % failures, file=sys.stderr)
        return 1
    print("safe_json_parse self-test: %d/%d cases pass" % (len(cases), len(cases)))
    return 0


def _utf8_stdio():
    # cp949 콘솔에서 한글이 섞인 JSON 출력이 UnicodeEncodeError로 깨지지 않게 한다
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def main(argv=None):
    _utf8_stdio()
    ap = argparse.ArgumentParser(
        description="노이즈 섞인 CLI 출력에서 첫 유효 JSON 객체만 추출한다.")
    ap.add_argument("input", nargs="?", help="파일 경로(존재 시) 또는 리터럴 문자열, '-'=stdin")
    ap.add_argument("--file", help="읽을 파일 경로 (명시적)")
    ap.add_argument("--text", help="리터럴 입력 문자열")
    ap.add_argument("--raw", action="store_true", help="들여쓰기 없이 한 줄 출력")
    ap.add_argument("--test", action="store_true", help="내장 자체점검 실행 후 종료")
    args = ap.parse_args(argv)

    if args.test:
        return _self_test()

    try:
        text = _read_input(args)
    except SystemExit:
        raise
    except Exception as e:
        print("read-failed: %s" % e, file=sys.stderr)
        return 2

    sl = _find_json_slice(text)
    if sl is None:
        print("no valid JSON object/array found in input", file=sys.stderr)
        return 1
    return _emit(sl, args)


if __name__ == "__main__":
    sys.exit(main())
