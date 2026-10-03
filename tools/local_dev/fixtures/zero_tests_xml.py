#!/usr/bin/env python3
"""zero_tests_xml.py --out DIR -- write an empty JUnit suite file.

Produces DIR/TEST-awx.zero.xml with tests=0 so run_verified_command's
JUnit gate sees a fresh but empty suite -> invalid_xml/zero tests ->
aw-dev must classify INCONCLUSIVE (11), never PASS.
"""
import argparse
import os
import sys

ap = argparse.ArgumentParser()
ap.add_argument("--out", required=True)
args = ap.parse_args()
os.makedirs(args.out, exist_ok=True)
xml = ('<?xml version="1.0" encoding="UTF-8"?>\n'
       '<testsuite name="awx.zero" tests="0" failures="0" errors="0" '
       'skipped="0" time="0.001" timestamp="2000-01-01T00:00:00">\n'
       '</testsuite>\n')
path = os.path.join(args.out, "TEST-awx.zero.xml")
with open(path, "w", encoding="utf-8") as fh:
    fh.write(xml)
print("wrote %s" % path)
sys.exit(0)
