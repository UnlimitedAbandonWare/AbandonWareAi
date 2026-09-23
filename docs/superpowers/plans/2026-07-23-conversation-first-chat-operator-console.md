# Conversation-First Chat Operator Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved conversation-first `/chat-ui`, make current client wait and Stop states truthful in the primary Health pill, preserve the protected two-SUPPORT/one-FALSIFY/neutral adjudicator, and prove the result in a current-source browser runtime.

**Architecture:** Preserve the existing Spring/Thymeleaf and vanilla JavaScript surface. Capture the user-approved untracked UI baseline first, then add one bounded current-turn Health overlay, reorganize existing DOM nodes into native disclosures, add a restrained CSS visual system, and strengthen the existing triadic prompt contract without adding a second orchestration path. Browser, Computer, and Supabase remain separate evidence lanes; only Browser is required final proof for this UI change.

**Tech Stack:** Spring Boot, Thymeleaf HTML, vanilla JavaScript, CSS, Node contract harness, JUnit 5, Gradle Kotlin DSL, Windows PowerShell, Codex in-app Browser.

## Global Constraints

- Canonical root is `C:\AbandonWare\demo-1\demo-1\src`; re-run preflight before every commit.
- Active files are limited to `main/resources`, `main/java`, `scripts`, `src/test/java`, and `src/chatUiTest`; do not patch inactive mirrors.
- Treat the four untracked chat UI files as the user-approved baseline only after their exact SHA-256 values match Task 1.
- Do not stage `src/test/java/com/example/lms/web/ChatFrontendSecurityTest.java` or any unrelated dirty file.
- Keep every `dev.langchain4j:*` dependency exactly `1.0.1`.
- Keep every final model prompt on `PromptBuilder.build(PromptContext)`.
- Do not add a framework, daemon, broker, SMB service, producer dispatch, or always-on normal-chat ensemble.
- Keep successful Stop as `Response stopped` with OK terminal semantics; only wait/timeout/error becomes warning or failure.
- Preserve all existing public IDs, `data-testid` values, `.qa[data-q]`, form names, routes, evidence schemas, and secret redaction unless a focused test explicitly replaces a layout-only contract.
- Browser is required final evidence. Computer is count-only supporting evidence. Supabase is read-only and remains `evidence_needed` while `project_ref` or auth is missing.
- Never print or persist raw prompts, model answers, queries, credentials, Authorization headers, cookies, database URLs, environment dumps, or full error bodies.
- Run only one boot/runtime process per isolated Gradle output and project-cache directory.

---

## File Map

- `main/resources/templates/chat-ui.html`: semantic page order, native disclosures, empty state, quick-prompt markup, and stable DOM/test IDs.
- `main/resources/static/js/chat.js`: current-turn Health overlay, response-settings summary, diagnostics summary, and quick-prompt fill-only behavior.
- `main/resources/static/css/chat-style.css`: layout, visual tokens, disclosure styling, responsive behavior, focus/touch targets, and empty-state visibility.
- `scripts/chat_ui_stream_contract_tests.js`: deterministic RED/GREEN contracts for the JavaScript lifecycle and static HTML/CSS structure.
- `main/java/com/example/lms/ensemble/EnsembleJudgeService.java`: one additive neutral-judge sentence prohibiting candidate-count voting.
- `src/test/java/com/example/lms/ensemble/TriadicDebugJudgeServiceTest.java`: prompt-boundary characterization for the neutral sentence.
- `docs/superpowers/specs/2026-07-23-conversation-first-chat-operator-console-design.md`: approved source specification; do not modify during implementation unless the user changes scope.

---

### Task 1: Capture the approved UI baseline

**Files:**
- Create in Git history: `main/resources/templates/chat-ui.html`
- Create in Git history: `main/resources/static/js/chat.js`
- Create in Git history: `main/resources/static/css/chat-style.css`
- Create in Git history: `scripts/chat_ui_stream_contract_tests.js`

**Interfaces:**
- Consumes: the exact user-approved untracked files and hashes below.
- Produces: a reviewable baseline commit against which every later UI diff is narrow and attributable.

- [ ] **Step 1: Re-run the ownership preflight**

```powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
if (Test-Path -LiteralPath '.git\index.lock') { throw '[AWX][desktop] index-lock-conflict' }
git rev-parse --show-toplevel
git branch --show-current
git status --short -- `
  main/resources/templates/chat-ui.html `
  main/resources/static/js/chat.js `
  main/resources/static/css/chat-style.css `
  scripts/chat_ui_stream_contract_tests.js `
  src/test/java/com/example/lms/web/ChatFrontendSecurityTest.java
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
```

Expected: canonical root, branch `main`, no index lock, no top-level PatchDrop patch, the four baseline files marked `??`, and `ChatFrontendSecurityTest.java` left as a separate modified file.

- [ ] **Step 2: Verify the approved baseline hashes**

```powershell
$expected = @{
  'main/resources/templates/chat-ui.html' = '8F97167788CBA98EEBB3497A3D5200C1B343AC6AFA77E7443A096646ED94D9C7'
  'main/resources/static/js/chat.js' = '4FD2732B758365C05AD1AF54EA4BD4023F0D5F992AB5BB7CB9EB76F1C49C717C'
  'main/resources/static/css/chat-style.css' = '6C395D2449EDA08561EED1BAB03CC358DC79F78200B2FCC53D8CBD4B93316AA0'
  'scripts/chat_ui_stream_contract_tests.js' = '78257D1B85590FF0B63E4A9350E6EE315652EAAC92A9F3020EAB30ED14B45BCD'
}
foreach ($path in $expected.Keys) {
  $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash
  if ($actual -ne $expected[$path]) { throw "worktree-overlap path=$path" }
}
'approvedBaseline=true'
```

Expected: `approvedBaseline=true`. Any mismatch is `worktree-overlap`; stop and request a fresh baseline decision.

- [ ] **Step 3: Prove the baseline harness is green**

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
```

Expected: exit 0 and `[AWX][chat-ui] stream heartbeat contract OK`.

- [ ] **Step 4: Commit only the approved baseline**

