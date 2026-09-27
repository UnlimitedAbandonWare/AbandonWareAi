# Calm Premium Chat Console and Neutral Codex Prompt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. In this dirty shared checkout, reviewers stay read-only and the root agent applies each minimal patch inline.

**Goal:** Preserve the bright conversation-first `/chat-ui`, refine it into a calm premium operator console without changing behavior, reconcile the stale Node geometry contract, and ship a standalone Korean Codex neutral-adjudication prompt.

**Architecture:** Keep the current Spring/Thymeleaf, vanilla JavaScript, and CSS structure. Treat the current `-20px`/`overflow: clip` wrapper geometry as the browser-proven lifecycle contract. Add only presentation tokens and bounded selector refinements; do not move controls or alter event wiring. Keep the existing read-only SUPPORT/SUPPORT_ALTERNATIVE/FALSIFY pack and add one explicit non-majoritarian NEUTRAL artifact that is not manifest-registered.

**Tech Stack:** CSS, Thymeleaf HTML contracts, Node assertions, JUnit 5, Python unittest, Gradle, Codex in-app Browser.

## Safety and ownership

- Root: `C:\AbandonWare\demo-1\demo-1\src`, branch `main`.
- Recheck `.git\index.lock` before every edit. A live lock stops the task.
- Preserve all unrelated dirty and untracked work. Do not stage or commit.
- Active targets only: `main/resources/static/css/chat-style.css`, `scripts/chat_ui_stream_contract_tests.js`, `src/test/java/com/example/lms/web/ChatFrontendSecurityTest.java`, `agent-prompts/agents/demo1_three_perspective_chat_postprocess/**`, and this plan/spec documentation.
- Do not change JavaScript behavior, DOM IDs, routes, `PromptBuilder.build(PromptContext)`, LangChain4j versions, Supabase state, or external producer defaults.
- Browser is required visual proof. Computer and Supabase are supporting/read-only lanes.

### Task 1: Reconcile the stale Node geometry fixture

**Files:**
- Modify: `scripts/chat_ui_stream_contract_tests.js:1065-1071`

- [ ] Keep the already observed RED as the baseline: the assertion requires desktop `-40px` and `overflow:hidden` while current browser-proven CSS uses `-20px` and `overflow:clip`.
- [ ] Change only those two expected CSS values. Add the existing disclosure-release contract to the same assertion so the wrapper remains unclipped when either native disclosure opens:

```javascript
/\.chat-area-wrapper\s*\{[\s\S]*max-height:\s*calc\(100vh\s*-\s*var\(--top-bar-min-height\)\s*-\s*20px\)/m.test(stylesheet)
/\.chat-area-wrapper\s*\{[\s\S]*overflow:\s*clip/m.test(stylesheet)
stylesheet.includes('.chat-area-wrapper:has(> .response-settings[open])')
stylesheet.includes('.chat-area-wrapper:has(> .diagnostics-disclosure[open])')
```

- [ ] Run `node .\scripts\chat_ui_stream_contract_tests.js` and require exit 0 with the existing contract-OK line.

### Task 2: Add a calm-premium visual contract RED-first

**Files:**
- Modify: `src/test/java/com/example/lms/web/ChatFrontendSecurityTest.java`
- Modify: `main/resources/static/css/chat-style.css`

- [ ] Add `calmPremiumOperatorConsoleKeepsBrightAccessibleHierarchy()` to `ChatFrontendSecurityTest`. It reads the CSS and requires:

```java
assertTrue(css.contains("calm-premium-operator-console"));
assertTrue(css.contains("--surface-raised: #ffffff;"));
assertTrue(css.contains("--shadow-control:"));
assertTrue(css.contains("font-variant-numeric: tabular-nums;"));
assertFalse(css.contains("radial-gradient("));
```

Also use the existing CSS-block helper to require `background: var(--page);` on `body`, `background: var(--surface-raised);` on `.top-utility-bar`, and the current `.chat-area-wrapper` geometry values `-20px` plus `overflow: clip`.

- [ ] Run the single method and observe RED:

```powershell
$env:AWX_AGENT_HOST='desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop-chat-premium'
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle-awx-desktop-chat-premium"
$pcd="$env:USERPROFILE\.awx-gradle-project-cache\desktop-chat-premium"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
.\gradlew.bat test --tests 'com.example.lms.web.ChatFrontendSecurityTest.calmPremiumOperatorConsoleKeepsBrightAccessibleHierarchy' --no-daemon --project-cache-dir $pcd
```

- [ ] Make the smallest CSS-only implementation:
  - add `--surface-raised: #ffffff` and a quiet `--shadow-control` token;
  - replace the page radial gradient with the warm-neutral `var(--page)` canvas;
  - make the sticky utility bar and main workspace crisp raised surfaces;
  - keep only the current soft workspace shadow and quiet control shadow;
  - give bounded status/debug metrics tabular numerals;
  - keep sentence-case labels, 44 px targets, current focus ring, `overflow: clip`, transcript scrolling, and disclosure expansion unchanged.
- [ ] Rerun the single method, Node contract, and then the full `ChatFrontendSecurityTest`.

### Task 3: Ship the standalone neutral Codex prompt RED-first

**Files:**
- Create: `scripts/test_three_perspective_chat_postprocess.py`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/neutral_judge_ko.md`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/meta.yaml`
- Create: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/codex_neutral_prompt_ko.md`

- [ ] Add a Python unittest requiring both neutral files to contain `APPLY | HOLD | REJECT`, non-majority language, exact role names, same-input RED/GREEN priority, `evidence_needed`, one `next_single_proof`, and a disclaimer that manual Codex review is not the protected runtime adjudicator. Assert `ACCEPT | HOLD | REJECT` is absent and `manifest_registered` remains false.
- [ ] Run `python -X utf8 .\scripts\test_three_perspective_chat_postprocess.py` and observe RED.
- [ ] Rewrite the existing neutral role with the strict `APPLY/HOLD/REJECT` schema.
- [ ] Add the standalone copy/paste prompt. It must normalize exact claims into evidence rows, reject majority voting, force HOLD for missing roles or missing proof, limit accepted fixes/risks to three, demand exactly one next proof, and prohibit raw sensitive payloads.
- [ ] Bump the local pack version to `1.1.0`, add a `standalone_prompt` pointer, and keep `manifest_registered: false`.
- [ ] Rerun the dedicated prompt test and `python -X utf8 .\scripts\test_agent_prompt_secret_patterns.py`.

### Task 4: Verification and independent adjudication

**Files:** verify only.

- [ ] Run:

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
python -X utf8 .\scripts\test_three_perspective_chat_postprocess.py
python -X utf8 .\scripts\test_agent_prompt_secret_patterns.py
.\gradlew.bat test --tests 'com.example.lms.web.ChatFrontendSecurityTest' --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes bootJar -x test --no-daemon --project-cache-dir $pcd
```

- [ ] Run `git diff --check` and a count-only secret scan over only the touched paths; require zero hits.
- [ ] Start a fresh isolated current-source runtime on a free port and use the Codex in-app Browser at 1280x720, 760x760, and 390x640. Verify New Chat is at least 44x44, hit-testable, the composer stays inside the wrapper, no horizontal overflow exists, the transcript scrolls internally, and opened disclosures release wrapper height.
- [ ] Use Computer only if a uniquely identified safe local browser window is visible; otherwise report `evidence_needed` without bypassing the safety stop.
- [ ] Run the read-only Supabase context probe; missing project/auth remains supporting `evidence_needed` and never blocks this Desktop-only UI patch.
- [ ] Ask the existing read-only agents for independent SUPPORT, FALSIFY, and NEUTRAL reports. The final neutral verdict must be exactly `APPLY`, `HOLD`, or `REJECT`; any reproducible contradiction forces HOLD.

## Completion criteria

- Node, prompt, full Java UI class, and broad Gradle ladder are green on current output.
- Browser geometry proves the premium CSS did not regress New Chat, composer reachability, disclosures, or responsive overflow.
- The standalone neutral prompt is explicit, strict, non-majoritarian, and not represented as a protected runtime execution.
- No external mutation, source dispatch, manifest registration, staging, commit, or unrelated dirty-worktree edit occurred.
