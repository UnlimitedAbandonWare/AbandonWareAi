package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUiSendMessageContractTest {

    @Test
    void chatComposerFallsBackToPostSoDraftIsNotLeakedInUrl() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(Pattern.compile("<form\\s+[^>]*id=\"chatForm\"[^>]*method=\"post\"[^>]*action=\"/api/chat\"")
                .matcher(html)
                .find(), "chat composer fallback must POST to avoid leaking drafts into /chat?message=...");
    }

    @Test
    void sendButtonDoesNotNativeSubmitJsonChatEndpointWhenScriptIsUnavailable() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(Pattern.compile("<button\\s+[^>]*id=\"sendBtn\"[^>]*type=\"button\"")
                .matcher(html)
                .find(), "Send must not native-submit form-encoded data to the JSON /api/chat endpoint");
        int listener = source.indexOf("dom.sendBtn?.addEventListener(\"click\", () => {");
        int listenerEnd = source.indexOf("});", listener);
        assertTrue(listener > 0,
                "Send click should be owned by chat.js after disabling native submit");
        assertTrue(listenerEnd > listener, "Send click listener block should be detectable");
        assertTrue(source.substring(listener, listenerEnd).contains("void sendMessage();"),
                "Send click should delegate to the existing JSON/SSE chat path");
    }

    @Test
    void sendMessageUsesSynchronousSingleFlightGuardAndAlwaysReleasesIt() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int declaration = source.indexOf("let sendMessageInFlight = false;");
        int send = source.indexOf("async function sendMessage()");
        int unlocked = source.indexOf("async function sendMessageUnlocked(text)", send);
        int blankGuard = source.indexOf("if (!text) {", send);
        int guard = source.indexOf("if (sendMessageInFlight || restoredRunResumeInFlight) return;", blankGuard);
        int lock = source.indexOf("sendMessageInFlight = true;", guard);
        int tryBlock = source.indexOf("try {", lock);
        int delegate = source.indexOf("return await sendMessageUnlocked(text);", tryBlock);
        int finallyBlock = source.indexOf("} finally {", delegate);
        int unlock = source.indexOf("sendMessageInFlight = false;", finallyBlock);

        assertTrue(declaration > 0 && declaration < send, "single-flight state should be declared before sendMessage");
        assertTrue(send > 0 && unlocked > send, "sendMessage should wrap the existing unlocked send path");
        assertTrue(blankGuard > send && guard > blankGuard,
                "blank input should return before the synchronous duplicate-send guard");
        assertTrue(lock > guard && tryBlock > lock && delegate > tryBlock,
                "sendMessage must acquire its lock before the first delegated await");
        assertTrue(finallyBlock > delegate && unlock > finallyBlock && unlock < unlocked,
                "success, cancellation, and exception paths must release the lock in the wrapper finally block");
    }

    @Test
    void normalChatStartsStreamAndNeverAutomaticallyStartsSyncGeneration() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int payload = source.indexOf("const payload = {");
        int firstStream = source.indexOf("await streamChat(payload, loaderId);", payload);
        int syncFallback = source.indexOf("const res = await apiCall(\"/api/chat\"", payload);

        assertTrue(payload > 0, "sendMessage payload block should exist");
        assertTrue(firstStream > payload, "sendMessage should call streamChat for normal chat");
        assertEquals(-1, syncFallback, "ambiguous stream delivery must never start a duplicate sync generation");

        String beforeFirstStream = source.substring(payload, firstStream);
        assertTrue(beforeFirstStream.contains("updateOrchestrationSignalBar({"),
                "RAG console should switch out of idle before network wait");
        assertFalse(beforeFirstStream.contains("if (dom.useRag?.checked)"),
                "streamChat must not be gated behind the RAG checkbox");
    }

    @Test
    void sendMessageFinallyReEnablesInputWithExecutableStatements() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int payload = source.indexOf("const payload = {");
        int finallyBlock = source.indexOf("} finally {", payload);
        int stopButton = source.indexOf("if (dom.stopBtn)", finallyBlock);
        String block = source.substring(finallyBlock, stopButton);

        assertTrue(Pattern.compile("(?m)^\\s*dom\\.messageInput\\.disabled\\s*=\\s*false;")
                .matcher(block)
                .find(), "message input must be re-enabled by executable code");
        assertTrue(Pattern.compile("(?m)^\\s*dom\\.messageInput\\.focus\\(\\);")
                .matcher(block)
                .find(), "message input focus must be restored by executable code");
    }

    @Test
    void finalDebugFxPrunesStaleLocalLlmWarningFromSameAnswerBubble() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int render = source.indexOf("function renderDebugFxTrace");
        int labels = source.indexOf("const labels = debugFxLabels(payload);", render);
        int prune = source.indexOf("pruneStaleLocalLlmDebugFxTraces(target, labels);", render);
        int append = source.indexOf("target?.appendChild(trace);", render);
        int helper = source.indexOf("function pruneStaleLocalLlmDebugFxTraces");

        assertTrue(render > 0, "Debug FX trace renderer should exist");
        assertTrue(labels > render, "renderer should derive labels before deciding stale local LLM cleanup");
        assertTrue(prune > labels, "renderer should prune stale local LLM traces from the same answer bubble");
        assertTrue(prune < append, "stale local LLM trace must be pruned before appending the final Debug FX line");
        assertTrue(helper > 0, "cleanup helper should be local to chat.js");
        assertTrue(source.contains("Local LLM model_blank"),
                "cleanup should target the visible stale local LLM warning text");
        assertTrue(source.contains("localLlmFailureClass === \"none\""),
                "cleanup should run only when the final Debug FX says the local LLM state recovered");
    }

    @Test
    void finalDebugFxReconcilesChatHarmonyRailStatus() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int helper = source.indexOf("function reconcileChatHarmonyRailFromDebugFx");
        int debugFxBranch = source.indexOf("} else if (type === \"debug_fx\") {");
        int reconcile = source.indexOf("reconcileChatHarmonyRailFromDebugFx(payload, assistant);", debugFxBranch);
        int render = source.indexOf("renderDebugFxTrace(payload, assistant);", debugFxBranch);

        assertTrue(helper > 0, "chat.js should reconcile the final Chat Harmony truth locally");
        assertTrue(source.contains("row.dataset.blockId = String(block?.id || \"\");"),
                "transformer rows need stable block ids for final reconciliation");
        assertTrue(source.contains("harmonyRow.dataset.status = finalStatus;"),
                "the final Debug FX status should replace the earlier stream-stage status");
        assertTrue(source.contains("bubble.__chatHarmonyFinalStatus = finalStatus;"),
                "the final status should stay scoped to the current answer bubble");
        assertTrue(source.contains("reason !== \"answer_shape_respected\""),
                "shape-only success must not overwrite a low-score Harmony warning");
        assertTrue(source.contains("applyChatHarmonyRailStatus(holder, anchor?.__chatHarmonyFinalStatus);"),
                "a later transformer event should reapply the final per-turn status");
        assertTrue(reconcile > debugFxBranch, "the final Debug FX branch should invoke reconciliation");
        assertTrue(reconcile < render, "the rail should reconcile before the final Debug FX trace is appended");
    }

    @Test
    void stopStreamMarksTransformerRailAsStoppedInsteadOfLeavingRunningStages() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int helper = source.indexOf("function markActiveStreamStoppedRail");
        int cancel = source.indexOf("async function cancelActiveStream(options = {})");
        int apply = source.indexOf("function applySuccessfulStreamCancel", helper);
        int abort = source.indexOf("controller?.abort();", apply);
        int call = source.indexOf("markActiveStreamStoppedRail(options.railReason", apply);
        int abortCatch = source.indexOf("if (error?.name === \"AbortError\" || streamCancelRequested) {");
        int abortCatchCall = source.indexOf("markActiveStreamStoppedRail(\"abort-error\"", abortCatch);

        assertTrue(helper > 0, "chat.js should have a local stop rail helper");
        assertTrue(cancel > helper, "stop helper should be defined before cancelActiveStream uses it");
        assertTrue(apply > helper && call > apply && call < abort,
                "only an acknowledged exact cancel should mark stopped before aborting");
        assertTrue(abortCatch > abort, "send failure handling should include the current AbortError/cancel branch");
        assertTrue(abortCatchCall > abortCatch, "AbortError fallback should also mark the visible rail stopped");
        assertTrue(source.contains("{ label: \"Stream\", status: \"cancelled\" }"),
                "stop rail should replace stale running/queued stream labels");
        assertTrue(source.contains("{ label: \"Model\", status: detail || \"stopped\" }"),
                "stop rail should explain why model output stopped");
    }

    @Test
    void stopButtonDelegatesClientAbortToCancelActiveStreamOnly() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int apply = source.indexOf("function applySuccessfulStreamCancel");
        int cancelAbort = source.indexOf("controller?.abort();", apply);
        int cancel = source.indexOf("async function cancelActiveStream(options = {})", cancelAbort);
        int listener = source.indexOf("dom.stopBtn?.addEventListener(\"click\", () => {", cancelAbort);
        int listenerEnd = source.indexOf("});", listener);

        assertTrue(apply > 0, "acknowledged cancellation should have one success helper");
        assertTrue(cancelAbort > apply, "the success helper should perform the single client stream abort");
        assertTrue(cancel > cancelAbort, "cancelActiveStream should delegate acknowledged cancellation to the helper");
        assertTrue(listener > cancel, "stop button listener should remain below cancelActiveStream");
        assertTrue(listenerEnd > listener, "stop button listener block should be detectable");
        assertFalse(source.substring(listener, listenerEnd).contains("streamController?.abort();"),
                "stop click should not abort twice; it should delegate to cancelActiveStream()");
    }

    @Test
    void stopCancelSettlesBeforeNextSendAndEofCannotLeaveBlankAssistantBubble() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int inFlight = source.indexOf("let streamCancelInFlight = null;");
        int cancel = source.indexOf("async function cancelActiveStream(options = {})");
        int assign = source.indexOf("streamCancelInFlight = cancelTask;", cancel);
        int wait = source.indexOf("async function waitForPendingStreamCancel()");
        String cancelBlock = source.substring(cancel, wait);
        int clearActiveSession = source.indexOf("clearActiveRunIdentityIfMatch(expectedRun)",
                source.indexOf("function applySuccessfulStreamCancel"));
        int send = source.indexOf("async function sendMessage()");
        int waitCall = source.indexOf("await waitForPendingStreamCancel();", send);
        int recovery = source.indexOf("async function recoverExactRunAfterTransportLoss(expectedRun, loaderId)");
        int recoveryState = source.indexOf("/api/chat/state?sessionId=${expectedRun.sessionId}", recovery);
        int recoveryAttach = source.indexOf("attach: true", recoveryState);
        int recoveryCall = source.indexOf("recoverExactRunAfterTransportLoss(expectedRun, loaderId)", send);
        int noSyncGeneration = source.indexOf("const res = await apiCall(\"/api/chat\"", send);
        int eof = source.indexOf("streamStatus: \"stream_end\", streamContext: \"eof\"");
        int eofGuard = source.indexOf("Stream ended before an answer. Please retry.", eof - 400);

        assertTrue(inFlight > 0, "client should track an in-flight server cancel");
        assertTrue(assign > cancel, "cancelActiveStream should publish the cancel promise before send can race it");
        assertFalse(cancelBlock.contains("forgetCurrentSessionId(sessionId);"),
                "stop should preserve the current session id after server cancel settles");
        assertTrue(clearActiveSession > 0,
                "acknowledged stop should compare-and-clear only its exact run capability");
        assertTrue(wait > cancel, "pending-cancel wait helper should live near cancel orchestration");
        assertTrue(waitCall > send, "sendMessage should wait for any pending server cancel before opening a new stream");
        assertTrue(recovery > 0 && recoveryState > recovery && recoveryAttach > recoveryState,
                "known-token delivery ambiguity should state-check and exact-attach");
        assertTrue(recoveryCall > send, "send failure should invoke exact-run recovery");
        assertEquals(-1, noSyncGeneration, "delivery ambiguity must not create a second sync generation");
        assertTrue(eofGuard > 0, "EOF without a terminal SSE event must not leave a blank assistant bubble");
    }

    @Test
    void restoredSessionHydrationValidatesStoredSessionBeforeSkippingRestoredMessages() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int hydrate = source.indexOf("async function hydrateRestoredSessionTranscript()");
        int messageCount = source.indexOf("const hydrationMessageCount = dom.chatMessages?.children?.length || 0;", hydrate);
        int hasExisting = source.indexOf("const hasExistingMessages = hydrationMessageCount > 0;", messageCount);
        int guard = source.indexOf("if (!sid || restoredSessionHydrated) return false;", hydrate);
        int fetch = source.indexOf("const response = await apiCall(`/api/chat/sessions/${sid}?restoreProbe=true`);", hydrate);
        int transcriptGuard = source.indexOf("(dom.chatMessages?.children?.length || 0) !== hydrationMessageCount", fetch);
        int softMissing = source.indexOf("if (detail?.found === false) {", fetch);
        int appendGuard = source.indexOf("if (!hasExistingMessages) {", fetch);
        int catch404 = source.indexOf("if (isHttp403(error) || error?.status === 404) {", hydrate);
        int forget = source.indexOf("forgetCurrentSessionId(sid);", catch404);
        int clear = source.indexOf("clearOrphanedSessionlessTranscript();", forget);

        assertTrue(hydrate > 0, "restored session hydrator should exist");
        assertTrue(messageCount > hydrate && hasExisting > messageCount,
                "hydrator should snapshot the restored transcript before awaiting the server");
        assertTrue(guard > hasExisting, "visible restored messages must not skip stale session validation");
        assertTrue(fetch > guard, "stored session id should be validated before deciding not to append messages");
        assertTrue(transcriptGuard > fetch,
                "a live turn added during hydration must invalidate the stale restore response");
        assertTrue(softMissing > fetch, "stale restore probes should clear state without a browser-visible 404");
        assertTrue(appendGuard > softMissing, "valid restored sessions should avoid duplicate visible messages");
        assertTrue(catch404 > fetch, "404/403 should still be handled as stale restore state");
        assertTrue(forget > catch404, "stale restored session id should be forgotten");
        assertTrue(clear > forget, "stale restored messages should be cleared after the id is forgotten");
    }

    @Test
    void sessionlessBrowserRestoredMessagesGetDelayedCleanupPass() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int reconcile = source.indexOf("function reconcileRestoredSessionTranscript()");
        int clear = source.indexOf("if (clearOrphanedSessionlessTranscript()) return true;", reconcile);
        int hydrate = source.indexOf("void hydrateRestoredSessionTranscript();", clear);
        int schedule = source.indexOf("function scheduleRestoredSessionTranscriptReconcile()");
        int timeout = source.indexOf("window.setTimeout(reconcileRestoredSessionTranscript, delayMs);", schedule);
        int startup = source.indexOf("restoreCurrentSessionId();");
        int startupReconcile = source.indexOf("reconcileRestoredSessionTranscript();", startup);
        int delayedStartup = source.indexOf("scheduleRestoredSessionTranscriptReconcile();", startupReconcile);
        int pageshow = source.indexOf("window.addEventListener(\"pageshow\", scheduleRestoredSessionTranscriptReconcile);", delayedStartup);

        assertTrue(reconcile > 0, "session restore reconciliation helper should exist");
        assertTrue(clear > reconcile, "sessionless restored messages should be cleared before hydration");
        assertTrue(hydrate > clear, "valid stored sessions should still be hydrated");
        assertTrue(schedule > hydrate, "delayed reconciliation helper should be defined after the immediate helper");
        assertTrue(timeout > schedule, "delayed pass should run after browser restoration timing");
        assertTrue(startupReconcile > startup, "startup should run the immediate reconciliation");
        assertTrue(delayedStartup > startupReconcile, "startup should also schedule the delayed browser-restore pass");
        assertTrue(pageshow > delayedStartup, "history/page restore should repeat the delayed cleanup");
    }

    @Test
    void sessionlessBrowserRestoreSchedulesLateCleanupPasses() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int schedule = source.indexOf("function scheduleRestoredSessionTranscriptReconcile()");
        int delays = source.indexOf("[0, 100, 500, 1500].forEach((delayMs) => {", schedule);
        int timeout = source.indexOf("window.setTimeout(reconcileRestoredSessionTranscript, delayMs);", delays);
        int startup = source.indexOf("scheduleRestoredSessionTranscriptReconcile();");
        int pageshow = source.indexOf("window.addEventListener(\"pageshow\", scheduleRestoredSessionTranscriptReconcile);", startup);

        assertTrue(schedule > 0, "session restore scheduler should exist");
        assertTrue(delays > schedule, "scheduler should cover late browser DOM restoration");
        assertTrue(timeout > delays, "scheduler should run the same cleanup at each delay");
        assertTrue(startup > timeout, "startup should use the multi-pass scheduler");
        assertTrue(pageshow > startup, "pageshow restore should also use the multi-pass scheduler");
    }

    @Test
    void sessionlessCleanupDoesNotClearActiveStreamTranscript() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int helper = source.indexOf("function streamTranscriptCleanupInProgress()");
        int activeAssistant = source.indexOf("activeStreamAssistant", helper);
        int activeController = source.indexOf("streamController", activeAssistant);
        int disabledComposer = source.indexOf("dom.messageInput?.disabled", activeController);
        int cleanup = source.indexOf("function clearOrphanedSessionlessTranscript()");
        int guard = source.indexOf("if (streamTranscriptCleanupInProgress()) return false;", cleanup);
        int clearMessages = source.indexOf("dom.chatMessages.replaceChildren();", cleanup);

        assertTrue(helper > 0, "chat.js should have a focused active-stream cleanup guard");
        assertTrue(activeAssistant > helper, "active assistant bubbles should block stale restore cleanup");
        assertTrue(activeController > activeAssistant, "active stream controller should block stale restore cleanup");
        assertTrue(disabledComposer > activeController, "disabled composer should block stale restore cleanup during send");
        assertTrue(guard > cleanup, "orphan cleanup should consult the active-stream guard");
        assertTrue(clearMessages > guard, "guard should run before transcript nodes can be removed");
    }

    @Test
    void newChatButtonClearsRestoredSessionAndTranscriptBeforeNextSend() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(Pattern.compile("<button\\s+[^>]*id=\"newChatBtn\"[^>]*type=\"button\"")
                        .matcher(html)
                        .find(),
                "chat UI should expose an explicit new chat control for restored sessions");

        int domHandle = source.indexOf("newChatBtn: $(\"newChatBtn\")");
        int helper = source.indexOf("function startNewChatSession()");
        int streamingGuard = source.indexOf("if (dom.messageInput?.disabled) {", helper);
        int forget = source.indexOf("forgetCurrentSessionId();", helper);
        int clearModes = source.indexOf("clearSessionModeDiagnostics();", forget);
        int clearMessages = source.indexOf("dom.chatMessages.replaceChildren();", clearModes);
        int restoreFlag = source.indexOf("restoredSessionHydrated = false;", clearMessages);
        int routeIdle = source.indexOf("setCoreStatus(\"idle\", \"idle\");", restoreFlag);
        int brainSignal = source.indexOf("dispatchBrainStateSignal('session', {});", restoreFlag);
        int listener = source.indexOf("dom.newChatBtn?.addEventListener(\"click\", startNewChatSession);", brainSignal);

        assertTrue(domHandle > 0, "chat.js should bind the new chat button");
        assertTrue(helper > domHandle, "new chat helper should live near session restore helpers");
        assertTrue(streamingGuard > helper, "new chat should not interrupt an in-flight response");
        assertTrue(forget > streamingGuard, "new chat should forget the stored session id");
        assertTrue(clearModes > forget, "new chat should clear restored session diagnostics");
        assertTrue(clearMessages > clearModes, "new chat should clear the visible transcript");
        assertTrue(restoreFlag > clearMessages, "new chat should allow future restore validation to run fresh");
        assertTrue(routeIdle > restoreFlag, "new chat should keep the route rail idle while Trace explains the reset");
        assertTrue(brainSignal > routeIdle, "new chat should publish an empty session signal");
        assertTrue(listener > brainSignal, "new chat button should use the helper");
    }

    @Test
    void sessionlessBrowserRestoreClearsSessionModeDiagnostics() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int helper = source.indexOf("function clearSessionModeDiagnostics");
        int helperList = source.indexOf("document.querySelector(\"[data-session-mode-list]\")", helper);
        int helperRows = source.indexOf("list.querySelectorAll(\"[data-session-mode-row]\")", helper);
        int helperRemove = source.indexOf("rows.forEach((row) => row.remove());", helper);
        int cleanup = source.indexOf("function clearOrphanedSessionlessTranscript()");
        int hasRows = source.indexOf("const hasSessionModeRows = Boolean(document.querySelector(\"[data-session-mode-list] [data-session-mode-row]\"));", cleanup);
        int clearCall = source.indexOf("clearSessionModeDiagnostics();", hasRows);
        int catch404 = source.indexOf("if (isHttp403(error) || error?.status === 404) {");
        int clearSid = source.indexOf("clearSessionModeDiagnostics(sid);", catch404);
        int forget = source.indexOf("forgetCurrentSessionId(sid);", clearSid);

        assertTrue(helper > 0, "chat.js should have a focused helper for session mode diagnostics cleanup");
        assertTrue(helperList > helper, "helper should own the session mode list lookup");
        assertTrue(helperRows > helperList, "helper should be able to remove rows for a stale stored session id");
        assertTrue(helperRemove > helperRows,
                "helper should clear every mode row without deleting co-located session-list rows");
        assertTrue(hasRows > cleanup, "sessionless cleanup should run when only diagnostic rows were restored");
        assertTrue(clearCall > hasRows, "sessionless cleanup should clear stale session mode rows");
        assertTrue(clearSid > catch404, "404/403 stale session recovery should remove that session row before forgetting the id");
        assertTrue(forget > clearSid, "stale id should still be forgotten after diagnostic cleanup");
    }

    @Test
    void staleRestoreFailureDoesNotForgetNewerActiveSession() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int forget = source.indexOf("function forgetCurrentSessionId(sessionId = null)");
        int expected = source.indexOf("const expectedSid = normalizeSessionIdValue(sessionId);", forget);
        int current = source.indexOf("const currentSid = sessionIdFromPayload({ sessionId: state.currentSessionId });", expected);
        int stored = source.indexOf("storedSid = normalizeSessionIdValue(window.sessionStorage?.getItem(CURRENT_SESSION_STORAGE_KEY));", current);
        int guard = source.indexOf("if (expectedSid && currentSid && currentSid !== expectedSid) return false;", stored);
        int catch404 = source.indexOf("if (isHttp403(error) || error?.status === 404) {");
        int clearSid = source.indexOf("clearSessionModeDiagnostics(sid);", catch404);
        int forgetSid = source.indexOf("forgetCurrentSessionId(sid);", clearSid);
        int cleanup = source.indexOf("clearOrphanedSessionlessTranscript();", forgetSid);

        assertTrue(forget > 0, "forget helper should accept the stale session id it is trying to clear");
        assertTrue(expected > forget, "forget helper should normalize the expected stale id");
        assertTrue(current > expected, "forget helper should compare against the live in-memory session id");
        assertTrue(stored > current, "forget helper should compare against the stored session id");
        assertTrue(guard > stored, "forget helper should not clear a newer active session after a stale restore 404");
        assertTrue(forgetSid > clearSid, "stale restore catch should pass the failed restore sid into forget");
        assertTrue(cleanup > forgetSid, "sessionless cleanup should still run after guarded forget");
    }

    @Test
    void missingStoredControlSettingsResetBrowserRestoredControlsToTemplateDefaults() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int optionHelper = source.indexOf("function defaultSelectOption(select)");
        int defaultSelected = source.indexOf("option.defaultSelected", optionHelper);
        int reset = source.indexOf("function resetControlSettingsToDefaults()");
        int defaultModel = source.indexOf("const defaultModel = defaultSelectOption(dom.modelSelect);", reset);
        int defaultSearch = source.indexOf("const defaultSearchMode = defaultSelectOption(dom.searchModeSelect);", reset);
        int defaultRag = source.indexOf("dom.useRag.checked = dom.useRag.defaultChecked !== false;", reset);
        int startup = source.indexOf("if (!restoreStoredControlSettings()) {");
        int startupReset = source.indexOf("resetControlSettingsToDefaults();", startup);
        int startupSync = source.indexOf("syncControlStatus({ persist: !smokeProofDefaultsApplied });", startupReset);

        assertTrue(optionHelper > 0, "select default helper should exist");
        assertTrue(defaultSelected > optionHelper, "select defaults should come from template defaultSelected state");
        assertTrue(reset > optionHelper, "control reset helper should exist");
        assertTrue(defaultModel > reset, "model select should reset to its server/template default");
        assertTrue(defaultSearch > defaultModel, "search mode should reset to its template default");
        assertTrue(defaultRag > defaultSearch, "RAG checkbox should reset to its template default");
        assertTrue(startupReset > startup, "startup should reset stale browser-restored controls when storage is missing");
        assertTrue(startupSync > startupReset, "rails should be synced after resetting defaults");
    }

    @Test
    void stoppedStreamDoesNotRenderAsAttentionNeeded() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        int title = source.indexOf("function debugHeartbeatSummaryTitle");
        int signalBar = source.indexOf("function renderLiveDebugHeartbeat");

        assertTrue(title > 0, "summary title helper should exist");
        assertTrue(signalBar > title, "stream signal rollup should be below the title helper");
        assertTrue(source.contains("return \"Response stopped\";"),
                "user-initiated stop should not be summarized as attention needed");
        assertTrue(source.contains("const streamStopped = /^(cancelled|stopped)$/i.test(streamStatus)"));
        assertTrue(source.contains("const liveStatus = streamDone || streamStopped || streamAnswerComplete ? 'OK' : 'WARN';"));
        assertTrue(source.contains("const cancelStatus = cancelReason === 'none' || streamStopped ? 'OK' : 'WARN';"));
    }

    @Test
    void successfulAnswerModesDoNotRenderAsAttentionNeeded() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        int completeModes = source.indexOf("const ANSWER_COMPLETE_MODES = new Set([");
        int helper = source.indexOf("const successfulAnswerMode = (mode) =>", completeModes);
        int signalBar = source.indexOf("function renderLiveDebugHeartbeat", helper);
        int streamComplete = source.indexOf(
                "const streamAnswerComplete = successfulAnswerMode(streamStatus) || successfulAnswerMode(partial.answerMode);",
                signalBar);

        assertTrue(completeModes > 0, "chat.js should keep an allowlist of successful answer modes");
        assertTrue(helper > completeModes, "successful answer mode helper should use the allowlist");
        assertTrue(signalBar > helper, "live heartbeat should use the successful answer mode helper");
        assertTrue(streamComplete > signalBar,
                "final chat/rag/history answer modes should not keep the heartbeat in WARN");
        assertTrue(source.contains("\"CHAT\", \"RAG\", \"HISTORY_CURRENT_TURN\", \"HISTORY_RECENT\", \"DIRECT_LITERAL\""),
                "ordinary chat, RAG, and local history answers should count as complete");
        assertTrue(source.contains("return ANSWER_COMPLETE_MODES.has(upper) || upper.startsWith(\"UI-MODE:LOCAL\");"),
                "request-local UI mode answers should also count as complete");
        assertTrue(source.contains("const liveStatus = streamDone || streamStopped || streamAnswerComplete ? 'OK' : 'WARN';"),
                "successful answer modes should render as OK instead of Attention needed");
        assertTrue(source.contains("const waitStatus = waitReason === 'none' && (streamDone || streamStopped || streamAnswerComplete) ? 'OK' : 'WARN';"),
                "successful answer modes should not leave the wait cell warning");
    }

    @Test
    void localUiModeAnswersUseLocalEvidenceRailAndSuppressGenericDiagnostics() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int label = source.indexOf("if (upper.startsWith(\"UI-MODE:LOCAL\")) {");
        int modelStatus = source.indexOf("const modelStatusRailValue = (model, mode) => {");
        int modelRail = source.indexOf("if (upper.startsWith(\"UI-MODE:LOCAL\")) {", modelStatus);
        int modelMarker = source.indexOf("const modelMarker = String(finalAnswerMode({}, modelText) || modelText).toUpperCase();", modelRail);
        int markerFallback = source.indexOf("if (modelMarker.startsWith(\"UI-MODE:LOCAL\")) return dom.modelSelect?.value || \"-\";", modelMarker);
        int selectedModel = source.indexOf("return modelText || dom.modelSelect?.value || \"-\";", markerFallback);
        int suppressHelper = source.indexOf("function suppressLocalUiModeDiagnostics(bubble, mode, model) {", selectedModel);
        int finalSuppression = source.indexOf("const localUiModeDiagnosticsSuppressed = suppressLocalUiModeDiagnostics(bubble, finalMode, model);", suppressHelper);
        int syncSuppression = source.indexOf("const localUiModeDiagnosticsSuppressed = suppressLocalUiModeDiagnostics(messageBubble, syncMode, model);", suppressHelper);

        assertTrue(label > 0, "UI mode local answers should have a human rail label");
        assertTrue(modelRail > modelStatus, "model rail should handle local UI evidence explicitly");
        assertTrue(modelMarker > modelRail, "payload UI-mode markers should be recognized before rendering the model rail");
        assertTrue(markerFallback > modelMarker, "payload UI-mode markers should fall back to the selected model");
        assertTrue(selectedModel > markerFallback, "real model values should stay visible on the model rail");
        assertTrue(suppressHelper > modelRail, "generic plan/evidence diagnostics should be removable for local UI answers");
        assertTrue(finalSuppression > suppressHelper, "SSE final path should suppress generic diagnostics");
        assertEquals(-1, syncSuppression, "automatic sync fallback path should be absent");
        assertTrue(source.contains("anchor?.dataset?.localUiModeDiagnostics === \"suppressed\""),
                "suppression state should be honored by later diagnostic renderers");
    }

    @Test
    void successfulAnswerClearsStaleLocalLlmFallbackHeartbeat() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        int helper = source.indexOf("function clearAssistantModelFallbackDebugOnSuccessfulAnswer(partial = {}, liveStatus = 'OK') {");
        int reset = source.indexOf("lastAssistantModelFallback = null;", helper);
        int model = source.indexOf("setDebugHeartbeatField(\"model\", \"OK\", modelDetail);", reset);
        int answer = source.indexOf("setDebugHeartbeatField(\"answer\", \"OK\",", model);
        int matrix = source.indexOf("setDebugMatrixCell(\"model-answer\", \"OK\", \"Model/Answer\", matrixDetail);", answer);
        int signalBar = source.indexOf("function renderLiveDebugHeartbeat", matrix);
        int streamComplete = source.indexOf("const streamAnswerComplete = successfulAnswerMode(streamStatus) || successfulAnswerMode(partial.answerMode);", signalBar);
        int call = source.indexOf("clearAssistantModelFallbackDebugOnSuccessfulAnswer(partial, liveStatus);", streamComplete);

        assertTrue(helper > 0, "chat.js should clear stale local LLM fallback UI after a successful answer");
        assertTrue(reset > helper, "clearing the UI should also reset the remembered fallback state");
        assertTrue(model > reset, "successful answer should overwrite the stale model heartbeat card");
        assertTrue(answer > model, "successful answer should overwrite the stale answer heartbeat card");
        assertTrue(matrix > answer, "successful answer should overwrite the stale Model/Answer matrix cell");
        assertTrue(call > streamComplete, "live heartbeat should clear stale fallback state after recognizing answer completion");
    }

    @Test
    void statusContextKeepsServerStatusLabelVisible() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        int helper = source.indexOf("const statusContext = (signal) =>");
        int handler = source.indexOf("} else if (type === \"status\") {");

        assertTrue(helper > 0, "statusContext helper should exist");
        assertTrue(handler > helper, "status events should use the shared statusContext helper");
        assertTrue(source.contains("const statusLabel = safeSignal.message || safeSignal.code || safeSignal.phase || \"status\";"),
                "stream status labels such as web_search_running must remain visible in the UI");
        assertTrue(source.contains("${statusLabel} remaining:${safeSignal.remainingMs ?? \"-\"}"),
                "status timing should be appended after the visible server label");
        assertTrue(source.contains("const signal = payload.signal || payload.statusSignal || payload.status_signal || payload;"),
                "status events must read the DTO statusSignal field, not only a legacy signal alias");
        assertTrue(source.contains("streamContext: waitReason || statusContext(signal)"));
    }

    @Test
    void sseErrorFinalizesAssistantBubbleWithoutRenderingServerDetail() throws Exception {
        String source = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        int handler = source.indexOf("} else if (type === \"error\") {");
        int nextBranch = source.indexOf("} else {", handler);

        assertTrue(handler > 0 && nextBranch > handler, "SSE error handler should be detectable");
        String errorBlock = source.substring(handler, nextBranch);
        assertTrue(errorBlock.contains(
                "setMessageContent(assistant, \"assistant\", \"message_failed\", \"error\");"),
                "terminal SSE errors must replace the pending assistant bubble");
        assertTrue(errorBlock.contains(
                "renderMessageFailedDiagnostic(assistant, new Error(\"stream_event_error\"));"),
                "terminal SSE errors should use a code-owned diagnostic reason");
        assertTrue(source.contains("arguments.length >= 3 ? failedSendReason(syncError, \"sync_failed\") : \"not_attempted\""),
                "SSE-only failures must not claim that an unattempted sync fallback failed");
        assertTrue(source.contains("if (terminalEventSeen) break;"),
                "a terminal SSE event must stop further reads before transport failure can trigger a duplicate sync request");
        assertTrue(errorBlock.contains(
                "updateOrchestrationSignalBar({ streamStatus: \"error\", streamContext: \"message_failed\" });"),
                "terminal SSE errors should finalize the visible signal rail");
        assertTrue(errorBlock.contains("setCoreStatus(\"error\", \"message_failed\");"),
                "terminal SSE errors should use a stable core status");
        assertFalse(errorBlock.contains("payload.data || \"error\""),
                "raw server error detail must not become a visible status label");
    }
}
