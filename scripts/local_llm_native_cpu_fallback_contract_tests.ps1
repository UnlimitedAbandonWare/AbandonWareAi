$ErrorActionPreference = "Stop"

$nativePath = "main\java\com\example\lms\llm\OllamaNativeChatModel.java"
$dynamicFactoryPath = "main\java\com\example\lms\llm\DynamicChatModelFactory.java"
$llmConfigPath = "main\java\com\example\lms\config\LlmConfig.java"
$pipelineHealthPath = "main\java\com\example\lms\agent\context\AgentPipelineHealthController.java"
$chatWorkflowPath = "main\java\com\example\lms\service\ChatWorkflow.java"
$chatApiPath = "main\java\com\example\lms\api\ChatApiController.java"
$chatUiCoreHeartbeatPath = "main\java\com\example\lms\web\ChatUiCoreHeartbeatProbe.java"
$modelRuntimeHealthPath = "main\java\com\example\lms\llm\ModelRuntimeHealthTracker.java"
$yamlPath = "main\resources\application-llm.yaml"
$testPath = "src\test\java\com\example\lms\llm\OllamaNativeChatModelTest.java"

$native = Get-Content $nativePath -Raw
$dynamicFactory = Get-Content $dynamicFactoryPath -Raw
$llmConfig = Get-Content $llmConfigPath -Raw
$pipelineHealth = Get-Content $pipelineHealthPath -Raw
$chatWorkflow = Get-Content $chatWorkflowPath -Raw
$chatApi = Get-Content $chatApiPath -Raw
$chatUiCoreHeartbeat = Get-Content $chatUiCoreHeartbeatPath -Raw
$modelRuntimeHealth = Get-Content $modelRuntimeHealthPath -Raw
$yaml = Get-Content $yamlPath -Raw
$test = Get-Content $testPath -Raw
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

