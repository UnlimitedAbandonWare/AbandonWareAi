"""demo1-uaw-vibe-longrun status probe (read-only JSON).

Snapshot only — no orchestration. Composes existing read paths:
  - project-root markers + journal counts (filesystem scan)
  - source-edit lease status (__patch_drop__/source_edit_session.ps1 -Action status -Json)
  - newest sealed/prepared checkpoint path (data/agent-handoff/codex-autonomy/**/checkpoint.json)
  - router resolve echo (scripts/demo1_vibe_skill_router.py resolve --ask)

Every field degrades to "not_observed" on failure; this script never throws
and never writes. Usage: python -B scripts/demo1_uaw_vibe_longrun_status.py [--ask "<text>"]
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TIMEOUT = 30


def run_json(cmd, timeout=TIMEOUT):
    try:
        out = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True,
                             timeout=timeout)
        return json.loads(out.stdout.strip().splitlines()[-1]) if out.stdout.strip() else {
            "status": "not_observed", "exitCode": out.returncode}
    except Exception as exc:  # noqa: BLE001 - status probe must not throw
        return {"status": "not_observed", "error": type(exc).__name__}


def project_root():
    markers = {name: (ROOT / name).exists() for name in
               ("gradlew.bat", "AGENTS.md", "settings.gradle.kts", "main/java")}
    return {"resolved": str(ROOT), "markers": markers,
            "status": "ok" if all(markers.values()) else "suspect"}


def journals():
    base = ROOT / "data" / "agent-handoff" / "codex-autonomy"
    active, latest = [], None
    if base.is_dir():
        for journal in base.glob("*/journal.json"):
            try:
                data = json.loads(journal.read_text(encoding="utf-8-sig"))
            except Exception:  # noqa: BLE001
                continue
            if data.get("status") == "in_progress":
                active.append({"taskId": data.get("taskId"), "agent": data.get("agent"),
                               "updatedAtUtc": data.get("updatedAtUtc")})
        checkpoints = sorted(base.glob("*/cycle-*/checkpoint.json"),
                             key=lambda p: p.stat().st_mtime, reverse=True)
        if checkpoints:
            latest = {"path": str(checkpoints[0].relative_to(ROOT)).replace("\\", "/")}
            try:
                latest["status"] = json.loads(
                    checkpoints[0].read_text(encoding="utf-8-sig")).get("status")
            except Exception:  # noqa: BLE001
                latest["status"] = "unreadable"
    return {"activeCount": len(active), "active": active[:10],
            "lastCheckpoint": latest or "not_observed"}


def leases():
    out = run_json(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    str(ROOT / "__patch_drop__" / "source_edit_session.ps1"),
                    "-Action", "status", "-Root", ".", "-Json"])
    if isinstance(out, dict):
        return {"active": out.get("sourceLeaseActiveCount", "not_observed"),
                "expired": out.get("sourceLeaseExpiredCount", "not_observed"),
                "corrupt": out.get("sourceLeaseCorruptCount", "not_observed"),
                "activeTopics": out.get("sourceLeaseActiveTopics", []),
                "status": "ok" if "sourceLeaseActiveCount" in out else "not_observed"}
    return {"status": "not_observed"}


def router_echo(ask):
    out = run_json([sys.executable, "-B", "scripts/demo1_vibe_skill_router.py",
                    "resolve", ask])
    if isinstance(out, dict):
        return {"ask": ask, "intent": out.get("intent"), "primary": out.get("primary"),
                "optional": out.get("optional"), "score": out.get("score")}
    return {"ask": ask, "status": "not_observed"}


def main():
    ask = "장기 오토 바이브"
    if "--ask" in sys.argv:
        idx = sys.argv.index("--ask")
        if idx + 1 < len(sys.argv):
            ask = sys.argv[idx + 1]
    print(json.dumps({
        "schemaVersion": "awx.uaw-vibe-longrun-status.v1",
        "projectRoot": project_root(),
        "journals": journals(),
        "leases": leases(),
        "routerResolve": router_echo(ask),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
