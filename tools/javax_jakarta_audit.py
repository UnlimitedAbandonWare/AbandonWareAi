#!/usr/bin/env python3
# Scan Java sources for javax.* vs jakarta.* mismatches.
import re, argparse
from pathlib import Path

IMPORT_RX = re.compile(r'^\s*import\s+(javax\.[\w\.]+|jakarta\.[\w\.]+);', re.M)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    args = ap.parse_args()
    root = Path(args.root)
    rows = []
    for p in root.rglob("*.java"):
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for m in IMPORT_RX.finditer(text):
            rows.append({"file": str(p), "import": m.group(1)})
    javax = [r for r in rows if r["import"].startswith("javax.")]
    jakarta = [r for r in rows if r["import"].startswith("jakarta.")]
    print("javax imports:", len(javax))
    print("jakarta imports:", len(jakarta))
    # show a few examples
    for r in javax[:20]:
        print("J:", r["file"], r["import"])
    for r in jakarta[:20]:
        print("K:", r["file"], r["import"])

if __name__ == "__main__":
    main()