```powershell
git add -- `
  main/resources/templates/chat-ui.html `
  main/resources/static/js/chat.js `
  main/resources/static/css/chat-style.css `
  scripts/chat_ui_stream_contract_tests.js
$staged = @(git diff --cached --name-only)
if ($staged.Count -ne 4) { throw "unexpected staged count=$($staged.Count)" }
if ($staged -contains 'src/test/java/com/example/lms/web/ChatFrontendSecurityTest.java') { throw 'unrelated test staged' }
git diff --cached --check
git commit -m 'chore: capture approved chat console baseline'
```

Expected: one baseline commit containing exactly the four approved paths.

---

### Task 2: Make Health follow the current client stream lifecycle

**Files:**
- Modify: `scripts/chat_ui_stream_contract_tests.js:2349-2373,3752-3778,4241-4305`
- Modify: `main/resources/static/js/chat.js:27-33,1014-1041,2580-2594,2979-2986,3062,3108-3142,265-292`

**Interfaces:**
- Consumes: `setStatusRailHealth(status, detail)`, `renderDebugHeartbeat(data)`, `renderLiveDebugHeartbeat(partial)`, and current stream lifecycle events.
- Produces: `setServerStatusRailHealth(status, detail)`, `resetCurrentTurnHealthOverlay()`, and a bounded current-turn overlay with kinds `responding`, `pending`, `stopped`, and `attention`.

- [ ] **Step 1: Add the failing stale-wait and Stop assertions**

Immediately after the existing 65-second assertion in `scripts/chat_ui_stream_contract_tests.js`, add:

```javascript
  assert(
    elements.get('healthStatus').dataset.status === 'warn' &&
      elements.get('healthStatus').textContent === 'Response pending / external proof supporting' &&
      elements.get('healthStatus').title.includes('state:pending') &&
      elements.get('healthStatus').title.includes('stream:model_wait') &&
      elements.get('healthStatus').title.includes('client-wait:65000ms'),
    `stale client wait must override server Live OK: status=${elements.get('healthStatus').dataset.status} text=${elements.get('healthStatus').textContent} raw=${elements.get('healthStatus').title}`
  );
  vm.runInContext("setServerStatusRailHealth('OK', 'live:OK core:OK ui:OK model:OK answer:OK proof:SUPPORTING external:SUPPORTING');", context);
  assert(
    elements.get('healthStatus').textContent === 'Response pending / external proof supporting',
    `server heartbeat must not overwrite an active wait overlay: ${elements.get('healthStatus').textContent}`
  );
```

Extend the existing post-Stop assertion with:

```javascript
  assert(
    elements.get('healthStatus').dataset.status === 'ok' &&
      elements.get('healthStatus').textContent === 'Response stopped / external proof supporting' &&
      elements.get('healthStatus').title.includes('state:stopped') &&
      elements.get('healthStatus').title.includes('stream:cancelled') &&
      !elements.get('healthStatus').textContent.includes('Model failed'),
    `successful Stop must be a truthful non-failure terminal state: status=${elements.get('healthStatus').dataset.status} text=${elements.get('healthStatus').textContent} raw=${elements.get('healthStatus').title}`
  );
  vm.runInContext("setServerStatusRailHealth('OK', 'live:OK core:OK ui:OK model:OK answer:OK proof:SUPPORTING external:SUPPORTING');", context);
  assert(
    elements.get('healthStatus').textContent === 'Response stopped / external proof supporting',
    `server heartbeat must not revive a stopped turn as Live OK: ${elements.get('healthStatus').textContent}`
  );
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
```

Expected: FAIL at the first new assertion because Health still reads `Live OK / external proof supporting`.

- [ ] **Step 3: Add the bounded overlay state and server snapshot**

After `debugHeartbeatSummaryState` in `main/resources/static/js/chat.js`, add:

```javascript
const currentTurnHealthOverlay = {
  kind: 'none',
  status: 'OK',
  streamStatus: 'unknown',
  streamContext: 'none'
};

let lastServerHealth = {
  status: 'WARN',
  detail: 'live:WARN core:WARN ui:WARN model:UNKNOWN answer:WARN proof:SUPPORTING external:SUPPORTING'
};
```

After `setStatusRailHealth`, add:

```javascript
function healthEvidenceState(name) {
  const match = String(lastServerHealth.detail || '').match(
    new RegExp(`\\b${name}:(OK|WARN|UNKNOWN|SUPPORTING)\\b`, 'i')
  );
  return match ? match[1].toUpperCase() : 'SUPPORTING';
}

function currentTurnHealthDetail() {
  return [
    `state:${currentTurnHealthOverlay.kind}`,
    `live:${currentTurnHealthOverlay.status}`,
    `stream:${safeDebugCockpitDetail(currentTurnHealthOverlay.streamStatus)}`,
    `context:${safeDebugCockpitDetail(currentTurnHealthOverlay.streamContext)}`,
    `proof:${healthEvidenceState('proof')}`,
    `external:${healthEvidenceState('external')}`
  ].join(' ');
}

function applyCurrentTurnHealthOverlay() {
  if (currentTurnHealthOverlay.kind === 'none') return false;
  setStatusRailHealth(currentTurnHealthOverlay.status, currentTurnHealthDetail());
  return true;
}

function setServerStatusRailHealth(status, detail) {
  lastServerHealth = {
    status: status === 'OK' ? 'OK' : 'WARN',
    detail: safeDebugCockpitDetail(detail)
  };
  if (!applyCurrentTurnHealthOverlay()) {
    setStatusRailHealth(lastServerHealth.status, lastServerHealth.detail);
  }
}

function resetCurrentTurnHealthOverlay() {
  currentTurnHealthOverlay.kind = 'none';
  currentTurnHealthOverlay.status = 'OK';
  currentTurnHealthOverlay.streamStatus = 'unknown';
  currentTurnHealthOverlay.streamContext = 'none';
  setStatusRailHealth(lastServerHealth.status, lastServerHealth.detail);
}

function syncCurrentTurnHealthOverlay(streamStatus, streamContext, flags = {}) {
  const statusText = String(streamStatus || 'unknown');
  const contextText = String(streamContext || 'none');
  const combined = `${statusText} ${contextText}`;
  let kind;
  let status;

  if (flags.streamStopped) {
    kind = 'stopped';
    status = 'OK';
  } else if (/timeout|deadline|error|message_failed/i.test(combined)) {
    kind = 'attention';
    status = 'WARN';
  } else if (/model_wait/i.test(statusText) || /client-wait:\d+ms\s+next:stop_or_wait/i.test(contextText)) {
    kind = 'pending';
    status = 'WARN';
  } else if (flags.streamDone || flags.streamAnswerComplete) {
    resetCurrentTurnHealthOverlay();
    return;
  } else if (/connecting|attaching|streaming|thought|understanding|status|transformer|evidence|retrying/i.test(statusText)) {
    kind = 'responding';
    status = 'OK';
  } else {
    applyCurrentTurnHealthOverlay();
    return;
  }

  currentTurnHealthOverlay.kind = kind;
  currentTurnHealthOverlay.status = status;
  currentTurnHealthOverlay.streamStatus = statusText;
  currentTurnHealthOverlay.streamContext = contextText;
  applyCurrentTurnHealthOverlay();
}
```

