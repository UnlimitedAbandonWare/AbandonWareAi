param(
    [string]$Root = "."
)

$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Text,
        [string]$Needle,
        [string]$Message
    )
    if (-not $Text.Contains($Needle)) {
        throw $Message
    }
}

function Assert-Order {
    param(
        [string]$Text,
        [string]$Before,
        [string]$After,
        [string]$Message
    )
    $beforeIndex = $Text.IndexOf($Before)
    $afterIndex = $Text.IndexOf($After)
    if ($beforeIndex -lt 0 -or $afterIndex -lt 0 -or $beforeIndex -gt $afterIndex) {
        throw $Message
    }
}

$controllerPath = Join-Path $Root "main/java/com/example/lms/api/ChatApiController.java"
$controller = Get-Content -LiteralPath $controllerPath -Raw -Encoding UTF8

Assert-Contains $controller "emitStreamQueryRewriteTransformer(" "ChatApiController must emit a mid-stream Query Rewrite transformer block."
Assert-Contains $controller "java.util.concurrent.atomic.AtomicBoolean __queryRewriteTransformerEmitted" "Stream QTX emission must be one-shot per request."
Assert-Contains $controller "blocks.stream().anyMatch(block -> `"rewrite`".equals(block.id()))" "Stream QTX helper must gate on the rewrite transformer block."
Assert-Contains $controller "ChatStreamSignalBuilder.buildTransformerBlocks(" "Stream QTX helper must reuse the existing transformer builder."
Assert-Contains $controller "ChatStreamEvent.transformer(blocks)" "Stream QTX helper must emit the existing transformer SSE shape."
Assert-Contains $controller "logSuppressed(`"stream.queryRewriteTransformer`")" "Stream QTX helper must fail soft with a redacted suppression stage."

$streamSupplierStart = $controller.IndexOf("java.util.function.Function<String, java.util.List<String>> __webSupplier")
$streamSupplierEnd = $controller.IndexOf("emitDefaultModelWaitStatus(", $streamSupplierStart)
if ($streamSupplierStart -lt 0 -or $streamSupplierEnd -le $streamSupplierStart) {
    throw "ChatApiController stream web supplier boundary is missing."
}
$streamSupplier = $controller.Substring($streamSupplierStart, $streamSupplierEnd - $streamSupplierStart)

Assert-Order $streamSupplier '"reuse");' "emitStreamQueryRewriteTransformer(" "Reuse QTX emission must follow its identity decision."
Assert-Order $streamSupplier "emitStreamQueryRewriteTransformer(" "return __prefetched;" "Reuse QTX emission must run before prefetched snippets are returned."

$retryStart = $streamSupplier.IndexOf("recordWebPrefetchTrace(")
if ($retryStart -lt 0) {
    throw "ChatApiController stream retry trace boundary is missing."
}
$retrySupplier = $streamSupplier.Substring($retryStart)
Assert-Order $retrySupplier "recordWebPrefetchTrace(" "emitStreamQueryRewriteTransformer(" "Retry QTX emission must follow retry trace metadata."
Assert-Order $retrySupplier "emitStreamQueryRewriteTransformer(" "return snippets == null ? java.util.List.of() : snippets;" "Retry QTX emission must run before fresh snippets are returned."

if ($controller -match "rawUserQuery|rawQuery|providerQueryRaw") {
    throw "Stream QTX contract must not expose raw query fields."
}

Write-Host "[AWX][chat-stream-qtx] PASS"
