# -*- coding: utf-8 -*-
import sys, glob, re
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
pattern = sys.argv[1] if len(sys.argv) > 1 else '*'
for f in glob.glob(rf'C:\AbandonWare\demo-1\demo-1\src\build\test-results\test\TEST-{pattern}.xml'):
    s = open(f, encoding='utf-8', errors='replace').read()
    m = re.search(r'<testsuite[^>]*tests="(\d+)"[^>]*skipped="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', s)
    print(f.split('\\')[-1], m.groups() if m else 'no-match')
    for tc in re.finditer(r'<testcase name="([^"]+)"[^>]*>([\s\S]*?)</testcase>', s):
        name, body = tc.groups()
        if '<failure' in body or '<error' in body:
            print('FAILED:', name)
            fm = re.search(r'<failure[^>]*>([\s\S]*?)</failure>', body) or re.search(r'<error[^>]*>([\s\S]*?)</error>', body)
            print((fm.group(1)[:2500] if fm else body[:2500]))
