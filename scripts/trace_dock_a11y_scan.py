#!/usr/bin/env python3
"""trace_dock_a11y_scan.py — trace dock 셀렉터 정적 접근성 grep.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.6.

dock 셀렉터(기본 data-testid=trace-dock-history, trace-dock-current,
trace-dock-toggle)가 js/html/css 에 등장하는지 스캔하고, 해당 요소(또는
같은 태그/문맥)에 aria-hidden="true" / inert / hidden / display:none 이
걸려 있으면 FAIL.

정적만 — 브라우저 실측 a11y snapshot 은 NOT_RUN 으로 JSON에 명시.

exit 0 = 셀렉터 존재 + 숨김 속성 없음
exit 2 = dock 요소/조상에 aria-hidden=true / inert / hidden 강제 발견
exit 3 = 셀렉터 부재(SELECTORS_ABSENT_PRE_PATCH — Codex 구현 전 정상) 또는 불명
"""
from __future__ import annotations

import argparse
import datetime as dt
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sys

CONTRACT_ID = "DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929"
SCHEMA = "awx.trace-dock-a11y-scan.v1"
DEFAULT_OUT_DIR = "data/diagnostics/f01b-trace-access-0929"
DEFAULT_SELECTORS = ("data-testid=trace-dock-history,"
                     "trace-dock-current,trace-dock-toggle")
DEFAULT_PATHS = "main/resources/static/js,main/resources/templates"
SCAN_EXT = (".js", ".html", ".htm", ".css")

HIDE_RE = re.compile(
    r'aria-hidden\s*=\s*["\']?true|setAttribute\(\s*["\']aria-hidden["\']\s*,'
    r'\s*["\']true|\binert\b|\bhidden\b|display\s*:\s*none', re.I)
VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input",
             "link", "meta", "param", "source", "track", "wbr"}


def html_visibility_findings(text: str, rel: str, selectors: list[str]) -> list[dict]:
    """Inspect the selector's actual tag/ancestors, allowing the collapsible body."""
    findings = []

    class VisibilityParser(HTMLParser):
        def __init__(self):
            super().__init__(convert_charrefs=False)
            self.stack = []

        def handle_starttag(self, tag, attributes):
            attrs = {name.lower(): value for name, value in attributes}
            reasons = set()
            if "hidden" in attrs:
                reasons.add("hidden")
            if "inert" in attrs:
                reasons.add("inert")
            if (attrs.get("aria-hidden") or "").lower() == "true":
                reasons.add("aria-hidden")
            if re.search(r"(?:^|;)\s*display\s*:\s*none(?:\s*;|$)",
                         attrs.get("style") or "", re.I):
                reasons.add("display")
            node = {"tag": tag, "id": attrs.get("id"), "reasons": reasons,
                    "dockRoot": "data-trace-dock" in attrs
                    or attrs.get("data-testid") == "trace-dock"}
            selector = attrs.get("data-testid")
            if selector in selectors:
                hidden_ancestors = []
                for ancestor in self.stack:
                    allowed_body = (ancestor["id"] == "traceDockBody"
                                    and ancestor["reasons"] == {"hidden"}
                                    and any(parent["dockRoot"] for parent in self.stack))
                    if ancestor["reasons"] and not allowed_body:
                        hidden_ancestors.append(ancestor)
                if reasons or hidden_ancestors:
                    findings.append({
                        "kind": "DOCK_ELEMENT_HIDDEN", "selector": selector,
                        "file": rel, "line": self.getpos()[0],
                        "snippetMasked": f"<{tag} data-testid={selector}>",
                        "detail": "hiding attribute on dock selector or ancestor",
                    })
            if tag not in VOID_TAGS:
                self.stack.append(node)

        def handle_endtag(self, tag):
            for index in range(len(self.stack) - 1, -1, -1):
                if self.stack[index]["tag"] == tag:
                    del self.stack[index:]
                    break

        def handle_startendtag(self, tag, attributes):
            self.handle_starttag(tag, attributes)
            if tag not in VOID_TAGS:
                self.handle_endtag(tag)

    parser = VisibilityParser()
    parser.feed(text)
    parser.close()
    return findings


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def selector_tokens(raw: str) -> list[str]:
    """`data-testid=trace-dock-history,trace-dock-current` → 순수 셀렉터 토큰."""
    tokens = []
    for part in str(raw).split(","):
        part = part.strip()
        if not part:
            continue
        tokens.append(part.split("=", 1)[-1])
    return tokens