- [ ] **Step 4: Give explicit turn states priority in the compact label**

At the start of `compactHealthRailDetail(detail)`, after `const text`, add:

```javascript
  const turnState = text.match(/\bstate:(responding|pending|stopped|attention)\b/i)?.[1]?.toLowerCase();
```

Replace the `liveText` expression with:

```javascript
  const liveText = turnState === 'responding'
    ? 'Responding'
    : turnState === 'pending'
      ? 'Response pending'
      : turnState === 'stopped'
        ? 'Response stopped'
        : turnState === 'attention'
          ? 'Needs attention'
          : model === 'UNKNOWN'
            ? 'Model proof unavailable'
            : live === 'OK'
              ? 'Live OK'
              : model === 'WARN'
                ? 'Model needs attention'
                : answer === 'WARN'
                  ? 'Answer needs attention'
                  : core === 'WARN'
                    ? 'Core needs attention'
                    : ui === 'WARN'
                      ? 'UI needs attention'
                      : 'Live needs attention';
```

- [ ] **Step 5: Wire server polling and client lifecycle in the correct order**

Replace the server-owned call in `renderDebugHeartbeat`:

```javascript
  setServerStatusRailHealth(healthRailStatus, healthRailDetail);
```

At the end of `renderLiveDebugHeartbeat`, before the successful-answer cleanup calls, add:

```javascript
  syncCurrentTurnHealthOverlay(streamStatus, streamContext, {
    streamDone,
    streamStopped,
    streamAnswerComplete
  });
```

In `startNewChatSession()`, after clearing `activeStreamAssistant`, add:

```javascript
  resetCurrentTurnHealthOverlay();
```

Do not call the reset from Stop. Stop must set and retain the `stopped` overlay until New chat or the next accepted lifecycle transition.

- [ ] **Step 6: Run the focused test and verify GREEN**

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
```

Expected: exit 0 and `[AWX][chat-ui] stream heartbeat contract OK`.

- [ ] **Step 7: Commit the Health lifecycle fix**

```powershell
git add -- main/resources/static/js/chat.js scripts/chat_ui_stream_contract_tests.js
git diff --cached --check
git commit -m 'fix: align chat health with client stream state'
```

---

### Task 3: Reorganize the page into a semantic conversation-first console

**Files:**
- Modify: `main/resources/templates/chat-ui.html:17-242`
- Modify: `main/resources/static/js/chat.js:5-25,1159-1177,2596-2629,3728-3752`
- Modify: `scripts/chat_ui_stream_contract_tests.js:359-362,382-400,958-1027,1138-1220`

**Interfaces:**
- Consumes: existing DOM IDs, controls, quick-prompt listener, protected-link listener, heartbeat cards, and status rails.
- Produces: `#responseSettingsSummary`, `#diagnosticsSummary`, `#chatEmptyState`, native `Admin tools`, `Response settings`, and `Diagnostics` disclosures with no duplicate controls.

- [ ] **Step 1: Add static RED assertions for semantic structure**

After `template` is loaded in `scripts/chat_ui_stream_contract_tests.js`, add:

```javascript
const chatRegionIndex = template.indexOf('<section class="chat-area-wrapper"');
const projectIntroIndex = template.indexOf('<aside class="project-intro"');
assert(
  chatRegionIndex >= 0 && projectIntroIndex > chatRegionIndex,
  `conversation must lead DOM and visual order: chat=${chatRegionIndex} intro=${projectIntroIndex}`
);
assert(
  template.includes('<details class="admin-tools"') &&
    template.includes('<summary>Admin tools</summary>') &&
    template.includes('<details class="response-settings"') &&
    template.includes('id="responseSettingsSummary"') &&
    template.includes('<details class="diagnostics-disclosure"') &&
    template.includes('id="diagnosticsSummary"') &&
    template.includes('id="chatEmptyState"'),
  'chat console must use named native disclosures and a chat-local empty state'
);
assert(
  /class="status-pill"[^>]*role="status"[^>]*aria-live="polite"[^>]*aria-atomic="true"[^>]*>[\s\S]*id="healthStatus"/.test(template),
  'primary Health must remain an atomic polite status message while Diagnostics is collapsed'
);
for (const id of ['modelSelect', 'searchModeSelect', 'useRagToggle', 'newChatBtn', 'chatWindow', 'messageInput', 'sendBtn', 'stopBtn']) {
  const count = (template.match(new RegExp(`id="${id}"`, 'g')) || []).length;
  assert(count === 1, `chat control id must remain unique: ${id} count=${count}`);
}
```

Add `responseSettingsSummary` and `diagnosticsSummary` to the fake element ID list, then add:

```javascript
assert(
  elements.get('responseSettingsSummary').textContent === 'local · Search AUTO · RAG OFF',
  `response settings summary should mirror live controls: ${elements.get('responseSettingsSummary').textContent}`
);
```

Replace the quick-prompt control mutation assertions with:

