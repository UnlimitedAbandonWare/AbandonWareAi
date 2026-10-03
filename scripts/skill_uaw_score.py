#!/usr/bin/env python3
"""skill_uaw_score.py — deterministic skill health score + sigmoid gate verdict.

UAW projection: M09 (sigmoid threshold gate) + M07 (deterministic-first;
ambiguous MERGE/COLD go to a separate critic pass, not decided here).

Score model (x in [0,1] weighted sum, then S(x) = 1/(1+e^(-k(x-x0)))):
  usage      0.30 * min(reads / READ_SAT, 1)            actual reads (3d window)
  indexed    0.15 * in skills-intent-index.yaml         gate visibility (M02)
  desc_qual  0.15 * min(len(description)/DESC_SAT, 1)   front-loads info (M05)
  when15     0.10 * "when to use" inside first 15 lines (M05)
  health     0.10 * (1 - near_fail / max(reads,1))      failure signature (M01)
  size_ok    0.10 * size score                          catalog budget (M08)
  unique     0.10 * (1 - max pairwise desc similarity)  diversity (M11)

Verdicts (ordered):
  FIX-FRONTMATTER : no/empty description (spec requires non-empty)
  MERGE-PROPOSE   : desc similarity >= SIM_MERGE and this is the weaker pair member
  COLD-PROPOSE    : score < T_COLD and reads == 0 and distinct_sessions == 0
  TIGHTEN         : score < T_TIGHTEN
  KEEP            : otherwise
"""
import argparse
import csv
import io
import json
import math
import os
import re
import sys

# ---- gate constants (tunable; re-measure then adjust — UAW 3388) ----
SIGMOID_K = 12.0        # sensitivity
SIGMOID_X0 = 0.50       # midpoint
T_TIGHTEN = 0.55        # below -> TIGHTEN
T_COLD = 0.32           # below + zero use -> COLD-PROPOSE
SIM_MERGE = 0.60        # description Jaccard >= -> same-job cluster
READ_SAT = 5.0          # reads saturating point
DESC_SAT = 60.0         # description chars for full desc_quality
SIZE_FULL = 8000        # bytes: full size credit
SIZE_ZERO = 24000       # bytes: zero size credit

DESC_KEY_RE = re.compile(r"^description\s*:", re.M)
WHEN_RE = re.compile(
    r"use this (skill )?when|use when|when .* (use|invoke)|trigger|"
    r"\ud560 \ub54c|\uc0ac\uc6a9|\uc5b8\uc81c", re.I)
TOKEN_RE = re.compile(r"[a-z0-9]+")
STOP = {"use", "this", "when", "the", "a", "an", "and", "or", "of", "to",
        "for", "in", "on", "with", "skill", "skills", "that", "is", "are",
        "be", "by", "at", "as", "it", "its", "not", "only", "one", "per",
        "your", "you", "all", "any", "each", "demo1", "from", "into",
        "over", "via", "their", "they", "which", "while", "where"}


def sigmoid(x):
    return 1.0 / (1.0 + math.exp(-SIGMOID_K * (x - SIGMOID_X0)))


def parse_frontmatter(text):
    """Return dict of top-level frontmatter keys -> raw value string."""
    fm = {}
    if not text.startswith("---"):
        return fm
    end = text.find("\n---", 3)
    if end < 0:
        return fm
    block = text[3:end]
    cur = None
    for line in block.splitlines():
        m = re.match(r"^([A-Za-z_-]+)\s*:\s*(.*)$", line)
        if m:
            cur = m.group(1)
            fm[cur] = m.group(2).strip()
        elif cur and line.startswith((" ", "\t")):
            fm[cur] += " " + line.strip()
    return fm


def tokens(s):
    return {t for t in TOKEN_RE.findall(s.lower()) if t not in STOP
            and len(t) > 1}


def jaccard(a, b):
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def read_skill_info(root, rel):
    path = os.path.join(root, rel)
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError:
        return {"exists": False, "desc": "", "name": "", "when15": False,
                "bytes": 0}
    fm = parse_frontmatter(text)
    desc = fm.get("description", "")
    head = "\n".join(text.splitlines()[:15])
    return {"exists": True, "desc": desc, "name": fm.get("name", ""),
            "when15": bool(WHEN_RE.search(head)) or
            bool(re.search(r"use\b.*\bwhen\b|\bwhen\b.*\buse\b|trigger",
                           desc, re.I)),
            "bytes": os.path.getsize(path)}


