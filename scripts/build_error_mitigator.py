\
    #!/usr/bin/env python3
    # -*- coding: utf-8 -*-

import sys, re, json, os, glob, io

def load_patterns(p):
    with open(p, "r", encoding="utf-8") as f:
        return json.load(f)

def read_text(p):
    with open(p, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()

def write_text(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)

def patch_groovy(txt, injects):
    if any(s in txt for s in injects):
        return txt
    lines = txt.splitlines()
    # Inject to dependencies {{ ... }} if exists
    dep_idx = None
    for i, line in enumerate(lines):
        if re.match(r'\s*dependencies\s*\{', line):
            dep_idx = i; break
    snippet = ["  // injected by build_error_mitigator.py"] + [ "  " + s for s in injects ]
    if dep_idx is not None:
        lines[dep_idx+1:dep_idx+1] = snippet
    else:
        lines.append("")
        lines.append("dependencies {")
        lines.extend(snippet)
        lines.append("}")
    if "tasks.withType(JavaCompile)" not in txt:
        lines.append("")
        lines.append("tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }")
    return "\n".join(lines)

def patch_kts(txt, injects):
    if any(s in txt for s in injects):
        return txt
    lines = txt.splitlines()
    dep_idx = None
    for i, line in enumerate(lines):
        if re.match(r'\s*dependencies\s*\{', line):
            dep_idx = i; break
    snippet = ["  // injected by build_error_mitigator.py"] + [ "  " + s for s in injects ]
    if dep_idx is not None:
        lines[dep_idx+1:dep_idx+1] = snippet
    else:
        lines.append("")
        lines.append("dependencies {")
        lines.extend(snippet)
        lines.append("}")
    if "tasks.withType<JavaCompile>()" not in txt:
        lines.append("")
        lines.append('tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }')
    return "\n".join(lines)

def main():
    if len(sys.argv) < 3:
        print("Usage: build_error_mitigator.py <patterns.json> <build.log>", file=sys.stderr)
        sys.exit(2)
    patterns_json = sys.argv[1]
    build_log = sys.argv[2]

    db = load_patterns(patterns_json)
    log_txt = read_text(build_log)

    applied = []
    for p in db.get("patterns", []):
        match_any = p.get("match_any", [])
        if any(re.search(rx, log_txt, re.IGNORECASE|re.MULTILINE) for rx in match_any):
            for act in p.get("actions", []):
                if act.get("type") == "patch_gradle":
                    targets = act.get("targets", ["**/build.gradle", "**/build.gradle.kts"])
                    injects_groovy = act.get("inject", {}).get("groovy", [])
                    injects_kts = act.get("inject", {}).get("kts", [])
                    paths = []
                    for t in targets:
                        paths.extend(glob.glob(t, recursive=True))
                    for path in sorted(set(paths)):
                        try:
                            txt = read_text(path)
                            if path.endswith(".kts"):
                                new_txt = patch_kts(txt, injects_kts)
                            else:
                                new_txt = patch_groovy(txt, injects_groovy)
                            if new_txt != txt:
                                write_text(path, new_txt)
                                applied.append({"pattern": p.get("id"), "file": path})
                        except Exception as e:
                            applied.append({"pattern": p.get("id"), "file": path, "error": str(e)})
    # write report
    out = {
        "applied": applied,
        "patterns_considered": [p.get("id") for p in db.get("patterns", [])]
    }
    os.makedirs("src", exist_ok=True)
    with open("src/AUTO_BUILD_FIX_REPORT__latest.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(json.dumps(out, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
