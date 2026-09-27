[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [ValidateSet('Render','Install','Status','Uninstall')]
    [string]$Action = 'Render',
    [string]$PolicyPath = 'C:\ProgramData\AbandonWareX\desktop_patchdrop_auto_intake.policy.json',
    [string]$OutputPath = '',
    [string]$ConfirmActionSha256 = '',
    [switch]$TestMode,
    [switch]$CompactJson,
    [object]$DesktopEvidence = $null
)

Set-StrictMode -Version Latest
$consumerLibraryPath = Join-Path $PSScriptRoot 'desktop_patchdrop_auto_intake.ps1'
if (-not (Test-Path -LiteralPath $consumerLibraryPath -PathType Leaf)) { throw [System.InvalidOperationException]::new('consumer-library-missing') }
$savedManagerPolicyPath = $PolicyPath
$savedManagerTestMode = $TestMode
. $consumerLibraryPath -PolicyPath $savedManagerPolicyPath -TestMode:$savedManagerTestMode
$PolicyPath = $savedManagerPolicyPath
$TestMode = $savedManagerTestMode
$script:AwxAutoIntakeTaskName = 'AwxDesktopPatchDropAutoIntake'
$script:AwxAutoIntakeDesktopRoot = 'C:\AbandonWare\demo-1\demo-1\src'
$script:AwxAutoIntakeConsumerPath = Join-Path $script:AwxAutoIntakeDesktopRoot 'scripts\desktop_patchdrop_auto_intake.ps1'
$script:AwxAutoIntakeExecutionTimeLimitMinutes = 15
$script:AwxAutoIntakeTaskPath = '\'
$script:AwxAutoIntakeTaskAttestationPath = 'C:\ProgramData\AbandonWareX\desktop_patchdrop_auto_intake.attestation.json'
$script:AwxAutoIntakeProvisioningAnchorKey = 'HKLM:\SOFTWARE\AbandonWareX\DesktopPatchDropAutoIntake'
$script:AwxAutoIntakeTrustedSids = @('S-1-5-18','S-1-5-32-544')
$script:AwxAutoIntakeTaskMaxInputBytes = 65536
$script:AwxAutoIntakeTaskStartBoundary = '2000-01-01T00:00:00'

function Get-AwxAutoIntakeTaskSha256 {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    return ([System.BitConverter]::ToString(([System.Security.Cryptography.SHA256]::Create()).ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
}

function Get-AwxAutoIntakeTaskPathSha256 {
    param([Parameter(Mandatory)][string]$Path)
    $fullPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
    return Get-AwxAutoIntakeTaskSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($fullPath))
}

function Get-AwxAutoIntakeTaskFileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    $handle = $null
    try {
        $handle = [System.IO.File]::Open([System.IO.Path]::GetFullPath($Path),[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,[System.IO.FileShare]::Read)
        return Get-AwxAutoIntakeTaskSha256 (Get-AwxAutoIntakeTaskHandleBytes -Handle $handle -MaxBytes $script:AwxAutoIntakeTaskMaxInputBytes)
    } finally { if($null -ne $handle){$handle.Dispose()} }
}

function New-AwxAutoIntakeTaskDefinition {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$PolicyPath, [byte[]]$PolicyBytes = $null)
    if ([string]::IsNullOrWhiteSpace($PolicyPath)) { throw [System.ArgumentException]::new('policy-path-invalid') }
    $policyFullPath = [System.IO.Path]::GetFullPath($PolicyPath)
    try {
        if ($null -eq $PolicyBytes) {
            $policyHandle = [System.IO.File]::Open($policyFullPath,[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,[System.IO.FileShare]::Read)
            try { $PolicyBytes = Get-AwxAutoIntakeTaskHandleBytes -Handle $policyHandle -MaxBytes $script:AwxAutoIntakeTaskMaxInputBytes } finally { $policyHandle.Dispose() }
        }
        $policy = ConvertFrom-AwxAutoIntakePolicyBytes -Bytes $PolicyBytes
    } catch { throw [System.InvalidOperationException]::new('policy-invalid') }
    $arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $script:AwxAutoIntakeConsumerPath + '" -PolicyPath "' + $policyFullPath + '"'
    return [pscustomobject][ordered]@{
        schemaVersion = 'awx.desktop-patchdrop-auto-intake.task.v1'
        taskName = $script:AwxAutoIntakeTaskName
        taskPath = $script:AwxAutoIntakeTaskPath
        principalSid = 'S-1-5-18'
        logonType = 'ServiceAccount'
        runLevel = 'Highest'
        execute = 'powershell.exe'
        arguments = $arguments
        workingDirectory = $script:AwxAutoIntakeDesktopRoot
        policyPath = $policyFullPath
        triggerType = 'Once'
        startBoundary = $script:AwxAutoIntakeTaskStartBoundary
        intervalMinutes = [int]$policy.pollIntervalMinutes
        repetitionDuration = ''
        multipleInstances = 'IgnoreNew'
        executionTimeLimitMinutes = $script:AwxAutoIntakeExecutionTimeLimitMinutes
        enabled = $false
    }
}

function Get-AwxAutoIntakeTaskActionSha256 {
    [CmdletBinding()]
    param([Parameter(Mandatory)][object]$Definition)
    if ($Definition.execute -isnot [string] -or $Definition.arguments -isnot [string] -or $Definition.workingDirectory -isnot [string]) { throw [System.ArgumentException]::new('task-definition-invalid') }
    $parts = @([string]$Definition.execute,[string]$Definition.arguments,[string]$Definition.workingDirectory)
    $canonical = ($parts | ForEach-Object { ([string]$_.Length) + ':' + $_ }) -join [char]0
    return Get-AwxAutoIntakeTaskSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($canonical))
}

