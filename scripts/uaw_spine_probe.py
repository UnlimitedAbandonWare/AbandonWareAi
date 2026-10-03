#!/usr/bin/env python3
"""uaw_spine_probe.py — UAW 스파인 정적 탐침 (읽기 전용).

DEMO1-DEVIN-UAW-HARMONY-FOUNDATION-20260928 WP1-H1.
큰 그림 척추(Boot/imports/RAG spine/gates/RuleBreak/plans)가 live 트리에서
계약대로 서 있는지 파일·텍스트 수준으로 관측한다. 제품 코드를 수정하지 않고,
빌드/부팅/네트워크도 실행하지 않는다 — "포트폴리오 문장이 아니라 checkout이
말하는" 스냅샷.

사용:
  python -B scripts/uaw_spine_probe.py [--root .] [--json] [--md <path>]

출력: checks[] 각 항목에 id|verdict(OK/WARN/STALE/ABSENT)|evidence.
exit 0 = 스캔 완료(verdict와 무관). exit 2 = 도구 자체 오류.
"""
import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.uaw-spine-probe.v1"

# canonical 부팅 계약 (Abandon_X §1 + build.gradle.kts)
EXPECTED_MAIN_CLASS = "com.example.lms.LmsApplication"
EXPECTED_SCAN = ["com.example.lms", "com.nova.protocol"]
EXPECTED_IMPORTS = [
    "ai.abandonware.nova.autoconfig.NovaDebugPortAutoConfiguration",
    "ai.abandonware.nova.autoconfig.NovaFailurePatternAutoConfiguration",
    "ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration",
    "ai.abandonware.nova.autoconfig.NovaOpsStabilizationAutoConfiguration",
    "ai.abandonware.nova.autoconfig.NovaZero100AutoConfiguration",
    "com.example.lms.agent.context.AgentDbContextAutoConfiguration",
]
# 스캔 밖 dormant 루트 (presence-only 관측; 활성화 시도 금지)
DORMANT_ROOTS = [
    "main/java/com/abandonware/ai",
    "main/java/com/abandonwareai",
    "main/java/com/abandonware/patch",
    "main/java/strategy",
    "main/java/service",
    "main/java/web",
    "main/java/config",
    "main/java/guard",
    "main/java/trace",
]


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def check(root: Path) -> list:
    out = []

    def add(cid, verdict, evidence):
        out.append({"id": cid, "verdict": verdict, "evidence": evidence})

    # S1: mainClass 계약
    gradle = read_text(root / "build.gradle.kts")
    if f'mainClass.set("{EXPECTED_MAIN_CLASS}")' in gradle:
        add("S1.boot-main-class", "OK", 'build.gradle.kts mainClass="com.example.lms.LmsApplication"')
    elif "mainClass.set" in gradle:
        m = re.search(r'mainClass\.set\("([^"]+)"\)', gradle)
        add("S1.boot-main-class", "STALE", f"mainClass={m.group(1) if m else 'unknown'}")
    else:
        add("S1.boot-main-class", "ABSENT", "build.gradle.kts에 mainClass.set 없음")

    # S2: canonical 스캔 경계
    lms = read_text(root / "main/java/com/example/lms/LmsApplication.java")
    if lms:
        ok = all(pkg in lms for pkg in EXPECTED_SCAN)
        add("S2.scan-boundary", "OK" if ok else "WARN",
            "scanBasePackages=" + (re.search(r"scanBasePackages\s*=\s*\{([^}]*)\}", lms).group(1).strip()
                                   if re.search(r"scanBasePackages\s*=\s*\{([^}]*)\}", lms) else "unparsed"))
    else:
        add("S2.scan-boundary", "ABSENT", "LmsApplication.java 없음")

    # S3: AutoConfiguration.imports = 정확히 6종, 중복 없음
    imp_path = root / ("main/resources/META-INF/spring/"
                       "org.springframework.boot.autoconfigure.AutoConfiguration.imports")
    if imp_path.exists():
        lines = [ln.strip() for ln in read_text(imp_path).splitlines()
                 if ln.strip() and not ln.strip().startswith("#")]
        missing = [c for c in EXPECTED_IMPORTS if c not in lines]
        dup = sorted({ln for ln in lines if lines.count(ln) > 1})
        extra = [ln for ln in lines if ln not in EXPECTED_IMPORTS]
        verdict = "OK" if not missing and not dup else "WARN"
        add("S3.autoconfig-imports", verdict,
            f"count={len(lines)} missing={missing or '[]'} dup={dup or '[]'} extra={extra or '[]'}")
    else:
        add("S3.autoconfig-imports", "ABSENT", "imports 파일 없음")

    # S4: 대체 런처 존재-only 경고 (삭제 판단 아님)
    agent_app = root / "main/java/com/abandonware/ai/agent/AgentApplication.java"
    add("S4.legacy-launcher", "WARN" if agent_app.exists() else "OK",
        "AgentApplication.java present — 레거시 런처 잔존(스캔 확장 금지, 존재 경고만)"
        if agent_app.exists() else "AgentApplication.java 없음")

    # S5: planDsl broad=not_used 계약 마커
    uro = read_text(root / "main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java")
    has_marker = '"not_used"' in uro or "not_used" in uro
    add("S5.plandsl-not-used-contract", "OK" if has_marker else "STALE",
        'UnifiedRagOrchestrator planDsl.status="not_used" 마커 '
        + ("존재" if has_marker else "부재 — 계약 drift 의심"))

    # S6: RuleBreak MVC 배선 (Abandon_X P0-A 추적)
    mvc = read_text(root / "main/java/com/example/lms/config/WebMvcConfig.java")
    wired = "ruleBreakInterceptorProvider" in mvc and "addInterceptor(ruleBreak)" in mvc
    interceptor = root / "main/java/com/example/lms/guard/rulebreak/RuleBreakInterceptor.java"
    bean = "@Component" in read_text(interceptor)
    if wired and bean:
        verdict, ev = "OK", "WebMvcConfig 조건부 등록 + canonical @Component 빈 (admin-token 게이트)"
    elif wired:
        verdict, ev = "WARN", "WebMvcConfig 등록 코드 있으나 canonical 빈 미확인"
    else:
        verdict, ev = "STALE", "WebMvcConfig에 RuleBreak 등록 없음 (Abandon_X P0-A 상태)"
    add("S6.rulebreak-mvc-wiring", verdict, ev)

    # S7: plans/ 비-v1 별칭 중복
    plans = root / "main/resources/plans"
    if plans.is_dir():
        names = [p.name for p in plans.iterdir() if p.suffix in (".yaml", ".yml")]
        non_v1 = sorted(n for n in names if ".v1." not in n)
        v1 = {n.replace(".v1.", ".").replace(".v1", "") for n in names if ".v1." in n}
        dupes = sorted(n for n in non_v1 if n in v1 or n.rsplit(".", 1)[0] + ".v1.yaml" in names)
        add("S7.plan-alias-duplicates", "WARN" if dupes else "OK",
            f"non-v1={non_v1 or '[]'} v1과 중복 의심={dupes or '[]'}")
    else:
        add("S7.plan-alias-duplicates", "ABSENT", "main/resources/plans 없음")

    # S8: 설정 이중 선언 (properties vs yml) — 관측만, 수정 아님
    props = read_text(root / "main/resources/application.properties")
    yml = read_text(root / "main/resources/application.yml")
    dual = []
    for key in ("ocr.enabled", "ocr.min-confidence", "local-llm.base-url",
                "retrieval.vector.enabled"):
        in_props = re.search(rf"^{re.escape(key)}\s*=", props, re.M) is not None
        # yml은 들여쓰기 구조라 세그먼트 존재 여부만 본다 (중첩 정합은 미보장 — 관측용)
        in_yml = all(re.search(rf"^\s*{re.escape(seg)}\s*:", yml, re.M)
                     for seg in key.split("."))
        if in_props and in_yml:
            dual.append(key)
    add("S8.config-dual-declare", "WARN" if dual else "OK",
        f"properties+yml 양쪽 선언={dual or '[]'} (properties가 우선 — 실효값은 origin 확인 필요)")

    # S9: dormant 루트 존재 관측
    present = [d for d in DORMANT_ROOTS if (root / d).is_dir()]
    add("S9.dormant-roots", "WARN" if present else "OK",
        f"스캔 밖 패키지 루트 존재={present or '[]'} — 깨우지 말 것")

    # S10: 게이트 canonical FQCN 존재
    gates = {
        "CitationGate": "main/java/com/example/lms/guard/CitationGate.java",
        "FinalSigmoidGate": "main/java/com/example/lms/guard/FinalSigmoidGate.java",
        "DomainWhitelist": "main/java/com/example/lms/service/rag/auth/DomainWhitelist.java",
        "PIISanitizer": "main/java/com/example/lms/service/guard/PIISanitizer.java",
        "PiiSanitizer(guard)": "main/java/com/example/lms/guard/PiiSanitizer.java",
    }
    found = {k: (root / v).exists() for k, v in gates.items()}
    add("S10.canonical-gates", "OK" if all(found.values()) else "WARN",
        "; ".join(f"{k}={'Y' if v else 'N'}" for k, v in found.items()))

    return out


