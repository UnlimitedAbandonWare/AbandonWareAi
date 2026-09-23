[CmdletBinding()]
param(
    [ValidateSet('Local','File')][string]$Mode='Local',
    [string]$BaseUrl='http://127.0.0.1:18220',
    [string]$AudioFile='',
    [string]$AudioManifest='',
    [string]$OutDir='',
    [string]$BuildHostId='soniox-verification',
    [ValidateRange(10,60)][int]$MaxConnectionSeconds=60
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
if(!$OutDir){$OutDir=Join-Path $root ('data/agent-handoff/codex-autonomy/soniox-verification-'+[guid]::NewGuid().ToString('N'))}
$OutDir=[IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Path $OutDir -Force|Out-Null
$result=[ordered]@{schema=1;observedAt=[DateTime]::UtcNow.ToString('o');diagnosticRunId=[guid]::NewGuid().ToString();mode=$Mode;
    local='not_performed';audioFile='not_performed';microphone='not_performed';glasses='not_performed';providerAttempt='not_observed';
    transcriptStored=$false;audioStored=$false;clockBasis='server monotonic metrics; client request round trips use one Stopwatch';status='running'}
$exitCode=1;$receiver=$null;$phone=$null;$pcm=$null;$bound=$null;$started=$false;$watch=$null
$savedEnvironment=@{};foreach($name in @('AWX_SPLIT_BUILD_OUTPUTS','AWX_BUILD_HOST_ID','GRADLE_USER_HOME')){$savedEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function New-Client {
    $handler=[Net.Http.HttpClientHandler]::new();$handler.CookieContainer=[Net.CookieContainer]::new()
    $client=[Net.Http.HttpClient]::new($handler);$client.Timeout=[TimeSpan]::FromSeconds(35)
    $client.DefaultRequestHeaders.Add('Origin',$BaseUrl);$client.DefaultRequestHeaders.Add('X-Display-Client','1')
    return $client
}
function Request($client,[string]$path,$body=$null,[switch]$Cleanup) {
    $response=$null;$cancel=[Threading.CancellationTokenSource]::new()
    try {
        $timeout=if($Cleanup){2000}elseif($started -and $watch){[Math]::Min(35000,($MaxConnectionSeconds*1000)-5000-$watch.ElapsedMilliseconds)}else{35000}
        if($timeout -le 0){throw 'connection_deadline'}
        $cancel.CancelAfter([int]$timeout)
        if($null -eq $body){$response=$client.GetAsync($BaseUrl+$path,$cancel.Token).GetAwaiter().GetResult()}
        else{$content=[Net.Http.StringContent]::new(($body|ConvertTo-Json -Depth 8 -Compress),[Text.Encoding]::UTF8,'application/json');try{$response=$client.PostAsync($BaseUrl+$path,$content,$cancel.Token).GetAwaiter().GetResult()}finally{$content.Dispose()}}
        $raw=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if(!$response.IsSuccessStatusCode){$reason='http_'+[int]$response.StatusCode;try{$safe=$raw|ConvertFrom-Json;if($safe.reason -match '^[a-z_]{1,64}$'){$reason=$safe.reason}}catch{};throw $reason}
        if($path.EndsWith('.html')){return $null}
        return $raw|ConvertFrom-Json
    }finally{if($response){$response.Dispose()};$cancel.Dispose()}
}
function Safe-Fields($value,[string[]]$names){$safe=[ordered]@{};foreach($name in $names){if($null -ne $value.$name){$safe[$name]=$value.$name}};return $safe}
function Semantic-Parts($caption){
    $parts=0
    if($caption){$normal=$caption.text.ToLowerInvariant()-replace '[^\p{L}\p{N}]','';if($normal.Contains('회의') -and ($normal.Contains('세시') -or $normal.Contains('3시'))){$parts=$parts-bor 1};if($normal.Contains('meeting') -and ($normal.Contains('three') -or $normal.Contains('3'))){$parts=$parts-bor 2}}
    return $parts
}
try {
    $hashes=[ordered]@{}
    $paths=@('main/resources/soniox-sidecar/session.mjs','main/resources/soniox-sidecar/server.mjs','main/resources/soniox-sidecar/package-lock.json','main/resources/soniox-sidecar/package.json',
        'main/java/com/example/lms/assist/SonioxSidecarManager.java','main/java/com/example/lms/assist/SonioxNodeTransport.java',
        'main/java/com/example/lms/assist/ConversateAsrBridge.java','main/java/com/example/lms/assist/ConversateCloudStt.java',
        'main/java/com/example/lms/assist/FailoverAsrTransport.java','main/java/com/example/lms/assist/SonioxAsrTransport.java','main/java/com/example/lms/service/stt/SonioxSttService.java',
        'main/java/com/example/lms/assist/DisplayConversateController.java','main/java/com/example/lms/assist/ConversateSessionService.java',
        'main/resources/static/assets/display/app.js','main/resources/static/assets/display/display-conversate.js','main/resources/static/assets/display/display-voice.js','main/resources/static/assets/display/index.html','main/resources/static/conversate/pcm-worklet.js')
    foreach($path in $paths){$hashes[$path]=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
    $result.sourceHashes=$hashes
    if($Mode -eq 'Local') {
        & node --test src/test/node/soniox-sidecar.test.mjs src/test/js/display-voice.test.cjs src/test/js/display-stop-lifecycle.test.cjs scripts/fold6_display_capture_tests.cjs scripts/display_receiver_rag_contract_tests.cjs *> (Join-Path $OutDir 'node.log')
        if($LASTEXITCODE){throw 'node_tests_failed'}
        $env:AWX_SPLIT_BUILD_OUTPUTS='1';$env:AWX_BUILD_HOST_ID=$BuildHostId
        $env:GRADLE_USER_HOME=Join-Path $env:USERPROFILE '.gradle-awx-desktop'
        & ./gradlew.bat --no-daemon --console=plain --project-cache-dir ('.gradle-'+$BuildHostId) test --tests 'com.example.lms.assist.*Soniox*' --tests 'com.example.lms.assist.FailoverAsrTransportTest' --tests 'com.example.lms.assist.ConversateAsr*Test' --tests 'com.example.lms.assist.ConversateCloudStt*Test' --tests 'com.example.lms.assist.DisplayConversateHttpTest' --tests 'com.example.lms.assist.ConversateCaptionTest' --tests 'com.example.lms.service.stt.SonioxSttServiceTest' checkSourceSetHygiene checkLangchain4jVersionPurity bootJar *> (Join-Path $OutDir 'java.log')
        if($LASTEXITCODE){throw 'java_tests_failed'}
        $result.local='pass';$result.status='local_verified';$exitCode=0
    } else {
        $uri=[uri]$BaseUrl
        if($uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.UserInfo){throw 'owned_loopback_runtime_required'}
        if(!$AudioFile -or !$AudioManifest){throw 'explicit_pcm_and_manifest_required'}
        $meta=Get-Content -LiteralPath $AudioManifest -Raw -Encoding UTF8|ConvertFrom-Json
        if($meta.kind -ne 'public_synthetic_speech' -or $meta.rate -ne 16000 -or $meta.channels -ne 1 -or $meta.bits -ne 16){throw 'pcm_format_unverified'}
        # This semantic check is intentionally bound to the public bilingual fixture, not arbitrary recordings.
        if($meta.pcmSha256 -ne 'c3941b9833945b09f9cbeea77d5456cc899dad88e933ccb9fc608a86a1bce2b3'){throw 'expected_speech_fixture_required'}
        $pcm=[IO.File]::ReadAllBytes([IO.Path]::GetFullPath($AudioFile))
        if($pcm.Length -lt 640 -or $pcm.Length -gt 1600000 -or $pcm.Length%640 -or $meta.audioBytes -ne $pcm.Length -or $meta.pcmSha256 -ne (Get-FileHash -LiteralPath $AudioFile).Hash.ToLowerInvariant()){throw 'pcm_hash_or_bounds_failed'}
        # CREATE_NEW prevents accidental replay into the same result directory.
        $marker=[IO.File]::Open((Join-Path $OutDir 'file-attempt.marker'),[IO.FileMode]::CreateNew);$marker.Dispose()
        $receiver=New-Client;$phone=New-Client
        Request $receiver '/assets/display/index.html'|Out-Null;Request $phone '/assets/display/index.html'|Out-Null
        $receiverId=[guid]::NewGuid().ToString('N');$phoneId=[guid]::NewGuid().ToString('N')
        $view=Request $receiver '/api/assist/display/transcription' @{clientId=$receiverId;epoch=0}
        $beforeProvider=$view.audioRuntime
        if($beforeProvider.configuredProvider -ne 'soniox' -or $beforeProvider.routing -ne 'fixed' -or $beforeProvider.model -ne 'stt-rt-v5' -or !$beforeProvider.nodeReady -or !$beforeProvider.nodeConfigured){throw 'existing_soniox_node_admission_required'}
        $result.sidecarAtStart=Safe-Fields $beforeProvider @('nodeReady','nodeConfigured','nodeState')
        $displayBound=@{assistId=$view.assistId;epoch=$view.epoch;clientId=$receiverId}
        $code=Request $receiver '/api/assist/display/link/code' $displayBound
        Request $phone '/api/assist/display/link/join' @{clientId=$phoneId;code=$code.code;requestId=[guid]::NewGuid().ToString()}|Out-Null
        Request $receiver '/api/assist/display/link/approve' $displayBound|Out-Null
        $bound=@{assistId=$view.assistId;epoch=$view.epoch;clientId=$phoneId}
        $result.audioFormat=@{encoding='pcm_s16le';sampleRate=16000;channels=1;bytes=$pcm.Length;sha256=$meta.pcmSha256;kind='public_synthetic_file'}
        # One start, no client reconnect or replay. At most two existing transports can be open
        # for this fixed provider; both are stopped within the 60-second capture ceiling.
        $watch=[Diagnostics.Stopwatch]::StartNew();$started=$true
        $view=Request $phone '/api/assist/display/audio/start' $bound
        $result.fileStart='ready';$offset=0;$seq=0;$lastPoll=-1000;$firstPartial=$null;$firstFinal=$null;$semanticParts=0
        while($offset -lt $pcm.Length) {
            if($watch.Elapsed.TotalSeconds -ge ($MaxConnectionSeconds-5)){throw 'connection_deadline'}
            if($watch.ElapsedMilliseconds-$lastPoll -ge 700){Request $receiver '/api/assist/display/poll' $displayBound|Out-Null;$lastPoll=$watch.ElapsedMilliseconds}
            $count=[Math]::Min(6400,$pcm.Length-$offset);$chunk=[byte[]]::new($count);[Array]::Copy($pcm,$offset,$chunk,0,$count)
            try{$request=@{}+$bound;$request.sequence=$seq++;$request.pcm=[Convert]::ToBase64String($chunk);$view=Request $phone '/api/assist/display/audio/chunk' $request}
            finally{[Array]::Clear($chunk,0,$chunk.Length);$request=$null}
            if($view.audioPartials -gt 0 -and $null -eq $firstPartial){$firstPartial=$watch.ElapsedMilliseconds}
            if($view.audioFinals -gt 0 -and $null -eq $firstFinal){$firstFinal=$watch.ElapsedMilliseconds}
            $semanticParts=$semanticParts-bor (Semantic-Parts $view.caption)
            $offset+=$count;Start-Sleep -Milliseconds 200
        }
        $stopBegan=$watch.ElapsedMilliseconds
        $finish=@{}+$bound;$finish.finish=$true
        $view=Request $phone '/api/assist/display/audio/stop' $finish;$started=$false
        $result.stopRoundTripMs=$watch.ElapsedMilliseconds-$stopBegan
        $result.captureConnectionUpperBoundMs=$watch.ElapsedMilliseconds
        $snapshot=$view;$provider=$snapshot.audioRuntime
        $result.audioMetrics=[ordered]@{chunks=$snapshot.audioChunks;partials=$snapshot.audioPartials;finals=$snapshot.audioFinals;state=$snapshot.audioState}
        $result.audioMetrics.runtime=Safe-Fields $provider @('diagnosticRunId','provider','transport','device','reason','failureReason','processedMs','providerResponse','firstPartialMs','finalAfterStopMs','fallbackGapMs','captionRenderAckMs','shutdownMs','stopReason')
        $semanticParts=$semanticParts-bor (Semantic-Parts $snapshot.caption)
        $result.newProviderAttempts=[long]$provider.soniox_attempts-[long]$beforeProvider.soniox_attempts
        $result.providerEvidence=Safe-Fields $provider @('providerState')
        foreach($key in @('attempts','handshakes','failures','successes','cancelled','connectedWallMs','acceptedAudioMs')){$name='soniox_'+$key;$result.providerEvidence[$key]=[long]$provider.$name-[long]$beforeProvider.$name}
        $result.providerEvidence.closedObserved=($provider.soniox_lastClosedAt -gt $beforeProvider.soniox_lastClosedAt -and $provider.providerState -ne 'IN_FLIGHT')
        $result.observedCaptureWallMs=$watch.ElapsedMilliseconds
        $result.aggregateConnectionUpperBoundMs=$result.captureConnectionUpperBoundMs*[Math]::Max(1,$result.newProviderAttempts)
        $result.firstPartialObservedMs=$firstPartial;$result.firstFinalObservedMs=$firstFinal;$result.semanticPartsMatched=if($semanticParts -eq 3){2}elseif($semanticParts){1}else{0}
        $result.providerAttempt=if($result.newProviderAttempts -gt 0 -and $result.providerEvidence.handshakes -gt 0){'observed'}else{'not_observed'}
        $result.audioFile=if($snapshot.audioFinals -gt 0 -and $semanticParts -eq 3 -and $result.providerAttempt -eq 'observed' -and $provider.transport -eq 'node_sdk' -and $provider.providerResponse -eq 'observed' -and $provider.stopReason -eq 'finished' -and $snapshot.audioState -eq 'STOPPED' -and $result.providerEvidence.closedObserved){'pass'}else{'failed'}
        $result.status=if($result.audioFile -eq 'pass'){'file_path_verified'}else{'file_transcription_unproven'}
        if($result.aggregateConnectionUpperBoundMs -gt 180000 -or $result.captureConnectionUpperBoundMs -gt ($MaxConnectionSeconds*1000)){throw 'connection_budget_exceeded'}
        if($result.audioFile -eq 'pass'){$exitCode=0}
    }
}catch {
    $reason=$_.Exception.Message;$result.status='failed';$result.reason=if($reason -match '^[a-z_0-9]{1,80}$'){$reason}else{'verification_exception'}
}finally {
    if($started -and $phone -and $bound){try{Request $phone '/api/assist/display/audio/stop' $bound -Cleanup|Out-Null;$result.stopRecovery='cancel_acknowledged'}catch{$result.stopRecovery='unconfirmed'}}
    if($receiver -and $bound){try{Request $receiver '/api/assist/display/link/unlink' $displayBound -Cleanup|Out-Null}catch{}}
    if($pcm){[Array]::Clear($pcm,0,$pcm.Length)}
    if($receiver){$receiver.Dispose()};if($phone){$phone.Dispose()}
    foreach($name in $savedEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process')}
    $result.sourceStable=$true
    foreach($path in $hashes.Keys){if((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -ne $hashes[$path]){$result.sourceStable=$false}}
    if(!$result.sourceStable){$result.status='evidence_needed';$result.reason='source_changed_during_verification';$exitCode=1}
    $result|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $OutDir 'result.json') -Encoding UTF8
    [pscustomobject]@{status=$result.status;reason=$result.reason;providerAttempt=$result.providerAttempt;resultPath=(Join-Path $OutDir 'result.json')}|ConvertTo-Json -Compress
}
exit $exitCode
