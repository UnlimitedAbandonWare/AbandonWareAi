"""RTX 3090 사고 플래그 (awx.gpu_incident.v1) — DESKTOP-M5NOV6K.

probe  : nvidia-smi 조회 + 최근 30분 System 이벤트 근거 첨부 후
         var/incident/gpu.json을 원자적으로 쓴다. exit 0=OK, 3=사고.
status : 플래그만 읽어 한 줄 출력. exit 0=OK, 3=LOST/DEGRADED, 4=파일 없음.
clear  : 새 probe가 OK일 때만 state=OK로 갱신(수동 강제 clear 없음 —
         거짓 복구 방지). exit 0=해제됨, 3=여전히 사고.

읽기 전용: 전력 제한/클럭/드라이버/재부팅 조치를 하지 않는다. 비밀값 출력 0.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCHEMA = "awx.gpu_incident.v1"
KST = timezone(timedelta(hours=9))
FLAG_REL = Path("var") / "incident" / "gpu.json"
SMI_TIMEOUT_SEC = 10
EVENT_LOOKBACK_MIN = 30
EVENT_TIMEOUT_SEC = 25

# DESKTOP-M5NOV6K 고정 배선: 3090 = 0A:00.0, 3060 = 05:00.0
TARGET_RE = re.compile(r"3090|0a:00\.0", re.I)
PEER_RE = re.compile(r"3060|05:00\.0", re.I)
SKIP_LANES_ON_INCIDENT = ["ollama:11434"]

# nvidia-smi 출력의 GPU 소실 신호 (watch 스크립트와 동일 계열)
LOST_RE = re.compile(
    r"gpu is lost|unable to determine the device handle|"
    r"has fallen off|\bxid\b|err!", re.I)

_STATES_INCIDENT = ("GPU3090_LOST", "GPU3090_DEGRADED")

_SMI_FIELD_SETS = (
    ("index", "name", "pci.bus_id", "power.draw", "clocks_event_reasons.active"),
    ("index", "name", "pci.bus_id", "power.draw", "clocks_throttle_reasons.active"),
    ("index", "name", "pci.bus_id", "power.draw"),
)


def _now_kst():
    return datetime.now(KST).isoformat(timespec="seconds")


def _cut(text, n=160):
    text = re.sub(r"\s+", " ", (text or "")).strip()
    return text[:n]


def _run_smi(fields, timeout=SMI_TIMEOUT_SEC):
    """nvidia-smi 한 번 실행. 반환 {ok,text,timed_out,exit}"""
    cmd = ["nvidia-smi", "--query-gpu=" + ",".join(fields),
           "--format=csv,noheader"]
    try:
        cp = subprocess.run(cmd, capture_output=True, text=True,
                            encoding="utf-8", errors="replace",
                            timeout=timeout)
        return {"ok": cp.returncode == 0,
                "text": (cp.stdout or "") + "\n" + (cp.stderr or ""),
                "timed_out": False, "exit": cp.returncode}
    except subprocess.TimeoutExpired:
        return {"ok": False, "text": "smi_timeout", "timed_out": True,
                "exit": None}
    except OSError as exc:
        return {"ok": False, "text": _cut(str(exc)), "timed_out": False,
                "exit": None}


def _parse_rows(text):
    """smi csv에서 숫자 index로 시작하는 GPU 행만 파싱 (stderr 잡음 배제)."""
    rows = []
    for line in (text or "").splitlines():
        line = line.strip()
        if not re.match(r"^\d+\s*,", line):
            continue
        parts = [p.strip() for p in line.split(",")]
        row = {"index": parts[0]}
        if len(parts) > 1:
            row["name"] = parts[1]
        if len(parts) > 2:
            row["bus_id"] = parts[2]
        if len(parts) > 3:
            row["power_draw"] = parts[3]
        if len(parts) > 4:
            row["throttle"] = parts[4]
        rows.append(row)
    return rows


def _smi_probe(timeout=SMI_TIMEOUT_SEC):
    """필드 셋을 순서대로 시도. 실패하면 다음 별칭으로 폴백."""
    last = {"ok": False, "text": "", "timed_out": False, "exit": None}
    for fields in _SMI_FIELD_SETS:
        last = _run_smi(fields, timeout)
        if last["timed_out"]:
            break
        if last["ok"] or LOST_RE.search(last["text"]):
            last["fields"] = list(fields)
            last["rows"] = _parse_rows(last["text"])
            return last
    last["rows"] = _parse_rows(last.get("text"))
    return last


def _collect_events(minutes=EVENT_LOOKBACK_MIN, timeout=EVENT_TIMEOUT_SEC):
    """최근 N분 System 로그의 nvlddmkm/Display 4101/Kernel-Power 41 (최선노력)."""
    ps = (
        "[Console]::OutputEncoding=[Text.UTF8Encoding]::new();"
        f"$since=(Get-Date).AddMinutes(-{int(minutes)});"
        "Get-WinEvent -FilterHashtable @{LogName='System';StartTime=$since} "
        "-MaxEvents 500 -ErrorAction SilentlyContinue | "
        "Where-Object { ($_.ProviderName -eq 'nvlddmkm') -or "
        "($_.ProviderName -eq 'Display' -and $_.Id -eq 4101) -or "
        "($_.ProviderName -eq 'Microsoft-Windows-Kernel-Power' "
        "-and $_.Id -eq 41) } | Sort-Object TimeCreated | "
        "Select-Object -Last 6 ProviderName,Id,"
        "@{n='Time';e={$_.TimeCreated.ToString('o')}},"
        "@{n='Message';e={($_.Message -replace '\\s+',' ').Trim()}} | "
        "ConvertTo-Json -Depth 3 -Compress"
    )
    try:
        cp = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps],
            capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout)
    except (subprocess.TimeoutExpired, OSError):
        return []
    if cp.returncode != 0 or not (cp.stdout or "").strip():
        return []
    try:
        data = json.loads(cp.stdout)
    except ValueError:
        return []
    if isinstance(data, dict):
        data = [data]
    out = []
    for e in data or []:
        out.append({"provider": e.get("ProviderName"),
                    "id": e.get("Id"),
                    "time": e.get("Time"),
                    "message": _cut(e.get("Message"), 140)})
    return out


def _assess(smi, events):
    """smi 결과 + 이벤트 목록 → (state, evidence, hypothesis)."""
    evidence = []
    if smi.get("timed_out"):
        return ("GPU3090_DEGRADED", ["smi_timeout"], "unknown")
    if not smi.get("ok") and not LOST_RE.search(smi.get("text") or ""):
        evidence.append("smi_exit_nonzero:" + _cut(smi.get("text"), 120))
        return ("GPU3090_DEGRADED", evidence, "unknown")
    rows = smi.get("rows") or []
    m = LOST_RE.search(smi.get("text") or "")
    if m:
        evidence.append("smi_lost_text:" + _cut(m.group(0), 120))
    tgt = [r for r in rows
           if TARGET_RE.search((r.get("name") or "") + " " + (r.get("bus_id") or ""))]
    if rows and not tgt:
        evidence.append("target_row_missing:3090")
    for r in rows:
        tag = "target3090" if r in tgt else "peer3060"
        if not r in tgt and not PEER_RE.search(
                (r.get("name") or "") + " " + (r.get("bus_id") or "")):
            tag = "other_gpu"
        evidence.append("gpu_row:" + tag + ":" + _cut(
            ",".join(str(r.get(k, "")) for k in ("index", "name", "bus_id",
                                                "power_draw")), 120))
    for e in events or []:
        evidence.append("event:%s/%s@%s" % (e.get("provider"), e.get("id"),
                                            _cut(e.get("time"), 30)))
        if e.get("message"):
            evidence.append("event_msg:" + _cut(e.get("message"), 140))
    if m or (rows and not tgt):
        return ("GPU3090_LOST", evidence, "psu_or_wiring")
    if not rows:
        evidence.append("smi_no_gpu_rows")
        return ("GPU3090_DEGRADED", evidence, "unknown")
    return ("OK", evidence, "unknown")


def _flag_path(root):
    return Path(root) / FLAG_REL


def _read_flag(root):
    try:
        return json.loads(_flag_path(root).read_text(encoding="utf-8"))
    except Exception:
        return None


def _write_flag(root, state, evidence, hypothesis, gpus, peer_ok, events,
                now=None):
    """원자적 쓰기(임시파일→rename). 같은 state면 기존 since_kst 유지."""
    now = now or _now_kst()
    prev = _read_flag(root)
    if prev and prev.get("state") == state and prev.get("since_kst"):
        since = prev["since_kst"]
    else:
        since = now
    flag = {
        "schema": SCHEMA,
        "state": state,
        "since_kst": since,
        "probed_at_kst": now,
        "evidence": evidence,
        "reboot_required": state == "GPU3090_LOST",
        "skip_lanes": list(SKIP_LANES_ON_INCIDENT)
        if state in _STATES_INCIDENT else [],
        "hypothesis": hypothesis,
        "confidence": "low",
        "gpus": gpus,
        "peer3060_ok": bool(peer_ok),
        "events": events or [],
    }
    path = _flag_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex[:8])
    tmp.write_text(json.dumps(flag, ensure_ascii=False, indent=1) + "\n",
                   encoding="utf-8")
    os.replace(tmp, path)
    return flag


def probe(root=".", smi=None, events=None, now=None):
    """probe 실행 → 플래그 기록. smi/events는 테스트 주입용."""
    smi = _smi_probe() if smi is None else smi
    if events is None:
        events = _collect_events()
    state, evidence, hypothesis = _assess(smi, events)
    rows = smi.get("rows") or []
    peer_ok = any(PEER_RE.search(
        (r.get("name") or "") + " " + (r.get("bus_id") or "")) for r in rows)
    flag = _write_flag(root, state, evidence, hypothesis, rows, peer_ok,
                       events, now=now)
    return flag, (0 if state == "OK" else 3)


def status(root="."):
    flag = _read_flag(root)
    if flag is None:
        return None, 4
    state = flag.get("state") or "unknown"
    return flag, (3 if state in _STATES_INCIDENT else 0)


def clear(root=".", smi=None, events=None, now=None):
    """probe가 OK일 때만 해제 — 거짓 복구 방지."""
    flag, code = probe(root, smi=smi, events=events, now=now)
    if flag["state"] == "OK":
        return flag, 0
    return flag, 3


def _line(flag):
    return ("gpu-incident state=%s since=%s skip=%s hypothesis=%s"
            % (flag.get("state"), flag.get("since_kst"),
               ",".join(flag.get("skip_lanes") or []) or "-",
               flag.get("hypothesis")))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("probe", help="smi+이벤트 조회 후 플래그 기록")
    sub.add_parser("status", help="플래그 읽기만")
    sub.add_parser("clear", help="probe OK일 때만 해제")
    args = ap.parse_args(argv)

    if args.cmd == "status":
        flag, code = status(args.root)
        if flag is None:
            print(json.dumps({"schema": SCHEMA, "ok": False,
                              "reason": "flag-missing",
                              "path": str(FLAG_REL)}))
            return code
        print(_line(flag))
        return code
    flag, code = (probe(args.root) if args.cmd == "probe"
                  else clear(args.root))
    print(json.dumps(flag, ensure_ascii=False))
    return code


if __name__ == "__main__":
    sys.exit(main())
