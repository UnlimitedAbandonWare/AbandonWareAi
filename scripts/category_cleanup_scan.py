#!/usr/bin/env python3
"""category_cleanup_scan.py -- read-only recon for DEMO1-DEVIN-CATEGORY-CLEANUP-TARGETS-20260928.

전탐침 결과를 JSON으로 낸다. 절대 삭제/수정하지 않는다. 쓰기는 --out 단일 파일뿐.
판정은 '힌트'다: 최종 verdict는 사람(또는 승인 카드)이 3중 증명(스캔 밖+미import+미호출)을 확인 후 내린다.

Usage:
  python -B scripts/category_cleanup_scan.py            # 콘솔 요약 + scan-report.json
  python -B scripts/category_cleanup_scan.py --no-write # 콘솔만
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # scripts/ 의 부모 = repo root
SRC = ROOT / "main" / "java"
RES = ROOT / "main" / "resources"
OUT_DEFAULT = ROOT / "docs" / "diagnostics" / "category-cleanup-0928" / "scan-report.json"

# 컴포넌트 스캔 대상(빌드 SSOT): LmsApplication.scanBasePackages
SCANNED_PREFIXES = ("com.example.lms", "com.nova.protocol")

PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", re.M)
CLASS_RE = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+\w+")
SPRING_RE = re.compile(
    r"@(SpringBootApplication|Service|Component|RestController|Controller|"
    r"Configuration|Repository|Aspect|ConditionalOnProperty)\b")
MAIN_RE = re.compile(r"mainClass\.set\(\"([^\"]+)\"\)")
SCANBASE_RE = re.compile(r"scanBasePackages\s*=\s*\{([^}]*)\}")
SCRIPT_REF_RE = re.compile(r"scripts/[A-Za-z0-9_.\-]+?\.(?:py|ps1|bat|cjs|js|sh)\b")
BAT_REF_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9\-]*\.bat\b")
PATH_LIT_RE = re.compile(r"main/java/[A-Za-z0-9_./]+?\.java")
JUNK_NAME_RE = re.compile(r"(?i)(_old|_copy|_orig|_backup|_new|_tmp|copy\d*|test_mod|shim)")

# 표면 스캔 대상: 에이전트가 읽는 지시 계층(규칙/스킬/프롬프트/최상위 문서)
RULE_DIRS = [".clinerules", ".grok/rules", ".codex", ".devin", ".windsurf/rules"]
REF_SCAN_DIRS = [".agents", ".windsurf", ".grok", ".codex", ".devin", ".clinerules",
                 "agent-prompts", "docs"]
REF_SCAN_FILES = ["AGENTS.md", "EXTERNAL_SKILLS.md", "Abandon_X.txt", "UAW.txt"]


def _pkg_of(text: str):
    m = PKG_RE.search(text)
    return m.group(1) if m else "(default)"


def _group_of(pkg: str) -> str:
    """패키지를 판정 그룹으로 묶는다(세부 판정은 사람이)."""
    if pkg == "(default)":
        return pkg
    p = pkg.split(".")
    if p[0] == "com":
        if len(p) > 2:
            return ".".join(p[:3])            # com.example.lms / com.nova.protocol / com.abandonware.ai ...
        return ".".join(p[:2])                # com.abandonwareai 같이 2단계로 끝나는 경우
    if p[0] == "ai":
        return ".".join(p[:3])                # ai.abandonware.nova 등
    return p[0]                               # 루트 패키지: service, web, config...


def _java_files():
    return sorted(p for p in SRC.rglob("*.java") if p.is_file())


def _read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace")


def _iter_ref_docs():
    """스킬/규칙/프롬프트의 스크립트 참조를 읽는다(대량이 아니라 지시 계층만)."""
    for d in REF_SCAN_DIRS:
        base = ROOT / d
        if not base.is_dir():
            continue
        for f in base.rglob("*"):
            if f.is_file() and f.suffix in (".md", ".yaml", ".yml", ".txt", ".json"):
                yield f
    for name in REF_SCAN_FILES:
        f = ROOT / name
        if f.is_file():
            yield f


def scan():
    report = {"schemaVersion": "awx.category-cleanup-scan.v1",
              "contract": "DEMO1-DEVIN-CATEGORY-CLEANUP-TARGETS-20260928",
              "generatedAtUtc": _dt.datetime.now(_dt.timezone.utc).isoformat(),
              "root": str(ROOT), "readOnly": True,
              "note": "verdict_hint 는 힌트. 최종 판정은 3중 증명 확인 후 사람이 내린다."}

    java_files = _java_files()
    fileinfo = []          # per-file 요약
    group_files = {}       # 그룹 -> [path]
    for f in java_files:
        rel = f.relative_to(ROOT).as_posix()
        text = _read(f)
        pkg = _pkg_of(text)
        expected = ".".join(f.parent.relative_to(SRC).parts) or "(default)"
        grp = _group_of(pkg)
        group_files.setdefault(grp, []).append(rel)
        fileinfo.append({
            "path": rel, "pkg": pkg, "expectedPkg": expected,
            "pkgMismatch": pkg != expected,
            "hasType": bool(CLASS_RE.search(text)),
            "annotations": sorted(set(SPRING_RE.findall(text))),
            "junkName": bool(JUNK_NAME_RE.search(f.stem)),
            "bytes": f.stat().st_size,
        })

    # import 수집: 누가 어떤 그룹을 참조하는가 (main + 테스트 소스 둘 다)
    imports_by_group = {}
    live_importers = {}
    test_roots = [ROOT / "src" / "test", ROOT / "src" / "chatUiTest",
                  ROOT / "src" / "gatewaySecurityTest", ROOT / "src" / "crossSubsystemContractTest"]
    all_java = list(java_files)
    for tr in test_roots:
        if tr.is_dir():
            all_java += sorted(p for p in tr.rglob("*.java") if p.is_file())
    for f in all_java:
        rel = f.relative_to(ROOT).as_posix()
        text = _read(f)
        own_pkg = _pkg_of(text)
        own = _group_of(own_pkg)
        for imp in IMPORT_RE.findall(text):
            ig = _group_of(".".join(imp.split(".")[:-1]))
            if ig and ig != own:
                imports_by_group.setdefault(ig, set()).add(rel)
                if own_pkg.startswith(SCANNED_PREFIXES) and rel.startswith("main/java/"):
                    live_importers.setdefault(ig, set()).add(rel)

    # 테스트/스크립트의 경로 리터럴(test-pinned 감지)
    pinned = {}
    for tr in test_roots + [ROOT / "scripts"]:
        if not tr.is_dir():
            continue
        for f in tr.rglob("*"):
            if f.suffix not in (".java", ".py", ".ps1", ".md"):
                continue
            try:
                text = _read(f)
            except OSError:
                continue
            for lit in PATH_LIT_RE.findall(text):
                pinned.setdefault(lit, set()).add(f.relative_to(ROOT).as_posix())

    # autoconfig 등록 확인(스캔 없이도 live가 되는 경로)
    autoconfigs = []
    ac = RES / "META-INF" / "spring" / "org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    if ac.is_file():
        autoconfigs = [ln.strip() for ln in _read(ac).splitlines()
                       if ln.strip() and not ln.strip().startswith("#")]

    report["c1_junk"] = [
        {"path": i["path"], "pkg": i["pkg"],
         "kinds": ([k for k, v in (("name-trap", i["junkName"]),
                                   ("pkg-mismatch", i["pkgMismatch"]),
                                   ("comment-or-empty", not i["hasType"])) if v]),
         "annotations": i["annotations"],
         "testPinned": i["path"] in pinned}
        for i in fileinfo if i["junkName"] or i["pkgMismatch"] or not i["hasType"]]

    c2 = []
    for grp, paths in sorted(group_files.items()):
        scanned = grp.startswith(SCANNED_PREFIXES)
        acfg = [a for a in autoconfigs if a.startswith(grp + ".")]
        live_imp = sorted(live_importers.get(grp, set()))
        pins = [p for p in paths if p in pinned]
        if scanned:
            hint = "SCANNED(LIVE)"
        elif acfg or live_imp:
            hint = "WIRED-NOT-SCANNED(autoconfig/import)"
        elif pins:
            hint = "TEST-PINNED-DORMANT?"
        else:
            hint = "DORMANT_CANDIDATE(3증명 필요)"
        c2.append({"group": grp, "files": len(paths), "scanned": scanned,
                   "autoconfigs": acfg,
                   "allImporterCount": len(imports_by_group.get(grp, set())),
                   "liveImporterCount": len(live_imp),
                   "liveImporterSamples": live_imp[:5],
                   "testPinnedCount": len(pins), "verdictHint": hint,
                   "samplePaths": paths[:8]})
    report["c2_packages"] = c2

    # C3: 진입점/플랜/설정 이중키
    def _scan_base(rel):
        m = SCANBASE_RE.search(_read(ROOT / rel))
        return re.findall(r'"([\w.]+)"', m.group(1)) if m else []

    mains = [{"path": i["path"], "annotations": i["annotations"],
              "scanBase": _scan_base(i["path"])}
             for i in fileinfo if "SpringBootApplication" in i["annotations"]]
    gradle = ROOT / "build.gradle.kts"
    main_classes = MAIN_RE.findall(_read(gradle)) if gradle.is_file() else []
    report["c3_runtime"] = {
        "springBootApps": mains, "gradleMainClasses": main_classes,
        "applicationFiles": sorted(
            ({"name": f.name, "bytes": f.stat().st_size}
             for f in RES.glob("application*") if f.is_file()),
            key=lambda d: d["name"]) if RES.is_dir() else [],
        "planFiles": sorted(f.name for f in (RES / "plans").glob("*")
                            if (RES / "plans").is_dir()),
    }

    # C4: 에이전트 표면 수치
    def _count_dir(d):
        b = ROOT / d
        return (len([x for x in b.iterdir() if x.is_dir()]) if b.is_dir() else 0,
                len([x for x in b.iterdir() if x.is_file()]) if b.is_dir() else 0)
    sk_d, sk_f = _count_dir(".agents/skills")
    ap_d, ap_f = _count_dir("agent-prompts")
    dg_d, dg_f = _count_dir("docs/diagnostics")
    report["c4_surface"] = {
        "skillDirs": sk_d, "skillLooseFiles": sk_f,
        "agentPromptDirs": ap_d, "agentPromptFiles": ap_f,
        "diagEntries": dg_d + dg_f,
        "ruleFiles": {d: (len(list((ROOT / d).rglob("*"))) if (ROOT / d).is_dir() else None)
                      for d in RULE_DIRS},
        "scriptsByExt": _ext_counts(ROOT / "scripts"),
        "rootBat": sorted(f.name for f in ROOT.glob("*.bat")),
    }

    # C5: 지시 문서가 가리키는 scripts/* 유령 + 쌍 중복 후보
    live = {p.relative_to(ROOT).as_posix().casefold(): p for p in (ROOT / "scripts").rglob("*") if p.is_file()}
    live |= {p.name.casefold(): p for p in ROOT.glob("*.bat")}
    refs = {}
    for doc in _iter_ref_docs():
        try:
            text = _read(doc)
        except OSError:
            continue
        drel = doc.relative_to(ROOT).as_posix()
        if "category-cleanup-0928" in drel:   # 자기 산출물 재흡수 방지
            continue
        for m in set(SCRIPT_REF_RE.findall(text)) | set(BAT_REF_RE.findall(text)):
            key = m
            cand = key.casefold()
            hit = (cand in live or (ROOT / key).is_file()
                   or (ROOT / "scripts" / Path(key).name).is_file())  # bare bat→scripts/
            ent = refs.setdefault(key, {"exists": False, "docs": []})
            ent["exists"] = ent["exists"] or bool(hit)
            ent["docs"].append(drel)
    ghosts = sorted(k for k, v in refs.items() if not v["exists"])
    report["c5_ghosts"] = {
        "referencedTotal": len(refs), "missingCount": len(ghosts),
        "missing": [{"ref": k, "docs": refs[k]["docs"][:4]} for k in ghosts],
        "stemPairs": _stem_pairs(ROOT / "scripts"),
    }
    return report


def _ext_counts(d: Path):
    out = {}
    if d.is_dir():
        for f in d.iterdir():
            if f.is_file():
                out[f.suffix or "(none)"] = out.get(f.suffix or "(none)", 0) + 1
    return out


def _stem_pairs(d: Path):
    """같은 stem 다른 확장자(예: foo.py+foo.ps1) = 이중 진입점 혼란 후보."""
    stems = {}
    if not d.is_dir():
        return []
    for f in d.iterdir():
        if f.is_file():
            stems.setdefault(f.stem.casefold(), []).append(f.name)
    return sorted(names for names in stems.values() if len(names) > 1)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=str(OUT_DEFAULT))
    ap.add_argument("--no-write", action="store_true")
    args = ap.parse_args()
    rep = scan()
    if not args.no_write:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(rep, ensure_ascii=False, indent=2), encoding="utf-8")
    c2 = rep["c2_packages"]
    print(f"[scan] java={sum(g['files'] for g in c2)} groups={len(c2)} "
          f"junk={len(rep['c1_junk'])} ghostRefs={rep['c5_ghosts']['missingCount']}/"
          f"{rep['c5_ghosts']['referencedTotal']} skills={rep['c4_surface']['skillDirs']} "
          f"promptDirs={rep['c4_surface']['agentPromptDirs']}")
    for g in c2:
        print(f"  {g['verdictHint']:34} {g['files']:5}  {g['group']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
