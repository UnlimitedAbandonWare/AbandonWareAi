Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

BeforeAll {
    $script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $script:CorpusPath = Join-Path $script:RepoRoot 'scripts\config\desktop-ollama-model-benchmark.json'
    $script:OraclePath = Join-Path $script:RepoRoot 'scripts\config\desktop-ollama-model-benchmark-oracle.json'
}
Describe 'benchmark v2 configuration' {
    It 'keeps request corpus and scorer oracle physically separate' {
        Test-Path -LiteralPath $script:CorpusPath | Should -BeTrue
        Test-Path -LiteralPath $script:OraclePath | Should -BeTrue
        $requestText = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8
        $requestText | Should -Not -Match '(?i)"(expected|oracle|correctAnswer)"\s*:'
    }

    It 'pins the exact six request case ids and deterministic options' {
        $corpus = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $corpus.schemaVersion | Should -Be 2
        $corpus.corpusId | Should -BeExactly 'awx.desktop-ollama-benchmark.v2'
        @($corpus.cases.id) | Should -Be @(
            'fast-rewrite-ko','fast-json-extract','fast-instruction',
            'main-grounded-ko','main-uncertainty-ko','main-policy-ko'
        )
        @($corpus.cases.prompt) | Should -Be @(
            "다음 문장을 의미를 유지한 한 문장으로 간결하게 고쳐라: 시스템은 모델을 다운로드한 뒤 검증을 통과한 경우에만 기본값을 바꾼다. rewrite에는 고친 문장을, meaningUnits에는 문장이 보존한 의미 단위를 선택해라.",
            "문장 'RTX 3060은 fast 역할이고 포트는 11435다'에서 gpu, role, port를 추출하라.",
            "오직 PASS라는 의미를 token field로 표현하라.",
            "근거 [A]: qwen3.5:9b는 3060 후보이다. 근거 [B]: 승격 전 GPU 적재를 확인한다. 근거만 사용해 summary를 작성하고, claims에는 각 주장과 그 근거 label을 연결하라.",
            "제공된 근거에는 RTX 3060의 실제 VRAM 용량이 없다. evidenceState, answerKind, value, unit, reasonCodes로 답하라. 근거가 지원하는 숫자만 value에 넣어라.",
            "다운로드 성공, CPU offload 발견, 기준선보다 품질 하락이라는 조건에서 승격 결정을 decision과 reasonCodes로 답하라."
        )
        $corpus.options.temperature | Should -Be 0
        $corpus.options.seed | Should -Be 42
        $corpus.options.num_ctx | Should -Be 8192
        $corpus.options.num_predict | Should -Be 192
        $corpus.options.stream | Should -BeFalse
        $corpus.options.think | Should -BeFalse
        $corpus.options.keep_alive | Should -BeExactly '5m'
    }

    It 'pins a matching scorer-only oracle case set' {
        $corpus = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $oracle = Get-Content -LiteralPath $script:OraclePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $oracle.schemaVersion | Should -Be 2
        $oracle.corpusId | Should -BeExactly $corpus.corpusId
        @($oracle.cases.id) | Should -Be @($corpus.cases.id)
        @($oracle.cases | Where-Object { $null -eq $_.expected }).Count | Should -Be 0
    }
}

Describe 'benchmark v2 request contract' {
    BeforeAll {
        $script:ModulePath = Join-Path $script:RepoRoot 'scripts\modules\DesktopOllamaModelTools.psm1'
        Import-Module $script:ModulePath -Force
        $script:Corpus = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $script:Oracle = Get-Content -LiteralPath $script:OraclePath -Raw -Encoding UTF8 | ConvertFrom-Json
    }

    It 'accepts the approved corpus and oracle' {
        { Assert-AwxBenchmarkContract -Corpus $script:Corpus -Oracle $script:Oracle } | Should -Not -Throw
    }

    It 'rejects const, single enums, reordered ontologies, and answer cardinality' {
        $coerciveVersion = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $coerciveVersion.schemaVersion = '2'
        { Assert-AwxBenchmarkContract -Corpus $coerciveVersion -Oracle $script:Oracle } | Should -Throw '*benchmark-contract-invalid*'

        $extraRootCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $extraRootCorpus | Add-Member -NotePropertyName undeclaredRoot -NotePropertyValue $true
        { Assert-AwxBenchmarkContract -Corpus $extraRootCorpus -Oracle $script:Oracle } | Should -Throw '*benchmark-contract-invalid*'

        $extraCaseCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $extraCaseCorpus.cases[0] | Add-Member -NotePropertyName undeclaredCase -NotePropertyValue $true
        { Assert-AwxBenchmarkContract -Corpus $extraCaseCorpus -Oracle $script:Oracle } | Should -Throw '*benchmark-contract-invalid*'

        $constCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $constCorpus.cases[0].responseSchema | Add-Member -NotePropertyName const -NotePropertyValue 'leak'
        { Assert-AwxBenchmarkContract -Corpus $constCorpus -Oracle $script:Oracle } | Should -Throw '*const-forbidden*'

        $singleCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $singleCorpus.cases[1].responseSchema.properties.gpu.enum = @('RTX 3060')
        { Assert-AwxBenchmarkContract -Corpus $singleCorpus -Oracle $script:Oracle } | Should -Throw '*single-valued-enum*'

        $reorderedCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $reorderedCorpus.cases[1].responseSchema.properties.gpu.enum = @('RTX 3090','RTX 3060')
        { Assert-AwxBenchmarkContract -Corpus $reorderedCorpus -Oracle $script:Oracle } | Should -Throw '*ontology-narrowed*'

        $cardinalityCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $cardinalityCorpus.cases[0].responseSchema.properties.meaningUnits.minItems = 3
        $cardinalityCorpus.cases[0].responseSchema.properties.meaningUnits.maxItems = 3
        { Assert-AwxBenchmarkContract -Corpus $cardinalityCorpus -Oracle $script:Oracle } | Should -Throw '*answer-cardinality-leaked*'

        $casingVariantCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $casingVariantCorpus.cases[0] | Add-Member -NotePropertyName Expected -NotePropertyValue 'ORACLE_SENTINEL_NEVER_SEND'
        { Assert-AwxBenchmarkContract -Corpus $casingVariantCorpus -Oracle $script:Oracle } | Should -Throw '*request-body-oracle-contamination*'

    }

    It 'keeps oracle-only mutations out of the outbound request' {
        $case = $script:Corpus.cases | Where-Object id -eq 'fast-json-extract'
        $body1 = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options
        $mutatedOracle = $script:Oracle | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $mutatedOracle.cases[1].expected.gpu = 'ORACLE_SENTINEL_NEVER_SEND'
        $body2 = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options
        (Get-AwxUtf8Sha256 $body1) | Should -BeExactly (Get-AwxUtf8Sha256 $body2)
        $body2 | Should -Not -Match 'ORACLE_SENTINEL_NEVER_SEND|"expected"|"oracle"'
        { Assert-AwxOutboundBodyClean '{"model":"qwen3.5:9b","expected":"ORACLE_SENTINEL_NEVER_SEND"}' } | Should -Throw '*request-body-oracle-contamination*'
    }

    It 'places the schema in format and maps options to the native Ollama shape' {
        $case = $script:Corpus.cases | Where-Object id -eq 'fast-json-extract'
        $body = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options | ConvertFrom-Json
        $body.model | Should -BeExactly 'qwen3.5:9b'
        $body.messages[0].content | Should -BeExactly $case.prompt
        $body.format.properties.gpu.enum | Should -Be @('RTX 3060','RTX 3090')
        $body.stream | Should -BeFalse
        $body.think | Should -BeFalse
        $body.keep_alive | Should -BeExactly '5m'
        $body.options.seed | Should -Be 42
        $body.options.num_ctx | Should -Be 8192
        $rawBody = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options
        $rawBody | Should -Match '"messages":\[\{'
        $rawBody | Should -Match '"required":\["gpu","role","port"\]'
        $singleRequiredCase = $script:Corpus.cases | Where-Object id -eq 'fast-instruction'
        (New-AwxBenchmarkRequestBody -Case $singleRequiredCase -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options) | Should -Match '"required":\["token"\]'
    }

    It 'changes the request hash when the response ontology changes' {
        $case1 = $script:Corpus.cases | Where-Object id -eq 'fast-json-extract'
        $case2 = $case1 | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $case2.responseSchema.properties.gpu.enum = @('RTX 3060','RTX 3090','RTX FUTURE')
        $hash1 = Get-AwxUtf8Sha256 (New-AwxBenchmarkRequestBody -Case $case1 -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options)
        $hash2 = Get-AwxUtf8Sha256 (New-AwxBenchmarkRequestBody -Case $case2 -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options)
        $hash2 | Should -Not -Be $hash1
    }
}

