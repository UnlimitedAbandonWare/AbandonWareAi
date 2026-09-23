$ErrorActionPreference = "Stop"

$probePath = "main\java\com\example\lms\web\ChatUiCoreHeartbeatProbe.java"
$payloadPath = "main\java\com\example\lms\web\ChatUiHeartbeatPayload.java"
$chatJsPath = "main\resources\static\js\chat.js"
$chatHtmlPath = "main\resources\templates\chat-ui.html"
$chatCssPath = "main\resources\static\css\chat-style.css"

$probe = Get-Content $probePath -Raw
$payload = Get-Content $payloadPath -Raw
$chatJs = Get-Content $chatJsPath -Raw
$chatHtml = Get-Content $chatHtmlPath -Raw
$chatCss = Get-Content $chatCssPath -Raw
$harmonyDetailNeedle = @'
const harmonyDetail = `degraded:${harmonyDegraded ? 'true' : 'false'} action:${chatHarmony.latestDebugAction || 'wait_for_chat_harmony_trace'} reason:${chatHarmony.latestDebugReason || chatHarmony.latestReason || 'unknown'} route:${chatHarmony.latestTraceRoute || 'none'}`;
'@
$failures = New-Object System.Collections.Generic.List[string]

function Require-Contains([string]$Text, [string]$Needle, [string]$Label) {
    if (-not $Text.Contains($Needle)) {
        $failures.Add($Label)
    }
}

function Require-NotContains([string]$Text, [string]$Needle, [string]$Label) {
    if ($Text.Contains($Needle)) {
        $failures.Add($Label)
    }
}

