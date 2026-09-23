Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:Utf8NoBom = [Text.UTF8Encoding]::new($false)
$script:AllowedEndpoints = @('http://127.0.0.1:11434','http://127.0.0.1:11435')

function ConvertTo-AwxCanonicalNode {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [string] -or $Value -is [bool] -or $Value -is [byte] -or
        $Value -is [int16] -or $Value -is [int32] -or $Value -is [int64] -or
        $Value -is [single] -or $Value -is [double] -or $Value -is [decimal]) {
        return $Value
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $ordered = [ordered]@{}
        foreach ($key in @($Value.Keys | ForEach-Object { [string]$_ } | Sort-Object -CaseSensitive)) {
            $ordered[$key] = ConvertTo-AwxCanonicalNode $Value[$key]
        }
        return [pscustomobject]$ordered
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        $items = [Collections.Generic.List[object]]::new()
        foreach ($item in $Value) { $items.Add((ConvertTo-AwxCanonicalNode $item)) }
        return ,([object[]]$items.ToArray())
    }
    $properties = @($Value.PSObject.Properties | Where-Object MemberType -in @('NoteProperty','Property'))
    $object = [ordered]@{}
    foreach ($name in @($properties.Name | Sort-Object -CaseSensitive)) {
        $object[$name] = ConvertTo-AwxCanonicalNode $Value.$name
    }
    return [pscustomobject]$object
}

function ConvertTo-AwxCanonicalJson {
    param([Parameter(Mandatory)]$Value)
    $node = ConvertTo-AwxCanonicalNode $Value
    return (ConvertTo-Json -InputObject $node -Depth 40 -Compress)
}

function Get-AwxUtf8Sha256 {
    param([AllowEmptyString()][string]$Text)
    if ($null -eq $Text) { $Text = '' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $script:Utf8NoBom.GetBytes($Text)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()
    } finally { $sha.Dispose() }
}

function Get-AwxFileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-AwxLoopbackEndpoint {
    param([Parameter(Mandatory)][string]$Endpoint)
    if ($Endpoint -cnotin $script:AllowedEndpoints) { throw 'endpoint-not-allowlisted' }
}

function Get-AwxRequiredOntologies {
    return [ordered]@{
        gpu = @('RTX 3060','RTX 3090')
        role = @('fast','main','high','coder','rewrite','explore','vision','embedding')
        port = @(11434,11435)
        instructionToken = @('PASS','FAIL','HOLD')
        meaningUnit = @('DOWNLOAD_MODEL','VERIFY_BEFORE_DEFAULT_CHANGE','CHANGE_DEFAULT_ONLY_AFTER_PASS','CHANGE_DEFAULT_BEFORE_VERIFY','AUTO_DELETE_BASELINE')
        groundedClaim = @('QWEN35_IS_3060_CANDIDATE','GPU_LOAD_CHECK_BEFORE_PROMOTION','QWEN35_IS_3090_BASELINE','PROMOTION_BEFORE_GPU_CHECK')
        evidenceState = @('SUPPORTED','INSUFFICIENT','CONFLICTING')
        answerKind = @('NUMERIC','NO_VALUE')
        unit = @('MIB','GIB','NONE')
        uncertaintyReason = @('EVIDENCE_MISSING','EVIDENCE_CONFLICT','EVIDENCE_PRESENT','OUT_OF_SCOPE')
        promotionDecision = @('PROMOTE','HOLD','REJECT')
        promotionReason = @('CPU_OFFLOAD','QUALITY_REGRESSION','DIGEST_MISMATCH','INSUFFICIENT_VRAM','LATENCY_REGRESSION','LINEAGE_MISSING','ALL_GATES_PASS')
        citation = @('A','B','NONE')
    }
}

function Test-AwxExactSequence {
    param($Actual,$Expected)
    $a = @($Actual); $e = @($Expected)
    if ($a.Count -ne $e.Count) { return $false }
    for ($i = 0; $i -lt $a.Count; $i++) {
        if ((ConvertTo-AwxCanonicalJson $a[$i]) -cne (ConvertTo-AwxCanonicalJson $e[$i])) { return $false }
    }
    return $true
}

function Test-AwxObjectNode {
    param($Value)
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) { return $false }
    return ($Value -is [Collections.IDictionary] -or @($Value.PSObject.Properties | Where-Object MemberType -in @('NoteProperty','Property')).Count -gt 0)
}

function Get-AwxNodeProperties {
    param($Node)
    if (-not (Test-AwxObjectNode $Node)) { return @() }
    return @($Node.PSObject.Properties | Where-Object MemberType -in @('NoteProperty','Property'))
}

function Assert-AwxSchemaNode {
    param([Parameter(Mandatory)]$Node,[Parameter(Mandatory)]$KnownOntologies)
    if (-not (Test-AwxObjectNode $Node)) { throw 'benchmark-contract-invalid' }
    foreach ($property in @(Get-AwxNodeProperties $Node)) {
        if ($property.Name -cin @('const','default','examples')) { throw ($property.Name + '-forbidden') }
        if ($property.Name -cin @('pattern','minimum','maximum','description')) { throw 'benchmark-contract-invalid' }
        if ($property.Name -ceq 'enum') {
            $values = @($property.Value)
            if ($values.Count -lt 2) { throw 'single-valued-enum' }
            $matches = @($KnownOntologies.GetEnumerator() | Where-Object { Test-AwxExactSequence $values $_.Value })
            if ($matches.Count -ne 1) { throw 'ontology-narrowed' }
        }
        if ($property.Name -ceq 'minItems' -and $Node.PSObject.Properties['maxItems'] -and
            [int]$property.Value -eq [int]$Node.maxItems) { throw 'answer-cardinality-leaked' }
        $value = $property.Value
        if ($value -is [Collections.IEnumerable] -and $value -isnot [string] -and $value -isnot [Collections.IDictionary]) {
            foreach ($child in @($value)) { if (Test-AwxObjectNode $child) { Assert-AwxSchemaNode -Node $child -KnownOntologies $KnownOntologies } }
        } elseif (Test-AwxObjectNode $value) {
            Assert-AwxSchemaNode -Node $value -KnownOntologies $KnownOntologies
        }
    }
}

function Assert-AwxNoForbiddenRequestKey {
    param($Node)
    if (-not (Test-AwxObjectNode $Node)) { return }
    foreach ($property in @(Get-AwxNodeProperties $Node)) {
        if ($property.Name -in @('expected','oracle','correctAnswer')) { throw 'request-body-oracle-contamination' }
        $value = $property.Value
        if ($value -is [Collections.IEnumerable] -and $value -isnot [string] -and $value -isnot [Collections.IDictionary]) {
            foreach ($child in @($value)) { if (Test-AwxObjectNode $child) { Assert-AwxNoForbiddenRequestKey $child } }
        } elseif (Test-AwxObjectNode $value) { Assert-AwxNoForbiddenRequestKey $value }
    }
}

function Assert-AwxBenchmarkContract {
    param([Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle)
    Assert-AwxNoForbiddenRequestKey $Corpus
    Assert-AwxExactPropertySet -Value $Corpus -Names @('schemaVersion','corpusId','options','ontologies','cases')
    foreach ($case in @($Corpus.cases)) {
        Assert-AwxExactPropertySet -Value $case -Names @('id','role','metrics','prompt','responseSchema')
    }
    if (-not (Test-AwxStrictIntegerRange $Corpus.schemaVersion 2 2) -or
        -not (Test-AwxStrictIntegerRange $Oracle.schemaVersion 2 2) -or
        $Corpus.corpusId -isnot [string] -or $Oracle.corpusId -isnot [string] -or
        $Corpus.cases -isnot [System.Array] -or $Oracle.cases -isnot [System.Array] -or
        $Corpus.corpusId -cne 'awx.desktop-ollama-benchmark.v2' -or
        $Oracle.corpusId -cne $Corpus.corpusId) { throw 'benchmark-contract-invalid' }
    $caseIds = @('fast-rewrite-ko','fast-json-extract','fast-instruction','main-grounded-ko','main-uncertainty-ko','main-policy-ko')
    if (-not (Test-AwxExactSequence @($Corpus.cases.id) $caseIds) -or
        -not (Test-AwxExactSequence @($Oracle.cases.id) $caseIds)) { throw 'benchmark-contract-invalid' }
    $known = Get-AwxRequiredOntologies
    foreach ($name in @($known.Keys | Where-Object { $_ -cne 'citation' })) {
        if (-not (Test-AwxExactSequence @($Corpus.ontologies.$name) @($known[$name]))) { throw 'ontology-narrowed' }
    }
    foreach ($case in @($Corpus.cases)) { Assert-AwxSchemaNode -Node $case.responseSchema -KnownOntologies $known }
    if ((Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.options)) -cne 'e33d12218521f60ee293d79c26438a37d91a1f79a882f096a99d1703b098ee9f' -or
        (Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.ontologies)) -cne '6e3521a689e2d7921b62b7068ca5eb56f94a8ea48cef2ed9dbb86c0f281bd35b') {
        throw 'benchmark-contract-invalid'
    }
    $caseContractHashes = [ordered]@{
        'fast-rewrite-ko'='c7e34b7d8f339c1ad19048145db7d3a3b97a7e6197985c644dd5882c45614862'
        'fast-json-extract'='b04f428ed77d45ab55e684e9b9cb10563dd8c39dbb85f879bc46bf6c25bf06c8'
        'fast-instruction'='4d9c7a00dcdd2396077ef577cf8ba26cb42166ea961acf49542004fbe68440db'
        'main-grounded-ko'='ded91b7d5126bb2da7ffeeea179931369a6040ed25750e90527c30ebba473579'
        'main-uncertainty-ko'='33f23c9ef4359e3f3ef00f351f63aa7bdfcc946da49925c1215262b83c8af26c'
        'main-policy-ko'='abf1e3a6d6fb303226d1d1ad2c54c24c1862d2feb740c58bd8cafc5aae4b547b'
    }
    foreach ($case in @($Corpus.cases)) {
        $contract = [ordered]@{id=$case.id;role=$case.role;metrics=@($case.metrics);prompt=$case.prompt;responseSchema=$case.responseSchema}
        if ((Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $contract)) -cne $caseContractHashes[[string]$case.id]) { throw 'benchmark-contract-invalid' }
    }
    if ((Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Oracle)) -cne 'bb2a2df286b2a023735f1d30b5da8b1db0739f9385a95194f79285936dc23c0b') {
        throw 'benchmark-contract-invalid'
    }
}

function Assert-AwxOutboundBodyClean {
    param([Parameter(Mandatory)][string]$Body)
    if ($Body -match '(?i)ORACLE_SENTINEL_NEVER_SEND|"(expected|oracle|correctAnswer)"\s*:') { throw 'request-body-oracle-contamination' }
}

function New-AwxBenchmarkRequestBody {
    param(
        [Parameter(Mandatory)]$Case,
        [Parameter(Mandatory)][string]$ModelTag,
        [Parameter(Mandatory)]$Options
    )
    if ([string]::IsNullOrWhiteSpace($ModelTag) -or $ModelTag -match '(?i)(^|:)latest$') {
        throw 'candidate-not-allowlisted'
    }
    $body = [ordered]@{
        model = $ModelTag
        messages = @([ordered]@{ role = 'user'; content = [string]$Case.prompt })
        stream = [bool]$Options.stream
        think = [bool]$Options.think
        format = $Case.responseSchema
        keep_alive = [string]$Options.keep_alive
        options = [ordered]@{
            temperature = [double]$Options.temperature
            seed = [int]$Options.seed
            num_ctx = [int]$Options.num_ctx
            num_predict = [int]$Options.num_predict
        }
    }
    $json = ConvertTo-AwxCanonicalJson $body
    Assert-AwxOutboundBodyClean $json
    return $json
}

function New-AwxCheckResult([bool]$Passed,[string]$ReasonCode) {
    return [pscustomobject]@{ passed = $Passed; reasonCode = $ReasonCode }
}

function Test-AwxNumber {
    param($Value)
    return ($Value -is [byte] -or $Value -is [sbyte] -or $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or $Value -is [int64] -or $Value -is [uint64] -or
        $Value -is [single] -or $Value -is [double] -or $Value -is [decimal])
}

function Test-AwxJsonSchemaValue {
    param($Value,[Parameter(Mandatory)]$Schema)
    if ($Schema.PSObject.Properties['anyOf']) {
        foreach ($candidate in @($Schema.anyOf)) {
            if ((Test-AwxJsonSchemaValue -Value $Value -Schema $candidate).passed) { return New-AwxCheckResult $true 'pass' }
        }
        return New-AwxCheckResult $false 'response-schema-invalid'
    }
    if ($Schema.PSObject.Properties['enum']) {
        $actualJson = ConvertTo-AwxCanonicalJson $Value
        if (@($Schema.enum | Where-Object { (ConvertTo-AwxCanonicalJson $_) -ceq $actualJson }).Count -ne 1) {
            return New-AwxCheckResult $false 'response-schema-invalid'
        }
    }
    $type = [string]$Schema.type
    switch ($type) {
        'null' { if ($null -ne $Value) { return New-AwxCheckResult $false 'response-schema-invalid' } }
        'string' {
            if ($Value -isnot [string]) { return New-AwxCheckResult $false 'response-schema-invalid' }
            if ($Schema.PSObject.Properties['minLength'] -and $Value.Length -lt [int]$Schema.minLength) { return New-AwxCheckResult $false 'response-schema-invalid' }
            if ($Schema.PSObject.Properties['maxLength'] -and $Value.Length -gt [int]$Schema.maxLength) { return New-AwxCheckResult $false 'response-schema-invalid' }
        }
        'integer' {
            if (-not (Test-AwxNumber $Value) -or [double]$Value -ne [Math]::Truncate([double]$Value)) { return New-AwxCheckResult $false 'response-schema-invalid' }
        }
        'number' { if (-not (Test-AwxNumber $Value)) { return New-AwxCheckResult $false 'response-schema-invalid' } }
        'array' {
            if ($Value -isnot [System.Array]) { return New-AwxCheckResult $false 'response-schema-invalid' }
            $items = @($Value)
            if ($Schema.PSObject.Properties['minItems'] -and $items.Count -lt [int]$Schema.minItems) { return New-AwxCheckResult $false 'response-schema-invalid' }
            if ($Schema.PSObject.Properties['maxItems'] -and $items.Count -gt [int]$Schema.maxItems) { return New-AwxCheckResult $false 'response-schema-invalid' }
            if ($Schema.PSObject.Properties['uniqueItems'] -and [bool]$Schema.uniqueItems) {
                $keys = @($items | ForEach-Object { ConvertTo-AwxCanonicalJson $_ })
                if (@($keys | Select-Object -Unique).Count -ne $keys.Count) { return New-AwxCheckResult $false 'response-schema-invalid' }
            }
            foreach ($item in $items) {
                $child = Test-AwxJsonSchemaValue -Value $item -Schema $Schema.items
                if (-not $child.passed) { return $child }
            }
        }
        'object' {
            if ($null -eq $Value -or $Value -is [string] -or @($Value.PSObject.Properties).Count -eq 0) { return New-AwxCheckResult $false 'response-schema-invalid' }
            $names = @($Value.PSObject.Properties.Name)
            foreach ($required in @($Schema.required)) {
                if ($names -cnotcontains [string]$required) { return New-AwxCheckResult $false 'response-schema-invalid' }
            }
            $allowed = @($Schema.properties.PSObject.Properties.Name)
            if (-not [bool]$Schema.additionalProperties -and @($names | Where-Object { $_ -cnotin $allowed }).Count -gt 0) {
                return New-AwxCheckResult $false 'response-schema-invalid'
            }
            foreach ($property in @($Schema.properties.PSObject.Properties)) {
                if ($names -ccontains $property.Name) {
                    $child = Test-AwxJsonSchemaValue -Value $Value.($property.Name) -Schema $property.Value
                    if (-not $child.passed) { return $child }
                }
            }
        }
        default { return New-AwxCheckResult $false 'response-schema-invalid' }
    }
    return New-AwxCheckResult $true 'pass'
}

function Test-AwxExactSet {
    param($Actual,$Expected)
    $a = @($Actual | ForEach-Object { ConvertTo-AwxCanonicalJson $_ } | Sort-Object -CaseSensitive)
    $e = @($Expected | ForEach-Object { ConvertTo-AwxCanonicalJson $_ } | Sort-Object -CaseSensitive)
    return Test-AwxExactSequence $a $e
}

function Test-AwxSemanticEnvelope {
    param([Parameter(Mandatory)][string]$CaseId,[Parameter(Mandatory)]$Envelope,[Parameter(Mandatory)]$Expected)
    $pass = switch ($CaseId) {
        'fast-rewrite-ko' {
            $rewrite = ([string]$Envelope.rewrite).Trim()
            $rewrite.Length -ge 1 -and $rewrite.Length -le 160 -and $rewrite -notmatch '[\r\n]' -and
                (Test-AwxExactSet @($Envelope.meaningUnits) @($Expected.meaningUnits))
        }
        'fast-json-extract' {
            [string]$Envelope.gpu -ceq [string]$Expected.gpu -and
            [string]$Envelope.role -ceq [string]$Expected.role -and
            [int]$Envelope.port -eq [int]$Expected.port
        }
        'fast-instruction' { [string]$Envelope.token -ceq [string]$Expected.token }
        'main-grounded-ko' {
            $summary = ([string]$Envelope.summary).Trim()
            $actualPairs = @($Envelope.claims | ForEach-Object { '{0}|{1}' -f $_.claimCode,$_.citation })
            $expectedPairs = @($Expected.claims | ForEach-Object { '{0}|{1}' -f $_.claimCode,$_.citation })
            $summary.Length -ge 1 -and $summary.Length -le 400 -and $summary -match '[\uac00-\ud7a3]' -and
                (Test-AwxExactSet $actualPairs $expectedPairs)
        }
        'main-uncertainty-ko' {
            [string]$Envelope.evidenceState -ceq [string]$Expected.evidenceState -and
            [string]$Envelope.answerKind -ceq [string]$Expected.answerKind -and
            $null -eq $Envelope.value -and $null -eq $Expected.value -and
            [string]$Envelope.unit -ceq [string]$Expected.unit -and
            (Test-AwxExactSet @($Envelope.reasonCodes) @($Expected.reasonCodes))
        }
        'main-policy-ko' {
            [string]$Envelope.decision -ceq [string]$Expected.decision -and
            (Test-AwxExactSet @($Envelope.reasonCodes) @($Expected.reasonCodes))
        }
        default { $false }
    }
    return New-AwxCheckResult ([bool]$pass) $(if ($pass) { 'pass' } else { 'response-semantic-mismatch' })
}

function Test-AwxBenchmarkResponse {
    param(
        [Parameter(Mandatory)]$Case,
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)][string]$ExpectedModel,
        [Parameter(Mandatory)]$OllamaResponse
    )
    $hasModel = $null -ne $OllamaResponse.PSObject.Properties['model']
    $hasDoneReason = $null -ne $OllamaResponse.PSObject.Properties['done_reason']
    $hasMessage = $null -ne $OllamaResponse.PSObject.Properties['message'] -and $null -ne $OllamaResponse.message
    $hasContent = $hasMessage -and $null -ne $OllamaResponse.message.PSObject.Properties['content'] -and $OllamaResponse.message.content -is [string]
    $content = $(if ($hasContent) {[string]$OllamaResponse.message.content} else {''})
    $thinking = $(if ($hasMessage -and $null -ne $OllamaResponse.message.PSObject.Properties['thinking']) {[string]$OllamaResponse.message.thinking} else {''})
    $boundedDoneReason = 'unknown'
    if ($hasDoneReason) {
        $candidateDoneReason = [string]$OllamaResponse.done_reason
        if ($candidateDoneReason -ceq 'stop' -or $candidateDoneReason -ceq 'length') { $boundedDoneReason = $candidateDoneReason }
    }
    $boundedTokenCount = $null
    if ($OllamaResponse.PSObject.Properties['eval_count'] -and (Test-AwxStrictIntegerRange $OllamaResponse.eval_count 0 ([long]::MaxValue))) {
        try { $boundedTokenCount = [int64]$OllamaResponse.eval_count }
        catch { $boundedTokenCount = $null }
    }
    $safe = [ordered]@{
        transportPassed = $true; lineagePassed = $false; jsonParsed = $false
        schemaPassed = $false; semanticPassed = $false; reasonCode = 'lineage-missing'
        responseSha256 = Get-AwxUtf8Sha256 $content
        responseLength = $content.Length
        tokenCount = $boundedTokenCount
        thinkingLength = $thinking.Length
        doneReason = $boundedDoneReason
    }
    if (-not $hasModel -or -not $hasDoneReason -or -not $hasMessage -or -not $hasContent -or [string]$OllamaResponse.model -cne $ExpectedModel -or $safe.thinkingLength -ne 0) { return [pscustomobject]$safe }
    if ($safe.doneReason -ceq 'length') { $safe.lineagePassed = $true; $safe.reasonCode = 'response-truncated'; return [pscustomobject]$safe }
    if ($safe.doneReason -cne 'stop') { $safe.reasonCode = 'lineage-missing'; return [pscustomobject]$safe }
    $safe.lineagePassed = $true
    try { $envelope = $content | ConvertFrom-Json }
    catch { $safe.reasonCode = 'response-json-invalid'; return [pscustomobject]$safe }
    $safe.jsonParsed = $true
    $schema = Test-AwxJsonSchemaValue -Value $envelope -Schema $Case.responseSchema
    if (-not $schema.passed) { $safe.reasonCode = $schema.reasonCode; return [pscustomobject]$safe }
    $safe.schemaPassed = $true
    $semantic = Test-AwxSemanticEnvelope -CaseId $Case.id -Envelope $envelope -Expected $Expected
    $safe.semanticPassed = $semantic.passed
    $safe.reasonCode = $semantic.reasonCode
    $envelope = $null
    $content = $null
    return [pscustomobject]$safe
}

