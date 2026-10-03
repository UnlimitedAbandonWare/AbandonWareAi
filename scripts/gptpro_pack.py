#!/usr/bin/env python3
"""[USER-ONLY] Pack-GPTPro.bat의 실제 로직.

demo-1 소스를 비밀값 없이 골라 zipHome에 zip 한 개로 만든다. 사용자가 그 zip을
ChatGPT(GPT Pro)에 올려 분석을 맡긴다. **사용자 전용 수동 도구**: 에이전트는
사용자가 명시적으로 요청하지 않는 한 실행/수정/자동 호출하지 않는다.

안전 경계:
- 비밀값 파일(.env*, apikey*, shared.env, .secrets/, application-secrets.*,
  auth.json, credentials*, *.pem/key/p12/pfx/jks/keystore)은 이름 규칙으로만
  제외한다 — 열지도, 읽지도, 출력하지도 않는다.
- 내용 검사는 남을 파일에만 적용한다. 키 패턴이 걸리면 그 파일을 빼고
  경로+줄번호+패턴 이름만 보고한다. 값은 절대 출력하지 않는다.
- 쓰기 위치는 --out 폴더(기본 zipHome)와 %TEMP% 마커 파일뿐. 레포에는 쓰지
  않는다. git은 읽기 명령(ls-files/rev-parse/status)만 사용한다.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import zipfile
from datetime import datetime, timedelta, timezone
from itertools import count
from pathlib import Path

from awx_paths import resolve as _awx_resolve

try:
    import gptpro_pack_context as gpc
except ImportError:  # pragma: no cover - 직접 실행 시 scripts/가 sys.path[0]
    gpc = None

KST = timezone(timedelta(hours=9))
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_ROOT = SCRIPT_DIR.parent
GIT_CANDIDATES = ["git", str(_awx_resolve("git.exe"))]

# v2 맥락 프로필 전용 제외 패턴 (기존 프로필 파일 선택 불변).
VENDOR_PATH = re.compile(r"(?i)(?:^|/)vendor(?:/|$)|\.min\.js$")
VENDOR_NAME_VER = re.compile(r"^([A-Za-z][\w.+-]*?)[-.](\d[\w.]*?)(?:\.min)?\.js$")
LEGACY_NAME = re.compile(r"(?i)(legacy|deprecated|_old\b|backup|\.bak|\.orig$)")
FOCUS_TEST_SUFFIX = re.compile(r"(?i)test\.(java|kt)$")

# ---------------------------------------------------------------------------
# Exclusion rules (name/path only — contents are never read for these).
# Two tiers: anywhere-components (unambiguous vendor/build/tool dirs) and
# root-only dirs (generic names that collide with real packages, e.g.
# main/java/com/example/lms/service/verification/).
# ---------------------------------------------------------------------------
EXCLUDE_ANYWHERE = {
    ".git", ".next", "node_modules", "build", "__patch_drop__",
    "_patch_artifacts", "__reports__", ".secrets",
}
EXCLUDE_ANYWHERE_PREFIX = (".gradle",)
EXCLUDE_ROOT = {
    "data", "logs", "uploads", "var", "scratch", "out", "output", "bin",
    "db-ledger", "verification", "agent-prompts", ".codex", ".devin",
    ".grok", ".cline", ".windsurf", ".playwright-cli",
}
BAN_EXTS = {
    ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".der",
    ".db", ".log", ".zip", ".jar", ".class", ".onnx", ".bin",
    ".safetensors", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
    ".bmp", ".tif", ".tiff", ".mp4", ".mov", ".avi", ".mkv", ".webm",
    ".mp3", ".wav", ".ogg", ".m4a", ".woff", ".woff2", ".ttf", ".otf",
    ".eot", ".exe", ".dll", ".so", ".dylib", ".pdf",
}
BAN_EXT_SUFFIXES = (".mv.db", ".h2.db", ".trace.db")
SSH_LEAVES = {"id_rsa", "id_dsa", "id_ecdsa", "id_ed25519"}
ENV_TEMPLATES = {".env.example", ".env.sample", ".env.template"}
APIKEY_LEAF = re.compile(r"^(apikey|api-key)[^/]*\.(txt|ps1|env|json|ya?ml|properties)$")
CREDENTIALS_LEAF = re.compile(r"^credentials[^/]*\.(json|txt|env|ya?ml|properties|xml|ini)$")
TEMPLATE_LEAF = re.compile(r"(example|sample|template)")

# Content scan patterns — ported from scripts/git_secret_guard.ps1 plus the
# directive's additions (ghp_, github_pat_, xox[bap]-, AKIA, Bearer).
SECRET_PATTERNS = [
    ("openai", re.compile(r"(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{20,}")),
    ("google-ai", re.compile(r"(?<![A-Za-z0-9_-])AIza[0-9A-Za-z_-]{20,}")),
    ("groq", re.compile(r"(?<![A-Za-z0-9_-])gsk_[A-Za-z0-9_-]{20,}")),
    ("pinecone", re.compile(r"(?<![A-Za-z0-9_-])pcsk_[A-Za-z0-9_-]{20,}")),
    ("supabase-api-key",
     re.compile(r"(?<![A-Za-z0-9_-])sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}")),
    ("supabase-access-token",
     re.compile(r"(?<![A-Za-z0-9_-])sbp_[A-Za-z0-9_-]{10,}")),
    ("github-pat", re.compile(r"(?<![A-Za-z0-9_-])ghp_[A-Za-z0-9]{20,}")),
    ("github-fine-pat", re.compile(r"github_pat_[A-Za-z0-9_]{20,}")),
    ("slack-token", re.compile(r"(?<![A-Za-z0-9_-])xox[bap]-[A-Za-z0-9-]{10,}")),
    ("aws-access-key", re.compile(r"(?<![A-Za-z0-9_-])AKIA[0-9A-Z]{16}")),
    ("private-key",
     re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY(?: BLOCK)?-----")),
    ("bearer-token", re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/=-]{20,}")),
]
SENSITIVE_ASSIGN = re.compile(
    r"(?i)(?<![$A-Za-z0-9_-])(?:api[-_]?key|apikey|client[-_]?secret|"
    r"service[-_]?role(?:[-_]?key)?|owner[-_]?token|subscription[-_]?token|"
    r"authorization|bearer|password|secret|token)\b\s*[:=]\s*['\"]?"
    r"(?!\$\{)(?!__MISSING__)(?!<)"
    r"(?!(?:dummy|test|changeme|change-me|sk-local|ollama|null|none|missing)\b)"
    r"[A-Za-z0-9_./+=:-]{24,}"
)
ASSIGN_SCAN_EXTS = {".yml", ".yaml", ".properties", ".json", ".toml",
                    ".ini", ".env", ".txt", ".ps1", ".sh"}

DEFAULT_CONFIG = {
    "maxFileMb": 2.0,
    "maxZipMb": 64,
    "allowFiles": [],
    "defaultOut": "",
    "profiles": {
        "main": {"include": ["main/java", "main/resources"]},
        "core": {"extends": "main", "include": [
            "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
            "configs", "frontend/src", "frontend/scripts",
            "frontend/package.json", "frontend/next.config.mjs",
            "frontend/jsconfig.json", "agents.md", "README.md",
            ".env.example", "frontend/.env.example",
            "docs/PROJECT_STATUS.md", "docs/API_ROUTING_SPEC.md",
            "docs/architecture",
        ]},
        "full": {"extends": "core", "include": [
            {"path": "scripts", "ext": [".py", ".js", ".ps1", ".bat"]},
            {"path": "docs", "ext": [".md"]},
            "src/test",
        ]},
        # v2 (2026-10-03): core 파일 선택 + GPT Pro 맥락 섹션. vendor/min JS는
        # 새 프로필에서만 기본 제외 — 기존 main/core/full 선택 결과는 불변.
        "ctx": {"extends": "core", "include": [], "context": True},
        "brief": {"extends": "core", "include": [], "context": True,
                  "skeletonJava": True,
                  "excludePaths": ["frontend/", "main/resources/static/",
                                   "main/resources/templates/",
                                   "docs/PROJECT_STATUS.md",
                                   "docs/architecture/"]},
    },
}


def norm(rel: str) -> str:
    return rel.replace("\\", "/")


def run_git(root: Path, *args: str):
    """Read-only git call. Returns stdout str or None when git is unusable."""
    for exe in GIT_CANDIDATES:
        try:
            proc = subprocess.run([exe, "-C", str(root), *args],
                                  capture_output=True, timeout=120)
            if proc.returncode == 0:
                return proc.stdout.decode("utf-8", "replace")
        except (OSError, subprocess.TimeoutExpired):
            continue
    return None


def load_config(root: Path) -> dict:
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    cfg_path = root / "configs" / "gptpro-pack.json"
    try:
        file_cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
        if isinstance(file_cfg, dict):
            cfg.update({k: v for k, v in file_cfg.items() if k != "profiles"})
            if isinstance(file_cfg.get("profiles"), dict):
                cfg["profiles"] = file_cfg["profiles"]
    except (OSError, ValueError):
        pass
    return cfg


def resolve_include(profiles: dict, name: str):
    seen, include = set(), []
    cur = name
    while cur and cur not in seen:
        seen.add(cur)
        spec = profiles.get(cur)
        if not isinstance(spec, dict):
            return None
        include = list(spec.get("include") or []) + include
        cur = spec.get("extends")
    return include


def resolve_flags(profiles: dict, name: str):
    """extends 체인을 따라 프로필 플래그(context/skeletonJava)를 합친다."""
    flags = {}
    chain = []
    cur, seen = name, set()
    while cur and cur not in seen:
        seen.add(cur)
        spec = profiles.get(cur)
        if not isinstance(spec, dict):
            return flags
        chain.append(spec)
        cur = spec.get("extends")
    for spec in reversed(chain):
        for k in ("context", "skeletonJava", "excludePaths"):
            if k in spec:
                flags[k] = spec[k]
    return flags


def spec_match(spec, rel_l: str) -> bool:
    if isinstance(spec, str):
        s = spec.lower().strip("/")
        return rel_l == s or rel_l.startswith(s + "/")
    if isinstance(spec, dict):
        p = str(spec.get("path", "")).lower().strip("/")
        if not (rel_l == p or rel_l.startswith(p + "/")):
            return False
        exts = spec.get("ext")
        if exts:
            leaf = rel_l.rsplit("/", 1)[-1]
            return any(leaf.endswith(str(e).lower()) for e in exts)
        return True
    return False


def dir_block_reason(rel_l: str):
    parts = rel_l.split("/")
    if parts[0] in EXCLUDE_ROOT:
        return "root-dir:" + parts[0]
    if "/config/secrets/" in "/" + rel_l + "/" or parts[:2] == ["config", "secrets"]:
        return "dir:config/secrets"
    for comp in parts:
        if comp in EXCLUDE_ANYWHERE:
            return "dir:" + comp
        if comp.startswith(EXCLUDE_ANYWHERE_PREFIX):
            return "dir:" + comp
    return None


def name_block_reason(rel_l: str):
    leaf = rel_l.rsplit("/", 1)[-1]
    if leaf in ENV_TEMPLATES:
        return None
    template = bool(TEMPLATE_LEAF.search(leaf))
    if leaf == ".env" or leaf.startswith(".env."):
        return "env-file"
    if leaf == "shared.env":
        return "shared-env"
    if leaf.startswith("application-secrets") and not template:
        return "secret-config-file"
    if ".secret" in leaf:
        return "secret-name-file"
    if leaf == "auth.json":
        return "auth-file"
    if leaf == "apikey" or APIKEY_LEAF.match(leaf):
        return "apikey-file"
    if leaf == "credentials" or CREDENTIALS_LEAF.match(leaf):
        return "credentials-file"
    if leaf in SSH_LEAVES:
        return "ssh-key-file"
    for suf in BAN_EXT_SUFFIXES:
        if leaf.endswith(suf):
            return "db-file"
    dot = leaf.rfind(".")
    if dot > 0 and leaf[dot:] in BAN_EXTS:
        return "banned-ext:" + leaf[dot:]
    return None


def read_text(path: Path):
    """UTF-8-ish text or None for binary/unreadable. Never called on
    name-blocked files."""
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\x00" in data[:8192]:
        return None
    return data.decode("utf-8", "replace")


def scan_text(rel: str, text: str):
    """Returns list of (line_no, pattern_id). Values are never copied out."""
    hits = []
    leaf = rel.rsplit("/", 1)[-1]
    ext = "." + leaf.rsplit(".", 1)[-1] if "." in leaf else ""
    scan_assign = ext in ASSIGN_SCAN_EXTS or leaf.startswith(".env")
    for i, line in enumerate(text.splitlines(), 1):
        for pid, rx in SECRET_PATTERNS:
            if rx.search(line):
                hits.append((i, pid))
        if scan_assign and SENSITIVE_ASSIGN.search(line):
            hits.append((i, "sensitive-assignment"))
        if len(hits) >= 8:
            break
    return hits


def build_zip_name(profile: str, sha7: str, dirty: bool, stamp: str) -> str:
    name = f"demo1_{profile}_{stamp}_{sha7}"
    if dirty:
        name += "-dirty"
    return name + ".zip"


def next_free_path(path: Path) -> Path:
    if not path.exists():
        return path
    stem = path.name[:-4] if path.name.lower().endswith(".zip") else path.stem
    for i in count(2):
        cand = path.with_name(f"{stem}-{i}.zip")
        if not cand.exists():
            return cand


def mb(n: int) -> str:
    return f"{n / (1024 * 1024):.1f}MB"


def detect_facts(root: Path) -> dict:
    facts = {"springBoot": "?", "java": "?", "gradle": "?", "langchain4j": "?"}
    try:
        b = (root / "build.gradle.kts").read_text(encoding="utf-8", errors="replace")
        m = re.search(r'org\.springframework\.boot["\)\s]*version\s*"([\d.]+)"', b)
        if m:
            facts["springBoot"] = m.group(1)
        m = re.search(r"JavaLanguageVersion\.of\((\d+)\)", b) or \
            re.search(r"VERSION_(\d+)", b)
        if m:
            facts["java"] = m.group(1)
        m = re.search(r"langchain4j[^\n\"']*?([\d]+\.[\d.]+)", b, re.IGNORECASE)
        if m:
            facts["langchain4j"] = m.group(1)
    except OSError:
        pass
    try:
        w = (root / "gradle" / "wrapper" / "gradle-wrapper.properties") \
            .read_text(encoding="utf-8", errors="replace")
        m = re.search(r"gradle-([\d.]+)-", w)
        if m:
            facts["gradle"] = m.group(1)
    except OSError:
        pass
    return facts


def make_manifest(profile, sha, dirty, changed, included, excluded_counts,
                  secret_hits, oversized, kst_now, ctx=None) -> str:
    lines = [
        "# GPT Pro pack manifest", "",
        f"- createdKst: {kst_now}",
        f"- profile: {profile}",
        f"- gitHead: {sha}",
        f"- dirty: {'yes' if dirty else 'no'}",
        f"- included: {len(included)} files, {mb(sum(s for _, s in included))} uncompressed",
        "- excludedByReason: "
        + ", ".join(f"{k}={v}" for k, v in sorted(excluded_counts.items())),
        "", "## secret-suspect files excluded (path/line/pattern only — values never copied)",
    ]
    lines += [f"- {p}:{ln} {pid}" for p, ln, pid in secret_hits] or ["- (none)"]
    lines += ["", "## oversized files excluded"]
    lines += [f"- {p} ({s})" for p, s in oversized] or ["- (none)"]
    if ctx:
        lines += ["", "## context sections (생성 파일 — 시크릿 스캔 통과)",
                  "", "| file | bytes | ~tokens |", "|---|---:|---:|"]
        for name, size in (ctx.get("sections") or {}).items():
            lines.append(f"| {name} | {size} | ~{size // 4} |")
        sec_lines = ctx.get("secretLines") or []
        lines += ["", "### generated sections: secret-suspect lines dropped"]
        lines += [f"- {p}:{ln} {pid}" for p, ln, pid in sec_lines] or ["- (none)"]
        if ctx.get("scopeExcludedPaths"):
            lines += ["", "### profile scope exclusions (excludePaths)"]
            lines += [f"- {p}* ({ctx.get('scopeExcludedCount', 0)} files total)"
                      for p in ctx["scopeExcludedPaths"]]
        vend = ctx.get("vendorExcluded") or []
        if vend:
            lines += ["", "### vendor/min JS excluded (name/version only)"]
            lines += [f"- {n} ({v})" for n, v in vend]
        legacy = ctx.get("legacyCandidates") or []
        if legacy:
            lines += ["", "### legacy-named candidates "
                      + ("dropped via --drop-legacy" if ctx.get("dropLegacy") else "kept")]
            lines += [f"- {n}" for n in legacy]
        if ctx.get("focusTests"):
            lines += ["", "### focus-matched test files included"]
            lines += [f"- {n}" for n in ctx["focusTests"]]
        if ctx.get("skeletonFailed"):
            lines += ["", "### java skeleton failed → full text kept"]
            lines += [f"- {n}" for n in ctx["skeletonFailed"]]
        if ctx.get("skeletonDropped"):
            lines += ["", "### java skeleton dropped for size budget"]
            lines += [f"- {n}" for n in ctx["skeletonDropped"]]
        if ctx.get("diffNotes"):
            lines += ["", "### diff notes"]
            lines += [f"- {n}" for n in ctx["diffNotes"]]
        if ctx.get("truncated") or ctx.get("errors"):
            lines += ["", "### section generation notes"]
            lines += [f"- truncated: {n}" for n in ctx.get("truncated", [])]
            lines += [f"- error: {n}" for n in ctx.get("errors", [])]
    lines += ["", "## working-tree changes at pack time (names only)"]
    shown = changed[:200]
    lines += [f"- {n}" for n in shown] or ["- (clean)"]
    if len(changed) > len(shown):
        lines.append(f"- ... +{len(changed) - len(shown)} more")
    return "\n".join(lines) + "\n"


def _yaml_list(text: str, key: str) -> str:
    m = re.search(rf"{re.escape(key)}:\s*\[([^\]]*)\]", text)
    return " → ".join(x.strip().strip('"\'') for x in m.group(1).split(",") if x.strip()) if m else ""


def cost_order_text(root: Path) -> str:
    """비용/모델 순서를 configs SSOT에서 읽어 한 줄로. 하드코딩 금지(F5)."""
    parts = []
    try:
        t = (root / "configs" / "agent-api-spend-guard.yaml") \
            .read_text(encoding="utf-8", errors="replace")
        order = _yaml_list(t, "agent_spend_order")
        if order:
            parts.append(f"에이전트 작업 순서={order} (configs/agent-api-spend-guard.yaml)")
    except OSError:
        pass
    try:
        t = (root / "configs" / "api-routing.yaml") \
            .read_text(encoding="utf-8", errors="replace")
        m = re.search(r"^\s*order:\s*\[([^\]]*)\]", t, re.M)
        if m:
            order = " → ".join(x.strip() for x in m.group(1).split(",") if x.strip())
            parts.append(f"제품 런타임 라우팅={order} (configs/api-routing.yaml)")
    except OSError:
        pass
    return "; ".join(parts) or "비용 순서는 configs/ SSOT 참조"


def make_readme(root: Path, profile: str) -> str:
    f = detect_facts(root)
    cost = cost_order_text(root)
    return f"""# _README_FOR_GPTPRO