Require-Contains $probe "listSummaries(" "heartbeat must inspect TraceSnapshotStore summaries"
Require-Contains $probe ".get(" "heartbeat must fetch the selected TraceSnapshot detail by id"
Require-Contains $probe '"chatHarmony"' "heartbeat must publish a redacted chatHarmony block"
Require-Contains $probe "latestDecision" "chatHarmony block must expose latestDecision"
Require-Contains $probe "latestReason" "chatHarmony block must expose latestReason"
Require-Contains $probe "latestDegraded" "chatHarmony block must expose latestDegraded"
Require-Contains $probe "latestWeightedScore" "chatHarmony block must expose latestWeightedScore"
Require-Contains $probe "latestEvidenceCount" "chatHarmony block must expose latestEvidenceCount"
Require-Contains $probe "latestAgentVisible" "chatHarmony block must expose latestAgentVisible"
Require-Contains $probe "latestTraceIdHash" "chatHarmony block must expose hash-only trace id"
Require-Contains $probe "SafeRedactor.hashValue" "trace id must be hashed before heartbeat exposure"
Require-Contains $probe "latestDebugAction" "chatHarmony block must expose latestDebugAction for agents"
Require-Contains $probe "latestDebugReason" "chatHarmony block must expose latestDebugReason for agents"
Require-Contains $probe "latestTraceRoute" "chatHarmony block must expose a fixed latest harmony trace route"
Require-Contains $probe "LATEST_HARMONY_TRACE_ROUTE" "latest harmony trace route must be a fixed route constant"
Require-Contains $probe "debug.ai.metrics.nextAction" "chatHarmony block must mirror debug AI nextAction"
Require-Contains $probe "debug.ai.metrics.nextReason" "chatHarmony block must mirror debug AI nextReason"
Require-Contains $probe "chatHarmonyInputPresent" "debugAiMetrics must expose harmony input presence"
Require-Contains $probe "chatHarmonyFailureClass" "debugAiMetrics must expose redacted harmony failure class"
Require-Contains $probe "evidenceOutputEvents" "debugAiMetrics must expose evidence-output event count"
Require-Contains $probe "nextDebugAction" "debugAiMetrics must expose a next debug action"
Require-Contains $probe "nextDebugReason" "debugAiMetrics must expose a redacted next debug reason"
Require-Contains $probe "hotChunkIndex" "debugAiMetrics must expose top virtual-matrix chunk index"
Require-Contains $probe "hotChunkRiskScore" "debugAiMetrics must expose top virtual-matrix risk score"
Require-Contains $probe "matrixActionFromDecision" "debugAiMetrics must derive action from virtual matrix decision"
Require-Contains $probe "heartbeatRollupReason" "heartbeat rollup must not let optional agent DB disablement hide current external evidence gaps"
Require-Contains $probe 'String heartbeatReason = heartbeatRollupReason(coreStatus, reason);' "heartbeat top-level reason must be derived from core/external evidence before projection"
Require-Contains $probe 'out.put("reason", heartbeatReason);' "heartbeat top-level reason must project the derived heartbeat reason"
Require-Contains $probe 'out.put("nextAction", heartbeatRollupNextAction(' "heartbeat top-level next action must point at the current proof gap"
Require-Contains $probe "planUsage" "debugAiMetrics must inspect planUsage"
Require-Contains $probe "DebugAiRawTile" "debugAiMetrics must read raw tile records before JSON serialization"
Require-Contains $probe "chat.harmony.postprocess" "debugAiMetrics must detect harmony plan usage"
Require-Contains $probe "chat_harmony" "debugAiMetrics must detect harmony failure class"
Require-Contains $payload '"chatHarmony"' "ChatUiHeartbeatPayload must allowlist chatHarmony"
Require-Contains $chatHtml 'data-debug-heartbeat-field="harmony"' "chat UI must render a visible Harmony heartbeat card"
Require-Contains $chatHtml "<span>Harmony</span>" "chat UI Harmony heartbeat card must have a visible label"
Require-Contains $chatCss '.debug-heartbeat-card[data-debug-heartbeat-field="harmony"]' "chat CSS compact heartbeat allowlist must display Harmony"
Require-Contains $chatJs "const chatHarmony = data.chatHarmony || {};" "chat UI must read redacted chatHarmony heartbeat data"
Require-Contains $chatJs '["supabase", "browser", "computer", "harmony"].includes(name)' "chat UI compact detail allowlist must include Harmony"
Require-Contains $chatJs "const harmonyDegraded = chatHarmony.latestDegraded === true || chatHarmony.latestDegraded === 'true';" "chat UI must derive Harmony degraded state from heartbeat"
Require-Contains $chatJs "const harmonyStatus = harmonyDegraded ? 'WARN' : (chatHarmony.status || (chatHarmony.latestAgentVisible ? 'OK' : 'WARN'));" "chat UI must let Harmony degraded state force WARN"
Require-Contains $chatJs $harmonyDetailNeedle.Trim() "chat UI must show Harmony next action, reason, and fixed route"
Require-Contains $chatJs "setDebugHeartbeatField('harmony', harmonyStatus, harmonyDetail);" "chat UI must update the Harmony heartbeat card"
Require-Contains $chatJs "setDebugHeartbeatField('harmony', 'WARN', 'chat_harmony_unknown');" "chat UI must have a Harmony fallback state"
Require-Contains $chatJs "function chatHarmonyDebugNextActionFallback(labels, debugAiNextAction)" "Debug FX summary must derive a chat-harmony next action before stale local LLM fallback"
Require-Contains $chatJs 'matrixDecision === "investigate_hot_chunk"' "Debug FX summary must map virtual-matrix investigate decisions to agent-visible actions"
Require-Contains $chatJs 'return "continue_observing_chat_harmony";' "Debug FX summary must keep smooth_chat turns on harmony observation when no matrix action is hot"
Require-Contains $chatJs 'const nextAction = localLlmNeedsAction ? localLlmNextAction : (chatHarmonyDebugNextActionFallback(labels, debugAiNextAction)' "Debug FX summary must prefer active local LLM action before chat harmony observation"

Require-NotContains $probe '"latestTraceId"' "heartbeat must not expose raw latest trace id"
Require-NotContains $probe '"answerText"' "heartbeat must not expose raw answer text"
Require-NotContains $probe '"rawAnswer"' "heartbeat must not expose raw answer"
Require-NotContains $probe '"rawQuery"' "heartbeat must not expose raw query"
Require-NotContains $probe '"prompt"' "heartbeat must not expose raw prompt"
Require-NotContains $chatJs "chatHarmony.latestTraceId`"" "chat UI must not read a raw latest trace id"
Require-NotContains $chatJs "chatHarmony.answerText" "chat UI must not read raw answer text"
Require-NotContains $chatJs "chatHarmony.rawAnswer" "chat UI must not read raw answer"
Require-NotContains $chatJs "chatHarmony.rawQuery" "chat UI must not read raw query"
Require-NotContains $chatJs "chatHarmony.prompt" "chat UI must not read raw prompt"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][chat-ui-heartbeat][harmony] FAIL"
    foreach ($failure in $failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "[AWX][chat-ui-heartbeat][harmony] PASS"