Describe 'typed envelope and semantic scoring' {
    BeforeAll {
        $fastCase = $script:Corpus.cases | Where-Object id -eq 'fast-json-extract'
        $fastExpected = ($script:Oracle.cases | Where-Object id -eq 'fast-json-extract').expected
        $policyCase = $script:Corpus.cases | Where-Object id -eq 'main-policy-ko'
        $policyExpected = ($script:Oracle.cases | Where-Object id -eq 'main-policy-ko').expected
    }

    It 'passes a bounded-ontology response with correct hidden semantics' {
        $native = [pscustomobject]@{
            model = 'qwen3.5:9b'; done_reason = 'stop'
            message = [pscustomobject]@{ thinking = ''; content = '{"gpu":"RTX 3060","role":"fast","port":11435}' }
        }
        $result = Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $native
        $result.jsonParsed | Should -BeTrue
        $result.schemaPassed | Should -BeTrue
        $result.semanticPassed | Should -BeTrue
        $result.PSObject.Properties.Name | Should -Not -Contain 'content'
    }

    It 'keeps shape-only JSON as a semantic mismatch' {
        $native = [pscustomobject]@{
            model = 'gemma4:12b'; done_reason = 'stop'
            message = [pscustomobject]@{ thinking = ''; content = '{"gpu":"RTX 3090","role":"main","port":11435}' }
        }
        $result = Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'gemma4:12b' -OllamaResponse $native
        $result.jsonParsed | Should -BeTrue
        $result.schemaPassed | Should -BeTrue
        $result.semanticPassed | Should -BeFalse
        $result.reasonCode | Should -BeExactly 'response-semantic-mismatch'
    }

    It 'rejects invalid envelopes and bounds missing or unknown done reasons for public reporting' {
        $badJson = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';message=[pscustomobject]@{thinking='';content='not-json'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $badJson).reasonCode | Should -BeExactly 'response-json-invalid'
        $extra = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3060","role":"fast","port":11435,"extra":true}'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $extra).reasonCode | Should -BeExactly 'response-schema-invalid'
        $thinking = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';message=[pscustomobject]@{thinking='hidden';content='{"gpu":"RTX 3060","role":"fast","port":11435}'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $thinking).reasonCode | Should -BeExactly 'lineage-missing'
        $truncated = [pscustomobject]@{model='qwen3.5:9b';done_reason='length';message=[pscustomobject]@{thinking='';content='{}'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $truncated).reasonCode | Should -BeExactly 'response-truncated'
        $missingDone = [pscustomobject]@{model='qwen3.5:9b';message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3060","role":"fast","port":11435}'}}
        $unknownDone = [pscustomobject]@{model='qwen3.5:9b';done_reason='future-terminal';message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3060","role":"fast","port":11435}'}}
        $module = Get-Module DesktopOllamaModelTools
        foreach ($native in @($missingDone,$unknownDone)) {
            $bounded = Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $native
            $bounded.reasonCode | Should -BeExactly 'lineage-missing'
            $bounded.doneReason | Should -BeExactly 'unknown'
            $bounded.lineagePassed | Should -BeFalse
            $sample = [pscustomobject][ordered]@{
                caseId='fast-json-extract';repetition=[int]1;latencyMs=[double]1
                requestSha256=('a' * 64);responseSha256=$bounded.responseSha256
                responseLength=$bounded.responseLength;tokenCount=$bounded.tokenCount
                transportPassed=$bounded.transportPassed;lineagePassed=$bounded.lineagePassed
                jsonParsed=$bounded.jsonParsed;schemaPassed=$bounded.schemaPassed;semanticPassed=$bounded.semanticPassed
                thinkingLength=$bounded.thinkingLength;doneReason=$bounded.doneReason;reasonCode=$bounded.reasonCode
            }
            $public = & $module { param($Sample) ConvertTo-AwxLivePublicSample -Sample $Sample -CaseIds @('fast-json-extract') -Warm } $sample
            $public.doneReason | Should -BeExactly 'unknown'
            { & $module { param($Sample) Assert-AwxPublicSample -Sample $Sample -CaseIds @('fast-json-extract') -Warm $true } $public } | Should -Not -Throw
            $wrongPair = $public | ConvertTo-Json -Depth 10 | ConvertFrom-Json
            $wrongPair.reasonCode = 'response-schema-invalid'
            { & $module { param($Sample) Assert-AwxPublicSample -Sample $Sample -CaseIds @('fast-json-extract') -Warm $true } $wrongPair } | Should -Throw '*report-privacy-invalid*'
            $wrongLineage = $public | ConvertTo-Json -Depth 10 | ConvertFrom-Json
            $wrongLineage.lineagePassed = $true
            { & $module { param($Sample) Assert-AwxPublicSample -Sample $Sample -CaseIds @('fast-json-extract') -Warm $true } $wrongLineage } | Should -Throw '*report-privacy-invalid*'
        }
        foreach ($evalCase in @(
            [pscustomobject]@{value=[int64]8;valid=$true},
            [pscustomobject]@{value=[double]1e100;valid=$false},
            [pscustomobject]@{value=[int]-1;valid=$false},
            [pscustomobject]@{value=[double]1.5;valid=$false}
        )) {
            $evalResponse = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';eval_count=$evalCase.value;message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3060","role":"fast","port":11435}'}}
            $holder = [pscustomobject]@{value=$null}
            { $holder.value = Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $evalResponse } | Should -Not -Throw
            if ($evalCase.valid) { $holder.value.tokenCount | Should -Be 8 }
            else { $holder.value.tokenCount | Should -BeNullOrEmpty }
        }
        $booleanPort = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3060","role":"fast","port":true}'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $booleanPort).reasonCode | Should -BeExactly 'response-schema-invalid'
    }

    It 'compares semantic arrays as exact order-independent sets and rejects extras' {
        $pass = [pscustomobject]@{
            model='qwen3.6:27b';done_reason='stop'
            message=[pscustomobject]@{thinking='';content='{"decision":"REJECT","reasonCodes":["QUALITY_REGRESSION","CPU_OFFLOAD"]}'}
        }
        (Test-AwxBenchmarkResponse -Case $policyCase -Expected $policyExpected -ExpectedModel 'qwen3.6:27b' -OllamaResponse $pass).semanticPassed | Should -BeTrue
        $extra = [pscustomobject]@{
            model='qwen3.6:27b';done_reason='stop'
            message=[pscustomobject]@{thinking='';content='{"decision":"REJECT","reasonCodes":["QUALITY_REGRESSION","CPU_OFFLOAD","LINEAGE_MISSING"]}'}
        }
        (Test-AwxBenchmarkResponse -Case $policyCase -Expected $policyExpected -ExpectedModel 'qwen3.6:27b' -OllamaResponse $extra).semanticPassed | Should -BeFalse
    }
}

Describe 'Family A statistics, gates, binding, and adjudication' {
    BeforeAll {
        $script:FamilyAMatrix = @(Get-AwxFamilyAModelIdentityMatrix)
        $script:FamilyABlockEnvelopes = @(
            for ($index = 0; $index -lt $script:FamilyAMatrix.Count; $index++) {
                $model = $script:FamilyAMatrix[$index]
                $caseIds = $(if ($model.role -ceq 'fast') {
                    @('fast-rewrite-ko','fast-json-extract','fast-instruction')
                } else {
                    @('main-grounded-ko','main-uncertainty-ko','main-policy-ko')
                })
                $cold = [pscustomobject][ordered]@{
                    caseId=$caseIds[0];requestSha256=Get-AwxUtf8Sha256 ("request|{0}|cold" -f $model.modelTag)
                    responseSha256=Get-AwxUtf8Sha256 ("response|{0}|cold" -f $model.modelTag);latencyMs=[double](100 + $index)
                    transportPassed=$true;lineagePassed=$true;jsonParsed=$true;schemaPassed=$true;semanticPassed=$true;reasonCode='pass'
                }
                $warm = foreach ($caseId in $caseIds) {
                    foreach ($repetition in 1..7) {
                        [pscustomobject][ordered]@{
                            caseId=$caseId;repetition=$repetition
                            requestSha256=Get-AwxUtf8Sha256 ("request|{0}|{1}|{2}" -f $model.modelTag,$caseId,$repetition)
                            responseSha256=Get-AwxUtf8Sha256 ("response|{0}|{1}|{2}" -f $model.modelTag,$caseId,$repetition)
                            latencyMs=[double](200 + $index + $repetition);transportPassed=$true;lineagePassed=$true
                            jsonParsed=$true;schemaPassed=$true;semanticPassed=$true;reasonCode='pass'
                        }
                    }
                }
                $facts = [pscustomobject][ordered]@{
                    modelTag=$model.modelTag;role=$model.role;classification=$model.classification;cold=$cold;warm=@($warm)
                    lane=[pscustomobject][ordered]@{gpuResidentRatio=0.99;loadedFreeMiB=[double]$(if($model.role -ceq 'fast'){1536}else{2048});lanePassed=$true}
                    cleanup=[pscustomobject][ordered]@{psEmpty=$true;gpuReleased=$true;endpointRestarted=$false}
                }
                New-AwxBlockEvidence -BlockFacts $facts
            }
        )
        $blocks = for ($index = 0; $index -lt 6; $index++) {
            [pscustomobject][ordered]@{
                modelTag=$script:FamilyAMatrix[$index].modelTag;digest=$script:FamilyAMatrix[$index].digest
                blockEvidenceSha256=$script:FamilyABlockEnvelopes[$index].blockEvidenceSha256
            }
        }
        $script:FamilyARun = New-AwxRunBinding -BenchmarkId 'awx.desktop-ollama-benchmark.v2' `
            -CorpusSha256 (Get-AwxUtf8Sha256 'corpus-v2') -OracleSha256 (Get-AwxUtf8Sha256 'oracle-v2') `
            -OntologySha256 (Get-AwxUtf8Sha256 'ontology-v2') -SchemaSha256 (Get-AwxUtf8Sha256 'schema-v2') `
            -OptionsSha256 (Get-AwxUtf8Sha256 'options-v2') -ModelBlocks @($blocks)
        $checks = @($script:FamilyAMatrix | ForEach-Object {[pscustomobject][ordered]@{modelTag=$_.modelTag;passed=$true}})
        $script:FamilyAContract = [pscustomobject][ordered]@{
            contractValidated=$true;digestChecks=$checks;hashCompletenessChecks=$checks;lineageChecks=$checks
        }
        $scenarioModels = @($script:FamilyAMatrix | ForEach-Object {
            $ids = $(if ($_.role -ceq 'fast') {@('fast-rewrite-ko','fast-json-extract','fast-instruction')} else {@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
            [pscustomobject][ordered]@{
                role=$_.role;classification=$_.classification;modelTag=$_.modelTag;hardGatePassed=$true;hardGateReasonCodes=@('pass')
                warmRoleP95Ms=[double](1000 + [array]::IndexOf($script:FamilyAMatrix,$_));loadedFreeMiB=[double]$(if($_.role -ceq 'fast'){1536}else{2048})
                balancedScore=[double](80 + [array]::IndexOf($script:FamilyAMatrix,$_))
                semanticRates=@($ids | ForEach-Object {[pscustomobject][ordered]@{caseId=$_;rate=1.0}})
            }
        })
        $script:FamilyAScenario = [pscustomobject][ordered]@{
            models=$scenarioModels
            recommendations=@(
                [pscustomobject][ordered]@{role='fast';status='keep-baseline';modelTag='qwen3:8b';scoreDelta=0.0;reasonCode='no-eligible-candidate'},
                [pscustomobject][ordered]@{role='main';status='keep-baseline';modelTag='gemma4:26b';scoreDelta=0.0;reasonCode='no-eligible-candidate'}
            )
        }
        $script:FamilyAFalsify = [pscustomobject][ordered]@{
            constRejected=$true;shapeOnlyRejected=$true;extraValueRejected=$true;truncationRejected=$true;timeoutRejected=$true
            missingHashRejected=$true;orderRejected=$true;oracleIsolated=$true;p95Correct=$true
        }
        $script:FamilyAPackets = @(New-AwxEvidencePackets -RunBinding $script:FamilyARun `
            -ContractEvidence $script:FamilyAContract -ScenarioEvidence $script:FamilyAScenario -FalsifyEvidence $script:FamilyAFalsify)
        $script:FamilyACommandFacts = [pscustomobject][ordered]@{
            digestChecks=$checks;completedBlockChecks=$checks
            finalEmptyPsChecks=@(
                [pscustomobject][ordered]@{endpointLabel='fast-11435';passed=$true},
                [pscustomobject][ordered]@{endpointLabel='primary-11434';passed=$true}
            )
            privacyProjectionChecked=$true;privacyProjectionPassed=$true;bindingGuardChecked=$true;bindingGuardUnchanged=$true
        }
        $script:FamilyACommand = New-AwxCommandEvidence -RunBinding $script:FamilyARun -Evidence $script:FamilyACommandFacts
    }

    It 'uses warm-only nearest-rank aggregation with the exact case multiset' {
        (Get-AwxNearestRank -Values (1..21) -Percentile 0.95) | Should -Be 20
        (Get-AwxNearestRank -Values (1..7) -Percentile 0.50) | Should -Be 4
        $warm = foreach ($caseId in @('fast-rewrite-ko','fast-json-extract','fast-instruction')) {
            1..7 | ForEach-Object {[pscustomobject]@{caseId=$caseId;repetition=$_;latencyMs=[double](($_-1)*3 + [array]::IndexOf(@('fast-rewrite-ko','fast-json-extract','fast-instruction'),$caseId)+1);transportPassed=$true;lineagePassed=$true;jsonParsed=$true;schemaPassed=$true;semanticPassed=$true}}
        }
        $metrics = Get-AwxModelMetrics -ColdResult ([pscustomobject]@{ latencyMs = 999999 }) -WarmResults $warm -LoadedFreeMiB 2048
        $metrics.coldLoadMs | Should -Be 999999
        $metrics.warmRoleP95Ms | Should -Be 20
        @($metrics.warmLatenciesMs) | Should -Not -Contain 999999
        $metrics.completedWarmCount | Should -Be 21
        $roundRobin = foreach ($repetition in 1..7) { foreach ($caseId in @('fast-rewrite-ko','fast-json-extract','fast-instruction')) { @($warm | Where-Object {$_.caseId -ceq $caseId -and $_.repetition -eq $repetition})[0] } }
        (Get-AwxModelMetrics -ColdResult ([pscustomobject]@{latencyMs=999999}) -WarmResults $roundRobin -LoadedFreeMiB 2048).warmRoleP95Ms | Should -Be 20
        $misrouted = @($warm | ForEach-Object { $_ | Select-Object * })
        $misrouted[20].caseId = 'fast-rewrite-ko'
        { Get-AwxModelMetrics -ColdResult ([pscustomobject]@{latencyMs=1}) -WarmResults $misrouted -LoadedFreeMiB 2048 } | Should -Throw '*warm-sample-insufficient*'
        $duplicate = @($warm | ForEach-Object { $_ | Select-Object * });$duplicate[20].caseId='fast-instruction';$duplicate[20].repetition=6
        { Get-AwxModelMetrics -ColdResult ([pscustomobject]@{latencyMs=1}) -WarmResults $duplicate -LoadedFreeMiB 2048 } | Should -Throw '*warm-sample-insufficient*'
        $coercive = @($warm | ForEach-Object { $_ | Select-Object * });$coercive[0].repetition='1'
        { Get-AwxModelMetrics -ColdResult ([pscustomobject]@{latencyMs=1}) -WarmResults $coercive -LoadedFreeMiB 2048 } | Should -Throw '*warm-sample-insufficient*'
    }

    It 'enforces strict hard-gate types, ranges, counts, and exact thresholds' {
        $fast = [pscustomobject][ordered]@{digestPassed=$true;lanePassed=$true;lineagePassed=$true;hashesComplete=$true;completedColdCount=1;completedWarmCount=21;caseSampleCountsPassed=$true;warmJsonSchemaPassedCount=21;nonEmptyThinkingCount=0;truncatedCount=0;gpuResidentRatio=0.99;loadedFreeMiB=1536.0;endpointRestarted=$false;timeoutCount=0}
        (Test-AwxModelHardGate -Role fast -Evidence $fast).passed | Should -BeTrue
        $main = $fast | ConvertTo-Json -Depth 10 | ConvertFrom-Json; $main.loadedFreeMiB=2048.0
        (Test-AwxModelHardGate -Role main -Evidence $main).passed | Should -BeTrue
        $belowRatio = $fast | ConvertTo-Json -Depth 10 | ConvertFrom-Json; $belowRatio.gpuResidentRatio=0.989999
        (Test-AwxModelHardGate -Role fast -Evidence $belowRatio).reasonCodes | Should -Contain 'cpu-offload-detected'
        $belowFast = $fast | ConvertTo-Json -Depth 10 | ConvertFrom-Json; $belowFast.loadedFreeMiB=1535.999
        (Test-AwxModelHardGate -Role fast -Evidence $belowFast).reasonCodes | Should -Contain 'insufficient-vram'
        $belowMain = $main | ConvertTo-Json -Depth 10 | ConvertFrom-Json; $belowMain.loadedFreeMiB=2047.999
        (Test-AwxModelHardGate -Role main -Evidence $belowMain).reasonCodes | Should -Contain 'insufficient-vram'
        $wrongLane = $fast | ConvertTo-Json -Depth 10 | ConvertFrom-Json;$wrongLane.lanePassed=$false
        (Test-AwxModelHardGate -Role fast -Evidence $wrongLane).reasonCodes | Should -Be @('gpu-lane-evidence-incomplete')
        foreach ($invalid in @(
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.digestPassed='true';$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.completedWarmCount=20.5;$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.gpuResidentRatio=[double]::NaN;$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.timeoutCount=22;$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.completedWarmCount=3;$x.warmJsonSchemaPassedCount=4;$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x|Add-Member -NotePropertyName extra -NotePropertyValue $true -PassThru )
        )) {
            $result = Test-AwxModelHardGate -Role fast -Evidence $invalid
            $result.passed | Should -BeFalse; $result.reasonCodes | Should -Be @('benchmark-contract-invalid')
        }
        $metrics=[pscustomobject]@{warmRoleP95Ms=1000.0;loadedFreeMiB=2048.0;rewriteAndExtractionQuality=1.0;instructionFollowing=1.0;toolAndStructuredOutput=1.0;runtimeStability=1.0;koreanRagQuality=1.0;codingAndToolUse=1.0;factualityAndCitationDiscipline=1.0}
        (Get-AwxRoleScore -Role fast -Metrics $metrics -MinEligibleWarmRoleP95Ms 1000 -MaxEligibleLoadedFreeMiB 2048).balancedScore | Should -Be 100.0
        { Get-AwxRoleScore -Role fast -Metrics $metrics -MinEligibleWarmRoleP95Ms ([double]::PositiveInfinity) -MaxEligibleLoadedFreeMiB 2048 } | Should -Throw '*benchmark-contract-invalid*'
        foreach($invalidMetric in @([double]::NaN,-0.01,1.01)){$copy=$metrics|Select-Object *;$copy.instructionFollowing=$invalidMetric;{Get-AwxRoleScore -Role fast -Metrics $copy -MinEligibleWarmRoleP95Ms 1000 -MaxEligibleLoadedFreeMiB 2048}|Should -Throw '*benchmark-contract-invalid*'}
    }

    It 'uses baseline-relative thresholds, retains ties, and rejects coercive gate booleans' {
        $fastBaseline = [pscustomobject]@{classification='baseline';modelTag='qwen3:8b';hardGatePassed=$true;balancedScore=80.0;rewriteAndExtractionQuality=0.90;koreanRagQuality=$null;warmRoleP95Ms=1000}
        $fastCandidate = [pscustomobject]@{classification='primary';modelTag='qwen3.5:9b';hardGatePassed=$true;balancedScore=80.01;rewriteAndExtractionQuality=0.90;koreanRagQuality=$null;warmRoleP95Ms=1000}
        (Select-AwxRoleRecommendation -Role fast -ModelRows @($fastBaseline,$fastCandidate)).modelTag | Should -BeExactly 'qwen3.5:9b'
        $fastCandidate.warmRoleP95Ms = 1001
        (Select-AwxRoleRecommendation -Role fast -ModelRows @($fastBaseline,$fastCandidate)).status | Should -BeExactly 'keep-baseline'
        $fastCandidate.warmRoleP95Ms = 1000; $fastCandidate.balancedScore = 80.0
        (Select-AwxRoleRecommendation -Role fast -ModelRows @($fastBaseline,$fastCandidate)).status | Should -BeExactly 'keep-baseline'

        $mainBaseline = [pscustomobject]@{classification='baseline';modelTag='gemma4:26b';hardGatePassed=$true;balancedScore=75.0;rewriteAndExtractionQuality=$null;koreanRagQuality=0.85;warmRoleP95Ms=1000}
        $mainCandidate = [pscustomobject]@{classification='primary';modelTag='qwen3.6:27b';hardGatePassed=$true;balancedScore=80.0;rewriteAndExtractionQuality=$null;koreanRagQuality=0.85;warmRoleP95Ms=1500}
        (Select-AwxRoleRecommendation -Role main -ModelRows @($mainBaseline,$mainCandidate)).modelTag | Should -BeExactly 'qwen3.6:27b'
        $mainCandidate.balancedScore = 79.99
        (Select-AwxRoleRecommendation -Role main -ModelRows @($mainBaseline,$mainCandidate)).status | Should -BeExactly 'keep-baseline'
        $fastCandidate.hardGatePassed = 'true'
        { Select-AwxRoleRecommendation -Role fast -ModelRows @($fastBaseline,$fastCandidate) } | Should -Throw '*benchmark-contract-invalid*'
    }

    It 'replays known bounded Family A hashes and projects a private order-stable APPLY' {
        $script:FamilyABlockEnvelopes[0].blockEvidenceSha256 | Should -BeExactly '2ba0011c2bc6e020a61e8346ae2f974b1fb9f6aa921c86d77513407b021e2cd8'
        $script:FamilyARun.runBindingSha256 | Should -BeExactly '0b49f747e50e23a97d19c4431197d49e545012713b129e26d1caf693e96cc3f2'
        $script:FamilyAPackets[0].evidenceSha256 | Should -BeExactly 'c16a976def5c96431334b5283c0ffddd458e7972f7453114b6e2ab603c23a5e9'
        $script:FamilyAPackets[0].packetSha256 | Should -BeExactly '4f44426e7fcbb385cbfc62b219b0d0e646db45c44329a631f5eb087173d689d3'
        $script:FamilyAPackets[1].evidenceSha256 | Should -BeExactly '528033b8f3d4b983122a1b2c2844ce60c67f62efad29b3ba1b3b13781aae4aa4'
        $script:FamilyAPackets[1].packetSha256 | Should -BeExactly 'f5eba666094b6c5b0bbcb88eb96c970f8521f0dc007403eb9f4707270e2312c2'
        $script:FamilyAPackets[2].evidenceSha256 | Should -BeExactly '03df061039d2efe36f6925776f6d2596fd0f70224a0370a869572ec232aa8fbe'
        $script:FamilyAPackets[2].packetSha256 | Should -BeExactly 'b9b0ec3cea0f275ef51ee41b84e8191284201a5e5f2081303bab10a948d31904'
        $script:FamilyACommand.commandEvidenceSha256 | Should -BeExactly 'e9ae7ae4cb22fb15aac2e9962ac206d588269d92197902126228ffcb7c497900'
        @($script:FamilyAPackets.passed) | Should -Not -Contain $false
        @($script:FamilyAPackets.reasonCodes | ForEach-Object {@($_)}) | Should -Not -Contain 'benchmark-contract-invalid'
        $packetOrder=@($script:FamilyAPackets.name)
        $provisionalFacts=$script:FamilyACommandFacts|ConvertTo-Json -Depth 40|ConvertFrom-Json;$provisionalFacts.privacyProjectionChecked=$false;$provisionalFacts.privacyProjectionPassed=$false
        $provisional=New-AwxCommandEvidence -RunBinding $script:FamilyARun -Evidence $provisionalFacts
        [void](Get-AwxOrderStableNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $provisional)
        @($script:FamilyAPackets.name) | Should -Be $packetOrder
        $neutral = Get-AwxOrderStableNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $script:FamilyACommand
        @($script:FamilyAPackets.name) | Should -Be $packetOrder
        $neutral.forward | Should -BeExactly 'APPLY'
        $neutral.forwardReasonCode | Should -BeExactly 'all-packets-pass'
        $neutral.reverse | Should -BeExactly $neutral.forward
        $neutral.reverseReasonCode | Should -BeExactly $neutral.forwardReasonCode
        $neutral.orderStable | Should -BeTrue
        $publicPackets = @($script:FamilyAPackets | ForEach-Object {ConvertTo-AwxPublicPacket $_})
        @($publicPackets[0].PSObject.Properties.Name) | Should -Be @('name','complete','passed','reasonCodes','runBindingSha256','evidenceSha256','packetSha256')
        ($publicPackets | ConvertTo-Json -Depth 20) | Should -Not -Match '"evidence"|"runBinding"'
        $publicNeutral = ConvertTo-AwxPublicNeutral $neutral
        @($publicNeutral.PSObject.Properties.Name) | Should -Be @('forward','forwardReasonCode','reverse','reverseReasonCode','orderStable','finalVerdict','finalReasonCode','runBindingSha256','commandEvidenceSha256')
        $inputPath = Join-Path $TestDrive 'family-a-replay-input.json'
        $replayInput=[ordered]@{blockFacts=@($script:FamilyABlockEnvelopes.blockFacts);runBinding=$script:FamilyARun.runBinding;contract=$script:FamilyAContract;scenario=$script:FamilyAScenario;falsify=$script:FamilyAFalsify;commandFacts=$script:FamilyACommandFacts}
        ConvertTo-AwxCanonicalJson $replayInput | Set-Content -LiteralPath $inputPath -Encoding UTF8
        $escapedModule=$script:ModulePath.Replace("'","''");$escapedInput=$inputPath.Replace("'","''")
        $command="Import-Module '$escapedModule' -Force;`$i=Get-Content -Raw -Encoding UTF8 '$escapedInput'|ConvertFrom-Json;`$m=@(Get-AwxFamilyAModelIdentityMatrix);`$b=@(`$i.blockFacts|%{New-AwxBlockEvidence -BlockFacts `$_});`$refs=for(`$n=0;`$n -lt 6;`$n++){[pscustomobject][ordered]@{modelTag=`$m[`$n].modelTag;digest=`$m[`$n].digest;blockEvidenceSha256=`$b[`$n].blockEvidenceSha256}};`$r=New-AwxRunBinding -BenchmarkId `$i.runBinding.benchmarkId -CorpusSha256 `$i.runBinding.corpusSha256 -OracleSha256 `$i.runBinding.oracleSha256 -OntologySha256 `$i.runBinding.ontologySha256 -SchemaSha256 `$i.runBinding.schemaSha256 -OptionsSha256 `$i.runBinding.optionsSha256 -ModelBlocks @(`$refs);`$p=@(New-AwxEvidencePackets -RunBinding `$r -ContractEvidence `$i.contract -ScenarioEvidence `$i.scenario -FalsifyEvidence `$i.falsify);`$c=New-AwxCommandEvidence -RunBinding `$r -Evidence `$i.commandFacts;`$n=Get-AwxOrderStableNeutralVerdict -Packets `$p -CommandEvidence `$c;ConvertTo-AwxCanonicalJson ([ordered]@{blockHashes=@(`$b.blockEvidenceSha256);runBindingSha256=`$r.runBindingSha256;evidenceHashes=@(`$p.evidenceSha256);packetHashes=@(`$p.packetSha256);packetNames=@(`$p.name);commandEvidenceSha256=`$c.commandEvidenceSha256;verdict=`$n.finalVerdict;reasonCode=`$n.finalReasonCode})"
        $replay1=(& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command $command|Out-String).Trim();$replay2=(& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command $command|Out-String).Trim()
        $current=ConvertTo-AwxCanonicalJson ([ordered]@{blockHashes=@($script:FamilyABlockEnvelopes.blockEvidenceSha256);runBindingSha256=$script:FamilyARun.runBindingSha256;evidenceHashes=@($script:FamilyAPackets.evidenceSha256);packetHashes=@($script:FamilyAPackets.packetSha256);packetNames=@($script:FamilyAPackets.name);commandEvidenceSha256=$script:FamilyACommand.commandEvidenceSha256;verdict=$neutral.finalVerdict;reasonCode=$neutral.finalReasonCode})
        $replay1 | Should -BeExactly $replay2; $replay1 | Should -BeExactly $current
    }

    It 'holds every Family A mutation, malformed envelope, reason violation, and order disagreement' {
        $copy={param($value) $value|ConvertTo-Json -Depth 40|ConvertFrom-Json}
        $evidenceHash={param($p) Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-lane-evidence.v2';runBindingSha256=$p.runBindingSha256;name=$p.name;evidence=$p.evidence}))}
        $packetHash={param($p) Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-packet.v2';runBindingSha256=$p.runBindingSha256;name=$p.name;complete=$p.complete;passed=$p.passed;reasonCodes=@($p.reasonCodes);evidenceSha256=$p.evidenceSha256}))}
        $mutations=[Collections.Generic.List[object]]::new()
        $forged=&$copy $script:FamilyAPackets;$forged[0].packetSha256=Get-AwxUtf8Sha256 'forged-format-only';$mutations.Add(@($forged))
        $changed=&$copy $script:FamilyAPackets;$changed[0].evidence.contractValidated=$false;$mutations.Add(@($changed))
        $hashOnly=&$copy $script:FamilyAPackets;$hashOnly[0].evidence.contractValidated=$false;$hashOnly[0].evidenceSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $hashOnly[0].evidence);$mutations.Add(@($hashOnly))
        $stale=&$copy $script:FamilyAPackets;$stale[0].runBindingSha256=Get-AwxUtf8Sha256 'stale-run';$stale[0].packetSha256=&$packetHash $stale[0];$mutations.Add(@($stale))
        $missing=&$copy $script:FamilyAPackets;$missing[0].evidence.PSObject.Properties.Remove('lineageChecks');$mutations.Add(@($missing))
        $extra=&$copy $script:FamilyAPackets;$extra[0].evidence|Add-Member extraFact $true;$mutations.Add(@($extra))
        $duplicateCheck=&$copy $script:FamilyAPackets;$duplicateCheck[0].evidence.digestChecks[1]=$duplicateCheck[0].evidence.digestChecks[0];$duplicateCheck[0].evidenceSha256=&$evidenceHash $duplicateCheck[0];$duplicateCheck[0].packetSha256=&$packetHash $duplicateCheck[0];$mutations.Add(@($duplicateCheck))
        $duplicateModel=&$copy $script:FamilyAPackets;$duplicateModel[1].evidence.models[1]=$duplicateModel[1].evidence.models[0];$duplicateModel[1].evidenceSha256=&$evidenceHash $duplicateModel[1];$duplicateModel[1].packetSha256=&$packetHash $duplicateModel[1];$mutations.Add(@($duplicateModel))
        $duplicateRecommendation=&$copy $script:FamilyAPackets;$duplicateRecommendation[1].evidence.recommendations[1]=$duplicateRecommendation[1].evidence.recommendations[0];$duplicateRecommendation[1].evidenceSha256=&$evidenceHash $duplicateRecommendation[1];$duplicateRecommendation[1].packetSha256=&$packetHash $duplicateRecommendation[1];$mutations.Add(@($duplicateRecommendation))
        $inconsistentRecommendation=&$copy $script:FamilyAPackets;$inconsistentRecommendation[1].evidence.recommendations[0]=[pscustomobject][ordered]@{role='fast';status='recommend-candidate';modelTag='qwen3.5:9b';scoreDelta=1.0;reasonCode='promotion-approval-required'};$inconsistentRecommendation[1].evidenceSha256=&$evidenceHash $inconsistentRecommendation[1];$inconsistentRecommendation[1].packetSha256=&$packetHash $inconsistentRecommendation[1];$mutations.Add(@($inconsistentRecommendation))
        foreach($candidate in $mutations){(Get-AwxNeutralVerdict -Packets $candidate -CommandEvidence $script:FamilyACommand).verdict|Should -BeExactly 'HOLD'}
        (Get-AwxNeutralVerdict -Packets @($script:FamilyAPackets[0],$script:FamilyAPackets[0],$script:FamilyAPackets[2]) -CommandEvidence $script:FamilyACommand).verdict | Should -BeExactly 'HOLD'
        $badCommand=&$copy $script:FamilyACommand;$badCommand.PSObject.Properties.Remove('evidence');(Get-AwxNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $badCommand).verdict|Should -BeExactly 'HOLD'
        $missingCommandHashes=&$copy $script:FamilyACommand;$missingCommandHashes.PSObject.Properties.Remove('evidence');$missingCommandHashes.PSObject.Properties.Remove('runBindingSha256');$missingCommandHashes.PSObject.Properties.Remove('commandEvidenceSha256')
        { $script:FamilyAMalformedOrder=Get-AwxOrderStableNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $missingCommandHashes }|Should -Not -Throw
        $script:FamilyAMalformedOrder.finalVerdict|Should -BeExactly 'HOLD';$script:FamilyAMalformedOrder.finalReasonCode|Should -BeExactly 'command-evidence-incomplete'
        ($null -eq $script:FamilyAMalformedOrder.runBindingSha256)|Should -BeTrue;($null -eq $script:FamilyAMalformedOrder.commandEvidenceSha256)|Should -BeTrue
        $duplicateFact=&$copy $script:FamilyABlockEnvelopes[0].blockFacts;$duplicateFact.warm[1]=$duplicateFact.warm[0]
        {New-AwxBlockEvidence -BlockFacts $duplicateFact}|Should -Throw '*benchmark-contract-invalid*'
        $malformedScenario=&$copy $script:FamilyAScenario;$malformedScenario.models[0].hardGatePassed=$false;$malformedScenario.models[0].hardGateReasonCodes=@('benchmark-contract-invalid')
        $malformedScenario.recommendations[0]=[pscustomobject][ordered]@{role='fast';status='HOLD';modelTag=$null;scoreDelta=$null;reasonCode='baseline-evidence-incomplete'}
        $mapped=@(New-AwxEvidencePackets -RunBinding $script:FamilyARun -ContractEvidence $script:FamilyAContract -ScenarioEvidence $malformedScenario -FalsifyEvidence $script:FamilyAFalsify)
        $mapped[1].passed|Should -BeFalse;$mapped[1].reasonCodes|Should -Be @('baseline-evidence-incomplete')
        $selectorRows=@(
            [pscustomobject][ordered]@{classification='baseline';modelTag='qwen3:8b';hardGatePassed=$true;balancedScore=80.0;rewriteAndExtractionQuality=1.0;koreanRagQuality=$null;warmRoleP95Ms=1000.0},
            [pscustomobject][ordered]@{classification='primary';modelTag='qwen3.5:9b';hardGatePassed=$true;balancedScore=81.0;rewriteAndExtractionQuality=1.0;koreanRagQuality=$null;warmRoleP95Ms=1001.0}
        )
        $failedBaseline=&$copy $selectorRows;$failedBaseline[0].hardGatePassed=$false;(Select-AwxRoleRecommendation -Role fast -ModelRows $failedBaseline).reasonCode|Should -BeExactly 'baseline-evidence-incomplete'
        $nullBaselineScore=&$copy $selectorRows;$nullBaselineScore[0].balancedScore=$null;(Select-AwxRoleRecommendation -Role fast -ModelRows $nullBaselineScore).reasonCode|Should -BeExactly 'baseline-evidence-incomplete'
        $nullBaselineQuality=&$copy $selectorRows;$nullBaselineQuality[0].rewriteAndExtractionQuality=$null;(Select-AwxRoleRecommendation -Role fast -ModelRows $nullBaselineQuality).reasonCode|Should -BeExactly 'baseline-evidence-incomplete'
        $missingCandidateMetric=&$copy $selectorRows;$missingCandidateMetric[1].balancedScore=$null;{Select-AwxRoleRecommendation -Role fast -ModelRows $missingCandidateMetric}|Should -Throw '*benchmark-contract-invalid*'
        $mismatchedIdentity=&$copy $selectorRows;$mismatchedIdentity[1].classification='challenger';{Select-AwxRoleRecommendation -Role fast -ModelRows $mismatchedIdentity}|Should -Throw '*benchmark-contract-invalid*'
        $precisionRows=&$copy $selectorRows;$precisionRows[1].balancedScore=80.000001;$precisionRows[1].warmRoleP95Ms=1000.0
        $precisionRecommendation=Select-AwxRoleRecommendation -Role fast -ModelRows $precisionRows
        $precisionRecommendation.status|Should -BeExactly 'recommend-candidate';$precisionRecommendation.scoreDelta|Should -Be 0.000001;([double]$precisionRecommendation.scoreDelta -gt 0)|Should -BeTrue
        $noncanonicalScore=&$copy $precisionRows;$noncanonicalScore[1].balancedScore=80.0000004;{Select-AwxRoleRecommendation -Role fast -ModelRows $noncanonicalScore}|Should -Throw '*benchmark-contract-invalid*'
        $precisionScenario=&$copy $script:FamilyAScenario;$precisionScenario.models[1].balancedScore=80.000001;$precisionScenario.models[1].warmRoleP95Ms=1000.0;$precisionScenario.recommendations[0]=[pscustomobject][ordered]@{role='fast';status='recommend-candidate';modelTag='qwen3.5:9b';scoreDelta=0.000001;reasonCode='promotion-approval-required'}
        $precisionPackets=@(New-AwxEvidencePackets -RunBinding $script:FamilyARun -ContractEvidence $script:FamilyAContract -ScenarioEvidence $precisionScenario -FalsifyEvidence $script:FamilyAFalsify);$precisionPackets[1].passed|Should -BeTrue;{ConvertTo-AwxPublicPacket $precisionPackets[1]}|Should -Not -Throw
        foreach($nonpositiveDelta in @(0.0,-0.000001)){
            $invalidScenario=&$copy $precisionScenario;$invalidScenario.recommendations[0].scoreDelta=$nonpositiveDelta
            {New-AwxEvidencePackets -RunBinding $script:FamilyARun -ContractEvidence $script:FamilyAContract -ScenarioEvidence $invalidScenario -FalsifyEvidence $script:FamilyAFalsify}|Should -Throw '*benchmark-contract-invalid*'
            $invalidRecommendationPacket=&$copy $precisionPackets[1];$invalidRecommendationPacket.evidence.recommendations[0].scoreDelta=$nonpositiveDelta;$invalidRecommendationPacket.evidenceSha256=&$evidenceHash $invalidRecommendationPacket;$invalidRecommendationPacket.packetSha256=&$packetHash $invalidRecommendationPacket
            {ConvertTo-AwxPublicPacket $invalidRecommendationPacket}|Should -Throw '*benchmark-contract-invalid*'
        }
        $reasonCases=[Collections.Generic.List[object]]::new()
        [void]$reasonCases.Add([object[]]@('unknown-code'));[void]$reasonCases.Add([object[]]@('pass','pass'));[void]$reasonCases.Add([object[]]@('Pass'));[void]$reasonCases.Add([object[]]@('warm-sample-insufficient'));[void]$reasonCases.Add([object[]]@('sk-secret-shaped-value'))
        foreach($badReasons in $reasonCases){
            $bad=&$copy $script:FamilyAPackets;$bad[0].reasonCodes=@($badReasons);$bad[0].packetSha256=&$packetHash $bad[0]
            (Get-AwxNeutralVerdict -Packets $bad -CommandEvidence $script:FamilyACommand).verdict|Should -BeExactly 'HOLD'
        }
        $status=&$copy $script:FamilyAPackets;$status[0].passed=$false;$status[0].reasonCodes=@('pass');$status[0].packetSha256=&$packetHash $status[0]
        (Get-AwxNeutralVerdict -Packets $status -CommandEvidence $script:FamilyACommand).verdict|Should -BeExactly 'HOLD'
        $invalidProjectionPacket=&$copy $script:FamilyAPackets[0];$invalidProjectionPacket.evidence.contractValidated=$false
        {ConvertTo-AwxPublicPacket $invalidProjectionPacket}|Should -Throw '*benchmark-contract-invalid*'
        $validNeutral=Get-AwxOrderStableNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $script:FamilyACommand
        foreach($invalidReason in @('unknown-code','All-Packets-Pass','sk-secret-shaped-value','rawResponse')){
            $invalidNeutral=&$copy $validNeutral;$invalidNeutral.forwardReasonCode=$invalidReason
            {ConvertTo-AwxPublicNeutral $invalidNeutral}|Should -Throw '*benchmark-contract-invalid*'
        }
        $nullHashNeutral=&$copy $validNeutral;$nullHashNeutral.runBindingSha256=$null;{ConvertTo-AwxPublicNeutral $nullHashNeutral}|Should -Throw '*benchmark-contract-invalid*'
        $extraNeutral=&$copy $validNeutral;$extraNeutral|Add-Member extraFact $true;{ConvertTo-AwxPublicNeutral $extraNeutral}|Should -Throw '*benchmark-contract-invalid*'
        $module=Get-Module DesktopOllamaModelTools
        $orderVerdict=&$module {Resolve-AwxNeutralOrder -Forward ([pscustomobject]@{verdict='APPLY';reasonCode='all-packets-pass'}) -Reverse ([pscustomobject]@{verdict='HOLD';reasonCode='scenario-evidence-incomplete'}) -RunBindingSha256 (Get-AwxUtf8Sha256 'order-run') -CommandEvidenceSha256 (Get-AwxUtf8Sha256 'order-command')}
        $orderReason=&$module {Resolve-AwxNeutralOrder -Forward ([pscustomobject]@{verdict='APPLY';reasonCode='all-packets-pass'}) -Reverse ([pscustomobject]@{verdict='APPLY';reasonCode='scenario-evidence-incomplete'}) -RunBindingSha256 (Get-AwxUtf8Sha256 'order-run') -CommandEvidenceSha256 (Get-AwxUtf8Sha256 'order-command')}
        $orderVerdict.finalReasonCode|Should -BeExactly 'order-unstable';$orderReason.finalReasonCode|Should -BeExactly 'order-unstable'
    }
}

