package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFrontendSecurityTest {

    @Test
    void chatUiScriptVersionBumpsForFeaturePoolPendingWaitAndKoreanImageFix() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("/js/chat.js?v=chat-ui-20260922-warn-evidence-v13"));
        assertFalse(html.contains("chat-ui-20260708-feature-pool-pending-v6"));
        assertFalse(html.contains("chat-ui-20260707-rag-budget-v5"));
        assertFalse(html.contains("chat-ui-20260707-rag-heartbeat-v4"));
        assertFalse(html.contains("chat-ui-20260707-rag-heartbeat-v2"));
        assertFalse(html.contains("chat-ui-20260707-stream-cleanup-v1"));
    }

    @Test
    void chatStreamDoesNotExecuteTraceScripts() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertFalse(js.contains("data-trace-script"));
        assertFalse(js.contains("createElement('script')"));
        assertFalse(js.contains("createElement(\"script\")"));
        assertTrue(js.contains("replaceWithSanitizedHtml(holder, payload.html || \"\")"));
        assertEquals(1, countOccurrences(js, "if (type === \"status\")"));
        assertEquals(1, countOccurrences(js, "if (type === \"trace\")"));
        assertEquals(1, countOccurrences(js, "if (type === \"evidence\")"));
        assertFalse(js.contains("updateOrchestrationSignalBar({ streamStatus: \"streaming\", status: payload.data || \"\" });"));
    }

    @Test
    void evidenceRendererUsesTextNodesOnly() throws Exception {
        String evidence = Files.readString(Path.of("main/resources/static/js/evidence-ui.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(evidence.contains("textContent"));
        assertFalse(evidence.contains("innerHTML"));
        assertFalse(evidence.contains("insertAdjacentHTML"));
        assertTrue(orch.contains("el.textContent = text(value, fallback);"));
        assertFalse(orch.contains("innerHTML"));
        assertTrue(orch.contains("scoreDelta"));
        assertTrue(orch.contains("export function renderPlanModeCard"));
        assertTrue(orch.contains("holder.dataset.ttsIgnore = \"1\""));
        assertTrue(orch.contains("valueEl.textContent = text(value, \"-\");"));
    }

    @Test
    void transformerCoreRendererUsesTextNodesAndStreamHandler() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String transformer = Files.readString(Path.of("main/resources/static/js/transformer-core-ui.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("renderTransformerCoreRail"));
        assertTrue(chat.contains("row.appendChild(document.createTextNode(\" \"));"));
        assertTrue(chat.contains("holder.appendChild(document.createTextNode(\" \"));"));
        assertTrue(chat.contains("if (type === \"transformer\")"));
        assertTrue(chat.contains("if (!hasBlocks && meta?.status === \"final\") return;"));
        assertTrue(chat.contains("deriveTransformerBadges(blocks)"));
        assertTrue(chat.contains("const modelReason = modelBlock ? blockReason(modelBlock) : null;"));
        assertTrue(chat.contains("model: modelReason"));
        assertTrue(chat.contains("modelActive: Boolean(modelBlock)"));
        assertTrue(chat.contains("modelBadge: modelBlock ? `Model: ${modelStatus || \"pending\"}` : \"Model\""));
        assertTrue(chat.contains("modelNeedsReview"));
        assertTrue(transformer.contains("export function renderTransformerCoreRail"));
        assertTrue(transformer.contains("setAttribute(\"data-role\", \"transformer-core-summary\")"));
        assertTrue(transformer.contains("setAttribute(\"data-role\", \"transformer-core-flow\")"));
        assertTrue(transformer.contains("setAttribute(\"data-role\", \"transformer-core-debug\")"));
        assertTrue(transformer.contains("slice(0, 12)"));
        assertTrue(transformer.contains("summarizeBlocks"));
        assertTrue(transformer.contains("Verification"));
        assertTrue(transformer.contains("Debug"));
        assertTrue(transformer.contains("Exception"));
        assertTrue(orch.contains("setBadge(root, \"model\", has(\"modelActive\") ? Boolean(partial.modelActive) : undefined, partial.modelBadge);"));
        assertTrue(orch.contains("setBadge(root, \"dpp\""));
        assertTrue(orch.contains("setBadge(root, \"cfvm\""));
        assertTrue(orch.contains("setBadge(root, \"supabase\""));
        assertTrue(html.contains("data-orch-badge=\"model\""));
        assertTrue(html.contains("data-orch-badge=\"dpp\""));
        assertTrue(html.contains("data-orch-badge=\"cfvm\""));
        assertTrue(html.contains("data-orch-badge=\"supabase\""));
        assertTrue(css.contains("repeat(auto-fit, minmax(92px, 1fr))"));
        assertTrue(transformer.contains("textContent"));
        assertFalse(transformer.contains("innerHTML"));
        assertFalse(transformer.contains("insertAdjacentHTML"));
        assertFalse(transformer.contains("createElement('script')"));
        assertFalse(transformer.contains("createElement(\"script\")"));
    }

    @Test
    void topUtilityBarUsesContentHeightInsteadOfClippingDebugSurfaces() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);
        String modelPickerCss = Files.readString(Path.of("main/resources/static/css/model-picker.css"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String refreshedTopBarBlock = css.substring(css.lastIndexOf(".top-utility-bar {"),
                css.indexOf("}", css.lastIndexOf(".top-utility-bar {")) + 1);
        String refreshedMainBlock = css.substring(css.lastIndexOf(".main-container {"),
                css.indexOf("}", css.lastIndexOf(".main-container {")) + 1);
        String refreshedChatWrapperBlock = cssBlockAfter(css, ".chat-area-wrapper {\n    grid-column: 1;");
        String operatorDebugBlock = cssBlockAfter(css, ".operator-debug-console {\n    display: grid;");
        String modelStrategyBlock = cssBlockAfter(css, ".model-strategy-bar {\n    position: static;");
        String modelStrategyLabelBlock = cssBlockAfter(css, ".model-strategy-bar strong {");

        assertFalse(css.contains(".top-utility-bar {\n    height: var(--top-bar-height);"),
                "fixed top-bar height clips or overlaps the model picker and signal bar");
        assertFalse(refreshedTopBarBlock.contains("align-content: center;"),
                "centered grid content can overflow upward when debug surfaces wrap");
        assertTrue(css.contains("min-height: var(--top-bar-min-height);"));
        assertTrue(css.contains("body {\n    min-height: 100vh;\n    display: flex;\n    flex-direction: column;"));
        assertTrue(refreshedMainBlock.contains("flex: 1 1 auto;"));
        assertTrue(refreshedMainBlock.contains("min-height: 0;"));
        assertTrue(refreshedMainBlock.contains("height: auto;"));
        assertTrue(refreshedChatWrapperBlock.contains("overflow: hidden;"),
                "the fixed-height wrapper must contain the static composer");
        assertFalse(refreshedChatWrapperBlock.contains("overflow: auto;")
                        || refreshedChatWrapperBlock.contains("overflow: scroll;"),
                "the chat wrapper must not become the focus-scroll container");
        assertTrue(css.contains("chat-supporting-proof-scroll-lock"));
        assertTrue(css.contains(".debug-proof-strip {\n    max-height: 58px;"));
        assertTrue(css.contains(".debug-flow-rail {\n    max-height: 58px;"));
        assertTrue(css.contains("overscroll-behavior: contain;"));
        assertTrue(css.contains(".diagnostics-stack > .brain-state-panel--compact {\n    max-height: 76px;"));
        assertTrue(operatorDebugBlock.contains("grid-template-columns: minmax(180px, .28fr) minmax(0, 1fr);"));
        assertTrue(operatorDebugBlock.contains("overflow: visible;"));
        assertTrue(css.contains("grid-template-columns: repeat(7, minmax(96px, 1fr));"));
        assertTrue(css.contains("min-height: 44px;"));
        assertTrue(css.contains(".debug-console-foot {\n    display: none;"));
        assertTrue(modelStrategyBlock.contains("flex-wrap: nowrap;"));
        assertTrue(modelStrategyBlock.contains("overflow-x: auto;"));
        assertTrue(modelStrategyBlock.contains("overflow-y: hidden;"));
        assertTrue(modelStrategyLabelBlock.contains("white-space: nowrap;"));
        assertTrue(modelStrategyLabelBlock.contains("flex: 0 0 auto;"));
        assertTrue(css.contains("grid-template-columns: repeat(auto-fit, minmax(82px, 1fr));"));
        assertTrue(css.contains(".orch-signal-badges {\n    grid-column: span 2;"));
        assertTrue(modelPickerCss.contains("max-height: 42px;"));
        assertTrue(modelPickerCss.contains("overflow-x: auto;"));
        assertTrue(modelPickerCss.contains("overflow-y: hidden;"));
        assertTrue(modelPickerCss.contains("flex-wrap: nowrap;"));
        assertTrue(css.contains("@media (max-height: 760px) {"));
        assertTrue(css.contains(".chat-command-copy p,\n    .section-kicker {\n        display: none;"));
        assertTrue(css.contains("grid-template-columns: repeat(7, minmax(82px, 1fr));"));
        assertTrue(css.contains("grid-template-columns: repeat(auto-fit, minmax(72px, 1fr));"));
        assertTrue(css.contains("min-height: 36px;"));
        assertTrue(css.contains("#chatWindow {\n        flex: 1 1 180px;"));
        assertTrue(css.contains("min-height: clamp(150px, 24vh, 220px);"));
        assertTrue(css.contains(".model-strategy-bar {\n        gap: .35rem;\n        max-height: 54px;"));
        assertTrue(css.contains(".drop-zone,\n    .input-toggle-row {\n        display: none !important;"));
        assertTrue(html.contains("min-height: var(--top-bar-min-height);"));
        assertTrue(html.contains("height: auto;"));
    }

    @Test
    void protectedOperatorMenuLinksExposeLabelsBeforeAuthRedirect() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-menu-action=\"open-brain-state\" data-admin-surface=\"protected\" aria-label=\"Open protected Brain state console\" title=\"Open protected Brain state console\""));
        assertTrue(html.contains("data-menu-action=\"open-pipeline-status\" data-admin-surface=\"protected\" aria-label=\"Open protected Pipeline status console\" title=\"Open protected Pipeline status console\""));
        assertTrue(html.contains("data-menu-action=\"open-rag-ops\" data-admin-surface=\"protected\" aria-label=\"Open protected RAG operations console\" title=\"Open protected RAG operations console\""));
        assertTrue(html.contains("data-menu-action=\"open-vector-diagnostics\" data-admin-surface=\"protected\" aria-label=\"Open protected Vector diagnostics console\" title=\"Open protected Vector diagnostics console\""));
        assertTrue(html.contains("data-menu-action=\"open-model-settings\" data-admin-surface=\"protected\" aria-label=\"Open protected Model settings console\" title=\"Open protected Model settings console\""));
    }

    @Test
    void operationalStabilityQuickPromptExposesActionLabelAndStableSelector() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"qa\" type=\"button\" data-testid=\"quick-prompt-operational-stability\""));
        assertTrue(html.contains("aria-label=\"Use operational stability quick prompt\""));
        assertTrue(html.contains("title=\"Use operational stability quick prompt\""));
    }

    @Test
    void chatTopBarShowsRedactedDebugHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"debugHeartbeatBar\""));
        assertTrue(html.contains("data-debug-heartbeat=\"root\""));
        assertTrue(html.contains("data-debug-heartbeat-summary"));
        assertTrue(html.contains("data-debug-heartbeat-field=\"core\""));
        assertTrue(html.contains("data-debug-heartbeat-field=\"ui\""));
        assertTrue(html.contains("data-debug-heartbeat-field=\"external\""));
        assertTrue(html.contains("data-debug-heartbeat-field=\"supabase\""));
        assertTrue(html.contains("data-debug-heartbeat-field=\"browser\""));

        assertTrue(css.contains(".debug-heartbeat-bar {"));
        assertTrue(css.contains("grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));"));
        assertTrue(css.contains(".debug-heartbeat-summary {"));
        assertTrue(css.contains("grid-column: 1 / -1;"));
        assertTrue(css.contains(".debug-heartbeat-card strong {"));
        assertTrue(css.contains(".debug-heartbeat-card small {"));

        assertTrue(chat.contains("const PIPELINE_HEALTH_API = '/agent/db-context/pipeline-health';"));
        assertTrue(chat.contains("debugHeartbeatBar: $(\"debugHeartbeatBar\")"));
        assertTrue(chat.contains("function renderDebugHeartbeat(data = {}) {"));
        assertTrue(chat.contains("setDebugHeartbeatField('core', coreStatus, coreDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('ui', uiStatus, uiDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('external', externalHeartbeatStatus, externalHeartbeatDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('supabase', supabaseStatus, supabaseDetail);"));
        assertTrue(chat.contains("const browserSupportingProof = isDesktopLocalReady(goalNextRow) && desktopOnlyReady"));
        assertTrue(chat.contains("const browserUiStatus = browserSupportingProof ? 'SUPPORTING' : browserStatus;"));
        assertTrue(chat.contains("setDebugHeartbeatField('browser', browserUiStatus, browserUiDetail);"));
        assertTrue(chat.contains("function refreshDebugHeartbeat()"));
        assertTrue(chat.contains("apiCall(PIPELINE_HEALTH_API"));
        assertTrue(chat.contains("window.setInterval(refreshDebugHeartbeat, 30000);"));
        assertFalse(chat.contains("debugHeartbeatBar.innerHTML"));
    }

    @Test
    void chatTopBarShowsOneLineDebugSummaryFromExistingStatuses() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-summary"));
        assertTrue(html.contains("<strong>Core wait / UI wait / External wait</strong>"));

        assertTrue(chat.contains("function setDebugHeartbeatSummary(status, detail) {"));
        assertTrue(chat.contains("const externalSummaryStatus = healthRailExternalStatus === 'SUPPORTING' ? 'OK' : externalStatus;"));
        assertTrue(chat.contains("const summaryStatus = [coreStatus, uiStatus, externalSummaryStatus, gateStatus, actionStatus].includes('WARN') || modelStatus !== 'OK' ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const summaryNextAction = primaryModelNextAction"));
        assertTrue(chat.contains("|| effectiveNextExternalAction"));
        assertTrue(chat.contains("|| (demandDrivenExternalOnly ? 'none' : (goalNextRow.nextAction || goalNextRow.firstAction))"));
        assertTrue(chat.contains("const summaryDetail = `core:${coreStatus} ui:${uiStatus} model:${modelStatus} external:${healthRailExternalStatus} next:${summaryNextAction}`;"));
        assertTrue(chat.contains("setDebugHeartbeatSummary(summaryStatus, summaryDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatSummary('WARN', 'pipeline_health_unavailable');"));

        assertFalse(chat.contains("summaryRaw"));
        assertFalse(chat.contains("summarySecret"));
        assertFalse(chat.contains("summaryAuthorization"));
    }

    @Test
    void chatTopBarSummaryIncludesLiveDefaultModelResponseState() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const debugHeartbeatSummaryState = {"));
        assertTrue(chat.contains("function refreshDebugHeartbeatSummary() {"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.liveStatus = liveStatus;"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.waitStatus = waitStatus;"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.timeoutStatus = timeoutStatus;"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.cancelStatus = cancelStatus;"));
        assertTrue(chat.contains("live:${debugHeartbeatSummaryState.liveStatus} wait:${debugHeartbeatSummaryState.waitStatus} timeout:${debugHeartbeatSummaryState.timeoutStatus} cancel:${debugHeartbeatSummaryState.cancelStatus}"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.nextAction = summaryNextAction;"));
        assertTrue(chat.contains("refreshDebugHeartbeatSummary();"));

        assertFalse(chat.contains("debugHeartbeatSummaryState.raw"));
        assertFalse(chat.contains("debugHeartbeatSummaryState.secret"));
        assertFalse(chat.contains("debugHeartbeatSummaryState.authorization"));
    }

    @Test
    void desktopOnlyHealthRailDemotesOptionalExternalProofToSupporting() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const match = text.match(new RegExp(`\\\\b${name}:(OK|WARN|UNKNOWN|SUPPORTING)\\\\b`, \"i\"));"));
        assertTrue(chat.contains("proof === \"SUPPORTING\" || external === \"SUPPORTING\" ? \"external proof supporting\""));
        assertTrue(chat.contains("const healthRailProofStatus = desktopOnlyReady && externalProofStatus === 'WARN' && !externalProofSeriousWarn ? 'SUPPORTING' : externalProofStatus;"));
        assertTrue(chat.contains("const healthRailExternalStatus = desktopOnlyReady && externalStatus === 'WARN' && externalOverviewBenign ? 'SUPPORTING' : externalStatus;"));
        assertTrue(chat.contains("const healthRailStatus = healthRailProofStatus === 'WARN' ? 'WARN' : liveStatus;"));
        assertTrue(chat.contains("const healthRailDetail = `live:${liveStatus} core:${coreStatus} ui:${uiStatus} model:${modelStatus} answer:${answerStatus} proof:${healthRailProofStatus} external:${healthRailExternalStatus}`;"));
        assertTrue(chat.contains("const externalProofUiStatus = healthRailProofStatus === 'SUPPORTING' ? 'OK' : externalProofStatus;"));
        assertTrue(chat.contains("const externalProofUiDetail = healthRailProofStatus === 'SUPPORTING' ? `supporting ${externalProofDetail}` : externalProofDetail;"));
        assertFalse(chat.contains("const healthRailStatus = externalProofStatus === 'WARN' ? 'WARN' : liveStatus;"));
    }

    @Test
    void chatTopBarShowsCompactDebugMatrixForCoreUiAndExternalState() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-heartbeat-matrix\""));
        assertTrue(html.contains("data-debug-matrix=\"root\""));
        assertTrue(html.contains("data-debug-matrix-cell=\"core-ui\""));
        assertTrue(html.contains("data-debug-matrix-cell=\"model-answer\""));
        assertTrue(html.contains("data-debug-matrix-cell=\"search-trace\""));
        assertTrue(html.contains("data-debug-matrix-cell=\"external-proof\""));

        assertTrue(css.contains(".debug-heartbeat-matrix {"));
        assertTrue(css.contains("grid-template-columns: repeat(4, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-matrix-cell[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-matrix-cell[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugMatrixCell(name, status, title, detail) {"));
        assertTrue(chat.contains("setDebugMatrixCell('core-ui', coreUiStatus, 'Core/UI', coreUiDetail);"));
        assertTrue(chat.contains("setDebugMatrixCell('search-trace', searchTraceStatus, 'Search/Trace', searchTraceDetail);"));
        assertTrue(chat.contains("setDebugMatrixCell('external-proof', externalProofUiStatus, 'External Proof', externalProofUiDetail);"));
        assertTrue(chat.contains("const explicitLocalLlmOperatorAction = modelRuntime.localLlmOperatorAction || {};"));
        assertTrue(chat.contains("const localLlmOperatorAction = explicitLocalLlmOperatorAction;"));
        assertTrue(chat.contains("llm:${localLlmOperatorAction.failureClass || 'none'} next:${localLlmOperatorAction.nextAction || 'none'} score:${localLlmOperatorAction.actionScore ?? 0}"));
        assertTrue(chat.contains("const modelAnswerMatrixDetail = `${modelAnswerDetail} live:${liveStatus}${localLlmOperatorDetail}`;"));
        assertTrue(chat.contains("setDebugMatrixCell('model-answer', modelAnswerLiveStatus, 'Model/Answer', modelAnswerMatrixDetail);"));

        assertFalse(chat.contains("debugMatrixRaw"));
        assertFalse(chat.contains("debugMatrixSecret"));
        assertFalse(chat.contains("debugMatrixAuthorization"));
    }

    @Test
    void chatTopBarShowsOneGlanceDebugCockpitForRuntimeAndNextAction() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-cockpit-rail\""));
        assertTrue(html.contains("data-debug-cockpit=\"root\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"stream\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"model\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"search\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"external\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"next\""));

        assertTrue(css.contains(".debug-cockpit-rail {"));
        assertTrue(css.contains("grid-template-columns: repeat(5, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-cockpit-cell[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-cockpit-cell[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugCockpitCell(name, status, title, detail) {"));
        assertTrue(chat.contains("function safeDebugCockpitDetail(value) {"));
        assertTrue(chat.contains("small.textContent = safeDebugCockpitDetail(detail);"));
        assertTrue(chat.contains("[url]"));
        assertTrue(chat.contains("[path]"));
        assertTrue(chat.contains("project_ref:[redacted]"));
        assertTrue(chat.contains("slice(0, 180)"));
        assertTrue(chat.contains("function updateDebugCockpitFromState() {"));
        assertTrue(chat.contains("setDebugCockpitCell('stream', debugHeartbeatSummaryState.liveStatus, 'Stream',"));
        assertTrue(chat.contains("setDebugCockpitCell('model', modelStatus, 'Model',"));
        assertTrue(chat.contains("setDebugCockpitCell('search', searchTraceStatus, 'Search',"));
        assertTrue(chat.contains("setDebugCockpitCell('external', externalProofUiStatus, 'External',"));
        assertTrue(chat.contains("setDebugCockpitCell('next', actionStatus, 'Next', actionDetail);"));

        assertFalse(chat.contains("debugCockpitRaw"));
        assertFalse(chat.contains("debugCockpitSecret"));
        assertFalse(chat.contains("debugCockpitAuthorization"));
    }

    @Test
    void debugFxSummaryPrioritizesLocalLlmActionBeforeHarmonyObservation() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String body = functionBody(chat, "function debugFxSummary(payload = {}) {");
        String actionHelper = functionBody(chat, "function effectiveLocalLlmDebugAction(labels = {}) {");

        assertTrue(body.contains("const debugAiNextAction = debugFxLabel(labels, \"debugAiNextAction\");"));
        assertTrue(body.contains("const localLlmAction = effectiveLocalLlmDebugAction(labels);"));
        assertTrue(body.contains("const localLlmFailureClass = localLlmAction.failureClass;"));
        assertTrue(body.contains("const localLlmNextAction = localLlmAction.nextAction;"));
        assertTrue(body.contains("const localLlmNeedsAction = localLlmNextAction && localLlmFailureClass && localLlmFailureClass !== \"none\";"));
        assertTrue(body.contains("const nextAction = localLlmNeedsAction ? localLlmNextAction : (chatHarmonyDebugNextActionFallback(labels, debugAiNextAction)\n    || debugFxLabel(labels, \"agentDbContextNextAction\")\n    || debugFxLabel(labels, \"supabaseNextAction\"));"));
        assertTrue(actionHelper.contains("const failureClass = debugFxLabel(labels, \"localLlmFailureClass\");"));
        assertTrue(actionHelper.contains("const nextAction = debugFxLabel(labels, \"localLlmNextAction\");"));
        assertTrue(actionHelper.contains("const upstreamFailureClass = debugFxLabel(labels, \"localLlmUpstreamFailureClass\");"));
        assertTrue(actionHelper.contains("failureClass: upstreamActive ? upstreamFailureClass : failureClass"));
        assertTrue(actionHelper.contains("nextAction: upstreamActive ? (upstreamNextAction || nextAction) : nextAction"));
        assertTrue(body.contains("pushDebugFxPart(parts, \"Local LLM\", ["));
        assertTrue(body.contains("localLlmNeedsAction ? `next:${localLlmNextAction}` : \"\""));
        assertFalse(body.contains("const nextAction = chatHarmonyDebugNextActionFallback(labels, debugAiNextAction)\n    || debugFxLabel(labels, \"localLlmNextAction\")"));
    }

    @Test
    void debugFxSummarySurfacesRedactedNativeRouteAndWorkerTermination() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String body = functionBody(chat, "function debugFxSummary(payload = {}) {");

        assertTrue(body.contains("const nativeRoute = debugFxLabel(labels, \"ollamaNativeRoute\");"));
        assertTrue(body.contains("const nativePromptLength = debugFxLabel(labels, \"ollamaNativePromptLength\");"));
        assertTrue(body.contains("const nativeMaxTokens = debugFxLabel(labels, \"ollamaNativeMaxTokens\");"));
        assertTrue(body.contains("const workerTermination = debugFxLabel(labels, \"llmTimeoutWorkerTermination\");"));
        assertTrue(body.contains("nativeRoute ? `route:${nativeRoute === \"true\" ? \"native\" : \"compat\"}` : \"\""));
        assertTrue(body.contains("nativePromptLength ? `promptChars:${nativePromptLength}` : \"\""));
        assertTrue(body.contains("nativeMaxTokens ? `maxTokens:${nativeMaxTokens}` : \"\""));
        assertTrue(body.contains("workerTermination ? `executorExit:${workerTermination}` : \"\""));
        assertFalse(body.contains("llm.factory.baseUrl"));
        assertFalse(body.contains("llm.client.rawPrompt"));
    }

    @Test
    void debugFxLocalLlmOperatorActionImmediatelyPromotesHeartbeatSummary() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String helper = functionBody(chat, "function promoteLocalLlmDebugFxToHeartbeat(labels = {}) {");
        String render = functionBody(chat, "function renderDebugFxTrace(payload, bubble) {");

        assertTrue(helper.contains("const localLlmAction = effectiveLocalLlmDebugAction(labels);"));
        assertTrue(helper.contains("const localLlmFailureClass = localLlmAction.failureClass;"));
        assertTrue(helper.contains("const localLlmNextAction = localLlmAction.nextAction;"));
        assertTrue(helper.contains("if (!localLlmNextAction || !localLlmFailureClass || localLlmFailureClass === \"none\") return false;"));
        assertTrue(helper.contains("debugHeartbeatSummaryState.nextAction = localLlmNextAction;"));
        assertTrue(helper.contains("setDebugHeartbeatField('model', 'WARN', detail);"));
        assertTrue(helper.contains("refreshDebugHeartbeatSummary();"));
        assertTrue(render.contains("promoteLocalLlmDebugFxToHeartbeat(labels);"));
    }

    @Test
    void chatTopBarShowsMissionStripWithCurrentBlockingAxis() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-mission-strip\""));
        assertTrue(html.contains("data-debug-mission=\"root\""));
        assertTrue(html.contains("data-debug-mission-axis=\"core\""));
        assertTrue(html.contains("data-debug-mission-axis=\"ui\""));
        assertTrue(html.contains("data-debug-mission-axis=\"external\""));
        assertTrue(html.contains("data-debug-mission-axis=\"next\""));
        assertTrue(html.contains("data-debug-mission-axis=\"focus\""));

        assertTrue(css.contains(".debug-mission-strip {"));
        assertTrue(css.contains("grid-template-columns: repeat(5, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-mission-axis[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-mission-axis[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugMissionAxis(name, status, title, value, detail) {"));
        assertTrue(chat.contains("function firstWarnMissionAxis("));
        assertTrue(chat.contains("const missionFocus = firstWarnMissionAxis("));
        assertTrue(chat.contains("setDebugMissionAxis('core', coreUiStatus, 'Core',"));
        assertTrue(chat.contains("setDebugMissionAxis('ui', uiStatus, 'UI',"));
        assertTrue(chat.contains("{ status: externalProofUiStatus, value: externalProofUiStatus, detail: externalProofUiDetail }"));
        assertTrue(chat.contains("setDebugMissionAxis('external', externalProofUiStatus, 'External',"));
        assertTrue(chat.contains("setDebugMissionAxis('next', actionStatus, 'Next',"));
        assertTrue(chat.contains("setDebugMissionAxis('focus', missionFocus.status, 'Focus', missionFocus.value, missionFocus.detail);"));
        assertTrue(chat.contains("setDebugMissionAxis('focus', 'WARN', 'Focus', 'pipeline', 'pipeline_health_unavailable');"));

        assertFalse(chat.contains("debugMissionRaw"));
        assertFalse(chat.contains("debugMissionSecret"));
        assertFalse(chat.contains("debugMissionAuthorization"));
    }

    @Test
    void chatTopBarShowsFlowRailForPipelineDebugSequence() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-flow-rail\""));
        assertTrue(html.contains("data-debug-flow=\"root\""));
        assertTrue(html.contains("data-debug-flow-step=\"input\""));
        assertTrue(html.contains("data-debug-flow-step=\"model\""));
        assertTrue(html.contains("data-debug-flow-step=\"search\""));
        assertTrue(html.contains("data-debug-flow-step=\"trace\""));
        assertTrue(html.contains("data-debug-flow-step=\"external\""));
        assertTrue(html.contains("data-debug-flow-step=\"action\""));

        assertTrue(css.contains(".debug-flow-rail {"));
        assertTrue(css.contains("grid-template-columns: repeat(6, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-flow-step[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-flow-step[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugFlowStep(name, status, title, value, detail) {"));
        assertTrue(chat.contains("setDebugFlowStep('input', debugHeartbeatSummaryState.liveStatus, 'Input',"));
        assertTrue(chat.contains("setDebugFlowStep('model', modelAnswerStatus, 'Model',"));
        assertTrue(chat.contains("setDebugFlowStep('search', searchTraceStatus, 'Search',"));
        assertTrue(chat.contains("setDebugFlowStep('trace', traceStatus, 'Trace',"));
        assertTrue(chat.contains("setDebugFlowStep('external', externalProofUiStatus, 'External',"));
        assertTrue(chat.contains("setDebugFlowStep('action', actionStatus, 'Action',"));
        assertTrue(chat.contains("setDebugFlowStep('action', 'WARN', 'Action', 'WAIT', 'next_proof_unknown');"));
        assertTrue(chat.contains("small.textContent = safeDebugCockpitDetail(detail);"));

        assertFalse(chat.contains("debugFlowRaw"));
        assertFalse(chat.contains("debugFlowSecret"));
        assertFalse(chat.contains("debugFlowAuthorization"));
    }

    @Test
    void chatHeartbeatVisibleDetailsUseSharedRedactor() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(functionBody(chat, "function setDebugHeartbeatField")
                .contains("small.textContent = safeDebugCockpitDetail(detail);"));
        assertTrue(functionBody(chat, "function setDebugHeartbeatSummary")
                .contains("small.textContent = safeDebugCockpitDetail(detail);"));
        assertTrue(functionBody(chat, "function setDebugMatrixCell")
                .contains("small.textContent = safeDebugCockpitDetail(detail);"));
        assertTrue(chat.contains("function ensureDebugCellTextSeparators(cell) {"));
        assertTrue(functionBody(chat, "function setDebugHeartbeatField")
                .contains("ensureDebugCellTextSeparators(card);"));
        assertTrue(functionBody(chat, "function setDebugMatrixCell")
                .contains("ensureDebugCellTextSeparators(cell);"));
        assertTrue(functionBody(chat, "function setDebugProofCell")
                .contains("ensureDebugCellTextSeparators(cell);"));
        assertTrue(chat.contains(".replace(/https?:\\/\\/\\S+/gi, '[url]')"));
        assertTrue(chat.contains(".replace(/project[_-]?ref[:=][A-Za-z0-9_-]+/gi, 'project_ref:[redacted]')"));

        assertFalse(functionBody(chat, "function setDebugHeartbeatField")
                .contains("small.textContent = String(detail || 'no detail');"));
        assertFalse(functionBody(chat, "function setDebugHeartbeatSummary")
                .contains("small.textContent = String(detail || 'no detail');"));
        assertFalse(functionBody(chat, "function setDebugMatrixCell")
                .contains("small.textContent = String(detail || 'no detail');"));
    }

    @Test
    void chatExternalProofHeartbeatCompactsStaleBrowserAndComputerActions() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String compactExternalEvidenceDetail = functionBody(chat, "function compactExternalEvidenceDetail");

        assertTrue(compactExternalEvidenceDetail.contains(".replace(/browser_ui_smoke_stale/g, \"browser proof stale\")"));
        assertTrue(compactExternalEvidenceDetail.contains(".replace(/computer_use_smoke_stale/g, \"computer proof stale\")"));
        assertTrue(compactExternalEvidenceDetail.contains(".replace(/run_browser_local_ui_smoke/g, \"refresh browser proof\")"));
        assertTrue(compactExternalEvidenceDetail.contains(".replace(/run_computer_use_lightweight_smoke/g, \"refresh computer proof\")"));
        assertTrue(compactExternalEvidenceDetail.contains(".replace(/countOnly:true/g, \"count-only:true\")"));
    }

    @Test
    void chatVisibleDebugDetailsCompactProtectedAdminCodes() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function compactProtectedOperatorDetail(value) {"));
        assertTrue(chat.contains(".replace(/admin_pipeline_not_exposed/g, \"auth disabled test mode\")"));
        assertTrue(chat.contains(".replace(/heartbeat_json_available/g, \"heartbeat available\")"));
        assertTrue(functionBody(chat, "function compactHeartbeatDetail")
                .contains("return compactProtectedOperatorDetail(compactExternalEvidenceDetail(name, safe));"));
        assertTrue(functionBody(chat, "function compactVisibleDebugDetail")
                .contains("return compactProtectedOperatorDetail(safeDebugCockpitDetail(detail));"));
    }

    @Test
    void chatTopBarShowsProofStripForLocalExternalReadiness() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-proof-strip\""));
        assertTrue(html.contains("data-debug-proof=\"root\""));
        assertTrue(html.contains("data-debug-proof-cell=\"local\""));
        assertTrue(html.contains("data-debug-proof-cell=\"browser\""));
        assertTrue(html.contains("data-debug-proof-cell=\"computer\""));
        assertTrue(html.contains("data-debug-proof-cell=\"supabase\""));
        assertTrue(html.contains("data-debug-proof-cell=\"producer\""));
        assertTrue(html.contains("data-debug-proof-cell=\"action\""));

        assertTrue(css.contains(".debug-proof-strip {"));
        assertTrue(css.contains("grid-template-columns: repeat(6, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-proof-cell[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-proof-cell[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugProofCell(name, status, title, value, detail) {"));
        assertTrue(chat.contains("const localProofStatus = goalNextRow.sourceHealthExit === 0 && goalNextRow.completionAuditExit === 0 ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const producerRequired = explicitProducerEvidenceRequired || blockingPatchDrop;"));
        assertTrue(chat.contains("const producerProofStatus = producerRequired"));
        assertTrue(chat.contains("optional rows:${producerEvidenceRows.length} patchdrop:${patchDropStatus}"));
        assertTrue(chat.contains("setDebugProofCell('local', localProofStatus, 'Local',"));
        assertTrue(chat.contains("setDebugProofCell('browser', browserUiStatus, 'Browser',"));
        assertTrue(chat.contains("setDebugProofCell('computer', computerUiStatus, 'Computer',"));
        assertTrue(chat.contains("setDebugProofCell('supabase', supabaseStatus, 'Supabase',"));
        assertTrue(chat.contains("setDebugProofCell('producer', producerProofStatus, 'Producer',"));
        assertTrue(chat.contains("setDebugProofCell('action', actionStatus, 'Action',"));
        assertTrue(functionBody(chat, "function setDebugProofCell")
                .contains("small.textContent = safeDebugCockpitDetail(detail);"));

        assertFalse(chat.contains("debugProofRaw"));
        assertFalse(chat.contains("debugProofSecret"));
        assertFalse(chat.contains("debugProofAuthorization"));
    }

    @Test
    void chatTopBarShowsNextExternalDebugActionWithoutSecrets() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"action\""));
        assertTrue(html.contains("debug-heartbeat-card--wide"));

        assertTrue(css.contains(".debug-heartbeat-card--wide {"));
        assertTrue(css.contains("grid-column: span 2;"));

        assertTrue(chat.contains("const goalNextRow = externalRows.find((item) => item?.service === 'goal-next-auto') || {};"));
        assertTrue(chat.contains("const demandDrivenExternalOnly = isDemandDrivenExternalEvidenceOnly("));
        assertTrue(chat.contains("const effectiveNextExternalAction = demandDrivenExternalOnly ? \"\" : nextExternalAction;"));
        assertTrue(chat.contains("const desktopOnlyReady = (isDesktopLocalReady(goalNextRow) || demandDrivenExternalOnly)"));
        assertTrue(chat.contains("&& !blockingPatchDrop;"));
        assertTrue(chat.contains("const actionStatus = !desktopOnlyReady && (goalNextRow.localPatchJustified === false || goalNextRow.decision === 'evidence_needed') ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("function primaryExternalNextAction(goalNextRow, supabaseRow) {"));
        assertTrue(chat.contains("const goalAction = normalizedNextAction(goalNextRow.nextAction) || normalizedNextAction(goalNextRow.firstAction);"));
        assertTrue(chat.contains("if (isDesktopLocalReady(goalNextRow) && isReadOnlySupabaseProbeAction(supabaseRow)) {"));
        assertTrue(chat.contains("return normalizedNextAction(supabaseRow.nextAction);"));
        assertTrue(chat.contains("const actionDetail = `next:${effectiveNextExternalAction || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('action', actionStatus, actionDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('action', 'WARN', 'next_proof_unknown');"));

        assertFalse(chat.contains("process.env"));
        assertFalse(chat.contains("SUPABASE_ACCESS_TOKEN"));
        assertFalse(chat.contains("SERVICE_ROLE"));
    }

    @Test
    void chatTopBarShowsSupabaseExternalInputNamesWithoutSecretValues() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const supabaseRequiredEnv = supabaseRow.requiredEnvNames || goalNextRow.supabaseRequiredEnvNames || 'none';"));
        assertTrue(chat.contains("const supabaseRequiredMcp = supabaseRow.requiredMcpTools || goalNextRow.supabaseRequiredMcpTools || 'none';"));
        assertTrue(chat.contains("const supabaseDetail = `scope:${supabaseRow.projectScopeStatus || 'unknown'} needed:${supabaseRow.evidenceNeededCount ?? goalNextRow.supabaseEvidenceNeededCount ?? 0} env:${supabaseRequiredEnv} mcp:${supabaseRequiredMcp} next:${supabaseRow.nextAction || 'none'}`;"));

        assertFalse(chat.contains("SUPABASE_ACCESS_TOKEN"));
        assertFalse(chat.contains("SERVICE_ROLE"));
        assertFalse(chat.contains("supabaseKey"));
    }

    @Test
    void chatTopBarShowsGoalNextGateHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"gate\""));
        assertTrue(html.contains("<span>Gate</span>"));

        assertTrue(chat.contains("const gateStatus = !desktopOnlyReady && (goalNextRow.decision === 'evidence_needed' || goalNextRow.externalInputGateStatus === 'external_input_needed' || goalNextRow.localPatchJustified === false) ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const gateDetail = `gate:${goalNextRow.externalInputGateStatus || 'unknown'} source:${goalNextRow.sourceHealthExit ?? 'n/a'} audit:${goalNextRow.completionAuditExit ?? 'n/a'} localPatch:${goalNextRow.localPatchJustified === false ? 'no' : 'yes'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('gate', gateStatus, gateDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('gate', 'WARN', 'goal_next_gate_unknown');"));

        assertFalse(chat.contains("summaryPath"));
        assertFalse(chat.contains("summaryFileUpdatedAt"));
    }

    @Test
    void chatTopBarShowsDefaultModelRuntimeHeartbeatWithoutRawModel() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"model\""));
        assertTrue(html.contains("<span>Model</span>"));

        assertTrue(chat.contains("const modelRuntime = data.modelRuntime || {};"));
        assertTrue(chat.contains("const assistantModelFallback = explicitLocalLlmOperatorPresent ? null : lastAssistantModelFallback;"));
        assertTrue(chat.contains("function modelRuntimeStatus(modelRuntime, assistantModelFallback) {"));
        assertTrue(chat.contains("const modelStatus = recoveryDetail"));
        assertTrue(chat.contains("lastLocalLlmRecovery.state === 'READY' && lastLocalLlmRecovery.modelReady === true"));
        assertTrue(chat.contains(": modelRuntimeStatus(modelRuntime, assistantModelFallback);"));
        assertTrue(chat.contains("const modelRuntimeDetail = `route:${modelRuntime.route || 'unknown'} delivery:${modelRuntime.deliveryState || 'unknown'} wait:${modelRuntime.defaultWaitCode || 'none'} hits:${modelRuntime.timeoutHits ?? 0} len:${modelRuntime.modelLength ?? 0} evidence:${modelRuntime.reason || 'unknown'} source:${modelRuntime.source || 'unknown'}`;"));
        assertTrue(chat.contains("const modelDetail = recoveryDetail"));
        assertTrue(chat.contains("localLlmOperatorPresent && !localLlmOperatorCleared ? localLlmOperatorDetail : ''"));
        assertTrue(chat.contains("function compactModelDebugDetail(detail) {"));
        assertTrue(chat.contains("return `${modelAnswerText}local safe fallback reason:${reason}${lengthText} next:inspect/start local model route`;"));
        assertTrue(chat.contains("if (name === \"model\" || name === \"answer\") {"));
        assertTrue(chat.contains("const detailTitle = [\"supabase\", \"browser\", \"computer\", \"harmony\", \"traceMemory\", \"action\"].includes(name)"));
        assertTrue(chat.contains("card.title = detailTitle;"));
        assertTrue(chat.contains("small.title = detailTitle;"));
        assertTrue(chat.contains("badge.title = name === \"supabase\""));
        assertTrue(chat.contains("? safeDebugCockpitDetail(badgeDetail)"));
        assertTrue(chat.contains(": compactVisibleDebugDetail(badgeTitle, badgeDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('model', modelStatus, modelDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('model', 'WARN', 'model_runtime_unknown');"));

        assertFalse(chat.contains("modelRuntime.model ||"));
        assertFalse(chat.contains("modelRuntime['model']"));
        assertFalse(chat.contains("observedModel"));
        assertFalse(chat.contains("badge.title = safeDebugCockpitDetail(detail || status || name);"));
    }

    @Test
    void chatTopBarPromotesLocalLlmOperatorActionIntoModelAndSummaryHeartbeat() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const primaryModelNextAction = localLlmOperatorPresent"),
                "local LLM operator action should become the primary model next action");
        assertTrue(chat.contains("const modelDetail = recoveryDetail"),
                "model heartbeat should include the local LLM operator failure and next action");
        assertTrue(chat.contains("const summaryNextAction = primaryModelNextAction")
                        && chat.contains("|| effectiveNextExternalAction")
                        && chat.contains("|| (demandDrivenExternalOnly ? 'none' : (goalNextRow.nextAction || goalNextRow.firstAction))"),
                "summary heartbeat should prefer model recovery before external evidence actions");
        assertTrue(chat.contains("const summaryStatus = [coreStatus, uiStatus, externalSummaryStatus, gateStatus, actionStatus].includes('WARN') || modelStatus !== 'OK' ? 'WARN' : 'OK';"),
                "summary heartbeat should turn WARN when the local model path needs operator action");
        assertTrue(chat.contains("debugHeartbeatSummaryState.nextAction = summaryNextAction;"));
        assertTrue(chat.contains("const summaryDetail = `core:${coreStatus} ui:${uiStatus} model:${modelStatus} external:${healthRailExternalStatus} next:${summaryNextAction}`;"));
    }

    @Test
    void chatTopBarShowsSearchProviderRuntimeHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"search\""));
        assertTrue(html.contains("<span>Search</span>"));

        assertTrue(chat.contains("const providerRows = Array.isArray(data.webProviders) ? data.webProviders : [];"));
        assertTrue(chat.contains("const providerRuntime = data.providerRuntime || {};"));
        assertTrue(chat.contains("const failSoftLadder = data.failSoftLadder || {};"));
        assertTrue(chat.contains("const supplementalSearchRow = providerRows.find((item) => item?.provider === 'supplemental-multi-search') || {};"));
        assertTrue(chat.contains("const supplementalSearchState = supplementalSearchProviderCount > 0 ? 'on' : (supplementalSearchRow.optional ? 'disabled' : 'unknown');"));
        assertTrue(chat.contains("const searchEnabled = providerRows.filter((item) => item?.hasKey === true).length;"));
        assertTrue(chat.contains("const rawSearchStatus = providerRuntime.status === 'WARN' || failSoftLadder.status === 'WARN' || providerRows.some((item) => item?.status === 'WARN') ? 'WARN' : (providerRuntime.status || failSoftLadder.status || (searchEnabled > 0 ? 'OK' : 'WARN'));"));
        assertTrue(chat.contains("const rawSearchDetail = `providers:${providerStatusDetail} enabled:${searchEnabled}/${providerRows.length} supplemental:${supplementalSearchState} supplementalCount:${supplementalSearchProviderCount} timeouts:${providerRuntime.awaitTimeoutCount ?? 0} cancels:${providerRuntime.cancelSuppressedCount ?? 0} cache:${failSoftLadder.cacheOnlyMergedCount ?? 0} vector:${failSoftLadder.vectorFallbackUsed ? 'yes' : 'no'}`;"));
        assertTrue(chat.contains("const searchStatus = directLiteralAnswerComplete || idleRetrievalWarmup ? 'OK' : rawSearchStatus;"));
        assertTrue(chat.contains(": idleRetrievalWarmup ? `${rawSearchDetail} idle:no_query` : rawSearchDetail;"));
        assertTrue(chat.contains("setDebugHeartbeatField('search', searchStatus, searchDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('search', 'WARN', 'search_runtime_unknown');"));

        assertFalse(chat.contains("providerRuntime.raw"));
        assertFalse(chat.contains("failSoftLadder.raw"));
        assertFalse(chat.contains("webProviders.innerHTML"));
    }

    @Test
    void chatTopBarShowsFailSoftLadderHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"ladder\""));
        assertTrue(html.contains("<span>Ladder</span>"));

        assertTrue(chat.contains("const rawLadderStatus = failSoftLadder.status || (failSoftLadder.poolSafeEmpty ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const rawLadderDetail = `out:${failSoftLadder.outCount ?? 0} tracePool:${failSoftLadder.tracePoolSize ?? 0} rescue:${failSoftLadder.rescueMergeUsed ? 'yes' : 'no'} trigger:${failSoftLadder.starvationFallbackTrigger || 'none'}`;"));
        assertTrue(chat.contains("const ladderStatus = directLiteralAnswerComplete || idleRetrievalWarmup ? 'OK' : rawLadderStatus;"));
        assertTrue(chat.contains(": idleRetrievalWarmup ? `${rawLadderDetail} idle:no_query` : rawLadderDetail;"));
        assertTrue(chat.contains("setDebugHeartbeatField('ladder', ladderStatus, ladderDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('ladder', 'WARN', 'failsoft_ladder_unknown');"));

        assertFalse(chat.contains("failSoftLadder.query"));
        assertFalse(chat.contains("failSoftLadder.rawResults"));
    }

    @Test
    void chatTopBarShowsTraceSnapshotHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"trace\""));
        assertTrue(html.contains("<span>Trace</span>"));

        assertTrue(chat.contains("const traceSnapshotHealth = data.traceSnapshotHealth || {};"));
        assertTrue(chat.contains("const traceStatus = traceSnapshotHealth.status || (traceSnapshotHealth.available ? 'OK' : 'WARN');"));
        assertTrue(chat.contains("const traceDetail = `summaries:${traceSnapshotHealth.summaryCount ?? 0} entries:${traceSnapshotHealth.latestTraceEntryCount ?? 0} events:${traceSnapshotHealth.latestEventCount ?? 0} error:${traceSnapshotHealth.latestErrorPresent ? 'yes' : 'no'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('trace', traceStatus, traceDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('trace', 'WARN', 'trace_snapshot_unknown');"));

        assertFalse(chat.contains("traceSnapshotHealth.id"));
        assertFalse(chat.contains("traceSnapshotHealth.path"));
        assertFalse(chat.contains("traceSnapshotHealth.errorBody"));
    }

    @Test
    void chatTopBarShowsDebugAiMetricsHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"metrics\""));
        assertTrue(html.contains("<span>Metrics</span>"));

        assertTrue(chat.contains("const debugAiMetrics = data.debugAiMetrics || {};"));
        assertTrue(chat.contains("const metricsStatus = debugAiMetrics.status || (Number(debugAiMetrics.errorEvents || 0) > 0 || Number(debugAiMetrics.warnEvents || 0) > 0 ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const virtualMatrixCount = debugAiMetrics.virtualMatrixCount ?? debugAiMetrics.scorecard?.virtualMatrixCount ?? 0;"));
        assertTrue(chat.contains("const virtualMatrixChunkCount = debugAiMetrics.virtualMatrixChunkCount ?? debugAiMetrics.scorecard?.virtualMatrixChunkCount ?? 0;"));
        assertTrue(chat.contains("const virtualMatrixScoreRole = debugAiMetrics.virtualMatrixScoreRole || debugAiMetrics.scorecard?.virtualMatrixScoreRole || 'evidence';"));
        assertTrue(chat.contains("const virtualMatrixScoreTrusted = debugAiMetrics.virtualMatrixScoreTrusted ?? debugAiMetrics.scorecard?.virtualMatrixScoreTrusted ?? false;"));
        assertTrue(chat.contains("const virtualMatrixDecision = debugAiMetrics.virtualMatrixDecision || debugAiMetrics.scorecard?.virtualMatrixDecision || 'observe';"));
        assertTrue(chat.contains("const virtualMatrixActionAllowed = debugAiMetrics.virtualMatrixActionAllowed ?? debugAiMetrics.scorecard?.virtualMatrixActionAllowed ?? false;"));
        assertTrue(chat.contains("matrix:${virtualMatrixCount}/${virtualMatrixChunkCount} decision:${virtualMatrixDecision} role:${virtualMatrixScoreRole} trusted:${virtualMatrixScoreTrusted} actionAllowed:${virtualMatrixActionAllowed}"));
        assertTrue(chat.contains("setDebugHeartbeatField('metrics', metricsStatus, metricsDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('metrics', 'WARN', 'debug_ai_metrics_unknown');"));

        assertFalse(chat.contains("debugAiMetrics.rawEvent"));
        assertFalse(chat.contains("debugAiMetrics.rawQuery"));
        assertFalse(chat.contains("debugAiMetrics.rawPayload"));
    }

    @Test
    void chatTopBarShowsMemoryGateHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"memory\""));
        assertTrue(html.contains("<span>Memory</span>"));

        assertTrue(chat.contains("const memoryGate = data.memoryGate || {};"));
        assertTrue(chat.contains("const memoryStatus = memoryGate.status || (Number(memoryGate.quarantined || 0) > 0 || Number(memoryGate.stale || 0) > 0 ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const memoryDetail = `active:${memoryGate.active ?? 0}/${memoryGate.total ?? 0} pending:${memoryGate.pending ?? 0} quarantine:${memoryGate.quarantined ?? 0} stale:${memoryGate.stale ?? 0}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('memory', memoryStatus, memoryDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('memory', 'WARN', 'memory_gate_unknown');"));

        assertFalse(chat.contains("memoryGate.raw"));
        assertFalse(chat.contains("memoryGate.snippet"));
        assertFalse(chat.contains("memoryGate.prompt"));
    }

    @Test
    void chatTopBarShowsRuntimeLaneHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"lanes\""));
        assertTrue(html.contains("<span>Lanes</span>"));

        assertTrue(chat.contains("const laneRows = Array.isArray(data.lanes) ? data.lanes : [];"));
        assertTrue(chat.contains("const disabledLaneRows = laneRows.filter((item) => item?.status === 'DISABLED' || item?.enabled === false);"));
        assertTrue(chat.contains("const laneStatus = disabledLaneRows.length > 0 ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const laneDetail = `enabled:${laneRows.length - disabledLaneRows.length}/${laneRows.length} disabled:${disabledLaneRows.length} reason:${disabledLaneRows[0]?.disabledReason || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('lanes', laneStatus, laneDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('lanes', 'WARN', 'runtime_lanes_unknown');"));

        assertFalse(chat.contains("laneRows.innerHTML"));
        assertFalse(chat.contains("lanes.raw"));
    }

    @Test
    void chatTopBarShowsStrategyPerformanceHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"strategy\""));
        assertTrue(html.contains("<span>Strategy</span>"));

        assertTrue(chat.contains("const strategyRows = Array.isArray(data.strategyPerformances) ? data.strategyPerformances : [];"));
        assertTrue(chat.contains("const hotspotRows = Array.isArray(data.hotspotDistribution) ? data.hotspotDistribution : [];"));
        assertTrue(chat.contains("const strategyStatus = strategyRows.length > 0 || hotspotRows.length > 0 ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const strategyTop = strategyRows[0]?.strategyName || strategyRows[0]?.strategy || hotspotRows[0]?.hotspot || hotspotRows[0]?.label || hotspotRows[0]?.name || 'none';"));
        assertTrue(chat.contains("const strategyDetail = `strategies:${strategyRows.length} hotspots:${hotspotRows.length} top:${strategyTop}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('strategy', strategyStatus, strategyDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('strategy', 'WARN', 'strategy_runtime_unknown');"));

        assertFalse(chat.contains("strategyRows.innerHTML"));
        assertFalse(chat.contains("strategyRows.raw"));
        assertFalse(chat.contains("hotspotRows.raw"));
    }

    @Test
    void chatTopBarShowsRecentFailureHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"failures\""));
        assertTrue(html.contains("<span>Failures</span>"));

        assertTrue(chat.contains("const failureRows = Array.isArray(data.recentFailures) ? data.recentFailures : [];"));
        assertTrue(chat.contains("const failureStatus = failureRows.length > 0 ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const failureDetail = `recent:${failureRows.length} class:${failureRows[0]?.failureClass || failureRows[0]?.classification || failureRows[0]?.reason || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('failures', failureStatus, failureDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('failures', 'WARN', 'recent_failures_unknown');"));

        assertFalse(chat.contains("failureRows.innerHTML"));
        assertFalse(chat.contains("failureRows.raw"));
        assertFalse(chat.contains("failureRows.stackTrace"));
        assertFalse(chat.contains("failureRows.prompt"));
    }

    @Test
    void chatTopBarShowsQueryTransformerLaneHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"qtx\""));
        assertTrue(html.contains("<span>QTX</span>"));

        assertTrue(chat.contains("const qtxLane = laneRows.find((item) => item?.name === 'queryTransformer') || {};"));
        assertTrue(chat.contains("const queryRewrite = data.queryRewrite || {};"));
        assertTrue(chat.contains("const qtxRewriteObserved = queryRewrite.enabled === true"));
        assertTrue(chat.contains("const qtxStatus = qtxLane.status"));
        assertTrue(chat.contains("const qtxRewriteDetail = qtxRewriteObserved"));
        assertTrue(chat.contains("branches:${queryRewrite.branchCount ?? 0}"));
        assertTrue(chat.contains("lanes:${queryRewrite.verificationLaneCount ?? 0}/${queryRewrite.explorationLaneCount ?? 0}"));
        assertTrue(chat.contains("temp:${queryRewrite.validationTemperature ?? 0}/${queryRewrite.explorationTemperature ?? 0}"));
        assertTrue(chat.contains("let latestStreamQueryRewriteHeartbeat = null;"));
        assertTrue(chat.contains("function promoteQueryRewriteTransformerToHeartbeat(blocks = [])"));
        assertTrue(chat.contains("const detail = `enabled:yes reason:stream_transformer ${parts.join(\" \")}`;"));
        assertTrue(chat.contains("if (hasBlocks) promoteQueryRewriteTransformerToHeartbeat(blocks);"));
        assertTrue(chat.contains("latestStreamQueryRewriteHeartbeat = null;"));
        assertTrue(chat.contains("const qtxDetail = qtxRewriteObserved || !latestStreamQueryRewriteHeartbeat"));
        assertTrue(chat.contains(": latestStreamQueryRewriteHeartbeat.detail;"));
        assertTrue(chat.contains("setDebugHeartbeatField('qtx', qtxStatus, qtxDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('qtx', 'WARN', 'query_transformer_unknown');"));

        assertFalse(chat.contains("qtxLane.rawQuery"));
        assertFalse(chat.contains("queryRewrite.rawQuery"));
        assertFalse(chat.contains("rawUserQuery"));
        assertFalse(chat.contains("qtxLane.prompt"));
        assertFalse(chat.contains("queryRewrite.prompt"));
    }

    @Test
    void chatTopBarShowsSessionScopedProbeRoundHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"causal\""));
        assertTrue(html.contains("<span>Probe Round</span>"));
        assertTrue(html.contains("data-debug-heartbeat-field=\"causal\" data-status=\"WARN\""));

        assertTrue(chat.contains("const causalLane = laneRows.find((item) => item?.name === 'causalProbe') || {};"));
        assertTrue(chat.contains("const causalStatus = causalLane.status || (causalLane.enabled === false ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("phase:${causalLane.phase || 'not_observed'}"));
        assertTrue(chat.contains("hypothesis:${causalLane.dominantFailure || 'none'}"));
        assertTrue(chat.contains("evidence:${causalLane.counterEvidenceCount ?? 0}"));
        assertTrue(chat.contains("confidence:${causalLane.confidence ?? 0}->${causalLane.confidenceRange || 'pending'}"));
        assertTrue(chat.contains("gate:${causalLane.verificationGatePassed === true ? 'pass' : 'hold'}"));
        assertTrue(chat.contains("setDebugHeartbeatField('causal', causalStatus, causalDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('causal', 'WARN', 'probe_round_unknown');"));
        assertTrue(chat.contains("withChatCorrelationHeaders({}, { sessionId: state.currentSessionId })"));

        assertFalse(chat.contains("causalLane.rawQuery"));
        assertFalse(chat.contains("causalLane.rawPrompt"));
        assertFalse(chat.contains("causalLane.stackTrace"));
    }

    @Test
    void chatTopBarShowsRetrieverLaneHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"retrievers\""));
        assertTrue(html.contains("<span>Retrievers</span>"));

        assertTrue(chat.contains("const retrieverLaneNames = ['webSearch', 'vectorSearch', 'kgSearch'];"));
        assertTrue(chat.contains("const retrieverRows = laneRows.filter((item) => retrieverLaneNames.includes(item?.name));"));
        assertTrue(chat.contains("const disabledRetrieverRows = retrieverRows.filter((item) => item?.status === 'DISABLED' || item?.enabled === false);"));
        assertTrue(chat.contains("const retrieverStatus = disabledRetrieverRows.length > 0 ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const retrieverDetail = `enabled:${retrieverRows.length - disabledRetrieverRows.length}/${retrieverRows.length} first:${disabledRetrieverRows[0]?.name || 'none'} reason:${disabledRetrieverRows[0]?.disabledReason || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('retrievers', retrieverStatus, retrieverDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('retrievers', 'WARN', 'retriever_lanes_unknown');"));

        assertFalse(chat.contains("retrieverRows.rawQuery"));
        assertFalse(chat.contains("retrieverRows.rawPrompt"));
    }

    @Test
    void chatTopBarShowsCircuitBreakerHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"breaker\""));
        assertTrue(html.contains("<span>Breaker</span>"));

        assertTrue(chat.contains("const breakerLane = laneRows.find((item) => item?.name === 'circuitBreaker') || {};"));
        assertTrue(chat.contains("const breakerStatus = breakerLane.status || (breakerLane.enabled === false ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const breakerDetail = `state:${breakerLane.disabledReason || 'closed'} enabled:${breakerLane.enabled === false ? 'no' : 'yes'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('breaker', breakerStatus, breakerDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('breaker', 'WARN', 'circuit_breaker_unknown');"));

        assertFalse(chat.contains("breakerLane.rawError"));
        assertFalse(chat.contains("breakerLane.stackTrace"));
    }

    @Test
    void chatTopBarShowsLiveStreamHeartbeatFromCurrentSseEvents() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"liveStream\""));
        assertTrue(html.contains("<span>Live</span>"));

        assertTrue(chat.contains("function renderLiveDebugHeartbeat(partial = {}) {"));
        assertTrue(chat.contains("const streamStatus = String(partial.streamStatus || 'unknown');"));
        assertTrue(chat.contains("const streamContext = String(partial.streamContext || 'none');"));
        assertTrue(chat.contains("const streamDone = /^(final|done|complete)$/i.test(streamStatus);"));
        assertTrue(chat.contains("const streamStopped = /^(cancelled|stopped)$/i.test(streamStatus)"));
        assertTrue(chat.contains("const liveStatus = streamDone || streamStopped || streamAnswerComplete ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const liveDetail = `stream:${streamStatus} context:${streamContext}${streamAnswerComplete ? ' answer:complete' : ''}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('liveStream', liveStatus, liveDetail);"));
        assertTrue(chat.contains("renderLiveDebugHeartbeat({"));
        assertTrue(chat.contains("setDebugHeartbeatField('liveStream', 'WARN', 'live_stream_unknown');"));

        assertFalse(chat.contains("partial.raw"));
        assertFalse(chat.contains("partial.prompt"));
    }

    @Test
    void chatTopBarShowsDefaultModelWaitHeartbeatFromCurrentSseEvents() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"modelWait\""));
        assertTrue(html.contains("<span>Wait</span>"));

        assertTrue(chat.contains("const waitSource = [partial.streamStatus, partial.streamContext, partial.answerMode, partial.model, partial.modelBadge]"));
        assertTrue(chat.contains("const waitReason = /waiting[_-]for[_-]default[_-]model/i.test(waitSource) ? 'waiting_for_default_model' : 'none';"));
        assertTrue(chat.contains("const waitStatus = waitReason === 'none' && (streamDone || streamStopped || streamAnswerComplete) ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const waitDetail = `default:${waitReason} stream:${streamStatus}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('modelWait', waitStatus, waitDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('modelWait', 'WARN', 'model_wait_unknown');"));

        assertFalse(chat.contains("waitSource.rawQuery"));
        assertFalse(chat.contains("waitSource.Authorization"));
    }

    @Test
    void chatTopBarShowsTimeoutHeartbeatFromCurrentSseEvents() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"timeout\""));
        assertTrue(html.contains("<span>Timeout</span>"));

        assertTrue(chat.contains("const timeoutReason = /timeout/i.test(waitSource) ? 'timeout' : /fallback/i.test(waitSource) ? 'fallback' : /error/i.test(waitSource) ? 'error' : 'none';"));
        assertTrue(chat.contains("const timeoutStatus = timeoutReason === 'none' ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const timeoutDetail = `reason:${timeoutReason} stream:${streamStatus}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('timeout', timeoutStatus, timeoutDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('timeout', 'WARN', 'timeout_unknown');"));

        assertFalse(chat.contains("timeoutSource.rawQuery"));
        assertFalse(chat.contains("timeoutSource.Authorization"));
    }

    @Test
    void chatTopBarShowsCancellationHeartbeatFromCurrentSseEvents() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"cancel\""));
        assertTrue(html.contains("<span>Cancel</span>"));

        assertTrue(chat.contains("const cancelReason = /cancel/i.test(waitSource) ? 'cancelled' : 'none';"));
        assertTrue(chat.contains("const cancelStatus = cancelReason === 'none' || streamStopped ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const cancelDetail = `reason:${cancelReason} stream:${streamStatus}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('cancel', cancelStatus, cancelDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('cancel', 'WARN', 'cancel_state_unknown');"));

        assertFalse(chat.contains("cancelSource.rawQuery"));
        assertFalse(chat.contains("cancelSource.Authorization"));
    }

    @Test
    void chatTopBarShowsAnswerOutputHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"answer\""));
        assertTrue(html.contains("<span>Answer</span>"));

        assertTrue(chat.contains("const answerOutput = data.answerOutput || {};"));
        assertTrue(chat.contains("const answerOutputStatus = answerOutput.status || (answerOutput.emptyAnswerGuardTriggered || answerOutput.blankBaseFallback ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const answerStatus = assistantModelFallback ? 'WARN' : answerOutputStatus;"));
        assertTrue(chat.contains("const answerRuntimeDetail = assistantModelFallback?.answerDetail || `mode:${answerOutput.answerMode || 'none'} guard:${answerOutput.emptyAnswerGuardTriggered ? 'yes' : 'no'} fallback:${answerOutput.emptyAnswerFallback || 'none'} docs:${answerOutput.evidenceDocs ?? 0}`;"));
        assertTrue(chat.contains("const answerDetail = `${chatUsageDetail} ${answerRuntimeDetail}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('answer', answerStatus, answerDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('answer', 'WARN', 'answer_output_unknown');"));
        assertTrue(chat.contains("reflectAssistantModelFallback(ariaText || bubble.textContent || \"\");"));

        assertFalse(chat.contains("answerOutput.rawText"));
        assertFalse(chat.contains("answerOutput.rawPrompt"));
        assertFalse(chat.contains("answerOutput.Authorization"));
    }

    @Test
    void chatTopBarShowsComputerUseHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"computer\""));
        assertTrue(html.contains("<span>Computer</span>"));

        assertTrue(chat.contains("const computerRow = externalRows.find((item) => item?.service === 'computer-use') || {};"));
        assertTrue(chat.contains("const computerStatus = computerRow.status || (computerRow.reachable && !computerRow.stale ? 'OK' : 'WARN');"));
        assertTrue(chat.contains("const computerDetail = `apps:${computerRow.appCount ?? 0} windows:${computerRow.targetableWindowCount ?? computerRow.windowCount ?? 0} stale:${computerRow.stale ? 'yes' : 'no'}`;"));
        assertTrue(chat.contains("const computerSupportingProof = isDesktopLocalReady(goalNextRow) && desktopOnlyReady"));
        assertTrue(chat.contains("const computerUiStatus = computerSupportingProof ? 'SUPPORTING' : computerStatus;"));
        assertTrue(chat.contains("setDebugHeartbeatField('computer', computerUiStatus, computerDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('computer', computerUiStatus, computerUiDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('computer', 'WARN', 'computer_use_unknown');"));

        assertFalse(chat.contains("storesAppNames: true"));
        assertFalse(chat.contains("storesWindowTitles: true"));
    }

    @Test
    void chatTopBarShowsNoetherHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"noether\""));
        assertTrue(html.contains("<span>Noether</span>"));

        assertTrue(chat.contains("const noetherRow = externalRows.find((item) => item?.service === 'noether') || {};"));
        assertTrue(chat.contains("const noetherStatus = noetherRow.status || (noetherRow.responded ? 'OK' : 'WARN');"));
        assertTrue(chat.contains("const noetherDetail = `wait:${noetherRow.waiting ? 'yes' : 'no'} reply:${noetherRow.responded ? 'yes' : 'no'} kind:${noetherRow.lastMessageKind || 'unknown'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('noether', noetherStatus, noetherDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('noether', 'WARN', 'noether_status_unknown');"));

        assertFalse(chat.contains("agentId"));
        assertFalse(chat.contains("agentIdHash"));
    }

    @Test
    void chatTopBarShowsExternalEvidenceFreshnessHeartbeat() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"freshness\""));
        assertTrue(html.contains("<span>Fresh</span>"));

        assertTrue(chat.contains("const staleEvidenceRows = externalRows.filter((item) => item?.stale === true);"));
        assertTrue(chat.contains("const evidenceAgeMinutes = externalRows.map((item) => Number(item?.ageMinutes)).filter(Number.isFinite);"));
        assertTrue(chat.contains("const maxEvidenceAgeMinutes = evidenceAgeMinutes.length ? Math.max(...evidenceAgeMinutes) : 0;"));
        assertTrue(chat.contains("const freshnessStatus = externalRows.length === 0 || staleEvidenceRows.length > 0 ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const freshnessDetail = `stale:${staleEvidenceRows.length}/${externalRows.length} maxAge:${Math.round(maxEvidenceAgeMinutes)}m`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('freshness', freshnessStatus, freshnessDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('freshness', 'WARN', 'freshness_unknown');"));

        assertFalse(chat.contains("SUPABASE_ACCESS_TOKEN"));
        assertFalse(chat.contains("rawSecret"));
    }

    @Test
    void chatTopBarShowsPatchDropHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"patchdrop\""));
        assertTrue(html.contains("<span>PatchDrop</span>"));

        assertTrue(chat.contains("const patchDropRow = externalRows.find((item) => item?.service === 'patchdrop') || {};"));
        assertTrue(chat.contains("function isSupportingPatchDropEvidence(row = {}) {"));
        assertTrue(chat.contains("const supportingPatchDrop = isSupportingPatchDropEvidence(patchDropRow) && !explicitProducerEvidenceRequired;"));
        assertTrue(chat.contains("const patchDropStatus = supportingPatchDrop"));
        assertTrue(chat.contains("const patchDropDetail = `${supportingPatchDrop ? 'supporting ' : ''}top:${patchDropRow.activeTopLevelPatchCount ?? 0} nested:${patchDropRow.nestedProducerPatchCount ?? 0} report:${patchDropRow.reportOnlyPendingCount ?? 0}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('patchdrop', patchDropStatus, patchDropDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('patchdrop', 'WARN', 'patchdrop_queue_unknown');"));

        assertFalse(chat.contains("patchBody"));
        assertFalse(chat.contains("rawPatch"));
    }

    @Test
    void debugFxRendererDisplaysLabelsWithTextNodesOnly() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const labels = signal.labels || {};"));
        assertTrue(chat.contains("const signal = payload.debugFxSignal || payload.debug_fx || {};"));
        assertTrue(chat.contains("if (type === \"debug_fx\")"));
        assertTrue(chat.contains("handleDebugFxSignal(payload);"));
        assertTrue(chat.contains("labelsHolder.dataset.role = \"debug-fx-labels\";"));
        assertTrue(chat.contains("const labelEntries = Object.entries(labels).filter(([key, value]) => key && value != null);"));
        assertTrue(chat.contains("labelEntries.slice(0, 8).forEach(([key, value]) => {"));
        assertTrue(chat.contains("labelKey.textContent = `${key}:`;"));
        assertTrue(chat.contains("labelValue.textContent = String(value ?? \"-\");"));
        assertTrue(chat.contains("card.appendChild(labelsHolder);"));
        assertFalse(chat.contains("labelsHolder.innerHTML"));
        assertFalse(chat.contains("insertAdjacentHTML"));
    }

    @Test
    void scoreDeltaRendererDisplaysRedactedSignalLabelsInSignalBar() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const signalLabel = (value) =>"));
        assertTrue(chat.contains("const scoreDeltaContext = (signal) =>"));
        assertTrue(chat.contains("signalLabel(signal.stage)"));
        assertTrue(chat.contains("signalLabel(signal.guard)"));
        assertTrue(chat.contains("signalLabel(signal.clampName)"));
        assertTrue(chat.contains("parts.push(`event:${signalLabel(signal.eventId)}`);"));
        assertTrue(chat.contains("scoreDeltaContext: scoreDeltaContext(signal)"));
        assertTrue(chat.contains("const renderScoreDeltaDetail = (signal, target) =>"));
        assertTrue(chat.contains("detail.dataset.role = \"score-delta-detail\";"));
        assertTrue(chat.contains("[\"rawScoreDelta\", signal.rawScoreDelta]"));
        assertTrue(chat.contains("[\"stage\", signal.stage]"));
        assertTrue(chat.contains("[\"guard\", signal.guard]"));
        assertTrue(chat.contains("[\"clampName\", signal.clampName]"));
        assertTrue(chat.contains("[\"eventId\", signal.eventId]"));
        assertTrue(chat.contains("numericFields.forEach(([label, value]) => appendScoreDeltaLabel(detail, label, value));"));
        assertTrue(chat.contains("labelFields.forEach(([label, value]) => appendScoreDeltaLabel(detail, label, value));"));
        assertTrue(chat.contains("val.textContent = ` ${signalLabel(value)}`;"));
        assertTrue(chat.contains("renderScoreDeltaDetail(signal, bubble?.parentElement || dom.chatMessages);"));
        assertTrue(orch.contains("function scoreValue(value, context)"));
        assertTrue(orch.contains("const contextText = text(context, \"\");"));
        assertTrue(orch.contains("return contextText ? `${base} | ${contextText}` : base;"));
        assertTrue(orch.contains("setField(root, \"scoreDelta\", scoreValue(partial.scoreDelta, partial.scoreDeltaContext));"));
        assertFalse(orch.contains("scoreDeltaContext.innerHTML"));
        assertFalse(chat.contains("scoreDeltaDetail.innerHTML"));
    }

    @Test
    void statusSignalRendererDisplaysTimingAndCancelStateInSignalBar() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const statusContext = (signal) =>"));
        assertTrue(chat.contains("safeSignal.remainingMs"));
        assertTrue(chat.contains("safeSignal.tookMs"));
        assertTrue(chat.contains("safeSignal.cancelled === true"));
        assertTrue(chat.contains("streamContext: waitReason || statusContext(signal)"));
        assertTrue(orch.contains("function streamValue(value, context)"));
        assertTrue(orch.contains("setField(root, \"streamStatus\", streamValue(streamStatus, partial.streamContext));"));
        assertTrue(orch.contains("return contextText ? `${base} | ${contextText}` : base;"));
    }

    @Test
    void streamingSignalBarShowsClientElapsedWhileWaitingForModel() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const streamStartedAt = nowMs();"));
        assertTrue(chat.contains("const streamHeartbeatContext = () =>"));
        assertTrue(chat.contains("client-wait:${elapsedMs}ms"));
        assertTrue(chat.contains("let streamHeartbeatTimer = null;"));
        assertTrue(chat.contains("const stopStreamHeartbeat = () =>"));
        assertTrue(chat.contains("clearActiveStreamHeartbeat(streamHeartbeatTimer);"));
        assertTrue(chat.contains("dom.stopBtn?.addEventListener(\"click\", () => {"));
        assertTrue(chat.contains("clearActiveStreamHeartbeat();"));
        assertTrue(chat.contains("void cancelActiveStream();"));
        assertFalse(chat.contains("setCoreStatus(\"stopped\", activeSessionId && activeRunToken ? \"server cancel\" : \"local stop\");"));
        assertTrue(chat.contains("traceTurn: \"cancelled\""));
        assertTrue(chat.contains("row.appendChild(label);"));
        assertTrue(chat.contains("row.appendChild(value);"));
        assertTrue(chat.contains("streamHeartbeatTimer = window.setInterval(() => {"));
        assertTrue(chat.contains("if (activeStreamHeartbeatTimer !== streamHeartbeatTimer || streamController?.signal?.aborted) return;"));
        assertTrue(chat.contains("const streamHeartbeatStatus = streamHeartbeatStale ? \"model_wait\" : payload?.attach ? \"attaching\" : \"streaming\";"));
        assertTrue(chat.contains("streamStatus: streamHeartbeatStatus"));
        assertTrue(chat.contains("const streamHeartbeatDetail = `client-wait:${elapsedMs}ms${streamHeartbeatStale ? \" next:stop_or_wait\" : \"\"}`;"));
        assertTrue(chat.contains("streamContext: streamHeartbeatDetail"));
        assertTrue(chat.contains("stopStreamHeartbeat();"));
        assertTrue(chat.contains("const streamUrl = payload?.attach === true ? \"/api/chat/stream?attach=true\" : \"/api/chat/stream\";"));
        assertTrue(chat.contains("const response = await fetch(streamUrl, {"));
        assertTrue(chat.contains("if (streamOwnedActiveAssistant && activeStreamAssistant === assistant) activeStreamAssistant = null;"));
        assertTrue(chat.contains("syncSessionSelectionCapability();"));
    }

    @Test
    void streamingClientWaitStaysUserControlledInsteadOfAutoStopping() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const STREAM_SERVER_EVIDENCE_BUDGET_MS = 120000;"));
        assertTrue(chat.contains("function hasComposerDraftRailToRefresh()"));
        assertTrue(chat.contains("fallback|new chat"));
        assertTrue(chat.contains("function streamClientDeadlineMs(payload = {})"));
        assertTrue(chat.contains("return null;"));
        assertTrue(chat.contains("clientDeadlinePromise"));
        assertTrue(chat.contains("if (clientDeadlineMs != null && elapsedMs >= clientDeadlineMs)"));
        assertTrue(chat.contains("const next = clientDeadlinePromise == null"));
        assertTrue(chat.contains("? await reader.read()"));
        assertTrue(chat.contains("next:stop_or_wait"));
    }

    @Test
    void transformerDefaultModelWaitKeepsSignalBarOutOfUnknownState() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("if (value === undefined) return;"));
        assertTrue(chat.contains("const defaultModelWaitReason = (reason) =>"));
        assertTrue(chat.contains("const modelWaitReason = defaultModelWaitReason(modelReason);"));
        assertTrue(chat.contains("streamStatus: modelWaitReason ? \"waiting_for_default_model\" : undefined"));
        assertTrue(chat.contains("streamContext: modelWaitReason"));
        assertTrue(chat.contains("answerMode: modelWaitReason ? \"MODEL_WAIT\" : undefined"));
    }

    @Test
    void operatorDebugLinksExposePipelineStatusFromChatUi() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-menu-action=\"open-pipeline-status\""));
        assertTrue(html.contains("href=\"/admin/pipeline-status\""));
        assertTrue(html.contains("<span>Pipeline Status</span>"));
        assertTrue(chat.contains("pipelineStatus: '/admin/pipeline-status'"));
        assertTrue(chat.contains("'open-pipeline-status': 'pipelineStatus'"));
        assertTrue(chat.contains("const protectedDetail = `admin_sign_in_required target:${target}`;"));
        assertTrue(chat.contains("setStatusRailValue(dom.traceStatus, protectedSurface ? protectedDetail : \"opening ops surface\");"));
        assertTrue(chat.contains("if (protectedSurface) {"));
        assertTrue(chat.contains("return false;"));
        assertTrue(chat.contains("window.location.assign(target);"));
        assertTrue(chat.contains("const protectedSurface = options.protectedSurface === true || target.startsWith(\"/admin/\");"));
        assertFalse(chat.contains("admin sign-in required"));
        assertFalse(chat.contains("if (protectedSurface) {\n    return true;\n  }"));
        assertTrue(chat.contains("event.preventDefault();\n    openOpsWindow(targetName, {"));
        assertTrue(chat.contains("protectedSurface: link.getAttribute(\"data-admin-surface\") === \"protected\""));
        assertFalse(chat.contains("window.open(target"));
    }

    @Test
    void debugEventsFingerprintSummaryShowsWindowAgeAndTotalSuppressed() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/debug-events.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("<th class=\"text-left p-2\">windowAgeMs</th>"));
        assertTrue(html.contains("<th class=\"text-left p-2\">totalSuppressed</th>"));
        assertTrue(html.contains("appendTextCell(tr, fp.windowAgeMs, 'mono');"));
        assertTrue(html.contains("appendTextCell(tr, fp.totalSuppressed, 'mono');"));
    }

    @Test
    void statusSignalDefaultModelWaitSetsModeWithoutTransformer() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const waitReason = defaultModelWaitReason(signal.code || signal.message || payload.data);"));
        assertTrue(chat.contains("streamContext: waitReason || statusContext(signal)"));
        assertTrue(chat.contains("answerMode: waitReason ? \"MODEL_WAIT\" : undefined"));
        assertTrue(chat.contains("modelActive: waitReason ? true : undefined"));
        assertTrue(chat.contains("modelBadge: waitReason ? \"Model: running\" : undefined"));
        assertTrue(chat.contains("resilienceActive: Boolean(waitReason) || signal.cancelled === true"));
        assertTrue(chat.contains("if (waitReason) {"));
        assertTrue(chat.contains("route: signal.phase || signal.code || \"status\""));
        assertTrue(chat.contains("answerMode: \"MODEL_WAIT\""));
        assertTrue(chat.contains("pipelineSnapshot: {}"));
    }

    @Test
    void transformerDefaultModelWaitRendersRouteModeBeforeFinal() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const pipeline = payload.pipelineSnapshot || payload.pipeline_snapshot || {};"));
        assertTrue(chat.contains("const hasLearningContext = payload.learningContext && Object.keys(payload.learningContext).length > 0;"));
        assertTrue(chat.contains("const transformerBadges = hasBlocks ? deriveTransformerBadges(blocks) : {};"));
        assertTrue(chat.contains("if (hasBlocks && (transformerBadges.answerMode || Object.keys(pipeline).length > 0 || hasLearningContext)) {"));
        assertTrue(chat.contains("answerMode: transformerBadges.answerMode || pipeline.answerMode"));
        assertTrue(chat.contains("route: pipeline.route || meta.status || \"transformer\""));
        assertTrue(chat.contains("pipelineSnapshot: pipeline"));
    }

    @Test
    void transformerModelReasonUpdatesTimeoutHeartbeat() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const liveHeartbeatModelReason = transformerBadges.model || transformerBadges.modelBadge || pipeline.failureClass || pipeline.disabledReason || pipeline.answerMode;"));
        assertTrue(chat.contains("streamStatus: meta.status || transformerBadges.streamStatus || \"transformer\""));
        assertTrue(chat.contains("streamContext: transformerBadges.streamContext || liveHeartbeatModelReason || transformerBadges.answerMode || pipeline.answerMode"));
        assertTrue(chat.contains("model: transformerBadges.model"));
        assertTrue(chat.contains("modelBadge: transformerBadges.modelBadge"));

        assertFalse(chat.contains("liveHeartbeatModelReason.rawQuery"));
        assertFalse(chat.contains("liveHeartbeatModelReason.Authorization"));
    }

    @Test
    void supabaseTransformerBlockReasonUpdatesSignalBarBadge() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const supabaseBlock = findBlock(\"supabase\");"));
        assertTrue(chat.contains("const supabaseReason = supabaseBlock ? blockReason(supabaseBlock) : null;"));
        assertTrue(chat.contains("supabaseBadge: supabaseBlock ? `Supabase: ${supabaseReason || \"pending\"}` : \"Supabase\""));
        assertTrue(orch.contains("if (has(\"supabaseActive\") || has(\"supabaseBadge\")) {"));
        assertTrue(orch.contains("setBadge(root, \"supabase\", has(\"supabaseActive\") ? Boolean(partial.supabaseActive) : undefined, partial.supabaseBadge || \"Supabase\");"));
        assertFalse(chat.contains("supabaseRaw"));
        assertFalse(orch.contains("supabaseRaw"));
    }

    @Test
    void finalEventDoesNotOverwriteTransformerModelDiagnosticInSignalBar() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("let transformerModelDiagnosticVisible = false;"));
        assertTrue(chat.contains("let transformerModelDiagnosticLabel = null;"));
        assertTrue(chat.contains("const modelFallbackDiagnostic = (model, mode) =>"));
        assertTrue(chat.contains("const isGenericModelDiagnostic = (value) =>"));
        assertTrue(chat.contains("if (transformerBadges.model) transformerModelDiagnosticVisible = true;"));
        assertTrue(chat.contains("if (finalModelDiagnostic) {"));
        assertTrue(chat.contains("transformerModelDiagnosticLabel = finalModelDiagnostic;"));
        assertTrue(chat.contains("if (meta?.status === \"final\" && transformerModelDiagnosticLabel && isGenericModelDiagnostic(transformerBadges.model)) {"));
        assertTrue(chat.contains("transformerBadges.model = transformerModelDiagnosticLabel;"));
        assertTrue(chat.contains("transformerBadges.modelBadge = `Model: ${transformerModelDiagnosticLabel}`;"));
        assertTrue(chat.contains("model: finalModelDiagnostic || (transformerModelDiagnosticVisible ? undefined : model),"));
        assertTrue(chat.contains("...(finalModelDiagnostic ? { modelActive: true, modelBadge: `Model: ${finalModelDiagnostic}` } : {}),"));
        assertFalse(chat.contains("streamStatus: \"final\",\n            plan: pipeline.planId || pipeline.plan || null,\n            model,\n            answerMode"));
    }

    @Test
    void orchestrationSignalBarTreatsEventsAsPartialUpdates() throws Exception {
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("const has = (key) => Object.prototype.hasOwnProperty.call(partial, key);"));
        assertTrue(orch.contains("const previousFieldValue = (name) =>"));
        assertTrue(orch.contains("if (has(\"streamStatus\") || has(\"streamContext\")) {"));
        assertTrue(orch.contains("const streamStatus = has(\"streamStatus\") && text(partial.streamStatus, \"\") ? partial.streamStatus : previousFieldValue(\"streamStatus\");"));
        assertTrue(orch.contains("setField(root, \"streamStatus\", streamValue(streamStatus, partial.streamContext));"));
        assertTrue(orch.contains("if (has(\"scoreDelta\") || has(\"scoreDeltaContext\")) {"));
        assertTrue(orch.contains("if (has(\"modelActive\") || has(\"modelBadge\")) {"));
        assertTrue(orch.contains("setBadge(root, \"model\", has(\"modelActive\") ? Boolean(partial.modelActive) : undefined, partial.modelBadge);"));
        assertTrue(orch.contains("if (active !== undefined) el.dataset.active = active ? \"true\" : \"false\";"));
        assertFalse(orch.contains("setBadge(root, \"model\", Boolean(partial.modelActive), partial.modelBadge || \"Model\");"));
    }

    @Test
    void sessionSignalRendererExposesSessionIdInSignalBar() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const sessionTraceLabel = (sessionId) =>"));
        assertTrue(chat.contains("const traceRailLabel = (traceTurn) =>"));
        assertTrue(chat.contains("const rawTraceLabel = traceSnapshotId && traceSnapshotId !== \"-\" ? traceSnapshotId : requestId;"));
        assertTrue(chat.contains("const traceLabel = traceRailLabel(rawTraceLabel);"));
        assertFalse(chat.contains("const traceLabel = traceSnapshotId && traceSnapshotId !== \"-\" ? traceSnapshotId : requestId;"));
        assertTrue(chat.contains("traceTurn: sessionTraceLabel(sid)"));
        assertTrue(chat.contains("updateOrchestrationSignalBar({\n        traceTurn: sessionTraceLabel(sid)\n      });"));
        assertFalse(chat.contains("streamStatus: \"session\""));
        assertTrue(chat.contains("traceTurnId: traceTurnId || pipeline.traceTurnId || sessionTraceLabel(sid)"));
    }

    @Test
    void chatTemplateMountsSessionModeDiagnostics() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("document.querySelector(\"[data-session-mode-list]\")"));
        assertTrue(html.contains("data-session-mode-list"));
        assertTrue(html.contains("aria-label=\"Session history and mode diagnostics\""));
        assertTrue(html.contains("aria-live=\"polite\""));
    }

    @Test
    void chatComposerInputHasStableAccessibleLabel() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"messageInput\""));
        assertTrue(html.contains("data-testid=\"chat-message-input\""));
        assertTrue(html.contains("aria-label=\"Message\""));
    }

    @Test
    void chatControlSelectsHaveStableAccessibleLabels() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"modelSelect\""));
        assertTrue(html.contains("data-testid=\"chat-model-select\""));
        assertTrue(html.contains("aria-label=\"Model\""));
        assertTrue(html.contains("id=\"searchModeSelect\""));
        assertTrue(html.contains("data-testid=\"chat-search-mode-select\""));
        assertTrue(html.contains("aria-label=\"Search\""));
        assertTrue(html.contains("id=\"useRagToggle\""));
        assertTrue(html.contains("data-testid=\"chat-rag-toggle\""));
        assertTrue(html.contains("aria-label=\"Use RAG context\""));
        assertTrue(html.contains("id=\"coreStatusRail\""));
        assertTrue(html.contains("data-testid=\"chat-core-status-rail\""));
        assertTrue(html.contains("id=\"streamStatus\""));
        assertTrue(html.contains("data-testid=\"chat-stream-status\""));
        assertTrue(html.contains("id=\"modelStatus\""));
        assertTrue(html.contains("data-testid=\"chat-model-status\""));
        assertTrue(html.contains("id=\"searchStatus\""));
        assertTrue(html.contains("data-testid=\"chat-search-status\""));
        assertTrue(html.contains("id=\"ragStatus\""));
        assertTrue(html.contains("data-testid=\"chat-rag-status\""));
        assertTrue(html.contains("id=\"traceStatus\""));
        assertTrue(html.contains("data-testid=\"chat-trace-status\""));
        assertTrue(html.contains("id=\"qualityStatus\""));
        assertTrue(html.contains("data-testid=\"chat-quality-status\""));
        assertTrue(html.contains("id=\"healthStatus\""));
        assertTrue(html.contains("data-testid=\"chat-health-status\""));
    }

    @Test
    void sessionModeDiagnosticsUpdateExistingSessionRow() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const sid = normalizeSessionIdValue(sessionId);"));
        assertTrue(chat.contains("const sidKey = String(sid);"));
        assertTrue(chat.contains("list.querySelectorAll(\"[data-session-mode-row]\")"));
        assertTrue(chat.contains("candidate.dataset.sessionModeSessionId === sidKey"));
        assertTrue(chat.contains("row.dataset.sessionModeRow = \"true\";"));
        assertTrue(chat.contains("row.dataset.sessionModeSessionId = sidKey;"));
        assertTrue(chat.contains("row.dataset.sessionModeTraceTurnId = String(traceTurnId || \"-\");"));
        assertFalse(chat.contains("const row = document.createElement(\"div\");\n  const modeText = answerModeRailText(mode) || mode;\n  row.textContent = `${sessionTraceLabel(sessionId)} ${modeText} ${traceTurnId || \"-\"}`;\n  list.appendChild(row);"));
    }

    @Test
    void sessionModeDiagnosticsRestorePersistedTraceTurn() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function persistSessionModeTraceTurn(sessionId, traceTurnId) {"));
        assertTrue(chat.contains("window.sessionStorage?.setItem(`answerModeTraceTurn:${sid}`, String(traceTurnId));"));
        assertTrue(chat.contains("function restoredSessionModeTraceTurn(sessionId, detail = {}) {"));
        assertTrue(chat.contains("restoredSessionSetting(detail, \"traceTurnId\", \"trace_turn_id\", \"lastTraceTurnId\", \"last_trace_turn_id\")"));
        assertTrue(chat.contains("return window.sessionStorage?.getItem(`answerModeTraceTurn:${sid}`);"));
        assertTrue(chat.contains("const traceTurnId = restoredSessionModeTraceTurn(sid, detail);"));
        assertTrue(chat.contains("persistSessionModeTraceTurn(sid, traceTurnId);"));
    }

    @Test
    void traceSignalRendererDisplaysNoHtmlDiagnosticsWithTextNodesOnly() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const renderTraceSignalDetail = (signal, pipeline, target) =>"));
        assertTrue(chat.contains("detail.dataset.role = \"trace-signal-detail\";"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"trace\", signal.traceIdHash || pipeline.traceTurnId);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"request\", signal.requestIdHash);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"session\", signal.sessionIdHash);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"events\", signal.eventCount);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"failure\", signal.failureClass || pipeline.failureClass);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"reason\", signal.reasonCode || pipeline.disabledReason);"));
        assertTrue(chat.contains("Object.entries(signal.stageCounts || {})"));
        assertTrue(chat.contains("renderTraceSignalDetail(signal, pipeline, bubble?.parentElement || dom.chatMessages);"));
        assertTrue(chat.contains("traceLabel.textContent = `${label}:`;"));
        assertTrue(chat.contains("traceValue.textContent = ` ${safeValue}`;"));
        assertFalse(chat.contains("traceSignalDetail.innerHTML"));
    }

    @Test
    void finalEventFallsBackToRagUsedForAnswerModeCard() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const finalAnswerMode = (payload, model) =>"));
        assertTrue(chat.contains("payload.ragUsed ?? payload.rag_used"));
        assertTrue(chat.contains("return ragUsed === true ? \"rag\" : ragUsed === false ? \"chat\" : null;"));
        assertTrue(chat.contains("const inferredMode = finalAnswerMode(payload, model);"));
        assertTrue(chat.contains("const inferredMode = finalAnswerMode(payload, model);"));
    }

    @Test
    void finalEventInfersFallbackModeFromFallbackModelLabels() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const modelLower = String(model || \"\").toLowerCase();"));
        assertTrue(chat.contains("if (modelLower.includes(\"fallback:evidence\")) return \"FALLBACK_EVIDENCE\";"));
        assertTrue(chat.contains("if (modelLower.includes(\"fallback-local\") || modelLower.includes(\"fallback:local\")) return \"FALLBACK_LOCAL\";"));
        assertTrue(chat.contains("if (modelLower.includes(\"fallback\")) return \"FALLBACK\";"));
        assertTrue(chat.contains("if (upper === \"FALLBACK_EVIDENCE\") {"));
        assertTrue(chat.contains("return { text: \"fallback: evidence\""));
        assertTrue(chat.contains("if (upper === \"FALLBACK_LOCAL\") {"));
        assertTrue(chat.contains("return { text: \"fallback: local\""));
        assertTrue(chat.contains("if (upper === \"FALLBACK\") {"));
        assertTrue(chat.contains("return { text: \"fallback\""));
    }

    @Test
    void finalEventDisplaysHistoryFallbackAsLocalMemoryMode() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("if (modelLower.includes(\"history:fallback:current-turn\")) return \"HISTORY_CURRENT_TURN\";"));
        assertTrue(chat.contains("if (modelLower.includes(\"history:fallback:recent\")) return \"HISTORY_RECENT\";"));
        assertTrue(chat.indexOf("history:fallback:current-turn") < chat.indexOf("if (modelLower.includes(\"fallback\")) return \"FALLBACK\";"));
        assertTrue(chat.contains("if (upper === \"HISTORY_CURRENT_TURN\") {"));
        assertTrue(chat.contains("return { text: \"current turn memory\", status: \"OK\" };"));
        assertTrue(chat.contains("if (upper === \"HISTORY_RECENT\") {"));
        assertTrue(chat.contains("return { text: \"recent history\", status: \"OK\" };"));
        assertTrue(chat.contains("context: \"current-turn-memory\""));
        assertTrue(chat.contains("text: \"Current turn memory - no external evidence\""));
        assertTrue(chat.contains("const answerModeRailText = (mode) => {"));
        assertTrue(chat.contains("function hasStatusRailValue(value)"));
        assertTrue(chat.contains("const nextStreamValue = streamStatusRailValue(partial);"));
        assertTrue(chat.contains("if (hasStatusRailValue(nextStreamValue) && nextStreamValue !== \"-\")"));
        assertTrue(chat.contains("setStatusRailValue(dom.streamStatus, answerModeRailText(detail) || detail || status);"));
        assertTrue(chat.contains("parts.push(`mode:${answerModeRailText(syncMode) || syncMode}`);"));
        assertTrue(chat.contains("badgeEl.dataset.answerMode = String(mode);"));
        assertTrue(chat.contains("badgeEl.textContent = `Answer mode: ${label}`;"));
        assertTrue(chat.contains("const modeText = answerModeRailText(mode) || mode;"));
        assertTrue(chat.contains("const modelStatusRailValue = (model, mode) => {"));
        assertTrue(chat.contains("if (upper === \"HISTORY_CURRENT_TURN\" || upper === \"HISTORY_RECENT\") return answerModeRailText(normalized);"));
        assertTrue(chat.contains("if (hasStatusRailValue(partial.model))"));
        assertTrue(chat.contains("setStatusRailValue(dom.modelStatus, modelStatusRailValue(partial.model, partial.answerMode || partial.streamStatus));"));
        assertTrue(chat.contains("setStatusRailValue(dom.modelStatus, modelStatusRailValue(model, finalMode));"));
    }

    @Test
    void evidenceRailRendererDeduplicatesStreamingAndFinalEvidenceForSameMessage() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function evidenceRailSignature(items, emptyState)"));
        assertTrue(chat.contains("function latestEvidenceRail(target)"));
        assertTrue(chat.contains("function isRenderableEvidenceItem(item)"));
        assertTrue(chat.contains("const renderableItems = prioritizedEvidenceItems("));
        assertTrue(chat.contains("items.filter((item) => isRenderableEvidenceItem(item)),"));
        assertTrue(chat.contains("holder.dataset.signature = evidenceRailSignature(renderableItems, emptyState);"));
        assertTrue(chat.contains("if (items.length !== renderableItems.length) holder.dataset.sourceCount = String(items.length);"));
        assertTrue(chat.contains("const existingRail = latestEvidenceRail(target);"));
        assertTrue(chat.contains("if (existingRail && existingCount > 0 && items.length === 0) {"));
        assertTrue(chat.contains("return mountEvidenceRail(holder, target, renderableItems);"));
        assertTrue(chat.contains("existingRail.replaceWith(holder);"));
        assertTrue(chat.contains("return holder;"));
        assertTrue(chat.contains("renderEvidenceRail(payload.evidence, assistant?.parentElement || dom.chatMessages, {"));
        assertTrue(chat.contains("answerMode: \"streamed\","));
        assertTrue(chat.contains("renderEvidenceRail(payload.evidence, assistant.parentElement || dom.chatMessages, {"));
        assertTrue(chat.contains("answerMode: finalMode,"));
        assertTrue(chat.contains("answerText: bubble?.dataset?.ariaText || bubble?.textContent || \"\""));
    }

    @Test
    void searchCardConsumesOnlyTheRedactedProviderStatusAggregate() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function providerStatusSearchSummary(rows = [])"));
        assertTrue(chat.contains("const providerStatusRows = Array.isArray(data.providerStatus) ? data.providerStatus : [];"));
        assertTrue(chat.contains("const providerStatusDetail = providerStatusSearchSummary(providerStatusRows);"));
        assertTrue(chat.contains("providers:${providerStatusDetail}"));
        assertTrue(chat.contains("setDebugCockpitCell('search', searchTraceStatus, 'Search', searchTraceDetail);"));
        assertTrue(countOccurrences(css,
                ".debug-heartbeat-card[data-debug-heartbeat-field=\"search\"],") >= 3,
                "the redacted Search provider-health card must remain visible at desktop-short and mobile breakpoints");
        assertFalse(chat.contains("providerStatusRows.innerHTML"));
        assertFalse(chat.contains("providerStatusRows.rawPrompt"));
        assertFalse(chat.contains("providerStatusRows.authorization"));
        assertFalse(chat.contains("providerStatusRows.endpoint"));
    }

    @Test
    void chatSearchDefaultsToDemandDrivenOff() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("<option value=\"OFF\" selected>OFF</option>"));
        assertFalse(html.contains("<option value=\"AUTO\" selected>AUTO</option>"));
    }

    @Test
    void searchModeStatusRailUsesFriendlyLabelsButSubmitsEnumValues() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const CONTROL_SETTINGS_STORAGE_KEY = \"chat.controlSettings\";"));
        assertTrue(chat.contains("let localControlOverrideActive = false;"));
        assertTrue(chat.contains("function persistControlSettings(source = \"\") {"));
        assertTrue(chat.contains("function restoreStoredControlSettings() {"));
        assertTrue(chat.contains("localControlOverrideActive = settings.source === \"user\";"));
        assertTrue(chat.contains("if (localControlOverrideActive && options.source !== \"stored-controls\") {"));
        assertTrue(chat.contains("function handleControlChange() {"));
        assertTrue(chat.contains("syncControlStatus({ source: \"user\" });"));
        assertTrue(chat.contains("if (!restoreStoredControlSettings()) {"));
        assertTrue(chat.contains("currentModelBadge: document.querySelector(\"[data-current-model]\")"));
        assertTrue(chat.contains("function setCurrentModelBadge(model) {"));
        assertTrue(chat.contains("dom.currentModelBadge.textContent = value;"));
        assertTrue(chat.contains("dom.currentModelBadge.setAttribute(\"aria-label\", `Current model: ${value}`);"));
        assertTrue(chat.contains("setCurrentModelBadge(selectedModel);"));
        assertTrue(chat.contains("const searchModeRailValue = (mode) => {"));
        assertTrue(chat.contains("if (upper === \"FORCE_LIGHT\") return \"LIGHT\";"));
        assertTrue(chat.contains("if (upper === \"FORCE_DEEP\") return \"DEEP\";"));
        assertTrue(chat.contains("setStatusRailValue(dom.searchStatus, searchModeRailValue(dom.searchModeSelect?.value));"));
        assertTrue(chat.contains("setStatusRailValue(dom.searchStatus, searchModeRailValue(payload.searchMode));"));
        assertTrue(chat.contains("searchMode: dom.searchModeSelect?.value || \"AUTO\""));
    }

    @Test
    void directLiteralFinalModeSuppressesPerMessageDiagnostics() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("if (modelLower.includes(\"direct:literal\")) return \"DIRECT_LITERAL\";"));
        assertTrue(chat.indexOf("direct:literal") < chat.indexOf("if (modelLower.includes(\"fallback\")) return \"FALLBACK\";"));
        assertTrue(chat.contains("if (upper === \"DIRECT_LITERAL\") {"));
        assertTrue(chat.contains("return { text: \"direct literal\", status: \"OK\" };"));
        assertTrue(chat.contains("const DIRECT_LITERAL_DIAGNOSTIC_SELECTOR ="));
        assertTrue(chat.contains("\".message-debug-fx\""));
        assertTrue(chat.contains("\"[data-role=\\\"plan-mode\\\"]\""));
        assertTrue(chat.contains("\"[data-role=\\\"transformer-core-rail\\\"]\""));
        assertTrue(chat.contains("\"[data-role=\\\"trace-signal-detail\\\"]\""));
        assertTrue(chat.contains("\"[data-role=\\\"score-delta-detail\\\"]\""));
        assertTrue(chat.contains("\".evidence-rail\""));
        assertTrue(chat.contains("\"[data-answer-mode-badge]\""));
        assertTrue(chat.contains("function isDirectLiteralMode(mode, model) {"));
        assertTrue(chat.contains("function suppressDirectLiteralDiagnostics(bubble, mode, model) {"));
        assertTrue(chat.contains("bubble.dataset.directLiteralDiagnostics = \"suppressed\";"));
        assertTrue(chat.contains("function isAnswerOnlyInstruction(text) {"));
        assertTrue(chat.contains("function suppressAnswerOnlyDiagnostics(bubble, userInstruction) {"));
        assertTrue(chat.contains("bubble.dataset.answerOnlyDiagnostics = \"suppressed\";"));
        assertTrue(chat.contains("while (node && !node.classList?.contains(\"message\")) {"));
        assertTrue(chat.contains("function shouldSuppressMessageDiagnostics(target) {"));
        assertTrue(chat.contains("const anchor = latestMessageDiagnosticAnchor(target);"));
        assertTrue(chat.contains("|| anchor?.dataset?.answerOnlyDiagnostics === \"suppressed\";"));
        assertTrue(chat.contains("suppressAnswerOnlyDiagnostics(assistant, draftText);"));
        assertTrue(chat.contains("suppressDirectLiteralDiagnostics(bubble, finalMode, model);"));
        assertFalse(chat.contains("suppressDirectLiteralDiagnostics(messageBubble, syncMode, model);"));
        assertTrue(chat.contains("if (shouldSuppressMessageDiagnostics(target)) return null;"));
        assertTrue(chat.contains("if (shouldSuppressMessageDiagnostics(bubble?.parentElement || bubble)) return;"));
    }

    @Test
    void directLiteralDiagnosticSuppressionIsScopedToCurrentAssistantTurn() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function suppressDirectLiteralDiagnostics(bubble, mode, model) {"));
        assertTrue(chat.contains("bubble.dataset.directLiteralDiagnostics = \"suppressed\";"));
        assertTrue(chat.contains("let node = bubble.nextElementSibling;"));
        assertTrue(chat.contains("while (node && !node.classList?.contains(\"message\")) {"));
        assertTrue(chat.contains("const next = node.nextElementSibling;"));
        assertTrue(chat.contains("if (node.matches?.(DIRECT_LITERAL_DIAGNOSTIC_SELECTOR)) {"));
        assertTrue(chat.contains("node.remove();"));
        assertTrue(chat.contains("node = next;"));
        assertTrue(chat.contains("function latestMessageDiagnosticAnchor(target) {"));
        assertTrue(chat.contains("const messages = Array.from(target.querySelectorAll?.(\".message\") || []);"));
        assertTrue(chat.contains("return messages.length ? messages[messages.length - 1] : null;"));
        assertTrue(chat.contains("function clearDirectLiteralDiagnosticsSuppression() {"));
        assertTrue(chat.contains("dom.chatMessages?.querySelectorAll?.(\"[data-direct-literal-diagnostics]\").forEach((node) => {"));
        assertTrue(chat.contains("delete node.dataset.directLiteralDiagnostics;"));
        assertTrue(chat.contains("dom.chatMessages?.querySelectorAll?.(\"[data-answer-only-diagnostics]\").forEach((node) => {"));
        assertTrue(chat.contains("delete node.dataset.answerOnlyDiagnostics;"));
        assertTrue(chat.contains("suppressDirectLiteralDiagnostics(bubble, finalMode, model);"));
        assertFalse(chat.contains("suppressDirectLiteralDiagnostics(messageBubble, syncMode, model);"));
        assertFalse(chat.contains("target.querySelectorAll(DIRECT_LITERAL_DIAGNOSTIC_SELECTOR).forEach((node) => node.remove());"));
    }

    @Test
    void finalSignalBarPrefersServerPipelineAnswerModeOverGenericInference() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const finalMode = normalizedAnswerMode(pipeline.answerMode || pipeline.answer_mode || pipeline.mode) || inferredMode;"));
        assertTrue(chat.contains("answerMode: finalMode"));
        assertTrue(chat.contains("upsertAnswerModeBadge(bubble.parentElement, finalMode);"));
        assertTrue(chat.contains("persistAnswerModeBadge(sid || state.currentSessionId, finalMode);"));
        assertTrue(chat.contains("upsertSessionModeBadgeInList(sid || state.currentSessionId, finalMode, traceTurnId);"));
        assertFalse(chat.contains("answerMode: inferredMode || pipeline.answerMode"));
        assertFalse(chat.contains("const finalMode = pipeline.answerMode || inferredMode;"));
    }

    @Test
    void finalSignalBarIgnoresUnknownPipelineModeWhenFallbackModelIsKnown() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const normalizedAnswerMode = (mode) => {"));
        assertTrue(chat.contains("if (UNKNOWN_ANSWER_MODES.has(raw.toLowerCase())) return null;"));
        assertTrue(chat.contains("const explicit = normalizedAnswerMode(payload?.answerMode || payload?.answer_mode || payload?.mode);"));
        assertTrue(chat.contains("const finalMode = normalizedAnswerMode(pipeline.answerMode || pipeline.answer_mode || pipeline.mode) || inferredMode;"));
        assertTrue(chat.contains("recoverExactRunAfterTransportLoss(expectedRun, loaderId)"));
        assertTrue(chat.contains("'/api/chat/state?sessionId=${expectedRun.sessionId}'")
                || chat.contains("`/api/chat/state?sessionId=${expectedRun.sessionId}`"));
        assertFalse(chat.contains("const syncMode = serverPipeline.answerMode || inferredMode;"));
    }

    @Test
    void transportRecoveryUsesExactStateAndAttachWithoutSyncGeneration() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("async function recoverExactRunAfterTransportLoss(expectedRun, loaderId)"));
        assertTrue(chat.contains("/api/chat/state?sessionId=${expectedRun.sessionId}"));
        assertTrue(chat.contains("attach: true"));
        assertTrue(chat.contains("runToken: expectedRun.runToken"));
        assertTrue(chat.contains("setCoreStatus(\"streaming\", \"recovering exact run\");"));
        assertFalse(chat.contains("const res = await apiCall(\"/api/chat\""));
        assertFalse(chat.contains("streamContext: answer"));
    }

    @Test
    void planModeDiagnosticsReuseExistingRouteCardPerMessageContainer() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function latestPlanModeDiagnostic(target, route)"));
        assertTrue(chat.contains("node.dataset.planRoute === route"));
        assertTrue(chat.contains("const existing = latestPlanModeDiagnostic(target, route);"));
        assertTrue(chat.contains("if (existing) {"));
        assertTrue(chat.contains("existing.textContent = `Plan route: ${route}`;"));
        assertTrue(chat.contains("existing.setAttribute(\"aria-label\", `Plan route: ${route}`);"));
        assertTrue(chat.contains("card.dataset.planRoute = route;"));
    }

    @Test
    void thoughtSignalUpdatesSignalBarWithCountOnlyContext() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("let thoughtEventCount = 0;"));
        assertTrue(chat.contains("thoughtEventCount += 1;"));
        assertTrue(chat.contains("streamStatus: \"thought\""));
        assertTrue(chat.contains("streamContext: `thoughts:${thoughtEventCount}`"));
        assertFalse(chat.contains("streamContext: msg"));
    }

    @Test
    void understandingSignalUpdatesSignalBarWithCountOnlyContext() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("let understandingEventCount = 0;"));
        assertTrue(chat.contains("understandingEventCount += 1;"));
        assertTrue(chat.contains("streamStatus: \"understanding\""));
        assertTrue(chat.contains("streamContext: `understanding:${understandingEventCount}`"));
        assertFalse(chat.contains("streamContext: jsonStr"));
        assertFalse(chat.contains("streamContext: summary"));
    }

    @Test
    void planModeCardDisplaysPipelineQualitySignalsWithTextNodesOnly() throws Exception {
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("const pipeline = meta.pipelineSnapshot || {};"));
        assertTrue(orch.contains("appendLine(holder, \"citation\", ratio(pipeline.citationCoverage));"));
        assertTrue(orch.contains("appendLine(holder, \"finalSigmoid\", ratio(pipeline.finalSigmoid));"));
        assertTrue(orch.contains("valueEl.textContent = text(value, \"-\");"));
        assertFalse(orch.contains("holder.innerHTML"));
    }

    @Test
    void planModeCardDisplaysLearningSummaryPresenceWithoutRawSummary() throws Exception {
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("learning.summaryPresent === true ? \"present\" : \"absent\""));
        assertTrue(orch.contains("appendLine(holder, \"summary\", summaryState);"));
        assertFalse(orch.contains("learningContextSummary"));
        assertFalse(orch.contains("learning.summaryText"));
        assertFalse(orch.contains("learning.summaryHtml"));
        assertFalse(orch.contains("holder.innerHTML"));
    }

    @Test
    void planModeCardDisplaysPipelineCountsAndReasonsWithTextNodesOnly() throws Exception {
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("function count(value, fallback = \"-\")"));
        assertTrue(orch.contains("appendLine(holder, \"web\", count(pipeline.webCount));"));
        assertTrue(orch.contains("appendLine(holder, \"vector\", count(pipeline.vectorCount));"));
        assertTrue(orch.contains("appendLine(holder, \"context\", count(pipeline.finalContextCount));"));
        assertTrue(orch.contains("appendLine(holder, \"failure\", pipeline.failureClass || \"none\");"));
        assertTrue(orch.contains("appendLine(holder, \"disabled\", pipeline.disabledReason || \"none\");"));
        assertFalse(orch.contains("pipeline.raw"));
        assertFalse(orch.contains("pipeline.prompt"));
        assertFalse(orch.contains("pipeline.snippet"));
        assertFalse(orch.contains("holder.innerHTML"));
    }

    @Test
    void signalBarDisplaysPipelineRouteContextQualityAndHealth() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-orch-field=\"route\""));
        assertTrue(html.contains("<strong>Route</strong>"));
        assertTrue(html.contains("data-orch-field=\"contextCounts\""));
        assertTrue(html.contains("<strong>Context</strong>"));
        assertTrue(html.contains("data-orch-field=\"traceTurn\""));
        assertTrue(html.contains("<strong>Trace</strong>"));
        assertTrue(html.contains("data-orch-field=\"quality\""));
        assertTrue(html.contains("<strong>Quality</strong>"));
        assertTrue(html.contains("data-orch-field=\"health\""));
        assertTrue(html.contains("<strong>Health</strong>"));
        assertTrue(chat.contains(".replace(/:\\s*$/, \"\")"));
        assertTrue(css.contains(".status-pill strong::after"));
        assertTrue(css.contains("content: \":\""));
        assertTrue(orch.contains("function pipelineRouteValue(pipeline, fallbackRoute)"));
        assertTrue(orch.contains("function pipelineContextCountsValue(pipeline)"));
        assertTrue(orch.contains("function pipelineQualityValue(pipeline)"));
        assertTrue(orch.contains("function pipelineHealthValue(pipeline)"));
        assertTrue(orch.contains("const pipeline = partial.pipelineSnapshot || {};"));
        assertTrue(orch.contains("setField(root, \"route\", pipelineRouteValue(pipeline, partial.route), \"-\");"));
        assertTrue(orch.contains("setField(root, \"contextCounts\", pipelineContextCountsValue(pipeline), \"-\");"));
        assertTrue(orch.contains("setField(root, \"quality\", pipelineQualityValue(pipeline), \"-\");"));
        assertTrue(orch.contains("setField(root, \"health\", pipelineHealthValue(pipeline), \"ok\");"));
        assertTrue(chat.contains("pipelineSnapshot: pipeline"));
        assertFalse(chat.contains("pipelineSnapshot: syncPipeline"));
    }

    @Test
    void chatFrontendStripsAssistantReasoningBlocksBeforeRendering() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function stripAssistantReasoningBlocks"));
        assertTrue(chat.contains("const assistantReasoningStates = new WeakMap();"));
        assertFalse(chat.contains("let suppressAssistantReasoning = false;"));
        assertTrue(chat.contains("filterAssistantReasoningChunk"));
        assertTrue(chat.contains("stripAssistantReasoningBlocks(text)"));
        assertTrue(chat.contains("stripAssistantReasoningBlocks(html)"));
        assertTrue(chat.contains("stripAssistantReasoningBlocks(rawHtml)"));
        assertTrue(chat.contains("appendTextWithBreaks(bubble, filterAssistantReasoningChunk(payload.data || \"\", bubble))"));
        assertFalse(chat.contains("appendTextWithBreaks(bubble, payload.data || \"\")"));
    }

    @Test
    void chatFrontendShowsIntegrityWarningForMojibakeAnswerText() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function looksLikeMojibake"));
        assertTrue(chat.contains("function renderAnswerIntegrityWarning"));
        assertTrue(chat.contains("answer-integrity-warning"));
        assertTrue(chat.contains("output.integrity"));
        assertTrue(chat.contains("looksLikeMojibake(cleanText)"));
        assertTrue(chat.contains("looksLikeMojibake(cleanHtml)"));
        assertTrue(css.contains(".answer-integrity-warning"));
    }

    @Test
    void imagineCommandUsesAsyncJobEndpoint() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String imageUi = Files.readString(Path.of("main/resources/static/js/image-jobs-ui.js"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(js.contains("import { renderImageJobCard, updateImageJobCard, attachImageJobDebug, attachImageJobConfigDebug }"));
        assertTrue(js.contains("fetch(\"/api/image-plugin/jobs\""));
        assertFalse(js.contains("/api/image-plugin/generate"));
        assertTrue(js.contains("headers,"));
        assertTrue(js.contains("...(CSRF.token ? { [CSRF.header]: CSRF.token } : {})"));
        assertTrue(js.contains("function imageJobConfigDisabledReason(config = {})"));
        assertTrue(js.contains("config[\"openai.image.enabled\"] === false || config.imageServiceAvailable === false"));
        assertTrue(js.contains("function rejectImageJobLocally(card, updateImageJobCard, assistant, reason)"));
        assertTrue(js.contains("rejectImageJobLocally(card, updateImageJobCard, assistant, \"image_prompt_required\");"));
        assertTrue(js.contains("const config = await attachImageJobConfigDebug(card, headers);"));
        assertTrue(js.contains("const disabledReason = imageJobConfigDisabledReason(config);"));
        assertTrue(js.indexOf("const config = await attachImageJobConfigDebug(card, headers);") < js.indexOf("fetch(\"/api/image-plugin/jobs\""));
        assertTrue(js.contains("const maxClientWaitMs = Math.min(30 * 60 * 1000, Math.max(120000, etaMs + 45000));"));
        assertTrue(js.contains("IMAGE_JOB_STILL_RUNNING_CHECK_STATUS_OR_MANIFEST"));
        assertTrue(js.contains("void attachImageJobDebug(card, id, headers);"));
        assertTrue(js.contains("async function monitorImageJob("));
        assertTrue(js.contains("if (status === \"SUCCEEDED\")"));
        assertTrue(js.contains("await attachImageJobConfigDebug(card, headers);"));
        assertTrue(js.contains("throw imageJobHandledError(reason);"));
        assertTrue(js.contains("if (error?.imageJobHandled === true)"));
        assertFalse(js.contains("if (isImagineCommand(text)) {\n      await submitImageJob(text, assistant);\n    } else {\n      await streamChat(payload, loaderId);\n    }\n    clearDraft = true;"));
        assertFalse(js.contains("content: `[image] ${prompt}`"));
        assertTrue(js.contains("content: \"[image generated]\""));
        assertTrue(imageUi.contains("value.startsWith(\"data:\")"));
        assertTrue(imageUi.contains("export async function attachImageJobDebug"));
        assertTrue(imageUi.contains("export async function attachImageJobConfigDebug"));
        assertTrue(imageUi.contains("/api/diagnostics/image/jobs/"));
        assertTrue(imageUi.contains("/api/diagnostics/image/config"));
        assertTrue(imageUi.contains("card.setAttribute(\"role\", \"status\");"));
        assertTrue(imageUi.contains("card.setAttribute(\"aria-live\", \"polite\");"));
        assertTrue(imageUi.contains("title.className = \"image-job-title\";"));
        assertTrue(imageUi.contains("promptEl.className = \"image-job-prompt\";"));
        assertTrue(imageUi.contains("config[\"openai.image.enabled\"] === false || config[\"imageServiceAvailable\"] === false"));
        assertTrue(imageUi.contains("warning.textContent = clean(config.disabledReason || config.nextAction || \"image provider unavailable\");"));
        assertTrue(imageUi.contains("card.dataset.imageJobDebug = \"true\";"));
        assertFalse(imageUi.contains("querySelector?.(\"[data-image-job-debug]\")"));
        assertTrue(imageUi.contains("const row = document.createElement(\"div\");"));
        assertTrue(imageUi.contains("key.textContent = `${label}: `;"));
        assertTrue(imageUi.contains("appendLine(meta, \"reason\", job.reason || job.error || job.errorCode || job.error_code);"));
        assertFalse(imageUi.contains("`job ${clean(job.id)}`"));
        assertFalse(imageUi.contains("promptEl.textContent = clean(prompt"));
        assertTrue(imageUi.contains("function imageJobPromptLabel(job = {})"));
        assertTrue(imageUi.contains("function imageJobCardLabel(job = {})"));
        assertTrue(imageUi.contains("if (reason) parts.push(`reason ${reason}`);"));
        assertTrue(imageUi.contains("card.setAttribute(\"aria-label\", cardLabel);"));
        assertTrue(imageUi.contains("card.title = cardLabel;"));
        assertTrue(imageUi.contains("return \"Image request unavailable\";"));
        assertTrue(imageUi.contains("return \"Image request failed\";"));
        assertTrue(imageUi.contains("return \"Image request submitted\";"));
        assertTrue(imageUi.contains("promptEl.textContent = imageJobPromptLabel(job);"));
        assertFalse(imageUi.contains("promptEl.textContent = \"Image request submitted\""));
        assertTrue(css.contains(".image-job-card .image-job-title"));
        assertTrue(css.contains(".image-job-card .image-job-prompt"));
    }

    @Test
    void imagineCommandRecognizesKoreanNaturalLanguageRequests() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(js.contains("const IMAGE_SLASH_COMMAND_PATTERN"));
        assertTrue(js.contains("const KOREAN_IMAGE_REQUEST_PATTERN"));
        assertTrue(js.contains("\\uC774\\uBBF8\\uC9C0"));
        assertTrue(js.contains("\\uB9CC\\uB4E4\\uC5B4"));
        assertTrue(js.contains("\\uC0DD\\uC131\\uD574"));
        assertTrue(js.contains("KOREAN_IMAGE_REQUEST_PATTERN.test(value)"));
        assertTrue(js.contains("value.replace(KOREAN_IMAGE_REQUEST_PREFIX_PATTERN, \"\")"));
        assertTrue(js.contains("value.replace(KOREAN_IMAGE_REQUEST_SUFFIX_PATTERN, \"\")"));
    }

    @Test
    void newChatSessionClearsComposerDraftAndDisablesSend() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        int start = js.indexOf("function startNewChatSession()");
        int end = js.indexOf("function restoredSessionSetting", start);
        String newChat = start >= 0 && end > start ? js.substring(start, end) : "";

        assertTrue(newChat.contains("dom.messageInput.value = \"\";"));
        assertTrue(newChat.contains("syncComposerDraftState();"));
        assertTrue(newChat.indexOf("dom.messageInput.value = \"\";")
                < newChat.indexOf("setComposerBusy(false);"));
    }

    @Test
    void newChatActionRemainsAvailableWhenResponseSettingsAreCollapsed() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        int settingsStart = html.indexOf("<details class=\"response-settings\"");
        int settingsEnd = html.indexOf("</details>", settingsStart);
        int newChat = html.indexOf("data-testid=\"chat-new-chat-button\"");

        assertTrue(settingsStart >= 0 && settingsEnd > settingsStart,
                "response settings should remain a collapsible disclosure");
        assertTrue(newChat >= 0, "New chat action should remain in the chat UI");
        assertTrue(newChat < settingsStart || newChat > settingsEnd,
                "New chat must not collapse to 0x0 with closed response settings");
    }

    @Test
    void shortDesktopDisclosuresKeepTranscriptAndComposerReachable() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);
        String shortDesktop = cssBlockAfter(css,
                "@media (min-width: 761px) and (max-height: 880px) {");
        assertTrue(shortDesktop.contains("height: auto;"),
                "short desktop chrome must not collapse the transcript or clip the composer");
        assertTrue(shortDesktop.contains("min-height: calc(100dvh - var(--top-bar-min-height) - 20px);"));
        assertFalse(shortDesktop.contains("overflow: auto;"),
                "the wrapper must not replace the transcript as the focus-scroll container");
        String diagnostics = cssBlockAfter(css,
                ".chat-area-wrapper:has(> .diagnostics-disclosure[open]) > .diagnostics-disclosure[open] > .diagnostics-stack {");
        assertTrue(diagnostics.contains("max-height: min(10vh, 88px);"));
        String settings = cssBlockAfter(css, ".chat-area-wrapper:has(> .response-settings[open]) {");
        assertTrue(settings.contains("height: auto;"));
    }

    @Test
    void newChatHeaderActionDoesNotGrowTheConversationStack() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);
        String conversationCopy = cssBlockAfter(css, ".conversation-copy {");
        String newChatAction = cssBlockAfter(css, ".new-chat-action {");

        assertTrue(conversationCopy.contains("display: grid;"));
        assertTrue(conversationCopy.contains("grid-template-columns: minmax(0, 1fr) auto;"));
        assertTrue(newChatAction.contains("grid-column: 2;"));
        assertTrue(newChatAction.contains("grid-row: 1 / span 2;"));
        assertTrue(newChatAction.contains("margin-top: 0;"));
    }

    @Test
    void desktopChatWrapperOwnsOneExplicitViewportHeight() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(css.contains("height: calc(100vh - var(--top-bar-min-height) - 20px);"));
        assertTrue(css.contains("height: calc(100dvh - var(--top-bar-min-height) - 20px);"));
        assertFalse(css.contains("max-height: calc(100vh - var(--top-bar-min-height) - 20px);"));
        assertFalse(css.contains("max-height: calc(100dvh - var(--top-bar-min-height) - 20px);"));
    }

    @Test
    void calmPremiumOperatorConsoleKeepsBrightAccessibleHierarchy() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);
        String body = cssBlockAfter(css, "body {");
        String topBar = cssBlockAfter(css, ".top-utility-bar {");
        String wrapper = cssBlockAfter(css, ".chat-area-wrapper {\n    grid-column: 1;");

        assertTrue(css.contains("calm-premium-operator-console"));
        assertTrue(css.contains("--surface-raised: #ffffff;"));
        assertTrue(css.contains("--shadow-control:"));
        assertTrue(css.contains("font-variant-numeric: tabular-nums;"));
        assertFalse(css.contains("radial-gradient("));
        assertTrue(body.contains("background: var(--page);"));
        assertTrue(topBar.contains("background: var(--surface-raised);"));
        assertTrue(wrapper.contains("height: calc(100vh - var(--top-bar-min-height) - 20px);"));
        assertTrue(wrapper.contains("overflow: hidden;"));
    }

    @Test
    void shortPhoneChatWrapperUsesFixedHeightAndShrinkableTranscript() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertEquals(2L, css.lines().filter(line -> line.strip().equals("height: calc(100dvh - 116px);")).count());
        assertFalse(css.contains("max-height: calc(100dvh - 116px);"));
        String transcript = cssBlockAfter(css, ".chat-transcript-region {");
        assertTrue(transcript.contains("min-height: 0;"),
                "the transcript must shrink before the static composer is displaced");
    }

    @Test
    void shortPhoneFinalCascadeRestoresCompactComposerGeometry() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int compactStart = css.lastIndexOf("@media (max-width: 760px) and (max-height: 760px) {");
        String compact = compactStart >= 0 ? css.substring(compactStart) : "";
        String composer = cssBlockAfter(compact, ".composer {");

        assertTrue(composer.contains("grid-template-columns: minmax(0, 1fr) 64px 64px;"));
        assertTrue(composer.contains("gap: 6px;"));
        assertTrue(composer.contains("padding: 6px 8px;"),
                "the final compact cascade must keep the composer inside the fixed-height wrapper");
    }

    @Test
    void chatFrontendDoesNotHardCodeAcademyLocationBiasIntoUserPrompt() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orgs = Files.readString(Path.of("main/resources/catalog/orgs.yml"), StandardCharsets.UTF_8);

        assertFalse(js.contains("LOCATION_BIAS_KEY"));
        assertFalse(js.contains("search.locationBias"));
        assertFalse(js.contains("needsDaejeonBias"));
        assertFalse(js.contains("applyLocationBiasIfNeeded"));
        assertFalse(js.contains("biasedText"));
        assertFalse(js.toLowerCase().contains("dwacademy"));
        assertTrue(js.contains("message: text,"));
        assertFalse(orgs.contains("dw_academy"));
        assertFalse(orgs.toLowerCase().contains("dwacademy"));
    }

    @Test
    void httpDebugUiDoesNotExposeRawRequestIdHeader() throws Exception {
        String fetchWrapper = Files.readString(Path.of("main/resources/static/js/fetch-wrapper.js"), StandardCharsets.UTF_8);
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertFalse(fetchWrapper.contains("requestId: pick('X-Request-Id')"));
        assertTrue(fetchWrapper.contains("requestIdHash: hashHeaderValue(pick('X-Request-Id'))"));
        assertFalse(chat.contains("requestId=${dbg.requestId}"));
        assertTrue(chat.contains("requestIdHash=${dbg.requestIdHash}"));
    }

    @Test
    void httpDebugUiDoesNotExposeRawUrlQuery() throws Exception {
        String fetchWrapper = Files.readString(Path.of("main/resources/static/js/fetch-wrapper.js"), StandardCharsets.UTF_8);

        assertFalse(fetchWrapper.contains("      url,"));
        assertFalse(fetchWrapper.contains("`${res.status} ${url}`"));
        assertTrue(fetchWrapper.contains("const debugUrl = safeDebugUrl(url);"));
        assertTrue(fetchWrapper.contains("urlPath: debugUrl.path"));
        assertTrue(fetchWrapper.contains("urlHash: debugUrl.hash"));
        assertTrue(fetchWrapper.contains("`[debug][http] ${res.status} ${debugUrl.path}`"));
    }

    @Test
    void narrowChatStatusCardsWrapLongDiagnosticsWithoutHidingThem() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int narrowStart = css.indexOf("@media (max-width: 760px) {");
        int shortStart = css.indexOf("@media (max-height: 760px) {", narrowStart);
        int compactStart = css.indexOf("@media (max-width: 760px) and (max-height: 760px) {");
        assertTrue(narrowStart >= 0 && shortStart > narrowStart && compactStart > shortStart,
                "chat CSS should keep distinct narrow, short, and narrow-short viewport rules");

        String narrow = css.substring(narrowStart, shortStart);
        String compact = css.substring(compactStart);
        String narrowHeartbeat = cssBlockAfter(narrow, ".debug-heartbeat-bar {");
        String narrowDetail = cssBlockAfter(narrow,
                ".debug-heartbeat-card small,\n    .debug-heartbeat-summary small {");
        String compactHealth = cssBlockAfter(compact, ".status-pill #healthStatus {");
        String compactHeartbeat = cssBlockAfter(compact, ".debug-heartbeat-bar {");

        assertTrue(narrow.contains("/* chat-status-cards-wrap-on-narrow-viewports */"));
        assertTrue(narrowHeartbeat.contains("overflow-x: hidden;"));
        assertTrue(narrowHeartbeat.contains("overflow-y: auto;"));
        assertTrue(narrowHeartbeat.contains("max-height: 104px;"));
        assertTrue(narrowDetail.contains("max-height: none;"));
        assertTrue(narrowDetail.contains("text-overflow: clip;"));
        assertTrue(narrowDetail.contains("overflow-wrap: anywhere;"));
        assertTrue(narrowDetail.contains("white-space: normal;"));
        assertTrue(compact.contains("/* chat-status-cards-remain-readable-on-short-phones */"));
        assertTrue(compactHealth.contains("overflow-wrap: anywhere;"));
        assertTrue(compactHealth.contains("white-space: normal;"));
        assertFalse(compactHealth.contains("white-space: nowrap;"));
        assertFalse(compactHealth.contains("overflow-wrap: normal;"));
        assertTrue(compactHeartbeat.contains("overflow-y: auto;"));
        assertTrue(compactHeartbeat.contains("max-height: 44px;"));
        assertTrue(countOccurrences(css,
                ".debug-heartbeat-card[data-debug-heartbeat-field=\"answer\"],") >= 3,
                "the existing Answer usage card must stay visible on narrow, short, and compact viewports");
    }

    @Test
    void statusRailWrapsWithoutANestedHorizontalScroller() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int railRules = 0;
        int from = 0;
        while ((from = css.indexOf(".status-rail {", from)) >= 0) {
            String block = cssBlockAfter(css.substring(from), ".status-rail {");
            assertFalse(block.contains("overflow-x: auto;"), block);
            assertFalse(block.contains("overflow-x: scroll;"), block);
            railRules++;
            from += ".status-rail {".length();
        }
        assertTrue(railRules >= 2, "base and responsive status-rail rules must remain explicit");

        int conversationStart = css.indexOf("/* Conversation-first console surfaces.");
        assertTrue(conversationStart >= 0);
        String conversationCss = css.substring(conversationStart);
        String rail = cssBlockAfter(conversationCss, ".status-rail {");
        String pill = cssBlockAfter(conversationCss, ".status-pill {");
        String pillText = cssBlockAfter(conversationCss, ".status-pill strong,\n.status-pill span {");

        assertTrue(rail.contains("display: flex;"));
        assertTrue(rail.contains("flex-wrap: wrap;"));
        assertTrue(rail.contains("overflow-x: visible;"));
        assertTrue(pill.contains("min-width: 0;"));
        assertTrue(pillText.contains("white-space: normal;"));
        assertTrue(pillText.contains("overflow-wrap: anywhere;"));

        int finalMobileStart = css.lastIndexOf("@media (max-width: 760px) {");
        assertTrue(finalMobileStart >= 0);
        String finalMobile = css.substring(finalMobileStart);
        String mobileRail = cssBlockAfter(finalMobile, ".status-rail {");
        String mobileHealthPill = cssBlockAfter(finalMobile, ".status-pill[data-health-pill] {");
        assertTrue(mobileRail.contains("display: grid;"));
        assertTrue(mobileRail.contains("grid-template-columns: repeat(3, minmax(0, 1fr));"));
        assertTrue(mobileRail.contains("overflow-x: visible;"));
        assertTrue(mobileHealthPill.contains("grid-column: 1 / -1;"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (text != null && needle != null && !needle.isEmpty()) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
        return count;
    }

    private static String cssBlockAfter(String text, String anchor) {
        int start = text.indexOf(anchor);
        if (start < 0) {
            return "";
        }
        int end = text.indexOf("}", start);
        return end < 0 ? text.substring(start) : text.substring(start, end + 1);
    }

    private static String functionBody(String text, String anchor) {
        int start = text.indexOf(anchor);
        if (start < 0) {
            return "";
        }
        int end = text.indexOf("\n  function ", start + anchor.length());
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }
}