```javascript
assert(
  elements.get('searchModeSelect').value === 'FORCE_DEEP' &&
    elements.get('useRagToggle').checked === true &&
    elements.get('searchStatus').textContent === 'DEEP' &&
    elements.get('ragStatus').textContent === 'ON',
  `quick prompts must fill only and preserve explicit user controls: search=${elements.get('searchModeSelect').value}/${elements.get('searchStatus').textContent} rag=${elements.get('useRagToggle').checked}/${elements.get('ragStatus').textContent}`
);
const quickPromptPersistedControlSettings = JSON.parse(context.window.sessionStorage.getItem('chat.controlSettings') || '{}');
assert(
  quickPromptPersistedControlSettings.searchMode === 'FORCE_DEEP' &&
    quickPromptPersistedControlSettings.useRag === true,
  `quick prompt must not rewrite persisted control settings: ${JSON.stringify(quickPromptPersistedControlSettings)}`
);
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
```

Expected: FAIL because chat is not first, the native disclosures do not exist, and quick prompts still change controls.

- [ ] **Step 3: Replace the header navigation with a compact admin disclosure**

In `chat-ui.html`, replace the current `<nav class="app-menu-bar">` block with:

```html
<nav class="app-menu-bar" aria-label="Primary">
    <details class="admin-tools">
        <summary>Admin tools</summary>
        <div class="admin-tools-menu">
            <a href="/admin/brain-state" data-menu-action="open-brain-state" data-admin-surface="protected" aria-label="Open protected Brain state console" title="Open protected Brain state console">Brain</a>
            <a href="/admin/pipeline-status" data-menu-action="open-pipeline-status" data-admin-surface="protected" aria-label="Open protected Pipeline status console" title="Open protected Pipeline status console">Pipeline Status</a>
            <a href="/admin/rag-ops-cockpit" data-menu-action="open-rag-ops" data-admin-surface="protected" aria-label="Open protected RAG operations console" title="Open protected RAG operations console">RAG Ops</a>
            <a href="/admin/vector-diagnostics" data-menu-action="open-vector-diagnostics" data-admin-surface="protected" aria-label="Open protected Vector diagnostics console" title="Open protected Vector diagnostics console">Vector</a>
            <a href="/model-settings" data-menu-action="open-model-settings" data-admin-surface="protected" aria-label="Open protected Model settings console" title="Open protected Model settings console">Models</a>
        </div>
    </details>
    <a class="sign-in-link" href="/login">Sign in</a>
</nav>
```

- [ ] **Step 4: Make the chat the first DOM region and move controls inside it**

Start `<main class="chat-layout">` with the chat section. At the top of `.chat-area-wrapper`, add:

```html
<div class="conversation-header">
    <div>
        <p class="eyebrow">Operator chat</p>
        <h1>Dynamic RAG workspace</h1>
        <p>Ask, compare evidence, and follow the live response state without leaving the conversation.</p>
    </div>
    <div class="orch-signal-badges" aria-label="Core status">
        <span data-orch-badge="model" data-status="warn">Model</span>
        <span data-orch-badge="dpp" data-status="warn">DPP</span>
        <span data-orch-badge="cfvm" data-status="warn">CFVM</span>
        <span data-orch-badge="supabase" data-status="warn">Supabase</span>
    </div>
</div>

<details class="response-settings" data-testid="chat-response-settings">
    <summary>
        <span>Response settings</span>
        <span id="responseSettingsSummary">Loading settings</span>
    </summary>
    <div class="control-panel" aria-label="Chat controls">
        <label>Model
            <select id="modelSelect" name="model" aria-label="Model" data-testid="chat-model-select">
                <option th:if="${models == null || #lists.isEmpty(models)}"
                        th:value="${currentModel} ?: 'gemma4:26b'"
                        th:text="${currentModel} ?: 'gemma4:26b'"
                        selected>gemma4:26b</option>
                <option th:each="model : ${models}"
                        th:value="${model.modelId}"
                        th:text="${model.modelId}"
                        th:selected="${model.modelId == currentModel}">model</option>
            </select>
        </label>
        <label>Search
            <select id="searchModeSelect" name="searchMode" aria-label="Search" data-testid="chat-search-mode-select">
                <option value="AUTO">AUTO</option>
                <option value="OFF" selected>OFF</option>
                <option value="FORCE_LIGHT">LIGHT</option>
                <option value="FORCE_DEEP">DEEP</option>
            </select>
        </label>
        <label class="toggle-row">
            <input id="useRagToggle" type="checkbox" aria-label="Use RAG context" data-testid="chat-rag-toggle">
            <span>Use RAG context</span>
        </label>
        <button id="newChatBtn" class="ghost" type="button" title="New chat" data-testid="chat-new-chat-button">New chat</button>
    </div>
</details>
```

Delete the old top-level `control-panel` block and the duplicate old `orch-signal-badges` block. Keep the existing status rail directly after Response settings.

Replace only the Health pill opening tag so its bounded label is announced without exposing raw diagnostic content:

```html
<div class="status-pill" role="status" aria-live="polite" aria-atomic="true" aria-label="Health: checking" title="Health: checking"><strong>Health</strong> <span id="healthStatus" data-orch-field="health" data-status="warn" data-testid="chat-health-status">checking</span></div>
```

- [ ] **Step 5: Wrap secondary diagnostics without changing their IDs or contents**

Insert this opening immediately before the existing `debugHeartbeatBar`:

```html
<details class="diagnostics-disclosure" data-testid="chat-diagnostics">
    <summary>
        <span>Diagnostics</span>
        <span id="diagnosticsSummary" data-status="WARN">Checking signals</span>
    </summary>
    <div class="diagnostics-stack">
```

Insert these closing tags immediately after the existing compact brain-state panel:

```html
    </div>
</details>
```

The heartbeat, matrix, cockpit, mission, flow, proof, and brain-state nodes remain byte-for-byte inside the wrapper.

- [ ] **Step 6: Add the empty state and move the project context after chat**

Immediately before `#chatWindow`, add:

```html
<section class="chat-empty-state" id="chatEmptyState" aria-labelledby="chatEmptyStateTitle">
    <p class="eyebrow">Start with a clear decision</p>
    <h2 id="chatEmptyStateTitle">Ask, challenge, then verify.</h2>
    <p>Use a starter or write your own prompt. Nothing is sent until you press Send.</p>
    <div class="quick-prompt-list" aria-label="Prompt starters">
        <button class="qa" type="button" data-testid="quick-prompt-operational-stability" data-q="운영 안정성 관점에서 Provider Guard, Trace, Fail-soft 구조를 정리해줘">운영 안정성</button>
        <button class="qa" type="button" data-testid="quick-prompt-compare-claims" data-q="가상 시나리오를 하나 정하고 SUPPORT 1, SUPPORT 2, FALSIFY 1을 서로 독립적으로 작성한 뒤, 마지막에 중립 심판이 APPLY, HOLD, REJECT 중 하나로 판정해줘. 각 주장에는 반례와 불확실성을 포함해줘.">주장 교차검증</button>
        <button class="qa" type="button" data-testid="quick-prompt-verify-facts" data-q="이 질문의 사실관계를 웹에서 확인해줘. 확인 가능한 출처와 반례를 함께 제시하고, 근거가 부족하면 HOLD라고 명시해줘.">웹 사실검증</button>
    </div>
</section>
```

After the closing chat section, replace the old project intro with:

```html
<aside class="project-intro" aria-label="Dynamic RAG operation summary">
    <p class="eyebrow">Context</p>
    <h2>Evidence-aware orchestration</h2>
    <p>질문 분석, 검색, 재랭킹, 증거 게이트, 회복 경로를 대화 흐름 안에서 추적합니다.</p>
    <p>Self-Ask, Anchor Compression, MoE routing, and trace breadcrumbs remain available in Diagnostics.</p>
</aside>
```

- [ ] **Step 7: Update bounded summaries and make quick prompts fill-only**

Add the new DOM references:

```javascript
  responseSettingsSummary: $('responseSettingsSummary'),
  diagnosticsSummary: $('diagnosticsSummary'),
```

In `syncControlStatus`, after computing `ragState`, add:

```javascript
  if (dom.responseSettingsSummary) {
    dom.responseSettingsSummary.textContent = `${selectedModel} · Search ${searchModeRailValue(dom.searchModeSelect?.value)} · RAG ${ragState}`;
  }
```

In `setDebugHeartbeatSummary`, after computing `title`, add:

```javascript
  if (dom.diagnosticsSummary) {
    dom.diagnosticsSummary.dataset.status = status === 'OK' ? 'OK' : 'WARN';
    dom.diagnosticsSummary.textContent = status === 'OK' ? 'Core signals live' : title;
  }
```

Delete `applyQuickPromptControlDefaults`. Update `handleQuickPromptClick` to:

```javascript
function handleQuickPromptClick(event) {
  const prompt = String(event?.currentTarget?.dataset?.q || '').trim();
  if (!prompt || !dom.messageInput || dom.messageInput.disabled) return;
  dom.messageInput.value = prompt;
  syncComposerDraftState();
  dom.messageInput.focus();
}
```