function Test-AwxStrictBoolean { param($Value); return $Value -is [System.Boolean] }

function Test-AwxStrictIntegerRange {
    param($Value,[long]$Minimum,[long]$Maximum)
    $integerTypes=@([byte],[sbyte],[int16],[uint16],[int32],[uint32],[int64],[uint64])
    if ($null -eq $Value -or -not ($integerTypes -contains $Value.GetType())) { return $false }
    try { $number=[decimal]$Value } catch { return $false }
    return $number -ge $Minimum -and $number -le $Maximum
}

function Test-AwxFiniteNumberRange {
    param($Value,[double]$Minimum,[double]$Maximum=[double]::PositiveInfinity)
    if (-not (Test-AwxNumber $Value) -or $Value -is [bool]) { return $false }
    try { $number=[double]$Value } catch { return $false }
    return -not [double]::IsNaN($number) -and -not [double]::IsInfinity($number) -and $number -ge $Minimum -and $number -le $Maximum
}

function Assert-AwxExactPropertySet {
    param([Parameter(Mandatory)]$Value,[Parameter(Mandatory)][string[]]$Names,[string]$ReasonCode='benchmark-contract-invalid')
    if ($null -eq $Value -or -not (Test-AwxExactSet @($Value.PSObject.Properties.Name) $Names)) { throw $ReasonCode }
}

function Assert-AwxHash64 { param($Value,[string]$ReasonCode='benchmark-contract-invalid');if($Value -isnot [string] -or $Value -cnotmatch '^[a-f0-9]{64}$'){throw $ReasonCode} }

function Get-AwxNearestRank {
    param([Parameter(Mandatory)][double[]]$Values,[Parameter(Mandatory)][double]$Percentile)
    if ($Values.Count -eq 0 -or -not (Test-AwxFiniteNumberRange $Percentile 0.0 1.0) -or $Percentile -eq 0.0 -or @($Values|Where-Object{-not(Test-AwxFiniteNumberRange $_ 0.0)}).Count -gt 0) { throw 'benchmark-contract-invalid' }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile * $sorted.Count) - 1
    return [double]$sorted[$index]
}

function Get-AwxSemanticRate {
    param([Parameter(Mandatory)][object[]]$WarmResults,[Parameter(Mandatory)][string]$CaseId)
    $rows = @($WarmResults | Where-Object { $_.caseId -ceq $CaseId })
    if ($rows.Count -ne 7) { return 0.0 }
    return [double](@($rows | Where-Object semanticPassed).Count / 7.0)
}

function Get-AwxModelMetrics {
    param([Parameter(Mandatory)]$ColdResult,[Parameter(Mandatory)]$WarmResults,[Parameter(Mandatory)]$LoadedFreeMiB)
    $fastIds = @('fast-rewrite-ko','fast-json-extract','fast-instruction')
    $mainIds = @('main-grounded-ko','main-uncertainty-ko','main-policy-ko')
    if($WarmResults -isnot [System.Array] -or -not(Test-AwxFiniteNumberRange $LoadedFreeMiB 0.0) -or -not(Test-AwxFiniteNumberRange $ColdResult.latencyMs 0.0)){throw 'benchmark-contract-invalid'}
    $actualIds = @($WarmResults.caseId)
    $fastOnly = $WarmResults.Count -eq 21 -and @($actualIds | Where-Object {$_ -cnotin $fastIds}).Count -eq 0
    $mainOnly = $WarmResults.Count -eq 21 -and @($actualIds | Where-Object {$_ -cnotin $mainIds}).Count -eq 0
    if ($fastOnly -eq $mainOnly) { throw 'warm-sample-insufficient' }
    $roleIds = $(if ($fastOnly) {$fastIds} else {$mainIds})
    foreach($id in $roleIds){
        $rows=@($WarmResults|Where-Object caseId -CEQ $id)
        if($rows.Count -ne 7 -or @($rows|Where-Object{-not(Test-AwxStrictIntegerRange $_.repetition 1 7)}).Count -gt 0 -or -not(Test-AwxExactSequence @($rows.repetition|Sort-Object) @(1..7))){throw 'warm-sample-insufficient'}
    }
    foreach($row in $WarmResults){if(-not(Test-AwxFiniteNumberRange $row.latencyMs 0.0)){throw 'benchmark-contract-invalid'};foreach($name in @('transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed')){if(-not(Test-AwxStrictBoolean $row.$name)){throw 'benchmark-contract-invalid'}}}
    $rates = [ordered]@{}
    foreach ($id in @('fast-rewrite-ko','fast-json-extract','fast-instruction','main-grounded-ko','main-uncertainty-ko','main-policy-ko')) {
        $rates[$id] = Get-AwxSemanticRate -WarmResults $WarmResults -CaseId $id
    }
    $warmLatencies = @($WarmResults | ForEach-Object { [double]$_.latencyMs })
    $complete = @($WarmResults | Where-Object { $_.transportPassed }).Count
    $caseP50 = [ordered]@{}
    foreach ($id in $roleIds) {
        $latencies = @($WarmResults | Where-Object { $_.caseId -ceq $id } | ForEach-Object { [double]$_.latencyMs })
        $caseP50[$id] = Get-AwxNearestRank -Values $latencies -Percentile 0.50
    }
    return [pscustomobject][ordered]@{
        coldLoadMs = [double]$ColdResult.latencyMs
        warmLatenciesMs = $warmLatencies
        warmRoleP95Ms = $(if ($warmLatencies.Count -eq 21) { Get-AwxNearestRank -Values $warmLatencies -Percentile 0.95 } else { $null })
        warmCaseP50Ms = [pscustomobject]$caseP50
        completedWarmCount = $complete
        runtimeStability = [Math]::Min(1.0,[Math]::Max(0.0,[double]($complete / 21.0)))
        loadedFreeMiB = $LoadedFreeMiB
        semanticRates = [pscustomobject]$rates
        rewriteAndExtractionQuality = [double](($rates['fast-rewrite-ko'] + $rates['fast-json-extract']) / 2.0)
        toolAndStructuredOutput = [double]$rates['fast-json-extract']
        koreanRagQuality = [double]$rates['main-grounded-ko']
        factualityAndCitationDiscipline = [double](($rates['main-grounded-ko'] + $rates['main-uncertainty-ko']) / 2.0)
        instructionFollowing = [double]$(if ($roleIds[0] -ceq 'fast-rewrite-ko') {$rates['fast-instruction']} else {$rates['main-policy-ko']})
        codingAndToolUse = [double]$rates['main-policy-ko']
    }
}

