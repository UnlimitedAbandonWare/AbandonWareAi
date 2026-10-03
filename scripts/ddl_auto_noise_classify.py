#!/usr/bin/env python3
"""ddl_auto_noise_classify.py — Hibernate ddl-auto DDL 소음과 진짜 에러 분리.

Contract DEMO1-DEVIN-F01B-POST-TOOLS-20260929 항목 2.

배경: 수동 DDL(migration/*.sql apply)과 `spring.jpa.hibernate.ddl-auto` 가 겹쳐
부팅 로그에 "Error executing DDL ... already exists" 예외가 매번 쌓인다
(final-runtime-verify exceptions=132). 진짜 에러가 소음에 묻히는 것을 막기 위해
예외를 `known_noise`(already-exists 계열)와 `real`(그 외)로 분리한다.

- `classify_h2_ddl_warnings.py` 의 스캔 규칙을 import 해 재사용한다.
- ddl-auto 설정값은 application*.yml 을 읽기 전용으로 보고한다 — 설정 변경은
  절대 하지 않고, 필요하면 `forCodexRecommendations` 텍스트로만 남긴다.

입력: <log path> | --latest (최신 launcher out.log) | --verify-json <path>
      (awx.rag_debug.v1 verify 결과에서 exceptions 클래스만 분리)
exit 0 classified / 1 usage / 2 log-not-found / 3 no-data(unclassified input).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import sys

import classify_h2_ddl_warnings as h2w

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

CONTRACT_ID = "DEMO1-DEVIN-F01B-POST-TOOLS-20260929"
SCHEMA = "awx.f01b-ddl-noise-classify.v1"
DEFAULT_OUT = "data/diagnostics/f01b-post-tools-0929/ddl_auto_noise_classify.json"

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_NOT_FOUND = 2
EXIT_NO_DATA = 3

NOISE_SUFFIX = "already exists"
FATAL_CLASSES = ("runtime-classpath", "spring-fatal", "config", "port-bind")


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def find_ddl_auto(root: Path) -> dict:
    """application*.yml 에서 ddl-auto 설정값 읽기 전용 탐색. 없으면 unset."""
    found = {}
    candidates = list((root / "main" / "resources").glob("application*.yml")) + \
        list((root / "main" / "resources").glob("application*.yaml"))
    for path in sorted(candidates):
        try:
            for line in path.read_text(encoding="utf-8",
                                       errors="replace").splitlines():
                m = re.search(r"ddl-auto\s*:\s*([A-Za-z-]+)", line)
                if m:
                    found[str(path.relative_to(root)).replace("\\", "/")] = \
                        m.group(1).strip()
        except OSError:
            continue
    return found


def buckets_from_classified(result: dict) -> tuple[dict, dict]:
    """classify_h2_ddl_warnings 결과를 known_noise / real 로 분리."""
    noise_kinds, real_kinds = {}, {}
    for kind, cnt in (result.get("kindCounts") or {}).items():
        if kind.endswith(NOISE_SUFFIX):
            noise_kinds[kind] = cnt
        else:
            real_kinds[kind] = cnt
    return noise_kinds, real_kinds


def verdict_for(noise: int, real: int, fatal: int) -> str:
    total = noise + real + fatal
    if total == 0:
        return "none-observed"
    if real + fatal == 0:
        return "all-known-noise"
    if noise == 0:
        return "real-only"
    return "mixed"


def recommendations(ddl_auto: dict, noise: int, real: int) -> list[str]:
    recs = []
    if noise > 0:
        current = next(iter(ddl_auto.values()), "unset")
        if str(current).lower() not in ("validate", "none"):
            recs.append(
                f"ddl-auto={current} 가 수동 DDL 과 충돌해 부팅마다 {noise}건의 "
                "'already exists' 예외를 냄. FOR_CODEX 권고: "
                "spring.jpa.hibernate.ddl-auto=validate(또는 none) 전환 검토 — "
                "이 도구는 설정을 바꾸지 않는다.")
        else:
            recs.append(
                f"ddl-auto={current} 인데도 {noise}건의 already-exists 소음 — "
                "수동 DDL apply 멱등성/실행자를 Codex 가 재확인 권고.")
    if real > 0:
        recs.append(f"real 에러 {real}건 — 소음과 별도로 개별 조사 필요.")
    return recs


def classify_log(path: Path, root: Path) -> dict:
    result = h2w.classify(path.read_text(encoding="utf-8", errors="replace"))
    noise_kinds, real_kinds = buckets_from_classified(result)
    ddl_auto = find_ddl_auto(root)
    noise_cnt = sum(noise_kinds.values())
    real_cnt = sum(real_kinds.values())
    return {
        "source": {"kind": "log", "path": str(path)},
        "ddlWarnLines": result.get("ddlWarnLines", 0),
        "hibernateWarnLines": result.get("hibernateWarnLines", 0),
        "knownNoise": {"count": noise_cnt, "kinds": noise_kinds,
                       "topObjects": result.get("topObjects", {})},
        "real": {"count": real_cnt, "kinds": real_kinds,
                 "samples": result.get("otherSamples", {})},
        "fatalClasses": {},
        "ddlAutoConfig": ddl_auto,
        "verdict": verdict_for(noise_cnt, real_cnt, 0),
        "forCodexRecommendations": recommendations(ddl_auto, noise_cnt,
                                                   real_cnt),
    }


def classify_verify_json(path: Path, root: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    classes = (((data.get("exceptions") or {}).get("classes")) or [])
    noise_kinds, real_kinds = {}, {}
    fatal = {}
    exc_lines = 0
    err_lines = 0
    log_ref = (data.get("exceptions") or {}).get("log")
    for cls in classes:
        name = cls.get("class", "?")
        cnt = int(cls.get("count") or 0)
        if name == "exception-lines":
            exc_lines = cnt
            continue
        if name == "error-level":
            err_lines = cnt
            continue
        if name in FATAL_CLASSES and cnt > 0:
            fatal[name] = cnt
        elif cnt > 0:
            real_kinds[f"verify:{name}"] = cnt
    # verify JSON 은 DDL 문장 본문을 보존하지 않는다 — real/known 분리는
    # 연결된 로그를 다시 스캔해야 정확하다. 여기선 클래스 카운트만 분리하고
    # knownNoise 추정은 로그 재스캔이 있을 때만 채운다.
    rescan = None
    if log_ref:
        log_path = Path(log_ref)
        if not log_path.is_absolute():
            log_path = root / log_ref
        if log_path.is_file():
            rescan = classify_log(log_path, root)
    ddl_auto = find_ddl_auto(root)
    if rescan:
        noise_kinds = rescan["knownNoise"]["kinds"]
        real_kinds.update(rescan["real"]["kinds"])
    noise_cnt = sum(noise_kinds.values())
    real_cnt = sum(real_kinds.values())
    fatal_cnt = sum(fatal.values())
    out = {
        "source": {"kind": "verify-json", "path": str(path),
                   "log": log_ref, "logRescanned": rescan is not None},
        "ddlWarnLines": rescan["ddlWarnLines"] if rescan else None,
        "exceptionLines": exc_lines,
        "errorLevelLines": err_lines,
        "knownNoise": {"count": noise_cnt, "kinds": noise_kinds,
                       "note": "log rescan 없으면 kinds 비어있음"},
        "real": {"count": real_cnt, "kinds": real_kinds,
                 "samples": (rescan or {}).get("real", {}).get("samples", {})},
        "fatalClasses": fatal,
        "ddlAutoConfig": ddl_auto,
        "verdict": verdict_for(noise_cnt, real_cnt, fatal_cnt),
        "forCodexRecommendations": recommendations(ddl_auto, noise_cnt,
                                                   real_cnt + fatal_cnt),
    }
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="ddl-auto DDL 소음 vs real 에러 분리 (read-only).")
    ap.add_argument("log", nargs="?", default=None,
                    help="분석할 로그 파일 경로")
    ap.add_argument("--latest", action="store_true",
                    help="최신 var/rag-launcher/*/chat-ui-vibe-listener-*.out.log")
    ap.add_argument("--verify-json", default=None,
                    help="awx.rag_debug.v1 verify 결과 JSON")
    ap.add_argument("--root", default=".")
    ap.add_argument("--json-out", default=DEFAULT_OUT)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    if args.verify_json:
        src = Path(args.verify_json)
        if not src.is_absolute():
            src = root / src
        if not src.is_file():
            print(f"verify-json 없음: {src}", file=sys.stderr)
            return EXIT_NOT_FOUND
        result = classify_verify_json(src, root)
    else:
        if args.latest:
            path = h2w.latest_launcher_log()
            if path is None:
                print(json.dumps({"status": "no-launcher-log"}))
                return EXIT_NOT_FOUND
        elif args.log:
            path = Path(args.log)
            if not path.is_absolute():
                path = root / path
        else:
            ap.print_help()
            return EXIT_USAGE
        if not path.is_file():
            print(json.dumps({"status": "log-not-found", "path": str(path)}))
            return EXIT_NOT_FOUND
        result = classify_log(path, root)

    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "generatedAtUtc": utcnow(),
        **result,
        "exitCode": EXIT_OK,
        "never": ["ddl-auto config change", "log edit", "restart",
                  "print_secrets"],
    }
    out_path = Path(args.json_out)
    if not out_path.is_absolute():
        out_path = root / out_path
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"ddl_auto_noise verdict={result['verdict']} "
              f"noise={result['knownNoise']['count']} "
              f"real={result['real']['count']} json={out_path}")
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
