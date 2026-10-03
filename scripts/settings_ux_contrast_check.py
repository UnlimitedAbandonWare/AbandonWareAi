#!/usr/bin/env python3
"""settings_ux_contrast_check.py — settings-page.css 명암비·폼 컨트롤 정적 검사.

Codex 설정 화면 UX 개선(P0/P1) 보조 도구(데빈 레인). 제품 소스는 읽기만 한다.

검사 항목:
- :focus-visible 윤곽선·입력/버튼 테두리 색이 배경(--surface/--background)과
  3:1 미만이면 WARN.
- 본문(--ink)·보조(--muted) 텍스트가 배경과 4.5:1 미만이면 WARN.
- `select,input` 규칙에 type 한정자가 없어 체크박스까지 width:100% 등이
  걸리는지 확인한다(타입별 규칙이 없으면 WARN).
- 입력·버튼의 대략 높이가 24px 미만인지 추정한다(padding+font-size+보더로
  추정했음을 detail에 명시).

출력: JSON {"status": "PASS"|"WARN"|"SKIP", "checks": [...], "metrics": ...}
기본 종료 코드는 0(관찰). --strict 일 때 WARN이 하나라도 있으면 1.

사용: python -B scripts/settings_ux_contrast_check.py [--root .] [--file rel]
      [--strict] [--json]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

CSS_REL = "main/resources/static/css/settings-page.css"

HEX_RE = re.compile(r"#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})\b")
RULE_RE = re.compile(r"([^{}]+)\{([^{}]*)\}")
VAR_DECL_RE = re.compile(r"(--[\w-]+)\s*:\s*([^;]+)")
VAR_REF_RE = re.compile(r"var\(\s*(--[\w-]+)\s*(?:,\s*([^)]*))?\)")
PADDING_RE = re.compile(r"padding\s*:\s*([^;}]+)")
FONT_SIZE_RE = re.compile(r"font-size\s*:\s*([\d.]+)px")
SHORTHAND_FONT_RE = re.compile(r"font\s*:\s*([\d.]+)px\s*/\s*([\d.]+)")
PX_RE = re.compile(r"([\d.]+)px")


def hex_to_rgb(value: str) -> tuple[int, int, int] | None:
    value = value.strip()
    m = re.fullmatch(r"#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})", value)
    if not m:
        return None
    h = m.group(1)
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def rel_luminance(rgb: tuple[int, int, int]) -> float:
    def lin(c: float) -> float:
        c = c / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (lin(rgb[0]), lin(rgb[1]), lin(rgb[2]))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_ratio(fg: tuple[int, int, int], bg: tuple[int, int, int]) -> float:
    l1, l2 = rel_luminance(fg), rel_luminance(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def parse_vars(text: str) -> dict[str, str]:
    vars_: dict[str, str] = {}
    root_m = re.search(r":root\s*\{([^{}]*)\}", text)
    scope = root_m.group(1) if root_m else text
    for name, raw in VAR_DECL_RE.findall(scope):
        vars_[name] = raw.strip()
    return vars_


def parse_rules(text: str) -> list[tuple[str, str]]:
    out = []
    for sel, body in RULE_RE.findall(text):
        sel = sel.strip()
        if not sel or sel.startswith("@"):
            continue
        out.append((sel, body))
    return out


def resolve_color(raw: str, vars_: dict[str, str]) -> tuple[int, int, int] | None:
    """리터럴 #hex 또는 var(--x[,fallback])를 RGB로 해석."""
    raw = raw.strip()
    rgb = hex_to_rgb(raw)
    if rgb:
        return rgb
    m = VAR_REF_RE.search(raw)
    if m:
        name, fallback = m.group(1), m.group(2)
        if name in vars_:
            return resolve_color(vars_[name], vars_)
        if fallback:
            return resolve_color(fallback, vars_)
    if raw in vars_:
        return resolve_color(vars_[raw], vars_)
    return None


def _prop_color(body: str, prop: str, vars_: dict[str, str]):
    """border/outline/color 계열에서 첫 색상 토큰을 찾는다."""
    for m in re.finditer(prop + r"[^;:]*:\s*([^;}]+)", body):
        decl = m.group(1)
        for token in HEX_RE.findall(decl):
            rgb = hex_to_rgb("#" + token)
            if rgb:
                return rgb, "#" + token
        vm = VAR_REF_RE.search(decl)
        if vm:
            rgb = resolve_color(decl[vm.start():], vars_)
            if rgb:
                return rgb, decl.strip()
        if re.fullmatch(r"\s*var\(", decl):
            rgb = resolve_color(decl, vars_)
            if rgb:
                return rgb, decl.strip()
    return None, None


