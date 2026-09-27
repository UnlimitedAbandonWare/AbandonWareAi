import re, sys
checks = {
    'AGENTS.md': ['DEMO1-GPU-LANE-EVIDENCE', 'demo1-gpu-lane-evidence', 'ollama-status-snapshot.ps1'],
    '.agents/skills/demo1-gpu-lane-evidence/SKILL.md': ['Verification contract', 'ollama-status-snapshot.ps1', 'auto_discovery_ambiguous'],
    'agent-prompts/gpu-lane-repair-20260924/brief.md': ['완료 기준', '수정 순서', 'F11', '11438'],
    'agent-prompts/gpu-lane-repair-20260924/codex_operating_card.md': ['glm_worker', 'agent_git_vibe_commit.py', 'Model lock'],
    'agent-prompts/gpu-lane-repair-20260924/baseline-snapshot.txt': ['llama-server.exe', 'GPU-4032bebe', 'gemma4:26b'],
}
secret = re.compile(r'(sk-[a-zA-Z0-9]{8,}|api[_-]?key\s*[:=]\s*["\'][^"\']{8,}|BEGIN [A-Z ]*PRIVATE KEY)', re.I)
ok = True
for f, marks in checks.items():
    t = open(f, encoding='utf-8').read()
    missing = [m for m in marks if m not in t]
    if missing:
        ok = False
        print('MISSING in %s: %s' % (f, missing))
    if secret.search(t):
        ok = False
        print('SECRET-LIKE PATTERN in %s' % f)
print('DOC-CHECKS', 'PASS' if ok else 'FAIL')
sys.exit(0 if ok else 1)