이 zip은 **AbandonWare demo-1** 소스 팩이다 (사용자 전용 Pack-GPTPro 도구로 생성, profile={profile}).

## 스택
- Java {f['java']} + Spring Boot {f['springBoot']} (Gradle {f['gradle']}, Kotlin DSL)
- LangChain4j {f['langchain4j']}, H2(file), 일부 Next.js 프론트/BFF (frontend/)

## 한 줄 설명
Conversate/ASR 전사 → 검색·보강 → LLM 힌트 생성 → Meta Ray-Ban Display 렌즈 표시를 하는
Dynamic RAG Orchestration Platform이다. {cost} — 세부는 _RULES_AND_ROLES.md(있으면)와 configs/ 참조.

## 폴더 지도
- `main/java` — Spring 백엔드 (com.example.lms, ai.abandonware.nova)
- `main/resources` — application*.yml/properties, static JS/CSS, templates, configs
- `frontend/` — Next.js App Router BFF (app/api 프록시 + chat 페이지)
- `scripts/` — 진단/검증/에이전트 도구 (full 프로필만)
- `configs/` — 라우팅·정책 YAML/JSON
- `docs/` — PROJECT_STATUS.md(대표 현황), API_ROUTING_SPEC.md, architecture/

## 포함/제외
- 비밀값 파일(.env*, apikey*, shared.env, .secrets/, application-secrets.*, 키 재질)은 제외됨.
- 키처럼 보이는 문자열이 내용에서 걸린 파일도 제외됨 — 목록은 `_MANIFEST.md` 참조.
- 빌드 산출물·node_modules·data/·agent-prompts/ 등은 제외됨.