Describe 'strict local runtime adapters' {
    It 'rejects non-loopback endpoints and incomplete ps evidence' {
        { Assert-AwxLoopbackEndpoint 'http://localhost:11434' } | Should -Throw '*endpoint-not-allowlisted*'
        (Get-Command Invoke-AwxOllamaHttp).Definition | Should -Match 'MaximumRedirection\s*=\s*0'
        $module = Get-Module DesktopOllamaModelTools
        { & $module { Assert-AwxOllamaHttpRequestPair -Path '/api/chat' -Method GET } } | Should -Throw '*ollama-http-method-invalid*'
        { & $module { Assert-AwxOllamaHttpRequestPair -Path '/api/tags' -Method POST } } | Should -Throw '*ollama-http-method-invalid*'
        $digest = Test-AwxInstalledDigest -Rows @([pscustomobject]@{name='qwen3:8b';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'}) -ModelTag 'qwen3:8b' -ExpectedDigest '500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'
        $digest.passed | Should -BeTrue
        $conflictingDigest = Test-AwxInstalledDigest -Rows @(
            [pscustomobject]@{name='qwen3:8b';model='qwen3:8b';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'},
            [pscustomobject]@{name='qwen3:8b';model='other:1b';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'}
        ) -ModelTag 'qwen3:8b' -ExpectedDigest '500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'
        $conflictingDigest.passed | Should -BeFalse
        foreach ($malformedIdentity in @(
            [pscustomobject]@{name='qwen3:8b';model=42;digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'},
            [pscustomobject]@{name='qwen3:8b';model='';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'}
        )) {
            (Test-AwxInstalledDigest -Rows @($malformedIdentity) -ModelTag 'qwen3:8b' -ExpectedDigest '500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41').passed | Should -BeFalse
        }
        { ConvertFrom-AwxPsResponse -Response ([pscustomobject]@{}) } | Should -Throw '*ps-evidence-incomplete*'
        { ConvertFrom-AwxPsResponse -Response ([pscustomobject]@{models=@([pscustomobject]@{name='qwen3:8b';size=0;size_vram=0;context_length=8192})}) } | Should -Throw '*ps-evidence-incomplete*'
        $invalidRows = @(
            [pscustomobject]@{name='qwen3:8b';model='other:1b';size=1000;size_vram=995;context_length=8192},
            [pscustomobject]@{name='qwen3:8b';model='qwen3:8b';size='1000';size_vram=995;context_length=8192},
            [pscustomobject]@{name='qwen3:8b';model=42;size=1000;size_vram=995;context_length=8192},
            [pscustomobject]@{name='qwen3:8b';model='';size=1000;size_vram=995;context_length=8192},
            [pscustomobject]@{name='qwen3:8b';model='qwen3:8b';size=1000;size_vram=995;context_length='8192.5'},
            [pscustomobject]@{name='qwen3:8b';model='qwen3:8b';size=[double]::NaN;size_vram=995;context_length=8192},
            [pscustomobject]@{name='qwen3:8b';model='qwen3:8b';size=1000;size_vram=2000;context_length=8192}
        )
        foreach ($invalidRow in $invalidRows) {
            { ConvertFrom-AwxPsResponse -Response ([pscustomobject]@{models=@($invalidRow)}) } | Should -Throw '*ps-evidence-incomplete*'
        }
    }

    It 'requires one exact loaded model, full context, and GPU residency' {
        $ps = ConvertFrom-AwxPsResponse -Response ([pscustomobject]@{models=@([pscustomobject]@{name='qwen3:8b';model='qwen3:8b';size=1000;size_vram=995;context_length=8192})})
        $row = Get-AwxLoadedModelEvidence -Rows $ps -ModelTag 'qwen3:8b' -ExpectedContext 8192
        $row.gpuResidentRatio | Should -Be 0.995
        $row.contextLength | Should -Be 8192
        { Get-AwxLoadedModelEvidence -Rows @($ps[0],$ps[0]) -ModelTag 'qwen3:8b' -ExpectedContext 8192 } | Should -Throw '*ps-evidence-incomplete*'
        $other = [pscustomobject]@{modelTag='other:1b';sizeBytes=1000;sizeVramBytes=1000;contextLength=8192}
        { Get-AwxLoadedModelEvidence -Rows @($ps[0],$other) -ModelTag 'qwen3:8b' -ExpectedContext 8192 } | Should -Throw '*ps-evidence-incomplete*'
        $coercive = [pscustomobject]@{modelTag='qwen3:8b';sizeBytes='1000';sizeVramBytes=995;contextLength='8192'}
        { Get-AwxLoadedModelEvidence -Rows @($coercive) -ModelTag 'qwen3:8b' -ExpectedContext 8192 } | Should -Throw '*ps-evidence-incomplete*'
    }

    It 'proves the exact endpoint process tree is resident on the expected named GPU without UUID output' {
        $snapshot = @(
            [pscustomobject]@{laneLabel='RTX 3060';totalMiB=12288;freeMiB=6000},
            [pscustomobject]@{laneLabel='RTX 3090';totalMiB=24576;freeMiB=22000}
        )
        $compute = @([pscustomobject]@{processId=102;laneLabel='RTX 3060';usedMiB=5000})
        $lane = Test-AwxGpuLane -EndpointProcessIds @(101,102) -ComputeRows $compute -Snapshot $snapshot -ExpectedLane 'RTX 3060' -MinimumFreeMiB 1536
        $lane.passed | Should -BeTrue
        ($lane | ConvertTo-Json -Depth 5) | Should -Not -Match '(?i)uuid|GPU-[0-9a-f-]{16,}'
        (Test-AwxGpuRelease -EndpointProcessIds @(101,102) -ComputeRows @()).passed | Should -BeTrue
        (Test-AwxGpuRelease -EndpointProcessIds @(101,102) -ComputeRows $compute).passed | Should -BeFalse
        $coerciveSnapshot = @(
            [pscustomobject]@{laneLabel='RTX 3060';totalMiB='12288';freeMiB='6000'},
            [pscustomobject]@{laneLabel='RTX 3090';totalMiB=24576;freeMiB=22000}
        )
        $coerciveCompute = @([pscustomobject]@{processId='102';laneLabel='RTX 3060'})
        { Test-AwxGpuLane -EndpointProcessIds @(101,102) -ComputeRows $coerciveCompute -Snapshot $coerciveSnapshot -ExpectedLane 'RTX 3060' -MinimumFreeMiB 1536 } | Should -Throw '*gpu-lane-evidence-incomplete*'
    }

    It 'takes an exclusive benchmark-only lease and atomically replaces a report' {
        $report = Join-Path $TestDrive 'out\report.json'
        $lease = Enter-AwxBenchmarkLease -OutputPath $report
        try {
            { Enter-AwxBenchmarkLease -OutputPath $report } | Should -Throw '*benchmark-lock-held*'
            $directory = Split-Path $report
            $leaf = Split-Path $report -Leaf
            $staleTemp = Join-Path $directory ('.' + $leaf + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
            $staleBackup = Join-Path $directory ('.' + $leaf + '.' + [guid]::NewGuid().ToString('N') + '.bak')
            $unrelated = Join-Path $directory ('.' + $leaf + '.not-a-guid.tmp')
            Set-Content -LiteralPath $staleTemp -Value 'stale' -Encoding UTF8
            Set-Content -LiteralPath $staleBackup -Value 'stale' -Encoding UTF8
            Set-Content -LiteralPath $unrelated -Value 'keep' -Encoding UTF8
            Write-AwxJsonAtomic -Path $report -Value ([ordered]@{schemaVersion=2;verdict='HOLD'})
            Write-AwxJsonAtomic -Path $report -Value ([ordered]@{schemaVersion=2;verdict='APPLY'})
            (Get-Content -LiteralPath $report -Raw -Encoding UTF8 | ConvertFrom-Json).verdict | Should -BeExactly 'APPLY'
            Test-Path -LiteralPath $staleTemp | Should -BeFalse
            Test-Path -LiteralPath $staleBackup | Should -BeFalse
            Test-Path -LiteralPath $unrelated | Should -BeTrue
            $collision = Join-Path $directory 'collision.json'
            New-Item -ItemType Directory -Path $collision | Out-Null
            { Write-AwxJsonAtomic -Path $collision -Value ([ordered]@{schemaVersion=2}) } | Should -Throw '*report-write-failed*'
            @(Get-ChildItem -LiteralPath $directory -Force | Where-Object Name -Match '^\.collision\.json\.[a-f0-9]{32}\.(tmp|bak)$').Count | Should -Be 0
        } finally { $lease.Dispose() }
        { $lease.Dispose() } | Should -Not -Throw
        (Get-Command Enter-AwxBenchmarkLease).Definition | Should -Match 'DeleteOnClose'
        (Get-Command Enter-AwxBenchmarkLease).Definition | Should -Not -Match 'Remove-Item\s+-LiteralPath\s+\$path'
        $mismatchedLease = Enter-AwxBenchmarkLease -OutputPath $report
        $mismatchedPath = $mismatchedLease.Path
        $mismatchedLease.Token = ('0' * 32)
        { $mismatchedLease.Dispose() } | Should -Throw '*benchmark-lock-ownership-lost*'
        Test-Path -LiteralPath $mismatchedPath | Should -BeFalse
        { $mismatchedLease.Dispose() } | Should -Not -Throw
    }
}

Describe 'serial model block orchestration' {
    It 'never overlaps model requests and always unloads after a successful block' {
        $script:active = 0; $script:maxActive = 0; $script:unloaded = @()
        $adapters = [pscustomobject]@{
            VerifyInstalled = { param($m) [pscustomobject]@{passed=$true} }
            PsEmpty = { param($m) $true }
            Gpu = { param($phase,$m,$timeout) @([pscustomobject]@{laneLabel=$m.gpuLane;totalMiB=24576;freeMiB=12000}) }
            Loaded = { param($m,$timeout) [pscustomobject]@{modelTag=$m.modelTag;gpuResidentRatio=1.0;sizeVramMiB=10000;contextLength=8192;endpointProcessIds=@(101,102);computeRows=@([pscustomobject]@{processId=102;laneLabel=$m.gpuLane;usedMiB=10000})} }
            Chat = {
                param($m,$case,$body,$timeout)
                $script:active++; $script:maxActive = [Math]::Max($script:maxActive,$script:active)
                $content = switch ($case.id) {
                    'fast-rewrite-ko' {'{"rewrite":"검증 후 기본값을 바꾼다.","meaningUnits":["DOWNLOAD_MODEL","VERIFY_BEFORE_DEFAULT_CHANGE","CHANGE_DEFAULT_ONLY_AFTER_PASS"]}'}
                    'fast-json-extract' {'{"gpu":"RTX 3060","role":"fast","port":11435}'}
                    'fast-instruction' {'{"token":"PASS"}'}
                    default { throw 'unexpected-test-case' }
                }
                try { [pscustomobject]@{latencyMs=10;ollamaResponse=[pscustomobject]@{model=$m.modelTag;done_reason='stop';eval_count=8;message=[pscustomobject]@{thinking='';content=$content}}} }
                finally { $script:active-- }
            }
            Unload = { param($m,$timeout) [void]($script:unloaded += $m.modelTag) }
            GpuReleased = { param($m,$processIds,$timeout) $true }
        }
        $model = [pscustomobject]@{role='fast';classification='baseline';modelTag='qwen3:8b';endpoint='http://127.0.0.1:11435';gpuLane='RTX 3060';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'}
        $portfolioClock = [Diagnostics.Stopwatch]::StartNew()
        $block = Invoke-AwxModelBlock -Model $model -Corpus $script:Corpus -Oracle $script:Oracle -Adapters $adapters -PortfolioClock $portfolioClock -BlockTimeoutSec 1800 -PortfolioTimeoutSec 7200
        $portfolioClock.Stop()
        $script:maxActive | Should -Be 1
        $script:unloaded | Should -Be @('qwen3:8b','qwen3:8b')
        @($block.blockFacts.PSObject.Properties.Name) | Should -Be @('modelTag','role','classification','cold','warm','lane','cleanup')
        $block.blockFacts.lane.lanePassed | Should -BeOfType System.Boolean
        $block.blockFacts.cleanup.psEmpty | Should -BeOfType System.Boolean
        $block.blockEvidenceSha256 | Should -Match '^[a-f0-9]{64}$'

        $script:schemaFailChatCalls = 0; $script:schemaFailUnloaded = @(); $script:schemaFailBlock = $null
        $schemaFailAdapters = [pscustomobject]@{
            VerifyInstalled = { param($m) [pscustomobject]@{passed=$true} }
            PsEmpty = { param($m) $true }
            Gpu = { param($phase,$m,$timeout) @([pscustomobject]@{laneLabel=$m.gpuLane;totalMiB=24576;freeMiB=12000}) }
            Loaded = { param($m,$timeout) [pscustomobject]@{modelTag=$m.modelTag;gpuResidentRatio=1.0;sizeVramMiB=10000;contextLength=8192;endpointProcessIds=@(101,102);computeRows=@([pscustomobject]@{processId=102;laneLabel=$m.gpuLane})} }
            Chat = {
                param($m,$case,$body,$timeout)
                $script:schemaFailChatCalls++
                $content = if ($script:schemaFailChatCalls -in @(1,2)) {
                    '{"rewrite":"검증 후 기본값을 바꾼다.","meaningUnits":["DOWNLOAD_MODEL","VERIFY_BEFORE_DEFAULT_CHANGE","CHANGE_DEFAULT_ONLY_AFTER_PASS"],"unexpected":true}'
                } else {
                    switch ($case.id) {
                        'fast-rewrite-ko' {'{"rewrite":"검증 후 기본값을 바꾼다.","meaningUnits":["DOWNLOAD_MODEL","VERIFY_BEFORE_DEFAULT_CHANGE","CHANGE_DEFAULT_ONLY_AFTER_PASS"]}'}
                        'fast-json-extract' {'{"gpu":"RTX 3060","role":"fast","port":11435}'}
                        'fast-instruction' {'{"token":"PASS"}'}
                        default { throw 'unexpected-test-case' }
                    }
                }
                [pscustomobject]@{latencyMs=10;ollamaResponse=[pscustomobject]@{model=$m.modelTag;done_reason='stop';eval_count=8;message=[pscustomobject]@{thinking='';content=$content}}}
            }
            Unload = { param($m,$timeout) [void]($script:schemaFailUnloaded += $m.modelTag) }
            GpuReleased = { param($m,$processIds,$timeout) $true }
        }
        $schemaFailClock = [Diagnostics.Stopwatch]::StartNew(); $schemaFailError = $null
        try { $script:schemaFailBlock = Invoke-AwxModelBlock -Model $model -Corpus $script:Corpus -Oracle $script:Oracle -Adapters $schemaFailAdapters -PortfolioClock $schemaFailClock -BlockTimeoutSec 1800 -PortfolioTimeoutSec 7200 }
        catch { $schemaFailError = $_.Exception.Message }
        finally { $schemaFailClock.Stop() }
        $schemaFailError | Should -BeNullOrEmpty
        $script:schemaFailChatCalls | Should -Be 22
        $script:schemaFailBlock.cold.jsonParsed | Should -BeTrue
        $script:schemaFailBlock.cold.schemaPassed | Should -BeFalse
        $script:schemaFailBlock.cold.reasonCode | Should -BeExactly 'response-schema-invalid'
        @($script:schemaFailBlock.warm).Count | Should -Be 21
        $script:schemaFailBlock.warm[0].jsonParsed | Should -BeTrue
        $script:schemaFailBlock.warm[0].schemaPassed | Should -BeFalse
        $script:schemaFailBlock.warm[0].reasonCode | Should -BeExactly 'response-schema-invalid'
        @($script:schemaFailBlock.warm | Where-Object schemaPassed).Count | Should -Be 20
        $script:schemaFailBlock.cleanup.psEmpty | Should -BeTrue
        $script:schemaFailBlock.cleanup.gpuReleased | Should -BeTrue
        $script:schemaFailUnloaded | Should -Be @('qwen3:8b','qwen3:8b')
        $script:schemaFailBlock.blockEvidenceSha256 | Should -Match '^[a-f0-9]{64}$'

        $newCoerciveAdapters = {
            param($installedValue,$psEmptyValue,$gpuReleasedValue)
            $verify = $installedValue; $empty = $psEmptyValue; $released = $gpuReleasedValue
            [pscustomobject]@{
                VerifyInstalled = { [pscustomobject]@{passed=$verify} }.GetNewClosure()
                PsEmpty = { $empty }.GetNewClosure()
                Gpu = { param($phase,$m,$timeout) @([pscustomobject]@{laneLabel=$m.gpuLane;totalMiB=24576;freeMiB=12000}) }
                Loaded = { param($m,$timeout) [pscustomobject]@{modelTag=$m.modelTag;gpuResidentRatio=1.0;sizeVramMiB=10000;contextLength=8192;endpointProcessIds=@(101,102);computeRows=@([pscustomobject]@{processId=102;laneLabel=$m.gpuLane})} }
                Chat = { param($m,$case,$body,$timeout) $script:coerciveChats++; throw 'unexpected-chat' }
                Unload = { param($m,$timeout) }
                GpuReleased = { $released }.GetNewClosure()
            }
        }
        $script:coerciveChats = 0
        $falseInstalled = & $newCoerciveAdapters 'false' $true $true
        $clock = [Diagnostics.Stopwatch]::StartNew()
        { Invoke-AwxModelBlock -Model $model -Corpus $script:Corpus -Oracle $script:Oracle -Adapters $falseInstalled -PortfolioClock $clock -BlockTimeoutSec 1800 -PortfolioTimeoutSec 7200 } | Should -Throw '*benchmark-runtime-failed*'
        $clock.Stop(); $script:coerciveChats | Should -Be 0
        $script:coerciveChats = 0
        $falsePs = & $newCoerciveAdapters $true 'false' $true
        $clock = [Diagnostics.Stopwatch]::StartNew()
        { Invoke-AwxModelBlock -Model $model -Corpus $script:Corpus -Oracle $script:Oracle -Adapters $falsePs -PortfolioClock $clock -BlockTimeoutSec 1800 -PortfolioTimeoutSec 7200 } | Should -Throw '*model-unload-unproven*'
        $clock.Stop(); $script:coerciveChats | Should -Be 0
        $falseRelease = & $newCoerciveAdapters $false $true 'false'
        $clock = [Diagnostics.Stopwatch]::StartNew()
        { Invoke-AwxModelBlock -Model $model -Corpus $script:Corpus -Oracle $script:Oracle -Adapters $falseRelease -PortfolioClock $clock -BlockTimeoutSec 1800 -PortfolioTimeoutSec 7200 } | Should -Throw '*model-unload-unproven*'
        $clock.Stop()
    }

    It 'unloads on failure and does not advance to the next model' {
        $script:calls = @(); $script:unloaded = @()
        $models = @(
            [pscustomobject]@{role='fast';classification='baseline';modelTag='qwen3:8b'},
            [pscustomobject]@{role='fast';classification='primary';modelTag='qwen3.5:9b'}
        )
        $runner = {
            param($model)
            $script:calls += $model.modelTag
            throw 'injected-block-failure'
        }
        $unloader = { param($model) [void]($script:unloaded += $model.modelTag) }
        { Invoke-AwxBenchmarkPortfolio -Models $models -ModelBlockRunner $runner -EmergencyUnloader $unloader -PortfolioTimeoutSec 7200 } | Should -Throw '*benchmark-runtime-failed*'
        $script:calls | Should -Be @('qwen3:8b')
        $script:unloaded | Should -Contain 'qwen3:8b'
        $script:calls | Should -Not -Contain 'qwen3.5:9b'
    }

    It 'runs two successful endpoint blocks strictly in sequence and rejects overlapping intervals' {
        $models = @(
            [pscustomobject]@{modelTag='qwen3:8b';endpoint='http://127.0.0.1:11435'},
            [pscustomobject]@{modelTag='gemma4:26b';endpoint='http://127.0.0.1:11434'}
        )
        $script:events = @(); $script:active = 0; $script:maxActive = 0
        $runner = {
            param($model,$clock,$limit)
            $script:events += ('start:' + $model.modelTag); $script:active++; $script:maxActive=[Math]::Max($script:maxActive,$script:active)
            try { Start-Sleep -Milliseconds 10; [pscustomobject]@{modelTag=$model.modelTag} }
            finally { $script:active--; $script:events += ('end:' + $model.modelTag) }
        }
        $results = @(Invoke-AwxBenchmarkPortfolio -Models $models -ModelBlockRunner $runner -EmergencyUnloader {param($m,$t)} -PortfolioTimeoutSec 7200)
        $results.Count | Should -Be 2
        $script:maxActive | Should -Be 1
        $script:events | Should -Be @('start:qwen3:8b','end:qwen3:8b','start:gemma4:26b','end:gemma4:26b')
        { Assert-AwxNoOverlappingModelBlocks @([pscustomobject]@{startMs=0;endMs=10},[pscustomobject]@{startMs=9;endMs=20}) } | Should -Throw '*concurrent-model-block-detected*'
    }
}

Describe 'benchmark v2 CLI and public report' {
    BeforeAll { $script:CliPath = Join-Path $script:RepoRoot 'scripts\desktop_ollama_model_benchmark.ps1' }

    It 'pins the exact ordered six-model matrix' {
        $models = @(Get-AwxBenchmarkModelMatrix)
        $models.Count | Should -Be 6
        foreach ($model in $models) {
            @($model.PSObject.Properties.Name) | Should -Be @('order','role','classification','modelTag','endpoint','endpointLabel','gpuLane','digest')
            $model.order | Should -BeOfType System.Int32
            foreach ($name in @('role','classification','modelTag','endpoint','endpointLabel','gpuLane','digest')) {
                $model.$name | Should -BeOfType System.String
            }
        }
        @($models.order) | Should -Be @(1,2,3,4,5,6)
        @($models.role) | Should -Be @('fast','fast','fast','main','main','main')
        @($models.classification) | Should -Be @('baseline','primary','challenger','baseline','primary','challenger')
        @($models.modelTag) | Should -Be @('qwen3:8b','qwen3.5:9b','gemma4:12b','gemma4:26b','qwen3.6:27b','gemma4:31b')
        @($models.endpoint) | Should -Be @('http://127.0.0.1:11435','http://127.0.0.1:11435','http://127.0.0.1:11435','http://127.0.0.1:11434','http://127.0.0.1:11434','http://127.0.0.1:11434')
        @($models.endpointLabel) | Should -Be @('fast-11435','fast-11435','fast-11435','primary-11434','primary-11434','primary-11434')
        @($models.gpuLane) | Should -Be @('RTX 3060','RTX 3060','RTX 3060','RTX 3090','RTX 3090','RTX 3090')
        @($models.digest) | Should -Be @(
            '500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41',
            '6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7',
            '4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c',
            '5571076f3d70050487b26b341705799e0ab29b808164f90d20d4cf84f699d251',
            'a50eda8ed977ab48a12431878896b27ffd5cef552c17af3317d9623b939a7f1e',
            '6316f0629137b426c9d9b853ffc4c8209589f30ee39aebede6285096c0ff47e7'
        )
        foreach ($helperName in @(
            'Test-AwxExactSequence','Test-AwxStrictBoolean','Test-AwxStrictIntegerRange',
            'Test-AwxFiniteNumberRange','Assert-AwxExactPropertySet','Assert-AwxHash64'
        )) {
            $command = Get-Command -Name $helperName -Module DesktopOllamaModelTools -ErrorAction SilentlyContinue
            $command | Should -Not -BeNullOrEmpty
            $command.CommandType | Should -BeExactly 'Function'
        }

        $global:AwxTask6AdapterFactoryCalls = 0
        Mock New-AwxProductionAdapters -ModuleName DesktopOllamaModelTools {
            $global:AwxTask6AdapterFactoryCalls++
            throw 'adapter-factory-must-not-run'
        }
        $mutated = @($models | ForEach-Object { $_ | ConvertTo-Json -Depth 10 | ConvertFrom-Json })
        $mutated[0].order = '1'
        $invalid = Invoke-AwxLiveBenchmarkReport -Corpus $script:Corpus -Oracle $script:Oracle -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -Models $mutated -PortfolioTimeoutSec 7200
        $invalid.verdict | Should -BeExactly 'HOLD'
        $invalid.reasonCodes | Should -Be @('benchmark-runtime-failed')
        $global:AwxTask6AdapterFactoryCalls | Should -Be 0

        $expired = Invoke-AwxLiveBenchmarkReport -Corpus $script:Corpus -Oracle $script:Oracle -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -Models $models -PortfolioTimeoutSec 0
        $expired.verdict | Should -BeExactly 'HOLD'
        $expired.reasonCodes | Should -Be @('portfolio-timeout')
        $global:AwxTask6AdapterFactoryCalls | Should -Be 0
        Remove-Variable -Name AwxTask6AdapterFactoryCalls -Scope Global -ErrorAction SilentlyContinue
    }

    It 'runs validate-only by default and writes no raw corpus or oracle value' {
        $output = Join-Path $TestDrive 'validate-only.json'
        & $script:CliPath -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -OutputPath $output
        $reportText = Get-Content -LiteralPath $output -Raw -Encoding UTF8
        $report = $reportText | ConvertFrom-Json
        $report.schemaVersion | Should -Be 2
        $report.mode | Should -BeExactly 'validate-only'
        $report.verdict | Should -BeExactly 'HOLD'
        $report.reasonCodes | Should -Contain 'live-evidence-not-requested'
        $corpus = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($case in $corpus.cases) { $reportText | Should -Not -Match ([regex]::Escape([string]$case.prompt)) }
        $oracle = Get-Content -LiteralPath $script:OraclePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $reportText | Should -Not -Match ([regex]::Escape((ConvertTo-AwxCanonicalJson $oracle.cases[0].expected)))

        $corpusHash = Get-AwxFileSha256 $script:CorpusPath
        { & $script:CliPath -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -OutputPath $script:CorpusPath } | Should -Throw '*output-path-alias-invalid*'
        (Get-AwxFileSha256 $script:CorpusPath) | Should -BeExactly $corpusHash
        $oracleHash = Get-AwxFileSha256 $script:OraclePath
        { & $script:CliPath -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -OutputPath $script:OraclePath } | Should -Throw '*output-path-alias-invalid*'
        (Get-AwxFileSha256 $script:OraclePath) | Should -BeExactly $oracleHash

        $module = Get-Module DesktopOllamaModelTools
        foreach ($driftTarget in @('corpus','oracle')) {
            $driftCorpusPath = Join-Path $TestDrive ("frozen-$driftTarget-corpus.json")
            $driftOraclePath = Join-Path $TestDrive ("frozen-$driftTarget-oracle.json")
            Copy-Item -LiteralPath $script:CorpusPath -Destination $driftCorpusPath
            Copy-Item -LiteralPath $script:OraclePath -Destination $driftOraclePath
            $frozenCorpus = Get-Content -LiteralPath $driftCorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $frozenOracle = Get-Content -LiteralPath $driftOraclePath -Raw -Encoding UTF8 | ConvertFrom-Json
            $drift = & $module {
                param($Corpus,$Oracle,$CorpusPath,$OraclePath,$Target)
                $projection = New-AwxCorpusProjection -Corpus $Corpus -CorpusPath $CorpusPath -OraclePath $OraclePath
                $cleanReason = $null
                try { Assert-AwxFrozenInputProjection -FrozenProjection $projection -Corpus $Corpus -Oracle $Oracle -CorpusPath $CorpusPath -OraclePath $OraclePath }
                catch { $cleanReason = $_.Exception.Message }
                $targetPath = $(if ($Target -ceq 'corpus') {$CorpusPath} else {$OraclePath})
                [IO.File]::AppendAllText($targetPath,"`r`n")
                $driftReason = $null
                try { Assert-AwxFrozenInputProjection -FrozenProjection $projection -Corpus $Corpus -Oracle $Oracle -CorpusPath $CorpusPath -OraclePath $OraclePath }
                catch { $driftReason = $_.Exception.Message }
                [pscustomobject]@{cleanReason=$cleanReason;driftReason=$driftReason}
            } $frozenCorpus $frozenOracle $driftCorpusPath $driftOraclePath $driftTarget
            $drift.cleanReason | Should -BeNullOrEmpty
            $drift.driftReason | Should -BeExactly 'lineage-missing'
        }
    }

    It 'has no environment, binding, pull, remove, or auth mutation path' {
        Test-Path -LiteralPath $script:CliPath -PathType Leaf | Should -BeTrue
        $production = (Get-Content -LiteralPath $script:CliPath -Raw -Encoding UTF8) + (Get-Content -LiteralPath $script:ModulePath -Raw -Encoding UTF8)
        $production | Should -Not -Match '(?i)SetEnvironmentVariable|\bsetx(?:\.exe)?\b|Remove-Item\s+Env:|\$env:[A-Za-z_][A-Za-z0-9_]*\s*='
        $production | Should -Not -Match '(?im)^\s*(?:&\s*)?ollama(?:\.exe)?\s+(pull|rm|remove|create|cp)\b'
        $production | Should -Not -Match '(?i)Authorization\s*=|Bearer\s+[A-Za-z0-9._-]+'
    }

    It 'rejects report fields and values outside the public privacy contract' {
        { Assert-AwxReportPrivacy ([pscustomobject]@{schemaVersion=2;rawResponse='secret'}) } | Should -Throw '*report-privacy-invalid*'
        $valid = New-AwxValidateOnlyReport -Corpus $script:Corpus -Oracle $script:Oracle -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath
        { Assert-AwxReportPrivacy $valid } | Should -Not -Throw
        $schemaString = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $schemaString.schemaVersion = '2'
        { Assert-AwxReportPrivacy $schemaString } | Should -Throw '*report-privacy-invalid*'
        $scalarReason = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $scalarReason.reasonCodes = 'live-evidence-not-requested'
        { Assert-AwxReportPrivacy $scalarReason } | Should -Throw '*report-privacy-invalid*'
        $duplicateReason = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $duplicateReason.reasonCodes = @('live-evidence-not-requested','live-evidence-not-requested')
        { Assert-AwxReportPrivacy $duplicateReason } | Should -Throw '*report-privacy-invalid*'
        $stringDuration = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $stringDuration.durationMs = '0'
        { Assert-AwxReportPrivacy $stringDuration } | Should -Throw '*report-privacy-invalid*'
        $nonStringHash = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $nonStringHash.corpus.corpusSha256 = 123
        { Assert-AwxReportPrivacy $nonStringHash } | Should -Throw '*report-privacy-invalid*'
        $multiReason = New-AwxLiveFailureReport -Corpus $script:Corpus -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -DurationMs 1 -ReasonCode 'candidate-digest-changed'; $multiReason.reasonCodes = @('candidate-digest-changed','warm-sample-insufficient')
        { Assert-AwxReportPrivacy $multiReason } | Should -Throw '*report-privacy-invalid*'
        $wrongLane = ConvertTo-AwxPublicPacket $script:FamilyAPackets[0]; $wrongLane.passed = $false; $wrongLane.reasonCodes = @('warm-sample-insufficient')
        { Assert-AwxPublicPacketProjection $wrongLane } | Should -Throw '*report-privacy-invalid*'
        $uuid = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $uuid.benchmarkId = 'GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
        { Assert-AwxReportPrivacy $uuid } | Should -Throw '*report-privacy-invalid*'
        $path = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $path.corpus.corpusId = 'C:\private\model-store'
        { Assert-AwxReportPrivacy $path } | Should -Throw '*report-privacy-invalid*'
    }

    It 'maps HTTP failures to bounded categories and still emits a valid HOLD report' {
        (Get-AwxBoundedReasonCode ([InvalidOperationException]::new('ollama-http-failed:/api/tags'))) | Should -BeExactly 'tags-evidence-incomplete'
        (Get-AwxBoundedReasonCode ([InvalidOperationException]::new('ollama-http-failed:/api/ps'))) | Should -BeExactly 'ps-evidence-incomplete'
        (Get-AwxBoundedReasonCode ([InvalidOperationException]::new('ollama-http-failed:/api/chat'))) | Should -BeExactly 'benchmark-runtime-failed'
        (Get-AwxBoundedReasonCode ([InvalidOperationException]::new('ollama-http-failed:/api/generate'))) | Should -BeExactly 'model-unload-unproven'
        $failure = New-AwxLiveFailureReport -Corpus $script:Corpus -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -DurationMs 1 -ReasonCode 'tags-evidence-incomplete'
        $failure.verdict | Should -BeExactly 'HOLD'
        { Assert-AwxReportPrivacy $failure } | Should -Not -Throw

        $tokens = $null; $errors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile($script:ModulePath,[ref]$tokens,[ref]$errors)
        $errors.Count | Should -Be 0
        $liveFunction = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Invoke-AwxLiveBenchmarkReport'},$true))
        $liveFunction.Count | Should -Be 1
        $commands = @($liveFunction[0].FindAll({param($node) $node -is [Management.Automation.Language.CommandAst]},$true))
        $finalReportOffset = @($commands | Where-Object {$_.GetCommandName() -ceq 'New-AwxLiveReport'} | ForEach-Object {$_.Extent.StartOffset} | Sort-Object)[-1]
        $finalBudgetOffset = @($commands | Where-Object {$_.GetCommandName() -ceq 'Get-AwxLiveRemainingSec'} | ForEach-Object {$_.Extent.StartOffset} | Sort-Object)[-1]
        $finalBudgetOffset | Should -BeGreaterThan $finalReportOffset
    }

    It 'returns exactly nine strict falsification facts for the module-owned packet constructor' {
        $evidence = Invoke-AwxFalsificationProbes -Corpus $script:Corpus -Oracle $script:Oracle
        @($evidence.PSObject.Properties.Name) | Should -Be @('constRejected','shapeOnlyRejected','extraValueRejected','truncationRejected','timeoutRejected','missingHashRejected','orderRejected','oracleIsolated','p95Correct')
        foreach ($property in $evidence.PSObject.Properties) { $property.Value | Should -BeOfType System.Boolean; $property.Value | Should -BeTrue }
        ($evidence | ConvertTo-Json -Depth 5) | Should -Not -Match 'evidenceSha256|complete|reasonCodes'
    }
}
