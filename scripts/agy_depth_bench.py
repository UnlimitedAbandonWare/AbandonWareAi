#!/usr/bin/env python3
"""agy depth bench: measure whether the depth router profile actually does
more work AND scores better.

Arms
  lite  = current behavior flags: --effort high, saved model selection.
  depth = depth profile flags:    --model <agy_model_latest -Pick> --effort
          <FlashMaxEffort> + a router-instruction preamble in the prompt.

Each arm runs every task --reps times (default 2) => 3 tasks * 2 arms * 2
reps = 12 live agy calls. Answer keys are extracted from the live files at
run time; graders score the model's trailing JSON line. Before/after
`git status --short` hashes are compared; a drift is reported as HOLD.

Usage: python -B scripts\agy_depth_bench.py [--reps 2] [--tasks E,M,H]
       [--arm both|lite|depth] [--dry-run] [--timeout 600]
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "var" / "agy-depth"
KST = timezone(timedelta(hours=9))
GIT = os.environ.get("AWX_GIT", r"F:\git\cmd\git.exe")


def agy_exe() -> str:
    if os.environ.get("AGY_EXE"):
        return os.environ["AGY_EXE"]
    return str(Path(os.path.expandvars(r"%LOCALAPPDATA%")) / "agy" / "bin" / "agy.exe")


def pick_model() -> str:
    r = subprocess.run(
        ["powershell", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
         "-File", str(ROOT / "scripts" / "agy_model_latest.ps1"), "-Pick"],
        capture_output=True, text=True, timeout=15, cwd=ROOT)
    picked = r.stdout.strip()
    if not picked:
        raise RuntimeError("agy_model_latest.ps1 -Pick returned empty")
    return picked


def flash_effort() -> str:
    r = subprocess.run(
        ["powershell", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
         "-File", str(ROOT / "scripts" / "agy_model_latest.ps1"), "-FlashMaxEffort"],
        capture_output=True, text=True, timeout=15, cwd=ROOT)
    return r.stdout.strip() or "high"


def git_status_sha() -> str:
    try:
        env = dict(os.environ, GIT_OPTIONAL_LOCKS="0")
        r = subprocess.run([GIT, "status", "--short"], cwd=ROOT,
                           capture_output=True, text=True, timeout=20, env=env)
        return hashlib.sha256(r.stdout.encode("utf-8")).hexdigest()
    except Exception:
        return "error"


# ---------- ground-truth extraction ----------

def read_lines(rel: str) -> list:
    return (ROOT / rel).read_text(encoding="utf-8", errors="replace").splitlines()


def key_e() -> dict:
    """effort default lines in Start-Agy-CLI.bat (may occur more than once)."""
    lines = read_lines("Start-Agy-CLI.bat")
    nums = [i + 1 for i, ln in enumerate(lines)
            if 'if not defined AWX_AGY_EFFORT set "AWX_AGY_EFFORT=' in ln]
    m = re.search(r'AWX_AGY_EFFORT=(\w+)"', lines[nums[0] - 1]) if nums else None
    return {"lines": nums, "value": m.group(1) if m else "high"}


def key_m() -> dict:
    """name + start line of the same-tier picker fn and its two compare keys."""
    lines = read_lines("scripts/agy_model_latest.ps1")
    fname, fline = None, None
    for i, ln in enumerate(lines):
        if ln.startswith("function ") and "BestSameTier" in ln:
            fname = ln.split()[1].strip(); fline = i + 1; break
    criteria = []
    for ln in lines:
        criteria += re.findall(r"-ne \$Cur\.(\w+)", ln)
    return {"func": fname or "Get-BestSameTier", "line": fline or 0,
            "criteria": sorted(set(criteria)) or ["family", "tier"]}


def key_h() -> dict:
    """first 5 section titles after byte 24000 of AGENTS.md + PROTO doc path."""
    data = (ROOT / "AGENTS.md").read_bytes()
    tail = data[24000:].decode("utf-8", errors="replace")
    titles = []
    base_lines = data[:24000].count(b"\n")
    for off, ln in enumerate(tail.splitlines()):
        m = re.match(r"^#{2,}\s+(.+?)\s*$", ln)
        if m and len(titles) < 5:
            titles.append({"title": m.group(1)[:60],
                           "line": base_lines + off + 1})
    proto = ""
    chunks = re.split(r"(?m)^(?=#{2,} )", tail)
    for ch in chunks:
        if "PROTO_OPEN" in ch.upper()[:200]:
            dm = re.search(r"docs/[\w\-./]+\.md", ch)
            if dm:
                proto = dm.group(0)
                break
    if not proto:
        dm = re.search(r"docs/agents-rules/DEMO1-PROTOTYPE-AUTH[A-Z\-]*\.md", tail)
        proto = dm.group(0) if dm else ""
    return {"titles": titles, "protoDoc": proto}


# ---------- prompts ----------

def prompt_for(tid: str, arm: str) -> str:
    q = {
        "E": ("Start-Agy-CLI.bat에서 reasoning effort 기본값을 정하는 줄의 "
              "줄 번호와 값을 알려줘. 파일 수정 금지. 마지막 줄에 JSON 한 줄: "
              '{"line": <번호>, "value": "<값>"}'),
        "M": ("scripts\\agy_model_latest.ps1에서 '최신 같은 티어 모델'을 고르는 "
              "함수의 이름과 시작 줄 번호, 그리고 그 함수가 비교하는 두 기준을 "
              "알려줘. 파일 수정 금지. 마지막 줄에 JSON 한 줄: "
              '{"func": "<이름>", "line": <번호>, "criteria": ["<기준1>", "<기준2>"]}'),
        "H": ("AGENTS.md를 바이트 기준 24000 이후만 볼 때(앞부분은 agy가 로드하니 "
              "제외), 그 뒤에 오는 ## 절 제목 5개와 각 시작 줄 번호, 그리고 그중 "
              "PROTO_OPEN 관련 절이 가리키는 상세 문서 경로(docs/...)를 알려줘. "
              "파일 수정 금지. 마지막 줄에 JSON 한 줄: "
              '{"titles": ["제목1","제목2","제목3","제목4","제목5"], '
              '"protoDoc": "<docs/... 경로>"}'),
    }[tid]
    if arm == "lite":
        return q
    return ("[지시] demo1-agy-depth-router 규칙으로 이 과제의 L1~L3 레벨을 먼저 "
            "판정하고 첫 줄에 [depth Lx: 이유]를 쓴 뒤 그 레벨의 절차대로 작업해. "
            "configs\\agy-depth.json 기준 적용. var\\agy-seed\\latest.md가 있으면 "
            "관련 부분만 참고하고, 인용하는 file:line 주장은 Select-String으로 "
            "재확인해. 초안이 끝나면 "
            ".agents\\skills\\demo1-agy-depth-router\\references\\depth-checklist.md "
            "기준으로 자기 검토 1회. L3라면 fact-verifier/red-team-reviewer "
            "서브에이전트를 invoke_subagent로 불러도 된다.\n\n" + q)


# ---------- grading ----------

def last_json(response: str):
    for ln in reversed(response.splitlines()):
        ln = ln.strip()
        if ln.startswith("{") and ln.endswith("}"):
            try:
                return json.loads(ln)
            except Exception:
                continue
    return None


def grade(tid: str, resp_json) -> float:
    key = KEYS[tid]
    if not isinstance(resp_json, dict):
        return 0.0
    if tid == "E":
        s = 0.0
        if resp_json.get("value") == key["value"]:
            s += 0.5
        try:
            if int(resp_json.get("line", -1)) in key["lines"]:
                s += 0.5
        except Exception:
            pass
        return s
    if tid == "M":
        s = 0.0
        if str(resp_json.get("func", "")).strip() == key["func"]:
            s += 0.5
        got = {str(c).lower() for c in (resp_json.get("criteria") or [])}
        for c in key["criteria"]:
            if c.lower() in got:
                s += 0.25
        return s
    if tid == "H":
        exp_titles = [t["title"] for t in key["titles"]]
        got = [str(t) for t in (resp_json.get("titles") or [])]
        hit = sum(1 for e in exp_titles
                  if any(e[:25].lower() in g.lower() or g[:25].lower() in e.lower()
                         for g in got))
        s = 0.6 * hit / max(1, len(exp_titles))
        proto = str(resp_json.get("protoDoc", "")).replace("\\", "/")
        if key["protoDoc"] and key["protoDoc"].lower() in proto.lower():
            s += 0.4
        return s
    return 0.0


# ---------- runner ----------

def run_call(prompt: str, arm: str, timeout: int) -> dict:
    cmd = [agy_exe(), "--output-format", "json", "--effort", flash_effort()]
    if arm == "depth":
        cmd += ["--model", pick_model()]
    cmd += ["--print", prompt]
    t0 = time.time()
    try:
        r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True,
                           timeout=timeout, encoding="utf-8", errors="replace")
        elapsed = round(time.time() - t0, 1)
        obj = None
        for ln in r.stdout.splitlines():
            ln = ln.strip()
            if ln.startswith("{"):
                try:
                    obj = json.loads(ln)
                except Exception:
                    continue
        usage = (obj or {}).get("usage") or {}
        return {"exit": r.returncode, "seconds": elapsed, "obj": obj,
                "response": (obj or {}).get("response", ""),
                "status": (obj or {}).get("status", "NO_JSON"),
                "tokens": usage.get("total_tokens", 0),
                "stderr_tail": (r.stderr or "")[-300:]}
    except subprocess.TimeoutExpired:
        return {"exit": -1, "seconds": timeout, "obj": None, "response": "",
                "status": "TIMEOUT", "tokens": 0, "stderr_tail": ""}


TASKS = ["E", "M", "H"]
KEYS = {}


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--reps", type=int, default=2)
    ap.add_argument("--tasks", default="E,M,H")
    ap.add_argument("--arm", default="both")
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    tasks = [t for t in args.tasks.split(",") if t in TASKS]
    KEYS.update({t: {"E": key_e, "M": key_m, "H": key_h}[t]() for t in tasks})

    if args.dry_run:
        print(json.dumps({"keys": KEYS, "note": "dry run - no agy calls"},
                         ensure_ascii=False, indent=2))
        return 0

    arms = ["lite", "depth"] if args.arm == "both" else [args.arm]
    before = git_status_sha()
    started = datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S KST")
    runs = []
    for tid in tasks:
        for arm in arms:
            for rep in range(args.reps):
                res = run_call(prompt_for(tid, arm), arm, args.timeout)
                res.update({"task": tid, "arm": arm, "rep": rep,
                            "score": grade(tid, last_json(res["response"]))})
                runs.append(res)
                print(f"[bench] {tid}/{arm}/r{rep} exit={res['exit']} "
                      f"tokens={res['tokens']} score={res['score']:.2f} "
                      f"s={res['seconds']} status={res['status']}",
                      flush=True)
    after = git_status_sha()

    summary = {}
    for arm in arms:
        sub = [r for r in runs if r["arm"] == arm]
        summary[arm] = {
            "calls": len(sub),
            "tokens": sum(r["tokens"] for r in sub),
            "score": round(sum(r["score"] for r in sub), 2),
            "seconds": round(sum(r["seconds"] for r in sub), 1),
            "per_task": {t: {"tokens": sum(r["tokens"] for r in sub if r["task"] == t),
                             "score": round(sum(r["score"] for r in sub if r["task"] == t), 2)}
                         for t in tasks},
        }
    verdict = {"gitStatusBefore": before, "gitStatusAfter": after,
               "drift": before != after}
    if arms == ["lite", "depth"] and summary["lite"]["tokens"]:
        verdict["tokenRatio"] = round(
            summary["depth"]["tokens"] / summary["lite"]["tokens"], 2)
        verdict["eTokenRatio"] = round(
            summary["depth"]["per_task"]["E"]["tokens"] /
            max(1, summary["lite"]["per_task"]["E"]["tokens"]), 2)
        verdict["scoreDelta"] = round(
            summary["depth"]["score"] - summary["lite"]["score"], 2)

    out = {"schema": "awx.agy-depth-bench.v1", "startedKst": started,
           "tasks": tasks, "reps": args.reps, "keys": KEYS,
           "summary": summary, "verdict": verdict,
           "runs": [{k: v for k, v in r.items() if k != "obj"} for r in runs]}
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUT_DIR / ("bench-" + datetime.now(KST).strftime("%Y%m%d-%H%M%S") + ".json")
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"out": str(path), "summary": summary,
                      "verdict": verdict}, ensure_ascii=False, indent=2))
    return 1 if verdict["drift"] else 0


if __name__ == "__main__":
    sys.exit(main())
