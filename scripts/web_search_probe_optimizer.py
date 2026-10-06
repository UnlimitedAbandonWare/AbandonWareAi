#!/usr/bin/env python3
"""Specialized web-search probe optimizer for agentic vibe coding.

Turns a raw error/log dump into a sanitized technical fingerprint and four
specialized probe plates (T1 official docs, T2 GitHub issues/PRs, T3 minimal
repro, T4 adversarial cross-check) with Exa-ready query payloads, then merges
returned results into a <=3000-char golden context markdown.

Subcommands (all print one JSON object; exit 0 ok, 2 usage):
    fingerprint "<error text>" | --error-file <path>
    probe       "<error text>" | --error-file <path>
    synthesize  --results-file <results.json> [--error-file <path>] [--max-chars 3000]
    demo

Never calls a network: query bundles are recommendations for the caller's
own Exa/web-search tools. Local paths and secret-looking values are masked
before they can enter a generated query.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

SCHEMA = "awx.web-search-probe.v1"
DEFAULT_MAX_CHARS = 3000

# --- redaction --------------------------------------------------------------

REDACTION_PATTERNS = [
    # local absolute paths (windows drive, UNC, unix homes) -> <LOCAL_PATH>
    (re.compile(r"[A-Za-z]:\\[^\s\"'<>|]+"), "<LOCAL_PATH>"),
    (re.compile(r"\\\\[^\s\"'<>|]+"), "<LOCAL_PATH>"),
    (re.compile(r"/(?:home|users|mnt|var|opt|tmp)/[^\s\"'<>|]+", re.I), "<LOCAL_PATH>"),
    # secret-shaped values -> <SECRET>
    (re.compile(r"\bsk-[A-Za-z0-9_\-]{6,}"), "<SECRET>"),
    (re.compile(r"\b(?:xox[baprs]|ghp|gho|glpat|AIza|ya29)[A-Za-z0-9_\-]{6,}"), "<SECRET>"),
    (re.compile(r"eyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}"), "<SECRET>"),
    (re.compile(r"(Bearer\s+)[A-Za-z0-9._\-+/=]{8,}", re.I), r"\1<SECRET>"),
    (re.compile(r"((?:api[_-]?key|access[_-]?token|refresh[_-]?token|secret|"
                r"password|passwd|pwd|credential)\s*[:=]\s*[\"']?)"
                r"[^\s,\"'}\]]{4,}", re.I), r"\1<SECRET>"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "<SECRET>"),
]


def redact(text: str) -> str:
    out = text or ""
    for pattern, repl in REDACTION_PATTERNS:
        out = pattern.sub(repl, out)
    return out


# --- fingerprint ------------------------------------------------------------

_FRAMEWORK_PATTERNS = [
    ("spring boot", [r"spring[- ]?boot[:\- ]?([\d]+\.[\d.]+)",
                     r"Spring Boot v([\d]+\.[\d.]+)",
                     r"org\.springframework\.boot[:\-]([\d]+\.[\d.]+)"]),
    ("langchain4j", [r"langchain4j[^\d]{0,8}([\d]+\.[\d.]+)",
                     r"dev\.langchain4j"]),
    ("gradle", [r"Gradle ([\d]+\.[\d.]+)", r"gradle-([\d]+\.[\d.]+)\."]),
    ("java", [r"java version \"([\d._]+)\"", r"openjdk[^\d]{0,4}([\d]+\.[\d._]+)",
              r"Java ([\d]+\.[\d._]+)"]),
    ("kotlin", [r"kotlin[:\- ]?([\d]+\.[\d.]+)"]),
    ("maven", [r"Apache Maven ([\d]+\.[\d.]+)"]),
    ("node", [r"node(?:\.js)?[:\- ]?v?([\d]+\.[\d.]+)"]),
    ("python", [r"Python ([\d]+\.[\d.]+)"]),
    ("react", [r"react[:\-@ ]?([\d]+\.[\d.]+)"]),
    ("springframework", [r"springframework[:\-]([\d]+\.[\d.]+)"]),
]

_DOC_DOMAINS = {
    "spring boot": ["docs.spring.io"],
    "springframework": ["docs.spring.io"],
    "langchain4j": ["docs.langchain4j.dev"],
    "gradle": ["docs.gradle.org"],
    "java": ["docs.oracle.com"],
    "kotlin": ["kotlinlang.org"],
    "maven": ["maven.apache.org"],
    "node": ["nodejs.org"],
    "python": ["docs.python.org"],
    "react": ["react.dev"],
}

_EXCEPTION_RE = re.compile(
    r"((?:[a-zA-Z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error|Throwable|Failure))")
_FRAME_RE = re.compile(r"\bat\s+([\w.$]+)\.([\w$<>]+)\(([^)]*)\)")
_FRAME_SKIP = ("java.", "jdk.", "sun.", "org.junit", "kotlin.", "scala.")


def extract_fingerprint(text: str) -> dict:
    """Pull the technical fingerprint from a raw error/log dump."""
    raw = text or ""
    clean = redact(raw)

    exceptions = _EXCEPTION_RE.findall(clean)
    caused = [m for line in clean.splitlines()
              if "caused by" in line.lower()
              for m in _EXCEPTION_RE.findall(line)]
    exception = (caused[-1] if caused else (exceptions[-1] if exceptions else None))
    short = exception.rsplit(".", 1)[-1] if exception else None

    message = None
    if exception:
        for line in clean.splitlines():
            idx = line.find(exception)
            if idx < 0:
                continue
            tail = line[idx + len(exception):].lstrip(" :")
            if not tail:
                continue
            if "caused by" in line.lower():
                message = tail[:300]
                break
            if message is None:
                message = tail[:300]

    frames = []
    for cls, meth, loc in _FRAME_RE.findall(clean):
        if any(cls.startswith(p) for p in _FRAME_SKIP):
            continue
        frames.append({"classFqn": cls, "method": meth, "location": loc})
    failed = frames[0] if frames else None

    # Order frameworks by proximity of their nearest mention to the exception:
    # the stack implicated next to the failure beats a version line elsewhere.
    exc_idx = clean.find(exception) if exception else -1
    candidates = []
    for name, patterns in _FRAMEWORK_PATTERNS:
        version = None
        pos = -1
        for pat in patterns:
            m = re.search(pat, clean, re.I)
            if not m:
                continue
            if pos < 0:
                pos = m.start()
            if m.groups() and version is None:
                version = m.group(1)
        if pos >= 0:
            dist = abs(pos - exc_idx) if exc_idx >= 0 else pos
            candidates.append((dist, {"name": name, "version": version,
                                      "docDomains": _DOC_DOMAINS.get(name, [])}))
    candidates.sort(key=lambda pair: pair[0])
    seen = set()
    frameworks = []
    for _, fw in candidates:
        if fw["name"] not in seen:
            seen.add(fw["name"])
            frameworks.append(fw)

    return {
        "schemaVersion": SCHEMA,
        "exceptionFqcn": exception,
        "exceptionShort": short,
        "message": message,
        "failedFrame": failed,
        "frameworks": frameworks,
        "redactedLength": len(clean),
        "originalLength": len(raw),
        "redactionApplied": clean != raw,
    }


# --- probe plates ------------------------------------------------------------

def _fw_rank(fp: dict, fw: dict) -> int:
    """Relevance of a detected framework to the failure itself: name tokens
    appearing in the exception/message/failed frame outrank mere presence."""
    hay = " ".join(filter(None, [
        fp.get("exceptionFqcn") or "", fp.get("message") or "",
        (fp.get("failedFrame") or {}).get("classFqn") or ""])).lower()
    score = sum(2 for token in re.split(r"[\s\-]+", (fw.get("name") or ""))
                if token and token in hay)
    if fw.get("version"):
        score += 1
    return score


def _terms(fp: dict) -> dict:
    exc = fp.get("exceptionShort") or "error"
    meth = (fp.get("failedFrame") or {}).get("method") or ""
    fws = fp.get("frameworks") or []
    primary_fw = max(fws, key=lambda f: _fw_rank(fp, f)) if fws else {
        "name": "", "version": None, "docDomains": []}
    msg = (fp.get("message") or "").strip()
    msg_terms = " ".join(msg.split()[:6]) if msg else ""
    return {"exc": exc, "meth": meth, "fw": primary_fw,
            "fws": fws, "msg": msg_terms}


def _exa(query: str, category: str | None = None,
         domains: list[str] | None = None) -> dict:
    payload = {"engine": "exa", "query": query, "type": "deep",
               "contents": {"highlights": True}}
    if category:
        payload["category"] = category
    if domains:
        payload["includeDomains"] = domains
    return payload


def build_probe_plates(fp: dict) -> list[dict]:
    """Four specialized probe plates for one fingerprint."""
    t = _terms(fp)
    exc, meth, msg = t["exc"], t["meth"], t["msg"]
    fw = t["fw"]
    fw_name, fw_ver = fw.get("name") or "", fw.get("version")
    ver = fw_ver or ""
    core = " ".join(x for x in [exc, msg] if x).strip() or "exception"

    t1_queries, domains = [], list(fw.get("docDomains") or [])
    if domains:
        q = f"{core} {fw_name} {ver}".strip()
        t1_queries.append(_exa(f"{q} site:{domains[0]}", "documentation", domains))
        t1_queries.append({"engine": "web", "query": f"{q} site:{domains[0]}"})
    else:
        q = f"{core} official documentation".strip()
        t1_queries.append(_exa(q, "documentation"))
        t1_queries.append({"engine": "web", "query": q})

    gh_lib = fw_name.replace(" ", "-") or "library"
    t2_q = (f"site:github.com {gh_lib} {exc} {meth} {ver} "
            f"issue OR \"breaking change\" OR regression").strip()
    t2_queries = [
        _exa(f"{gh_lib} {exc} {ver} site:github.com", "github",
             ["github.com"]),
        {"engine": "web", "query": t2_q},
        {"engine": "web",
         "query": f"site:github.com {gh_lib} {exc} changelog breaking change"},
    ]

    anchor = meth or (fp.get("failedFrame") or {}).get("classFqn", "").rsplit(".", 1)[-1] or exc
    t3_q = f"site:github.com {gh_lib} {anchor} \"@Test\" minimal reproduction"
    t3_queries = [
        _exa(f"{gh_lib} {anchor} minimal reproduction test site:github.com",
             "github", ["github.com"]),
        {"engine": "web", "query": t3_q},
        {"engine": "web",
         "query": f"{fw_name} {exc} minimal reproducible example"},
    ]

    fw_list = " ".join(f"{f['name']} {f.get('version') or ''}".strip()
                       for f in t["fws"][:3])
    t4_q = (f"{exc} {fw_list} version compatibility conflict".strip())
    t4_queries = [
        _exa(t4_q),
        {"engine": "web",
         "query": f"{exc} {fw_name} {ver} incompatible version 0.x OR legacy"},
        {"engine": "web",
         "query": f"\"{exc}\" {ver} vs site:stackoverflow.com OR site:github.com"},
    ]

    return [
        {"id": "T1", "name": "Official Docs",
         "goal": "직격 공식 문서 — 벤더 문서 도메인 한정, 블로그 노이즈 차단",
         "queries": t1_queries},
        {"id": "T2", "name": "GitHub Issues/PRs",
         "goal": "동일 예외의 이슈/PR·breaking change 직격",
         "queries": t2_queries},
        {"id": "T3", "name": "Minimal Repro",
         "goal": "최소 재현/테스트 코드 탐침 — 재현 패턴 확보",
         "queries": t3_queries},
        {"id": "T4", "name": "Adversarial Cross-Check",
         "goal": "다중 출처 대조 + 비호환 버전(0.x vs 1.x) 회피 검증",
         "queries": t4_queries},
    ]


def probe_bundle(text: str) -> dict:
    fp = extract_fingerprint(text)
    return {
        "schemaVersion": SCHEMA,
        "fingerprint": fp,
        "plates": build_probe_plates(fp),
        "usage": "각 plate의 exa 페이로드를 Exa 검색에 투입하고, web 쿼리는 "
                 "일반 웹서치에 투입. 결과를 synthesize --results-file로 합성.",
    }


# --- synthesis ----------------------------------------------------------------

_PLATE_WEIGHT = {"T1": 40, "T2": 30, "T3": 20, "T4": 10}
_VERSION_RE = re.compile(r"\b(\d+\.\d+(?:\.\d+)*)\b")


def _result_version_flags(item: dict, fp: dict) -> list[str]:
    """Tag a result when it talks about a different major version than the
    fingerprint's pinned stack (e.g. 0.x doc against 1.0.1 runtime). Only
    versions appearing right after the framework name count."""
    flags = []
    text = " ".join(str(item.get(k) or "") for k in ("title", "text", "url"))
    for h in item.get("highlights") or []:
        text += " " + str(h)
    low = text.lower()
    for fw in fp.get("frameworks") or []:
        pinned = fw.get("version")
        name = (fw.get("name") or "").lower()
        if not pinned or not name or name not in low:
            continue
        pinned_major = pinned.split(".")[0]
        start = 0
        while True:
            idx = low.find(name, start)
            if idx < 0:
                break
            window = low[idx:idx + len(name) + 40]
            mismatch = next(
                (v for v in _VERSION_RE.findall(window)
                 if v != pinned and v.split(".")[0] != pinned_major), None)
            if mismatch:
                flags.append(
                    f"version-mismatch:{fw['name']}:{mismatch}!={pinned}")
                break
            start = idx + len(name)
    return flags


def _score_item(item: dict, fp: dict) -> float:
    base = float(item.get("score") or 0)
    plate = str(item.get("plate") or "").upper()
    base += _PLATE_WEIGHT.get(plate, 0)
    flags = _result_version_flags(item, fp)
    if flags:
        base -= 25 * len(flags)
    item["versionFlags"] = flags
    return base


def synthesize(results: list[dict], fp: dict | None = None,
               max_chars: int = DEFAULT_MAX_CHARS) -> dict:
    """Rank results (official-first, version-compatible-first) and emit one
    dense markdown block capped at max_chars."""
    fp = fp or {"frameworks": []}
    scored = []
    for r in results or []:
        item = dict(r)
        item["_probeScore"] = _score_item(item, fp)
        scored.append(item)
    scored.sort(key=lambda i: i["_probeScore"], reverse=True)

    lines = ["## Golden Context (web-search-probe)", ""]
    used = []
    for item in scored:
        title = str(item.get("title") or item.get("url") or "untitled")[:140]
        url = str(item.get("url") or "")
        plate = str(item.get("plate") or "?").upper()
        flags = item.get("versionFlags") or []
        snippet = ""
        for h in item.get("highlights") or []:
            snippet = str(h).strip().replace("\n", " ")[:240]
            if snippet:
                break
        if not snippet:
            snippet = str(item.get("text") or "")[:240].replace("\n", " ")
        flag_txt = f" [FLAGS:{','.join(flags)}]" if flags else ""
        entry = f"- [{plate}] {title} — {url}{flag_txt}"
        if snippet:
            entry += f"\n  {snippet}"
        used.append(entry)
    emitted = 0
    for entry in used:
        candidate = "\n".join(lines) + "\n" + entry + "\n"
        if len(candidate) > max_chars:
            break
        lines.append(entry)
        emitted += 1

    markdown = "\n".join(lines).rstrip() + "\n"
    if len(markdown) > max_chars:
        markdown = markdown[:max_chars - 20].rstrip() + "\n…(truncated)\n"
    return {
        "schemaVersion": SCHEMA,
        "markdown": markdown,
        "charCount": len(markdown),
        "maxChars": max_chars,
        "itemsUsed": emitted,
        "itemsTotal": len(results or []),
        "versionFlags": {str(i.get("url") or i.get("title")): i.get("versionFlags")
                         for i in scored if i.get("versionFlags")},
        # 랭킹만 재사용하는 호출자(예: gemini_search_worker 카드 출처 정렬)용
        # 구조화 필드 — markdown 외에 정렬된 title/url/flags를 그대로 제공.
        "rankedItems": [{"title": str(i.get("title") or i.get("url") or "untitled")[:140],
                         "url": str(i.get("url") or ""),
                         "plate": str(i.get("plate") or "").upper(),
                         "versionFlags": i.get("versionFlags") or []}
                        for i in scored],
    }


# --- demo ---------------------------------------------------------------------

# Secret-shaped demo values are assembled so no contiguous credential literal
# exists in this file (checkpoint secret-scan fixture style).
DEMO_ERROR = (
    r"""org.springframework.beans.factory.BeanCreationException: Error creating bean
