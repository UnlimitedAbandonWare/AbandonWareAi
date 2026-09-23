$ErrorActionPreference = "Stop"

$source = Get-Content "main\java\com\example\lms\api\ChatHarmonyTracePostprocessor.java" -Raw
$streamSignalBuilder = Get-Content "main\java\com\example\lms\api\ChatStreamSignalBuilder.java" -Raw
$test = Get-Content "src\test\java\com\example\lms\api\ChatHarmonyTracePostprocessorTest.java" -Raw
$failures = [System.Collections.Generic.List[string]]::new()

if ($source -notmatch 'TraceStore\.put\("chat\.harmony\.postprocess\.applied"') {
    $failures.Add("postprocessor must mirror applied=true into TraceStore")
}
if ($source -notmatch 'TraceStore\.put\("chat\.harmony\.postprocess\.agentVisible"') {
    $failures.Add("postprocessor must mirror agentVisible=true into TraceStore")
}
if ($source -notmatch 'TraceStore\.put\("chat\.harmony\.postprocess\.degraded"') {
    $failures.Add("postprocessor must mirror degraded state into TraceStore")
}
if ($source -notmatch 'TraceStore\.put\("chat\.harmony\.postprocess\.decision"') {
    $failures.Add("postprocessor must mirror decision into TraceStore")
}
if ($source -notmatch 'TraceStore\.put\("chat\.harmony\.postprocess\.reason"') {
    $failures.Add("postprocessor must mirror reason into TraceStore")
}
if ($source -notmatch 'TraceStore\.put\("chat\.harmony\.postprocess\.weightedScore"') {
    $failures.Add("postprocessor must mirror weightedScore into TraceStore")
}
if ($source -notmatch 'TraceStore\.put\("prompt\.agentDebugEvidence\.chatHarmony\.decision"') {
    $failures.Add("postprocessor must mirror chat harmony decision into agent-visible TraceStore prefix")
}
if ($source -notmatch 'TraceStore\.put\("prompt\.agentDebugEvidence\.chatHarmony\.degraded"') {
    $failures.Add("postprocessor must mirror chat harmony degraded state into agent-visible TraceStore prefix")
}
if ($source -notmatch 'TraceStore\.put\("prompt\.agentDebugEvidence\.chatHarmony\.nextAction"') {
    $failures.Add("postprocessor must mirror chat harmony nextAction into agent-visible TraceStore prefix")
}
if ($source -notmatch 'meta\.put\("prompt\.agentDebugEvidence\.chatHarmony\.decision"') {
    $failures.Add("postprocessor must mirror chat harmony decision into response meta prefix")
}
if ($source -notmatch 'meta\.put\("prompt\.agentDebugEvidence\.chatHarmony\.degraded"') {
    $failures.Add("postprocessor must mirror chat harmony degraded state into response meta prefix")
}
if ($source -notmatch 'meta\.put\("prompt\.agentDebugEvidence\.chatHarmony\.nextAction"') {
    $failures.Add("postprocessor must mirror chat harmony nextAction into response meta prefix")
}
if ($source -notmatch 'debug\.ai\.metrics\.nextAction') {
    $failures.Add("postprocessor must emit agent-visible Debug AI nextAction")
}
if ($source -notmatch 'debug\.ai\.metrics\.nextReason') {
    $failures.Add("postprocessor must emit redacted Debug AI nextReason")
}
if ($source -notmatch 'shapeAnswerForUserInstruction') {
    $failures.Add("postprocessor must expose a narrow answer-shaping seam for explicit user format requests")
}
if ($source -notmatch 'chat\.harmony\.postprocess\.shapeApplied') {
    $failures.Add("postprocessor must record whether user-format answer shaping was applied")
}
if ($source -notmatch 'one_sentence_user_request') {
    $failures.Add("postprocessor must classify explicit one-sentence user requests without storing raw text")
}
if ($source -notmatch 'one_sentence_user_request_already_satisfied') {
    $failures.Add("postprocessor must classify already-satisfied one-sentence answers as respected user format")
}
if ($source -notmatch 'shapeRespected') {
    $failures.Add("postprocessor must evaluate answer shape separately from fallback mode")
}
if ($source -notmatch 'answer_shape_respected') {
    $failures.Add("postprocessor must classify shaped one-sentence answers as respected user format, not degraded short evidence")
}
if ($source -notmatch 'inspect_chat_harmony_trace') {
    $failures.Add("degraded harmony must route agents to inspect_chat_harmony_trace")
}
if ($source -notmatch 'matrixActionFromDecision') {
    $failures.Add("postprocessor must derive next action from virtual matrix decision")
}
if ($streamSignalBuilder -notmatch 'firstNonBlank\(\s*safeString\(value\(meta, "debug\.ai\.metrics\.nextAction"\)\),\s*safeString\(value\(meta, "prompt\.agentDebugEvidence\.chatHarmony\.nextAction"\)\)') {
    $failures.Add("SSE Debug FX label must prefer postprocessed chat harmony nextAction before stale local LLM action")
}
if ($streamSignalBuilder -notmatch 'firstNonBlank\(\s*safeString\(value\(meta, "debug\.ai\.metrics\.nextReason"\)\),\s*safeString\(value\(meta, "prompt\.agentDebugEvidence\.chatHarmony\.nextReason"\)\)') {
    $failures.Add("SSE Debug FX label must prefer postprocessed chat harmony nextReason before fallback reasons")
}
if ($streamSignalBuilder -notmatch 'ensureSmoothChatDebugAiNextAction\(labels, safeMeta\)') {
    $failures.Add("SSE Debug FX label must fill smooth_chat nextAction before falling back to stale local LLM action")
}
if ($streamSignalBuilder -notmatch 'continue_observing_chat_harmony') {
    $failures.Add("SSE Debug FX label must expose continue_observing_chat_harmony for smooth_chat turns")
}
if ($source -match 'TraceStore\.put\([^;]*(rawAnswer|answerText|rawPayload|payload|content|Authorization)') {
    $failures.Add("TraceStore harmony breadcrumbs must not persist raw answer or payload text")
}
if ($test -notmatch 'enrichMirrorsHarmonyDecisionIntoTraceStoreForAgentDiagnostics') {
    $failures.Add("JUnit contract for TraceStore harmony breadcrumbs is missing")
}
if ($test -notmatch 'enrichTurnsDegradedHarmonyIntoAgentNextActionWithoutRawPayloads') {
    $failures.Add("JUnit contract for degraded harmony next action is missing")
}
if ($test -notmatch 'investigate_debug_ai_hot_chunk') {
    $failures.Add("JUnit contract must cover virtual matrix next action")
}
if ($test -notmatch 'TraceStore\.get\("chat\.harmony\.postprocess\.decision"\)') {
    $failures.Add("JUnit contract must assert TraceStore harmony decision")
}
if ($test -notmatch 'TraceStore\.get\("chat\.harmony\.postprocess\.degraded"\)') {
    $failures.Add("JUnit contract must assert TraceStore harmony degraded state")
}
if ($test -notmatch 'TraceStore\.get\("prompt\.agentDebugEvidence\.chatHarmony\.decision"\)') {
    $failures.Add("JUnit contract must assert agent-visible harmony decision prefix")
}
if ($test -notmatch 'TraceStore\.get\("prompt\.agentDebugEvidence\.chatHarmony\.degraded"\)') {
    $failures.Add("JUnit contract must assert agent-visible harmony degraded prefix")
}
if ($test -notmatch 'meta\.get\("prompt\.agentDebugEvidence\.chatHarmony\.decision"\)') {
    $failures.Add("JUnit contract must assert response meta agent-visible harmony decision prefix")
}
if ($test -notmatch 'meta\.get\("prompt\.agentDebugEvidence\.chatHarmony\.degraded"\)') {
    $failures.Add("JUnit contract must assert response meta agent-visible harmony degraded prefix")
}
if ($test -notmatch 'shapeAnswerForUserInstructionCondensesExplicitOneSentenceRequest') {
    $failures.Add("JUnit contract must cover explicit one-sentence answer shaping")
}
if ($test -notmatch 'enrichTreatsShapedOneSentenceAsSmoothChat') {
    $failures.Add("JUnit contract must cover shaped one-sentence harmony classification")
}
if ($test -notmatch 'enrichTreatsAlreadySatisfiedOneSentenceFallbackAsSmoothChatShape') {
    $failures.Add("JUnit contract must cover already-satisfied one-sentence fallback classification")
}

if ($failures.Count -gt 0) {
    Write-Host "[AWX][chat-harmony-contract] FAIL"
    foreach ($failure in $failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "[AWX][chat-harmony-contract] PASS"
