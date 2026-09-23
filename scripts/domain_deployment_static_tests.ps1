$ErrorActionPreference = "Stop"

$failures = New-Object System.Collections.Generic.List[string]

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Needle
    )
    if (-not $Text.Contains($Needle)) {
        $script:failures.Add("$Name missing <$Needle>") | Out-Null
    }
}

function Assert-NotContains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Needle
    )
    if ($Text.Contains($Needle)) {
        $script:failures.Add("$Name still contains <$Needle>") | Out-Null
    }
}

$publicUrlSupportSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\api\PublicUrlSupport.java')
$uploadSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\web\UploadController.java')
$applicationYaml = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\resources\application.yml')
$applicationProdYaml = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\resources\application-prod.yml')
$domainPreflightSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'domain_public_https_preflight.ps1')
$domainStartSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'domain_public_https_start.ps1')
$goalNextAutoSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'goal_next_auto.ps1')
$nettySource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\netty\NettyServerConfig.java')
$tomcatDualPortSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\config\TomcatDualPortConfig.java')
$chatOpenSecuritySource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\security\ChatOpenSecurityConfig.java')
$appSecuritySource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\config\AppSecurityConfig.java')
$customSecuritySource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\config\CustomSecurityConfig.java')
$identitySource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\abandonware\ai\agent\identity\IdentityInterceptor.java')
$ownerKeySource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\web\OwnerKeyBootstrapFilter.java')
$adminTokenSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\security\AdminTokenGuardInterceptor.java')
$adminSessionSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\service\AdminSessionService.java')
$trialQuotaSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\trial\TrialQuotaInterceptor.java')
$agentPipelineHealthSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\agent\context\AgentPipelineHealthController.java')
$externalEvidenceReaderSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\main\java\com\example\lms\agent\context\ExternalAgentEvidenceReader.java')