with name 'ragChatService' defined in file
[C:\AbandonWare\demo-1\demo-1\src\main\java\io\abandonware\rag\RagChatService.class]:
Instantiation of bean failed; nested exception is java.lang.IllegalStateException
Caused by: java.lang.IllegalStateException: dev.langchain4j.model.chat.ChatModel
builder() requires modelName — langchain4j:1.0.1
    at io.abandonware.rag.RagChatService.buildModel(RagChatService.java:142)
    at java.base/java.lang.reflect.Method.invoke(Method.java:569)
"""
    + "api" + "_key=sk-" + "demoDeadBeef0123456789"
    + " Bearer " + "eyJhbGciOiJ9" + ".eyJzdWIiOiJ4In0.sig\n"
)

DEMO_RESULTS = [
    {"plate": "T2", "title": "langchain4j issue #1234: ChatModel builder "
     "requires modelName (1.0.x regression)", "score": 12,
     "url": "https://github.com/langchain4j/langchain4j/issues/1234",
     "highlights": ["Fixed in 1.0.1: builder() no longer infers modelName "
                    "from bean name; pass modelName explicitly."]},
    {"plate": "T1", "title": "LangChain4j 1.0.1 ChatModel docs",
     "score": 8, "url": "https://docs.langchain4j.dev/tutorials/chat-and-language-models",
     "highlights": ["ChatModel.builder().modelName(\"...\").build()"]},
    {"plate": "T4", "title": "Old 0.36.0 blog: ChatLanguageModel auto-wiring",
     "score": 15, "url": "https://blog.example.invalid/langchain4j-0.36",
     "highlights": ["langchain4j 0.36.0 auto-configures ChatLanguageModel "
                    "without modelName."]},
]


def demo() -> dict:
    fp = extract_fingerprint(DEMO_ERROR)
    return {
        "schemaVersion": SCHEMA,
        "mode": "demo",
        "fingerprint": fp,
        "plates": build_probe_plates(fp),
        "synthesis": synthesize(DEMO_RESULTS, fp),
    }


# --- cli ----------------------------------------------------------------------

def _read_text(args) -> str:
    if getattr(args, "error_file", None):
        return Path(args.error_file).read_text(encoding="utf-8", errors="replace")
    if getattr(args, "text", None):
        return args.text
    if not sys.stdin.isatty():
        return sys.stdin.read()
    return ""


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="web_search_probe_optimizer")
    sub = ap.add_subparsers(dest="action")

    def add_common(p):
        p.add_argument("text", nargs="?", default=None)
        p.add_argument("--error-file", default=None)

    p = sub.add_parser("fingerprint"); add_common(p)
    p = sub.add_parser("probe"); add_common(p)
    p = sub.add_parser("synthesize")
    p.add_argument("--error-file", default=None)
    p.add_argument("--results-file", required=True)
    p.add_argument("--max-chars", type=int, default=DEFAULT_MAX_CHARS)
    sub.add_parser("demo")

    args = ap.parse_args(argv)
    if not args.action:
        ap.print_help()
        return 2
    try:
        if args.action == "demo":
            result = demo()
        elif args.action == "fingerprint":
            result = extract_fingerprint(_read_text(args))
        elif args.action == "probe":
            result = probe_bundle(_read_text(args))
        elif args.action == "synthesize":
            results = json.loads(
                Path(args.results_file).read_text(encoding="utf-8"))
            if isinstance(results, dict):
                results = results.get("results") or []
            fp = (extract_fingerprint(_read_text(args))
                  if getattr(args, "error_file", None) else None)
            result = synthesize(results, fp, args.max_chars)
        else:
            return 2
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": str(error)}))
        return 2
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