function Test-AwxModelHardGate {
    param([Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role,[Parameter(Mandatory)]$Evidence)
    $properties=@('digestPassed','lanePassed','lineagePassed','hashesComplete','completedColdCount','completedWarmCount','caseSampleCountsPassed','warmJsonSchemaPassedCount','nonEmptyThinkingCount','truncatedCount','gpuResidentRatio','loadedFreeMiB','endpointRestarted','timeoutCount')
    try {
        Assert-AwxExactPropertySet $Evidence $properties
        foreach($name in @('digestPassed','lanePassed','lineagePassed','hashesComplete','caseSampleCountsPassed','endpointRestarted')){if(-not(Test-AwxStrictBoolean $Evidence.$name)){throw 'benchmark-contract-invalid'}}
        if(-not(Test-AwxStrictIntegerRange $Evidence.completedColdCount 0 1) -or -not(Test-AwxStrictIntegerRange $Evidence.completedWarmCount 0 21) -or
           -not(Test-AwxStrictIntegerRange $Evidence.warmJsonSchemaPassedCount 0 21) -or -not(Test-AwxStrictIntegerRange $Evidence.nonEmptyThinkingCount 0 21) -or
           -not(Test-AwxStrictIntegerRange $Evidence.truncatedCount 0 21) -or -not(Test-AwxStrictIntegerRange $Evidence.timeoutCount 0 21) -or
           -not(Test-AwxFiniteNumberRange $Evidence.gpuResidentRatio 0.0 1.0) -or -not(Test-AwxFiniteNumberRange $Evidence.loadedFreeMiB 0.0) -or
           [long]$Evidence.warmJsonSchemaPassedCount -gt [long]$Evidence.completedWarmCount -or [long]$Evidence.nonEmptyThinkingCount -gt [long]$Evidence.completedWarmCount -or
           [long]$Evidence.truncatedCount -gt [long]$Evidence.completedWarmCount -or [long]$Evidence.timeoutCount -gt [long]$Evidence.completedWarmCount){throw 'benchmark-contract-invalid'}
    } catch { return [pscustomobject][ordered]@{passed=$false;reasonCodes=@('benchmark-contract-invalid')} }
    $minFree = $(if ($Role -ceq 'fast') { 1536.0 } else { 2048.0 })
    $reasons = [Collections.Generic.List[string]]::new()
    if (-not $Evidence.digestPassed) { $reasons.Add('candidate-digest-changed') }
    if (-not $Evidence.lanePassed) { $reasons.Add('gpu-lane-evidence-incomplete') }
    if (-not $Evidence.lineagePassed -or -not $Evidence.hashesComplete) { $reasons.Add('lineage-missing') }
    if ([int]$Evidence.completedColdCount -ne 1 -or [int]$Evidence.completedWarmCount -ne 21 -or -not [bool]$Evidence.caseSampleCountsPassed) { $reasons.Add('warm-sample-insufficient') }
    if ([int]$Evidence.warmJsonSchemaPassedCount -ne 21) { $reasons.Add('response-schema-invalid') }
    if ([int]$Evidence.nonEmptyThinkingCount -ne 0) { $reasons.Add('lineage-missing') }
    if ([int]$Evidence.truncatedCount -ne 0) { $reasons.Add('response-truncated') }
    if ([double]$Evidence.gpuResidentRatio -lt 0.99) { $reasons.Add('cpu-offload-detected') }
    if ([double]$Evidence.loadedFreeMiB -lt $minFree) { $reasons.Add('insufficient-vram') }
    if ([bool]$Evidence.endpointRestarted -or [int]$Evidence.timeoutCount -ne 0) { $reasons.Add('warm-sample-insufficient') }
    $policy=@(Get-AwxHardGateReasonPolicy | Where-Object {$_ -cne 'pass' -and $_ -cne 'benchmark-contract-invalid'})
    $unique=@($policy|Where-Object{$reasons -ccontains $_})
    [object[]]$finalReasonCodes = $(if($unique.Count -eq 0){@('pass')}else{@($unique)})
    return [pscustomobject][ordered]@{passed=($unique.Count -eq 0);reasonCodes=$finalReasonCodes}
}

function Get-AwxRoleScore {
    param(
        [Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role,
        [Parameter(Mandatory)]$Metrics,
        [Parameter(Mandatory)]$MinEligibleWarmRoleP95Ms,
        [Parameter(Mandatory)]$MaxEligibleLoadedFreeMiB
    )
    foreach($value in @($Metrics.warmRoleP95Ms,$Metrics.loadedFreeMiB,$MinEligibleWarmRoleP95Ms,$MaxEligibleLoadedFreeMiB)){if(-not(Test-AwxFiniteNumberRange $value 0.0)){throw 'benchmark-contract-invalid'}}
    $semanticNames=$(if($Role -ceq 'fast'){@('rewriteAndExtractionQuality','instructionFollowing','toolAndStructuredOutput','runtimeStability')}else{@('koreanRagQuality','instructionFollowing','codingAndToolUse','factualityAndCitationDiscipline','runtimeStability')})
    foreach($name in $semanticNames){if(-not(Test-AwxFiniteNumberRange $Metrics.$name 0.0 1.0)){throw 'benchmark-contract-invalid'}}
    if ($Metrics.warmRoleP95Ms -le 0 -or $MinEligibleWarmRoleP95Ms -le 0 -or $MaxEligibleLoadedFreeMiB -le 0) { throw 'benchmark-contract-invalid' }
    $latency = [Math]::Min(1.0,[Math]::Max(0.0,$MinEligibleWarmRoleP95Ms / [double]$Metrics.warmRoleP95Ms))
    $vram = [Math]::Min(1.0,[Math]::Max(0.0,[double]$Metrics.loadedFreeMiB / $MaxEligibleLoadedFreeMiB))
    if ($Role -ceq 'fast') {
        $score = 100.0 * (0.30 * $Metrics.rewriteAndExtractionQuality + 0.30 * $latency +
            0.15 * $Metrics.instructionFollowing + 0.10 * $Metrics.toolAndStructuredOutput +
            0.10 * $vram + 0.05 * $Metrics.runtimeStability)
    } else {
        $score = 100.0 * (0.35 * $Metrics.koreanRagQuality + 0.20 * $Metrics.instructionFollowing +
            0.15 * $Metrics.codingAndToolUse + 0.10 * $Metrics.factualityAndCitationDiscipline +
            0.10 * $latency + 0.05 * $vram + 0.05 * $Metrics.runtimeStability)
    }
    $boundedScore=[Math]::Min(100.0,[Math]::Max(0.0,$score))
    $canonicalScore=[Math]::Round([decimal]$boundedScore,6,[MidpointRounding]::ToEven)
    return [pscustomobject]@{balancedScore=[double]$canonicalScore;latencyEfficiency=$latency;vramHeadroom=$vram}
}

function ConvertTo-AwxScoreMicrounits {
    param($Score,[switch]$AllowSigned)
    $minimum=$(if($AllowSigned){-100.0}else{0.0})
    if(-not(Test-AwxFiniteNumberRange -Value $Score -Minimum $minimum -Maximum 100.0)){throw 'benchmark-contract-invalid'}
    try{$decimalScore=[decimal]$Score}catch{throw 'benchmark-contract-invalid'}
    $canonical=[Math]::Round($decimalScore,6,[MidpointRounding]::ToEven)
    if($decimalScore -ne $canonical){throw 'benchmark-contract-invalid'}
    $scaled=[decimal]::Multiply($canonical,[decimal]1000000)
    if($scaled -ne [decimal]::Truncate($scaled) -or $scaled -lt -100000000 -or $scaled -gt 100000000){throw 'benchmark-contract-invalid'}
    return [int64]$scaled
}

function Test-AwxRecommendationMetricSet {
    param($Row,[Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role)
    $qualityName=$(if($Role -ceq 'fast'){'rewriteAndExtractionQuality'}else{'koreanRagQuality'})
    try{[void](ConvertTo-AwxScoreMicrounits -Score $Row.balancedScore)}catch{return $false}
    return (Test-AwxFiniteNumberRange $Row.$qualityName 0.0 1.0) -and
        (Test-AwxFiniteNumberRange $Row.warmRoleP95Ms 0.0) -and [double]$Row.warmRoleP95Ms -gt 0.0
}

function New-AwxBaselineEvidenceIncompleteRecommendation {
    param([Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role)
    return [pscustomobject][ordered]@{role=$Role;status='HOLD';modelTag=$null;scoreDelta=$null;reasonCode='baseline-evidence-incomplete'}
}

function Select-AwxRoleRecommendation {
    param([Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role,[Parameter(Mandatory)][object[]]$ModelRows)
    $rows=@($ModelRows);$roleMatrix=@(Get-AwxFamilyAModelIdentityMatrix|Where-Object role -CEQ $Role)
    if($rows.Count -lt 1 -or $rows.Count -gt 3){throw 'benchmark-contract-invalid'}
    $seen=[Collections.Generic.List[string]]::new()
    foreach($row in $rows){
        Assert-AwxExactPropertySet $row @('classification','modelTag','hardGatePassed','balancedScore','rewriteAndExtractionQuality','koreanRagQuality','warmRoleP95Ms')
        if(-not(Test-AwxStrictBoolean $row.hardGatePassed)){throw 'benchmark-contract-invalid'}
        $identity=@($roleMatrix|Where-Object {$_.modelTag -ceq $row.modelTag})
        if($identity.Count -ne 1 -or $identity[0].classification -cne $row.classification -or $seen -ccontains [string]$row.modelTag){throw 'benchmark-contract-invalid'}
        $seen.Add([string]$row.modelTag)
    }
    $baseline=@($rows|Where-Object classification -CEQ 'baseline')
    if($baseline.Count -ne 1){return New-AwxBaselineEvidenceIncompleteRecommendation -Role $Role}
    $qualityName=$(if($Role -ceq 'fast'){'rewriteAndExtractionQuality'}else{'koreanRagQuality'})
    foreach($row in @($rows|Where-Object classification -CNE 'baseline')){
        if($row.hardGatePassed){
            if(-not(Test-AwxRecommendationMetricSet -Row $row -Role $Role)){throw 'benchmark-contract-invalid'}
        } else {
            if($null -ne $row.balancedScore){[void](ConvertTo-AwxScoreMicrounits -Score $row.balancedScore)}
            if(($null -ne $row.$qualityName -and -not(Test-AwxFiniteNumberRange $row.$qualityName 0.0 1.0)) -or
               ($null -ne $row.warmRoleP95Ms -and (-not(Test-AwxFiniteNumberRange $row.warmRoleP95Ms 0.0) -or [double]$row.warmRoleP95Ms -le 0.0))){throw 'benchmark-contract-invalid'}
        }
    }
    if(-not $baseline[0].hardGatePassed -or -not(Test-AwxRecommendationMetricSet -Row $baseline[0] -Role $Role)){
        return New-AwxBaselineEvidenceIncompleteRecommendation -Role $Role
    }
    $baselineScoreMicros=ConvertTo-AwxScoreMicrounits -Score $baseline[0].balancedScore
    $eligible = foreach ($row in @($rows | Where-Object { $_.classification -cne 'baseline' -and $_.hardGatePassed })) {
        $scoreMicros=ConvertTo-AwxScoreMicrounits -Score $row.balancedScore;$deltaMicros=$scoreMicros-$baselineScoreMicros
        $passes = if ($Role -ceq 'fast') {
            $scoreMicros -ge $baselineScoreMicros -and
            [double]$row.rewriteAndExtractionQuality -ge [double]$baseline[0].rewriteAndExtractionQuality -and
            [double]$row.warmRoleP95Ms -le [double]$baseline[0].warmRoleP95Ms
        } else {
            $deltaMicros -ge 5000000 -and
            [double]$row.koreanRagQuality -ge [double]$baseline[0].koreanRagQuality -and
            [double]$row.warmRoleP95Ms -le (1.5 * [double]$baseline[0].warmRoleP95Ms)
        }
        if ($passes -and $deltaMicros -gt 0) {[pscustomobject]@{row=$row;scoreMicros=$scoreMicros;deltaMicros=$deltaMicros}}
    }
    $ordered = @($eligible | Sort-Object @{Expression={$_.scoreMicros};Descending=$true},@{Expression={$_.row.warmRoleP95Ms};Descending=$false},@{Expression={$_.row.modelTag};Descending=$false})
    if ($ordered.Count -eq 0) { return [pscustomobject][ordered]@{role=$Role;status='keep-baseline';modelTag=$baseline[0].modelTag;scoreDelta=0.0;reasonCode='no-eligible-candidate'} }
    if ($ordered.Count -gt 1 -and [int64]$ordered[0].scoreMicros -eq [int64]$ordered[1].scoreMicros -and
        [double]$ordered[0].row.warmRoleP95Ms -eq [double]$ordered[1].row.warmRoleP95Ms) {
        return [pscustomobject][ordered]@{role=$Role;status='keep-baseline';modelTag=$baseline[0].modelTag;scoreDelta=0.0;reasonCode='candidate-tie'}
    }
    return [pscustomobject][ordered]@{role=$Role;status='recommend-candidate';modelTag=$ordered[0].row.modelTag;scoreDelta=[double]([int64]$ordered[0].deltaMicros/1000000.0);reasonCode='promotion-approval-required'}
}

function Get-AwxFamilyAModelIdentityMatrix {
    return @(
        [pscustomobject][ordered]@{role='fast';classification='baseline';modelTag='qwen3:8b';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'},
        [pscustomobject][ordered]@{role='fast';classification='primary';modelTag='qwen3.5:9b';digest='6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7'},
        [pscustomobject][ordered]@{role='fast';classification='challenger';modelTag='gemma4:12b';digest='4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c'},
        [pscustomobject][ordered]@{role='main';classification='baseline';modelTag='gemma4:26b';digest='5571076f3d70050487b26b341705799e0ab29b808164f90d20d4cf84f699d251'},
        [pscustomobject][ordered]@{role='main';classification='primary';modelTag='qwen3.6:27b';digest='a50eda8ed977ab48a12431878896b27ffd5cef552c17af3317d9623b939a7f1e'},
        [pscustomobject][ordered]@{role='main';classification='challenger';modelTag='gemma4:31b';digest='6316f0629137b426c9d9b853ffc4c8209589f30ee39aebede6285096c0ff47e7'}
    )
}

function Get-AwxReasonPolicy {
    param([Parameter(Mandatory)][string]$Lane)
    switch($Lane){
        'SUPPORT_CONTRACT'{@('pass','benchmark-contract-invalid','const-forbidden','single-valued-enum','ontology-narrowed','answer-cardinality-leaked','request-body-oracle-contamination','candidate-digest-changed','lineage-missing')}
        'SUPPORT_SCENARIO'{@('pass','baseline-evidence-incomplete','warm-sample-insufficient','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated','lineage-missing','cpu-offload-detected','insufficient-vram','portfolio-timeout','model-block-timeout','model-unload-unproven','gpu-lane-evidence-incomplete','gpu-release-unproven','ps-evidence-incomplete','native-probe-timeout','concurrent-model-block-detected')}
        'FALSIFY'{@('pass','benchmark-contract-invalid')}
        'COMMAND'{@('pass','command-evidence-incomplete','candidate-digest-changed','warm-sample-insufficient','model-unload-unproven','report-privacy-invalid','secret-scan-failed','role-binding-mutated')}
        default{throw 'benchmark-contract-invalid'}
    }
}

function Get-AwxHardGateReasonPolicy {
    @('pass','benchmark-contract-invalid','candidate-digest-changed','warm-sample-insufficient','response-schema-invalid','response-truncated','lineage-missing','gpu-lane-evidence-incomplete','cpu-offload-detected','insufficient-vram')
}

function Assert-AwxHardGateReasonEnvelope {
    param([bool]$Passed,$ReasonCodes)
    if($ReasonCodes -isnot [System.Array]){throw 'benchmark-contract-invalid'}
    $codes=@($ReasonCodes);$policy=@(Get-AwxHardGateReasonPolicy);$ordered=@($policy|Where-Object{$codes -ccontains $_})
    if($codes.Count -eq 0 -or @($codes|Select-Object -Unique).Count -ne $codes.Count -or @($codes|Where-Object{$_ -isnot [string] -or $_ -cnotin $policy}).Count -gt 0 -or -not(Test-AwxExactSequence $codes $ordered) -or ($Passed -and -not(Test-AwxExactSequence $codes @('pass'))) -or (-not $Passed -and $codes -ccontains 'pass')){throw 'benchmark-contract-invalid'}
}

function Assert-AwxReasonEnvelope {
    param([string]$Lane,[bool]$Complete,[bool]$Passed,$ReasonCodes)
    if($ReasonCodes -isnot [System.Array]){throw 'benchmark-contract-invalid'}
    $codes=@($ReasonCodes);$policy=@(Get-AwxReasonPolicy $Lane)
    if($codes.Count -eq 0 -or @($codes|Select-Object -Unique).Count -ne $codes.Count -or @($codes|Where-Object{$_ -isnot [string] -or $_ -cnotin $policy -or $_ -match '(?i)(?:sk|pk)-[A-Za-z0-9_-]{8,}|authorization|cookie|credential'}).Count -gt 0){throw 'benchmark-contract-invalid'}
    $ordered=@($policy|Where-Object{$codes -ccontains $_});if(-not(Test-AwxExactSequence $codes $ordered)){throw 'benchmark-contract-invalid'}
    if($Passed -and -not(Test-AwxExactSequence $codes @('pass'))){throw 'benchmark-contract-invalid'}
    if(-not $Passed -and $codes -ccontains 'pass'){throw 'benchmark-contract-invalid'}
    if(-not $Complete -and $Passed){throw 'benchmark-contract-invalid'}
}

function Assert-AwxResultSample {
    param($Sample,[string[]]$AllowedCaseIds,[bool]$Warm)
    $names=@('caseId','requestSha256','responseSha256','latencyMs','transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed','reasonCode');if($Warm){$names=@('caseId','repetition','requestSha256','responseSha256','latencyMs','transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed','reasonCode')}
    Assert-AwxExactPropertySet $Sample $names
    if($Sample.caseId -cnotin $AllowedCaseIds -or -not(Test-AwxFiniteNumberRange $Sample.latencyMs 0.0)){throw 'benchmark-contract-invalid'}
    if($Warm -and -not(Test-AwxStrictIntegerRange $Sample.repetition 1 7)){throw 'benchmark-contract-invalid'}
    Assert-AwxHash64 $Sample.requestSha256;Assert-AwxHash64 $Sample.responseSha256
    foreach($name in @('transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed')){if(-not(Test-AwxStrictBoolean $Sample.$name)){throw 'benchmark-contract-invalid'}}
    if($Sample.reasonCode -isnot [string] -or $Sample.reasonCode -cnotin @('pass','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated','lineage-missing','model-block-timeout','portfolio-timeout')){throw 'benchmark-contract-invalid'}
}

function New-AwxBlockEvidence {
    param([Parameter(Mandatory)]$BlockFacts)
    Assert-AwxExactPropertySet $BlockFacts @('modelTag','role','classification','cold','warm','lane','cleanup')
    $matrix=@(Get-AwxFamilyAModelIdentityMatrix);$match=@($matrix|Where-Object modelTag -CEQ $BlockFacts.modelTag)
    if($match.Count -ne 1 -or $BlockFacts.role -cne $match[0].role -or $BlockFacts.classification -cne $match[0].classification){throw 'benchmark-contract-invalid'}
    $caseIds=$(if($BlockFacts.role -ceq 'fast'){@('fast-rewrite-ko','fast-json-extract','fast-instruction')}else{@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
    Assert-AwxResultSample $BlockFacts.cold $caseIds $false;if($BlockFacts.cold.caseId -cne $caseIds[0]){throw 'benchmark-contract-invalid'}
    if($BlockFacts.warm -isnot [System.Array] -or @($BlockFacts.warm).Count -ne 21){throw 'benchmark-contract-invalid'}
    foreach($sample in @($BlockFacts.warm)){Assert-AwxResultSample $sample $caseIds $true}
    foreach($id in $caseIds){$rows=@($BlockFacts.warm|Where-Object caseId -CEQ $id);if($rows.Count -ne 7 -or -not(Test-AwxExactSequence @($rows.repetition|Sort-Object) @(1..7))){throw 'benchmark-contract-invalid'}}
    Assert-AwxExactPropertySet $BlockFacts.lane @('gpuResidentRatio','loadedFreeMiB','lanePassed');if(-not(Test-AwxFiniteNumberRange $BlockFacts.lane.gpuResidentRatio 0.0 1.0) -or -not(Test-AwxFiniteNumberRange $BlockFacts.lane.loadedFreeMiB 0.0) -or -not(Test-AwxStrictBoolean $BlockFacts.lane.lanePassed)){throw 'benchmark-contract-invalid'}
    Assert-AwxExactPropertySet $BlockFacts.cleanup @('psEmpty','gpuReleased','endpointRestarted');foreach($name in @('psEmpty','gpuReleased','endpointRestarted')){if(-not(Test-AwxStrictBoolean $BlockFacts.cleanup.$name)){throw 'benchmark-contract-invalid'}}
    $preimage=[ordered]@{hashContract='awx.desktop-ollama-model-block-evidence.v2';blockFacts=$BlockFacts}
    return [pscustomobject][ordered]@{blockFacts=$BlockFacts;blockEvidenceSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $preimage)}
}

function New-AwxRunBinding {
    param([string]$BenchmarkId,[string]$CorpusSha256,[string]$OracleSha256,[string]$OntologySha256,[string]$SchemaSha256,[string]$OptionsSha256,[object[]]$ModelBlocks)
    if($BenchmarkId -cne 'awx.desktop-ollama-benchmark.v2'){throw 'benchmark-contract-invalid'}
    foreach($hash in @($CorpusSha256,$OracleSha256,$OntologySha256,$SchemaSha256,$OptionsSha256)){Assert-AwxHash64 $hash}
    $matrix=@(Get-AwxFamilyAModelIdentityMatrix)
    if($ModelBlocks.Count -ne 6 -or -not(Test-AwxExactSequence @($ModelBlocks.modelTag) @($matrix.modelTag))){throw 'benchmark-contract-invalid'}
    for($i=0;$i -lt 6;$i++){Assert-AwxExactPropertySet $ModelBlocks[$i] @('modelTag','digest','blockEvidenceSha256');if($ModelBlocks[$i].digest -cne $matrix[$i].digest){throw 'benchmark-contract-invalid'};Assert-AwxHash64 $ModelBlocks[$i].blockEvidenceSha256}
    $binding=[pscustomobject][ordered]@{benchmarkId=$BenchmarkId;corpusSha256=$CorpusSha256;oracleSha256=$OracleSha256;ontologySha256=$OntologySha256;schemaSha256=$SchemaSha256;optionsSha256=$OptionsSha256;modelBlocks=@($ModelBlocks)}
    $hash=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-run-binding.v2';runBinding=$binding}))
    return [pscustomobject][ordered]@{runBinding=$binding;runBindingSha256=$hash}
}

function Assert-AwxRunBindingEnvelope {
    param($Envelope)
    Assert-AwxExactPropertySet $Envelope @('runBinding','runBindingSha256');Assert-AwxHash64 $Envelope.runBindingSha256
    Assert-AwxExactPropertySet $Envelope.runBinding @('benchmarkId','corpusSha256','oracleSha256','ontologySha256','schemaSha256','optionsSha256','modelBlocks')
    $replayed=New-AwxRunBinding -BenchmarkId $Envelope.runBinding.benchmarkId -CorpusSha256 $Envelope.runBinding.corpusSha256 -OracleSha256 $Envelope.runBinding.oracleSha256 -OntologySha256 $Envelope.runBinding.ontologySha256 -SchemaSha256 $Envelope.runBinding.schemaSha256 -OptionsSha256 $Envelope.runBinding.optionsSha256 -ModelBlocks @($Envelope.runBinding.modelBlocks)
    if($replayed.runBindingSha256 -cne $Envelope.runBindingSha256){throw 'benchmark-contract-invalid'}
}

function Assert-AwxFixedChecks {
    param($Checks)
    $matrix=@(Get-AwxFamilyAModelIdentityMatrix);if(@($Checks).Count -ne 6 -or -not(Test-AwxExactSequence @($Checks.modelTag) @($matrix.modelTag))){throw 'benchmark-contract-invalid'}
    foreach($check in @($Checks)){Assert-AwxExactPropertySet $check @('modelTag','passed');if(-not(Test-AwxStrictBoolean $check.passed)){throw 'benchmark-contract-invalid'}}
}

function Get-AwxScenarioSelectorRows {
    param([Parameter(Mandatory)][object[]]$Models,[Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role)
    $fixed=@(Get-AwxFamilyAModelIdentityMatrix|Where-Object role -CEQ $Role);$rows=@($Models|Where-Object role -CEQ $Role)
    if($rows.Count -ne $fixed.Count -or -not(Test-AwxExactSequence @($rows.modelTag) @($fixed.modelTag))){throw 'benchmark-contract-invalid'}
    return @($rows|ForEach-Object {
        $row=$_;$rewriteQuality=$null;$koreanQuality=$null
        if($Role -ceq 'fast'){
            $rewrite=@($row.semanticRates|Where-Object caseId -CEQ 'fast-rewrite-ko');$extract=@($row.semanticRates|Where-Object caseId -CEQ 'fast-json-extract')
            if($rewrite.Count -ne 1 -or $extract.Count -ne 1){throw 'benchmark-contract-invalid'}
            $rewriteQuality=[double](($rewrite[0].rate+$extract[0].rate)/2.0)
        } else {
            $grounded=@($row.semanticRates|Where-Object caseId -CEQ 'main-grounded-ko')
            if($grounded.Count -ne 1){throw 'benchmark-contract-invalid'}
            $koreanQuality=[double]$grounded[0].rate
        }
        [pscustomobject][ordered]@{classification=$row.classification;modelTag=$row.modelTag;hardGatePassed=$row.hardGatePassed;balancedScore=$row.balancedScore;rewriteAndExtractionQuality=$rewriteQuality;koreanRagQuality=$koreanQuality;warmRoleP95Ms=$row.warmRoleP95Ms}
    })
}

function Assert-AwxRecommendationMatch {
    param([Parameter(Mandatory)]$Actual,[Parameter(Mandatory)]$Expected)
    $names=@('role','status','modelTag','scoreDelta','reasonCode');Assert-AwxExactPropertySet $Actual $names;Assert-AwxExactPropertySet $Expected $names
    $actualProjection=[ordered]@{};$expectedProjection=[ordered]@{}
    foreach($name in $names){$actualProjection[$name]=$Actual.$name;$expectedProjection[$name]=$Expected.$name}
    if((ConvertTo-AwxCanonicalJson $actualProjection) -cne (ConvertTo-AwxCanonicalJson $expectedProjection)){throw 'benchmark-contract-invalid'}
}

function Assert-AwxLaneEvidence {
    param([string]$Name,$Evidence)
    $matrix=@(Get-AwxFamilyAModelIdentityMatrix)
    if($Name -ceq 'SUPPORT_CONTRACT'){
        Assert-AwxExactPropertySet $Evidence @('contractValidated','digestChecks','hashCompletenessChecks','lineageChecks');if(-not(Test-AwxStrictBoolean $Evidence.contractValidated)){throw 'benchmark-contract-invalid'}
        Assert-AwxFixedChecks $Evidence.digestChecks;Assert-AwxFixedChecks $Evidence.hashCompletenessChecks;Assert-AwxFixedChecks $Evidence.lineageChecks;return
    }
    if($Name -ceq 'FALSIFY'){
        $names=@('constRejected','shapeOnlyRejected','extraValueRejected','truncationRejected','timeoutRejected','missingHashRejected','orderRejected','oracleIsolated','p95Correct');Assert-AwxExactPropertySet $Evidence $names
        foreach($name in $names){if(-not(Test-AwxStrictBoolean $Evidence.$name)){throw 'benchmark-contract-invalid'}};return
    }
    Assert-AwxExactPropertySet $Evidence @('models','recommendations')
    if(@($Evidence.models).Count -ne 6 -or -not(Test-AwxExactSequence @($Evidence.models.modelTag) @($matrix.modelTag))){throw 'benchmark-contract-invalid'}
    for($i=0;$i -lt 6;$i++){
        $row=$Evidence.models[$i];Assert-AwxExactPropertySet $row @('role','classification','modelTag','hardGatePassed','hardGateReasonCodes','warmRoleP95Ms','loadedFreeMiB','balancedScore','semanticRates')
        if($row.role -cne $matrix[$i].role -or $row.classification -cne $matrix[$i].classification -or $row.modelTag -cne $matrix[$i].modelTag -or -not(Test-AwxStrictBoolean $row.hardGatePassed) -or -not(Test-AwxFiniteNumberRange $row.warmRoleP95Ms 0.0) -or -not(Test-AwxFiniteNumberRange $row.loadedFreeMiB 0.0) -or ($null -ne $row.balancedScore -and -not(Test-AwxFiniteNumberRange $row.balancedScore 0.0 100.0))){throw 'benchmark-contract-invalid'}
        Assert-AwxHardGateReasonEnvelope $row.hardGatePassed $row.hardGateReasonCodes
        $ids=$(if($row.role -ceq 'fast'){@('fast-rewrite-ko','fast-json-extract','fast-instruction')}else{@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
        if(@($row.semanticRates).Count -ne 3 -or -not(Test-AwxExactSequence @($row.semanticRates.caseId) $ids)){throw 'benchmark-contract-invalid'}
        foreach($rate in @($row.semanticRates)){Assert-AwxExactPropertySet $rate @('caseId','rate');if(-not(Test-AwxFiniteNumberRange $rate.rate 0.0 1.0)){throw 'benchmark-contract-invalid'}}
    }
    if(@($Evidence.recommendations).Count -ne 2 -or -not(Test-AwxExactSequence @($Evidence.recommendations.role) @('fast','main'))){throw 'benchmark-contract-invalid'}
    foreach($rec in @($Evidence.recommendations)){Assert-AwxExactPropertySet $rec @('role','status','modelTag','scoreDelta','reasonCode');if($rec.status -cnotin @('keep-baseline','recommend-candidate','HOLD') -or ($null -ne $rec.modelTag -and $rec.modelTag -cnotin @($matrix.modelTag)) -or ($null -ne $rec.scoreDelta -and -not(Test-AwxFiniteNumberRange $rec.scoreDelta ([double]::NegativeInfinity))) -or $rec.reasonCode -cnotin @('baseline-evidence-incomplete','no-eligible-candidate','candidate-tie','promotion-approval-required')){throw 'benchmark-contract-invalid'}}
    foreach($role in @('fast','main')){
        $selectorRows=@(Get-AwxScenarioSelectorRows -Models @($Evidence.models) -Role $role);$derived=Select-AwxRoleRecommendation -Role $role -ModelRows $selectorRows
        $actual=@($Evidence.recommendations|Where-Object role -CEQ $role);if($actual.Count -ne 1){throw 'benchmark-contract-invalid'}
        Assert-AwxRecommendationMatch -Actual $actual[0] -Expected $derived
    }
}

function Get-AwxLaneStatus {
    param([string]$Name,$Evidence)
    Assert-AwxLaneEvidence $Name $Evidence;$reasons=[Collections.Generic.List[string]]::new()
    if($Name -ceq 'SUPPORT_CONTRACT'){
        if(-not $Evidence.contractValidated){$reasons.Add('benchmark-contract-invalid')};if(@($Evidence.digestChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('candidate-digest-changed')};if(@($Evidence.hashCompletenessChecks|Where-Object{-not $_.passed}).Count -or @($Evidence.lineageChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('lineage-missing')}
    }elseif($Name -ceq 'FALSIFY'){if(@($Evidence.PSObject.Properties.Value|Where-Object{-not $_}).Count){$reasons.Add('benchmark-contract-invalid')}}else{
        foreach($row in @($Evidence.models|Where-Object{-not $_.hardGatePassed})){foreach($reason in @($row.hardGateReasonCodes)){if($reason -cin @('benchmark-contract-invalid','candidate-digest-changed')){$reasons.Add('baseline-evidence-incomplete')}elseif($reason -cin (Get-AwxReasonPolicy 'SUPPORT_SCENARIO')){$reasons.Add($reason)}else{throw 'benchmark-contract-invalid'}}}
        if(@($Evidence.recommendations|Where-Object status -CEQ 'HOLD').Count){$reasons.Add('baseline-evidence-incomplete')}
    }
    $policy=@(Get-AwxReasonPolicy $Name);$ordered=@($policy|Where-Object{$reasons -ccontains $_});$passed=$ordered.Count -eq 0
    return [pscustomobject][ordered]@{complete=$true;passed=$passed;reasonCodes=$(if($passed){@('pass')}else{$ordered})}
}

function New-AwxEvidencePackets {
    param([Parameter(Mandatory)]$RunBinding,[Parameter(Mandatory)]$ContractEvidence,[Parameter(Mandatory)]$ScenarioEvidence,[Parameter(Mandatory)]$FalsifyEvidence)
    Assert-AwxRunBindingEnvelope $RunBinding;$definitions=[ordered]@{SUPPORT_CONTRACT=$ContractEvidence;SUPPORT_SCENARIO=$ScenarioEvidence;FALSIFY=$FalsifyEvidence}
    foreach($name in $definitions.Keys){
        $evidence=$definitions[$name];$status=Get-AwxLaneStatus $name $evidence
        $evidenceHash=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-lane-evidence.v2';runBindingSha256=$RunBinding.runBindingSha256;name=$name;evidence=$evidence}))
        $packetHash=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-packet.v2';runBindingSha256=$RunBinding.runBindingSha256;name=$name;complete=$status.complete;passed=$status.passed;reasonCodes=@($status.reasonCodes);evidenceSha256=$evidenceHash}))
        [pscustomobject][ordered]@{name=$name;complete=$status.complete;passed=$status.passed;reasonCodes=@($status.reasonCodes);runBindingSha256=$RunBinding.runBindingSha256;evidence=$evidence;evidenceSha256=$evidenceHash;packetSha256=$packetHash}
    }
}

function New-AwxCommandEvidence {
    param([Parameter(Mandatory)]$RunBinding,[Parameter(Mandatory)]$Evidence)
    Assert-AwxRunBindingEnvelope $RunBinding;Assert-AwxExactPropertySet $Evidence @('digestChecks','completedBlockChecks','finalEmptyPsChecks','privacyProjectionChecked','privacyProjectionPassed','bindingGuardChecked','bindingGuardUnchanged')
    Assert-AwxFixedChecks $Evidence.digestChecks;Assert-AwxFixedChecks $Evidence.completedBlockChecks
    if(@($Evidence.finalEmptyPsChecks).Count -ne 2 -or -not(Test-AwxExactSequence @($Evidence.finalEmptyPsChecks.endpointLabel) @('fast-11435','primary-11434'))){throw 'benchmark-contract-invalid'}
    foreach($check in @($Evidence.finalEmptyPsChecks)){Assert-AwxExactPropertySet $check @('endpointLabel','passed');if(-not(Test-AwxStrictBoolean $check.passed)){throw 'benchmark-contract-invalid'}}
    foreach($name in @('privacyProjectionChecked','privacyProjectionPassed','bindingGuardChecked','bindingGuardUnchanged')){if(-not(Test-AwxStrictBoolean $Evidence.$name)){throw 'benchmark-contract-invalid'}}
    $reasons=[Collections.Generic.List[string]]::new();$complete=[bool]($Evidence.privacyProjectionChecked -and $Evidence.bindingGuardChecked)
    if(-not $complete){$reasons.Add('command-evidence-incomplete')};if(@($Evidence.digestChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('candidate-digest-changed')};if(@($Evidence.completedBlockChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('warm-sample-insufficient')};if(@($Evidence.finalEmptyPsChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('model-unload-unproven')};if($Evidence.privacyProjectionChecked -and -not $Evidence.privacyProjectionPassed){$reasons.Add('report-privacy-invalid')};if($Evidence.bindingGuardChecked -and -not $Evidence.bindingGuardUnchanged){$reasons.Add('role-binding-mutated')}
    $policy=@(Get-AwxReasonPolicy 'COMMAND');$ordered=@($policy|Where-Object{$reasons -ccontains $_});$passed=$complete -and $ordered.Count -eq 0;$codes=$(if($passed){@('pass')}else{$ordered})
    $hash=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-command-evidence.v2';runBindingSha256=$RunBinding.runBindingSha256;complete=$complete;passed=$passed;reasonCodes=@($codes);evidence=$Evidence}))
    return [pscustomobject][ordered]@{complete=$complete;passed=$passed;reasonCodes=@($codes);runBinding=$RunBinding.runBinding;runBindingSha256=$RunBinding.runBindingSha256;evidence=$Evidence;commandEvidenceSha256=$hash}
}

function Assert-AwxPacketEnvelope {
    param($Packet)
    Assert-AwxExactPropertySet $Packet @('name','complete','passed','reasonCodes','runBindingSha256','evidence','evidenceSha256','packetSha256');if($Packet.name -cnotin @('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY') -or -not(Test-AwxStrictBoolean $Packet.complete) -or -not(Test-AwxStrictBoolean $Packet.passed)){throw 'benchmark-contract-invalid'}
    Assert-AwxHash64 $Packet.runBindingSha256;Assert-AwxHash64 $Packet.evidenceSha256;Assert-AwxHash64 $Packet.packetSha256
    $status=Get-AwxLaneStatus $Packet.name $Packet.evidence;if($Packet.complete -ne $status.complete -or $Packet.passed -ne $status.passed -or -not(Test-AwxExactSequence @($Packet.reasonCodes) @($status.reasonCodes))){throw 'benchmark-contract-invalid'};Assert-AwxReasonEnvelope $Packet.name $Packet.complete $Packet.passed $Packet.reasonCodes
    $eh=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-lane-evidence.v2';runBindingSha256=$Packet.runBindingSha256;name=$Packet.name;evidence=$Packet.evidence}));if($eh -cne $Packet.evidenceSha256){throw 'benchmark-contract-invalid'}
    $ph=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-packet.v2';runBindingSha256=$Packet.runBindingSha256;name=$Packet.name;complete=$Packet.complete;passed=$Packet.passed;reasonCodes=@($Packet.reasonCodes);evidenceSha256=$Packet.evidenceSha256}));if($ph -cne $Packet.packetSha256){throw 'benchmark-contract-invalid'}
}

function Assert-AwxCommandEnvelope {
    param($Command)
    Assert-AwxExactPropertySet $Command @('complete','passed','reasonCodes','runBinding','runBindingSha256','evidence','commandEvidenceSha256');if(-not(Test-AwxStrictBoolean $Command.complete) -or -not(Test-AwxStrictBoolean $Command.passed)){throw 'benchmark-contract-invalid'};Assert-AwxHash64 $Command.commandEvidenceSha256
    $binding=[pscustomobject][ordered]@{runBinding=$Command.runBinding;runBindingSha256=$Command.runBindingSha256};Assert-AwxRunBindingEnvelope $binding
    $replayed=New-AwxCommandEvidence -RunBinding $binding -Evidence $Command.evidence
    if($replayed.complete -ne $Command.complete -or $replayed.passed -ne $Command.passed -or -not(Test-AwxExactSequence @($replayed.reasonCodes) @($Command.reasonCodes)) -or $replayed.commandEvidenceSha256 -cne $Command.commandEvidenceSha256){throw 'benchmark-contract-invalid'}
    Assert-AwxReasonEnvelope 'COMMAND' $Command.complete $Command.passed $Command.reasonCodes
}

function Get-AwxNeutralVerdict {
    param([Parameter(Mandatory)][object[]]$Packets,[Parameter(Mandatory)]$CommandEvidence)
    try{Assert-AwxCommandEnvelope $CommandEvidence}catch{return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='command-evidence-incomplete'}}
    $required=@('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY');if($Packets.Count -ne 3 -or @($required|Where-Object{@($Packets|Where-Object name -CEQ $_).Count -ne 1}).Count){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='packet-set-invalid'}}
    foreach($packet in $Packets){try{Assert-AwxPacketEnvelope $packet}catch{return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='packet-incomplete'}};if($packet.runBindingSha256 -cne $CommandEvidence.runBindingSha256){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='packet-set-invalid'}}}
    if(-not $CommandEvidence.complete -or -not $CommandEvidence.passed){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='command-evidence-incomplete'}}
    $byName=@{};foreach($packet in $Packets){$byName[$packet.name]=$packet};if(@($Packets|Where-Object{-not $_.complete}).Count){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='packet-incomplete'}}
    if(-not $byName.SUPPORT_CONTRACT.passed -or -not $byName.FALSIFY.passed){return [pscustomobject][ordered]@{verdict='REJECT';reasonCode='contract-or-counterexample-failed'}}
    if(-not $byName.SUPPORT_SCENARIO.passed){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='scenario-evidence-incomplete'}}
    return [pscustomobject][ordered]@{verdict='APPLY';reasonCode='all-packets-pass'}
}

function Resolve-AwxNeutralOrder {
    param($Forward,$Reverse,$RunBindingSha256,$CommandEvidenceSha256)
    $stable=$Forward.verdict -ceq $Reverse.verdict -and $Forward.reasonCode -ceq $Reverse.reasonCode
    return [pscustomobject][ordered]@{forward=$Forward.verdict;forwardReasonCode=$Forward.reasonCode;reverse=$Reverse.verdict;reverseReasonCode=$Reverse.reasonCode;orderStable=$stable;finalVerdict=$(if($stable){$Forward.verdict}else{'HOLD'});finalReasonCode=$(if($stable){$Forward.reasonCode}else{'order-unstable'});runBindingSha256=$RunBindingSha256;commandEvidenceSha256=$CommandEvidenceSha256}
}

function Test-AwxInternalNeutralPair {
    param($Verdict,$ReasonCode)
    if($Verdict -ceq 'APPLY'){return $ReasonCode -ceq 'all-packets-pass'}
    if($Verdict -ceq 'REJECT'){return $ReasonCode -ceq 'contract-or-counterexample-failed'}
    return $Verdict -ceq 'HOLD' -and $ReasonCode -cin @('packet-set-invalid','packet-incomplete','command-evidence-incomplete','scenario-evidence-incomplete')
}

function Assert-AwxNeutralEnvelope {
    param($Neutral)
    Assert-AwxExactPropertySet $Neutral @('forward','forwardReasonCode','reverse','reverseReasonCode','orderStable','finalVerdict','finalReasonCode','runBindingSha256','commandEvidenceSha256')
    if(-not(Test-AwxStrictBoolean $Neutral.orderStable) -or -not(Test-AwxInternalNeutralPair $Neutral.forward $Neutral.forwardReasonCode) -or -not(Test-AwxInternalNeutralPair $Neutral.reverse $Neutral.reverseReasonCode)){throw 'benchmark-contract-invalid'}
    if($Neutral.orderStable){
        if($Neutral.forward -cne $Neutral.reverse -or $Neutral.forwardReasonCode -cne $Neutral.reverseReasonCode -or $Neutral.finalVerdict -cne $Neutral.forward -or $Neutral.finalReasonCode -cne $Neutral.forwardReasonCode){throw 'benchmark-contract-invalid'}
    } elseif($Neutral.finalVerdict -cne 'HOLD' -or $Neutral.finalReasonCode -cne 'order-unstable' -or ($Neutral.forward -ceq $Neutral.reverse -and $Neutral.forwardReasonCode -ceq $Neutral.reverseReasonCode)){throw 'benchmark-contract-invalid'}
    Assert-AwxHash64 $Neutral.runBindingSha256;Assert-AwxHash64 $Neutral.commandEvidenceSha256
}

function Get-AwxOptionalCommandHash {
    param($CommandEvidence,[Parameter(Mandatory)][string]$Name)
    if($null -eq $CommandEvidence){return $null}
    try{
        $property=$CommandEvidence.PSObject.Properties[$Name]
        if($null -ne $property -and $property.Value -is [string] -and $property.Value -cmatch '^[a-f0-9]{64}$'){return [string]$property.Value}
    }catch{}
    return $null
}

function Get-AwxOrderStableNeutralVerdict {
    param([Parameter(Mandatory)][object[]]$Packets,$CommandEvidence)
    $runBindingSha256=Get-AwxOptionalCommandHash -CommandEvidence $CommandEvidence -Name 'runBindingSha256';$commandEvidenceSha256=Get-AwxOptionalCommandHash -CommandEvidence $CommandEvidence -Name 'commandEvidenceSha256'
    try{$forward=Get-AwxNeutralVerdict -Packets @($Packets) -CommandEvidence $CommandEvidence}catch{$forward=[pscustomobject][ordered]@{verdict='HOLD';reasonCode='command-evidence-incomplete'}}
    [object[]]$reversePackets=[object[]]$Packets.Clone();[array]::Reverse($reversePackets)
    try{$reverse=Get-AwxNeutralVerdict -Packets $reversePackets -CommandEvidence $CommandEvidence}catch{$reverse=[pscustomobject][ordered]@{verdict='HOLD';reasonCode='command-evidence-incomplete'}}
    return Resolve-AwxNeutralOrder -Forward $forward -Reverse $reverse -RunBindingSha256 $runBindingSha256 -CommandEvidenceSha256 $commandEvidenceSha256
}

function ConvertTo-AwxPublicPacket {param($Packet);Assert-AwxPacketEnvelope $Packet;[pscustomobject][ordered]@{name=$Packet.name;complete=$Packet.complete;passed=$Packet.passed;reasonCodes=@($Packet.reasonCodes);runBindingSha256=$Packet.runBindingSha256;evidenceSha256=$Packet.evidenceSha256;packetSha256=$Packet.packetSha256}}
function ConvertTo-AwxPublicNeutral {param($Neutral);Assert-AwxNeutralEnvelope $Neutral;[pscustomobject][ordered]@{forward=$Neutral.forward;forwardReasonCode=$Neutral.forwardReasonCode;reverse=$Neutral.reverse;reverseReasonCode=$Neutral.reverseReasonCode;orderStable=$Neutral.orderStable;finalVerdict=$Neutral.finalVerdict;finalReasonCode=$Neutral.finalReasonCode;runBindingSha256=$Neutral.runBindingSha256;commandEvidenceSha256=$Neutral.commandEvidenceSha256}}

function Assert-AwxOllamaHttpRequestPair {
    param(
        [Parameter(Mandatory)][ValidateSet('/api/tags','/api/ps','/api/chat','/api/generate')][string]$Path,
        [Parameter(Mandatory)][ValidateSet('GET','POST')][string]$Method
    )
    $valid = ($Method -ceq 'GET' -and $Path -cin @('/api/tags','/api/ps')) -or
        ($Method -ceq 'POST' -and $Path -cin @('/api/chat','/api/generate'))
    if (-not $valid) { throw 'ollama-http-method-invalid' }
}

function Invoke-AwxOllamaHttp {
    param(
        [Parameter(Mandatory)][string]$Endpoint,
        [Parameter(Mandatory)][ValidateSet('/api/tags','/api/ps','/api/chat','/api/generate')][string]$Path,
        [Parameter(Mandatory)][ValidateSet('GET','POST')][string]$Method,
        [string]$Body,
        [Parameter(Mandatory)][ValidateRange(1,1800)][int]$TimeoutSec
    )
    Assert-AwxLoopbackEndpoint $Endpoint
    Assert-AwxOllamaHttpRequestPair -Path $Path -Method $Method
    $invoke = @{Uri=($Endpoint + $Path);Method=$Method;TimeoutSec=$TimeoutSec;MaximumRedirection=0;ErrorAction='Stop'}
    if ($Method -ceq 'POST') {
        $invoke.ContentType = 'application/json; charset=utf-8'
        $invoke.Body = $script:Utf8NoBom.GetBytes([string]$Body)
    }
    try { return Invoke-RestMethod @invoke }
    catch { throw [InvalidOperationException]::new(('ollama-http-failed:{0}' -f $Path)) }
}

function Get-AwxOllamaTags {
    param([Parameter(Mandatory)][string]$Endpoint,[int]$TimeoutSec=15)
    $response = Invoke-AwxOllamaHttp -Endpoint $Endpoint -Path '/api/tags' -Method GET -TimeoutSec $TimeoutSec
    if ($null -eq $response.PSObject.Properties['models']) { throw 'tags-evidence-incomplete' }
    return @($response.models)
}

function Test-AwxInstalledDigest {
    param([Parameter(Mandatory)][object[]]$Rows,[Parameter(Mandatory)][string]$ModelTag,[Parameter(Mandatory)][string]$ExpectedDigest)
    if ($ExpectedDigest -cnotmatch '^[a-f0-9]{64}$') { return [pscustomobject]@{passed=$false;reasonCode='candidate-digest-changed'} }
    $targetRows = @($Rows | Where-Object {
        $nameProperty = $_.PSObject.Properties['name']; $modelProperty = $_.PSObject.Properties['model']
        $nameValid = $null -ne $nameProperty -and $nameProperty.Value -is [string] -and $nameProperty.Value.Length -gt 0
        $modelValid = $null -ne $modelProperty -and $modelProperty.Value -is [string] -and $modelProperty.Value.Length -gt 0
        ($nameValid -and $nameProperty.Value -ceq $ModelTag) -or ($modelValid -and $modelProperty.Value -ceq $ModelTag)
    })
    if ($targetRows.Count -ne 1) { return [pscustomobject]@{passed=$false;reasonCode='candidate-digest-changed'} }
    $target = $targetRows[0]
    $nameProperty = $target.PSObject.Properties['name']; $modelProperty = $target.PSObject.Properties['model']
    $namePresent = $null -ne $nameProperty; $modelPresent = $null -ne $modelProperty
    $nameValid = $null -ne $nameProperty -and $nameProperty.Value -is [string] -and $nameProperty.Value.Length -gt 0
    $modelValid = $null -ne $modelProperty -and $modelProperty.Value -is [string] -and $modelProperty.Value.Length -gt 0
    if (($namePresent -and -not $nameValid) -or ($modelPresent -and -not $modelValid) -or
        -not($nameValid -or $modelValid) -or ($nameValid -and $modelValid -and $nameProperty.Value -cne $modelProperty.Value) -or
        $null -eq $target.PSObject.Properties['digest'] -or $target.digest -isnot [string] -or
        [string]$target.digest -cnotmatch '^[a-f0-9]{64}$') {
        return [pscustomobject]@{passed=$false;reasonCode='candidate-digest-changed'}
    }
    $passed = [string]$target.digest -ceq $ExpectedDigest
    return [pscustomobject]@{passed=$passed;reasonCode=$(if ($passed) {'pass'} else {'candidate-digest-changed'})}
}

function ConvertFrom-AwxPsResponse {
    param([Parameter(Mandatory)]$Response)
    if ($null -eq $Response -or $null -eq $Response.PSObject.Properties['models'] -or $null -eq $Response.models) { throw 'ps-evidence-incomplete' }
    $rows = foreach ($row in @($Response.models)) {
        if ($null -eq $row) { throw 'ps-evidence-incomplete' }
        $nameProperty = $row.PSObject.Properties['name']; $modelProperty = $row.PSObject.Properties['model']
        $namePresent = $null -ne $nameProperty; $modelPresent = $null -ne $modelProperty
        $nameValid = $null -ne $nameProperty -and $nameProperty.Value -is [string] -and $nameProperty.Value.Length -gt 0
        $modelValid = $null -ne $modelProperty -and $modelProperty.Value -is [string] -and $modelProperty.Value.Length -gt 0
        if (($namePresent -and -not $nameValid) -or ($modelPresent -and -not $modelValid) -or
            -not($nameValid -or $modelValid) -or ($nameValid -and $modelValid -and $nameProperty.Value -cne $modelProperty.Value) -or
            $null -eq $row.PSObject.Properties['size'] -or $null -eq $row.PSObject.Properties['size_vram'] -or
            $null -eq $row.PSObject.Properties['context_length'] -or
            -not(Test-AwxFiniteNumberRange $row.size 0.0) -or [double]$row.size -le 0.0 -or
            -not(Test-AwxFiniteNumberRange $row.size_vram 0.0) -or [double]$row.size_vram -gt [double]$row.size -or
            -not(Test-AwxStrictIntegerRange $row.context_length 1 ([int]::MaxValue))) {
            throw 'ps-evidence-incomplete'
        }
        [pscustomobject]@{modelTag=$(if ($nameValid) {[string]$nameProperty.Value} else {[string]$modelProperty.Value});sizeBytes=[double]$row.size;sizeVramBytes=[double]$row.size_vram;contextLength=[int]$row.context_length}
    }
    return @($rows)
}

function Get-AwxOllamaPs {
    param([Parameter(Mandatory)][string]$Endpoint,[int]$TimeoutSec=15)
    return ConvertFrom-AwxPsResponse (Invoke-AwxOllamaHttp -Endpoint $Endpoint -Path '/api/ps' -Method GET -TimeoutSec $TimeoutSec)
}

function Get-AwxLoadedModelEvidence {
    param([Parameter(Mandatory)][object[]]$Rows,[Parameter(Mandatory)][string]$ModelTag,[Parameter(Mandatory)][int]$ExpectedContext)
    $modelRows = @($Rows | Where-Object { $_.PSObject.Properties['modelTag'] -and $_.modelTag -is [string] -and $_.modelTag -ceq $ModelTag })
    if ($Rows.Count -ne 1 -or $modelRows.Count -ne 1 -or
        $null -eq $modelRows[0].PSObject.Properties['sizeBytes'] -or $null -eq $modelRows[0].PSObject.Properties['sizeVramBytes'] -or
        $null -eq $modelRows[0].PSObject.Properties['contextLength'] -or
        -not(Test-AwxFiniteNumberRange $modelRows[0].sizeBytes 0.0) -or [double]$modelRows[0].sizeBytes -le 0.0 -or
        -not(Test-AwxFiniteNumberRange $modelRows[0].sizeVramBytes 0.0) -or [double]$modelRows[0].sizeVramBytes -gt [double]$modelRows[0].sizeBytes -or
        -not(Test-AwxStrictIntegerRange $modelRows[0].contextLength 1 ([int]::MaxValue)) -or [int]$modelRows[0].contextLength -ne $ExpectedContext) {
        throw 'ps-evidence-incomplete'
    }
    return [pscustomobject]@{modelTag=$ModelTag;contextLength=[int]$modelRows[0].contextLength;sizeVramMiB=[Math]::Round([double]$modelRows[0].sizeVramBytes / 1MB,3);gpuResidentRatio=[Math]::Round([double]$modelRows[0].sizeVramBytes / [double]$modelRows[0].sizeBytes,6)}
}

function Invoke-AwxNativeBounded {
    param([Parameter(Mandatory)][string]$FilePath,[Parameter(Mandatory)][string]$Arguments,[Parameter(Mandatory)][ValidateRange(1,60)][int]$TimeoutSec)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath; $start.Arguments = $Arguments; $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true; $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'native-probe-failed' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync(); $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSec * 1000)) { try {$process.Kill()} catch {}; throw 'native-probe-timeout' }
        $stdout = $stdoutTask.GetAwaiter().GetResult(); [void]$stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'native-probe-failed' }
        return @($stdout -split '\r?\n' | Where-Object { $_.Length -gt 0 })
    } finally { $process.Dispose() }
}

function Get-AwxGpuSnapshot {
    param([int]$TimeoutSec=15)
    $lines = @(Invoke-AwxNativeBounded -FilePath 'nvidia-smi.exe' -Arguments '--query-gpu=name,memory.total,memory.free --format=csv,noheader,nounits' -TimeoutSec $TimeoutSec)
    $rows = foreach ($line in $lines) {
        $parts = @($line -split ',' | ForEach-Object { $_.Trim() })
        if ($parts.Count -ne 3 -or $parts[0] -notmatch '^NVIDIA GeForce RTX (3060|3090)$' -or $parts[1] -notmatch '^\d+$' -or $parts[2] -notmatch '^\d+$') { throw 'gpu-evidence-incomplete' }
        [pscustomobject]@{laneLabel=($parts[0] -replace '^NVIDIA GeForce ','');totalMiB=[int]$parts[1];freeMiB=[int]$parts[2]}
    }
    if ($rows.Count -ne 2 -or @($rows.laneLabel | Select-Object -Unique).Count -ne 2) { throw 'gpu-evidence-incomplete' }
    return @($rows)
}

function Get-AwxEndpointProcessIds {
    param([Parameter(Mandatory)][string]$Endpoint,[int]$TimeoutSec=15)
    Assert-AwxLoopbackEndpoint $Endpoint
    $port = ([uri]$Endpoint).Port
    $listenerPattern = '^\s*TCP\s+(?:127\.0\.0\.1|0\.0\.0\.0|\[::\]|\[::1\]):' + $port + '\s+\S+\s+LISTENING\s+(\d+)\s*$'
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $listeners = @(Invoke-AwxNativeBounded -FilePath 'netstat.exe' -Arguments '-ano -p tcp' -TimeoutSec $TimeoutSec | ForEach-Object {
        if ($_ -match $listenerPattern) { [int]$Matches[1] }
    } | Select-Object -Unique)
    if ($listeners.Count -ne 1) { throw 'gpu-lane-evidence-incomplete' }
    $remaining = [Math]::Floor($TimeoutSec - $clock.Elapsed.TotalSeconds)
    if ($remaining -lt 1) { throw 'native-probe-timeout' }
    $processes = @(Get-CimInstance Win32_Process -OperationTimeoutSec $remaining -ErrorAction Stop | Select-Object ProcessId,ParentProcessId)
    $ids = [Collections.Generic.HashSet[int]]::new(); [void]$ids.Add([int]$listeners[0])
    do {
        $added = $false
        foreach ($process in $processes) {
            if ($ids.Contains([int]$process.ParentProcessId) -and $ids.Add([int]$process.ProcessId)) { $added = $true }
        }
    } while ($added)
    return @($ids | Sort-Object)
}

function Get-AwxGpuComputeRows {
    param([int]$TimeoutSec=15)
    $lines = @(Invoke-AwxNativeBounded -FilePath 'nvidia-smi.exe' -Arguments '--query-compute-apps=pid,gpu_name --format=csv,noheader,nounits' -TimeoutSec $TimeoutSec)
    return @($lines | ForEach-Object {
        $parts = @($_ -split ',' | ForEach-Object {$_.Trim()})
        if ($parts.Count -ne 2 -or $parts[0] -notmatch '^\d+$' -or $parts[1] -notmatch '^NVIDIA GeForce RTX (3060|3090)$') { throw 'gpu-lane-evidence-incomplete' }
        [pscustomobject]@{processId=[int]$parts[0];laneLabel=($parts[1] -replace '^NVIDIA GeForce ','')}
    })
}

function Assert-AwxEndpointProcessIds {
    param([AllowEmptyCollection()][object[]]$Values,[Parameter(Mandatory)][string]$ReasonCode)
    $ids = @($Values)
    if (@($ids | Where-Object {-not(Test-AwxStrictIntegerRange $_ 1 ([int]::MaxValue))}).Count -gt 0 -or
        @($ids | Select-Object -Unique).Count -ne $ids.Count) { throw $ReasonCode }
}

function Assert-AwxComputeEvidenceRows {
    param([AllowEmptyCollection()][object[]]$Rows,[Parameter(Mandatory)][string]$ReasonCode)
    $keys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($row in @($Rows)) {
        if ($null -eq $row -or $null -eq $row.PSObject.Properties['processId'] -or $null -eq $row.PSObject.Properties['laneLabel'] -or
            -not(Test-AwxStrictIntegerRange $row.processId 1 ([int]::MaxValue)) -or $row.laneLabel -isnot [string] -or
            $row.laneLabel -cnotin @('RTX 3060','RTX 3090') -or -not $keys.Add(('{0}|{1}' -f [int]$row.processId,[string]$row.laneLabel))) {
            throw $ReasonCode
        }
    }
}

function Assert-AwxGpuSnapshotRows {
    param([Parameter(Mandatory)][object[]]$Rows)
    $values = @($Rows)
    if ($values.Count -lt 1 -or $values.Count -gt 2) { throw 'gpu-lane-evidence-incomplete' }
    $labels = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($row in $values) {
        try { Assert-AwxExactPropertySet $row @('laneLabel','totalMiB','freeMiB') 'gpu-lane-evidence-incomplete' } catch { throw 'gpu-lane-evidence-incomplete' }
        if ($row.laneLabel -isnot [string] -or $row.laneLabel -cnotin @('RTX 3060','RTX 3090') -or
            -not(Test-AwxStrictIntegerRange $row.totalMiB 1 ([int]::MaxValue)) -or
            -not(Test-AwxStrictIntegerRange $row.freeMiB 0 ([int]::MaxValue)) -or [int64]$row.freeMiB -gt [int64]$row.totalMiB -or
            -not $labels.Add([string]$row.laneLabel)) { throw 'gpu-lane-evidence-incomplete' }
    }
}

function Test-AwxGpuLane {
    param([Parameter(Mandatory)][AllowEmptyCollection()][object[]]$EndpointProcessIds,[Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ComputeRows,[Parameter(Mandatory)][object[]]$Snapshot,[Parameter(Mandatory)][string]$ExpectedLane,[Parameter(Mandatory)][double]$MinimumFreeMiB)
    Assert-AwxEndpointProcessIds -Values @($EndpointProcessIds) -ReasonCode 'gpu-lane-evidence-incomplete'
    Assert-AwxComputeEvidenceRows -Rows @($ComputeRows) -ReasonCode 'gpu-lane-evidence-incomplete'
    Assert-AwxGpuSnapshotRows -Rows @($Snapshot)
    $matches = @($ComputeRows | Where-Object { $_.processId -in $EndpointProcessIds })
    $card = @($Snapshot | Where-Object laneLabel -CEQ $ExpectedLane)
    $passed = $matches.Count -gt 0 -and @($matches | Where-Object laneLabel -CNE $ExpectedLane).Count -eq 0 -and $card.Count -eq 1 -and [double]$card[0].freeMiB -ge $MinimumFreeMiB
    return [pscustomobject]@{passed=$passed;reasonCode=$(if($passed){'pass'}elseif($card.Count -eq 1 -and [double]$card[0].freeMiB -lt $MinimumFreeMiB){'insufficient-vram'}else{'gpu-lane-evidence-incomplete'});laneLabel=$ExpectedLane;totalMiB=$(if($card.Count -eq 1){[int]$card[0].totalMiB}else{0});freeMiB=$(if($card.Count -eq 1){[int]$card[0].freeMiB}else{0})}
}

function Test-AwxGpuRelease {
    param([Parameter(Mandatory)][AllowEmptyCollection()][object[]]$EndpointProcessIds,[Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ComputeRows)
    Assert-AwxEndpointProcessIds -Values @($EndpointProcessIds) -ReasonCode 'gpu-release-unproven'
    Assert-AwxComputeEvidenceRows -Rows @($ComputeRows) -ReasonCode 'gpu-release-unproven'
    $remaining = @($ComputeRows | Where-Object { $_.processId -in $EndpointProcessIds })
    return [pscustomobject]@{passed=($remaining.Count -eq 0);reasonCode=$(if($remaining.Count -eq 0){'pass'}else{'gpu-release-unproven'})}
}

function Stop-AwxOllamaModel {
    param([Parameter(Mandatory)]$Model,[int]$TimeoutSec=30)
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $body = ConvertTo-AwxCanonicalJson ([ordered]@{model=$Model.modelTag;keep_alive=0})
    Invoke-AwxOllamaHttp -Endpoint $Model.endpoint -Path '/api/generate' -Method POST -Body $body -TimeoutSec $TimeoutSec | Out-Null
    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $remaining = [Math]::Floor($TimeoutSec - $clock.Elapsed.TotalSeconds)
        if ($remaining -lt 1) { break }
        if (@(Get-AwxOllamaPs -Endpoint $Model.endpoint -TimeoutSec ([Math]::Min(5,$remaining))).Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    }
    throw 'model-unload-unproven'
}

function Enter-AwxBenchmarkLease {
    param([Parameter(Mandatory)][string]$OutputPath)
    $directory = [IO.Path]::GetFullPath((Split-Path -Parent $OutputPath))
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    $path = Join-Path $directory '.desktop-ollama-benchmark-v2.lock'
    $fileOptions = [IO.FileOptions]([int][IO.FileOptions]::WriteThrough -bor [int][IO.FileOptions]::DeleteOnClose)
    try { $stream = [IO.FileStream]::new($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None,4096,$fileOptions) }
    catch [IO.IOException] { throw 'benchmark-lock-held' }
    $token = [guid]::NewGuid().ToString('N')
    try {
        $bytes = $script:Utf8NoBom.GetBytes((ConvertTo-AwxCanonicalJson ([ordered]@{schemaVersion=2;pid=$PID;token=$token})))
        $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true)
        $lease = [pscustomobject]@{Path=$path;Stream=$stream;Token=$token;Disposed=$false}
        $lease | Add-Member ScriptMethod Dispose {
            if ($this.Disposed) { return }
            $owned = $false
            try {
                if ($null -eq $this.Stream -or -not $this.Stream.CanRead) { throw 'invalid-stream' }
                $this.Stream.Flush($true); $this.Stream.Position = 0
                $length = [int]$this.Stream.Length
                if ($length -lt 1 -or $length -gt 4096) { throw 'invalid-lock-length' }
                $buffer = New-Object byte[] $length; $offset = 0
                while ($offset -lt $length) {
                    $read = $this.Stream.Read($buffer,$offset,$length-$offset)
                    if ($read -le 0) { throw 'short-lock-read' }
                    $offset += $read
                }
                $record = [Text.UTF8Encoding]::new($false,$true).GetString($buffer) | ConvertFrom-Json
                $owned = $null -ne $record.PSObject.Properties['schemaVersion'] -and [int]$record.schemaVersion -eq 2 -and
                    $null -ne $record.PSObject.Properties['pid'] -and [int]$record.pid -eq $PID -and
                    $null -ne $record.PSObject.Properties['token'] -and $record.token -is [string] -and
                    [string]$record.token -ceq [string]$this.Token
            } catch { $owned = $false }
            finally {
                $this.Disposed = $true
                if ($null -ne $this.Stream) { $this.Stream.Dispose(); $this.Stream = $null }
            }
            if (-not $owned) { throw 'benchmark-lock-ownership-lost' }
        }
        return $lease
    } catch {
        try { $stream.Dispose() } catch {}
        throw 'benchmark-lock-initialization-failed'
    }
}

function Write-AwxJsonAtomic {
    param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)]$Value)
    $directory = [IO.Path]::GetFullPath((Split-Path -Parent $Path))
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    $target = [IO.Path]::GetFullPath($Path)
    $leaf = [IO.Path]::GetFileName($target)
    $orphanPattern = '^\.' + [regex]::Escape($leaf) + '\.[a-f0-9]{32}\.(tmp|bak)$'
    try {
        foreach ($orphan in @(Get-ChildItem -LiteralPath $directory -File -Force -ErrorAction Stop | Where-Object Name -CMatch $orphanPattern)) {
            Remove-Item -LiteralPath $orphan.FullName -Force -ErrorAction Stop
        }
        if (@(Get-ChildItem -LiteralPath $directory -File -Force -ErrorAction Stop | Where-Object Name -CMatch $orphanPattern).Count -gt 0) { throw 'orphan-cleanup-failed' }
    } catch { throw 'report-write-failed' }
    $temp = Join-Path $directory ('.' + [IO.Path]::GetFileName($target) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backup = Join-Path $directory ('.' + [IO.Path]::GetFileName($target) + '.' + [guid]::NewGuid().ToString('N') + '.bak')
    $json = ConvertTo-AwxCanonicalJson $Value
    $bytes = $script:Utf8NoBom.GetBytes($json)
    try {
        $stream = [IO.FileStream]::new($temp,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        if ((Get-AwxFileSha256 $temp) -cne (Get-AwxUtf8Sha256 $json)) { throw 'report-temp-hash-mismatch' }
        if (Test-Path -LiteralPath $target -PathType Leaf) { [IO.File]::Replace($temp,$target,$backup,$true); Remove-Item -LiteralPath $backup -Force }
        else { [IO.File]::Move($temp,$target) }
    } catch {
        Remove-Item -LiteralPath $temp,$backup -Force -ErrorAction SilentlyContinue
        throw 'report-write-failed'
    }
}

function Get-AwxBoundedReasonCode {
    param([Parameter(Mandatory)][Exception]$Exception)
    $httpMap = @{
        'ollama-http-failed:/api/tags'='tags-evidence-incomplete'
        'ollama-http-failed:/api/ps'='ps-evidence-incomplete'
        'ollama-http-failed:/api/chat'='benchmark-runtime-failed'
        'ollama-http-failed:/api/generate'='model-unload-unproven'
    }
    $allowed = @(
        'candidate-digest-changed','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated',
        'lineage-missing','cpu-offload-detected','insufficient-vram','warm-sample-insufficient','portfolio-timeout','model-block-timeout',
        'model-unload-unproven','gpu-lane-evidence-incomplete','gpu-release-unproven','ps-evidence-incomplete','tags-evidence-incomplete','native-probe-timeout','role-binding-mutated','concurrent-model-block-detected'
    )
    $message = [string]$Exception.Message
    if ($httpMap.ContainsKey($message)) { return [string]$httpMap[$message] }
    return $(if ($message -cin $allowed) {$message} else {'benchmark-runtime-failed'})
}

function Get-AwxRemainingTimeoutSec {
    param([Parameter(Mandatory)][int]$RequestCapSec,[Parameter(Mandatory)]$BlockClock,[Parameter(Mandatory)][int]$BlockTimeoutSec,[Parameter(Mandatory)]$PortfolioClock,[Parameter(Mandatory)][int]$PortfolioTimeoutSec)
    $blockRemaining = [Math]::Floor($BlockTimeoutSec - $BlockClock.Elapsed.TotalSeconds)
    $portfolioRemaining = [Math]::Floor($PortfolioTimeoutSec - $PortfolioClock.Elapsed.TotalSeconds)
    if ($portfolioRemaining -lt 1) { throw 'portfolio-timeout' }
    if ($blockRemaining -lt 1) { throw 'model-block-timeout' }
    return [int][Math]::Min($RequestCapSec,[Math]::Min($blockRemaining,$portfolioRemaining))
}

function Assert-AwxNoOverlappingModelBlocks {
    param([Parameter(Mandatory)][object[]]$Intervals)
    $ordered = @($Intervals | Sort-Object startMs)
    for ($index=1; $index -lt $ordered.Count; $index++) {
        if ([double]$ordered[$index].startMs -lt [double]$ordered[$index-1].endMs) { throw 'concurrent-model-block-detected' }
    }
}

function Get-AwxStrictAdapterBoolean {
    param($Value,[Parameter(Mandatory)][string]$ReasonCode)
    if (-not(Test-AwxStrictBoolean $Value)) { throw $ReasonCode }
    return [bool]$Value
}

function Assert-AwxLoadedAdapterEvidence {
    param([Parameter(Mandatory)]$Evidence,[Parameter(Mandatory)][string]$ExpectedModelTag)
    try { Assert-AwxExactPropertySet $Evidence @('modelTag','contextLength','sizeVramMiB','gpuResidentRatio','endpointProcessIds','computeRows') 'ps-evidence-incomplete' }
    catch { throw 'ps-evidence-incomplete' }
    if ($Evidence.modelTag -isnot [string] -or $Evidence.modelTag -cne $ExpectedModelTag -or
        -not(Test-AwxStrictIntegerRange $Evidence.contextLength 1 ([int]::MaxValue)) -or [int]$Evidence.contextLength -ne 8192 -or
        -not(Test-AwxFiniteNumberRange $Evidence.sizeVramMiB 0.0) -or [double]$Evidence.sizeVramMiB -le 0.0 -or
        -not(Test-AwxFiniteNumberRange $Evidence.gpuResidentRatio 0.0 1.0)) { throw 'ps-evidence-incomplete' }
    $processIds = @($Evidence.endpointProcessIds)
    if ($processIds.Count -eq 0) { throw 'ps-evidence-incomplete' }
    try {
        Assert-AwxEndpointProcessIds -Values $processIds -ReasonCode 'ps-evidence-incomplete'
        Assert-AwxComputeEvidenceRows -Rows @($Evidence.computeRows) -ReasonCode 'ps-evidence-incomplete'
    } catch { throw 'ps-evidence-incomplete' }
}

function Invoke-AwxModelBlock {
    param(
        [Parameter(Mandatory)]$Model,[Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle,[Parameter(Mandatory)]$Adapters,
        [Parameter(Mandatory)]$PortfolioClock,[int]$BlockTimeoutSec=1800,[int]$PortfolioTimeoutSec=7200
    )
    $blockClock = [Diagnostics.Stopwatch]::StartNew()
    $warm = [Collections.Generic.List[object]]::new(); $cold = $null; $loadedGpu = $null; $loaded = $null; $endLoaded = $null; $result = $null
    $primaryReason = $null; $cleanupReason = $null; $cleanupEvidence = $null
    $caseIds = $(if ($Model.role -ceq 'fast') {@('fast-rewrite-ko','fast-json-extract','fast-instruction')} else {@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
    try {
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $installed = & $Adapters.VerifyInstalled $Model $timeout
        if ($null -eq $installed -or $null -eq $installed.PSObject.Properties['passed'] -or
            -not(Get-AwxStrictAdapterBoolean $installed.passed 'benchmark-runtime-failed')) { throw 'candidate-digest-changed' }
        $timeout = Get-AwxRemainingTimeoutSec 30 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        [void](& $Adapters.Unload $Model $timeout)
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $beforePsEmpty = & $Adapters.PsEmpty $Model $timeout
        if (-not(Get-AwxStrictAdapterBoolean $beforePsEmpty 'model-unload-unproven')) { throw 'model-unload-unproven' }
        $coldCase = $Corpus.cases | Where-Object id -CEQ $caseIds[0]
        $coldExpected = ($Oracle.cases | Where-Object id -CEQ $caseIds[0]).expected
        $coldBody = New-AwxBenchmarkRequestBody -Case $coldCase -ModelTag $Model.modelTag -Options $Corpus.options
        $timeout = Get-AwxRemainingTimeoutSec 300 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $coldCall = & $Adapters.Chat $Model $coldCase $coldBody $timeout
        $coldCheck = Test-AwxBenchmarkResponse -Case $coldCase -Expected $coldExpected -ExpectedModel $Model.modelTag -OllamaResponse $coldCall.ollamaResponse
        $cold = [pscustomobject]@{caseId=$caseIds[0];latencyMs=[double]$coldCall.latencyMs;requestSha256=Get-AwxUtf8Sha256 $coldBody;transportPassed=$coldCheck.transportPassed;lineagePassed=$coldCheck.lineagePassed;jsonParsed=$coldCheck.jsonParsed;schemaPassed=$coldCheck.schemaPassed;semanticPassed=$coldCheck.semanticPassed;reasonCode=$coldCheck.reasonCode;responseSha256=$coldCheck.responseSha256;responseLength=$coldCheck.responseLength;tokenCount=$coldCheck.tokenCount;thinkingLength=$coldCheck.thinkingLength;doneReason=$coldCheck.doneReason}
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $loaded = & $Adapters.Loaded $Model $timeout
        Assert-AwxLoadedAdapterEvidence -Evidence $loaded -ExpectedModelTag $Model.modelTag
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $loadedGpu = @(& $Adapters.Gpu 'loaded' $Model $timeout)
        $minFree = $(if ($Model.role -ceq 'fast') {1536} else {2048})
        $lane = Test-AwxGpuLane -EndpointProcessIds @($loaded.endpointProcessIds) -ComputeRows @($loaded.computeRows) -Snapshot $loadedGpu -ExpectedLane $Model.gpuLane -MinimumFreeMiB $minFree
        if (-not $lane.passed) { throw $lane.reasonCode }
        if ([int]$loaded.contextLength -ne 8192) { throw 'ps-evidence-incomplete' }
        if ([double]$loaded.gpuResidentRatio -lt 0.99) { throw 'cpu-offload-detected' }
        foreach ($caseId in $caseIds) {
            $case = $Corpus.cases | Where-Object id -CEQ $caseId
            $expected = ($Oracle.cases | Where-Object id -CEQ $caseId).expected
            $body = New-AwxBenchmarkRequestBody -Case $case -ModelTag $Model.modelTag -Options $Corpus.options
            foreach ($repetition in 1..7) {
                $timeout = Get-AwxRemainingTimeoutSec 120 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
                $call = & $Adapters.Chat $Model $case $body $timeout
                $check = Test-AwxBenchmarkResponse -Case $case -Expected $expected -ExpectedModel $Model.modelTag -OllamaResponse $call.ollamaResponse
                $warm.Add([pscustomobject]@{caseId=$caseId;repetition=$repetition;latencyMs=[double]$call.latencyMs;requestSha256=Get-AwxUtf8Sha256 $body;transportPassed=$check.transportPassed;lineagePassed=$check.lineagePassed;jsonParsed=$check.jsonParsed;schemaPassed=$check.schemaPassed;semanticPassed=$check.semanticPassed;reasonCode=$check.reasonCode;responseSha256=$check.responseSha256;responseLength=$check.responseLength;tokenCount=$check.tokenCount;thinkingLength=$check.thinkingLength;doneReason=$check.doneReason})
                [void](Get-AwxRemainingTimeoutSec 120 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec)
            }
        }
        if ($warm.Count -ne 21) { throw 'warm-sample-insufficient' }
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $endLoaded = & $Adapters.Loaded $Model $timeout
        Assert-AwxLoadedAdapterEvidence -Evidence $endLoaded -ExpectedModelTag $Model.modelTag
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $endGpu = @(& $Adapters.Gpu 'end' $Model $timeout)
        $endLane = Test-AwxGpuLane -EndpointProcessIds @($endLoaded.endpointProcessIds) -ComputeRows @($endLoaded.computeRows) -Snapshot $endGpu -ExpectedLane $Model.gpuLane -MinimumFreeMiB $minFree
        if (-not $endLane.passed -or [double]$endLoaded.gpuResidentRatio -lt 0.99 -or [int]$endLoaded.contextLength -ne 8192) { throw 'gpu-lane-evidence-incomplete' }
        $endpointRestarted = -not (Test-AwxExactSequence @($loaded.endpointProcessIds) @($endLoaded.endpointProcessIds))
        $result = [pscustomobject]@{modelTag=$Model.modelTag;role=$Model.role;classification=$Model.classification;cold=$cold;warm=@($warm);loaded=$loaded;lane=$lane;endLoaded=$endLoaded;endLane=$endLane;endpointRestarted=$endpointRestarted;blockElapsedMs=[Math]::Round($blockClock.Elapsed.TotalMilliseconds,3)}
    } catch {
        $primaryReason = Get-AwxBoundedReasonCode $_.Exception
    } finally {
        try {
            [void](& $Adapters.Unload $Model 30)
            $psEmpty = Get-AwxStrictAdapterBoolean (& $Adapters.PsEmpty $Model 15) 'model-unload-unproven'
            $loadedProcessIds = $(if ($null -ne $loaded -and $loaded.PSObject.Properties['endpointProcessIds']) {@($loaded.endpointProcessIds)} else {@()})
            $endProcessIds = $(if ($null -ne $endLoaded -and $endLoaded.PSObject.Properties['endpointProcessIds']) {@($endLoaded.endpointProcessIds)} else {@()})
            $allProcessIds = @($loadedProcessIds + $endProcessIds | Select-Object -Unique)
            $gpuReleased = Get-AwxStrictAdapterBoolean (& $Adapters.GpuReleased $Model $allProcessIds 15) 'model-unload-unproven'
            $postUnloadGpu = @(& $Adapters.Gpu 'post-unload' $Model 15)
            if (-not $psEmpty -or -not $gpuReleased) { $cleanupReason = 'model-unload-unproven' }
            else { $cleanupEvidence = [pscustomobject]@{psEmpty=$true;gpuReleased=$true;postUnloadGpu=$postUnloadGpu} }
        } catch { $cleanupReason = 'model-unload-unproven' }
        $blockClock.Stop()
    }
    if ($cleanupReason) { throw $cleanupReason }
    if ($primaryReason) { throw $primaryReason }
    $result | Add-Member -NotePropertyName cleanup -NotePropertyValue $cleanupEvidence
    $coldFact=[pscustomobject][ordered]@{caseId=$cold.caseId;requestSha256=$cold.requestSha256;responseSha256=$cold.responseSha256;latencyMs=[double]$cold.latencyMs;transportPassed=$cold.transportPassed;lineagePassed=$cold.lineagePassed;jsonParsed=$cold.jsonParsed;schemaPassed=$cold.schemaPassed;semanticPassed=$cold.semanticPassed;reasonCode=$cold.reasonCode}
    $warmFacts=@($warm|ForEach-Object{[pscustomobject][ordered]@{caseId=$_.caseId;repetition=[int]$_.repetition;requestSha256=$_.requestSha256;responseSha256=$_.responseSha256;latencyMs=[double]$_.latencyMs;transportPassed=$_.transportPassed;lineagePassed=$_.lineagePassed;jsonParsed=$_.jsonParsed;schemaPassed=$_.schemaPassed;semanticPassed=$_.semanticPassed;reasonCode=$_.reasonCode}})
    $blockFacts=[pscustomobject][ordered]@{
        modelTag=$Model.modelTag;role=$Model.role;classification=$Model.classification;cold=$coldFact;warm=$warmFacts
        lane=[pscustomobject][ordered]@{gpuResidentRatio=[Math]::Min([double]$loaded.gpuResidentRatio,[double]$endLoaded.gpuResidentRatio);loadedFreeMiB=[Math]::Min([double]$lane.freeMiB,[double]$endLane.freeMiB);lanePassed=[bool]($lane.passed -and $endLane.passed)}
        cleanup=[pscustomobject][ordered]@{psEmpty=[bool]$cleanupEvidence.psEmpty;gpuReleased=[bool]$cleanupEvidence.gpuReleased;endpointRestarted=[bool]$endpointRestarted}
    }
    $blockEnvelope=New-AwxBlockEvidence -BlockFacts $blockFacts
    $result|Add-Member -NotePropertyName blockFacts -NotePropertyValue $blockEnvelope.blockFacts
    $result|Add-Member -NotePropertyName blockEvidenceSha256 -NotePropertyValue $blockEnvelope.blockEvidenceSha256
    return $result
}

function Invoke-AwxBenchmarkPortfolio {
    param([Parameter(Mandatory)][object[]]$Models,[Parameter(Mandatory)][scriptblock]$ModelBlockRunner,[Parameter(Mandatory)][scriptblock]$EmergencyUnloader,[int]$PortfolioTimeoutSec=7200)
    $clock = [Diagnostics.Stopwatch]::StartNew(); $results = [Collections.Generic.List[object]]::new(); $intervals = [Collections.Generic.List[object]]::new()
    foreach ($model in $Models) {
        if ($clock.Elapsed.TotalSeconds -ge $PortfolioTimeoutSec) { throw 'portfolio-timeout' }
        $startMs = $clock.Elapsed.TotalMilliseconds
        try { $results.Add((& $ModelBlockRunner $model $clock $PortfolioTimeoutSec)) }
        catch {
            $reason = Get-AwxBoundedReasonCode $_.Exception
            try { [void](& $EmergencyUnloader $model 30) } catch { throw 'model-unload-unproven' }
            throw $reason
        }
        $intervals.Add([pscustomobject]@{startMs=$startMs;endMs=$clock.Elapsed.TotalMilliseconds})
        Assert-AwxNoOverlappingModelBlocks @($intervals)
        if ($clock.Elapsed.TotalSeconds -ge $PortfolioTimeoutSec) { throw 'portfolio-timeout' }
    }
    $clock.Stop(); return @($results)
}

function Get-AwxBenchmarkModelMatrix {
    return @(
        [pscustomobject][ordered]@{order=[int]1;role='fast';classification='baseline';modelTag='qwen3:8b';endpoint='http://127.0.0.1:11435';endpointLabel='fast-11435';gpuLane='RTX 3060';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'},
        [pscustomobject][ordered]@{order=[int]2;role='fast';classification='primary';modelTag='qwen3.5:9b';endpoint='http://127.0.0.1:11435';endpointLabel='fast-11435';gpuLane='RTX 3060';digest='6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7'},
        [pscustomobject][ordered]@{order=[int]3;role='fast';classification='challenger';modelTag='gemma4:12b';endpoint='http://127.0.0.1:11435';endpointLabel='fast-11435';gpuLane='RTX 3060';digest='4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c'},
        [pscustomobject][ordered]@{order=[int]4;role='main';classification='baseline';modelTag='gemma4:26b';endpoint='http://127.0.0.1:11434';endpointLabel='primary-11434';gpuLane='RTX 3090';digest='5571076f3d70050487b26b341705799e0ab29b808164f90d20d4cf84f699d251'},
        [pscustomobject][ordered]@{order=[int]5;role='main';classification='primary';modelTag='qwen3.6:27b';endpoint='http://127.0.0.1:11434';endpointLabel='primary-11434';gpuLane='RTX 3090';digest='a50eda8ed977ab48a12431878896b27ffd5cef552c17af3317d9623b939a7f1e'},
        [pscustomobject][ordered]@{order=[int]6;role='main';classification='challenger';modelTag='gemma4:31b';endpoint='http://127.0.0.1:11434';endpointLabel='primary-11434';gpuLane='RTX 3090';digest='6316f0629137b426c9d9b853ffc4c8209589f30ee39aebede6285096c0ff47e7'}
    )
}

function Assert-AwxBenchmarkModelMatrix {
    param([Parameter(Mandatory)][object[]]$Models)
    $expected = @(Get-AwxBenchmarkModelMatrix)
    if ($Models.Count -ne $expected.Count) { throw 'benchmark-contract-invalid' }
    $names = @('order','role','classification','modelTag','endpoint','endpointLabel','gpuLane','digest')
    for ($index = 0; $index -lt $expected.Count; $index++) {
        $actual = $Models[$index]
        Assert-AwxExactPropertySet -Value $actual -Names $names
        if (-not(Test-AwxStrictIntegerRange $actual.order 1 6) -or [int]$actual.order -ne ($index + 1)) { throw 'benchmark-contract-invalid' }
        foreach ($name in @('role','classification','modelTag','endpoint','endpointLabel','gpuLane','digest')) {
            if ($actual.$name -isnot [string] -or $actual.$name -cne $expected[$index].$name) { throw 'benchmark-contract-invalid' }
        }
        Assert-AwxHash64 $actual.digest
        Assert-AwxLoopbackEndpoint $actual.endpoint
    }
}

function Assert-AwxReportPropertySet {
    param($Value,[string[]]$Names)
    Assert-AwxExactPropertySet -Value $Value -Names $Names -ReasonCode 'report-privacy-invalid'
}

function Assert-AwxHashValue {
    param($Value)
    Assert-AwxHash64 -Value $Value -ReasonCode 'report-privacy-invalid'
}

function Assert-AwxFiniteNonNegative {
    param($Value)
    if (-not(Test-AwxFiniteNumberRange $Value 0.0)) { throw 'report-privacy-invalid' }
}

function Assert-AwxStrictReportString {
    param($Value,[string[]]$Allowed)
    if ($Value -isnot [string] -or ($Allowed.Count -gt 0 -and $Value -cnotin $Allowed)) { throw 'report-privacy-invalid' }
}

function Assert-AwxPublicReasonArray {
    param($ReasonCodes,[string[]]$Policy)
    if ($ReasonCodes -isnot [System.Array]) { throw 'report-privacy-invalid' }
    $codes = @($ReasonCodes)
    $ordered = @($Policy | Where-Object {$codes -ccontains $_})
    if ($codes.Count -eq 0 -or @($codes | Select-Object -Unique).Count -ne $codes.Count -or
        @($codes | Where-Object {$_ -isnot [string] -or $_ -cnotin $Policy}).Count -gt 0 -or
        -not(Test-AwxExactSequence $codes $ordered)) { throw 'report-privacy-invalid' }
}

function Assert-AwxPublicPacketProjection {
    param($Packet)
    Assert-AwxReportPropertySet $Packet @('name','complete','passed','reasonCodes','runBindingSha256','evidenceSha256','packetSha256')
    if ($Packet.name -isnot [string] -or $Packet.name -cnotin @('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY') -or
        -not(Test-AwxStrictBoolean $Packet.complete) -or -not(Test-AwxStrictBoolean $Packet.passed)) { throw 'report-privacy-invalid' }
    try { Assert-AwxReasonEnvelope $Packet.name $Packet.complete $Packet.passed $Packet.reasonCodes }
    catch { throw 'report-privacy-invalid' }
    foreach ($name in @('runBindingSha256','evidenceSha256','packetSha256')) { Assert-AwxHashValue $Packet.$name }
}

function Assert-AwxPublicNeutralProjection {
    param($Neutral)
    Assert-AwxReportPropertySet $Neutral @('forward','forwardReasonCode','reverse','reverseReasonCode','orderStable','finalVerdict','finalReasonCode','runBindingSha256','commandEvidenceSha256')
    if (-not(Test-AwxStrictBoolean $Neutral.orderStable) -or
        -not(Test-AwxInternalNeutralPair $Neutral.forward $Neutral.forwardReasonCode) -or
        -not(Test-AwxInternalNeutralPair $Neutral.reverse $Neutral.reverseReasonCode)) { throw 'report-privacy-invalid' }
    if ($Neutral.orderStable) {
        if ($Neutral.forward -cne $Neutral.reverse -or $Neutral.forwardReasonCode -cne $Neutral.reverseReasonCode -or
            $Neutral.finalVerdict -cne $Neutral.forward -or $Neutral.finalReasonCode -cne $Neutral.forwardReasonCode) { throw 'report-privacy-invalid' }
    } elseif ($Neutral.finalVerdict -cne 'HOLD' -or $Neutral.finalReasonCode -cne 'order-unstable' -or
        ($Neutral.forward -ceq $Neutral.reverse -and $Neutral.forwardReasonCode -ceq $Neutral.reverseReasonCode)) { throw 'report-privacy-invalid' }
    Assert-AwxHashValue $Neutral.runBindingSha256
    Assert-AwxHashValue $Neutral.commandEvidenceSha256
}

function Get-AwxReportCaseIds {
    return @('fast-rewrite-ko','fast-json-extract','fast-instruction','main-grounded-ko','main-uncertainty-ko','main-policy-ko')
}

function Get-AwxLiveFailureReasonPolicy {
    return @('candidate-digest-changed','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated','lineage-missing','cpu-offload-detected','insufficient-vram','warm-sample-insufficient','portfolio-timeout','model-block-timeout','model-unload-unproven','gpu-lane-evidence-incomplete','gpu-release-unproven','ps-evidence-incomplete','tags-evidence-incomplete','native-probe-timeout','role-binding-mutated','concurrent-model-block-detected','benchmark-runtime-failed')
}

function Assert-AwxPublicSample {
    param($Sample,[string[]]$CaseIds,[bool]$Warm)
    $names = @('caseId','latencyMs','requestSha256','responseSha256','responseLength','tokenCount','transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed','thinkingLength','doneReason','reasonCode')
    if ($Warm) { $names = @('caseId','repetition','latencyMs','requestSha256','responseSha256','responseLength','tokenCount','transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed','thinkingLength','doneReason','reasonCode') }
    Assert-AwxReportPropertySet $Sample $names
    if ($Sample.caseId -isnot [string] -or $Sample.caseId -cnotin $CaseIds -or
        $Sample.doneReason -isnot [string] -or $Sample.doneReason -cnotin @('stop','length','unknown') -or
        $Sample.reasonCode -isnot [string] -or $Sample.reasonCode -cnotin @('pass','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated','lineage-missing','model-block-timeout','portfolio-timeout')) { throw 'report-privacy-invalid' }
    foreach ($name in @('transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed')) {
        if (-not(Test-AwxStrictBoolean $Sample.$name)) { throw 'report-privacy-invalid' }
    }
    if ($Warm -and -not(Test-AwxStrictIntegerRange $Sample.repetition 1 7)) { throw 'report-privacy-invalid' }
    if ($Sample.semanticPassed -and $Sample.reasonCode -cne 'pass') { throw 'report-privacy-invalid' }
    if ($Sample.reasonCode -ceq 'pass' -and (-not $Sample.transportPassed -or -not $Sample.lineagePassed -or -not $Sample.jsonParsed -or -not $Sample.schemaPassed -or -not $Sample.semanticPassed -or $Sample.doneReason -cne 'stop')) { throw 'report-privacy-invalid' }
    if ($Sample.reasonCode -ceq 'response-truncated' -and $Sample.doneReason -cne 'length') { throw 'report-privacy-invalid' }
    if ($Sample.doneReason -ceq 'unknown' -and ($Sample.reasonCode -cne 'lineage-missing' -or $Sample.lineagePassed -or $Sample.jsonParsed -or $Sample.schemaPassed -or $Sample.semanticPassed)) { throw 'report-privacy-invalid' }
    Assert-AwxHashValue $Sample.requestSha256; Assert-AwxHashValue $Sample.responseSha256
    Assert-AwxFiniteNonNegative $Sample.latencyMs
    foreach ($name in @('responseLength','thinkingLength')) {
        if (-not(Test-AwxStrictIntegerRange $Sample.$name 0 ([long]::MaxValue))) { throw 'report-privacy-invalid' }
    }
    if ($null -ne $Sample.tokenCount -and -not(Test-AwxStrictIntegerRange $Sample.tokenCount 0 ([long]::MaxValue))) { throw 'report-privacy-invalid' }
}

function Assert-AwxReportPrivacy {
    param([Parameter(Mandatory)]$Report)
    $caseIds = @(Get-AwxReportCaseIds)
    $fixedModels = @(Get-AwxBenchmarkModelMatrix)
    $modelTags = @($fixedModels.modelTag)
    Assert-AwxReportPropertySet $Report @('schemaVersion','benchmarkId','mode','durationMs','corpus','models','recommendations','packets','neutral','verdict','reasonCodes')
    if (-not(Test-AwxStrictIntegerRange $Report.schemaVersion 2 2)) { throw 'report-privacy-invalid' }
    Assert-AwxStrictReportString $Report.benchmarkId @('awx.desktop-ollama-benchmark.v2')
    Assert-AwxStrictReportString $Report.mode @('validate-only','live')
    Assert-AwxStrictReportString $Report.verdict @('APPLY','HOLD','REJECT')
    if ($Report.models -isnot [System.Array] -or $Report.recommendations -isnot [System.Array] -or
        $Report.packets -isnot [System.Array] -or $Report.reasonCodes -isnot [System.Array]) { throw 'report-privacy-invalid' }
    Assert-AwxFiniteNonNegative $Report.durationMs
    Assert-AwxReportPropertySet $Report.corpus @('corpusId','corpusSha256','ontologySha256','schemaSha256','oracleSha256','optionsSha256','promptHashes')
    Assert-AwxStrictReportString $Report.corpus.corpusId @('awx.desktop-ollama-benchmark.v2')
    foreach ($name in @('corpusSha256','ontologySha256','schemaSha256','oracleSha256','optionsSha256')) { Assert-AwxHashValue $Report.corpus.$name }
    if ($Report.corpus.promptHashes -isnot [System.Array] -or @($Report.corpus.promptHashes).Count -ne 6 -or
        -not(Test-AwxExactSequence @($Report.corpus.promptHashes.caseId) $caseIds)) { throw 'report-privacy-invalid' }
    foreach ($row in @($Report.corpus.promptHashes)) {
        Assert-AwxReportPropertySet $row @('caseId','promptSha256','schemaSha256')
        Assert-AwxStrictReportString $row.caseId $caseIds
        Assert-AwxHashValue $row.promptSha256; Assert-AwxHashValue $row.schemaSha256
    }
    if ($Report.mode -ceq 'validate-only') {
        if (@($Report.models).Count -ne 0 -or @($Report.recommendations).Count -ne 0 -or @($Report.packets).Count -ne 0 -or
            $null -ne $Report.neutral -or $Report.verdict -cne 'HOLD') { throw 'report-privacy-invalid' }
        Assert-AwxPublicReasonArray $Report.reasonCodes @('live-evidence-not-requested')
    } else {
        $failureShape = @($Report.models).Count -eq 0 -and @($Report.recommendations).Count -eq 0 -and @($Report.packets).Count -eq 0 -and $null -eq $Report.neutral -and $Report.verdict -ceq 'HOLD'
        $completeShape = @($Report.models).Count -eq 6 -and (Test-AwxExactSequence @($Report.models.modelTag) $modelTags)
        if (-not $failureShape -and -not $completeShape) { throw 'report-privacy-invalid' }
        if ($failureShape) {
            if (@($Report.reasonCodes).Count -ne 1) { throw 'report-privacy-invalid' }
            Assert-AwxPublicReasonArray $Report.reasonCodes @(Get-AwxLiveFailureReasonPolicy)
        }
    }
    foreach ($index in 0..(@($Report.models).Count-1)) {
        if (@($Report.models).Count -eq 0) { break }
        $model = $Report.models[$index]; $fixed = $fixedModels[$index]
        Assert-AwxReportPropertySet $model @('role','classification','modelTag','digest','endpointLabel','gpuLane','totalMiB','freeMiB','gpuResidentRatio','coldLoadMs','warmRoleP95Ms','completedWarmCount','balancedScore','scoreDelta','hardGatePassed','cleanupPassed','reasonCodes','caseTimings','semanticRates','coldResult','warmResults')
        foreach ($name in @('role','classification','modelTag','digest','endpointLabel','gpuLane')) {
            if ($model.$name -isnot [string] -or $model.$name -cne $fixed.$name) { throw 'report-privacy-invalid' }
        }
        if (-not(Test-AwxStrictBoolean $model.hardGatePassed) -or -not(Test-AwxStrictBoolean $model.cleanupPassed)) { throw 'report-privacy-invalid' }
        if (-not(Test-AwxStrictIntegerRange $model.totalMiB 1 ([int]::MaxValue)) -or -not(Test-AwxStrictIntegerRange $model.freeMiB 0 ([int]::MaxValue)) -or [int64]$model.freeMiB -gt [int64]$model.totalMiB -or
            -not(Test-AwxFiniteNumberRange $model.gpuResidentRatio 0.0 1.0) -or -not(Test-AwxFiniteNumberRange $model.coldLoadMs 0.0) -or
            -not(Test-AwxFiniteNumberRange $model.warmRoleP95Ms 0.0) -or -not(Test-AwxStrictIntegerRange $model.completedWarmCount 21 21)) { throw 'report-privacy-invalid' }
        Assert-AwxHashValue $model.digest
        if ($null -ne $model.balancedScore) { try {[void](ConvertTo-AwxScoreMicrounits $model.balancedScore)} catch {throw 'report-privacy-invalid'} }
        if ($null -ne $model.scoreDelta) { try {[void](ConvertTo-AwxScoreMicrounits $model.scoreDelta -AllowSigned)} catch {throw 'report-privacy-invalid'} }
        try { Assert-AwxHardGateReasonEnvelope $model.hardGatePassed $model.reasonCodes } catch { throw 'report-privacy-invalid' }
        if (-not $model.cleanupPassed -and $model.hardGatePassed) { throw 'report-privacy-invalid' }
        $roleIds = $(if ($model.role -ceq 'fast') {@('fast-rewrite-ko','fast-json-extract','fast-instruction')} else {@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
        if ($model.caseTimings -isnot [System.Array] -or @($model.caseTimings).Count -ne 3 -or -not(Test-AwxExactSequence @($model.caseTimings.caseId) $roleIds) -or
            $model.semanticRates -isnot [System.Array] -or @($model.semanticRates).Count -ne 3 -or -not(Test-AwxExactSequence @($model.semanticRates.caseId) $roleIds) -or
            $model.warmResults -isnot [System.Array] -or @($model.warmResults).Count -ne 21 -or $null -eq $model.coldResult) { throw 'report-privacy-invalid' }
        foreach ($timing in @($model.caseTimings)) { Assert-AwxReportPropertySet $timing @('caseId','p50Ms'); Assert-AwxStrictReportString $timing.caseId $roleIds; Assert-AwxFiniteNonNegative $timing.p50Ms }
        foreach ($rate in @($model.semanticRates)) { Assert-AwxReportPropertySet $rate @('caseId','rate'); Assert-AwxStrictReportString $rate.caseId $roleIds; if (-not(Test-AwxFiniteNumberRange $rate.rate 0.0 1.0)) {throw 'report-privacy-invalid'} }
        Assert-AwxPublicSample -Sample $model.coldResult -CaseIds $roleIds -Warm $false
        foreach ($sample in @($model.warmResults)) { Assert-AwxPublicSample -Sample $sample -CaseIds $roleIds -Warm $true }
        foreach ($id in $roleIds) {
            $rows = @($model.warmResults | Where-Object caseId -CEQ $id)
            if ($rows.Count -ne 7 -or -not(Test-AwxExactSequence @($rows.repetition | Sort-Object) @(1..7))) { throw 'report-privacy-invalid' }
        }
    }
    foreach ($recommendation in @($Report.recommendations)) {
        Assert-AwxReportPropertySet $recommendation @('role','status','modelTag','scoreDelta','reasonCode')
        Assert-AwxStrictReportString $recommendation.role @('fast','main')
        Assert-AwxStrictReportString $recommendation.status @('HOLD','keep-baseline','recommend-candidate')
        $baselineTag = $(if ($recommendation.role -ceq 'fast') {'qwen3:8b'} else {'gemma4:26b'})
        $candidateTags = $(if ($recommendation.role -ceq 'fast') {@('qwen3.5:9b','gemma4:12b')} else {@('qwen3.6:27b','gemma4:31b')})
        $deltaMicros = $null
        if ($null -ne $recommendation.scoreDelta) { try {$deltaMicros=ConvertTo-AwxScoreMicrounits $recommendation.scoreDelta} catch {throw 'report-privacy-invalid'} }
        if ($recommendation.status -ceq 'HOLD') {
            if ($recommendation.reasonCode -cne 'baseline-evidence-incomplete' -or $null -ne $recommendation.modelTag -or $null -ne $recommendation.scoreDelta) {throw 'report-privacy-invalid'}
        } elseif ($recommendation.status -ceq 'keep-baseline') {
            if ($recommendation.reasonCode -cnotin @('no-eligible-candidate','candidate-tie') -or $recommendation.modelTag -cne $baselineTag -or $deltaMicros -ne 0) {throw 'report-privacy-invalid'}
        } elseif ($recommendation.reasonCode -cne 'promotion-approval-required' -or $recommendation.modelTag -cnotin $candidateTags -or $null -eq $deltaMicros -or $deltaMicros -le 0) { throw 'report-privacy-invalid' }
    }
    foreach ($packet in @($Report.packets)) { Assert-AwxPublicPacketProjection $packet }
    if ($Report.mode -ceq 'live' -and @($Report.models).Count -eq 6) {
        if (@($Report.recommendations).Count -ne 2 -or -not(Test-AwxExactSequence @($Report.recommendations.role) @('fast','main')) -or
            @($Report.packets).Count -ne 3 -or -not(Test-AwxExactSequence @($Report.packets.name) @('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY')) -or
            $null -eq $Report.neutral) { throw 'report-privacy-invalid' }
        Assert-AwxPublicNeutralProjection $Report.neutral
        if ($Report.verdict -cne $Report.neutral.finalVerdict -or @($Report.reasonCodes).Count -ne 1 -or
            $Report.reasonCodes[0] -cne $Report.neutral.finalReasonCode -or
            @($Report.packets | Where-Object runBindingSha256 -CNE $Report.neutral.runBindingSha256).Count -ne 0) { throw 'report-privacy-invalid' }
        Assert-AwxPublicReasonArray $Report.reasonCodes @([string]$Report.neutral.finalReasonCode)
    } elseif ($null -ne $Report.neutral) { throw 'report-privacy-invalid' }
    $json = ConvertTo-AwxCanonicalJson $Report
    if ($json -match '(?i)raw(prompt|response)|authorization|cookie|credential|owner.?token|model.?store|correctAnswer|GPU-[0-9a-f-]{16,}|\b(?:sk|pk)-[A-Za-z0-9_-]{8,}|[A-Za-z]:\\|\\\\') { throw 'report-privacy-invalid' }
}

function New-AwxCorpusProjection {
    param($Corpus,[string]$CorpusPath,[string]$OraclePath)
    $schemaHashes = @($Corpus.cases | ForEach-Object { Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $_.responseSchema) })
    $promptHashes = @($Corpus.cases | ForEach-Object {
        $position = [array]::IndexOf(@($Corpus.cases.id),$_.id)
        [pscustomobject][ordered]@{caseId=[string]$_.id;promptSha256=Get-AwxUtf8Sha256 ([string]$_.prompt);schemaSha256=$schemaHashes[$position]}
    })
    return [pscustomobject][ordered]@{
        corpusId=[string]$Corpus.corpusId;corpusSha256=Get-AwxFileSha256 $CorpusPath
        ontologySha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.ontologies)
        schemaSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $schemaHashes)
        oracleSha256=Get-AwxFileSha256 $OraclePath
        optionsSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.options)
        promptHashes=$promptHashes
    }
}

function Assert-AwxFrozenInputProjection {
    param(
        [Parameter(Mandatory)]$FrozenProjection,
        [Parameter(Mandatory)]$Corpus,
        [Parameter(Mandatory)]$Oracle,
        [Parameter(Mandatory)][string]$CorpusPath,
        [Parameter(Mandatory)][string]$OraclePath
    )
    Assert-AwxBenchmarkContract -Corpus $Corpus -Oracle $Oracle
    $currentProjection = New-AwxCorpusProjection -Corpus $Corpus -CorpusPath $CorpusPath -OraclePath $OraclePath
    if ((ConvertTo-AwxCanonicalJson $currentProjection) -cne (ConvertTo-AwxCanonicalJson $FrozenProjection)) {
        throw 'lineage-missing'
    }
}

function New-AwxValidateOnlyReport {
    param([Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle,[Parameter(Mandatory)][string]$CorpusPath,[Parameter(Mandatory)][string]$OraclePath)
    Assert-AwxBenchmarkContract -Corpus $Corpus -Oracle $Oracle
    $report = [pscustomobject][ordered]@{
        schemaVersion=[int]2;benchmarkId='awx.desktop-ollama-benchmark.v2';mode='validate-only';durationMs=[double]0
        corpus=New-AwxCorpusProjection -Corpus $Corpus -CorpusPath $CorpusPath -OraclePath $OraclePath
        models=@();recommendations=@();packets=@();neutral=$null;verdict='HOLD';reasonCodes=@('live-evidence-not-requested')
    }
    Assert-AwxReportPrivacy $report
    return $report
}

function New-AwxLiveFailureReport {
    param([Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)][string]$CorpusPath,[Parameter(Mandatory)][string]$OraclePath,[Parameter(Mandatory)]$DurationMs,[Parameter(Mandatory)][string]$ReasonCode)
    $projection = New-AwxCorpusProjection -Corpus $Corpus -CorpusPath $CorpusPath -OraclePath $OraclePath
    return New-AwxLiveFailureReportFromProjection -CorpusProjection $projection -DurationMs $DurationMs -ReasonCode $ReasonCode
}

function New-AwxLiveFailureReportFromProjection {
    param([Parameter(Mandatory)]$CorpusProjection,[Parameter(Mandatory)]$DurationMs,[Parameter(Mandatory)][string]$ReasonCode)
    if (-not(Test-AwxFiniteNumberRange $DurationMs 0.0) -or $ReasonCode -cnotin (Get-AwxLiveFailureReasonPolicy)) { throw 'report-privacy-invalid' }
    $report = [pscustomobject][ordered]@{
        schemaVersion=[int]2;benchmarkId='awx.desktop-ollama-benchmark.v2';mode='live';durationMs=[Math]::Round([double]$DurationMs,3)
        corpus=$CorpusProjection
        models=@();recommendations=@();packets=@();neutral=$null;verdict='HOLD';reasonCodes=@($ReasonCode)
    }
    Assert-AwxReportPrivacy $report
    return $report
}

function Test-AwxCompleteHashSet {
    param([Parameter(Mandatory)]$Values,[Parameter(Mandatory)][int]$ExpectedCount)
    if ($Values -isnot [System.Array] -or $ExpectedCount -lt 0) { return $false }
    $items = @($Values)
    if ($items.Count -ne $ExpectedCount) { return $false }
    foreach ($value in $items) {
        if ($value -isnot [string] -or $value -cnotmatch '^[a-f0-9]{64}$') { return $false }
    }
    return $true
}

function Get-AwxAdapterRemainingSec {
    param([Parameter(Mandatory)]$Clock,[Parameter(Mandatory)][int]$TimeoutSec)
    $remaining = [Math]::Floor($TimeoutSec - $Clock.Elapsed.TotalSeconds)
    if ($remaining -lt 1) { throw 'native-probe-timeout' }
    return [int]$remaining
}

function New-AwxProductionAdapters {
    return [pscustomobject][ordered]@{
        VerifyInstalled = {
            param($Model,$TimeoutSec)
            $rows = @(Get-AwxOllamaTags -Endpoint $Model.endpoint -TimeoutSec $TimeoutSec)
            return Test-AwxInstalledDigest -Rows $rows -ModelTag $Model.modelTag -ExpectedDigest $Model.digest
        }
        PsEmpty = {
            param($Model,$TimeoutSec)
            $rows = @(Get-AwxOllamaPs -Endpoint $Model.endpoint -TimeoutSec $TimeoutSec)
            return [bool]($rows.Count -eq 0)
        }
        Gpu = {
            param($Phase,$Model,$TimeoutSec)
            if ($Phase -isnot [string] -or $Phase -cnotin @('loaded','end','post-unload')) { throw 'gpu-lane-evidence-incomplete' }
            return @(Get-AwxGpuSnapshot -TimeoutSec $TimeoutSec)
        }
        Loaded = {
            param($Model,$TimeoutSec)
            $clock = [Diagnostics.Stopwatch]::StartNew()
            $rows = @(Get-AwxOllamaPs -Endpoint $Model.endpoint -TimeoutSec (Get-AwxAdapterRemainingSec $clock $TimeoutSec))
            $loaded = Get-AwxLoadedModelEvidence -Rows $rows -ModelTag $Model.modelTag -ExpectedContext 8192
            $processIds = @(Get-AwxEndpointProcessIds -Endpoint $Model.endpoint -TimeoutSec (Get-AwxAdapterRemainingSec $clock $TimeoutSec))
            $computeRows = @(Get-AwxGpuComputeRows -TimeoutSec (Get-AwxAdapterRemainingSec $clock $TimeoutSec))
            return [pscustomobject][ordered]@{
                modelTag=[string]$loaded.modelTag
                contextLength=[int]$loaded.contextLength
                sizeVramMiB=[double]$loaded.sizeVramMiB
                gpuResidentRatio=[double]$loaded.gpuResidentRatio
                endpointProcessIds=$processIds
                computeRows=$computeRows
            }
        }
        Chat = {
            param($Model,$Case,$Body,$TimeoutSec)
            Assert-AwxOutboundBodyClean $Body
            $clock = [Diagnostics.Stopwatch]::StartNew()
            $response = Invoke-AwxOllamaHttp -Endpoint $Model.endpoint -Path '/api/chat' -Method POST -Body $Body -TimeoutSec $TimeoutSec
            $clock.Stop()
            return [pscustomobject][ordered]@{latencyMs=[Math]::Round($clock.Elapsed.TotalMilliseconds,3);ollamaResponse=$response}
        }
        Unload = {
            param($Model,$TimeoutSec)
            Stop-AwxOllamaModel -Model $Model -TimeoutSec $TimeoutSec
        }
        GpuReleased = {
            param($Model,$EndpointProcessIds,$TimeoutSec)
            $rows = @(Get-AwxGpuComputeRows -TimeoutSec $TimeoutSec)
            $release = Test-AwxGpuRelease -EndpointProcessIds @($EndpointProcessIds) -ComputeRows $rows
            if (-not(Test-AwxStrictBoolean $release.passed)) { throw 'gpu-release-unproven' }
            return [bool]$release.passed
        }
    }
}

function Invoke-AwxFalsificationProbes {
    param([Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle)
    Assert-AwxBenchmarkContract -Corpus $Corpus -Oracle $Oracle

    $constRejected = $false
    try {
        $constCorpus = $Corpus | ConvertTo-Json -Depth 100 | ConvertFrom-Json
        $constCorpus.cases[0].responseSchema.properties.rewrite | Add-Member -NotePropertyName const -NotePropertyValue 'forbidden'
        Assert-AwxBenchmarkContract -Corpus $constCorpus -Oracle $Oracle
    } catch { $constRejected = $_.Exception.Message -ceq 'const-forbidden' }

    $shapeCase = @($Corpus.cases | Where-Object id -CEQ 'fast-instruction')[0]
    $shapeExpected = @($Oracle.cases | Where-Object id -CEQ 'fast-instruction')[0].expected
    $shapeResponse = [pscustomobject][ordered]@{model='probe:model';done_reason='stop';message=[pscustomobject]@{content='{"token":"FAIL"}';thinking=''};eval_count=[int]1}
    $shapeResult = Test-AwxBenchmarkResponse -Case $shapeCase -Expected $shapeExpected -ExpectedModel 'probe:model' -OllamaResponse $shapeResponse
    $shapeOnlyRejected = [bool]($shapeResult.jsonParsed -and $shapeResult.schemaPassed -and -not $shapeResult.semanticPassed -and $shapeResult.reasonCode -ceq 'response-semantic-mismatch')

    $extraCase = @($Corpus.cases | Where-Object id -CEQ 'main-uncertainty-ko')[0]
    $extraExpected = @($Oracle.cases | Where-Object id -CEQ 'main-uncertainty-ko')[0].expected
    $extraEnvelope = [ordered]@{
        evidenceState='INSUFFICIENT'
        answerKind='NO_VALUE'
        value=$null
        unit='NONE'
        reasonCodes=@('EVIDENCE_MISSING','OUT_OF_SCOPE')
    }
    $extraResponse = [pscustomobject][ordered]@{model='probe:model';done_reason='stop';message=[pscustomobject]@{content=(ConvertTo-AwxCanonicalJson $extraEnvelope);thinking=''};eval_count=[int]1}
    $extraResult = Test-AwxBenchmarkResponse -Case $extraCase -Expected $extraExpected -ExpectedModel 'probe:model' -OllamaResponse $extraResponse
    $extraValueRejected = [bool]($extraResult.jsonParsed -and $extraResult.schemaPassed -and -not $extraResult.semanticPassed -and $extraResult.reasonCode -ceq 'response-semantic-mismatch')

    $truncatedResponse = [pscustomobject][ordered]@{model='probe:model';done_reason='length';message=[pscustomobject]@{content='{"token":"PASS"}';thinking=''};eval_count=[int]1}
    $truncatedResult = Test-AwxBenchmarkResponse -Case $shapeCase -Expected $shapeExpected -ExpectedModel 'probe:model' -OllamaResponse $truncatedResponse
    $truncationRejected = [bool](-not $truncatedResult.jsonParsed -and -not $truncatedResult.schemaPassed -and -not $truncatedResult.semanticPassed -and $truncatedResult.reasonCode -ceq 'response-truncated')
    $timeoutRejected = [bool]((Get-AwxBoundedReasonCode ([InvalidOperationException]::new('model-block-timeout'))) -ceq 'model-block-timeout')
    $missingHashRejected = [bool](-not(Test-AwxCompleteHashSet -Values @((Get-AwxUtf8Sha256 'present'),[int]7) -ExpectedCount 2))

    $orderRejected = $false
    try {
        $matrix = @(Get-AwxFamilyAModelIdentityMatrix)
        $blocks = @($matrix | ForEach-Object {[pscustomobject][ordered]@{modelTag=$_.modelTag;digest=$_.digest;blockEvidenceSha256=(Get-AwxUtf8Sha256 $_.modelTag)}})
        [array]::Reverse($blocks)
        [void](New-AwxRunBinding -BenchmarkId 'awx.desktop-ollama-benchmark.v2' -CorpusSha256 ('a' * 64) -OracleSha256 ('b' * 64) -OntologySha256 ('c' * 64) -SchemaSha256 ('d' * 64) -OptionsSha256 ('e' * 64) -ModelBlocks $blocks)
    } catch { $orderRejected = $_.Exception.Message -ceq 'benchmark-contract-invalid' }

    $requestCase = @($Corpus.cases | Where-Object id -CEQ 'fast-json-extract')[0]
    $beforeBody = New-AwxBenchmarkRequestBody -Case $requestCase -ModelTag 'probe:model' -Options $Corpus.options
    $mutatedOracle = $Oracle | ConvertTo-Json -Depth 100 | ConvertFrom-Json
    $mutatedOracle.cases[1].expected.gpu = 'ORACLE-SENTINEL-MUST-NOT-LEAK'
    $Oracle = $mutatedOracle
    $afterBody = New-AwxBenchmarkRequestBody -Case $requestCase -ModelTag 'probe:model' -Options $Corpus.options
    $requestParameters = @((Get-Command New-AwxBenchmarkRequestBody -CommandType Function).Parameters.Keys)
    $oracleIsolated = [bool]($beforeBody -ceq $afterBody -and $afterBody -cnotmatch 'ORACLE-SENTINEL-MUST-NOT-LEAK' -and
        $requestParameters -cnotcontains 'Oracle' -and $requestParameters -cnotcontains 'Expected')
    $p95Correct = [bool]((Get-AwxNearestRank -Values ([double[]](1..21)) -Percentile 0.95) -eq 20.0)

    return [pscustomobject][ordered]@{
        constRejected=[bool]$constRejected
        shapeOnlyRejected=[bool]$shapeOnlyRejected
        extraValueRejected=[bool]$extraValueRejected
        truncationRejected=[bool]$truncationRejected
        timeoutRejected=[bool]$timeoutRejected
        missingHashRejected=[bool]$missingHashRejected
        orderRejected=[bool]$orderRejected
        oracleIsolated=[bool]$oracleIsolated
        p95Correct=[bool]$p95Correct
    }
}

function ConvertTo-AwxLivePublicSample {
    param([Parameter(Mandatory)]$Sample,[Parameter(Mandatory)][string[]]$CaseIds,[switch]$Warm)
    $projection = if ($Warm) {
        [pscustomobject][ordered]@{
            caseId=$Sample.caseId;repetition=$Sample.repetition;latencyMs=$Sample.latencyMs
            requestSha256=$Sample.requestSha256;responseSha256=$Sample.responseSha256
            responseLength=$Sample.responseLength;tokenCount=$Sample.tokenCount
            transportPassed=$Sample.transportPassed;lineagePassed=$Sample.lineagePassed;jsonParsed=$Sample.jsonParsed
            schemaPassed=$Sample.schemaPassed;semanticPassed=$Sample.semanticPassed;thinkingLength=$Sample.thinkingLength
            doneReason=$Sample.doneReason;reasonCode=$Sample.reasonCode
        }
    } else {
        [pscustomobject][ordered]@{
            caseId=$Sample.caseId;latencyMs=$Sample.latencyMs
            requestSha256=$Sample.requestSha256;responseSha256=$Sample.responseSha256
            responseLength=$Sample.responseLength;tokenCount=$Sample.tokenCount
            transportPassed=$Sample.transportPassed;lineagePassed=$Sample.lineagePassed;jsonParsed=$Sample.jsonParsed
            schemaPassed=$Sample.schemaPassed;semanticPassed=$Sample.semanticPassed;thinkingLength=$Sample.thinkingLength
            doneReason=$Sample.doneReason;reasonCode=$Sample.reasonCode
        }
    }
    Assert-AwxPublicSample -Sample $projection -CaseIds $CaseIds -Warm ([bool]$Warm)
    return $projection
}

function Get-AwxUserBindingGuardHash {
    $names = @('LLM_FAST_MODEL','LLM_CHAT_MODEL','LLM_HIGH_MODEL','LLM_CODER_MODEL')
    $bindings = @($names | ForEach-Object {
        $value = [Environment]::GetEnvironmentVariable($_,[EnvironmentVariableTarget]::User)
        [ordered]@{
            name=$_
            present=[bool]($null -ne $value)
            valueSha256=$(if ($null -eq $value) {$null} else {Get-AwxUtf8Sha256 ([string]$value)})
        }
    })
    return Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-user-binding-guard.v2';bindings=$bindings}))
}

function Get-AwxLiveModelEvidence {
    param([Parameter(Mandatory)][object[]]$Models,[Parameter(Mandatory)][object[]]$Runs,[Parameter(Mandatory)][object[]]$DigestChecks)
    Assert-AwxBenchmarkModelMatrix -Models $Models
    if ($Runs.Count -ne 6 -or -not(Test-AwxExactSequence @($Runs.modelTag) @($Models.modelTag))) { throw 'benchmark-contract-invalid' }
    Assert-AwxFixedChecks $DigestChecks
    $working = [Collections.Generic.List[object]]::new()
    for ($index=0; $index -lt 6; $index++) {
        $model = $Models[$index]; $run = $Runs[$index]
        Assert-AwxExactPropertySet $run @('modelTag','role','classification','cold','warm','loaded','lane','endLoaded','endLane','endpointRestarted','blockElapsedMs','cleanup','blockFacts','blockEvidenceSha256')
        if ($run.modelTag -isnot [string] -or $run.modelTag -cne $model.modelTag -or $run.role -isnot [string] -or $run.role -cne $model.role -or
            $run.classification -isnot [string] -or $run.classification -cne $model.classification -or -not(Test-AwxStrictBoolean $run.endpointRestarted) -or
            -not(Test-AwxFiniteNumberRange $run.blockElapsedMs 0.0)) { throw 'benchmark-contract-invalid' }
        $replay = New-AwxBlockEvidence -BlockFacts $run.blockFacts
        if ($run.blockEvidenceSha256 -isnot [string] -or $run.blockEvidenceSha256 -cne $replay.blockEvidenceSha256) { throw 'benchmark-contract-invalid' }
        Assert-AwxLoadedAdapterEvidence -Evidence $run.loaded -ExpectedModelTag $model.modelTag
        Assert-AwxLoadedAdapterEvidence -Evidence $run.endLoaded -ExpectedModelTag $model.modelTag
        foreach ($lane in @($run.lane,$run.endLane)) {
            Assert-AwxExactPropertySet $lane @('passed','reasonCode','laneLabel','totalMiB','freeMiB')
            if (-not(Test-AwxStrictBoolean $lane.passed) -or $lane.reasonCode -isnot [string] -or $lane.reasonCode -cnotin @('pass','insufficient-vram','gpu-lane-evidence-incomplete') -or
                $lane.laneLabel -isnot [string] -or $lane.laneLabel -cne $model.gpuLane -or
                -not(Test-AwxStrictIntegerRange $lane.totalMiB 1 ([int]::MaxValue)) -or -not(Test-AwxStrictIntegerRange $lane.freeMiB 0 ([int]::MaxValue)) -or
                [int64]$lane.freeMiB -gt [int64]$lane.totalMiB) { throw 'benchmark-contract-invalid' }
        }
        if ([int]$run.lane.totalMiB -ne [int]$run.endLane.totalMiB) { throw 'benchmark-contract-invalid' }
        Assert-AwxExactPropertySet $run.cleanup @('psEmpty','gpuReleased','postUnloadGpu')
        if (-not(Test-AwxStrictBoolean $run.cleanup.psEmpty) -or -not(Test-AwxStrictBoolean $run.cleanup.gpuReleased)) { throw 'benchmark-contract-invalid' }
        Assert-AwxGpuSnapshotRows -Rows @($run.cleanup.postUnloadGpu)
        $expectedRatio = [Math]::Min([double]$run.loaded.gpuResidentRatio,[double]$run.endLoaded.gpuResidentRatio)
        $expectedFree = [Math]::Min([double]$run.lane.freeMiB,[double]$run.endLane.freeMiB)
        if ([double]$run.blockFacts.lane.gpuResidentRatio -ne $expectedRatio -or [double]$run.blockFacts.lane.loadedFreeMiB -ne $expectedFree -or
            $run.blockFacts.lane.lanePassed -ne ($run.lane.passed -and $run.endLane.passed) -or
            $run.blockFacts.cleanup.psEmpty -ne $run.cleanup.psEmpty -or $run.blockFacts.cleanup.gpuReleased -ne $run.cleanup.gpuReleased -or
            $run.blockFacts.cleanup.endpointRestarted -ne $run.endpointRestarted) { throw 'benchmark-contract-invalid' }

        $caseIds = $(if ($model.role -ceq 'fast') {@('fast-rewrite-ko','fast-json-extract','fast-instruction')} else {@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
        $cold = ConvertTo-AwxLivePublicSample -Sample $run.cold -CaseIds $caseIds
        $warm = @($run.warm | ForEach-Object { ConvertTo-AwxLivePublicSample -Sample $_ -CaseIds $caseIds -Warm })
        $expectedWarmIds = @($caseIds | ForEach-Object {$id=$_; 1..7 | ForEach-Object {$id}})
        $expectedRepetitions = @(1..7) + @(1..7) + @(1..7)
        if ($warm.Count -ne 21 -or -not(Test-AwxExactSequence @($warm.caseId) $expectedWarmIds) -or -not(Test-AwxExactSequence @($warm.repetition) $expectedRepetitions)) { throw 'benchmark-contract-invalid' }
        $hashes = @($cold.requestSha256,$cold.responseSha256) + @($warm | ForEach-Object {$_.requestSha256;$_.responseSha256})
        $hashesComplete = Test-AwxCompleteHashSet -Values ([object[]]$hashes) -ExpectedCount 44
        $metrics = Get-AwxModelMetrics -ColdResult $cold -WarmResults $warm -LoadedFreeMiB $expectedFree
        $allSamples = @($cold) + @($warm)
        $lineagePassed = @($allSamples | Where-Object {-not $_.lineagePassed}).Count -eq 0
        $caseSampleCountsPassed = @($caseIds | Where-Object {@($warm | Where-Object caseId -CEQ $_).Count -ne 7}).Count -eq 0
        $hardGateEvidence = [pscustomobject][ordered]@{
            digestPassed=$DigestChecks[$index].passed
            lanePassed=$run.blockFacts.lane.lanePassed
            lineagePassed=[bool]$lineagePassed
            hashesComplete=[bool]$hashesComplete
            completedColdCount=[int]$(if ($cold.transportPassed) {1} else {0})
            completedWarmCount=[int]@($warm | Where-Object transportPassed).Count
            caseSampleCountsPassed=[bool]$caseSampleCountsPassed
            warmJsonSchemaPassedCount=[int]@($warm | Where-Object schemaPassed).Count
            nonEmptyThinkingCount=[int]@($warm | Where-Object {[int]$_.thinkingLength -gt 0}).Count
            truncatedCount=[int]@($warm | Where-Object doneReason -CEQ 'length').Count
            gpuResidentRatio=[double]$run.blockFacts.lane.gpuResidentRatio
            loadedFreeMiB=[double]$run.blockFacts.lane.loadedFreeMiB
            endpointRestarted=$run.endpointRestarted
            timeoutCount=[int]@($warm | Where-Object {$_.reasonCode -cin @('model-block-timeout','portfolio-timeout')}).Count
        }
        $hardGate = Test-AwxModelHardGate -Role $model.role -Evidence $hardGateEvidence
        if (-not(Test-AwxStrictBoolean $hardGate.passed)) { throw 'benchmark-contract-invalid' }
        Assert-AwxHardGateReasonEnvelope $hardGate.passed $hardGate.reasonCodes
        $working.Add([pscustomobject][ordered]@{
            model=$model;run=$run;cold=$cold;warm=$warm;metrics=$metrics;hardGate=$hardGate
            hashesComplete=[bool]$hashesComplete;lineagePassed=[bool]$lineagePassed
            cleanupPassed=[bool]($run.cleanup.psEmpty -and $run.cleanup.gpuReleased)
            balancedScore=$null;scoreDelta=$null
        })
    }

    foreach ($role in @('fast','main')) {
        $eligible = @($working | Where-Object {$_.model.role -ceq $role -and $_.hardGate.passed})
        if ($eligible.Count -gt 0) {
            $minimumP95 = [double](($eligible | ForEach-Object {$_.metrics.warmRoleP95Ms} | Measure-Object -Minimum).Minimum)
            $maximumFree = [double](($eligible | ForEach-Object {$_.metrics.loadedFreeMiB} | Measure-Object -Maximum).Maximum)
            foreach ($item in $eligible) {
                $item.balancedScore = (Get-AwxRoleScore -Role $role -Metrics $item.metrics -MinEligibleWarmRoleP95Ms $minimumP95 -MaxEligibleLoadedFreeMiB $maximumFree).balancedScore
            }
        }
        $baseline = @($working | Where-Object {$_.model.role -ceq $role -and $_.model.classification -ceq 'baseline'})
        if ($baseline.Count -eq 1 -and $null -ne $baseline[0].balancedScore) {
            $baselineMicros = ConvertTo-AwxScoreMicrounits $baseline[0].balancedScore
            foreach ($item in @($working | Where-Object {$_.model.role -ceq $role -and $null -ne $_.balancedScore})) {
                $deltaMicros = (ConvertTo-AwxScoreMicrounits $item.balancedScore) - $baselineMicros
                $item.scoreDelta = [double]([decimal]$deltaMicros / [decimal]1000000)
            }
        }
    }

    $publicModels = @($working | ForEach-Object {
        $item=$_;$caseIds=$(if($item.model.role -ceq 'fast'){@('fast-rewrite-ko','fast-json-extract','fast-instruction')}else{@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
        [pscustomobject][ordered]@{
            role=$item.model.role;classification=$item.model.classification;modelTag=$item.model.modelTag;digest=$item.model.digest
            endpointLabel=$item.model.endpointLabel;gpuLane=$item.model.gpuLane
            totalMiB=[int]$item.run.lane.totalMiB;freeMiB=[int][Math]::Min($item.run.lane.freeMiB,$item.run.endLane.freeMiB)
            gpuResidentRatio=[double]$item.run.blockFacts.lane.gpuResidentRatio;coldLoadMs=[double]$item.metrics.coldLoadMs
            warmRoleP95Ms=[double]$item.metrics.warmRoleP95Ms;completedWarmCount=[int]$item.metrics.completedWarmCount
            balancedScore=$item.balancedScore;scoreDelta=$item.scoreDelta;hardGatePassed=$item.hardGate.passed;cleanupPassed=$item.cleanupPassed
            reasonCodes=@($item.hardGate.reasonCodes)
            caseTimings=@($caseIds | ForEach-Object {[pscustomobject][ordered]@{caseId=$_;p50Ms=[double]$item.metrics.warmCaseP50Ms.$_}})
            semanticRates=@($caseIds | ForEach-Object {[pscustomobject][ordered]@{caseId=$_;rate=[double]$item.metrics.semanticRates.$_}})
            coldResult=$item.cold;warmResults=@($item.warm)
        }
    })
    $scenarioModels = @($publicModels | ForEach-Object {[pscustomobject][ordered]@{
        role=$_.role;classification=$_.classification;modelTag=$_.modelTag;hardGatePassed=$_.hardGatePassed
        hardGateReasonCodes=@($_.reasonCodes);warmRoleP95Ms=$_.warmRoleP95Ms;loadedFreeMiB=[double]$_.freeMiB
        balancedScore=$_.balancedScore;semanticRates=@($_.semanticRates)
    }})
    $contractEvidence = [pscustomobject][ordered]@{
        contractValidated=$true
        digestChecks=@($DigestChecks)
        hashCompletenessChecks=@($working | ForEach-Object {[pscustomobject][ordered]@{modelTag=$_.model.modelTag;passed=$_.hashesComplete}})
        lineageChecks=@($working | ForEach-Object {[pscustomobject][ordered]@{modelTag=$_.model.modelTag;passed=$_.lineagePassed}})
    }
    return [pscustomobject][ordered]@{
        publicModels=$publicModels
        contractEvidence=$contractEvidence
        scenarioModels=$scenarioModels
        completedBlockChecks=@($working | ForEach-Object {[pscustomobject][ordered]@{modelTag=$_.model.modelTag;passed=[bool]($_.warm.Count -eq 21 -and $_.cleanupPassed)}})
        modelBlocks=@($working | ForEach-Object {[pscustomobject][ordered]@{modelTag=$_.model.modelTag;digest=$_.model.digest;blockEvidenceSha256=$_.run.blockEvidenceSha256}})
    }
}

function New-AwxLiveReport {
    param(
        [Parameter(Mandatory)]$CorpusProjection,
        [Parameter(Mandatory)]$DurationMs,[Parameter(Mandatory)][object[]]$Models,[Parameter(Mandatory)][object[]]$Recommendations,
        [Parameter(Mandatory)][object[]]$Packets,[Parameter(Mandatory)]$Neutral
    )
    if (-not(Test-AwxFiniteNumberRange $DurationMs 0.0)) { throw 'report-privacy-invalid' }
    $report = [pscustomobject][ordered]@{
        schemaVersion=[int]2;benchmarkId='awx.desktop-ollama-benchmark.v2';mode='live';durationMs=[Math]::Round([double]$DurationMs,3)
        corpus=$CorpusProjection
        models=@($Models);recommendations=@($Recommendations);packets=@($Packets);neutral=$Neutral
        verdict=$Neutral.finalVerdict;reasonCodes=@($Neutral.finalReasonCode)
    }
    Assert-AwxReportPrivacy $report
    return $report
}

function Get-AwxLiveRemainingSec {
    param([Parameter(Mandatory)]$Clock,[Parameter(Mandatory)][int]$PortfolioTimeoutSec,[Parameter(Mandatory)][int]$CapSec)
    $remaining = [Math]::Floor($PortfolioTimeoutSec - $Clock.Elapsed.TotalSeconds)
    if ($remaining -lt 1) { throw 'portfolio-timeout' }
    return [int][Math]::Min($remaining,$CapSec)
}

function Invoke-AwxLiveBenchmarkReport {
    param(
        [Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle,
        [Parameter(Mandatory)][string]$CorpusPath,[Parameter(Mandatory)][string]$OraclePath,
        [object[]]$Models=@(Get-AwxBenchmarkModelMatrix),[int]$PortfolioTimeoutSec=7200,[int]$BlockTimeoutSec=1800
    )
    $runClock = [Diagnostics.Stopwatch]::StartNew()
    $adapters = $null
    $frozenCorpusProjection = $null
    try {
        Assert-AwxBenchmarkContract -Corpus $Corpus -Oracle $Oracle
        $frozenCorpusProjection = New-AwxCorpusProjection -Corpus $Corpus -CorpusPath $CorpusPath -OraclePath $OraclePath
        if ($PortfolioTimeoutSec -lt 1) { throw 'portfolio-timeout' }
        if ($BlockTimeoutSec -lt 1) { throw 'model-block-timeout' }
        Assert-AwxBenchmarkModelMatrix -Models $Models
        $bindingGuardBefore = Get-AwxUserBindingGuardHash

        $adapters = New-AwxProductionAdapters
        $digestChecks = [Collections.Generic.List[object]]::new()
        foreach ($model in $Models) {
            $timeout = Get-AwxLiveRemainingSec -Clock $runClock -PortfolioTimeoutSec $PortfolioTimeoutSec -CapSec 15
            $installed = & $adapters.VerifyInstalled $model $timeout
            if ($null -eq $installed -or $null -eq $installed.PSObject.Properties['passed']) { throw 'benchmark-runtime-failed' }
            $passed = Get-AwxStrictAdapterBoolean -Value $installed.passed -ReasonCode 'benchmark-runtime-failed'
            $digestChecks.Add([pscustomobject][ordered]@{modelTag=$model.modelTag;passed=$passed})
            if (-not $passed) { throw 'candidate-digest-changed' }
        }
        foreach ($model in @($Models[0],$Models[3])) {
            $timeout = Get-AwxLiveRemainingSec -Clock $runClock -PortfolioTimeoutSec $PortfolioTimeoutSec -CapSec 15
            $empty = Get-AwxStrictAdapterBoolean -Value (& $adapters.PsEmpty $model $timeout) -ReasonCode 'model-unload-unproven'
            if (-not $empty) { throw 'model-unload-unproven' }
        }

        $portfolioRemaining = Get-AwxLiveRemainingSec -Clock $runClock -PortfolioTimeoutSec $PortfolioTimeoutSec -CapSec $PortfolioTimeoutSec
        $modelBlockRunner = {
            param($model,$portfolioClock,$portfolioLimit)
            Invoke-AwxModelBlock -Model $model -Corpus $Corpus -Oracle $Oracle -Adapters $adapters -PortfolioClock $portfolioClock -BlockTimeoutSec $BlockTimeoutSec -PortfolioTimeoutSec $portfolioLimit
        }.GetNewClosure()
        $emergencyUnloader = { param($model,$timeout) & $adapters.Unload $model $timeout }.GetNewClosure()
        $runs = @(Invoke-AwxBenchmarkPortfolio -Models $Models -ModelBlockRunner $modelBlockRunner -EmergencyUnloader $emergencyUnloader -PortfolioTimeoutSec $portfolioRemaining)
        [void](Get-AwxLiveRemainingSec -Clock $runClock -PortfolioTimeoutSec $PortfolioTimeoutSec -CapSec 15)
        Assert-AwxFrozenInputProjection -FrozenProjection $frozenCorpusProjection -Corpus $Corpus -Oracle $Oracle -CorpusPath $CorpusPath -OraclePath $OraclePath

        $evidence = Get-AwxLiveModelEvidence -Models $Models -Runs $runs -DigestChecks @($digestChecks)
        $recommendations = @(
            Select-AwxRoleRecommendation -Role fast -ModelRows @(Get-AwxScenarioSelectorRows -Models @($evidence.publicModels) -Role fast)
            Select-AwxRoleRecommendation -Role main -ModelRows @(Get-AwxScenarioSelectorRows -Models @($evidence.publicModels) -Role main)
        )
        $scenarioEvidence = [pscustomobject][ordered]@{models=@($evidence.scenarioModels);recommendations=$recommendations}
        $runBinding = New-AwxRunBinding -BenchmarkId 'awx.desktop-ollama-benchmark.v2' -CorpusSha256 $frozenCorpusProjection.corpusSha256 -OracleSha256 $frozenCorpusProjection.oracleSha256 -OntologySha256 $frozenCorpusProjection.ontologySha256 -SchemaSha256 $frozenCorpusProjection.schemaSha256 -OptionsSha256 $frozenCorpusProjection.optionsSha256 -ModelBlocks @($evidence.modelBlocks)
        $falsifyEvidence = Invoke-AwxFalsificationProbes -Corpus $Corpus -Oracle $Oracle
        $packets = @(New-AwxEvidencePackets -RunBinding $runBinding -ContractEvidence $evidence.contractEvidence -ScenarioEvidence $scenarioEvidence -FalsifyEvidence $falsifyEvidence)

        $finalEmptyChecks = [Collections.Generic.List[object]]::new()
        foreach ($entry in @(
            [pscustomobject]@{endpointLabel='fast-11435';model=$Models[0]},
            [pscustomobject]@{endpointLabel='primary-11434';model=$Models[3]}
        )) {
            $timeout = Get-AwxLiveRemainingSec -Clock $runClock -PortfolioTimeoutSec $PortfolioTimeoutSec -CapSec 15
            $empty = Get-AwxStrictAdapterBoolean -Value (& $adapters.PsEmpty $entry.model $timeout) -ReasonCode 'model-unload-unproven'
            $finalEmptyChecks.Add([pscustomobject][ordered]@{endpointLabel=$entry.endpointLabel;passed=$empty})
        }
        $bindingGuardAfter = Get-AwxUserBindingGuardHash
        $provisionalFacts = [pscustomobject][ordered]@{
            digestChecks=@($digestChecks)
            completedBlockChecks=@($evidence.completedBlockChecks)
            finalEmptyPsChecks=@($finalEmptyChecks)
            privacyProjectionChecked=$false
            privacyProjectionPassed=$false
            bindingGuardChecked=$true
            bindingGuardUnchanged=[bool]($bindingGuardBefore -ceq $bindingGuardAfter)
        }

        $provisionalCommand = New-AwxCommandEvidence -RunBinding $runBinding -Evidence $provisionalFacts
        $provisionalNeutral = Get-AwxOrderStableNeutralVerdict -Packets $packets -CommandEvidence $provisionalCommand
        $publicPackets = @($packets | ForEach-Object {ConvertTo-AwxPublicPacket $_})
        $provisionalPublicNeutral = ConvertTo-AwxPublicNeutral $provisionalNeutral
        [void](New-AwxLiveReport -CorpusProjection $frozenCorpusProjection -DurationMs $runClock.Elapsed.TotalMilliseconds -Models @($evidence.publicModels) -Recommendations $recommendations -Packets $publicPackets -Neutral $provisionalPublicNeutral)

        $finalFacts = [pscustomobject][ordered]@{
            digestChecks=@($digestChecks)
            completedBlockChecks=@($evidence.completedBlockChecks)
            finalEmptyPsChecks=@($finalEmptyChecks)
            privacyProjectionChecked=$true
            privacyProjectionPassed=$true
            bindingGuardChecked=$true
            bindingGuardUnchanged=[bool]($bindingGuardBefore -ceq $bindingGuardAfter)
        }
        $command = New-AwxCommandEvidence -RunBinding $runBinding -Evidence $finalFacts
        $neutral = Get-AwxOrderStableNeutralVerdict -Packets $packets -CommandEvidence $command
        $publicNeutral = ConvertTo-AwxPublicNeutral $neutral
        Assert-AwxFrozenInputProjection -FrozenProjection $frozenCorpusProjection -Corpus $Corpus -Oracle $Oracle -CorpusPath $CorpusPath -OraclePath $OraclePath
        $finalReport = New-AwxLiveReport -CorpusProjection $frozenCorpusProjection -DurationMs $runClock.Elapsed.TotalMilliseconds -Models @($evidence.publicModels) -Recommendations $recommendations -Packets $publicPackets -Neutral $publicNeutral
        [void](Get-AwxLiveRemainingSec -Clock $runClock -PortfolioTimeoutSec $PortfolioTimeoutSec -CapSec 15)
        return $finalReport
    } catch {
        $reasonCode = Get-AwxBoundedReasonCode $_.Exception
        if ($null -ne $adapters) {
            $cleanupFailed = $false
            foreach ($model in @(Get-AwxBenchmarkModelMatrix)) {
                try { [void](& $adapters.Unload $model 30) } catch { $cleanupFailed = $true }
            }
            foreach ($model in @((Get-AwxBenchmarkModelMatrix)[0],(Get-AwxBenchmarkModelMatrix)[3])) {
                try {
                    $empty = Get-AwxStrictAdapterBoolean -Value (& $adapters.PsEmpty $model 15) -ReasonCode 'model-unload-unproven'
                    if (-not $empty) { $cleanupFailed = $true }
                } catch { $cleanupFailed = $true }
            }
            if ($cleanupFailed) { $reasonCode = 'model-unload-unproven' }
        }
        $runClock.Stop()
        if ($null -ne $frozenCorpusProjection) {
            return New-AwxLiveFailureReportFromProjection -CorpusProjection $frozenCorpusProjection -DurationMs $runClock.Elapsed.TotalMilliseconds -ReasonCode $reasonCode
        }
        return New-AwxLiveFailureReport -Corpus $Corpus -CorpusPath $CorpusPath -OraclePath $OraclePath -DurationMs $runClock.Elapsed.TotalMilliseconds -ReasonCode $reasonCode
    } finally {
        if ($runClock.IsRunning) { $runClock.Stop() }
    }
}

Export-ModuleMember -Function @(
    'ConvertTo-AwxCanonicalJson','Get-AwxUtf8Sha256','Get-AwxFileSha256','Test-AwxExactSequence','Test-AwxStrictBoolean',
    'Test-AwxStrictIntegerRange','Test-AwxFiniteNumberRange','Assert-AwxExactPropertySet','Assert-AwxHash64',
    'Assert-AwxLoopbackEndpoint','Assert-AwxBenchmarkContract',
    'Assert-AwxOutboundBodyClean','New-AwxBenchmarkRequestBody','Test-AwxJsonSchemaValue','Test-AwxSemanticEnvelope','Test-AwxBenchmarkResponse',
    'Get-AwxNearestRank','Get-AwxModelMetrics','Test-AwxModelHardGate','Get-AwxRoleScore','Select-AwxRoleRecommendation',
    'Get-AwxFamilyAModelIdentityMatrix','New-AwxBlockEvidence','New-AwxRunBinding','New-AwxEvidencePackets','New-AwxCommandEvidence',
    'Get-AwxNeutralVerdict','Get-AwxOrderStableNeutralVerdict','ConvertTo-AwxPublicPacket','ConvertTo-AwxPublicNeutral','Invoke-AwxOllamaHttp',
    'Get-AwxOllamaTags','Test-AwxInstalledDigest','ConvertFrom-AwxPsResponse','Get-AwxOllamaPs','Get-AwxLoadedModelEvidence',
    'Get-AwxGpuSnapshot','Get-AwxEndpointProcessIds','Get-AwxGpuComputeRows','Test-AwxGpuLane','Test-AwxGpuRelease','Stop-AwxOllamaModel',
    'Enter-AwxBenchmarkLease','Write-AwxJsonAtomic','Assert-AwxNoOverlappingModelBlocks','Invoke-AwxModelBlock','Invoke-AwxBenchmarkPortfolio',
    'Get-AwxBoundedReasonCode','Get-AwxBenchmarkModelMatrix',
    'Assert-AwxPublicPacketProjection','Assert-AwxPublicNeutralProjection','Assert-AwxReportPrivacy','New-AwxValidateOnlyReport',
    'New-AwxLiveFailureReport','Invoke-AwxFalsificationProbes','Invoke-AwxLiveBenchmarkReport'
)
