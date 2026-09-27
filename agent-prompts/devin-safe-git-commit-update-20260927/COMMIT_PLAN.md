# COMMIT_PLAN — devin-safe-git-commit-update-20260927

Generated 2026-09-27 from `git status --porcelain=v1 -uall -z` (7042 entries).
Executor: `scripts/conditional_local_git.py commit --repo . --preserve-foreign-staged`
per batch. Caps enforced by the tool: ≤40 paths / ≤15 deletions per commit.
Foreign staged `SelfAskPlannerOwnershipContractTest.java` (AM) is preserved
byte-identical and never added to a batch.

## Excluded (not committed)

| paths | reason |
|---|---|
| `uploads/` | brief default exclusion (untracked, unignored) |
| `.env` real values / `.secrets/` / `config/secrets/` / `data/` / `var/` / `logs/` | secrets/DB/models/large-log classes; none present in porcelain except uploads |
| `*.sqlite|*.h2|*.gguf|*.safetensors|*.onnx|*.pt|*.bin`, `*.pem|key|p12|pfx|jks|der|keystore`, `*.log`, cookie files | safety net regexes |
| `_compile_out.txt`,`_final_tests_out.txt`,`_mm_test*.txt`,`_nova_tests_out.txt` | session test-output scratch |
| `targets*.json`,`phase0-targets.json` | session lease manifests |
| `.well-known/pki-validation/*.txt` | transient domain-verification token |
| `AGENTS.md`, `.clinerules/00|61`, `.windsurf/rules/demo1-hard-constraints.md` | ACTIVE foreign lease `clean-vibe-agent-auth-relax-0927` (expires ~03:14Z) |
| `Read-RAG-Debug.bat`, `scripts/read_rag_debug_trail{,_tests}.ps1` | expired-lease targets `clean-primitive-debug-ai-impl-0926` |
| `src/test/java/.../SelfAskPlannerOwnershipContractTest.java` | foreign staged, preserve |

## Batch table (topic → paths → batches)

| topic | paths | batches |
|---|---|---|
| root | 238 | 14 |
| main/java | 1130 | 29 |
| main/resources | 101 | 3 |
| src/test | 935 | 24 |
| src/chatUiTest, src/glmAgentMcp(+Test) | 44 | 3 |
| app (+resources/tools/src) | 454 | 31 |
| scripts | 373 | 10 |
| docs | 328 | 9 |
| agent-prompts | 216 | 6 |
| .agents(+skills) | 326 | 10 |
| .cline/.clinerules/.codex/.devin/.githooks/.github/.grok/.windsurf/.internal/.well-known(excl) | 49 | 9 |
| configs(committed 0bc6319), config, frontend, tools | 40 | 4 |
| java/ (obsolete tree) | 1877 | 126 |
| lms-core/ | 325 | 22 |
| test/ | 109 | 8 |
| demo-1/ | 103 | 7 |
| backup/ | 93 | 7 |
| extras/ | 65 | 5 |
| cfvm-raw/ | 63 | 5 |
| addons/ | 32 | 3 |
| agent_scaffold/, analysis/, PATCHES/, tools-scorecard/, ops/, com/, service/, plans/, __reports__/, _assistant/, AGENT/, .build/, build-logs/, build-logic/, ci/, contract/, infra/, guard/, __patch_drop__/ | 121 | 18 |

**Total: 355 batches → ≤355 commits** (354 remaining after smoke batch).
Smoke batch `configs` → commit `0bc6319` (3 paths, scan pass, foreign staging
preserved).

## Failure handling

- `secret-found` / `blocked-path` / `forbidden-remote` / `origin-mismatch`:
  record, skip batch, continue.
- `index-lock` / `index-changed-*` / `head-changed-*` / timeout: retry ≤3,
  15s backoff, then record fail.
- Results: `data/agent-handoff/codex-autonomy/devin-safe-git-commit-update-0927-b6f0e3c8/results.jsonl`.

## Push (post-commit, user-authorized)

`F:\git\cmd\git.exe push -u origin HEAD` → sole remote
`github.com/UnlimitedAbandonWare/AbandonWareAi`. No force, no history rewrite.
Pre-push hook requires `publish.allowTarget` + `AWX_PUBLISH_APPROVED` +
`git_publish_review.py` CLEAN; divergent/absent upstream → report, no fix-up.