def _selector_has_bare_input(selector: str) -> bool:
    for part in selector.split(","):
        part = part.strip()
        if re.fullmatch(r"input(?![\w-])", part):
            return True
        if re.search(r"(?<![\w-])input(?![\w-]*\])(?!\[|:)", part):
            return True
    return False


def _estimate_height(body: str, base_font_px: float) -> tuple[float | None, str]:
    pad_m = PADDING_RE.search(body)
    if not pad_m:
        return None, "padding 없음 — 추정 불가"
    vals = [float(x) for x in PX_RE.findall(pad_m.group(1))]
    if not vals:
        return None, "padding px 해석 불가 — 추정 불가"
    if len(vals) == 1:
        pad_top = pad_bottom = vals[0]
    elif len(vals) == 2:
        pad_top = pad_bottom = vals[0]
    else:
        pad_top, pad_bottom = vals[0], vals[2] if len(vals) > 2 else vals[0]
    fs_m = FONT_SIZE_RE.search(body)
    font_px = float(fs_m.group(1)) if fs_m else base_font_px
    line_m = SHORTHAND_FONT_RE.search(body)
    line_px = font_px * float(line_m.group(2)) if line_m else font_px * 1.2
    border = 2.0 if re.search(r"border\s*:", body) else 0.0
    return pad_top + pad_bottom + line_px + border, (
        f"padding {pad_top:g}+{pad_bottom:g}px + line≈{line_px:g}px"
        f"(font {font_px:g}px) + border {border:g}px — 추정값")