function ConvertTo-AwxAutoIntakeInvariantMinutes {
    param([object]$Value)
    try {
        $span=if($Value -is [timespan]){[timespan]$Value}else{[System.Xml.XmlConvert]::ToTimeSpan([string]$Value)}
        if($span.Ticks -lt 0 -or ($span.Ticks % [timespan]::TicksPerMinute) -ne 0){return -1}
        $minutes=$span.Ticks/[timespan]::TicksPerMinute
        if($minutes -gt [int]::MaxValue){return -1}
        return [int]$minutes
    } catch { return -1 }
}

function ConvertTo-AwxAutoIntakeStartBoundary {
    param([object]$Value)
    try {
        if($Value -is [datetime]){$parsed=[datetime]$Value}
        else{
            $text=[string]$Value
            if($text -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$'){return ''}
            $parsed=[datetime]::ParseExact($text,'yyyy-MM-ddTHH:mm:ss',[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None)
        }
        if(($parsed.Ticks % [timespan]::TicksPerSecond) -ne 0){return ''}
        return $parsed.ToString('yyyy-MM-ddTHH:mm:ss',[Globalization.CultureInfo]::InvariantCulture)
    } catch { return '' }
}

function Get-AwxAutoIntakeInstalledTriggerType {
    param([Parameter(Mandatory)][object]$Trigger)
    try{
        if($null -ne $Trigger.PSObject.Properties['TriggerType']){return [string]$Trigger.TriggerType}
        if($null -ne $Trigger.PSObject.Properties['CimClass'] -and $null -ne $Trigger.CimClass){
            if([string]$Trigger.CimClass.CimClassName -ceq 'MSFT_TaskTimeTrigger'){return 'Once'}
        }
        return ''
    }catch{return ''}
}

function Get-AwxAutoIntakeTaskIdentityProjection {
    param([Parameter(Mandatory)][object]$Definition)
    try {
        $required = @('principalSid','logonType','runLevel','execute','arguments','workingDirectory','triggerType','startBoundary','intervalMinutes','repetitionDuration','multipleInstances','executionTimeLimitMinutes','enabled')
        foreach($name in $required){if($null -eq $Definition.PSObject.Properties[$name]){throw 'invalid'}}
        if([string]$Definition.principalSid -cne 'S-1-5-18' -or [string]$Definition.logonType -cne 'ServiceAccount' -or [string]$Definition.runLevel -cne 'Highest' -or [string]$Definition.triggerType -cne 'Once' -or [string]$Definition.repetitionDuration -cne '' -or $Definition.enabled -isnot [bool] -or [bool]$Definition.enabled){throw 'invalid'}
        if([int]$Definition.intervalMinutes -lt 1 -or [int]$Definition.intervalMinutes -gt 60 -or [int]$Definition.executionTimeLimitMinutes -ne $script:AwxAutoIntakeExecutionTimeLimitMinutes){throw 'invalid'}
        return [pscustomobject][ordered]@{
            principalSid=[string]$Definition.principalSid;logonType=[string]$Definition.logonType;runLevel=[string]$Definition.runLevel
            execute=[string]$Definition.execute;arguments=[string]$Definition.arguments;workingDirectory=[string]$Definition.workingDirectory
            triggerType=[string]$Definition.triggerType;startBoundary=(ConvertTo-AwxAutoIntakeStartBoundary $Definition.startBoundary)
            intervalMinutes=[int]$Definition.intervalMinutes;repetitionDuration=[string]$Definition.repetitionDuration
            executionTimeLimitMinutes=[int]$Definition.executionTimeLimitMinutes;multipleInstances=[string]$Definition.multipleInstances;enabled=[bool]$Definition.enabled
        }
    } catch { throw [System.ArgumentException]::new('task-definition-invalid') }
}

function Get-AwxAutoIntakeTaskIdentitySha256 {
    [CmdletBinding()]
    param([Parameter(Mandatory)][object]$Definition)
    $projection = Get-AwxAutoIntakeTaskIdentityProjection $Definition
    $json = $projection | ConvertTo-Json -Compress
    return Get-AwxAutoIntakeTaskSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($json))
}

function ConvertFrom-AwxAutoIntakeInstalledTask {
    param([Parameter(Mandatory)][object]$Task)
    try {
        $actions=@($Task.Actions)
        $triggers=@(if($null -ne $Task.PSObject.Properties['Triggers']){$Task.Triggers}else{$Task.Trigger})
        if($actions.Count -ne 1 -or $triggers.Count -ne 1 -or $null -eq $Task.Principal -or $null -eq $Task.Settings){throw 'invalid'}
        $principalSid=Resolve-AwxAutoIntakeTaskSid $Task.Principal.UserId
        $trigger=$triggers[0]
        $triggerType=Get-AwxAutoIntakeInstalledTriggerType $trigger
        if($triggerType -cne 'Once'){throw 'invalid'}
        $intervalValue=if($null -ne $trigger.PSObject.Properties['RepetitionInterval']){$trigger.RepetitionInterval}else{$trigger.Repetition.Interval}
        $durationValue=if($null -ne $trigger.PSObject.Properties['Repetition'] -and $null -ne $trigger.Repetition.PSObject.Properties['Duration']){[string]$trigger.Repetition.Duration}else{''}
        $startValue=if($null -ne $trigger.PSObject.Properties['StartBoundary']){$trigger.StartBoundary}else{$trigger.At}
        return [pscustomobject][ordered]@{
            principalSid=$principalSid;logonType=[string]$Task.Principal.LogonType;runLevel=[string]$Task.Principal.RunLevel
            execute=[string]$actions[0].Execute;arguments=[string]$actions[0].Arguments;workingDirectory=[string]$actions[0].WorkingDirectory
            triggerType=$triggerType;startBoundary=(ConvertTo-AwxAutoIntakeStartBoundary $startValue)
            intervalMinutes=(ConvertTo-AwxAutoIntakeInvariantMinutes $intervalValue);repetitionDuration=$durationValue
            executionTimeLimitMinutes=(ConvertTo-AwxAutoIntakeInvariantMinutes $Task.Settings.ExecutionTimeLimit);multipleInstances=[string]$Task.Settings.MultipleInstances;enabled=[bool]$Task.Settings.Enabled
        }
    } catch { throw [System.ArgumentException]::new('task-readback-invalid') }
}

function Get-AwxAutoIntakeInstalledTaskIdentitySha256 {
    param([Parameter(Mandatory)][object]$Task)
    try { return Get-AwxAutoIntakeTaskIdentitySha256 -Definition (ConvertFrom-AwxAutoIntakeInstalledTask $Task) } catch { return '' }
}

function ConvertTo-AwxAutoIntakeTaskRenderJson {
    param([Parameter(Mandatory)][object]$Definition)
    $render = [ordered]@{
        schemaVersion = [string]$Definition.schemaVersion
        taskName = [string]$Definition.taskName
        taskPath = [string]$Definition.taskPath
        principalSid = [string]$Definition.principalSid
        logonType = [string]$Definition.logonType
        runLevel = [string]$Definition.runLevel
        execute = [string]$Definition.execute
        arguments = [string]$Definition.arguments
        workingDirectory = [string]$Definition.workingDirectory
        policyPath = [string]$Definition.policyPath
        triggerType = [string]$Definition.triggerType
        startBoundary = [string]$Definition.startBoundary
        intervalMinutes = [int]$Definition.intervalMinutes
        repetitionDuration = [string]$Definition.repetitionDuration
        multipleInstances = [string]$Definition.multipleInstances
        executionTimeLimitMinutes = [int]$Definition.executionTimeLimitMinutes
        enabled = [bool]$Definition.enabled
        actionSha256 = Get-AwxAutoIntakeTaskActionSha256 -Definition $Definition
        taskIdentitySha256 = Get-AwxAutoIntakeTaskIdentitySha256 -Definition $Definition
    }
    return (($render | ConvertTo-Json -Compress -Depth 3) + "`n")
}

function Write-AwxAutoIntakeTaskRender {
    param([Parameter(Mandatory)][string]$Content, [Parameter(Mandatory)][string]$Path)
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $directory = Split-Path -Parent $fullPath
    if ([string]::IsNullOrEmpty($directory) -or -not (Test-Path -LiteralPath $directory -PathType Container)) { throw [System.ArgumentException]::new('output-path-invalid') }
    [System.IO.File]::WriteAllText($fullPath, $Content, [System.Text.UTF8Encoding]::new($false))
}

function New-AwxAutoIntakeTaskResult {
    param([string]$Decision, [string]$FailureClass = '', [bool]$TaskPresent = $false, [string]$TaskState = '', [string]$TaskIdentitySha256 = '')
    return [pscustomobject][ordered]@{ decision = $Decision; failureClass = $FailureClass; taskPresent = $TaskPresent; taskState = $TaskState; taskIdentitySha256 = $TaskIdentitySha256 }
}

function Get-AwxAutoIntakeTaskSystemSid { return [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value }
function Get-AwxAutoIntakeTaskMachineBindingSha256 {
    $machineGuid = (Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Cryptography' -Name MachineGuid -ErrorAction Stop).MachineGuid
    $systemDrive = [System.IO.Path]::GetPathRoot([Environment]::SystemDirectory).TrimEnd('\')
    $volume = Get-CimInstance -ClassName Win32_LogicalDisk -Filter ("DeviceID='" + $systemDrive + "'") -ErrorAction Stop
    return Get-AwxAutoIntakeTaskSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes(([string]$machineGuid + [char]0 + [string]$volume.VolumeSerialNumber)))
}
function Test-AwxAutoIntakeTaskReparseFreePath {
    param([Parameter(Mandatory)][string]$Path)
    try {
        $full = [System.IO.Path]::GetFullPath($Path); $root = [System.IO.Path]::GetPathRoot($full); $relative = $full.Substring($root.Length); $current = $root
        foreach ($part in $relative.Split([char]'\')) { if ($part) { $current = Join-Path $current $part; if (((Get-Item -LiteralPath $current -Force -ErrorAction Stop).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $false } } }
        return $true
    } catch { return $false }
}
function Resolve-AwxAutoIntakeTaskSid {
    param([object]$Identity)
    try { $text=if($Identity -is [string]){$Identity}elseif($null -ne $Identity.PSObject.Properties['Value']){[string]$Identity.Value}else{[string]$Identity}; if($text -cmatch '^S-\d(-\d+)+$'){return $text}; return ([System.Security.Principal.NTAccount]::new($text)).Translate([System.Security.Principal.SecurityIdentifier]).Value } catch { return '' }
}
function Test-AwxAutoIntakeTaskSecureFileSystemAcl {
    param([Parameter(Mandatory)][object]$Acl)
    try {
        $owner = Resolve-AwxAutoIntakeTaskSid $Acl.Owner
        if ([string]::IsNullOrEmpty($owner) -or $script:AwxAutoIntakeTrustedSids -cnotcontains $owner) { return $false }
        $mutating = [System.Security.AccessControl.FileSystemRights]::WriteData -bor [System.Security.AccessControl.FileSystemRights]::AppendData -bor [System.Security.AccessControl.FileSystemRights]::WriteAttributes -bor [System.Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor [System.Security.AccessControl.FileSystemRights]::Delete -bor [System.Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor [System.Security.AccessControl.FileSystemRights]::ChangePermissions -bor [System.Security.AccessControl.FileSystemRights]::TakeOwnership -bor [System.Security.AccessControl.FileSystemRights]::Modify -bor [System.Security.AccessControl.FileSystemRights]::FullControl -bor [System.Security.AccessControl.FileSystemRights]::Write
        foreach ($rule in @($Acl.Access)) {
            if (($rule.PropagationFlags -band [System.Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0) { continue }
            $identity = Resolve-AwxAutoIntakeTaskSid $rule.IdentityReference
            if ([string]::IsNullOrEmpty($identity)) { return $false }
            if (($rule.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Allow) -and (($rule.FileSystemRights -band $mutating) -ne 0) -and ($script:AwxAutoIntakeTrustedSids -cnotcontains $identity)) { return $false }
        }
        return $true
    } catch { return $false }
}
function Test-AwxAutoIntakeTaskSecureSystemFile {
    param([Parameter(Mandatory)][string]$Path)
    try { return Test-AwxAutoIntakeTaskSecureFileSystemAcl (Get-Acl -LiteralPath ([IO.Path]::GetFullPath($Path)) -ErrorAction Stop) } catch { return $false }
}
function Get-AwxAutoIntakeTaskReplacementBoundaryPaths {
    param([Parameter(Mandatory)][string]$Path)
    try {
        $full=[IO.Path]::GetFullPath($Path)
        $root=[IO.Path]::GetPathRoot($full)
        if([string]::IsNullOrWhiteSpace($root)){throw 'invalid'}
        $current=if($full.Equals($root,[StringComparison]::OrdinalIgnoreCase)){$root}else{$full.TrimEnd('\')}
        $paths=[Collections.Generic.List[string]]::new()
        while(-not [string]::IsNullOrWhiteSpace($current)){
            $paths.Add($current)
            if($current.Equals($root,[StringComparison]::OrdinalIgnoreCase)){break}
            $parent=Split-Path -Parent $current
            if([string]::IsNullOrWhiteSpace($parent) -or $parent.Equals($current,[StringComparison]::OrdinalIgnoreCase)){throw 'invalid'}
            $parentFull=[IO.Path]::GetFullPath($parent)
            $current=if($parentFull.Equals($root,[StringComparison]::OrdinalIgnoreCase)){$root}else{$parentFull.TrimEnd('\')}
        }
        if($paths.Count -eq 0 -or -not $paths[$paths.Count-1].Equals($root,[StringComparison]::OrdinalIgnoreCase)){throw 'invalid'}
        return @($paths)
    } catch { throw [System.InvalidOperationException]::new('replacement-boundary-invalid') }
}
function Test-AwxAutoIntakeTaskSecureReplacementBoundary {
    param([Parameter(Mandatory)][string]$Path)
    try {
        if(-not (Test-AwxAutoIntakeTaskReparseFreePath $Path)){return $false}
        foreach($boundary in @(Get-AwxAutoIntakeTaskReplacementBoundaryPaths $Path)){if(-not (Test-AwxAutoIntakeTaskSecureSystemFile $boundary)){return $false}}
        return $true
    } catch { return $false }
}
function Test-AwxAutoIntakeTaskSecureRegistryAcl {
    param([Parameter(Mandatory)][object]$Acl)
    try {
        $owner = Resolve-AwxAutoIntakeTaskSid $Acl.Owner
        if ([string]::IsNullOrEmpty($owner) -or $script:AwxAutoIntakeTrustedSids -cnotcontains $owner) { return $false }
        $mutating = [Security.AccessControl.RegistryRights]::SetValue -bor [Security.AccessControl.RegistryRights]::CreateSubKey -bor [Security.AccessControl.RegistryRights]::Delete -bor [Security.AccessControl.RegistryRights]::ChangePermissions -bor [Security.AccessControl.RegistryRights]::TakeOwnership -bor [Security.AccessControl.RegistryRights]::WriteKey -bor [Security.AccessControl.RegistryRights]::FullControl
        foreach ($rule in @($Acl.Access)) {
            if (($rule.PropagationFlags -band [System.Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0) { continue }
            $identity = Resolve-AwxAutoIntakeTaskSid $rule.IdentityReference
            if ([string]::IsNullOrEmpty($identity)) { return $false }
            if (($rule.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Allow) -and (($rule.RegistryRights -band $mutating) -ne 0) -and ($script:AwxAutoIntakeTrustedSids -cnotcontains $identity)) { return $false }
        }
        return $true
    } catch { return $false }
}
function Test-AwxAutoIntakeTaskSecureRegistryKey {
    param([Parameter(Mandatory)][string]$Path)
    try { return Test-AwxAutoIntakeTaskSecureRegistryAcl (Get-Acl -LiteralPath $Path -ErrorAction Stop) } catch { return $false }
}
function Get-AwxAutoIntakeTaskProvisioningAnchor {
    try { if(-not (Test-AwxAutoIntakeTaskSecureRegistryKey $script:AwxAutoIntakeProvisioningAnchorKey)){return $null}; $raw=(Get-ItemProperty -LiteralPath $script:AwxAutoIntakeProvisioningAnchorKey -Name ProvisioningAnchorJson -ErrorAction Stop).ProvisioningAnchorJson; $anchor=$raw|ConvertFrom-Json -ErrorAction Stop; $required=@('schemaVersion','machineBindingSha256','provisioningIdentitySha256'); if(@($anchor.PSObject.Properties.Name).Count -ne $required.Count){return $null};foreach($name in $required){if($null -eq $anchor.PSObject.Properties[$name]){return $null}};if([string]$anchor.schemaVersion -cne 'awx.desktop-patchdrop-auto-intake.provisioning.v1'){return $null};foreach($name in @('machineBindingSha256','provisioningIdentitySha256')){if([string]$anchor.$name -cnotmatch '^[a-f0-9]{64}$'){return $null}};return $anchor }catch{return $null}
}
function Read-AwxAutoIntakeTaskAttestation {
    $required = @('schemaVersion','machineBindingSha256','provisioningIdentitySha256','desktopRootPathSha256','consumerPathSha256','consumerContentSha256','policyPathSha256','policyContentSha256')
    try {
        $handle=[IO.File]::Open($script:AwxAutoIntakeTaskAttestationPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
        try{$bytes=Get-AwxAutoIntakeTaskHandleBytes -Handle $handle -MaxBytes $script:AwxAutoIntakeTaskMaxInputBytes}finally{$handle.Dispose()}
        $value = [Text.UTF8Encoding]::new($false,$true).GetString($bytes) | ConvertFrom-Json -ErrorAction Stop
        if (@($value.PSObject.Properties.Name).Count -ne $required.Count) { return $null }
        foreach($name in $required) { if($null -eq $value.PSObject.Properties[$name]) { return $null } }
        if ([string]$value.schemaVersion -cne 'awx.desktop-patchdrop-auto-intake.attestation.v1') { return $null }
        foreach($name in $required | Where-Object {$_ -ne 'schemaVersion'}) { if([string]$value.$name -cnotmatch '^[a-f0-9]{64}$') { return $null } }
        return $value
    } catch { return $null }
}
function Test-AwxAutoIntakeTaskProductionAttestation {
    param([Parameter(Mandatory)][object]$Definition)
    try {
        $root = [IO.Path]::GetFullPath($script:AwxAutoIntakeDesktopRoot).TrimEnd('\'); $consumer=[IO.Path]::GetFullPath($script:AwxAutoIntakeConsumerPath); $policy=[IO.Path]::GetFullPath([string]$Definition.policyPath); $attestation=[IO.Path]::GetFullPath($script:AwxAutoIntakeTaskAttestationPath)
        if ((Get-AwxAutoIntakeTaskSystemSid) -cne 'S-1-5-18' -or $policy -notmatch '^[A-Za-z]:\\' -or $policy.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) { return $false }
        foreach($path in @($consumer,$policy,$attestation)) { if(-not (Test-AwxAutoIntakeTaskSecureReplacementBoundary $path)) { return $false } }
        $a=Read-AwxAutoIntakeTaskAttestation; $anchor=Get-AwxAutoIntakeTaskProvisioningAnchor; if($null -eq $a -or $null -eq $anchor){return $false}
        return ([string]$a.machineBindingSha256 -ceq (Get-AwxAutoIntakeTaskMachineBindingSha256)) -and ([string]$a.provisioningIdentitySha256 -ceq [string]$anchor.provisioningIdentitySha256) -and ([string]$anchor.machineBindingSha256 -ceq (Get-AwxAutoIntakeTaskMachineBindingSha256)) -and ([string]$a.desktopRootPathSha256 -ceq (Get-AwxAutoIntakeTaskPathSha256 $root)) -and ([string]$a.consumerPathSha256 -ceq (Get-AwxAutoIntakeTaskPathSha256 $consumer)) -and ([string]$a.consumerContentSha256 -ceq (Get-AwxAutoIntakeTaskFileSha256 $consumer)) -and ([string]$a.policyPathSha256 -ceq (Get-AwxAutoIntakeTaskPathSha256 $policy)) -and ([string]$a.policyContentSha256 -ceq (Get-AwxAutoIntakeTaskFileSha256 $policy))
    } catch { return $false }
}
function Open-AwxAutoIntakeTaskReadHandle { param([string]$Path) return [IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read) }
function Get-AwxAutoIntakeTaskHandleBytes {
    param([Parameter(Mandatory)][IO.Stream]$Handle,[int64]$MaxBytes=$script:AwxAutoIntakeTaskMaxInputBytes)
    try{
        if(-not $Handle.CanRead -or -not $Handle.CanSeek -or $Handle.Length -lt 1 -or $Handle.Length -gt $MaxBytes){throw 'invalid'}
        $Handle.Position=0;$memory=[IO.MemoryStream]::new([int]$Handle.Length)
        try{$buffer=[byte[]]::new(8192);$total=[int64]0;while(($read=$Handle.Read($buffer,0,$buffer.Length)) -gt 0){$total+=$read;if($total -gt $MaxBytes){throw 'invalid'};$memory.Write($buffer,0,$read)};if($total -ne $Handle.Length){throw 'invalid'};return $memory.ToArray()}finally{$memory.Dispose()}
    }catch{throw [System.InvalidOperationException]::new('task-input-invalid')}
}
function Test-AwxAutoIntakeTaskEnabledPolicyBytes { param([byte[]]$Bytes) try { $policy=ConvertFrom-AwxAutoIntakePolicyBytes -Bytes $Bytes; return [bool]$policy.enabled }catch{return $false} }

function Get-AwxAutoIntakeInstalledTaskActionSha256 {
    param([Parameter(Mandatory)][object]$Task)
    try {
        $actions = @($Task.Actions)
        if ($actions.Count -ne 1) { return '' }
        return Get-AwxAutoIntakeTaskActionSha256 ([pscustomobject]@{execute=[string]$actions[0].Execute;arguments=[string]$actions[0].Arguments;workingDirectory=[string]$actions[0].WorkingDirectory})
    } catch { return '' }
}

function Test-AwxAutoIntakeTaskHeldInputs {
    param(
        [Parameter(Mandatory)][object]$Definition,
        [Parameter(Mandatory)][IO.Stream]$ConsumerHandle,
        [Parameter(Mandatory)][IO.Stream]$PolicyHandle,
        [Parameter(Mandatory)][byte[]]$ExpectedConsumerBytes,
        [Parameter(Mandatory)][byte[]]$ExpectedPolicyBytes
    )
    try{
        $attestation=Read-AwxAutoIntakeTaskAttestation;if($null -eq $attestation){return $false}
        $consumerBytes=Get-AwxAutoIntakeTaskHandleBytes -Handle $ConsumerHandle -MaxBytes $script:AwxAutoIntakeTaskMaxInputBytes
        $policyBytes=Get-AwxAutoIntakeTaskHandleBytes -Handle $PolicyHandle -MaxBytes $script:AwxAutoIntakeTaskMaxInputBytes
        if(-not [Linq.Enumerable]::SequenceEqual([byte[]]$consumerBytes,[byte[]]$ExpectedConsumerBytes) -or -not [Linq.Enumerable]::SequenceEqual([byte[]]$policyBytes,[byte[]]$ExpectedPolicyBytes)){return $false}
        if((Get-AwxAutoIntakeTaskSha256 $consumerBytes) -cne [string]$attestation.consumerContentSha256 -or (Get-AwxAutoIntakeTaskSha256 $policyBytes) -cne [string]$attestation.policyContentSha256){return $false}
        $strictPolicy=ConvertFrom-AwxAutoIntakePolicyBytes -Bytes $policyBytes
        return [int]$strictPolicy.pollIntervalMinutes -eq [int]$Definition.intervalMinutes
    }catch{return $false}
}

function Invoke-AwxAutoIntakeTaskManager {
    [CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
    param([string]$Action, [string]$PolicyPath, [string]$OutputPath, [string]$ConfirmActionSha256, [switch]$TestMode, [object]$DesktopEvidence)
    if (@('Render','Install','Status','Uninstall') -cnotcontains $Action) { return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'invalid-action' }
    if ($Action -ceq 'Render') {
        try{$definition = New-AwxAutoIntakeTaskDefinition -PolicyPath $PolicyPath}catch{return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'policy-invalid'}
        $content = ConvertTo-AwxAutoIntakeTaskRenderJson -Definition $definition
        if (-not [string]::IsNullOrWhiteSpace($OutputPath)) { Write-AwxAutoIntakeTaskRender -Content $content -Path $OutputPath }
        return [pscustomobject][ordered]@{ decision = 'RENDERED'; actionSha256 = Get-AwxAutoIntakeTaskActionSha256 $definition; taskIdentitySha256=Get-AwxAutoIntakeTaskIdentitySha256 $definition; bytes = [System.Text.UTF8Encoding]::new($false).GetByteCount($content) }
    }
    if ($TestMode) { return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'testmode-render-only' }
    try{$attestationDefinition=[pscustomobject]@{policyPath=[IO.Path]::GetFullPath($PolicyPath)}}catch{return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'desktop-attestation-unproven'}
    if (-not (Test-AwxAutoIntakeTaskProductionAttestation $attestationDefinition)) { return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'desktop-attestation-unproven' }
    $mutex=$null; $owned=$false; $consumerHandle=$null; $policyHandle=$null
    try {
        $mutex=[Threading.Mutex]::new($false,'Local\AwxDesktopPatchDropAutoIntakeTask'); $owned=$mutex.WaitOne(0); if(-not $owned){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-lock-conflict'}
        if($Action -eq 'Uninstall'){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'atomic-uninstall-unavailable'}
        $consumerHandle=Open-AwxAutoIntakeTaskReadHandle $script:AwxAutoIntakeConsumerPath;$policyHandle=Open-AwxAutoIntakeTaskReadHandle $attestationDefinition.policyPath
        $consumerBytes=Get-AwxAutoIntakeTaskHandleBytes -Handle $consumerHandle -MaxBytes $script:AwxAutoIntakeTaskMaxInputBytes;$policyBytes=Get-AwxAutoIntakeTaskHandleBytes -Handle $policyHandle -MaxBytes $script:AwxAutoIntakeTaskMaxInputBytes
        try{$definition=New-AwxAutoIntakeTaskDefinition -PolicyPath $attestationDefinition.policyPath -PolicyBytes $policyBytes}catch{return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'policy-invalid'}
        if(-not (Test-AwxAutoIntakeTaskProductionAttestation $definition) -or -not (Test-AwxAutoIntakeTaskHeldInputs -Definition $definition -ConsumerHandle $consumerHandle -PolicyHandle $policyHandle -ExpectedConsumerBytes $consumerBytes -ExpectedPolicyBytes $policyBytes)){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-input-hash-drift'}
        $expectedHash=Get-AwxAutoIntakeTaskActionSha256 $definition;$expectedIdentity=Get-AwxAutoIntakeTaskIdentitySha256 $definition
        if($Action -eq 'Status') {
            if(-not (Test-AwxAutoIntakeTaskProductionAttestation $definition) -or -not (Test-AwxAutoIntakeTaskHeldInputs -Definition $definition -ConsumerHandle $consumerHandle -PolicyHandle $policyHandle -ExpectedConsumerBytes $consumerBytes -ExpectedPolicyBytes $policyBytes)){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-input-hash-drift'}
            $existing=@(Get-ScheduledTask -TaskName $script:AwxAutoIntakeTaskName -TaskPath $script:AwxAutoIntakeTaskPath -ErrorAction SilentlyContinue);if($existing.Count -gt 1){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-state-ambiguous'};if($existing.Count -eq 0){return [pscustomobject][ordered]@{taskPresent=$false;taskState='';taskIdentitySha256=''}};return [pscustomobject][ordered]@{taskPresent=$true;taskState=[string]$existing[0].State;taskIdentitySha256=(Get-AwxAutoIntakeInstalledTaskIdentitySha256 $existing[0])}
        }
        if($Action -eq 'Install') {
            if(-not (Test-AwxAutoIntakeTaskEnabledPolicyBytes $policyBytes)){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'policy-not-enabled'}
            if($ConfirmActionSha256 -cne $expectedHash){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-action-unconfirmed'}
            $initial=@(Get-ScheduledTask -TaskName $script:AwxAutoIntakeTaskName -TaskPath $script:AwxAutoIntakeTaskPath -ErrorAction SilentlyContinue);if($initial.Count -gt 1){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-state-ambiguous'};if($initial.Count -eq 1){if([bool]$initial[0].Settings.Enabled -or (Get-AwxAutoIntakeInstalledTaskIdentitySha256 $initial[0]) -cne $expectedIdentity){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-identity-changed'};return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'desktop-enable-proof-required' -TaskPresent $true -TaskState ([string]$initial[0].State) -TaskIdentitySha256 $expectedIdentity}
            if(-not $PSCmdlet.ShouldProcess(($script:AwxAutoIntakeTaskPath+$script:AwxAutoIntakeTaskName),'Register Scheduled Task')){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-registration-not-confirmed'}
            if(@(Get-ScheduledTask -TaskName $script:AwxAutoIntakeTaskName -TaskPath $script:AwxAutoIntakeTaskPath -ErrorAction SilentlyContinue).Count -ne 0){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-race-detected'}
            if(-not (Test-AwxAutoIntakeTaskProductionAttestation $definition) -or -not (Test-AwxAutoIntakeTaskHeldInputs -Definition $definition -ConsumerHandle $consumerHandle -PolicyHandle $policyHandle -ExpectedConsumerBytes $consumerBytes -ExpectedPolicyBytes $policyBytes)){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-input-hash-drift'}
            $scheduledAction=New-ScheduledTaskAction -Execute $definition.execute -Argument $definition.arguments -WorkingDirectory $definition.workingDirectory;$trigger=New-ScheduledTaskTrigger -Once -At ([datetime]::ParseExact($definition.startBoundary,'yyyy-MM-ddTHH:mm:ss',[Globalization.CultureInfo]::InvariantCulture)) -RepetitionInterval (New-TimeSpan -Minutes ([int]$definition.intervalMinutes));$settings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes ([int]$definition.executionTimeLimitMinutes));$principal=New-ScheduledTaskPrincipal -UserId $definition.principalSid -LogonType $definition.logonType -RunLevel $definition.runLevel;$taskDefinition=New-ScheduledTask -Action $scheduledAction -Trigger $trigger -Settings $settings -Principal $principal;$taskDefinition.Settings.Enabled=$false
            try { Register-ScheduledTask -TaskName $script:AwxAutoIntakeTaskName -TaskPath $script:AwxAutoIntakeTaskPath -InputObject $taskDefinition -ErrorAction Stop | Out-Null } catch { return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-race-detected' }
            if(-not (Test-AwxAutoIntakeTaskProductionAttestation $definition) -or -not (Test-AwxAutoIntakeTaskHeldInputs -Definition $definition -ConsumerHandle $consumerHandle -PolicyHandle $policyHandle -ExpectedConsumerBytes $consumerBytes -ExpectedPolicyBytes $policyBytes)){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-input-hash-drift'}
            $post=@(Get-ScheduledTask -TaskName $script:AwxAutoIntakeTaskName -TaskPath $script:AwxAutoIntakeTaskPath -ErrorAction SilentlyContinue);if($post.Count -ne 1 -or [bool]$post[0].Settings.Enabled -or (Get-AwxAutoIntakeInstalledTaskIdentitySha256 $post[0]) -cne $expectedIdentity){return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-postreadback-mismatch'};return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'desktop-enable-proof-required' -TaskPresent $true -TaskState ([string]$post[0].State) -TaskIdentitySha256 $expectedIdentity
        }
        return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-operation-unproven'
    } catch { return New-AwxAutoIntakeTaskResult -Decision 'HOLD' -FailureClass 'task-operation-unproven' } finally { if($consumerHandle){$consumerHandle.Dispose()};if($policyHandle){$policyHandle.Dispose()};if($owned){$mutex.ReleaseMutex()};if($mutex){$mutex.Dispose()} }
}

if ($MyInvocation.InvocationName -ne '.') {
    $nativeTaskResult=Invoke-AwxAutoIntakeTaskManager -Action $Action -PolicyPath $PolicyPath -OutputPath $OutputPath -ConfirmActionSha256 $ConfirmActionSha256 -TestMode:$TestMode -DesktopEvidence $DesktopEvidence
    if($CompactJson){
        [Console]::Out.WriteLine(($nativeTaskResult|ConvertTo-Json -Compress -Depth 3))
        $nativeTaskExit=if([string]$nativeTaskResult.decision -ceq 'RENDERED'){0}elseif([string]$nativeTaskResult.decision -ceq 'HOLD'){2}else{4}
        [Environment]::Exit($nativeTaskExit)
    }
    $nativeTaskResult
}
