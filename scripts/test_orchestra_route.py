"""test_orchestra_route.py — lane boundary, roundtrip cap, error-code guard.

Run: python -B scripts/test_orchestra_route.py   (exit 0 = all pass)
--no-classifiers keeps it offline; boundary inputs are crafted fixtures.
"""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
SIG = ROOT / "scripts" / "orchestra_signal.py"
ROUTE = ROOT / "scripts" / "orchestra_route.py"
RULES = ROOT / "scripts" / "fixtures" / "orchestra" / "route-rules.json"

STORE = None
FAILS = []


def check(name, cond, detail=""):
    if cond:
        print(f"  PASS {name}")
    else:
        FAILS.append(name)
        print(f"  FAIL {name} {detail}")


def sig_new(**kw):
    args = [sys.executable, "-B", str(SIG), "--store", str(STORE), "new"]
    for k, v in kw.items():
        if v is None:
            continue
        key = "--" + k.replace("_", "-")
        if isinstance(v, list):
            args += [key] + v
        else:
            args += [key, str(v)]
    proc = subprocess.run(args, cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    return json.loads(proc.stdout)["signal"]


def route(sid, extra=None):
    args = [sys.executable, "-B", str(ROUTE), "--store", str(STORE),
            "--rules", str(RULES), "--signal", sid, "--no-classifiers"]
    args += extra or []
    proc = subprocess.run(args, cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    return json.loads(proc.stdout)


def link(parent, child):
    proc = subprocess.run(
        [sys.executable, "-B", str(SIG), "--store", str(STORE), "link",
         "--parent", parent, "--child", child],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    assert proc.returncode == 0, proc.stdout + proc.stderr


def main():
    global STORE
    STORE = Path(tempfile.mkdtemp(prefix="orch-route-test-"))
    try:
        # DEVIN: tooling scope only
        s = sig_new(**{"from": "grokbot", "kind": "devin-signal",
                       "summary": "make a board tool",
                       "files": ["scripts/orchestra_board.py"]})
        r = route(s["id"])
        check("tooling->DEVIN", r["lane"] == "DEVIN", r["lane"])

        # CODEX_DIRECT: one product file, no high-bar markers
        s = sig_new(**{"from": "grokbot", "kind": "codex-brief",
                       "summary": "fix typo in receiver label",
                       "files": ["main/java/com/example/lms/web/X.java"]})
        r = route(s["id"])
        check("narrow product->CODEX_DIRECT", r["lane"] == "CODEX_DIRECT", r["lane"])

        # GPTPRO_THEN_CODEX: 3 product files (broad-surface) + external-info marker
        s = sig_new(**{"from": "gptpro", "kind": "gptpro-brief",
                       "summary": "settings defaults 정리 — 최신 문서 기준 버전 확인 포함",
                       "files": ["main/java/a/A.java", "main/java/b/B.java",
                                 "main/resources/application.properties"]})
        r = route(s["id"])
        check("highbar->GPTPRO_THEN_CODEX", r["lane"] == "GPTPRO_THEN_CODEX", r["lane"])
        check("highbar hits>=2", len(r["highBar"]) >= 2, r["highBar"])
        check("gptpro-brief nextAgent=codex", r["nextAgent"] == "codex", r["nextAgent"])

        # research-question -> AGY_RESEARCH
        s = sig_new(**{"from": "grokbot", "kind": "research-question",
                       "summary": "which embedding model is cheapest now"})
        check("research->AGY_RESEARCH", route(s["id"])["lane"] == "AGY_RESEARCH")

        # vague idea -> GROKBOT_AMPLIFY; --grokbot-absent -> AGY_AS_GROKBOT
        s = sig_new(**{"from": "user", "kind": "idea", "summary": "아이디어: 해볼까"})
        check("idea->GROKBOT_AMPLIFY", route(s["id"])["lane"] == "GROKBOT_AMPLIFY")
        check("absent->AGY_AS_GROKBOT",
              route(s["id"], ["--grokbot-absent"])["lane"] == "AGY_AS_GROKBOT")

        # Roundtrip >2 -> ASK_USER (user->grokbot->devin->codex = 3 hops)
        a = sig_new(**{"from": "user", "kind": "idea", "summary": "rt stem seed",
                       "files": ["scripts/rt.py"]})
        b = sig_new(**{"from": "grokbot", "kind": "amplified",
                       "summary": "rt hop1", "files": ["scripts/rt.py"]})
        link(a["id"], b["id"])
        c = sig_new(**{"from": "devin", "kind": "devin-signal",
                       "summary": "rt hop2", "files": ["scripts/rt.py"]})
        link(b["id"], c["id"])
        d = sig_new(**{"from": "codex", "kind": "patch-report",
                       "summary": "rt hop3 report", "files": ["scripts/rt.py"]})
        link(c["id"], d["id"])
        r = route(d["id"])
        check("roundtrip>2->ASK_USER", r["lane"] == "ASK_USER", r["lane"])
        check("roundtrips counted", r["roundtrips"] == 3, r["roundtrips"])

        # 401/403/429 -> question signal, no retry
        for code, lane in (("401", "ASK_USER"), ("403", "ASK_USER"), ("429", "DEVIN")):
            s = sig_new(**{"from": "codex", "kind": "patch-report",
                           "summary": f"provider call returned HTTP {code} on sync"})
            r = route(s["id"])
            check(f"{code}->{lane}", r["lane"] == lane, r["lane"])
            check(f"{code} derived question",
                  r["derivedSignal"] and r["derivedSignal"]["kind"] == "question")
            check(f"{code} no retry",
                  r["detectedError"]["retrySignalCreated"] is False)

        # irreversible -> ASK_USER
        s = sig_new(**{"from": "devin", "kind": "devin-signal",
                       "summary": "데이터셋 삭제 후 재색인"})
        check("irreversible->ASK_USER", route(s["id"])["lane"] == "ASK_USER")

        # overlap warning: live signal sharing files
        s1 = sig_new(**{"from": "devin", "kind": "devin-signal",
                        "summary": "overlap a", "files": ["scripts/shared.py"]})
        run_args = [sys.executable, "-B", str(SIG), "--store", str(STORE),
                    "move", "--id", s1["id"], "--to", "outbox/devin",
                    "--status", "in-progress"]
        subprocess.run(run_args, cwd=ROOT, capture_output=True)
        s2 = sig_new(**{"from": "codex", "kind": "codex-brief",
                        "summary": "overlap b", "files": ["scripts/shared.py"]})
        r = route(s2["id"])
        check("signal overlap warned",
              any(w["type"] == "signal-overlap" for w in r["overlapWarnings"]),
              r["overlapWarnings"])

        # evidence fields present when classifiers run (boundary: keep off here)
        r = route(s2["id"], ["--apply"])
        check("apply writes lane", r["applied"] is True and r["lane"])

        print(f"\n{len(FAILS)} failures")
        return 1 if FAILS else 0
    finally:
        shutil.rmtree(STORE, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
