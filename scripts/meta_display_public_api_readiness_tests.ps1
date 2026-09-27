$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'start_rag_stack.ps1')
$script:requests = [Collections.Generic.List[object]]::new()
$script:validationStatus = 400
$script:validationType = 'application/json'
function Get-RagHttp {
    param($Url, $Method, $Body, $Headers)
    $script:requests.Add(@{url=$Url;method=$Method;body=$Body;headers=$Headers})
    if ($Url.EndsWith('/chat-ui')) { return @{status=200;content='/assets/interview/app.js'} }
    if ($Url.EndsWith('/assets/display/index.html')) { return @{status=200;content='<script src="display-core.js"></script><script src="lens.js?v=synthetic"></script>'} }
    return @{status=$script:validationStatus;contentType=$script:validationType}
}
$checks = 0
function Assert-Readiness($Condition, $Name) {
    if (-not $Condition) { throw ('FAIL: ' + $Name) }
    $script:checks++
}
Assert-Readiness (Test-RagWeb -Port 18180 -MetaDisplay) 'Meta malformed bootstrap readiness'
$request = $script:requests[$script:requests.Count - 1]
Assert-Readiness ($request.url.EndsWith('/api/assist/display/bootstrap')) 'only Display validation route'
Assert-Readiness ($request.method -eq 'POST') 'POST contract'
Assert-Readiness ($request.headers.Origin -eq 'http://127.0.0.1:18180') 'same origin'
Assert-Readiness ($request.headers['X-Display-Client'] -eq '1') 'required Display header'
Assert-Readiness ($request.body -eq '{"clientId":""}') 'invalid identity cannot allocate a session'
Assert-Readiness (@($script:requests | Where-Object {$_.url.EndsWith('/api/chat/sync')}).Count -eq 0) 'no legacy public inference probe'
foreach ($status in @(200,401,403,404,429,503)) {
    $script:validationStatus = $status
    Assert-Readiness (-not (Test-RagWeb -Port 18180 -MetaDisplay)) ('reject status ' + $status)
}
$script:validationStatus = 400; $script:validationType = 'text/html'
Assert-Readiness (-not (Test-RagWeb -Port 18180 -MetaDisplay)) 'HTML is not API readiness'
$script:validationType = 'application/json'; $script:requests.Clear()
Assert-Readiness (Test-RagWeb -Port 18080) 'generic launcher remains ready'
$request = $script:requests[$script:requests.Count - 1]
Assert-Readiness ($request.url.EndsWith('/api/chat/sync')) 'generic sync route preserved'
Assert-Readiness ($request.body -eq '{"message":""}') 'generic no-generation validation preserved'
Assert-Readiness ($null -eq $request.headers) 'generic request headers unchanged'
Write-Output "PASS: $checks public Display readiness checks"
