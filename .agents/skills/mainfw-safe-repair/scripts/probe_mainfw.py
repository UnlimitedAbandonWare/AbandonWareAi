#!/usr/bin/env python3
"""Static probe for the mainfw Java/Spring source overlay.

The probe never prints property values or token-like literals. It reports only
paths, counts, key names, hashes, and structural evidence. It supports either:
  1) an extracted overlay with java/ + resources/, or
  2) a normal repository with src/main/java + src/main/resources.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any, Iterable

try:
    import yaml  # type: ignore
except Exception:
    yaml = None

STEREOTYPES = {"Component", "Service", "Repository", "Controller", "RestController", "Configuration"}
KNOWN_UNWIRED_ROOT = {
    "llm", "guard", "alias", "budget", "concurrency", "whitening", "expansion", "fusion", "probe"
}
KNOWN_UNWIRED_PLAN = {"when", "pipeline"}
SENSITIVE_KEY_RE = re.compile(r"(?i)(api[-_.]?key|secret|token|password|credential|private[-_.]?key)")
SECRET_SIGNATURES: list[tuple[str, re.Pattern[str]]] = [
    ("openai", re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b")),
    ("google", re.compile(r"\bAIza[0-9A-Za-z_-]{20,}\b")),
    ("groq", re.compile(r"\bgsk_[0-9A-Za-z_-]{20,}\b")),
    ("pinecone", re.compile(r"\bpcsk_[0-9A-Za-z_-]{20,}\b")),
    ("tavily", re.compile(r"\btvly-[0-9A-Za-z_-]{20,}\b")),
    ("jwt", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b")),
]


def sha(value: str, n: int = 12) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="ignore")).hexdigest()[:n]


def discover(root: Path) -> tuple[Path, Path, str]:
    candidates = [
        (root / "java", root / "resources", "overlay"),
        (root / "src/main/java", root / "src/main/resources", "standard"),
    ]
    for java_root, res_root, mode in candidates:
        if java_root.is_dir() and res_root.is_dir():
            return java_root, res_root, mode
    raise SystemExit(f"No Java/resources roots found under {root}")


def strip_comments(text: str) -> str:
    # Good enough for structural probing; strings are intentionally retained.
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def java_facts(java_root: Path) -> dict[str, Any]:
    files = sorted(java_root.rglob("*.java"))
    pkg_re = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
    public_type_re = re.compile(
        r"\bpublic\s+(?:(?:abstract|final|sealed|non-sealed|static)\s+)*(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)"
    )
    any_type_re = re.compile(
        r"\b(?:(?:abstract|final|sealed|non-sealed|static)\s+)*(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)"
    )
    type_with_anns_re = re.compile(
        r"(?P<anns>(?:\s*@(?:[\w.]+)(?:\s*\([^;{}]*?\))?\s*)+)"
        r"\s*(?:(?:public|protected|private|abstract|final|sealed|non-sealed|static)\s+)*"
        r"(?:class|record|interface|enum)\s+(?P<name>[A-Za-z_$][\w$]*)",
        re.S,
    )

    fqcn_map: dict[str, list[str]] = collections.defaultdict(list)
    simple: collections.Counter[str] = collections.Counter()
    mismatches: list[dict[str, str]] = []
    no_package: list[str] = []
    stereotype_entries: list[dict[str, str]] = []
    hardcoded_secret_hits: list[dict[str, Any]] = []

    for path in files:
        rel = path.relative_to(java_root).as_posix()
        raw = path.read_text(encoding="utf-8", errors="ignore")
        clean = strip_comments(raw)
        pkg_m = pkg_re.search(clean)
        type_m = public_type_re.search(clean) or any_type_re.search(clean)
        if not pkg_m:
            no_package.append(rel)
        if pkg_m and type_m:
            pkg, name = pkg_m.group(1), type_m.group(1)
            fqcn = f"{pkg}.{name}"
            fqcn_map[fqcn].append(rel)
            simple[name] += 1
            expected = f"{pkg.replace('.', '/')}/{name}.java"
            if not rel.endswith(expected):
                mismatches.append({"path": rel, "declared_fqcn": fqcn, "expected_suffix": expected})

        if pkg_m:
            for tm in type_with_anns_re.finditer(clean):
                anns, name = tm.group("anns"), tm.group("name")
                found: list[tuple[str, str]] = []
                for am in re.finditer(r"@([\w.]+)(?:\s*\((.*?)\))?", anns, flags=re.S):
                    short = am.group(1).split(".")[-1]
                    if short in STEREOTYPES:
                        found.append((short, am.group(2) or ""))
                if not found:
                    continue
                explicit = ""
                for _, arg in found:
                    mm = re.search(r"(?:^|\bvalue\s*=\s*)[\"']([^\"']+)[\"']", arg.strip())
                    if mm:
                        explicit = mm.group(1)
                        break
                bean = explicit or (name[:1].lower() + name[1:])
                stereotype_entries.append(
                    {"bean": bean, "fqcn": f"{pkg_m.group(1)}.{name}", "path": rel, "annotation": found[0][0]}
                )
                break

        for line_no, line in enumerate(raw.splitlines(), 1):
            for label, pattern in SECRET_SIGNATURES:
                if pattern.search(line):
                    hardcoded_secret_hits.append({"path": rel, "line": line_no, "signature": label})

    duplicate_fqcn = {k: v for k, v in fqcn_map.items() if len(v) > 1}
    duplicate_simple_top = [
        {"name": name, "count": count}
        for name, count in simple.most_common(40)
        if count > 1
    ]

    def collisions(prefixes: list[str]) -> list[dict[str, Any]]:
        by_name: dict[str, list[dict[str, str]]] = collections.defaultdict(list)
        for entry in stereotype_entries:
            fqcn = entry["fqcn"]
            if any(fqcn == p or fqcn.startswith(p + ".") for p in prefixes):
                by_name[entry["bean"]].append(entry)
        return [
            {"bean": bean, "classes": vals}
            for bean, vals in sorted(by_name.items())
            if len(vals) > 1
        ]

    return {
        "java_files": len(files),
        "duplicate_fqcn": duplicate_fqcn,
        "duplicate_simple_name_count": sum(1 for c in simple.values() if c > 1),
        "duplicate_simple_name_top": duplicate_simple_top,
        "package_path_mismatches": mismatches,
        "no_package_files": no_package,
        "hardcoded_secret_signature_hits": hardcoded_secret_hits,
        "bean_collisions": {
            "LmsApplication": collisions(["com.example.lms", "com.nova.protocol"]),
            "AgentApplication": collisions(["com.abandonware.ai.agent", "com.example.lms"]),
        },
    }


def javac_parse(java_root: Path) -> dict[str, Any]:
    helper = r'''
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

public class MainfwJavaParseProbe {
  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    List<Path> paths;
    try (var stream = Files.walk(root)) {
      paths = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      System.out.println("{\"available\":false,\"reason\":\"no_system_compiler\"}");
      return;
    }
    DiagnosticCollector<JavaFileObject> dc = new DiagnosticCollector<>();
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(dc, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(paths);
      JavacTask task = (JavacTask) compiler.getTask(null, fm, dc, List.of("-proc:none"), null, units);
      task.parse();
    }
    long errors = dc.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
    System.out.print("{\"available\":true,\"files\":" + paths.size() + ",\"parse_errors\":" + errors + ",\"diagnostics\":[");
    boolean first = true;
    for (var d : dc.getDiagnostics()) {
      if (d.getKind() != Diagnostic.Kind.ERROR) continue;
      if (!first) System.out.print(",");
      first = false;
      String src = d.getSource() == null ? "" : d.getSource().getName().replace("\\", "/");
      String msg = d.getMessage(Locale.ROOT).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
      System.out.print("{\"sourceHash\":\"" + hex(src) + "\",\"line\":" + d.getLineNumber() + ",\"message\":\"" + msg + "\"}");
    }
    System.out.println("]}");
  }
  static String hex(String s) throws Exception {
    var md = java.security.MessageDigest.getInstance("SHA-256");
    byte[] dig = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var out = new StringBuilder();
    for (int i=0; i<6; i++) out.append(String.format("%02x", dig[i]));
    return out.toString();
  }
}
'''
    try:
        with tempfile.TemporaryDirectory(prefix="mainfw-javac-probe-") as td:
            td_path = Path(td)
            source = td_path / "MainfwJavaParseProbe.java"
            source.write_text(helper, encoding="utf-8")
            cp = subprocess.run(["javac", str(source)], cwd=td, text=True, capture_output=True, timeout=60)
            if cp.returncode != 0:
                return {"available": False, "reason": "helper_compile_failed", "stderr_hash": sha(cp.stderr)}
            rp = subprocess.run(
                ["java", "-cp", td, "MainfwJavaParseProbe", str(java_root)],
                cwd=td,
                text=True,
                capture_output=True,
                timeout=300,
            )
            if rp.returncode != 0:
                return {"available": False, "reason": "helper_run_failed", "stderr_hash": sha(rp.stderr)}
            return json.loads(rp.stdout)
    except FileNotFoundError:
        return {"available": False, "reason": "javac_or_java_not_found"}
    except subprocess.TimeoutExpired:
        return {"available": False, "reason": "javac_parse_timeout"}
    except Exception as exc:
        return {"available": False, "reason": type(exc).__name__}


class UniqueKeyLoader(yaml.SafeLoader if yaml else object):  # type: ignore[misc]
    pass


def _construct_mapping(loader: Any, node: Any, deep: bool = False) -> Any:
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ValueError(f"duplicate key: {key}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


if yaml:
    UniqueKeyLoader.add_constructor(  # type: ignore[attr-defined]
        yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping
    )


def resource_facts(res_root: Path) -> dict[str, Any]:
    yaml_files = sorted([*res_root.rglob("*.yml"), *res_root.rglob("*.yaml")])
    json_files = sorted(res_root.rglob("*.json"))
    yaml_errors: list[dict[str, str]] = []
    json_errors: list[dict[str, str]] = []
    parsed_yaml: dict[str, Any] = {}
    if yaml:
        for p in yaml_files:
            rel = p.relative_to(res_root).as_posix()
            try:
                parsed_yaml[rel] = yaml.load(p.read_text(encoding="utf-8-sig", errors="strict"), Loader=UniqueKeyLoader)
            except Exception as exc:
                yaml_errors.append({"path": rel, "error_type": type(exc).__name__})
    for p in json_files:
        rel = p.relative_to(res_root).as_posix()
        try:
            json.loads(p.read_text(encoding="utf-8-sig", errors="strict"))
        except Exception as exc:
            json_errors.append({"path": rel, "error_type": type(exc).__name__})

    plans: list[dict[str, Any]] = []
    plans_dir = res_root / "plans"
    if yaml and plans_dir.is_dir():
        for p in sorted([*plans_dir.glob("*.yml"), *plans_dir.glob("*.yaml")]):
            rel = p.relative_to(res_root).as_posix()
            data = parsed_yaml.get(rel)
            if not isinstance(data, dict):
                continue
            plan = data.get("plan") if isinstance(data.get("plan"), dict) else {}
            unwired = [k for k in KNOWN_UNWIRED_ROOT if k in data]
            unwired += [f"plan.{k}" for k in KNOWN_UNWIRED_PLAN if k in plan]
            plans.append({
                "file": rel,
                "id": str(data.get("id") or plan.get("id") or p.stem),
                "unwired_keys": sorted(unwired),
                "has_chain_metadata": "chain" in data,
            })

    auto_imports_path = res_root / "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    auto_imports = []
    if auto_imports_path.exists():
        auto_imports = [
            line.strip() for line in auto_imports_path.read_text(encoding="utf-8-sig").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
    expected = [
        "ai.abandonware.nova.autoconfig.NovaFailurePatternAutoConfiguration",
        "ai.abandonware.nova.autoconfig.NovaZero100AutoConfiguration",
    ]

    return {
        "yaml_available": yaml is not None,
        "yaml_files": len(yaml_files),
        "yaml_errors": yaml_errors,
        "json_files": len(json_files),
        "json_errors": json_errors,
        "plans": plans,
        "auto_configuration_imports": auto_imports,
        "missing_expected_auto_configurations": [x for x in expected if x not in auto_imports],
    }


def flatten_yaml(value: Any, prefix: str = "") -> dict[str, Any]:
    out: dict[str, Any] = {}
    if isinstance(value, dict):
        for key, child in value.items():
            p = f"{prefix}.{key}" if prefix else str(key)
            out.update(flatten_yaml(child, p))
    else:
        out[prefix] = value
    return out


def property_facts(res_root: Path) -> dict[str, Any]:
    props_path = res_root / "application.properties"
    yml_path = res_root / "application.yml"
    props: dict[str, str] = {}
    duplicate_props: list[str] = []
    if props_path.exists():
        logical = ""
        for raw in props_path.read_text(encoding="utf-8-sig", errors="ignore").splitlines():
            line = raw.rstrip()
            if logical:
                logical += line.lstrip()
            else:
                logical = line
            if logical.endswith("\\"):
                logical = logical[:-1]
                continue
            s = logical.strip()
            logical = ""
            if not s or s.startswith("#") or s.startswith("!"):
                continue
            m = re.match(r"([^:=\s]+)\s*[:=]\s*(.*)$", s)
            if not m:
                continue
            key, value = m.group(1), m.group(2)
            if key in props:
                duplicate_props.append(key)
            props[key] = value
    yml_flat: dict[str, Any] = {}
    if yaml and yml_path.exists():
        try:
            obj = yaml.safe_load(yml_path.read_text(encoding="utf-8-sig")) or {}
            yml_flat = flatten_yaml(obj)
        except Exception:
            pass
    overlap = []
    for key in sorted(set(props) & set(yml_flat)):
        pv, yv = str(props[key]), str(yml_flat[key])
        overlap.append({
            "key": key,
            "property_value_hash": sha(pv),
            "yaml_value_hash": sha(yv),
            "same_text": pv == yv,
            "sensitive_key": bool(SENSITIVE_KEY_RE.search(key)),
        })
    critical = [
        "local-llm.base-url", "kakao.redirect-uri", "ocr.enabled",
        "ocr.min-confidence", "retrieval.vector.enabled"
    ]
    return {
        "application_properties_keys": len(props),
        "duplicate_property_keys": sorted(set(duplicate_props)),
        "yaml_properties_overlap_count": len(overlap),
        "critical_overlaps": [x for x in overlap if x["key"] in critical],
    }


def wiring_facts(java_root: Path) -> dict[str, Any]:
    java_texts: dict[str, str] = {
        p.relative_to(java_root).as_posix(): p.read_text(encoding="utf-8", errors="ignore")
        for p in java_root.rglob("*.java")
    }
    add_refs = [
        {"path": rel, "contains_canonical_rulebreak": "addInterceptor(ruleBreakInterceptor)" in text}
        for rel, text in java_texts.items()
        if "addInterceptor" in text
    ]
    unsafe = []
    for rel, text in java_texts.items():
        if "com/abandonwareai/zerobreak" in rel and "setZeroBreakEnabled(true)" in text:
            unsafe.append(rel)
    plan_dsl_not_used = [
        rel for rel, text in java_texts.items()
        if 'planDsl.status", "not_used"' in text or 'planDsl.status", "not_used:' in text
    ]
    return {
        "mvc_interceptor_registration_files": add_refs,
        "canonical_rulebreak_registered": any(x["contains_canonical_rulebreak"] for x in add_refs),
        "unsafe_nonblank_token_interceptors": unsafe,
        "plan_dsl_not_used_markers": plan_dsl_not_used,
    }


def build_test_facts(root: Path) -> dict[str, Any]:
    build_names = {
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradlew", "mvnw"
    }
    build_files = [p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() and p.name in build_names]
    tests = [
        p.relative_to(root).as_posix()
        for p in root.rglob("*.java")
        if p.name.endswith(("Test.java", "Tests.java", "IT.java")) or "/test/" in p.as_posix()
    ]
    return {"build_files": sorted(build_files), "test_sources": sorted(tests)}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="Extracted overlay or repository root")
    ap.add_argument("--output", help="Write JSON to this path; stdout when omitted")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    java_root, res_root, mode = discover(root)
    report = {
        "root_hash": sha(str(root)),
        "mode": mode,
        "java_root": java_root.relative_to(root).as_posix(),
        "resources_root": res_root.relative_to(root).as_posix(),
        "java": java_facts(java_root),
        "javac_parse": javac_parse(java_root),
        "resources": resource_facts(res_root),
        "properties": property_facts(res_root),
        "wiring": wiring_facts(java_root),
        "build_and_tests": build_test_facts(root),
    }
    encoded = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(encoded + "\n", encoding="utf-8")
        print(output)
    else:
        print(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

