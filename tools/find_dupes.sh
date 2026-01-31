#!/usr/bin/env bash
set -euo pipefail
find . -type f -name "*.java"     | sed 's#.*/##'     | sort | uniq -d > .dupe-names.txt

echo "== duplicate file names =="
cat .dupe-names.txt || true

echo "== locations =="
while read -r f; do
  echo "### $f"
  git ls-files | grep "/$f$" || true
done < .dupe-names.txt
