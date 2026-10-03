"""orchestra_paste.py — render a paste-ready brief for the routed agent.

Reads a routed awx.orchestra-signal.v1 signal and writes
  var/orchestra/outbox/PASTE_<AGENT>_<topic>_<yyyymmdd>.txt
plus a `말로:` one-liner the user can type into that agent's chat.

This tool NEVER sends anything anywhere — it only writes the local file and
prints the 말로 line. Actual keys/passwords are never included; forbidden
list and cost order are embedded in every template.
"""
import argparse
import json
import re
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

SCHEMA = "awx.orchestra-paste.v1"

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (OSError, ValueError):
        pass
DEFAULT_STORE = "data/agent-handoff/orchestra"
DEFAULT_RULES = "scripts/fixtures/orchestra/route-rules.json"
DEFAULT_OUTDIR = "var/orchestra/outbox"

COST_ORDER_TEXT = "비용 순서: Codex 크레딧 → 외부 유료 → 무료 → Ollama 마지막"

FORBIDDEN = [
    "push/pull/commit, git add -A, reset/checkout/restore/stash/clean, git remote 변경",
    "전체 테스트 스위트·Gradle 실행, 서버 시작·재시작",
    ".env/.secrets/토큰 열람·출력, PROTO_OPEN·admin 강화",
    "DB 스키마 변경·데이터셋 삭제, chat.js 수정, 제품 소스 수정(비소유자)",
    "다른 세션 lease·hunk·ledger 수정, 전역 에이전트 설정 수정",
    "skip-permissions/full-access 모드 권장, gptpro_pack.py 직접 실행(사용자 전용)",
    "유료 API·웹서치 실제 호출(허용 예산 범위 밖), 다른 에이전트 창·방·DM 자동 게시",
    "native glm_worker 사용, mock을 라이브 PASS로 보고",
]


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def find_signal(root, store, ref):
    path = Path(ref)
    if path.is_file():
        return load_json(path)
    base = Path(root) / store
    for part in ("inbox", "outbox"):
        hits = sorted((base / part).glob("*/" + ref + ".json"))
        if hits:
            return load_json(hits[0])
    hit = base / "archive" / (ref + ".json")
    if hit.is_file():
        return load_json(hit)
    raise FileNotFoundError("signal-not-found:" + ref)


def slug(text):
    text = re.sub(r"[^0-9A-Za-z가-힣]+", "-", (text or "")[:40]).strip("-").lower()
    return text or "signal"


def files_block(sig):
    files = sig.get("files") or []
    if not files:
        return "(지정된 파일 없음 — 범위는 신호 summary/notes 참조)"
    return "\n".join("- " + f for f in files)


def signal_block(sig):
    return (
        f"## 신호\n"
        f"- id: {sig.get('id')} / parentId: {sig.get('parentId') or '-'}\n"
        f"- kind: {sig.get('kind')} / lane: {sig.get('lane')} / priority: {sig.get('priority')}\n"
        f"- evidenceTier: {sig.get('evidenceTier')} / status: {sig.get('status')}\n"
        f"- 요약: {sig.get('summary')}\n"
        f"- 예산: 라이브 호출 {sig.get('budget', {}).get('liveCalls', 0)}, "
        f"재시작 {sig.get('budget', {}).get('restarts', 0)}\n"
        f"\n## 대상 파일\n{files_block(sig)}\n"
    )


def common_tail(agent):
    return (
        f"\n## 공통 금지(모든 에이전트)\n"
        + "\n".join("- " + f for f in FORBIDDEN)
        + f"\n\n{COST_ORDER_TEXT}\n"
        + "실제 키·비밀번호는 절대 포함하지 않는다. mock·dry-run 결과를 '확인됨'으로 보고하지 않는다.\n"
        + f"응답 첫 줄 형식: `외부 API: n회, 웹서치 n회, 재시작 n`\n"
    )


