#!/usr/bin/env python3
"""exit_with.py N -- exit with code N. Fixture for exit-code pass-through."""
import sys

code = int(sys.argv[1]) if len(sys.argv) > 1 else 0
print("exit_with:%d" % code)
raise SystemExit(code)