def run_check(root: Path, rel: str = CSS_REL) -> dict:
    path = root / rel
    report = {"schemaVersion": "devin.settings-ux-contrast.v1",
              "file": rel, "checks": [], "metrics": {"ratios": {}},
              "status": "SKIP"}
    if not path.is_file():
        report["checks"].append({"id": "css.present", "status": "WARN",
                                 "detail": f"{rel} 파일이 없습니다"})
        report["status"] = "WARN"
        return report
    text = path.read_text(encoding="utf-8", errors="replace")
    vars_ = parse_vars(text)
    rules = parse_rules(text)

    surface = resolve_color(vars_.get("--surface", "#fff"), vars_) or (255, 255, 255)
    background = resolve_color(vars_.get("--background", "#fff"), vars_) or (255, 255, 255)
    white = (255, 255, 255)
    report["metrics"]["backgrounds"] = {
        "surface": vars_.get("--surface"), "background": vars_.get("--background")}

    def ratio_vs_both(rgb):
        return {"vsSurface": round(contrast_ratio(rgb, surface), 2),
                "vsBackground": round(contrast_ratio(rgb, background), 2),
                "vsWhite": round(contrast_ratio(rgb, white), 2)}

    named = {}
    for var_name in ("--ink", "--muted", "--accent", "--line"):
        rgb = resolve_color(vars_.get(var_name, ""), vars_)
        if rgb:
            named[var_name] = {"rgb": rgb, "raw": vars_[var_name],
                               "ratios": ratio_vs_both(rgb)}
            report["metrics"]["ratios"][var_name] = named[var_name]["ratios"]

    # 1) 포커스 윤곽선 — 3:1 미만 WARN
    focus_bodies = [body for sel, body in rules if ":focus-visible" in sel]
    if focus_bodies:
        rgb, raw = _prop_color(focus_bodies[0], r"(?:outline|border)", vars_)
        if rgb:
            ratios = ratio_vs_both(rgb)
            report["metrics"]["ratios"]["focusOutline"] = ratios
            worst = min(ratios["vsSurface"], ratios["vsBackground"])
            report["checks"].append({
                "id": "contrast.focus_outline", "status": "WARN" if worst < 3.0 else "PASS",
                "detail": f":focus-visible 윤곽선 {raw} — surface 대비 "
                          f"{ratios['vsSurface']}:1, background 대비 {ratios['vsBackground']}:1 "
                          f"(기준 3:1)"})
    else:
        report["checks"].append({"id": "contrast.focus_outline", "status": "WARN",
                                 "detail": ":focus-visible 규칙 미발견"})

    # 2) 입력·버튼 테두리 — 3:1 미만 WARN
    border_seen = []
    for sel, body in rules:
        if re.search(r"(?<![\w-])(input|select|button|textarea)(?![\w-])", sel):
            rgb, raw = _prop_color(body, r"border(?:-(?:top|right|bottom|left|color))?", vars_)
            if rgb and (sel, raw) not in border_seen:
                border_seen.append((sel, raw, rgb))
    if border_seen:
        worst = min(min(ratio_vs_both(rgb)["vsSurface"], ratio_vs_both(rgb)["vsBackground"])
                    for _, _, rgb in border_seen)
        for sel, raw, rgb in border_seen:
            r = ratio_vs_both(rgb)
            report["metrics"]["ratios"][f"border:{sel[:40]}"] = r
        report["checks"].append({
            "id": "contrast.control_border", "status": "WARN" if worst < 3.0 else "PASS",
            "detail": "입력·버튼 테두리 최저 대비 "
                      f"{worst}:1 (기준 3:1) — "
                      + "; ".join(f"{s.strip()[:60]} → {raw}" for s, raw, _ in border_seen[:4])})
    else:
        report["checks"].append({"id": "contrast.control_border", "status": "WARN",
                                 "detail": "입력·버튼 테두리 선언 미발견"})

    # 3) 본문/보조 텍스트 — 4.5:1 미만 WARN
    for var_name, label in (("--ink", "본문"), ("--muted", "보조 텍스트")):
        info = named.get(var_name)
        if not info:
            continue
        worst = min(info["ratios"]["vsSurface"], info["ratios"]["vsBackground"])
        report["checks"].append({
            "id": f"contrast.text.{var_name[2:]}",
            "status": "WARN" if worst < 4.5 else "PASS",
            "detail": f"{label} {var_name}={info['raw']} — surface 대비 "
                      f"{info['ratios']['vsSurface']}:1, background 대비 "
                      f"{info['ratios']['vsBackground']}:1 (기준 4.5:1)"})

    # 4) select,input 규칙의 체크박스 적용 여부
    broad = [sel for sel, _ in rules
             if _selector_has_bare_input(sel)
             and "type" not in sel and ":not(" not in sel]
    type_specific = [sel for sel, _ in rules if re.search(r"input\s*\[\s*type", sel)]
    checkbox_covered = bool(broad) and not type_specific
    report["checks"].append({
        "id": "form.checkbox_broad_rule",
        "status": "WARN" if checkbox_covered else "PASS",
        "detail": ("type 한정 없는 input 규칙이 체크박스에도 적용됩니다: "
                   + "; ".join(s.strip()[:60] for s in broad[:4])
                   if checkbox_covered else
                   "type 한정 없는 input 규칙 없음 또는 타입별 규칙 존재"),
        "evidence": {"broadSelectors": [s.strip() for s in broad],
                     "typeSpecificSelectors": [s.strip() for s in type_specific]}})

    # 5) 입력·버튼 높이 추정 24px 미만 WARN
    font_m = SHORTHAND_FONT_RE.search(text)
    base_font = float(font_m.group(1)) if font_m else 15.0
    heights = []
    for sel, body in rules:
        if re.search(r"(?<![\w-])(input|select|button|textarea)(?![\w-])", sel):
            est, note = _estimate_height(body, base_font)
            heights.append((sel, est, note))
    low = [(s, e, n) for s, e, n in heights if e is not None and e < 24.0]
    known = [(s, e) for s, e, _ in heights if e is not None]
    report["checks"].append({
        "id": "form.control_height", "status": "WARN" if low else "PASS",
        "detail": ("24px 미만 추정 컨트롤: "
                   + "; ".join(f"{s.strip()[:50]}≈{e:.0f}px ({n})" for s, e, n in low[:4])
                   if low else
                   "모든 입력·버튼이 24px 이상으로 추정됩니다 — "
                   + "; ".join(f"{s.strip()[:40]}≈{e:.0f}px" for s, e in known[:4])),
        "evidence": {"basis": "padding+font-size+border 추정 — 실제 렌더링 아님"}})

    warn = any(c["status"] == "WARN" for c in report["checks"])
    report["status"] = "WARN" if warn else "PASS"
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="settings-page.css contrast/control static check (read-only)")
    ap.add_argument("--root", default=".", help="repo root (default: cwd)")
    ap.add_argument("--file", default=CSS_REL, help="repo-relative css path")
    ap.add_argument("--strict", action="store_true",
                    help="WARN이 있으면 exit 1 (기본은 관찰 전용 exit 0)")
    ap.add_argument("--json", action="store_true", dest="as_json")
    args = ap.parse_args(argv)
    report = run_check(Path(args.root).resolve(), args.file)
    print(json.dumps(report, ensure_ascii=True, indent=2))
    if args.strict and report["status"] == "WARN":
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