def render(agent, sig):
    head = signal_block(sig)
    notes = (sig.get("notes") or "").strip()
    if agent == "codex":
        return (
            "[ANTI-STOP]\n읽고 설계만 하지 말고 끝까지 구현·검증·보고합니다.\n\n"
            + head
            + "\n## 수정 허용\n위 대상 파일 범위 안. lease·checkpoint·journal 게이트는 기존 규칙 그대로.\n"
            + "\n## 수정 금지\n범위 밖 제품 소스·테스트·설정. 아래 공통 금지 전부.\n"
            + "\n## Acceptance\n- 범위 안 변경 컴파일/포커스 테스트 PASS\n- 기존 계약·기본값 회귀 0\n- 보고 첫 줄: `외부 API:` 회수 명시\n"
            + common_tail(agent)
            + (f"\n## 참고 메모\n{notes}\n" if notes else "")
            + "\n[ANTI-STOP] 설계만 쓰고 멈추지 마세요.\n"
        )
    if agent == "devin":
        return (
            "[데빈 작업]\n" + head
            + "\n## 허용\n새 파일만: scripts/orchestra_*·test_*·fixtures·docs·.agents 스킬/규칙.\n"
            + "\n## 금지\n제품 소스(main/ app/ frontend/ configs/)·chat.js·기존 scripts 수정 금지(감싸서 호출만).\n"
            + common_tail(agent)
            + (f"\n## 참고 메모\n{notes}\n" if notes else "")
        )
    if agent == "gptpro":
        return (
            "[GPT Pro 브리프]\n" + head
            + "\n## harmonized 템플릿 4칸(채워서 사용자가 업로드)\n"
            + "1. 요청: " + (sig.get("summary") or "") + "\n"
            + "2. 모드: 분석+패치 지시서 초안 (ZIP 스냅샷 + 웹서치 근거)\n"
            + "3. 꼭 지킬 것: 제품 소스는 Codex가 적용 — GPT Pro는 지시서만. 비밀값 0.\n"
            + "4. 받을 사람: codex (지시서), grokbot (PC 소스 대조·필터)\n"
            + "\n## 입력\n- ZIP: Pack-GPTPro.bat 프로필 product-source (사용자가 실행)\n"
            + "- web-evidence 신호: "
            + (", ".join(sig.get("evidenceIds") or []) or "(없음 — agy 조사 먼저 권장)") + "\n"
            + common_tail(agent)
        )
    if agent == "agy":
        return (
            "[agy 조사 요청]\n" + head
            + "\n## 할 일\n"
            + "1. 위 요약을 조사 질문으로 웹서치(결과·출처 수집).\n"
            + "2. 합치기: `python -B scripts/agy_web_fuse.py`(Weighted-RRF·신선도·중복).\n"
            + "   gate.pass 아니면 next_query_hints로 1회만 더.\n"
            + "3. 결과를 web-evidence 신호로 회수:\n"
            + "   `python -B scripts/orchestra_signal.py new --from agy --kind web-evidence "
            + "--summary \"<한 줄 결론>\" --parent " + str(sig.get("id")) + "`\n"
            + "Grok Bot 서브 역할이면 demo1-agy-grokbot-mode의 팩 형식으로 답변.\n"
            + common_tail(agent)
        )
    if agent == "grokbot":
        return (
            "[Grok Bot 키우기]\n" + head
            + "\n## 할 일\n아이디어를 연타로 키움: 긍정/부정/반례 → 중립 판정 여러 번.\n"
            + "결과를 amplified 신호로 쪼개 devin-signal / codex 후보 / research-question으로 나눔.\n"
            + common_tail(agent)
            + (f"\n## 참고 메모\n{notes}\n" if notes else "")
        )
    return head + common_tail(agent)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--store", default=DEFAULT_STORE)
    parser.add_argument("--rules", default=DEFAULT_RULES)
    parser.add_argument("--id", dest="signal", required=True)
    parser.add_argument("--agent", required=True,
                        choices=("codex", "devin", "gptpro", "agy", "grokbot", "clean", "user"))
    parser.add_argument("--outdir", default=DEFAULT_OUTDIR)
    args = parser.parse_args()

    try:
        sig = find_signal(args.root, args.store, args.signal)
        body = render(args.agent, sig)
        day = datetime.now(timezone(timedelta(hours=9))).strftime("%Y%m%d")
        name = f"PASTE_{args.agent.upper()}_{slug(sig.get('summary'))}_{day}.txt"
        out_dir = Path(args.root) / args.outdir
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / name
        out_path.write_text(body, encoding="utf-8")
        mallow = f"말로: 「{out_path.as_posix()} 내용을 그대로 붙여넣어 진행해줘」"
        print(json.dumps({
            "schemaVersion": SCHEMA,
            "signalId": sig.get("id"),
            "agent": args.agent,
            "pasteFile": str(out_path),
            "mallowLine": mallow,
            "autoSent": False,
        }, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError) as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": str(exc)}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    sys.exit(main())
