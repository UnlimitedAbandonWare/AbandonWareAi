#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fast_patch_preflight — apply_patch 전 컨텍스트 줄 일치 여부 0.1초 사전 확인.

배경: `apply_patch verification failed: Failed to find expected lines in ...`
는 파일이 최신 상태와 어긋났을 때 발생한다. 패치를 던지기 전에 기준 문자열
(needle)이 파일 안에 실제로 존재하는지만 확인하면 실패를 사전 차단할 수 있다.

용법:
  python -B scripts/fast_patch_preflight.py --file <경로> --needle <문자열>
  python -B scripts/fast_patch_preflight.py --file <경로> --needle-file <경로>
  python -B scripts/fast_patch_preflight.py --file <경로>   # needle은 stdin
  python -B scripts/fast_patch_preflight.py --test          # 자체점검

종료코드: 0=일치, 1=불일치(유사 라인 안내), 2=입력/인자 오류, 3=파일 읽기 실패.
불일치 시 difflib 유사도 상위 라인 ±3줄 컨텍스트를 출력해 재기준점을 돕는다.
"""
import argparse
import difflib
import json
import sys
import tempfile
from pathlib import Path

CONTEXT = 3  # 힌트 라인 상하 문맥


def _norm(text):
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _find_needle(haystack, needle):
    """반환: ('exact', occurrences) | ('normalized-only', occurrences) | ('missing', 0)."""
    n = haystack.count(needle)
    if n:
        return "exact", n
    nh, nn = _norm(haystack), _norm(needle)
    m = nh.count(nn)
    if m:
        return "normalized-only", m
    return "missing", 0


def _hints(haystack, needle, top=3):
    """needle 첫 줄 기준 유사 라인 top-N과 ±CONTEXT 문맥을 만든다."""
    lines = _norm(haystack).split("\n")
    probe = _norm(needle).split("\n")[0].strip() or _norm(needle).strip()[:80]
    if not probe:
        return []
    scored = sorted(
        ((difflib.SequenceMatcher(None, probe, ln.strip()).ratio(), i)
         for i, ln in enumerate(lines)),
        key=lambda x: -x[0])[:top]
    out = []
    for ratio, i in scored:
        if ratio < 0.3:
            continue
        lo, hi = max(0, i - CONTEXT), min(len(lines), i + CONTEXT + 1)
        out.append({
            "lineNo": i + 1,
            "similarity": round(ratio, 3),
            "context": [
                {"lineNo": j + 1, "text": lines[j]} for j in range(lo, hi)
            ],
        })
    return out


def check(file_path, needle):
    p = Path(file_path)
    if not p.is_file():
        return 2, {"verdict": "file-missing", "file": str(file_path)}
    try:
        haystack = p.read_bytes().decode("utf-8", errors="replace")
    except OSError as e:
        return 3, {"verdict": "read-failed", "file": str(file_path), "error": str(e)}
    kind, count = _find_needle(haystack, needle)
    if kind == "exact":
        return 0, {"verdict": "exact", "file": str(file_path), "occurrences": count,
                   "note": "occurrences>1 may fail apply_patch as ambiguous"}
    if kind == "normalized-only":
        return 0, {"verdict": "normalized-only", "file": str(file_path),
                   "occurrences": count,
                   "note": "line endings differ (CRLF/LF) — match patch newlines to file"}
    return 1, {"verdict": "missing", "file": str(file_path),
               "hints": _hints(haystack, needle)}


def _self_test():
    td = Path(tempfile.mkdtemp(prefix="fpp-test-"))
    f = td / "sample.txt"
    f.write_bytes("line one\nline two\nline three\nline four\n".encode("utf-8"))
    code, res = check(f, "line two")
    assert code == 0 and res["verdict"] == "exact", res
    code, res = check(f, "line two\nline three")  # 멀티라인 needle
    assert code == 0 and res["verdict"] == "exact", res
    crlf = td / "crlf.txt"
    crlf.write_bytes(b"alpha\r\nbeta\r\ngamma\r\n")
    code, res = check(crlf, "alpha\nbeta")  # LF needle vs CRLF file
    assert code == 0 and res["verdict"] == "normalized-only", res
    code, res = check(f, "line too")
    assert code == 1 and res["hints"], res
    code, res = check(td / "absent.txt", "x")
    assert code == 2, res
    print("fast_patch_preflight self-test: pass")
    return 0


def _utf8_stdio():
    # cp949 콘솔에서 한글 출력이 깨지지 않게 한다
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def main(argv=None):
    _utf8_stdio()
    ap = argparse.ArgumentParser(
        description="apply_patch 전 기준 문자열 존재 여부 사전 확인.")
    ap.add_argument("--file", help="대상 파일 경로")
    ap.add_argument("--needle", help="찾을 기준 문자열 (리터럴)")
    ap.add_argument("--needle-file", dest="needle_file", help="기준 문자열을 담은 파일")
    ap.add_argument("--test", action="store_true", help="자체점검")
    args = ap.parse_args(argv)

    if args.test:
        return _self_test()
    if not args.file:
        ap.error("--file is required")
    needle = args.needle
    if needle is None and args.needle_file:
        try:
            needle = Path(args.needle_file).read_bytes().decode("utf-8", errors="replace")
        except OSError as e:
            print("needle-file read failed: %s" % e, file=sys.stderr)
            return 2
    if needle is None:
        needle = (sys.stdin.buffer.read() if hasattr(sys.stdin, "buffer")
                  else sys.stdin.read())
        if isinstance(needle, bytes):
            needle = needle.decode("utf-8", errors="replace")
    if needle == "":
        print("empty needle", file=sys.stderr)
        return 2

    code, res = check(args.file, needle)
    res["schema"] = "awx.patch-preflight.v1"
    out = json.dumps(res, ensure_ascii=False, indent=2)
    if code in (1, 2, 3):
        print(out, file=sys.stderr)
    else:
        print(out)
    return code


if __name__ == "__main__":
    sys.exit(main())