## 질문 템플릿 (그대로 붙여 써도 됨)
1. "이 zip 기준으로 <분석 대상>을 분석하고 수정안을 파일:줄로 제시해 줘."
2. "변경 시 영향 받는 다른 파일도 함께 나열해 줘."
3. "비밀값/외부 의존이 필요한 부분은 추측 없이 명시적으로 표시해 줘."

zip 업로드가 실패하면 주요 파일 몇 개(예: build.gradle.kts, 해당 소스 파일)만 골라 직접 올려도 된다.
"""


def parse_porcelain(text: str):
    names = []
    for line in (text or "").splitlines():
        if len(line) < 4:
            continue
        n = line[3:]
        if " -> " in n:
            n = n.split(" -> ", 1)[1]
        names.append(n.strip().strip('"'))
    return names


def collect(root: Path, include_specs):
    """Returns (files, git_ok). files = rel posix paths matched by include."""
    listed = run_git(root, "ls-files", "-co", "--exclude-standard", "-z")
    git_ok = listed is not None
    if git_ok:
        rels = [x for x in listed.split("\0") if x]
    else:
        rels = []
        for dp, dns, fns in os.walk(root):
            rel_dir = os.path.relpath(dp, root)
            rel_dir = "" if rel_dir == "." else norm(rel_dir)
            keep = []
            for d in dns:
                cand = f"{rel_dir}/{d}".strip("/").lower()
                parts = cand.split("/")
                if parts[0] in EXCLUDE_ROOT or parts[0] in EXCLUDE_ANYWHERE \
                        or parts[0].startswith(EXCLUDE_ANYWHERE_PREFIX):
                    continue
                keep.append(d)
            dns[:] = keep
            for f in fns:
                rels.append(f"{rel_dir}/{f}".strip("/"))
    matched = []
    for rel in rels:
        rel_l = norm(rel).lower()
        if any(spec_match(s, rel_l) for s in include_specs):
            matched.append(norm(rel))
    return sorted(set(matched)), git_ok


def cmd_pack(args) -> int:
    root = Path(args.root).resolve() if args.root else DEFAULT_ROOT
    cfg = load_config(root)
    profiles = cfg.get("profiles") or {}
    profile = args.profile_opt or args.profile or "core"
    include_specs = resolve_include(profiles, profile)
    if include_specs is None:
        print(f"[gptpro-pack] unknown profile '{profile}'. "
              f"available: {', '.join(sorted(profiles))}", file=sys.stderr)
        return 2
    out_dir = Path(args.out or os.environ.get("GPTPRO_ZIP_HOME")
                   or cfg.get("defaultOut")
                   or str(Path.home() / "OneDrive" / "Desktop" / "zipHome"))
    max_file_mb = args.max_file_mb if args.max_file_mb is not None \
        else float(cfg.get("maxFileMb", 2.0))
    max_zip_mb = args.max_zip_mb if args.max_zip_mb is not None \
        else float(cfg.get("maxZipMb", 64))
    allow_files = {norm(p).lower() for p in cfg.get("allowFiles") or []}
    marker = Path(os.environ.get("TEMP", str(Path.home()))) / "gptpro_pack_last.txt"

    head = run_git(root, "rev-parse", "--short=7", "HEAD")
    sha7 = head.strip() if head and head.strip() else "nogit"
    porcelain = run_git(root, "status", "--porcelain") or ""
    changed = parse_porcelain(porcelain)
    dirty = bool(changed)

    candidates, git_ok = collect(root, include_specs)
    excluded = {"dir": 0, "name": 0, "oversized": 0, "content": 0}
    secret_hits, oversized, included = [], [], []
    for rel in candidates:
        rel_l = rel.lower()
        reason = dir_block_reason(rel_l)
        if reason:
            excluded["dir"] += 1
            continue
        reason = name_block_reason(rel_l)
        if reason:
            excluded["name"] += 1
            continue
        path = root / rel
        try:
            size = path.stat().st_size
        except OSError:
            continue
        if size > max_file_mb * 1024 * 1024:
            excluded["oversized"] += 1
            oversized.append((rel, mb(size)))
            continue
        text = read_text(path)
        if text is not None and rel_l not in allow_files:
            hits = scan_text(rel_l, text)
            if hits:
                excluded["content"] += 1
                secret_hits.extend((rel, ln, pid) for ln, pid in hits)
                continue
        included.append((rel, size))

    # ---- v2 맥락 프로필 필터 (context 플래그가 있는 새 프로필만; 기존 프로필 불변)
    flags = resolve_flags(profiles, profile)
    ctx_enabled = bool(flags.get("context"))
    skeleton_java = bool(flags.get("skeletonJava"))
    if ctx_enabled and gpc is None:
        print("[gptpro-pack] gptpro_pack_context.py 로드 실패 — scripts/에 파일 필요",
              file=sys.stderr)
        return 2
    focus = [k.strip() for k in re.split(r"[,\s]+", args.focus or "") if k.strip()]
    ctx_meta: dict = {}
    if ctx_enabled:
        # 프로필별 범위 축소 (excludePaths를 정의한 새 프로필만; 기존 프로필 불변)
        xpaths = [str(p).rstrip("/").lower()
                  for p in (flags.get("excludePaths") or [])]
        if xpaths:
            before = len(included)
            excluded_paths = []
            kept = []
            for r, s in included:
                rl = r.lower()
                if any(rl == xp or rl.startswith(xp + "/") for xp in xpaths):
                    excluded_paths.append(r)
                else:
                    kept.append((r, s))
            included = kept
            ctx_meta["scopeExcludedPaths"] = xpaths
            ctx_meta["scopeExcludedCount"] = len(excluded_paths)
            print(f"[gptpro-pack] scope-excluded: {len(excluded_paths)} files "
                  f"({len(xpaths)} excludePaths)")
        # vendor/min JS 기본 제외 (새 프로필만) — 이름·버전은 manifest로.
        vend = [(r, s) for r, s in included if VENDOR_PATH.search(r.lower())]
        if vend:
            vendor_list = []
            for rel, _ in vend:
                leaf = rel.rsplit("/", 1)[-1]
                m = VENDOR_NAME_VER.match(leaf)
                vendor_list.append((leaf, m.group(2) if m else "?"))
            ctx_meta["vendorExcluded"] = vendor_list
            vend_set = {r for r, _ in vend}
            included = [(r, s) for r, s in included if r not in vend_set]
            print(f"[gptpro-pack] vendor/min-js excluded: {len(vend)}")
        # legacy 후보 — --drop-legacy일 때만 실제 제외.
        legacy = [r for r, _ in included
                  if LEGACY_NAME.search(r.rsplit("/", 1)[-1])]
        if legacy:
            ctx_meta["legacyCandidates"] = legacy
            ctx_meta["dropLegacy"] = bool(args.drop_legacy)
            if args.drop_legacy:
                lset = set(legacy)
                included = [(r, s) for r, s in included if r not in lset]
                print(f"[gptpro-pack] legacy dropped: {len(legacy)}")
        # --focus: main 파일 매칭 + 짝인 *Test.java 전문 포함.
        if focus:
            focus_rels = {r for r, _ in included
                          if any(k.lower() in r.lower() for k in focus)}
            stems = {Path(r).stem.lower() for r in focus_rels}
            tdir = root / "src" / "test"
            focus_tests = []
            if tdir.is_dir():
                have = {r for r, _ in included}
                for f in sorted(tdir.rglob("*")):
                    if not f.is_file() or not FOCUS_TEST_SUFFIX.search(f.name):
                        continue
                    rel = norm(f.relative_to(root).as_posix())
                    stem = f.stem.lower()
                    base = stem[:-4] if stem.endswith("test") else stem
                    hit_kw = any(k.lower() in rel.lower() for k in focus)
                    if (hit_kw or base in stems or stem in stems) and rel not in have:
                        if name_block_reason(rel.lower()) or dir_block_reason(rel.lower()):
                            continue
                        try:
                            sz = f.stat().st_size
                        except OSError:
                            continue
                        if sz > max_file_mb * 1024 * 1024:
                            continue
                        text = read_text(f)
                        if text is not None and scan_text(rel.lower(), text):
                            continue
                        focus_tests.append(rel)
                        included.append((rel, sz))
            if focus_tests:
                included.sort(key=lambda t: t[0])
                ctx_meta["focusTests"] = focus_tests
            print(f"[gptpro-pack] focus={','.join(focus)} "
                  f"main-matched={len(focus_rels)} tests-added={len(focus_tests)}")

    kst_now = datetime.now(KST)
    stamp = kst_now.strftime("%Y%m%d-%H%M")
    total = sum(s for _, s in included)
    git_state = f"ok({sha7}{',dirty' if dirty else ''})" if git_ok \
        else "unavailable(fallback-walk; .gitignore approximated)"
    print(f"[gptpro-pack] profile={profile} git={git_state}")
    print(f"[gptpro-pack] files={len(included)} bytes={mb(total)}")
    print(f"[gptpro-pack] excluded: dir={excluded['dir']} "
          f"name={excluded['name']} oversized={excluded['oversized']} "
          f"content={excluded['content']}")
    for rel, ln, pid in secret_hits[:50]:
        print(f"[gptpro-pack] secret-suspect excluded: {rel}:{ln} pattern={pid}")
    for rel, s in oversized[:50]:
        print(f"[gptpro-pack] oversized excluded: {rel} ({s})")
    if args.list:
        for rel, _ in included:
            print(rel)

    if args.dry_run or args.list:
        print("[gptpro-pack] dry-run/list: no zip written")
        try:
            marker.write_text("DRYRUN", encoding="utf-8")
        except OSError:
            pass
        return 0

    # ---- v2 맥락 섹션 생성 (zip을 실제로 쓸 때만 — dry-run/list는 위에서 반환)
    skeleton_map: dict[str, str] = {}
    sections: list[tuple[str, str]] = []
    if ctx_enabled:
        # brief: java 골격화 (focus 매칭 파일은 전문 유지).
        if skeleton_java:
            focus_rels = {r for r, _ in included
                          if any(k.lower() in r.lower() for k in focus)} if focus else set()
            failed, skels = [], {}
            for rel, sz in included:
                if not rel.lower().endswith(".java"):
                    continue
                # brief: focus main 파일만 전문 유지 — src/test의 focus 테스트도 골격화
                if rel in focus_rels and not rel.startswith("src/test/"):
                    continue
                text = read_text(root / rel)
                if text is None:
                    continue
                body, ok = gpc.java_skeleton(text)
                if ok:
                    skels[rel] = body
                else:
                    failed.append(rel)
            # 크기 예산: 초과하면 가장 큰 골격부터 드롭(파일 자체를 뺌, manifest에 기록).
            budget = int(gpc.BRIEF_JAVA_BUDGET)
            total_sk = sum(len(b.encode("utf-8")) for b in skels.values())
            dropped = []
            while total_sk > budget and skels:
                big = max(skels, key=lambda r: len(skels[r].encode("utf-8")))
                total_sk -= len(skels.pop(big).encode("utf-8"))
                dropped.append(big)
            if dropped:
                dset = set(dropped)
                included = [(r, s) for r, s in included if r not in dset]
            skeleton_map = skels
            if failed:
                ctx_meta["skeletonFailed"] = failed
            if dropped:
                ctx_meta["skeletonDropped"] = dropped
            print(f"[gptpro-pack] skeleton: {len(skels)} ok, {len(failed)} failed->full, "
                  f"{len(dropped)} dropped(budget)")
        # 맥락 섹션 생성 — 최종 included 기준.
        in_scope = lambda rl: any(spec_match(s, rl) for s in include_specs)  # noqa: E731
        git_fn = lambda *a: run_git(root, *a)  # noqa: E731
        rel_list = [r for r, _ in included]
        sections, gen_meta = gpc.build_all(
            root, profile, in_scope, changed, git_fn, max_file_mb, focus,
            args.briefs, sha7, dirty, kst_now.strftime("%Y-%m-%d %H:%M KST"),
            rel_list, scan_text)
        ctx_meta.update(gen_meta)
        if ctx_meta.get("secretLines"):
            for name, ln, pid in ctx_meta["secretLines"]:
                print(f"[gptpro-pack] section secret-line dropped: {name}:{ln} pattern={pid}")

    out_dir.mkdir(parents=True, exist_ok=True)
    name = build_zip_name(profile, sha7, dirty, stamp)
    zp = next_free_path(out_dir / name)
    manifest = make_manifest(profile, sha7, dirty, changed, included,
                             excluded, secret_hits, oversized,
                             kst_now.strftime("%Y-%m-%d %H:%M KST"),
                             ctx=ctx_meta or None)
    readme = make_readme(root, profile)
    with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as z:
        for arc, text in sections:
            z.writestr(arc, text)
        for rel, _ in included:
            if rel in skeleton_map:
                z.writestr(rel, skeleton_map[rel])
            else:
                z.write(root / rel, rel)
        z.writestr("_MANIFEST.md", manifest)
        z.writestr("_README_FOR_GPTPRO.md", readme)
    try:
        (out_dir / (zp.stem + "_MANIFEST.md")).write_text(manifest, encoding="utf-8")
    except OSError:
        pass
    zip_mb = zp.stat().st_size / (1024 * 1024)
    print(f"[gptpro-pack] zip={zp} size={zip_mb:.1f}MB")
    if zip_mb > max_zip_mb:
        print(f"[gptpro-pack] WARNING: zip {zip_mb:.0f}MB > --max-zip-mb "
              f"{max_zip_mb:g}. ChatGPT hard cap is 512MB/file but large text "
              f"packs get truncated (2M tokens/doc). Try '--profile main' or "
              f"upload key files directly.")
    if profile == "full" and zip_mb > 50:
        print("[gptpro-pack] WARNING: full profile >50MB; consider 'core' or 'main'.")
    old = sorted(out_dir.glob("demo1_*.zip"))
    if len(old) > 10:
        print(f"[gptpro-pack] note: {len(old)} zips in {out_dir} — "
              f"오래된 zip 정리를 고려하세요.")
    try:
        marker.write_text(str(zp), encoding="utf-8")
    except OSError:
        pass
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(
        prog="gptpro_pack.py",
        description="[USER-ONLY] pack demo-1 source into a secret-free zip for GPT Pro")
    p.add_argument("profile", nargs="?", default=None,
                   help="main | core | full | ctx | brief (default: core)")
    p.add_argument("--profile", dest="profile_opt", default=None)
    p.add_argument("--root", default=None)
    p.add_argument("--out", default=None)
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--list", action="store_true",
                   help="print included file list; no zip")
    p.add_argument("--max-file-mb", type=float, default=None)
    p.add_argument("--max-zip-mb", type=float, default=None)
    p.add_argument("--focus", default=None,
                   help="ctx/brief: comma keywords — matching main files stay "
                        "full-text, paired *Test.java included")
    p.add_argument("--briefs", default=None, metavar="DIR",
                   help="ctx/brief: also excerpt newest PASTE_*.txt from DIR "
                        "(default off)")
    p.add_argument("--drop-legacy", action="store_true",
                   help="ctx/brief: exclude backup/legacy/deprecated-named files")
    p.add_argument("--no-pause", action="store_true",
                   help="consumed by Pack-GPTPro.bat; ignored here")
    p.add_argument("--no-explorer", action="store_true",
                   help="consumed by Pack-GPTPro.bat; ignored here")
    args = p.parse_args(argv)
    if args.profile and args.profile.startswith("-"):
        args.profile = None  # argparse should not reach here; safety
    return cmd_pack(args)


if __name__ == "__main__":
    raise SystemExit(main())