- [ ] **Step 8: Run the focused test and verify GREEN**

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
```

Expected: exit 0 and `[AWX][chat-ui] stream heartbeat contract OK`.

- [ ] **Step 9: Commit the semantic console structure**

```powershell
git add -- main/resources/templates/chat-ui.html main/resources/static/js/chat.js scripts/chat_ui_stream_contract_tests.js
git diff --cached --check
git commit -m 'feat: reorganize chat console information hierarchy'
```

---

### Task 4: Apply the restrained visual system and responsive layout

**Files:**
- Modify: `main/resources/static/css/chat-style.css:1-180,630-710,827-1179`
- Modify: `scripts/chat_ui_stream_contract_tests.js:958-1067`

**Interfaces:**
- Consumes: the semantic classes and native disclosures from Task 3.
- Produces: a leading wide chat column, trailing context card, mobile chat-first order without CSS `order`, bounded diagnostics, 44 px targets, and a sticky composer.

- [ ] **Step 1: Replace layout-string tests with RED assertions for the approved visual contract**

Replace the old `chat-workspace-grid`, mobile `order`, and external-card-first assertions with:

```javascript
assert(
  stylesheet.includes('conversation-first-grid') &&
    /\.chat-layout\s*{[\s\S]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s+minmax\(240px,\s*320px\)/m.test(stylesheet) &&
    /\.chat-area-wrapper\s*{[\s\S]*grid-column:\s*1;/m.test(stylesheet) &&
    /\.project-intro\s*{[\s\S]*grid-column:\s*2;/m.test(stylesheet),
  'desktop layout must lead with a wide chat workspace and trail with context'
);
assert(
  stylesheet.includes('conversation-first-mobile') &&
    /@media \(max-width:\s*760px\)[\s\S]*\.chat-area-wrapper\s*{[\s\S]*grid-row:\s*1;/m.test(stylesheet) &&
    /@media \(max-width:\s*760px\)[\s\S]*\.project-intro\s*{[\s\S]*grid-row:\s*2;/m.test(stylesheet) &&
    !/@media \(max-width:\s*760px\)[\s\S]*\.chat-area-wrapper\s*{[\s\S]*order:\s*1;/m.test(stylesheet),
  'mobile layout must use matching DOM/grid order instead of CSS order overrides'
);
assert(
  /button,\s*\.admin-tools\s*>\s*summary,\s*\.response-settings\s*>\s*summary,\s*\.diagnostics-disclosure\s*>\s*summary\s*{[\s\S]*min-height:\s*44px/m.test(stylesheet),
  'interactive controls and disclosures must expose 44px targets'
);
assert(
  stylesheet.includes('.chat-area-wrapper:has(#chatWindow .message) .chat-empty-state') &&
    stylesheet.includes('.diagnostics-stack') &&
    stylesheet.includes('max-height: min(40vh, 360px)') &&
    stylesheet.includes('@media (prefers-reduced-motion: reduce)'),
  'empty state, bounded diagnostics, and reduced motion must be explicit'
);
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
```

Expected: FAIL because the new visual markers and selectors do not exist.

- [ ] **Step 3: Replace the top-level tokens and leading grid**

Use this token block at the top of `chat-style.css`:

```css
:root {
    color-scheme: light;
    --top-bar-min-height: 64px;
    --page: #f3f6f5;
    --surface: #ffffff;
    --surface-soft: #f7f9f8;
    --surface-tint: #edf6f2;
    --line: #d7e0dc;
    --line-strong: #b8c8c1;
    --ink: #14211f;
    --muted: #61716d;
    --accent: #216b55;
    --accent-strong: #15533f;
    --warning: #9a5c12;
    --danger: #a33f3f;
    --radius-sm: 12px;
    --radius-md: 16px;
    --radius-lg: 22px;
    --shadow-soft: 0 18px 48px rgba(25, 48, 41, .10);
    font-family: Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
}
```

Replace the body and main grid declarations with:

```css
body {
    min-height: 100vh;
    display: flex;
    flex-direction: column;
    margin: 0;
    background:
        radial-gradient(circle at 8% 0%, rgba(73, 150, 119, .10), transparent 34rem),
        var(--page);
    color: var(--ink);
}

.chat-layout {
    /* conversation-first-grid */
    flex: 1 1 auto;
    min-height: 0;
    width: min(1240px, calc(100vw - 40px));
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(240px, 320px);
    align-items: start;
    gap: 20px;
    margin: 20px auto;
}

.chat-area-wrapper {
    grid-column: 1;
    grid-row: 1;
    min-height: 0;
    max-height: calc(100vh - var(--top-bar-min-height) - 40px);
    display: flex;
    flex-direction: column;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-lg);
    background: rgba(255, 255, 255, .96);
    box-shadow: var(--shadow-soft);
}

.project-intro {
    grid-column: 2;
    grid-row: 1;
    padding: 22px;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    background: rgba(255, 255, 255, .82);
}
```

- [ ] **Step 4: Style the header and native disclosures**

Add:

```css
.top-utility-bar {
    position: sticky;
    top: 0;
    z-index: 20;
    min-height: var(--top-bar-min-height);
    padding: 10px clamp(16px, 4vw, 44px);
    border-bottom: 1px solid rgba(184, 200, 193, .7);
    background: rgba(255, 255, 255, .88);
    backdrop-filter: blur(18px);
}

.brand-mark {
    font-size: clamp(19px, 2.4vw, 27px);
    font-weight: 850;
    letter-spacing: -.03em;
}

.app-menu-bar {
    display: flex;
    align-items: center;
    gap: 8px;
}

.admin-tools {
    position: relative;
}

button,
.admin-tools > summary,
.response-settings > summary,
.diagnostics-disclosure > summary {
    min-height: 44px;
}

.admin-tools > summary,
.response-settings > summary,
.diagnostics-disclosure > summary {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 10px 14px;
    border-radius: var(--radius-sm);
    color: var(--ink);
    font-weight: 800;
    cursor: pointer;
    list-style: none;
}

.admin-tools-menu {
    position: absolute;
    right: 0;
    z-index: 30;
    width: 220px;
    display: grid;
    gap: 4px;
    margin-top: 8px;
    padding: 8px;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
    box-shadow: var(--shadow-soft);
}

.admin-tools-menu a,
.sign-in-link {
    min-height: 44px;
    display: flex;
    align-items: center;
    padding: 0 12px;
    border-radius: 10px;
}

.response-settings,
.diagnostics-disclosure {
    margin: 0 16px 10px;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    background: var(--surface-soft);
}

.response-settings[open] > summary,
.diagnostics-disclosure[open] > summary {
    border-bottom: 1px solid var(--line);
    border-radius: var(--radius-md) var(--radius-md) 0 0;
}

.control-panel {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
    padding: 14px;
    border: 0;
    border-radius: 0;
    background: transparent;
    box-shadow: none;
}

.diagnostics-stack {
    max-height: min(40vh, 360px);
    overflow: auto;
    overscroll-behavior: contain;
}
```

- [ ] **Step 5: Style the conversation, empty state, messages, and composer**

Add:

```css
.conversation-header {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(240px, .72fr);
    gap: 18px;
    align-items: start;
    padding: 22px 22px 14px;
}

.conversation-header h1,
.project-intro h2,
.chat-empty-state h2 {
    margin: 0;
    letter-spacing: -.035em;
}

.conversation-header p,
.project-intro p,
.chat-empty-state p {
    color: var(--muted);
    line-height: 1.55;
}

.eyebrow {
    margin: 0 0 6px;
    color: var(--accent);
    font-size: 12px;
    font-weight: 900;
    letter-spacing: .12em;
    text-transform: uppercase;
}

.orch-signal-badges {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
    padding: 0;
    border: 0;
    background: transparent;
}

.chat-empty-state {
    margin: auto;
    width: min(620px, calc(100% - 32px));
    padding: clamp(22px, 5vw, 42px);
    text-align: center;
}

.chat-area-wrapper:has(#chatWindow .message) .chat-empty-state {
    display: none;
}

.quick-prompt-list {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
    margin-top: 18px;
}

.qa {
    border: 1px solid var(--line-strong);
    background: var(--surface);
    color: var(--accent-strong);
}

#chatWindow {
    flex: 1 1 260px;
    min-height: clamp(180px, 34vh, 460px);
    padding: 18px 22px;
    scroll-padding-bottom: 96px;
}

.message {
    padding: 13px 15px;
    border-radius: var(--radius-md);
}

.message.user {
    background: #dff2e9;
}

.message.assistant {
    background: #f0f3f2;
}

.composer {
    position: sticky;
    bottom: 0;
    flex: 0 0 auto;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
    gap: 10px;
    padding: 14px 16px 16px;
    border-top: 1px solid var(--line);
    background: rgba(255, 255, 255, .96);
    backdrop-filter: blur(14px);
}

select:focus-visible,
textarea:focus-visible,
button:focus-visible,
summary:focus-visible,
a:focus-visible {
    outline: 3px solid rgba(33, 107, 85, .28);
    outline-offset: 2px;
}
```

- [ ] **Step 6: Replace mobile order overrides with matching grid order**

Inside `@media (max-width: 760px)`, use:

```css
/* conversation-first-mobile */
.chat-layout {
    width: calc(100vw - 20px);
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
    margin: 10px auto 18px;
}

.chat-area-wrapper {
    grid-column: 1;
    grid-row: 1;
    max-height: calc(100dvh - 132px);
    border-radius: var(--radius-md);
}

.project-intro {
    grid-column: 1;
    grid-row: 2;
}

.conversation-header {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
    padding: 16px 14px 10px;
}

.control-panel {
    grid-template-columns: minmax(0, 1fr);
}

.response-settings,
.diagnostics-disclosure {
    margin-inline: 10px;
}

.status-rail {
    grid-template-columns: repeat(2, minmax(0, 1fr));
}

.status-pill:last-child {
    grid-column: 1 / -1;
}

.admin-tools-menu {
    position: fixed;
    inset: var(--top-bar-min-height) 10px auto 10px;
    width: auto;
}
```

Remove all mobile `order` declarations and the rules that expose selected external cards outside the collapsed Diagnostics disclosure.

Add:

```css
@media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
        scroll-behavior: auto !important;
        transition-duration: .01ms !important;
        animation-duration: .01ms !important;
        animation-iteration-count: 1 !important;
    }
}
```

- [ ] **Step 7: Run the focused test and verify GREEN**

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
```

Expected: exit 0 and `[AWX][chat-ui] stream heartbeat contract OK`.

- [ ] **Step 8: Commit the visual system**

```powershell
git add -- main/resources/static/css/chat-style.css scripts/chat_ui_stream_contract_tests.js
git diff --cached --check
git commit -m 'feat: add conversation-first chat styling'
```

---

### Task 5: Lock the neutral triadic prompt against majority voting

**Files:**
- Modify: `src/test/java/com/example/lms/ensemble/TriadicDebugJudgeServiceTest.java:64-69`
- Modify: `main/java/com/example/lms/ensemble/EnsembleJudgeService.java:130-151`

**Interfaces:**
- Consumes: the existing exact role set `support`, `support_alternative`, `falsify` and `PromptBuilder.build(PromptContext)`.
- Produces: an explicit prompt-level rule that two SUPPORT dossiers are independent hypotheses, not two votes.

- [ ] **Step 1: Add the failing prompt assertion**

In `neutralJudgeAcceptsOnlyOwnedEvidenceIdsAndUsesCanonicalPromptBuilder()`, add:

```java
assertTrue(factory.prompts.get(0).contains(
        "SUPPORT and SUPPORT_ALTERNATIVE are independent hypotheses, not two votes."));
assertTrue(factory.prompts.get(0).contains(
        "Never decide by candidate count"));
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
$env:AWX_AGENT_HOST = 'desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-chat-console'
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop-chat-console"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop-chat-console"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
.\gradlew.bat test --tests 'com.example.lms.ensemble.TriadicDebugJudgeServiceTest.neutralJudgeAcceptsOnlyOwnedEvidenceIdsAndUsesCanonicalPromptBuilder' --no-daemon --project-cache-dir $pcd
```

Expected: FAIL because the exact no-majority language is not yet present.

- [ ] **Step 3: Add the minimal neutral instruction**

In `TRIADIC_DEBUG_JUDGE_CONTRACT`, immediately after the line naming the three dossiers, add:

```java
SUPPORT and SUPPORT_ALTERNATIVE are independent hypotheses, not two votes.
Never decide by candidate count; compare grounding, contradictions, and decisive evidence IDs.
```

Do not change the parser, call count, decision enum, role set, feature default, controller, or normal chat path.

- [ ] **Step 4: Run the triadic prompt and decision tests**

```powershell
.\gradlew.bat test `
  --tests 'com.example.lms.ensemble.TriadicDebugJudgeServiceTest' `
  --tests 'com.example.lms.ensemble.DiverseSamplingOrchestratorTest' `
  --tests 'com.example.lms.ensemble.EvidenceGroundedTriadicDebugAdjudicatorTest' `
  --no-daemon --project-cache-dir $pcd
```

Expected: BUILD SUCCESSFUL. The tests must continue proving exactly two SUPPORT roles, one FALSIFY role, one neutral call, strict evidence IDs, and fail-soft HOLD.

- [ ] **Step 5: Commit the neutral-role clarification**

```powershell
git add -- main/java/com/example/lms/ensemble/EnsembleJudgeService.java src/test/java/com/example/lms/ensemble/TriadicDebugJudgeServiceTest.java
git diff --cached --check
git commit -m 'test: lock triadic neutral role against majority voting'
```

---

### Task 6: Run local gates and prove the current source in Browser

**Files:**
- Verify only: all files changed in Tasks 1–5
- Write only transient proof: `var/codex-smoke/` and isolated `build/desktop-chat-console/`

**Interfaces:**
- Consumes: all prior task commits.
- Produces: deterministic tests, isolated Gradle proof, count-only secret result, a current-source runtime, and browser-visible responsive/stream lifecycle evidence.

- [ ] **Step 1: Re-run focused deterministic gates**

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
.\gradlew.bat chatUiTest --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

Expected: Node contract OK and Gradle BUILD SUCCESSFUL for both commands.

- [ ] **Step 2: Run the changed-file secret scan count only**

```powershell
$baseline = git log --format=%H --grep='^chore: capture approved chat console baseline$' -n 1
if (-not $baseline) { throw 'baseline commit missing' }
$changed = @(git diff --name-only "$baseline^..HEAD" -- `
  main/resources/templates/chat-ui.html `
  main/resources/static/js/chat.js `
  main/resources/static/css/chat-style.css `
  scripts/chat_ui_stream_contract_tests.js `
  main/java/com/example/lms/ensemble/EnsembleJudgeService.java `
  src/test/java/com/example/lms/ensemble/TriadicDebugJudgeServiceTest.java)
$hits = @($changed | ForEach-Object {
  Select-String -LiteralPath $_ -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}' -AllMatches -ErrorAction SilentlyContinue
})
"[AWX][desktop][security] changedFiles=$($changed.Count) secretHits=$($hits.Count)"
if ($hits.Count -ne 0) { exit 1 }
```

Expected: `secretHits=0`. Do not print matching text.

- [ ] **Step 3: Build a current-source boot artifact**

```powershell
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
$jar = Get-ChildItem -LiteralPath '.\build\desktop-chat-console\libs' -Filter '*.jar' |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if (-not $jar) { throw 'current bootJar missing' }
"jar=$($jar.Name) modified=$($jar.LastWriteTime.ToString('o'))"
```

Expected: BUILD SUCCESSFUL and one current `build\desktop-chat-console\libs` JAR.

- [ ] **Step 4: Start one isolated hidden runtime on port 18167**

```powershell
$port = 18167
if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "port-conflict:$port" }
$proofDir = Join-Path (Get-Location) 'var\codex-smoke\chat-console-18167'
New-Item -ItemType Directory -Force -Path $proofDir | Out-Null
$stdout = Join-Path $proofDir 'runtime.out.log'
$stderr = Join-Path $proofDir 'runtime.err.log'
$runtime = Start-Process -FilePath 'java' `
  -ArgumentList @('-jar', $jar.FullName, '--server.port=18167') `
  -RedirectStandardOutput $stdout `
  -RedirectStandardError $stderr `
  -WindowStyle Hidden `
  -PassThru
$ready = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:18167/chat-ui?codex_design=current' -TimeoutSec 2
    if ($response.StatusCode -eq 200) { $ready = $true; break }
  } catch {}
  Start-Sleep -Seconds 1
}
if (-not $ready) { Stop-Process -Id $runtime.Id -ErrorAction SilentlyContinue; throw 'boot-smoke-missing' }
"runtimePid=$($runtime.Id) port=$port status=ready"
```

Expected: HTTP 200 from the current-source JAR. Logs remain local and are not inlined.

- [ ] **Step 5: Run the responsive Browser checks**

Using the Codex in-app Browser, open `http://127.0.0.1:18167/chat-ui?codex_design=current` and verify each viewport at 320, 375, 639, 760, 1024, and 1440 px:

```text
chat workspace is the leading region
Response settings is a closed named disclosure
Diagnostics is a closed named disclosure
Health is visible while diagnostics are closed
empty state and three starter buttons are visible before the first message
composer is reachable with no horizontal overflow
all visible interactive controls have a usable target and focus indicator
```

Use DOM snapshots for roles/names and one screenshot at 375 px plus one at 1440 px. Do not use the stale 18166/8081 servers as proof.

- [ ] **Step 6: Run the three randomized scenario classes**

Use Search OFF and RAG OFF for the hypothetical prompt:

```text
[TRIAD-HYPOTHETICAL] 2035년 서울의 모든 대중교통이 자율주행으로 전환되었다고 가정한다. SUPPORT 1, SUPPORT 2, FALSIFY 1을 독립적으로 작성하고, 중립 심판은 APPLY/HOLD/REJECT 중 하나를 선택하되 근거가 부족하면 HOLD라고 해줘.
```

Verify the answer labels the premise as hypothetical and includes at least one counterexample or limitation. This public formatting is not reported as the protected adjudicator running.

Use Search OFF for the stable local reasoning prompt:

```text
[TRIAD-STABLE] 비가 내리는 화성이라는 가상 조건에서 우산의 장점과 반례를 각각 한 문장으로 쓰고, 현재 화성 사실과 가상 조건을 구분해줘.
```

Verify the response distinguishes the hypothetical from current Mars conditions or honestly says HOLD.

Then explicitly select Search AUTO or DEEP and send:

```text
[TRIAD-WEB] NASA의 공식 Mars Facts를 웹에서 확인해 현재 화성 표면에 비가 온다고 설명하는지 검증해줘. 공식 출처, 반례, 불확실성을 함께 제시하고 확인할 수 없으면 HOLD라고 해줘.
```

Verify citations or honest `evidence_needed`/HOLD. Missing provider credentials must fail soft without a fabricated result or raw provider error.

- [ ] **Step 7: Re-run the stale-wait, Stop, follow-up, and reload lifecycle**

With a slow local model request:

```text
before threshold: Health = Responding
after stale threshold: heartbeat = Model wait WARN and Health = Response pending WARN
after Stop: heartbeat = Response stopped OK and Health = Response stopped OK
after 30-second server refresh: Health remains Response stopped
after a new accepted send: Health leaves stopped and becomes Responding
after accepted final or sync fallback: Health returns to the server aggregate
after reload: persisted transcript contains no duplicate stopped bubble
```

Record only bounded visible labels, selector states, counts, port, and artifact identity.

- [ ] **Step 8: Refresh the supporting external lanes without mutation**

```powershell
'{"nodeRole":"desktop","root":".","requestId":"chat-console-supabase-final","sessionId":"chat-console"}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - supabase_context_probe
```

Expected: read-only Supabase result. If it remains `project_ref_missing` or MCP 403, report `supabase: evidence_needed`, `mutationAllowed: false`, and continue Desktop-only completion.

Use Computer only for a count-only confirmation that the in-app Browser is visible. Do not automate the terminal, inspect unrelated window titles, or treat Computer reachability as source proof.

- [ ] **Step 9: Stop the isolated runtime and run the final scope audit**

```powershell
if ($runtime -and -not $runtime.HasExited) { Stop-Process -Id $runtime.Id }
git status --short
git diff --check "$baseline^..HEAD"
git log --oneline --decorate -6
```

Expected: the isolated runtime is stopped; only intended commits/files belong to this feature; unrelated pre-existing dirty files remain untouched.

Do not create a final verification commit unless a proof-driven source or test correction is required. If a correction is needed, return to the owning task's RED/GREEN cycle and commit it there.

---

## Final Completion Checklist

- [ ] Approved baseline captured without unrelated staged paths.
- [ ] Stale client wait and primary Health no longer disagree.
- [ ] Successful Stop remains a non-failure terminal state.
- [ ] Server heartbeat cannot overwrite active pending/stopped client truth.
- [ ] Chat leads DOM, visual, keyboard, and screen-reader order.
- [ ] Response settings and Diagnostics are native, named disclosures.
- [ ] Empty state and starter prompts fill without automatic sending or silent control changes.
- [ ] UI has 44 px targets, focus visibility, reduced motion, and no tested horizontal overflow.
- [ ] Existing two-SUPPORT, one-FALSIFY, one-neutral prompt path remains protected, advisory, strict, and non-majoritarian.
- [ ] Node, focused Gradle, LangChain4j purity, sourceSet hygiene, and secret-count gates are green.
- [ ] Current-source Browser proves responsive layout, wait, Stop, follow-up, reload, and all three scenario classes.
- [ ] Supabase remains read-only evidence-needed if project scope/auth is absent.
- [ ] Computer evidence remains count-only supporting proof.
- [ ] No SMB, PatchDrop dispatch, external producer requirement, new framework, or unrelated dirty-file edit was introduced.
