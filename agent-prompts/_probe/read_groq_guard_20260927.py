from pathlib import Path
import json, time, re
root = Path(r"C:/AbandonWare/demo-1/demo-1/src")
p = root / "main/java/com/example/lms/agent/GroqFreeTierGuard.java"
print(p.read_text(encoding="utf-8", errors="replace")[:5000])
print("---META---")
meta = root / "main/resources/application-meta-display.yml"
text = meta.read_text(encoding="utf-8", errors="replace")
for i,l in enumerate(text.splitlines(),1):
    if re.search(r"groq|free-tier|evidence", l, re.I):
        print(f"{i}:{l}")
print("---LIMITS---")
lim = root / "docs/provider-limits/groq-limits.md"
if lim.exists():
    print(lim.read_text(encoding="utf-8", errors="replace")[:2000])
e = root / "data/usage/groq-free-plan.json"
print("evidence_exists", e.exists())
if e.exists():
    st = e.stat()
    print("mtime_local", time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(st.st_mtime)))
    j = json.loads(e.read_text(encoding="utf-8"))
    print("keys", sorted(j.keys()))
    for k in ("plan","issuedAtMs","expiresAtMs","attestedAtMs","capturedAtMs","speechVerified","accountEmailHash"):
        if k in j:
            print(k, j[k])
# find DAY constant and property names in guard
for m in re.finditer(r".{0,40}DAY.{0,40}|groq\.free-tier[^\"\']*|expiresAtMs|now-at", p.read_text(encoding="utf-8", errors="replace")):
    print("SNIP", m.group(0).replace("\n"," "))
# tests
for f in (root/"src/test").rglob("*Groq*"):
    print("TEST", f.relative_to(root))