def to_markdown(report: dict) -> str:
    lines = [
        "# uaw_spine_probe summary",
        "",
        f"- generatedAtUtc: {report['generatedAtUtc']}",
        f"- root: {report['root']}",
        f"- counts: {report['counts']}",
        "",
        "| id | verdict | evidence |",
        "| --- | --- | --- |",
    ]
    for c in report["checks"]:
        ev = str(c["evidence"]).replace("|", "\\|")
        lines.append(f"| {c['id']} | {c['verdict']} | {ev} |")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--md", default=None, help="요약 markdown 출력 경로")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    if not (root / "build.gradle.kts").exists():
        print(json.dumps({"schemaVersion": SCHEMA, "error": "not-demo1-root",
                          "root": str(root)}))
        return 2

    checks = check(root)
    counts = {}
    for c in checks:
        counts[c["verdict"]] = counts.get(c["verdict"], 0) + 1
    report = {"schemaVersion": SCHEMA, "generatedAtUtc":
              datetime.now(timezone.utc).isoformat(timespec="seconds"),
              "root": str(root), "counts": counts, "checks": checks}

    if args.md:
        md_path = Path(args.md)
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text(to_markdown(report), encoding="utf-8")
    if args.json or not args.md:
        # cp949 콘솔에서 유니코드 출력이 죽지 않도록 stdout을 utf-8로 재설정
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
        try:
            print(json.dumps(report, ensure_ascii=False, indent=2))
        except UnicodeEncodeError:
            print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
