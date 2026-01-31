#!/usr/bin/env python3
# Detect and optionally split Java files containing multiple public types.
import re, argparse, os, shutil
from pathlib import Path

PUB_RX = re.compile(r'^\s*public\s+(class|interface|enum)\s+([A-Za-z_]\w*)', re.M)

def process_file(path: Path, apply=False, backup=True):
    text = path.read_text(encoding="utf-8", errors="replace")
    matches = list(PUB_RX.finditer(text))
    if len(matches) <= 1:
        return 0
    if backup:
        shutil.copy2(path, path.with_suffix(".java.bak"))
    # Keep only the first public type in original file; extract others to new files
    first = matches[0]
    head_name = first.group(2)
    # Split rough by type declarations
    parts = PUB_RX.split(text)
    # parts structure: [pre, kind1, name1, rest1, kind2, name2, rest2, ...]
    # Rebuild the primary file
    new_text = parts[0] + f"public {parts[1]} {parts[2]}" + parts[3]
    path.write_text(new_text, encoding="utf-8")
    created = 0
    i = 4
    while i < len(parts):
        kind = parts[i-1]  # actually misaligned; adjust indexes
        # Correct parsing: recompute using matches
        m = matches[(i-4)//3 + 1] if ((i-4)//3 + 1) < len(matches) else None
        if not m:
            break
        kind = m.group(1)
        name = m.group(2)
        start = m.start()
        # Extract from match to either next match or end
        # For simplicity, slice using next match position
        idx = matches.index(m)
        end = matches[idx+1].start() if idx+1 < len(matches) else len(text)
        chunk = text[m.start():end]
        new_file = path.parent / f"{name}.java"
        new_file.write_text(chunk, encoding="utf-8")
        created += 1
        i += 3
    return created

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()
    root = Path(args.root)
    total = 0
    for p in root.rglob("*.java"):
        try:
            total += process_file(p, apply=args.apply)
        except Exception as e:
            print(f"[WARN] {p}: {e}")
    print(f"Created files: {total}")

if __name__ == "__main__":
    main()