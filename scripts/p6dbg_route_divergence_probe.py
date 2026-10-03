#!/usr/bin/env python3
"""P6-D D2: Front-router divergence probe (offline, javac-only, no Spring).

Reused existing scripts / newly added:
  - Reused: isolation pattern from P6_REF_20261001/run_offline_probes.py
    (copy needed methods to a temp dir, javac there, never touch build/).
  - Reused: corpus from data/agent-handoff/devin-p6/complexity-corpus.jsonl (D6).
  - New: condition x question verdict matrix for QueryComplexityGate /
    ModelBasedQueryComplexityClassifier / RouterPolicy, compiled against
    minimal stubs (Spring annotations, slf4j, JevChoiceAdvisor.ChoiceObservation,
    SafeRedactor). Product sources are copied byte-identically; diffs are
    proven by sha256 before/after.

Conditions (8):
  g1_rules_only          QueryComplexityGate with classifier=null (rule path)
  g2_model_nopath        Gate + ModelBased with blank modelPath (modelUnavailable)
  g3_model_emptyfile     Gate + ModelBased with existing-but-empty model file
  g4_classifier_throws   Gate + classifier throwing RuntimeException
  r1_complexMainRequest  RouterPolicy.complexMainRequest(q, "GENERAL")
  r2_promote_tok_default shouldPromote(maxTokens=256 < 280 threshold)
  r3_promote_tok_raised  shouldPromote(maxTokens=800 > 280 threshold)
  r4_promote_quality     shouldPromote(preferred=QUALITY)

Exit: 0 PASS (matrix produced), 1 FAIL (probe run error), 2 missing input.
Output: route-divergence.json + .md under data/agent-handoff/devin-p6/.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "devin-p6.route-divergence.v1"

COPY_SOURCES = [
    "main/java/com/example/lms/service/rag/QueryComplexityGate.java",
    "main/java/com/example/lms/service/rag/QueryComplexityClassifier.java",
    "main/java/com/example/lms/service/rag/ModelBasedQueryComplexityClassifier.java",
    "main/java/com/example/lms/service/rag/JevComplexityClassifier.java",
    "main/java/com/example/lms/service/routing/RouterPolicy.java",
    "main/java/com/example/lms/service/routing/RouteSignal.java",
    "main/java/com/example/lms/config/MoeRoutingProps.java",
]

STUBS = {
    "org/springframework/stereotype/Component.java":
        "package org.springframework.stereotype;\n"
        "public @interface Component { String value() default \"\"; }\n",
    "org/springframework/beans/factory/annotation/Autowired.java":
        "package org.springframework.beans.factory.annotation;\n"
        "public @interface Autowired { boolean required() default true; }\n",
    "org/springframework/beans/factory/annotation/Value.java":
        "package org.springframework.beans.factory.annotation;\n"
        "public @interface Value { String value(); }\n",
    "org/springframework/boot/context/properties/ConfigurationProperties.java":
        "package org.springframework.boot.context.properties;\n"
        "public @interface ConfigurationProperties { String prefix() default \"\"; }\n",
    "jakarta/annotation/PostConstruct.java":
        "package jakarta.annotation;\n"
        "public @interface PostConstruct {}\n",
    "org/slf4j/Logger.java":
        "package org.slf4j;\n"
        "public interface Logger {\n"
        "  default void info(String f, Object... a) {}\n"
        "  default void debug(String f, Object... a) {}\n"
        "  default void warn(String f, Object... a) {}\n"
        "  default void error(String f, Object... a) {}\n"
        "}\n",
    "org/slf4j/LoggerFactory.java":
        "package org.slf4j;\n"
        "public final class LoggerFactory {\n"
        "  private static final Logger NOP = new Logger() {};\n"
        "  public static Logger getLogger(Class<?> c) { return NOP; }\n"
        "}\n",
    "com/example/lms/assist/JevChoiceAdvisor.java":
        "package com.example.lms.assist;\n"
        "import java.util.OptionalDouble;\n"
        "public final class JevChoiceAdvisor {\n"
        "  public record ChoiceObservation(String choice, OptionalDouble probability,\n"
        "      boolean schemaValid, boolean confidenceAccepted) {}\n"
        "}\n",
    "com/example/lms/trace/SafeRedactor.java":
        "package com.example.lms.trace;\n"
        "public final class SafeRedactor {\n"
        "  public static String traceLabelOrFallback(String s, String fb) {\n"
        "    return s == null ? fb : s;\n"
        "  }\n"
        "}\n",
}

PROBE_MAIN = r"""
package com.example.lms.service.rag;