def load_index_names(index_path):
    if not os.path.isfile(index_path):
        return set()
    with open(index_path, "r", encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    names = set()
    names.update(re.findall(
        r"^\s*(?:primary_skill|optional_skill|secondary_skill)\s*:\s*"
        r"([A-Za-z0-9][A-Za-z0-9._-]+)", text, re.M))
    names.update(re.findall(r"^\s*-\s*([A-Za-z0-9][A-Za-z0-9._-]*-[A-Za-z0-9._-]+)\s*$",
                            text, re.M))  # "- hyphenated-name" list items
    names.update(re.findall(r"skills[/\\]([A-Za-z0-9][A-Za-z0-9._-]*)",
                            text, re.I))
    return {n.lower() for n in names}


def size_score(nbytes):
    if nbytes <= SIZE_FULL:
        return 1.0
    if nbytes >= SIZE_ZERO:
        return 0.0
    return 1.0 - (nbytes - SIZE_FULL) / (SIZE_ZERO - SIZE_FULL)


def compute(root, signals_path, index_path):
    skills = {}
    with open(signals_path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            skills[row["skill"]] = row
    index_names = load_index_names(index_path) if index_path else set()

    infos = {}
    tokmap = {}
    for s, row in skills.items():
        info = read_skill_info(root, row["path"])
        infos[s] = info
        tokmap[s] = tokens(info["desc"] + " " + s.replace("-", " "))

    # pairwise description similarity (Jaccard on tokens)
    names = sorted(skills)
    for i in range(len(names)):
        best, partner = 0.0, None
        for j in range(len(names)):
            if i == j:
                continue
            sim = jaccard(tokmap[names[i]], tokmap[names[j]])
            if sim > best:
                best, partner = sim, names[j]
        infos[names[i]]["max_sim"] = best
        infos[names[i]]["sim_partner"] = partner

    rows = []
    xs = {}
    for s in names:
        row = skills[s]
        info = infos[s]
        reads = row.get("reads", 0)
        nf = row.get("near_fail", 0)
        desc = info["desc"].strip()
        desc_len = len(desc)
        indexed = s in index_names or info["name"].strip().lower() in index_names
        parts = {
            "usage": 0.30 * min(reads / READ_SAT, 1.0),
            "indexed": 0.15 * (1.0 if indexed else 0.0),
            "desc": 0.15 * min(desc_len / DESC_SAT, 1.0),
            "when15": 0.10 * (1.0 if info["when15"] else 0.0),
            "health": 0.10 * (1.0 - min(nf / max(reads, 1), 1.0)),
            "size": 0.10 * size_score(info["bytes"]),
            "unique": 0.10 * (1.0 - info["max_sim"]),
        }
        x = sum(parts.values())
        xs[s] = x
        rows.append({"skill": s, "path": row["path"], "x": x,
                     "score": sigmoid(x), "reads": reads,
                     "catalog_mentions": row.get("catalog_mentions", 0),
                     "near_fail": nf, "bytes": info["bytes"],
                     "desc_len": desc_len, "desc": desc,
                     "when15": info["when15"], "indexed": indexed,
                     "max_sim": round(info["max_sim"], 3),
                     "sim_partner": info["sim_partner"],
                     "distinct_sessions": row.get("distinct_sessions", 0),
                     "exists": info["exists"]})

    for r in rows:
        s = r["skill"]
        reasons = []
        if not r["desc"]:
            verdict = "FIX-FRONTMATTER"
            reasons.append("description missing/empty")
        elif (r["max_sim"] >= SIM_MERGE and r["sim_partner"]
              and xs.get(r["sim_partner"], 0) > r["x"]):
            verdict = "MERGE-PROPOSE"
            reasons.append(f"desc_sim={r['max_sim']}>= {SIM_MERGE} with "
                           f"{r['sim_partner']} (weaker member)")
        elif (r["score"] < T_COLD and r["reads"] == 0
              and r["distinct_sessions"] == 0):
            verdict = "COLD-PROPOSE"
            reasons.append(f"score {r['score']:.3f} < {T_COLD} + zero use")
        elif r["score"] < T_TIGHTEN:
            verdict = "TIGHTEN"
            reasons.append(f"score {r['score']:.3f} < {T_TIGHTEN}")
        else:
            verdict = "KEEP"
            reasons.append("score >= tighten threshold")
        if not r["indexed"]:
            reasons.append("unindexed")
        if r["reads"] == 0:
            reasons.append("reads=0")
        r["verdict"] = verdict
        r["reasons"] = "; ".join(reasons)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--signals", required=True)
    ap.add_argument("--index", default=".agents/skills-intent-index.yaml")
    ap.add_argument("--csv-out", default=None)
    ap.add_argument("--md-out", default=None)
    ap.add_argument("--json-out", default=None)
    args = ap.parse_args()
    rows = compute(os.path.abspath(args.root), args.signals, args.index)

    if args.csv_out:
        os.makedirs(os.path.dirname(os.path.abspath(args.csv_out)),
                    exist_ok=True)
        with open(args.csv_out, "w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["skill", "verdict", "score", "x", "reads",
                        "catalog_mentions", "near_fail", "bytes", "desc_len",
                        "when15", "indexed", "max_sim", "sim_partner",
                        "distinct_sessions", "reasons"])
            for r in rows:
                w.writerow([r["skill"], r["verdict"], f"{r['score']:.4f}",
                            f"{r['x']:.4f}", r["reads"], r["catalog_mentions"],
                            r["near_fail"], r["bytes"], r["desc_len"],
                            r["when15"], r["indexed"], r["max_sim"],
                            r["sim_partner"], r["distinct_sessions"],
                            r["reasons"]])

    dist = {}
    for r in rows:
        dist[r["verdict"]] = dist.get(r["verdict"], 0) + 1

    if args.md_out:
        os.makedirs(os.path.dirname(os.path.abspath(args.md_out)),
                    exist_ok=True)
        lines = ["# Skill verdicts (skill_uaw_score)", "",
                 f"- constants: K={SIGMOID_K} X0={SIGMOID_X0} "
                 f"T_TIGHTEN={T_TIGHTEN} T_COLD={T_COLD} SIM_MERGE={SIM_MERGE} "
                 f"READ_SAT={READ_SAT} DESC_SAT={DESC_SAT} "
                 f"SIZE_FULL={SIZE_FULL} SIZE_ZERO={SIZE_ZERO}",
                 f"- skills: {len(rows)}", "", "## Distribution", ""]
        lines += [f"- {k}: {v}" for k, v in sorted(dist.items())]
        for v in ("FIX-FRONTMATTER", "MERGE-PROPOSE", "COLD-PROPOSE",
                  "TIGHTEN"):
            sel = [r for r in rows if r["verdict"] == v]
            if not sel:
                continue
            lines += ["", f"## {v} ({len(sel)})", ""]
            for r in sel:
                lines.append(f"- `{r['skill']}` score={r['score']:.3f} "
                             f"reads={r['reads']} sim={r['max_sim']}"
                             f"->{r['sim_partner']} | {r['reasons']}")
        with open(args.md_out, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    if args.json_out:
        os.makedirs(os.path.dirname(os.path.abspath(args.json_out)),
                    exist_ok=True)
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump({"constants": {"K": SIGMOID_K, "X0": SIGMOID_X0,
                                     "T_TIGHTEN": T_TIGHTEN, "T_COLD": T_COLD,
                                     "SIM_MERGE": SIM_MERGE},
                       "distribution": dist, "rows": rows},
                      fh, ensure_ascii=False, indent=1)
    print(json.dumps({"skills": len(rows), "distribution": dist,
                      "constants": {"K": SIGMOID_K, "X0": SIGMOID_X0,
                                    "T_TIGHTEN": T_TIGHTEN,
                                    "T_COLD": T_COLD,
                                    "SIM_MERGE": SIM_MERGE}},
                     ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
