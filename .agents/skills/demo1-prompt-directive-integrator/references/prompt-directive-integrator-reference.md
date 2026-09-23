# Prompt Directive Integrator Reference

This reference keeps prompt-pack commands and comparison scripts out of the
entrypoint.

## UTF-8 Intake

Read the attachment as UTF-8. If PowerShell output is mojibake, use Python:

```powershell
$AttachmentPath = "C:\path\to\directive.txt"
$env:ATTACHMENT_PATH = $AttachmentPath
python -X utf8 -c "from pathlib import Path; import os; p=Path(os.environ['ATTACHMENT_PATH']); print(p.read_text(encoding='utf-8', errors='replace')[:4000])"
```

## Requirement Extraction

Extract only durable requirements:

- target agent or prompt id,
- backlog IDs such as `P0-AP0-F`, `P1-AP1-C`, or `P2-*`,
- target file list,
- required trace keys,
- focused test gates,
- `no_patch_needed` and `evidence_needed` rules,
- explicit non-goals such as DB/DDL, GUI, or live provider proof.

Treat stale prior reports as evidence only. If current prompt source already
contains the directive contract and generated output is current, produce a
proof-backed no-op instead of duplicating sections.

## Prompt Seam Search

Search with concrete prompt IDs or directive IDs from the attachment:

```powershell
$Needle = "demo1_p0_safe_patch_orchestrator"
rg -n "$Needle|Attached Runtime Patch Directive|evidence_needed|no_patch_needed" agent-prompts AGENTS.md .agents/skills
```

## Patch Rules

- Patch the smallest prompt source file that owns the behavior. Usually this is
  an `agent-prompts/agents/.../system_ko.md` or `system.md` file.
- Update `AGENTS.md` only as a short pointer.
- Do not edit active Java/resources, secret files, `.mcp.json`, DB/DDL, or GUI
  automation for prompt-only tasks.
- Preserve Desktop final-proof wording: Mac mini, Notebook, Supabase, and
  Computer Use evidence is supporting unless current Desktop output proves it.
- Prefer explicit `evidence_needed` over implied success.

## Verification Commands

Validate this skill after skill-only edits:

```powershell
$env:PYTHONUTF8 = "1"
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .\.agents\skills\demo1-prompt-directive-integrator
```

Build the changed prompt:

```powershell
$AgentId = "demo1_p0_safe_patch_orchestrator"
python -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent $AgentId
```

Check manifest parsing and duplicate IDs:

```powershell
$AgentId = "demo1_p0_safe_patch_orchestrator"
$env:AGENT_ID = $AgentId
python -X utf8 -c "import os, yaml, pathlib, collections; agent_id=os.environ['AGENT_ID']; m=yaml.safe_load(pathlib.Path('agent-prompts/prompts.manifest.yaml').read_text(encoding='utf-8')); ids=[a['id'] for a in m.get('agents',[])]; dup=[k for k,v in collections.Counter(ids).items() if v>1]; assert not dup, dup; assert agent_id in ids; print('YAML_OK agents=%d duplicate_ids=NONE' % len(ids))"
```

Check generated output equals the manifest merge result:

```powershell
$env:AGENT_ID = "demo1_p0_safe_patch_orchestrator"
@'
from pathlib import Path
import os, yaml
agent_id = os.environ["AGENT_ID"]
root = Path("agent-prompts")
m = yaml.safe_load((root / "prompts.manifest.yaml").read_text(encoding="utf-8"))
agent = next(a for a in m["agents"] if a["id"] == agent_id)
parts = []
for item in agent.get("merge", {}).get("order", ["trait", "system"]):
    if item == "trait":
        for trait in agent.get("traits", []):
            parts.append((root / trait).read_text(encoding="utf-8").replace("\r\n", "\n"))
    elif item == "system":
        parts.append((root / agent["system"]).read_text(encoding="utf-8").replace("\r\n", "\n"))
expected = "\n\n".join(parts)
out = (root / agent["output"]["path"]).read_text(encoding=agent["output"].get("encoding", "utf-8")).replace("\r\n", "\n")
print("manifest_merge_eq_out", expected == out)
raise SystemExit(0 if expected == out else 1)
'@ | python -X utf8 -
```

Run prompt secret tests when available:

```powershell
python -X utf8 scripts\test_agent_prompt_secret_patterns.py
```

For broad prompt/governance changes, add Desktop cache isolation before Gradle:

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
.\gradlew.bat sourceScoreReport checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

## Completion Report

Report:

- prompt source path and generated output path,
- directive IDs integrated or proven already present,
- manifest parse result,
- generated-output comparison result,
- secret-scan result as counts only,
- Gradle/governance result or exact `evidence_needed`,
- whether the long goal remains open.

Never claim the full 9-hour source patch is complete from a prompt-pack rebuild
alone.

## Common Mistakes

| Mistake | Correction |
| --- | --- |
| Treating a prior completion report as current truth | Re-read live prompt source and generated output. |
| Editing `main/java` during a prompt-only directive task | Stop and switch to runtime Safe Patch skills only if a concrete source blocker is requested. |
| Comparing `system_ko.md` directly to output when traits exist | Compare the manifest merge result to output. |
| Marking Supabase, DB/DDL, GUI, or Mac mini proof complete without live Desktop evidence | Report `evidence_needed` or supporting evidence only. |
| Rebuilding output but skipping manifest or secret checks | Run the prompt build, manifest parse, output comparison, and count-only secret scan before completion. |