import com.example.lms.service.routing.RouteSignal;
import com.example.lms.service.routing.RouterPolicy;
import com.example.lms.config.MoeRoutingProps;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ProbeMain {

    static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    static String safeAssess(QueryComplexityGate g, String q) {
        try {
            return String.valueOf(g.assess(q));
        } catch (Throwable t) {
            return "EXCEPTION:" + t.getClass().getSimpleName();
        }
    }

    static ModelBasedQueryComplexityClassifier modelClassifier(String path) throws Exception {
        ModelBasedQueryComplexityClassifier m = new ModelBasedQueryComplexityClassifier();
        set(m, "modelPath", path);
        Method init = ModelBasedQueryComplexityClassifier.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(m);
        return m;
    }

    static RouterPolicy policy() throws Exception {
        RouterPolicy p = new RouterPolicy(new MoeRoutingProps());
        set(p, "tokensThreshold", 280);
        set(p, "complexityThreshold", 0.55d);
        set(p, "uncertaintyThreshold", 0.35d);
        set(p, "webEvidenceThreshold", 0.55d);
        set(p, "elevateOnRigidTemp", true);
        set(p, "upgradeThreshold", 0.62d);
        set(p, "margin", 0.08d);
        return p;
    }

    static RouteSignal sig(int maxTokens, RouteSignal.Preference pref) {
        return new RouteSignal(0.1, 0.0, 0.1, 0.1,
                RouteSignal.Intent.GENERAL, RouteSignal.Verbosity.NORMAL,
                maxTokens, pref, "probe");
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        List<String> qs = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) qs.add(line);
        }
        String emptyModel = args[1];

        QueryComplexityClassifier throwing = q -> { throw new RuntimeException("boom"); };
        RouterPolicy rp = policy();

        for (int i = 0; i < qs.size(); i++) {
            String q = qs.get(i);
            String g1 = safeAssess(new QueryComplexityGate(), q);

            QueryComplexityGate gate2 = new QueryComplexityGate();
            set(gate2, "classifier", modelClassifier(""));
            String g2 = safeAssess(gate2, q);

            QueryComplexityGate gate3 = new QueryComplexityGate();
            set(gate3, "classifier", modelClassifier(emptyModel));
            String g3 = safeAssess(gate3, q);

            QueryComplexityGate gate4 = new QueryComplexityGate();
            set(gate4, "classifier", throwing);
            String g4 = safeAssess(gate4, q);

            boolean r1;
            try { r1 = rp.complexMainRequest(q, "GENERAL"); }
            catch (Throwable t) { r1 = false; g4 += "|r1ex:" + t.getClass().getSimpleName(); }

            boolean r2 = rp.shouldPromote(sig(256, RouteSignal.Preference.BALANCED));
            boolean r3 = rp.shouldPromote(sig(800, RouteSignal.Preference.BALANCED));
            boolean r4 = rp.shouldPromote(sig(256, RouteSignal.Preference.QUALITY));

            System.out.println("{\"i\":" + i
                    + ",\"g1\":\"" + esc(g1) + "\",\"g2\":\"" + esc(g2)
                    + "\",\"g3\":\"" + esc(g3) + "\",\"g4\":\"" + esc(g4)
                    + "\",\"r1\":" + r1 + ",\"r2\":" + r2
                    + ",\"r3\":" + r3 + ",\"r4\":" + r4 + "}");
        }
    }
}
"""


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def run(cmd, cwd=None, timeout=120):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout)
    return {"returncode": p.returncode, "stdout": p.stdout, "stderr": p.stderr}


def main(argv=None):
    ap = argparse.ArgumentParser(prog="p6dbg_route_divergence")
    ap.add_argument("--root", default=".")
    ap.add_argument("--work-dir", default=str(Path.home()) + r"\AppData\Local\Temp\p6dbg\route")
    ap.add_argument("--corpus", default="data/agent-handoff/devin-p6/complexity-corpus.jsonl")
    ap.add_argument("--out-json", default="data/agent-handoff/devin-p6/route-divergence.json")
    ap.add_argument("--out-md", default="data/agent-handoff/devin-p6/route-divergence.md")
    ap.add_argument("--max-questions", type=int, default=0, help="0 = all")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    corpus = root / args.corpus
    if not corpus.is_file():
        print(json.dumps({"error": "corpus-missing", "path": str(corpus)}))
        return 2
    questions = []
    for line in corpus.read_text(encoding="utf-8").splitlines():
        if line.strip():
            questions.append(json.loads(line))
    if args.max_questions:
        questions = questions[: args.max_questions]
    if len(questions) < 20:
        print(json.dumps({"error": "corpus-too-small", "n": len(questions)}))
        return 2
    if not shutil.which("javac") or not shutil.which("java"):
        print(json.dumps({"error": "NOT_RUN", "reason": "javac/java missing"}))
        return 2

    work = Path(args.work_dir)
    src_dir = work / "src"
    cls_dir = work / "classes"
    src_dir.mkdir(parents=True, exist_ok=True)
    cls_dir.mkdir(parents=True, exist_ok=True)

    pre_hash, post_hash = {}, {}
    copied = []
    missing_src = []
    for rel in COPY_SOURCES:
        src = root / rel
        if not src.is_file():
            missing_src.append(rel)
            continue
        pre_hash[rel] = sha256(src)
        dst = src_dir / rel[len("main/java/"):]
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
        copied.append(rel)
    if missing_src:
        print(json.dumps({"error": "source-missing", "paths": missing_src}))
        return 2

    for rel, text in STUBS.items():
        p = src_dir / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    probe_src = src_dir / "com/example/lms/service/rag/ProbeMain.java"
    probe_src.write_text(PROBE_MAIN.lstrip(), encoding="utf-8")

    qfile = work / "questions.txt"
    qfile.write_text("\n".join(re.sub(r"\s+", " ", q["text"]) for q in questions) + "\n",
                     encoding="utf-8")
    empty_model = work / "empty-model.bin"
    empty_model.write_bytes(b"")

    all_src = [str(p.relative_to(src_dir)) for p in src_dir.rglob("*.java")]
    comp = run(["javac", "-encoding", "UTF-8", "-d", str(cls_dir)] + all_src, cwd=src_dir)
    if comp["returncode"] != 0:
        for rel in COPY_SOURCES:
            post_hash[rel] = sha256(root / rel)
        print(json.dumps({"error": "compile-failed",
                          "stderr": comp["stderr"][:3000],
                          "source_hashes_unchanged": pre_hash == post_hash},
                         ensure_ascii=False))
        return 1

    runres = run(["java", "-Dfile.encoding=UTF-8", "-cp", str(cls_dir),
                  "com.example.lms.service.rag.ProbeMain",
                  str(qfile), str(empty_model)], cwd=work, timeout=180)

    for rel in COPY_SOURCES:
        post_hash[rel] = sha256(root / rel)
    src_unchanged = pre_hash == post_hash

    if runres["returncode"] != 0:
        print(json.dumps({"error": "probe-run-failed",
                          "stderr": runres["stderr"][:3000],
                          "source_hashes_unchanged": src_unchanged},
                         ensure_ascii=False))
        return 1

    rows = []
    for line in runres["stdout"].splitlines():
        line = line.strip()
        if line.startswith("{"):
            rows.append(json.loads(line))

    conditions = ["g1_rules_only", "g2_model_nopath", "g3_model_emptyfile",
                  "g4_classifier_throws", "r1_complexMainRequest",
                  "r2_promote_tok_default", "r3_promote_tok_raised",
                  "r4_promote_quality"]
    key_of = {"g1_rules_only": "g1", "g2_model_nopath": "g2",
              "g3_model_emptyfile": "g3", "g4_classifier_throws": "g4",
              "r1_complexMainRequest": "r1", "r2_promote_tok_default": "r2",
              "r3_promote_tok_raised": "r3", "r4_promote_quality": "r4"}

    matrix, divergent, low_promoted = [], [], []
    exception_rows = 0
    for row in rows:
        q = questions[row["i"]]
        entry = {"id": q["id"], "text": q["text"], "expected": q["expected"],
                 "trap": q.get("trap", False)}
        for cond, k in key_of.items():
            entry[cond] = row[k]
        levels = {row["g1"], row["g2"], row["g3"]}
        entry["gate_divergent"] = len(levels - {v for v in levels if str(v).startswith("EXCEPTION")}) > 1 \
            or any(str(v).startswith("EXCEPTION") for v in (row["g1"], row["g2"], row["g3"]))
        entry["promoted_with_low_complexity"] = (
            row["g1"] in ("SIMPLE", "AMBIGUOUS") and (row["r3"] or row["r4"]))
        if str(row["g4"]).startswith("EXCEPTION") or "r1ex" in str(row["g4"]):
            exception_rows += 1
        if entry["gate_divergent"]:
            divergent.append(q["id"])
        if entry["promoted_with_low_complexity"]:
            low_promoted.append(q["id"])
        matrix.append(entry)

    report = {
        "schema": SCHEMA,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "root": str(root),
        "work_dir": str(work),
        "questions": len(matrix),
        "conditions": conditions,
        "copied_sources": copied,
        "source_hashes_unchanged": src_unchanged,
        "source_sha256_before": pre_hash,
        "source_sha256_after": post_hash,
        "compile_exit": comp["returncode"],
        "probe_exit": runres["returncode"],
        "gate_divergent_questions": divergent,
        "low_complexity_promoted": low_promoted,
        "classifier_exception_rows": exception_rows,
        "matrix": matrix,
        "external_calls": 0,
        "note": "mock-only: no Spring context; QueryComplexityGate assessed via "
                "javac-compiled copies under TEMP. r1 uses the same "
                "new QueryComplexityGate() path the live RouterPolicy calls.",
    }

    out_json = root / args.out_json
    out_md = root / args.out_md
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")

    md = ["# Route divergence probe (D2)", "",
          f"Generated: {report['generated_at_utc']}",
          f"Conditions {len(conditions)} x questions {len(matrix)}; "
          f"gate-divergent questions: **{len(divergent)}**; "
          f"low-complexity-but-promoted: **{len(low_promoted)}**; "
          f"classifier-exception rows: {exception_rows}",
          "", "Verdict columns:", "",
          "- g1 rules-only / g2 model-no-path / g3 model-empty-file / g4 throws",
          "- r1 complexMainRequest / r2 tokens=256 / r3 tokens=800 / r4 QUALITY",
          "", "| id | text | expected | g1 | g2 | g3 | g4 | r1 | r2 | r3 | r4 | flags |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for e in matrix:
        flags = []
        if e["gate_divergent"]:
            flags.append("DIVERGENT")
        if e["promoted_with_low_complexity"]:
            flags.append("LOW->PROMOTE")
        if e["trap"]:
            flags.append("trap")
        text = e["text"] if len(e["text"]) <= 38 else e["text"][:35] + "..."
        md.append(f"| {e['id']} | {text} | {e['expected']} | {e['g1_rules_only']} | "
                  f"{e['g2_model_nopath']} | {e['g3_model_emptyfile']} | {e['g4_classifier_throws']} | "
                  f"{e['r1_complexMainRequest']} | {e['r2_promote_tok_default']} | "
                  f"{e['r3_promote_tok_raised']} | {e['r4_promote_quality']} | {','.join(flags)} |")
    md += ["", "## Divergent cells", ""]
    for e in matrix:
        if e["gate_divergent"]:
            md.append(f"- **{e['id']}** `{e['text']}`: rules={e['g1_rules_only']} "
                      f"model-no-path={e['g2_model_nopath']} model-empty={e['g3_model_emptyfile']}")
    md += ["", "## LOW-complexity promoted", ""]
    for e in matrix:
        if e["promoted_with_low_complexity"]:
            md.append(f"- **{e['id']}** `{e['text']}`: g1={e['g1_rules_only']} "
                      f"tokens800={e['r3_promote_tok_raised']} quality={e['r4_promote_quality']}")
    out_md.write_text("\n".join(md) + "\n", encoding="utf-8")

    print(json.dumps({
        "questions": len(matrix),
        "gate_divergent": len(divergent),
        "low_complexity_promoted": len(low_promoted),
        "classifier_exception_rows": exception_rows,
        "source_hashes_unchanged": src_unchanged,
        "json": str(out_json),
        "md": str(out_md),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
