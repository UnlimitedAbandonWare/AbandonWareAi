#!/usr/bin/env python3
"""chat_timeout_ollama_load_stats.py — Ollama 로그에서 모델 로드 소요 시간만 추출한다.

읽기 전용. 표준 라이브러리만 사용. 로그 본문·프롬프트·비밀은 출력하지 않고
라벨·시각·숫자·모델명·blob 해시 앞 12자만 출력한다.

인식 형식 (Ollama serve stderr/stdout):
  time=2026-10-02T11:28:56.600+09:00 level=INFO source=sched.go:1147 msg="disabling mmap ..." model=E:\models\blobs\sha256-<hash>
  time=... source=llama_server.go:1046 msg="loading model via llama-server" model=<path>
  time=... source=llama_server.go:1360 msg="llama-server started in 63.46 seconds"
  time=... source=sched.go:641 msg="Load failed" model=<path>
  time=... msg="client connection closed before llama-server finished loading, aborting ..."
  time=... source=images.go:374 msg="template selection" model=registry.ollama.ai/library/<name>
  [GIN] 2026/10/02 - 11:29:06 | 499 | 12.132012s | 127.0.0.1 | POST     "/api/chat"

출력: JSON — 로드 이벤트 목록, 모델별 count/median/max, 요청(GIN) 경로별 지연 통계.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))

RE_LOG = re.compile(
    r'^time=(?P<ts>\d{4}-\d{2}-\d{2}T[\d:.]+(?:[+-]\d{2}:?\d{2}|Z)?)\s+'
    r'level=(?P<level>\w+)\s+source=(?P<src>[\w.]+:\d+)\s+msg="(?P<msg>.*?)"\s*(?P<kv>.*)$'
)
RE_GIN = re.compile(
    r'^\[GIN\]\s+(?P<d>\d{4}/\d{2}/\d{2})\s+-\s+(?P<t>\d{2}:\d{2}:\d{2})\s*\|\s*'
    r'(?P<status>\d{3})\s*\|\s*(?P<dur>[~\d.,a-zµμ]+?)\s*\|\s*\S+\s*\|\s*'
    r'(?P<method>[A-Z]+)\s+"(?P<path>[^"]*)"'
)
RE_STARTED_IN = re.compile(r'(?:llama-server|llama runner|runner)\s+started in\s+([\d.]+)\s*seconds?', re.I)
RE_MODEL_KV = re.compile(r'\bmodel=(?P<path>"[^"]*"|\S+)')
RE_TEMPLATE = re.compile(r'model=registry\.ollama\.ai/library/(?P<name>[\w.\-:]+)')
RE_GIN_DUR = re.compile(r'^(?:(\d+)m)?([\d.]+)(ms|µs|us|s)?$')

LOAD_START_MSGS = (
    "loading model via llama-server",
    "disabling mmap for llama-server load",
    "using llama-server for model",
    "starting llama-server",
)
LOAD_DONE_MARKERS = ("started in",)
LOAD_FAIL_MSGS = ("Load failed",)
LOAD_ABORT_HINT = "client connection closed before llama-server finished loading"


def parse_ts(text: str) -> datetime | None:
    """RFC3339(`time=`) 또는 `YYYY/MM/DD - HH:MM:SS`(GIN, 로컬 KST 가정) → aware datetime."""
    if not text:
        return None
    text = text.strip()
    try:
        if "/" in text:
            naive = datetime.strptime(text, "%Y/%m/%d - %H:%M:%S")
            return naive.replace(tzinfo=KST)
        iso = text.replace("Z", "+00:00")
        dt = datetime.fromisoformat(iso)
        return dt if dt.tzinfo else dt.replace(tzinfo=KST)
    except ValueError:
        return None


def parse_gin_duration_ms(text: str) -> float | None:
    """GIN latency `1m9s` / `12.43s` / `1.5ms` / `620µs` / `0s` → ms."""
    m = RE_GIN_DUR.match(text.strip())
    if not m:
        return None
    minutes = float(m.group(1) or 0)
    value = float(m.group(2))
    unit = m.group(3) or "s"
    total = minutes * 60.0 + value
    if unit in ("ms",):
        return total
    if unit in ("µs", "us"):
        return total / 1000.0
    return total * 1000.0  # seconds


def _clean_model_ref(raw: str | None) -> str | None:
    if not raw:
        return None
    return raw.strip().strip('"')


def blob_label(path: str | None) -> str | None:
    if not path:
        return None
    name = path.replace("\\", "/").rsplit("/", 1)[-1]
    if name.startswith("sha256-"):
        return "sha256-" + name[len("sha256-"):len("sha256-") + 12]
    return name[:24]


def load_manifest_map(manifests_dir: Path | None) -> dict[str, str]:
    """E:\\models\\manifests\\registry.ollama.ai\\library\\<model>\\<tag> JSON에서
    weights blob digest(sha256-…) → `model:tag` 매핑."""
    out: dict[str, str] = {}
    if not manifests_dir or not manifests_dir.is_dir():
        return out
    for path in manifests_dir.rglob("*"):
        if not path.is_file() or path.suffix:
            continue
        try:
            doc = json.loads(path.read_text(encoding="utf-8", errors="replace"))
        except Exception:
            continue
        layers = doc.get("layers") if isinstance(doc, dict) else None
        if not isinstance(layers, list):
            continue
        name = f"{path.parent.name}:{path.name}"
        for layer in layers:
            if not isinstance(layer, dict):
                continue
            digest = str(layer.get("digest") or "")
            media = str(layer.get("mediaType") or "")
            if digest.startswith("sha256:") and ("model" in media or media == ""):
                out.setdefault("sha256-" + digest[7:19], name)
    return out


def extract(path: Path, since: datetime | None, until: datetime | None) -> dict:
    """한 파일에서 로드 이벤트와 GIN 요청을 추출한다."""
    loads: list[dict] = []
    requests: list[dict] = []
    open_loads: dict[str, dict] = {}  # blob_label -> open load event
    last_closed: dict | None = None

    def in_window(ts: datetime | None) -> bool:
        if ts is None:
            return True
        if since and ts < since:
            return False
        if until and ts > until:
            return False
        return True

    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return {"file": str(path), "error": "unreadable", "loads": [], "requests": []}

    for line in lines:
        gin = RE_GIN.match(line)
        if gin:
            ts = parse_ts(f"{gin.group('d')} - {gin.group('t')}")
            if not in_window(ts):
                continue
            requests.append({
                "ts": ts.isoformat() if ts else None,
                "status": int(gin.group("status")),
                "duration_s": round((parse_gin_duration_ms(gin.group("dur")) or 0.0) / 1000.0, 3),
                "method": gin.group("method"),
                "path": gin.group("path"),
                "source": "gin",
            })
            continue
        m = RE_LOG.match(line)
        if not m:
            continue
        ts = parse_ts(m.group("ts"))
        msg = m.group("msg")
        kv = m.group("kv") or ""
        model_m = RE_MODEL_KV.search(msg) or RE_MODEL_KV.search(kv)
        model_ref = _clean_model_ref(model_m.group("path")) if model_m else None
        blob = blob_label(model_ref)

        if any(k in msg for k in LOAD_START_MSGS) and blob:
            if in_window(ts):
                ev = open_loads.get(blob)
                if not ev:
                    open_loads[blob] = {
                        "start": ts.isoformat() if ts else None,
                        "model_blob": blob,
                        "model": None,
                        "status": "loading",
                        "file": path.name,
                    }
            continue
        if RE_STARTED_IN.search(msg):
            if not in_window(ts):
                continue
            dur = float(RE_STARTED_IN.search(msg).group(1))
            ev = None
            if open_loads:
                # 가장 최근에 연 로드를 닫는다(동일 blob 우선)
                ev = open_loads.pop(next(reversed(open_loads)))
            if ev is None:
                ev = {"start": None, "model_blob": None, "file": path.name}
            ev.update({"end": ts.isoformat() if ts else None,
                       "duration_s": dur, "status": "ok"})
            last_closed = ev
            loads.append(ev)
            continue
        if LOAD_ABORT_HINT in msg:
            for ev in open_loads.values():
                ev["status"] = "aborting"
            continue
        if any(k in msg for k in LOAD_FAIL_MSGS):
            if not in_window(ts):
                continue
            ev = None
            if blob and blob in open_loads:
                ev = open_loads.pop(blob)
            elif open_loads:
                ev = open_loads.pop(next(reversed(open_loads)))
            if ev is not None:
                start_ts = parse_ts(ev.get("start") or "")
                dur = round((ts - start_ts).total_seconds(), 3) if (ts and start_ts) else None
                ev.update({"end": ts.isoformat() if ts else None,
                           "duration_s": dur,
                           "status": "failed" if ev.get("status") != "aborting" else "aborted_by_client"})
                last_closed = ev
                loads.append(ev)
            continue
        tpl = RE_TEMPLATE.search(line)
        if tpl and last_closed is not None and not last_closed.get("model"):
            last_closed["model"] = tpl.group("name")

    # 윈도우 밖 또는 미종결 로드는 버린다
    for ev in open_loads.values():
        if ev.get("status") in ("loading",) :
            continue
        loads.append(ev)
    return {"file": str(path), "loads": loads, "requests": requests}


def median(values: list[float]) -> float | None:
    if not values:
        return None
    s = sorted(values)
    n = len(s)
    mid = n // 2
    return s[mid] if n % 2 else round((s[mid - 1] + s[mid]) / 2.0, 3)


def summarize(loads: list[dict], requests: list[dict]) -> dict:
    per_model: dict[str, dict] = {}
    for ev in loads:
        key = ev.get("model") or ev.get("model_blob") or "unknown"
        if ev.get("status") != "ok":
            key_stats = per_model.setdefault(key, {"count": 0, "median_s": None,
                                                   "max_s": None, "min_s": None, "failed": 0})
            key_stats["failed"] += 1
            continue
        st = per_model.setdefault(key, {"count": 0, "median_s": None,
                                        "max_s": None, "min_s": None, "failed": 0})
        st.setdefault("_durations", []).append(ev["duration_s"])
    for key, st in per_model.items():
        durs = st.pop("_durations", [])
        st["count"] = len(durs)
        st["median_s"] = median(durs)
        st["max_s"] = max(durs) if durs else None
        st["min_s"] = min(durs) if durs else None

    per_request: dict[str, dict] = {}
    for req in requests:
        key = f'{req["method"]} {req["path"]}'
        st = per_request.setdefault(key, {"count": 0, "median_s": None,
                                          "max_s": None, "statuses": {}})
        st.setdefault("_durations", []).append(req["duration_s"])
        st["statuses"][str(req["status"])] = st["statuses"].get(str(req["status"]), 0) + 1
    for st in per_request.values():
        durs = st.pop("_durations", [])
        st["count"] = len(durs)
        st["median_s"] = median(durs)
        st["max_s"] = max(durs) if durs else None
    return {"per_model": per_model, "per_request": per_request}


def parse_bound(text: str | None) -> datetime | None:
    if not text:
        return None
    ts = parse_ts(text)
    return ts


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Ollama 로그에서 모델 로드 소요시간 추출")
    ap.add_argument("--log", action="append", required=True,
                    help="로그 파일 경로(반복 가능)")
    ap.add_argument("--since", help="ISO 시각(예: 2026-10-02T11:00:00+09:00; naive는 KST)")
    ap.add_argument("--until", help="ISO 시각")
    ap.add_argument("--manifests-dir",
                    help="ollama manifests 루트(예: E:/models/manifests/registry.ollama.ai/library) — blob→모델명 해석")
    ap.add_argument("--out", help="결과 JSON 경로(없으면 stdout)")
    args = ap.parse_args(argv)

    since = parse_bound(args.since)
    until = parse_bound(args.until)
    blob_names = load_manifest_map(Path(args.manifests_dir)) if args.manifests_dir else {}

    all_loads: list[dict] = []
    all_requests: list[dict] = []
    files = []
    for raw in args.log:
        part = extract(Path(raw), since, until)
        files.append({"file": part["file"], "loads": len(part["loads"]),
                      "requests": len(part["requests"]),
                      **({"error": part["error"]} if "error" in part else {})})
        all_loads.extend(part["loads"])
        all_requests.extend(part["requests"])

    for ev in all_loads:
        if not ev.get("model") and ev.get("model_blob") in blob_names:
            ev["model"] = blob_names[ev["model_blob"]]

    summary = summarize(all_loads, all_requests)
    doc = {
        "schemaVersion": "devin.chat-timeout.ollama-load-stats.v1",
        "window": {"since": since.isoformat() if since else None,
                   "until": until.isoformat() if until else None},
        "files": files,
        "loadEvents": all_loads,
        "requestStats": summary["per_request"],
        "perModel": summary["per_model"],
        "loadCount": len(all_loads),
        "requestCount": len(all_requests),
    }
    text = json.dumps(doc, ensure_ascii=False, indent=1)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        print(f"out={args.out} loads={len(all_loads)} requests={len(all_requests)}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
