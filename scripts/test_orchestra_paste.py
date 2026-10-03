"""test_orchestra_paste.py — 4 templates, forbidden list, cost order, 말로 line,
and proof there is no auto-send code path.

Run: python -B scripts/test_orchestra_paste.py   (exit 0 = all pass)
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
PASTE = ROOT / "scripts" / "orchestra_paste.py"

FAILS = []


def check(name, cond, detail=""):
    if cond:
        print(f"  PASS {name}")
    else:
        FAILS.append(name)
        print(f"  FAIL {name} {detail}")


def main():
    tmp = Path(tempfile.mkdtemp(prefix="orch-paste-test-"))
    try:
        store = tmp / "store"
        inbox = store / "inbox" / "codex"
        inbox.mkdir(parents=True)
        sig = {
            "schemaVersion": "awx.orchestra-signal.v1",
            "fromTask": None, "toTask": None, "fromChat": None,
            "lastCheckpoint": None, "ownedChanges": [], "smokeUsed": 0,
            "restartUsed": 0, "openItems": [], "askOnceAnswers": [],
            "findings": [], "atUtc": "2026-10-03T00:00:00+00:00",
            "id": "beefcafe0001", "parentId": None, "from": "grokbot",
            "kind": "codex-brief", "lane": "CODEX_DIRECT", "priority": "P1",
            "evidenceTier": "확인됨", "summary": "test paste signal",
            "notes": "memo body", "files": ["main/java/a/A.java"],
            "budget": {"liveCalls": 0, "restarts": 0},
            "budgetUsed": {"liveCalls": 0, "restarts": 0},
            "status": "routed", "pasteFile": None,
            "atKst": "2026-10-03 09:00 KST", "children": [], "roundtrips": 0,
        }
        (inbox / "beefcafe0001.json").write_text(json.dumps(sig), encoding="utf-8")

        for agent in ("codex", "devin", "gptpro", "agy"):
            outdir = tmp / "out" / agent
            proc = subprocess.run(
                [sys.executable, "-B", str(PASTE), "--store", str(store),
                 "--id", "beefcafe0001", "--agent", agent,
                 "--outdir", str(outdir)],
                cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
                errors="replace")
            check(f"{agent} exit0", proc.returncode == 0, proc.stderr[:200])
            res = json.loads(proc.stdout)
            check(f"{agent} file", Path(res["pasteFile"]).is_file())
            check(f"{agent} autoSent false", res["autoSent"] is False)
            check(f"{agent} mallow", res["mallowLine"].startswith("말로: 「"))
            body = Path(res["pasteFile"]).read_text(encoding="utf-8")
            check(f"{agent} forbidden list", "공통 금지" in body and
                  "chat.js" in body and "push" in body)
            check(f"{agent} cost order", "Codex 크레딧" in body and
                  "Ollama" in body)
            check(f"{agent} api-count line", "외부 API:" in body)

        # template specifics
        codex_body = next((tmp / "out" / "codex").glob("PASTE_CODEX_*")).read_text(encoding="utf-8")
        check("codex anti-stop", codex_body.count("[ANTI-STOP]") == 2)
        check("codex acceptance", "Acceptance" in codex_body)
        gpt_body = next((tmp / "out" / "gptpro").glob("PASTE_GPTPRO_*")).read_text(encoding="utf-8")
        check("gptpro 4 fields", all(t in gpt_body for t in
                                     ("요청", "모드", "꼭 지킬 것", "받을 사람")))
        check("gptpro user-only pack", "사용자가 실행" in gpt_body)
        agy_body = next((tmp / "out" / "agy").glob("PASTE_AGY_*")).read_text(encoding="utf-8")
        check("agy fuse cmd", "agy_web_fuse.py" in agy_body)
        check("agy return format", "web-evidence" in agy_body)
        devin_body = next((tmp / "out" / "devin").glob("PASTE_DEVIN_*")).read_text(encoding="utf-8")
        check("devin no-product rule", "제품 소스" in devin_body)

        # no auto-send code path in the tool itself
        src = PASTE.read_text(encoding="utf-8")
        check("no network/send calls",
              not any(t in src for t in ("requests.", "urllib.request", "smtplib",
                                         "websocket", "socket.", "http.post")))

        print(f"\n{len(FAILS)} failures")
        return 1 if FAILS else 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