Require-Contains $native '"num_gpu"' "native Ollama adapter must send num_gpu when CPU fallback is configured"
Require-Contains $native "toNativeMessages(messages, prompt)" "native Ollama adapter must preserve chat message roles for /api/chat"
Require-Contains $native 'return "system";' "native Ollama adapter must map SystemMessage to system role"
Require-Contains $native 'return "assistant";' "native Ollama adapter must map AiMessage to assistant role"
Require-Contains $native "llm.ollamaNative.numGpu" "native Ollama adapter must trace redacted numGpu state"
Require-Contains $native "cpu_fallback" "native Ollama adapter must classify num_gpu=0 as cpu_fallback"
Require-Contains $native "native_success" "native Ollama adapter must clear stale operator warnings on nonblank success"
Require-Contains $native "llm.localSmoke.operatorAction.failureClass" "native Ollama adapter must update agent-visible local LLM operator state"
Require-Contains $native "ModelRuntimeHealthTracker" "native Ollama adapter must record app-level model runtime health"
Require-Contains $native "recordNativeSuccess()" "native Ollama adapter must record nonblank native success"
Require-Contains $native 'recordNativeFailure("blank_response")' "native Ollama adapter must record blank native responses"
Require-Contains $native "LAST_NATIVE_SUCCESS_EPOCH_MS" "native Ollama adapter must preserve recent native success inside the JVM"
Require-Contains $native "hasRecentNativeSuccess" "native Ollama adapter must expose recent native success to diagnostics"
Require-Contains $dynamicFactory 'llm.ollama-native.num-gpu' "dynamic factory must read llm.ollama-native.num-gpu"
Require-Contains $dynamicFactory "ollamaNativeNumGpu()" "dynamic factory must pass parsed numGpu to native adapter"
Require-Contains $dynamicFactory "modelRuntimeHealthTracker" "dynamic factory must pass model runtime health tracker to native adapter"
Require-Contains $llmConfig 'llm.ollama-native.num-gpu' "LlmConfig must read llm.ollama-native.num-gpu"
Require-Contains $llmConfig "ollamaNativeNumGpu()" "LlmConfig fast model must pass parsed numGpu to native adapter"
Require-Contains $llmConfig '"llm.ollamaNative.route.bean", "chatModel"' "LlmConfig primary chat model must use native qwen CPU fallback route"
Require-Contains $llmConfig "ModelRuntimeHealthTracker" "LlmConfig must inject model runtime health tracker into native adapter"
Require-Contains $pipelineHealth "ModelRuntimeHealthTracker" "pipeline health must see recent local model runtime success"
Require-Contains $pipelineHealth "OllamaNativeChatModel.hasRecentNativeSuccess" "pipeline health must accept native adapter success when tracker history is unavailable"
Require-Contains $pipelineHealth "ModelRuntimeHealthTracker.hasRecentLocalSuccess" "pipeline health must accept JVM-local chat success when bean history is unavailable"
Require-Contains $pipelineHealth "recent_local_model_success" "pipeline health must mark stale smoke warnings as overridden by recent local success"
Require-Contains $pipelineHealth "localLlmSmokeHistoryStale" "pipeline health must expose stale smoke suppression for agent-visible debugging"
Require-Contains $chatWorkflow "recordModelSuccess(primarySuccess.modelId(), primarySuccess.endpoint())" "main chat draft completion must record only the admitted model attempt"
Require-Contains $chatWorkflow "modelRuntimeHealthTracker.recordAttemptSuccess(" "workflow model completion must remain attempt-only"
Require-NotContains $chatWorkflow "ModelRuntimeHealthTracker.recordLocalSuccessSignal()" "workflow attempt completion must not bypass the controller semantic boundary"
Require-Contains $chatApi "recordVisibleModelSemanticOutcome(" "stream and sync final paths must use the controller-owned semantic boundary"
Require-Contains $chatApi "modelRuntimeHealthTracker.recordSemanticOutcome(" "controller semantic boundary must delegate the bounded outcome to the tracker"
Require-NotContains $chatApi "ModelRuntimeHealthTracker.recordLocalSuccessSignal()" "chat API must not promote a global local-success shortcut"
Require-Contains $modelRuntimeHealth "recordRouteSuccess(descriptor.key(), descriptor.endpoint())" "tracker must promote only the route accepted by the semantic boundary"
Require-Contains $modelRuntimeHealth "outcome.terminalState() != SemanticTerminalState.COMPLETED" "semantic promotion must require a completed terminal state"
Require-Contains $modelRuntimeHealth "!outcome.hardGuardAccepted()" "semantic promotion must require the hard guard"
Require-Contains $modelRuntimeHealth "!outcome.persistenceAccepted()" "semantic promotion must require persistence acceptance"
Require-Contains $modelRuntimeHealth "!outcome.deliveryAccepted()" "semantic promotion must require delivery acceptance"
Require-Contains $chatUiCoreHeartbeat "ModelRuntimeHealthTracker.hasRecentLocalSuccess" "core heartbeat fallback must accept JVM-local chat success"
Require-Contains $chatUiCoreHeartbeat "OllamaNativeChatModel.hasRecentNativeSuccess" "core heartbeat fallback must accept native adapter success"
Require-Contains $chatUiCoreHeartbeat "localLlmSmokeHistoryStale" "core heartbeat fallback must expose stale smoke suppression"
Require-Contains $modelRuntimeHealth "LAST_LOCAL_SUCCESS_EPOCH_MS" "model runtime health tracker must preserve recent local success inside the JVM"
Require-Contains $modelRuntimeHealth "hasRecentLocalSuccess" "model runtime health tracker must expose recent local success to diagnostics"
Require-Contains $yaml "num-gpu: `${LLM_OLLAMA_NATIVE_NUM_GPU:}" "application-llm must expose env-backed native num-gpu override"
Require-Contains $test "nativeAdapterCanForceCpuFallbackWithoutRawModelTrace" "unit contract must cover CPU fallback option"

Require-NotContains $native '"rawPrompt"' "native Ollama adapter must not publish raw prompt labels"
Require-NotContains $native '"rawModel"' "native Ollama adapter must not publish raw model labels"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][local-llm][native-cpu-fallback] FAIL"
    foreach ($failure in $failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "[AWX][local-llm][native-cpu-fallback] PASS"
