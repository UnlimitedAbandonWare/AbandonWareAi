package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPipelineStatusPageControllerTest {

    @Test
    void pageRouteReturnsPipelineStatusTemplate() {
        AgentPipelineStatusPageController controller = new AgentPipelineStatusPageController();

        assertEquals("pipeline-status", controller.page());
    }

    @Test
    void templateDefinesRequiredPipelineStatusSurface() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/pipeline-status.html"));

        assertTrue(html.contains("Pipeline Status"));
        assertTrue(html.contains("/agent/db-context/pipeline-health"));
        assertTrue(html.contains("id=\"memGrid\""));
        assertTrue(html.contains("id=\"debugOverviewGrid\""));
        assertTrue(html.contains("id=\"debugRollupStrip\""));
        assertTrue(html.contains("id=\"uiFetchGrid\""));
        assertTrue(html.contains("id=\"debugEventHealthGrid\""));
        assertTrue(html.contains("id=\"laneGrid\""));
        assertTrue(html.contains("id=\"modelRuntimeGrid\""));
        assertTrue(html.contains("id=\"answerOutputGrid\""));
        assertTrue(html.contains("id=\"traceSnapshotGrid\""));
        assertTrue(html.contains("id=\"providerRuntimeGrid\""));
        assertTrue(html.contains("id=\"failSoftLadderGrid\""));
        assertTrue(html.contains("id=\"debugAiMetricsGrid\""));
        assertTrue(html.contains("id=\"localLlmSmokeGrid\""));
        assertTrue(html.contains("class=\"local-llm-smoke-grid\""));
        assertTrue(html.contains("id=\"providerGrid\""));
        assertTrue(html.contains("id=\"externalRollupStrip\""));
        assertTrue(html.contains("id=\"externalGrid\""));
        assertTrue(html.contains("id=\"stratBody\""));
        assertTrue(html.contains("id=\"hotspotList\""));
        assertTrue(html.contains("id=\"debugFingerprintList\""));
        assertTrue(html.contains("Web Provider Credentials"));
        assertTrue(html.contains("External Evidence Readiness"));
        assertTrue(html.contains("Core/UI Debug Overview"));
        assertTrue(html.contains("UI Fetch Status"));
        assertTrue(html.contains("Debug Event Store Health"));
        assertTrue(html.contains("Answer Output Health"));
        assertTrue(html.contains("Trace Snapshot Readiness"));
        assertTrue(html.contains("Provider Runtime Pressure"));
        assertTrue(html.contains("Fail-Soft Ladder Health"));
        assertTrue(html.contains("Debug AI Metrics Readiness"));
        assertTrue(html.contains("Local LLM Route Pressure"));
        assertTrue(html.contains("renderDebugOverview"));
        assertTrue(html.contains("renderDebugRollupStrip"));
        assertTrue(html.contains("renderOpsControlStrip"));
        assertTrue(html.contains("renderUiFetchStatus"));
        assertTrue(html.contains("renderDebugEventHealth"));
        assertTrue(html.contains("data.debugOverview"));
        assertTrue(html.contains("item.name === 'debugRollup'"));
        assertTrue(html.contains("item.name === 'debugBlocker'"));
        assertTrue(html.contains("debugFingerprintFetch"));
        assertFalse(html.contains("item.name === 'uiDebug'"));
        assertTrue(html.contains("data.debugEventHealth"));
        assertTrue(html.contains("debugResult.status"));
        assertTrue(html.contains("healthResult.status"));
        assertTrue(html.contains("pipeline_health_unavailable"));
        assertTrue(html.contains("debug_fingerprints_unavailable"));
        assertTrue(html.contains("lastRefreshAt"));
        assertTrue(html.contains("autoRefresh ? 'auto:on interval:30s' : 'auto:off'"));
        assertTrue(html.contains("Default Model Runtime"));
        assertTrue(html.contains("renderWebProviders"));
        assertTrue(html.contains("renderExternalRollupStrip"));
        assertTrue(html.contains("renderExternalEvidence"));
        assertTrue(html.contains("renderModelRuntime"));
        assertTrue(html.contains("renderAnswerOutput"));
        assertTrue(html.contains("renderTraceSnapshotHealth"));
        assertTrue(html.contains("renderProviderRuntime"));
        assertTrue(html.contains("renderFailSoftLadder"));
        assertTrue(html.contains("renderDebugAiMetrics"));
        assertTrue(html.contains("Query rewrite"));
        assertTrue(html.contains("sub-models:${safe.queryRewriteSubModelCount ?? 0}"));
        assertTrue(html.contains("branch-titles:${safe.queryRewriteBranchTitleCount ?? 0}"));
        assertTrue(html.contains("title-hashes:${safe.queryRewriteBranchTitleHashCount ?? 0}"));
        assertTrue(html.contains("axes:${safe.queryRewriteBranchAxisCount ?? 0}"));
        assertTrue(html.contains("padded:${safe.queryRewritePaddedCount ?? 0}"));
        assertTrue(html.contains("renderLocalLlmSmoke"));
        assertTrue(html.contains("[lane.detail, lane.source].filter(Boolean).join(' / ')"));
        assertTrue(html.contains("data.modelRuntime"));
        assertTrue(html.contains("data.answerOutput"));
        assertTrue(html.contains("data.traceSnapshotHealth"));
        assertTrue(html.contains("data.providerRuntime"));
        assertTrue(html.contains("data.failSoftLadder"));
        assertTrue(html.contains("data.debugAiMetrics"));
        assertTrue(html.contains("localLlmResult.status"));
        assertTrue(html.contains("/api/diagnostics/local-llm/smoke-history?limit=12"));
        assertTrue(html.contains("safe.latest.cumulativeSignals"));
        assertTrue(html.contains("safe.latest.attemptScores"));
        assertTrue(html.contains("safe.latest.operatorAction"));
        assertTrue(html.contains("const operatorAction = safe.latest.operatorAction || {}"));
        assertTrue(html.contains("operatorAction.triggered === true"));
        assertTrue(html.contains("operatorAction.nextAction"));
        assertTrue(html.contains("operatorAction.actionScore"));
        assertTrue(html.contains("operatorAction.triggerReason"));
        assertTrue(html.contains("operatorAction.failureClass"));
        assertTrue(html.contains("operatorAction.scoreDelta"));
        assertTrue(html.contains("local-llm-smoke-row"));
        assertTrue(html.contains("local-llm-smoke-name"));
        assertTrue(html.indexOf("Default Model Runtime") < html.indexOf("Local LLM Route Pressure"));
        assertTrue(html.indexOf("Local LLM Route Pressure") < html.indexOf("Answer Output Health"));
        assertTrue(html.contains("safe.defaultWaitCode"));
        assertTrue(html.contains("safe.fastBailTimeout"));
        assertTrue(html.contains("safe.deliveryState"));
        assertTrue(html.contains("safe.finalDeliveryExpected"));
        assertTrue(html.contains("safe.modelGuardTriggered"));
        assertTrue(html.contains("guard:${yesNo(safe.modelGuardTriggered)}"));
        assertTrue(html.contains("safe.modelHash"));
        assertTrue(html.contains("safe.source"));
        assertTrue(html.contains("safe.awaitTimeoutCount"));
        assertTrue(html.contains("safe.cancelSuppressedCount"));
        assertTrue(html.contains("safe.cooldownSkippedCount"));
        assertTrue(html.contains("safe.emptyAnswerGuardTriggered"));
        assertTrue(html.contains("safe.evidenceListTraceInjected"));
        assertTrue(html.contains("safe.blankBaseFallback"));
        assertTrue(html.contains("safe.derivedSnippetCount"));
        assertTrue(html.contains("safe.summaryCount"));
        assertTrue(html.contains("safe.latestPathHash"));
        assertTrue(html.contains("safe.latestErrorPresent"));
        assertTrue(html.contains("safe.recentEventCount"));
        assertTrue(html.contains("safe.latestFingerprintHash"));
        assertTrue(html.contains("safe.totalSuppressed"));
        assertTrue(html.contains("safe.cacheOnlyMergedCount"));
        assertTrue(html.contains("safe.rescueMergeUsed"));
        assertTrue(html.contains("safe.starvationFallbackTrigger"));
        assertTrue(html.contains("safe.vectorFallbackUsed"));
        assertTrue(html.contains("safe.vectorFallbackReason"));
        assertTrue(html.contains("safe.vectorFallbackEffectiveTopK"));
        assertTrue(html.contains("safe.starvationFallbackCount"));
        assertTrue(html.contains("safe.starvationSafePoolSize"));
        assertTrue(html.contains("safe.totalEvents"));
        assertTrue(html.contains("safe.warnEvents"));
        assertTrue(html.contains("safe.topTile"));
        assertTrue(html.contains("safe.topFailureClass"));
        assertTrue(html.contains("provider.keySource"));
        assertTrue(html.contains("provider.disabledReason"));
        assertTrue(html.contains("item.projectScoped"));
        assertTrue(html.contains("item.authPresent"));
        assertTrue(html.contains("item.mutationAllowed"));
        assertTrue(html.contains("item.service === 'computer-use'"));
        assertTrue(html.contains("item.service === 'browser'"));
        assertTrue(html.contains("item.service === 'goal-next-auto'"));
        assertTrue(html.contains("item.service === 'patchdrop'"));
        assertTrue(html.contains("item.service === 'noether'"));
        assertTrue(html.contains("const externalStatusCounts = rows.reduce((acc, item) => {"));
        assertTrue(html.contains("strip.appendChild(textEl('span', 'external-rollup-chip', `warn:${externalStatusCounts.WARN || 0}`));"));
        assertTrue(html.contains("strip.appendChild(textEl('span', 'external-rollup-chip', `blocked:${externalBlockedCount}`));"));
        assertTrue(html.contains("strip.appendChild(textEl('span', 'external-rollup-chip', `browser:${browserRow?.statusClass || 'unknown'}`));"));
        assertTrue(html.contains("strip.appendChild(textEl('span', 'external-rollup-chip', `computer:${computerRow?.reachable ? 'reachable' : 'missing'}`));"));
        assertTrue(html.contains("strip.appendChild(textEl('span', 'external-rollup-chip', `supabase:${supabaseRow?.projectScopeStatus || 'unknown'}`));"));
        assertTrue(html.contains("item.evidenceScope || 'gui-supporting-only'"));
        assertTrue(html.contains("decision:${item.decision || 'unknown'}"));
        assertTrue(html.contains("apps:${item.appCount ?? 0}"));
        assertTrue(html.contains("running:${item.runningCount ?? 0}"));
        assertTrue(html.contains("windows:${item.targetableWindowCount ?? item.windowCount ?? 0}"));
        assertTrue(html.contains("countOnly:${yesNo(item.countOnly)}"));
        assertTrue(html.contains("names:${yesNo(item.storesAppNames)}"));
        assertTrue(html.contains("rawNames:${yesNo(item.storesRawAppNames)}"));
        assertTrue(html.contains("titles:${yesNo(item.storesWindowTitles)}"));
        assertTrue(html.contains("stale:${yesNo(item.stale)}"));
        assertTrue(html.contains("age:${item.ageMinutes ?? 0}/${item.staleAfterMinutes ?? 0}m"));
        assertTrue(html.contains("generated:${item.generatedAt || 'unknown'}"));
        assertTrue(html.contains("next:${item.nextAction || 'none'}"));
        assertTrue(html.contains("secretHits:${item.secretHits ?? 0}"));
        assertTrue(html.contains("item.targetableWindowCount"));
        assertTrue(html.contains("item.noTerminalAutomation ? 'blocked' : 'unknown'"));
        assertTrue(html.contains("item.evidenceScope || 'local-ui-proof'"));
        assertTrue(html.contains("localhost:${yesNo(item.localhost)}"));
        assertTrue(html.contains("screenshot:${yesNo(item.screenshotCaptured)}"));
        assertTrue(html.contains("status:${item.statusClass || 'unknown'}"));
        assertTrue(html.contains("error:${item.errorClass || 'none'}"));
        assertTrue(html.contains("target:${yesNo(item.targetContentVisible)}"));
        assertTrue(html.contains("surface:${item.browserSurface || 'unknown'}"));
        assertTrue(html.contains("stale:${yesNo(item.stale)}"));
        assertTrue(html.contains("age:${item.ageMinutes ?? 0}/${item.staleAfterMinutes ?? 0}m"));
        assertTrue(html.contains("next:${item.nextAction || 'none'}"));
        assertTrue(html.indexOf("secretHits:${item.secretHits ?? 0}", html.indexOf("item.service === 'browser'")) > 0);
        assertTrue(html.contains("active:${item.activeTopLevelPatchCount ?? 0}"));
        assertTrue(html.contains("nested:${item.nestedProducerPatchCount ?? 0}"));
        assertTrue(html.contains("reportOnly:${item.reportOnlyPendingCount ?? 0}"));
        assertTrue(html.contains("applied:${item.appliedPatchCount ?? 0}"));
        assertTrue(html.contains("pending:${item.pendingProducerNode || 'none'}:${item.pendingProducerTopic || 'none'}"));
        assertTrue(html.contains("patch:${item.pendingProducerPatchName || 'none'}"));
        assertTrue(html.contains("status:${item.pendingProducerStatus || 'none'}"));
        assertTrue(html.contains("sidecars:report=${yesNo(item.pendingProducerReportPresent)} verify=${yesNo(item.pendingProducerVerifyLogPresent)} sha=${yesNo(item.pendingProducerShaPresent)} complete=${yesNo(item.pendingProducerBundleComplete)}"));
        assertTrue(html.contains("isolation:${item.pendingProducerSourceIsolationGuard || 'unknown'} root:${item.pendingProducerSourceRootKind || 'unknown'} direct:${yesNo(item.pendingProducerDirectCanonicalSourceEdit)} shared:${yesNo(item.pendingProducerSharedSourceRoot)} ok:${yesNo(item.pendingProducerSourceIsolationOk)}"));
        assertTrue(html.contains("item.decision || 'evidence_needed'"));
        assertTrue(html.contains("gate:${item.externalInputGateStatus || 'unknown'}"));
        assertTrue(html.contains("mutation:${yesNo(item.externalInputMutationAllowed)}"));
        assertTrue(html.contains("actions:${item.nextActionCount ?? 0}"));
        assertTrue(html.contains("sources:${item.nextActionSources || 'none'}"));
        assertTrue(html.contains("top:${item.topActions || 'none'}"));
        assertTrue(html.contains("generated:${item.summaryGeneratedAt || 'unknown'}"));
        assertTrue(html.contains("updated:${item.summaryFileUpdatedAt || 'unknown'}"));
        assertTrue(html.contains("pathHash:${item.summaryPathHash || 'none'}:${item.summaryPathLength ?? 0}"));
        assertTrue(html.contains("computer:${yesNo(item.computerUseReachable)} stale:${yesNo(item.computerUseStale)} apps:${item.computerUseAppCount ?? 0} windows:${item.computerUseWindowCount ?? 0}"));
        assertTrue(html.contains("browser:${item.browserUseStatusClass || 'unknown'} stale:${yesNo(item.browserUseStale)} screenshot:${yesNo(item.browserUseScreenshotCaptured)} target:${yesNo(item.browserUseTargetContentVisible)}"));
        assertTrue(html.contains("rerun:${item.computerUseNextAction || 'none'}|${item.browserUseNextAction || 'none'}"));
        assertTrue(html.contains("repeat:${item.repeatCount ?? 0}"));
        assertTrue(html.contains("item.exitSummary || 'exit:unknown'"));
        assertTrue(html.contains("needed:supabase=${item.supabaseEvidenceNeededCount ?? 0} external=${item.externalEvidenceNeededCount ?? 0}"));
        assertTrue(html.contains("copied:evidence=${item.externalCopiedEvidenceCount ?? 0} handoff=${item.externalCopiedHandoffCount ?? 0}"));
        assertTrue(html.contains("roles:${item.externalRequiredRoles || 'none'}"));
        assertTrue(html.contains("env:${item.supabaseRequiredEnvNames || 'none'}"));
        assertTrue(html.contains("tools:${item.supabaseRequiredMcpTools || 'none'}"));
        assertTrue(html.contains("results:${item.supabaseRequiredResultNames || 'none'}"));
        assertTrue(html.contains("producerFiles:${item.externalRequiredProducerEvidenceFiles || 'none'}"));
        assertTrue(html.contains("sidecars:${item.externalRequiredPatchDropSidecars || 'none'}"));
        assertTrue(html.contains("sourceIsolation:${item.externalRequiredSourceIsolationGuard || 'unknown'}:${item.externalRequiredSourceRootKind || 'unknown'}:${yesNo(item.externalRequiredDirectCanonicalSourceEdit)}"));
        assertTrue(html.contains("mcp:${item.supabaseMcpDecision || 'unknown'}"));
        assertTrue(html.contains("external:${yesNo(item.externalEvidenceComplete)}"));
        assertTrue(html.contains("localPatch:${yesNo(item.localPatchJustified)}"));
        assertTrue(html.contains("agent:${item.agentName || 'noether'}"));
        assertTrue(html.contains("responded:${yesNo(item.responded)}"));
        assertTrue(html.contains("id:${item.agentIdHash || 'none'}:${item.agentIdLength ?? 0}"));
        assertTrue(html.contains("scope:${item.projectScopeStatus || 'unknown'}"));
        assertTrue(html.contains("auth:${item.authStatus || (item.authPresent ? 'auth_present' : 'auth_missing')}"));
        assertTrue(html.contains("features:${item.mcpFeatureGroups || 'unknown'}"));
        assertTrue(html.contains("host:${item.mcpEndpointHost || 'unknown'}"));
        assertTrue(html.contains("mcp:${item.mcpDecision || 'unknown'}"));
        assertTrue(html.contains("thread:${yesNo(item.executionThread)}"));
        assertTrue(html.contains("policy:${item.lanePolicy || 'external_evidence'}"));
        assertTrue(html.contains("scope:${item.evidenceScope || 'external-evidence-lane'}"));
        assertTrue(html.contains("needed:${item.evidenceNeededCount ?? 0}"));
        assertTrue(html.contains("env:${item.requiredEnvNames || 'none'}"));
        assertTrue(html.contains("tools:${item.requiredMcpTools || 'none'}"));
        assertTrue(html.contains("results:${item.requiredResultNames || 'none'}"));
        assertTrue(html.contains("item.evidenceNeeded"));
        assertTrue(html.contains("item.nextAction"));
        assertTrue(html.contains("/api/diagnostics/debug/fingerprints?limit=5"));
        assertTrue(html.contains("renderDebugFingerprints"));
        assertTrue(html.contains("recentFailureByHotspot"));
        assertTrue(html.contains("failure.failureClass"));
        assertTrue(html.contains("textEl('div', 'recent-failure-class'"));
        assertTrue(html.contains("Promise.allSettled"));
        assertTrue(html.contains("setInterval(load, 30000)"));
    }

    @Test
    void pipelineStatusShowsOneScreenOpsControlStrip() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/pipeline-status.html"));

        assertTrue(html.contains("id=\"opsControlStrip\""));
        assertTrue(html.contains("class=\"ops-control-strip\""));
        assertTrue(html.contains("data-ops-control-cell=\"core-ui\""));
        assertTrue(html.contains("data-ops-control-cell=\"model-answer\""));
        assertTrue(html.contains("data-ops-control-cell=\"search-trace\""));
        assertTrue(html.contains("data-ops-control-cell=\"external-proof\""));

        assertTrue(html.contains(".ops-control-strip {"));
        assertTrue(html.contains("grid-template-columns: repeat(4, minmax(0, 1fr));"));
        assertTrue(html.contains(".ops-control-cell[data-status=\"warn\"]"));
        assertTrue(html.contains(".ops-control-cell[data-status=\"ok\"]"));

        assertTrue(html.contains("function renderOpsControlStrip(data, healthResult, debugResult, localLlmResult) {"));
        assertTrue(html.contains("setOpsControlCell('core-ui', coreUiStatus, 'Core/UI', coreUiDetail);"));
        assertTrue(html.contains("setOpsControlCell('model-answer', modelAnswerStatus, 'Model/Answer', modelAnswerDetail);"));
        assertTrue(html.contains("setOpsControlCell('search-trace', searchTraceStatus, 'Search/Trace', searchTraceDetail);"));
        assertTrue(html.contains("setOpsControlCell('external-proof', externalProofStatus, 'External Proof', externalProofDetail);"));
        assertTrue(html.contains("renderOpsControlStrip(data, healthResult, debugResult, localLlmResult);"));

        assertFalse(html.contains("opsControlRaw"));
        assertFalse(html.contains("opsControlSecret"));
        assertFalse(html.contains("opsControlAuthorization"));
    }

    @Test
    void pipelineStatusLocalLlmSmokeShowsDebugTrigger() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/pipeline-status.html"));

        assertTrue(html.contains("['Debug trigger', latest.debugTrigger ? 'WARN' : 'OK'"));
        assertTrue(html.contains("`triggered:${yesNo(latest.debugTrigger)}`"));
        assertTrue(html.contains("`negative:${latest.negativeSignalCount ?? 0} secrets:${latest.secretPatternHits ?? 0}`"));
    }

    @Test
    void pipelineStatusRendersDebugHealthErrorTypeFields() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/pipeline-status.html"));
        String marker = "error:${safe.errorType || '-'}";

        assertTrue(countOccurrences(html, marker) >= 2);
        assertTrue(html.indexOf(marker, html.indexOf("function renderDebugEventHealth")) > 0);
        assertTrue(html.indexOf(marker, html.indexOf("function renderDebugAiMetrics")) > 0);
    }

    @Test
    void pipelineStatusRendersTraceSnapshotErrorTypeField() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/pipeline-status.html"));
        String marker = "error:${safe.errorType || '-'}";

        assertTrue(html.indexOf(marker, html.indexOf("function renderTraceSnapshotHealth")) > 0);
    }

    @Test
    void pipelineStatusRendersGoalNextDecisionSourceAndLocalReady() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/pipeline-status.html"));

        assertTrue(html.contains("source:${item.nextActionSource || 'unknown'}"));
        assertTrue(html.contains("localReady:${yesNo(item.localReady)}"));
        assertTrue(html.contains("scope:${item.supabaseProjectScopeStatus || 'unknown'}"));
    }

    @Test
    void adminDiagnosticPagesLinkToPipelineStatus() throws Exception {
        for (String path : new String[] {
                "main/resources/templates/dashboard.html",
                "main/resources/templates/brain-state.html",
                "main/resources/templates/debug-events.html",
                "main/resources/templates/trace-snapshots.html",
                "main/resources/templates/vector-diagnostics.html"
        }) {
            String html = Files.readString(Path.of(path));
            assertTrue(html.contains("/admin/pipeline-status"), path);
            assertTrue(html.contains("Pipeline Status"), path);
        }
    }

    @Test
    void debugEventsTableShowsRedactedCorrelationFieldsWithTextNodes() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/debug-events.html"));

        assertTrue(html.contains(">where</th>"));
        assertTrue(html.contains(">trace</th>"));
        assertTrue(html.contains(">request</th>"));
        assertTrue(html.contains(">sid</th>"));
        assertTrue(html.contains(">error</th>"));
        assertTrue(html.contains(">agg</th>"));
        assertTrue(html.contains(">signal</th>"));
        assertTrue(html.contains("filter: message / fingerprint / error / where / traceId / requestId / sid"));
        assertTrue(html.contains("id=\"detailErrorType\""));
        assertTrue(html.contains("id=\"detailSignal\""));
        assertTrue(html.contains("id=\"detailModelRoute\""));
        assertTrue(html.contains("id=\"detailRequestId\""));
        assertTrue(html.contains("id=\"detailAgg\""));
        assertTrue(html.contains("function appendTextCell(tr, value, className = '')"));
        assertTrue(html.contains("function eventErrorType(ev)"));
        assertTrue(html.contains("function eventSignalSummary(ev)"));
        assertTrue(html.contains("function eventModelRoute(ev)"));
        assertTrue(html.contains("function eventAggSummary(ev)"));
        assertTrue(html.contains("failureClass"));
        assertTrue(html.contains("suppressedStage"));
        assertTrue(html.contains("lanePolicy"));
        assertTrue(html.contains("subModelCount"));
        assertTrue(html.contains("branchTitleCount"));
        assertTrue(html.contains("branchAxisCount"));
        assertTrue(html.contains("paddedCount"));
        assertTrue(html.contains("requestedModelHash"));
        assertTrue(html.contains("selectedHash"));
        assertTrue(html.contains("suppressedInWindow"));
        assertTrue(html.contains("windowCount"));
        assertTrue(html.contains("appendTextCell(tr, ev.where, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, eventSignalSummary(ev), 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, ev.traceId, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, ev.requestId, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, ev.sid, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, eventAggSummary(ev), 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, eventErrorType(ev), 'mono')"));
        assertTrue(html.contains("$('detailErrorType').textContent = eventErrorType(data) || '-'"));
        assertTrue(html.contains("$('detailSignal').textContent = eventSignalSummary(data) || '-'"));
        assertTrue(html.contains("$('detailModelRoute').textContent = eventModelRoute(data) || '-'"));
        assertTrue(html.contains("$('detailAgg').textContent = eventAggSummary(data) || '-'"));
        assertTrue(html.contains("td.textContent = fmt(value);"));
        assertFalse(html.contains("tr.innerHTML = `"));
        assertFalse(html.contains("JSON.stringify(ev.error)"));
        assertFalse(html.contains("appendTextCell(tr, ev.traceId || ev.requestId, 'mono')"));
    }

    @Test
    void debugEventsSignalSummarySurfacesOperatorActionWithoutRawPayloads() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/debug-events.html"));

        assertTrue(html.contains("const nextAction = fmt(data.nextAction || data.operatorActionNext || data.localLlmNextAction);"));
        assertTrue(html.contains("const triggerReason = fmt(data.triggerReason || data.operatorTriggerReason || data.localLlmTriggerReason);"));
        assertTrue(html.contains("parts.push('action:' + nextAction);"));
        assertTrue(html.contains("parts.push('reason:' + triggerReason);"));
        assertFalse(html.contains("rawPrompt"));
        assertFalse(html.contains("rawQuery"));
    }

    @Test
    void debugEventsPageShowsLocalLlmSmokePressureWithoutRawPayloads() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/debug-events.html"));

        assertTrue(html.contains("Local LLM Smoke"));
        assertTrue(html.contains("/api/diagnostics/local-llm/smoke-history?limit=12"));
        assertTrue(html.contains("id=\"localLlmSmokeStatus\""));
        assertTrue(html.contains("id=\"localLlmRecommendedRoute\""));
        assertTrue(html.contains("id=\"localLlmThresholdExceeded\""));
        assertTrue(html.contains("id=\"localLlmNegativePressure\""));
        assertTrue(html.contains("id=\"localLlmOpenAiScore\""));
        assertTrue(html.contains("id=\"localLlmNativeScore\""));
        assertTrue(html.contains("id=\"localLlmDebugTrigger\""));
        assertTrue(html.contains("id=\"localLlmSecretPatternHits\""));
        assertTrue(html.contains("Local LLM Operator Action"));
        assertTrue(html.contains("id=\"localLlmOperatorActionNext\""));
        assertTrue(html.contains("id=\"localLlmOperatorTriggerReason\""));
        assertTrue(html.contains("id=\"localLlmOperatorFailureClass\""));
        assertTrue(html.contains("id=\"localLlmOperatorActionScore\""));
        assertTrue(html.contains("id=\"localLlmOperatorScoreDelta\""));
        assertTrue(html.contains("id=\"localLlmSmokeJson\""));
        assertTrue(html.contains("function renderLocalLlmSmoke(data)"));
        assertTrue(html.contains("function refreshLocalLlmSmoke()"));
        assertTrue(html.contains("$('localLlmRecommendedRoute').textContent"));
        assertTrue(html.contains("$('localLlmThresholdExceeded').textContent"));
        assertTrue(html.contains("$('localLlmDebugTrigger').textContent = fmt(latest.debugTrigger) || '-'"));
        assertTrue(html.contains("$('localLlmSecretPatternHits').textContent = fmt(latest.secretPatternHits) || '0'"));
        assertTrue(html.contains("const operatorAction = latest.operatorAction ? latest.operatorAction : {}"));
        assertTrue(html.contains("operatorAction.nextAction || 'monitor_local_llm_route'"));
        assertTrue(html.contains("operatorAction.triggerReason || 'none'"));
        assertTrue(html.contains("operatorAction.failureClass || 'none'"));
        assertTrue(html.contains("operatorAction.actionScore"));
        assertTrue(html.contains("operatorAction.scoreDelta"));
        assertTrue(html.contains("$('localLlmOperatorActionNext').textContent"));
        assertTrue(html.contains("$('localLlmOperatorTriggerReason').textContent"));
        assertTrue(html.contains("$('localLlmOperatorFailureClass').textContent"));
        assertTrue(html.contains("$('localLlmOperatorActionScore').textContent"));
        assertTrue(html.contains("$('localLlmOperatorScoreDelta').textContent"));
        assertTrue(html.contains("latest.cumulativeSignals"));
        assertTrue(html.contains("latest.attemptScores"));
        assertTrue(html.contains("await refreshLocalLlmSmoke();"));
        assertFalse(html.contains("rawPrompt"));
        assertFalse(html.contains("rawBody"));
        assertFalse(html.contains("rawModel"));
        assertFalse(html.contains("modelPath"));
        assertFalse(html.contains("rawPath"));
        assertFalse(html.contains("rawPayload"));
        assertFalse(html.contains("Authorization"));
        assertFalse(html.contains("ownerToken"));
    }

    @Test
    void traceSnapshotsTableRendersSnapshotRowsWithTextNodes() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/trace-snapshots.html"));

        assertTrue(html.contains("function appendTextCell(tr, value, className = '')"));
        assertTrue(html.contains("td.textContent = fmt(value);"));
        assertTrue(html.contains("appendTextCell(tr, snap.ts, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, snap.sid, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, snap.traceId, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, snap.reason)"));
        assertTrue(html.contains("appendTextCell(tr, snap.eventCount, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, snap.lastControlAction, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, snap.lastFailureReason, 'mono')"));
        assertTrue(html.contains("appendTextCell(tr, snap.status, 'mono')"));
        assertFalse(html.contains("tr.innerHTML = `"));
    }

    @Test
    void traceSnapshotsTableShowsRequestIdColumn() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/trace-snapshots.html"));

        assertTrue(html.contains("filter: sid / traceId / requestId / reason / path"));
        assertTrue(html.contains("<th class=\"text-left p-2\">requestId</th>"));
        assertTrue(html.contains("appendTextCell(tr, snap.requestId, 'mono');"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