def iter_files(root: Path, paths: list[str]):
    for rel in paths:
        base = root / rel
        if base.is_file():
            yield base
        elif base.is_dir():
            for p in sorted(base.rglob("*")):
                if p.suffix.lower() in SCAN_EXT and p.is_file():
                    yield p


def scan(root: Path, selectors: list[str], also: list[str],
         paths: list[str]) -> dict:
    occurrences = []   # 셀렉터 발견 지점
    findings = []      # 숨김 위반
    also_sites = []    # --also 심볼 사용 지점 (정보용)
    scanned = 0

    for path in iter_files(root, paths):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        scanned += 1
        rel = str(path.relative_to(root)).replace("\\", "/")
        lines = text.splitlines()
        is_html = path.suffix.lower() in (".html", ".htm")
        if is_html:
            findings.extend(html_visibility_findings(text, rel, selectors))
        for i, line in enumerate(lines, 1):
            for sel in selectors:
                if sel in line:
                    occurrences.append({"selector": sel, "file": rel,
                                        "line": i})
                    # 같은 태그/같은 문장 범위(앞 2줄 + 뒤 2줄)에서 숨김 속성 탐지
                    context = "\n".join(lines[max(0, i - 3):i + 3])
                    if not is_html and HIDE_RE.search(context):
                        findings.append({
                            "kind": "DOCK_ELEMENT_HIDDEN",
                            "selector": sel, "file": rel, "line": i,
                            "snippetMasked": line.strip()[:160],
                            "detail": "aria-hidden/inert/hidden/display:none "
                                      "on or near dock selector",
                        })
            for sym in also:
                if sym and sym + "(" in line:
                    also_sites.append({"symbol": sym, "file": rel, "line": i})
                    # dock 셀렉터가 이 호출 문맥(앞 2줄 + 뒤 2줄)에 있으면
                    # 숨김 라우팅으로 간주 (호출 인자가 앞줄 변수인 경우 포함)
                    context = "\n".join(lines[max(0, i - 3):i + 3])
                    hit = next((s for s in selectors if s in context), None)
                    if hit:
                        findings.append({
                            "kind": "DOCK_VIA_HIDDEN_HELPER",
                            "selector": hit, "symbol": sym, "file": rel,
                            "line": i,
                            "detail": "dock selector routed through "
                                      f"{sym} (aria-hidden producer)",
                        })
    return {"occurrences": occurrences, "findings": findings,
            "alsoSites": also_sites, "scannedFiles": scanned}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Static aria-hidden/inert/hidden scan for trace dock selectors")
    ap.add_argument("--root", default=".")
    ap.add_argument("--selectors", default=DEFAULT_SELECTORS,
                    help="comma-separated selector tokens (data-testid=…)")
    ap.add_argument("--also", default="markChatDiagnosticNode",
                    help="comma-separated symbols to watch for dock routing")
    ap.add_argument("--paths", default=DEFAULT_PATHS,
                    help="comma-separated dirs/files under root")
    ap.add_argument("--json-out",
                    default=f"{DEFAULT_OUT_DIR}/trace_dock_a11y_scan.json")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    selectors = selector_tokens(args.selectors)
    also = [s.strip() for s in str(args.also).split(",") if s.strip()]
    paths = [p.strip() for p in str(args.paths).split(",") if p.strip()]
    if not selectors:
        print("usage: --selectors 비어 있음", file=sys.stderr)
        return 1

    res = scan(root, selectors, also, paths)

    if res["findings"]:
        verdict, code, reason = "FAIL", 2, "HIDDEN_ATTR_ON_DOCK"
    elif not res["occurrences"]:
        verdict, code, reason = "PARTIAL", 3, "SELECTORS_ABSENT_PRE_PATCH"
    else:
        verdict, code, reason = "PASS", 0, None

    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "track": "TRACE",
        "generatedAtUtc": utcnow(),
        "root": str(root),
        "verdict": verdict,
        "exitCode": code,
        "reason": reason,
        "selectors": selectors,
        "occurrences": res["occurrences"],
        "findings": res["findings"],
        "alsoSites": res["alsoSites"],
        "scannedFiles": res["scannedFiles"],
        "browserA11ySnapshot": "NOT_RUN",
        "note": "static grep only; browser a11y snapshot not run. "
                "PARTIAL/SELECTORS_ABSENT_PRE_PATCH is normal before the "
                "Codex dock implementation lands",
    }
    out_path = root / args.json_out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"a11y verdict={verdict} exit={code} reason={reason} "
              f"occurrences={len(res['occurrences'])} json={out_path}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