Assert-Contains "Public URL support accepts https public base" $publicUrlSupportSource 'startsWith("https://")'
Assert-Contains "Public URL support accepts http public base" $publicUrlSupportSource 'startsWith("http://")'
Assert-Contains "Public URL support strips configured paths from public origin" $publicUrlSupportSource 'return scheme + "://" + host + port;'
Assert-Contains "Public URL support accepts schemeless public base URL" $publicUrlSupportSource '"https://" + raw'
Assert-Contains "Upload controller supports public path token links" $uploadSource '@GetMapping("/{token}")'
Assert-Contains "Application YAML defines central public base URL" $applicationYaml 'public-base-url: ${APP_PUBLIC_BASE_URL:https://abandonwareai.kro.kr}'
Assert-Contains "Application YAML defines configurable HTTPS port" $applicationYaml 'https-port: ${SERVER_HTTPS_PORT:443}'
Assert-Contains "Application YAML defines configurable HTTP port" $applicationYaml 'http-port: ${SERVER_HTTP_PORT:80}'
Assert-Contains "Application YAML derives forced HTTPS from SSL enabled" $applicationYaml 'force-https: ${SECURITY_FORCE_HTTPS:${SERVER_SSL_ENABLED:false}}'
Assert-NotContains "Application SSL config should not bind blank key alias" $applicationYaml 'key-alias: ${SERVER_SSL_KEY_ALIAS:}'
Assert-NotContains "Prod SSL config should not bind blank key alias" $applicationProdYaml 'key-alias: ${SERVER_SSL_KEY_ALIAS:}'
Assert-Contains "Domain HTTPS preflight checks public domain" $domainPreflightSource 'abandonwareai.kro.kr'
Assert-Contains "Domain HTTPS preflight separates TCP connect host" $domainPreflightSource '[string]$ConnectHost = ""'
Assert-Contains "Domain HTTPS preflight accepts configurable HTTPS port" $domainPreflightSource '[int]$HttpsPort = 443'
Assert-Contains "Domain HTTPS preflight accepts configurable HTTP port" $domainPreflightSource '[int]$HttpPort = 80'
Assert-Contains "Domain HTTPS preflight can require browser-trusted TLS" $domainPreflightSource '[switch]$RequireTrustedCertificate'
Assert-Contains "Domain HTTPS preflight supports TLS termination mode" $domainPreflightSource '[ValidateSet("auto", "embedded", "offload")]'
Assert-Contains "Domain HTTPS preflight can accept proxy TLS offload" $domainPreflightSource 'tls.offload.accepted'
Assert-Contains "Domain HTTPS preflight only requires embedded SSL when selected" $domainPreflightSource 'embeddedSslRequired'
Assert-Contains "Domain HTTPS preflight checks runtime TLS trust" $domainPreflightSource 'ssl.runtime.trusted-certificate'
Assert-Contains "Domain HTTPS preflight uses SslStream trust validation" $domainPreflightSource 'System.Net.Security.SslStream'
Assert-Contains "Domain HTTPS preflight classifies public TCP listener failures" $domainPreflightSource 'public-listener-unreachable'
Assert-Contains "Domain HTTPS preflight requires 443 listener for trusted TLS proof" $domainPreflightSource '$httpsTcpRequired = [bool]$RequireRunning -or [bool]$RequireTrustedCertificate'
Assert-Contains "Domain HTTPS preflight requires SSL enabled env" $domainPreflightSource 'SERVER_SSL_ENABLED'
Assert-Contains "Domain HTTPS preflight requires keystore env" $domainPreflightSource 'SERVER_SSL_KEY_STORE'
Assert-Contains "Domain HTTPS preflight validates certificate domain" $domainPreflightSource 'ssl.certificate.matches-domain'
Assert-Contains "Domain HTTPS preflight validates certificate SAN DNS names" $domainPreflightSource '"2.5.29.17"'
Assert-Contains "Domain HTTPS preflight uses compatible hash helper" $domainPreflightSource 'function Hash-Text12'
Assert-NotContains "Domain HTTPS preflight avoids unavailable SHA256 HashData" $domainPreflightSource 'SHA256]::HashData'
Assert-NotContains "Domain HTTPS preflight avoids unavailable Convert ToHexString" $domainPreflightSource 'Convert]::ToHexString'
Assert-NotContains "Domain HTTPS preflight avoids reserved PowerShell Host variable assignment" $domainPreflightSource '$host ='
Assert-NotContains "Domain HTTPS preflight must not pass keystore password to keytool" $domainPreflightSource '-storepass'
Assert-Contains "Domain HTTPS start checks SSL enabled env" $domainStartSource 'SERVER_SSL_ENABLED'
Assert-Contains "Domain HTTPS start checks keystore env" $domainStartSource 'SERVER_SSL_KEY_STORE'
Assert-Contains "Domain HTTPS start supports TLS termination mode" $domainStartSource '[ValidateSet("embedded", "offload")]'
Assert-Contains "Domain HTTPS start supports proxy backend port" $domainStartSource '[int]$BackendPort = 8080'
Assert-Contains "Domain HTTPS start supports explicit Netty port" $domainStartSource '[int]$NettyPort = 18082'
Assert-Contains "Domain HTTPS start passes explicit Netty port" $domainStartSource '--netty.port=$NettyPort'
Assert-Contains "Domain HTTPS start rejects management Netty overlap" $domainStartSource 'management-netty-overlap'
Assert-Contains "Domain HTTPS start skips SSL secret gate in offload mode" $domainStartSource '$embeddedSslRequired = $TlsMode -eq "embedded"'
Assert-Contains "Domain HTTPS start records offload mode" $domainStartSource 'tlsMode=$TlsMode'
Assert-Contains "Domain HTTPS start marks public proxy mode" $domainStartSource '--server.ssl.enabled=false'
Assert-Contains "Domain HTTPS start health probe honors forwarded proto" $domainStartSource 'X-Forwarded-Proto: https'
Assert-Contains "Domain HTTPS start maps embedded HTTPS or offload backend port" $domainStartSource '$serverPort = if ($embeddedSslRequired) { $HttpsPort } else { $BackendPort }'
Assert-Contains "Domain HTTPS start passes Tomcat HTTPS connector port" $domainStartSource '--server.https-port=$HttpsPort'
Assert-Contains "Domain HTTPS start passes Tomcat HTTP connector port" $domainStartSource '--server.http-port=$HttpPort'
Assert-Contains "Domain HTTPS start converts keystore path to file URI" $domainStartSource '$keyStoreLocation = ([System.Uri]::new($resolvedKeyStorePath)).AbsoluteUri'
Assert-Contains "Domain HTTPS start passes non-secret keystore location" $domainStartSource '--server.ssl.key-store=$keyStoreLocation'
Assert-Contains "Domain HTTPS start passes non-secret keystore type" $domainStartSource '--server.ssl.key-store-type=$keyStoreType'
Assert-Contains "Domain HTTPS start passes optional non-secret key alias" $domainStartSource '--server.ssl.key-alias=$keyAlias'
Assert-Contains "Domain HTTPS start keeps management local-only" $domainStartSource '--management.server.address=127.0.0.1'
Assert-Contains "Domain HTTPS start keeps management health on HTTP" $domainStartSource '--management.server.ssl.enabled=false'
Assert-Contains "Domain HTTPS start enables forced HTTPS for public launch" $domainStartSource '--security.force-https=true'
Assert-Contains "Domain HTTPS start uses mode-aware readiness probe" $domainStartSource '$healthUrl = "${healthScheme}://127.0.0.1:$serverPort/"'
Assert-Contains "Domain HTTPS start allows redirect readiness codes" $domainStartSource '"301", "302", "307", "308"'
Assert-Contains "Domain HTTPS start readiness probe has connect timeout" $domainStartSource '"--connect-timeout", "2"'
Assert-Contains "Domain HTTPS start readiness probe has max timeout" $domainStartSource '"--max-time", "4"'
Assert-Contains "Domain HTTPS start captures HTTP redirect target" $domainStartSource '"%{http_code} %{redirect_url}"'
Assert-Contains "Domain HTTPS start public probe has connect timeout" $domainStartSource '--connect-timeout 3'
Assert-Contains "Domain HTTPS start public probe has max timeout" $domainStartSource '--max-time 8'
Assert-Contains "Domain HTTPS start rejects HTTP redirect loops" $domainStartSource '$httpRedirectOk = $httpRedirectUrl.StartsWith("https://$Domain")'
Assert-Contains "Domain HTTPS start cleans up runtime when public probe fails" $domainStartSource 'publicProbe.cleanup=stopping'
Assert-Contains "Domain HTTPS start classifies failed public probe before cleanup" $domainStartSource 'classification=public-https-response evidence_needed=trusted-public-domain-response'
$publicProbeStart = $domainStartSource.IndexOf('[AWX][domain-start] publicProbe http=')
$publicProbeEnd = if ($publicProbeStart -ge 0) { $domainStartSource.IndexOf('[AWX][domain-start] publicProbe.cleanup=stopping', $publicProbeStart) } else { -1 }
$publicProbeBranch = if ($publicProbeStart -ge 0 -and $publicProbeEnd -gt $publicProbeStart) {
    $domainStartSource.Substring($publicProbeStart, $publicProbeEnd - $publicProbeStart)
} else {
    $domainStartSource
}
Assert-NotContains "Domain HTTPS public probe must not classify app HTTPS failure as provider disabled" $publicProbeBranch 'classification=provider-disabled'
Assert-Contains "Domain HTTPS start writes a pid file" $domainStartSource 'domain-public-https.latest.pid'
Assert-Contains "Domain HTTPS start logs non-secret launch arguments" $domainStartSource '[AWX][domain-start] launchArgs='
Assert-Contains "Domain HTTPS start uses Start-Process splat" $domainStartSource '$startProcessSplat = @{'
Assert-Contains "Domain HTTPS start redirects logs only for verification cleanup mode" $domainStartSource 'if ($StopAfterReady) {'
Assert-Contains "Domain HTTPS start records detached deploy log mode" $domainStartSource 'deployLogMode=detached'
Assert-Contains "Domain HTTPS start supports verification cleanup" $domainStartSource 'StopAfterReady'
Assert-NotContains "Domain HTTPS start must not put key-store-password on command line" $domainStartSource '--server.ssl.key-store-password'
Assert-NotContains "Domain HTTPS start must not put key-password on command line" $domainStartSource '--server.ssl.key-password'
Assert-NotContains "Domain HTTPS start must not put management key-store-password on command line" $domainStartSource '--management.server.ssl.key-store-password'
Assert-NotContains "Domain HTTPS start must not put management key-password on command line" $domainStartSource '--management.server.ssl.key-password'
Assert-NotContains "Domain HTTPS start should not configure management keystore" $domainStartSource '--management.server.ssl.key-store='
Assert-NotContains "Domain HTTPS start must not pass password to keytool" $domainStartSource '-storepass'
Assert-Contains "Netty WebSocket log reads public base URL" $nettySource 'app.public-base-url:${APP_PUBLIC_BASE_URL:${PUBLIC_BASE_URL:}}'
Assert-Contains "Netty WebSocket log maps HTTPS to secure WebSocket" $nettySource 'wsScheme = "wss"'
Assert-Contains "Netty WebSocket log advertises public endpoint" $nettySource 'public={}'
Assert-Contains "Netty WebSocket log keeps internal localhost separate" $nettySource 'internal=ws://localhost:'
Assert-NotContains "Netty WebSocket log should not advertise localhost-only listening URL" $nettySource 'WebSocket listening on ws://localhost'
Assert-Contains "Tomcat dual port reads configurable HTTPS port" $tomcatDualPortSource 'server.https-port:443'
Assert-Contains "Tomcat dual port reads configurable HTTP port" $tomcatDualPortSource 'server.http-port:80'
Assert-Contains "Tomcat dual port applies explicit SSL object" $tomcatDualPortSource 'factory.setSsl(ssl)'
Assert-Contains "Tomcat dual port runs after default customizers" $tomcatDualPortSource 'Ordered.LOWEST_PRECEDENCE'
Assert-Contains "Tomcat dual port reads canonical SSL key-store property" $tomcatDualPortSource 'server.ssl.key-store'
Assert-Contains "Tomcat dual port reads SSL key-store env fallback" $tomcatDualPortSource 'SERVER_SSL_KEY_STORE'
Assert-Contains "Tomcat dual port normalizes Windows keystore paths" $tomcatDualPortSource 'normalizeKeyStoreLocation'
Assert-NotContains "Tomcat dual port should not hard-code HTTPS port" $tomcatDualPortSource 'factory.setPort(443)'
Assert-NotContains "Tomcat dual port should not hard-code HTTP port" $tomcatDualPortSource 'http.setPort(80)'
Assert-Contains "Chat open security reads force HTTPS switch" $chatOpenSecuritySource 'security.force-https:false'
Assert-Contains "Chat open security reads HTTPS port" $chatOpenSecuritySource 'server.https-port:443'
Assert-Contains "Chat open security reads HTTP port" $chatOpenSecuritySource 'server.http-port:80'
Assert-Contains "Chat open security can require secure channel" $chatOpenSecuritySource '.requiresChannel(channel -> channel.anyRequest().requiresSecure())'
Assert-Contains "Chat open security maps HTTP port to HTTPS port" $chatOpenSecuritySource '.portMapper(mapper -> mapper.http(httpPort).mapsTo(httpsPort))'
Assert-Contains "Chat open security normalizes configured CORS origins" $chatOpenSecuritySource 'normalizeCorsOrigin(origin)'
Assert-Contains "Chat open security strips public base URL paths from CORS origins" $chatOpenSecuritySource 'originUri.getRawPath()'
Assert-Contains "Default security reads force HTTPS switch" $appSecuritySource 'security.force-https:false'
Assert-Contains "Default security reads HTTPS port" $appSecuritySource 'server.https-port:443'
Assert-Contains "Default security reads HTTP port" $appSecuritySource 'server.http-port:80'
Assert-Contains "Default security can require secure channel" $appSecuritySource '.requiresChannel(channel -> channel.anyRequest().requiresSecure())'
Assert-Contains "Default security maps HTTP port to HTTPS port" $appSecuritySource '.portMapper(mapper -> mapper.http(httpPort).mapsTo(httpsPort))'
Assert-Contains "Admin security reads HTTPS port" $customSecuritySource 'server.https-port:443'
Assert-Contains "Admin security reads HTTP port" $customSecuritySource 'server.http-port:80'
Assert-Contains "Admin security maps HTTP port to HTTPS port" $customSecuritySource '.portMapper(mapper -> mapper.http(httpPort).mapsTo(httpsPort))'
Assert-Contains "Identity cookie uses ResponseCookie" $identitySource 'ResponseCookie.from("gid", gid)'
Assert-Contains "Identity cookie marks secure for HTTPS" $identitySource '.secure(isHttpsRequest(request))'
Assert-Contains "Identity cookie honors forwarded proto" $identitySource 'X-Forwarded-Proto'
Assert-Contains "Identity cookie honors forwarded header proto" $identitySource 'proto=https'
Assert-Contains "Identity cookie keeps SameSite Lax" $identitySource '.sameSite("Lax")'
Assert-NotContains "Identity cookie should not emit duplicate servlet cookie" $identitySource 'response.addCookie(cookie)'
Assert-Contains "OwnerKey cookie marks secure for HTTPS" $ownerKeySource '.secure(isHttpsRequest(req))'
Assert-Contains "OwnerKey cookie honors forwarded proto" $ownerKeySource 'X-Forwarded-Proto'
Assert-Contains "OwnerKey cookie honors forwarded header proto" $ownerKeySource 'proto=https'
Assert-Contains "OwnerKey filter runs before security redirect" $ownerKeySource '@Order(Ordered.HIGHEST_PRECEDENCE + 1)'
Assert-Contains "Admin token cookie marks secure for HTTPS" $adminTokenSource '.secure(isHttpsRequest(req))'
Assert-Contains "Admin token cookie honors forwarded proto" $adminTokenSource 'X-Forwarded-Proto'
Assert-Contains "Admin token cookie honors forwarded header proto" $adminTokenSource 'proto=https'
Assert-Contains "Admin session cookie uses ResponseCookie" $adminSessionSource 'ResponseCookie.from(COOKIE_NAME, token)'
Assert-Contains "Admin session cookie marks secure for public HTTPS" $adminSessionSource '.secure(true)'
Assert-Contains "Admin session cookie keeps SameSite Lax" $adminSessionSource '.sameSite("Lax")'
Assert-Contains "Trial quota cookie uses ResponseCookie" $trialQuotaSource 'ResponseCookie.from(props.getCookieName(), signed)'
Assert-Contains "Trial quota cookie marks secure for public HTTPS" $trialQuotaSource '.secure(true)'
Assert-Contains "Trial quota cookie keeps SameSite Lax" $trialQuotaSource '.sameSite("Lax")'
Assert-Contains "Pipeline health distinguishes public-domain browser proof scope" $agentPipelineHealthSource 'public-domain-ui-proof'
Assert-Contains "Pipeline health keeps local browser proof scope for localhost" $agentPipelineHealthSource 'local-ui-proof'
Assert-Contains "Pipeline health classifies public listener failures" $agentPipelineHealthSource 'public-listener-unreachable'
Assert-Contains "Pipeline health asks for listener open before public browser rerun" $agentPipelineHealthSource 'open_public_80_443_then_rerun_browser_public_domain_ui_smoke'
Assert-Contains "Pipeline health detects browser connection refused status" $agentPipelineHealthSource 'connection_refused'
Assert-Contains "Pipeline health overrides stale public listener smoke action" $agentPipelineHealthSource 'publicListenerUnreachable ? recommendedNextAction'
Assert-Contains "Goal-next freshness watches domain preflight dependency" $goalNextAutoSource 'domain_public_https_preflight.ps1'
Assert-Contains "Goal-next external row keeps browser evidence-needed reason" $externalEvidenceReaderSource 'browserUseEvidenceNeeded'
Assert-Contains "Goal-next external row reads browser evidence-needed reason" $externalEvidenceReaderSource 'browserUse.path("evidenceNeeded")'

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Host "[domain-deploy-test][FAIL] $failure"
    }
    Write-Host "[domain-deploy-test][SUMMARY] failed=$($failures.Count)"
    exit 1
}

Write-Host "[domain-deploy-test][SUMMARY] failed=0"
