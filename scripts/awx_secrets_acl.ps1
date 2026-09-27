[CmdletBinding()]
param(
    [ValidateSet('Init','Verify','Plan','Configure','SelfTest')][string]$Action = 'Verify',
    [string]$Root = '',
    [string[]]$AccountName = @(),
    [string[]]$DeviceAddress = @(),
    [switch]$Apply,
    [switch]$AcknowledgeHostWideSmb
)
$ErrorActionPreference = 'Stop'
function Test-AllowedSecretPrincipal([string]$Sid, [string]$OwnerSid, [string[]]$AllowedSids) {
    # OWNER_RIGHTS names the current object owner, not an additional account.
    return ($Sid -in $AllowedSids -or ($Sid -eq 'S-1-3-4' -and $OwnerSid -in $AllowedSids))
}
function Test-ClientDeviceAddress([string[]]$AllowedAddresses, [string[]]$ConnectionAddresses) {
    # Only established SMB connection source addresses count as device evidence.
    if (@($ConnectionAddresses).Count -eq 0) { return $false }
    try {
        $approved = @($AllowedAddresses | ForEach-Object {
            $ip = [Net.IPAddress]::Parse($_)
            if ($ip.IsIPv4MappedToIPv6) { $ip = $ip.MapToIPv4() }
            if (-not [Net.IPAddress]::IsLoopback($ip)) { $ip.ToString() }
        })
        foreach ($value in $ConnectionAddresses) {
            $ip = [Net.IPAddress]::Parse($value)
            if ($ip.IsIPv4MappedToIPv6) { $ip = $ip.MapToIPv4() }
            if ([Net.IPAddress]::IsLoopback($ip) -or $ip.ToString() -in @('0.0.0.0','::') -or
                $ip.ToString() -notin $approved) { return $false }
        }
        return $true
    } catch { return $false }
}
function Test-SmbFirewallEvidence($Evidence, [string[]]$ExpectedPrefixes) {
    # Pure predicate: missing or narrower filters cannot prove a host-wide block.
    foreach ($name in @('rule','port','address','application','service','interface','interfaceType','security')) {
        if ($null -eq $Evidence[$name] -or @($Evidence[$name]).Count -ne 1) { return $false }
    }
    $rule = $Evidence.rule
    if ($rule.Action -ne 'Block' -or $rule.Direction -ne 'Inbound' -or $rule.Enabled -ne 'True' -or
        [string]$rule.Profile -ne 'Any' -or -not $Evidence.allProfilesEnabled) { return $false }
    if ($Evidence.port.Protocol -ne 'TCP' -or
        (@($Evidence.port.LocalPort | Sort-Object) -join ',') -ne '139,445' -or
        [string]$Evidence.port.RemotePort -ne 'Any' -or [string]$Evidence.address.LocalAddress -ne 'Any') { return $false }
    if ($ExpectedPrefixes.Count -eq 0 -or
        (@($Evidence.address.RemoteAddress | Sort-Object) -join ',') -ne (@($ExpectedPrefixes | Sort-Object) -join ',')) { return $false }
    foreach ($pair in @(@('application','Program'),@('application','Package'),@('service','Service'),
        @('interface','InterfaceAlias'),@('interfaceType','InterfaceType'),
        @('security','LocalUser'),@('security','RemoteUser'),@('security','RemoteMachine'))) {
        if ([string]$Evidence[$pair[0]].($pair[1]) -ne 'Any') { return $false }
    }
    if ($Evidence.security.Authentication -ne 'NotRequired' -or $Evidence.security.Encryption -ne 'NotRequired') { return $false }
    return $true
}
function Invoke-SecuritySelfTest {
    # Synthetic values only; no OS mocks, secret files, ACLs, or network mutations.
    $count = 0
    $allowedSids = @('S-1-5-21-1-2-3-1001','S-1-5-18','S-1-5-32-544')
    foreach ($case in @(
        @{sid=$allowedSids[0];owner=$allowedSids[0];expected=$true},
        @{sid='S-1-5-18';owner=$allowedSids[0];expected=$true},
        @{sid='S-1-3-4';owner=$allowedSids[0];expected=$true},
        @{sid='S-1-3-4';owner='S-1-5-21-1-2-3-1002';expected=$false},
        @{sid='S-1-1-0';owner=$allowedSids[0];expected=$false})) {
        if ((Test-AllowedSecretPrincipal $case.sid $case.owner $allowedSids) -ne $case.expected) { throw 'security-selftest-failed' }
        $count++
    }
    $allowed = @('192.0.2.10','2001:db8::10','127.0.0.1','::1')
    foreach ($case in @(
        @{addresses=@('192.0.2.10');expected=$true},
        @{addresses=@('::ffff:192.0.2.10');expected=$true},
        @{addresses=@('2001:db8::10');expected=$true},
        @{addresses=@('127.0.0.1');expected=$false},
        @{addresses=@('::1');expected=$false},
        @{addresses=@('::ffff:127.0.0.1');expected=$false},
        @{addresses=@('192.0.2.11');expected=$false},
        @{addresses=@('192.0.2.10','192.0.2.11');expected=$false},
        @{addresses=@('0.0.0.0');expected=$false},
        @{addresses=@('invalid');expected=$false},
        @{addresses=@();expected=$false})) {
        if ((Test-ClientDeviceAddress $allowed $case.addresses) -ne $case.expected) { throw 'security-selftest-failed' }
        $count++
    }
    $base = @{
        rule=@{Action='Block';Direction='Inbound';Enabled='True';Profile='Any'};
        port=@{Protocol='TCP';LocalPort=@('445','139');RemotePort='Any'};
        address=@{LocalAddress='Any';RemoteAddress=@('198.51.100.0/24')};
        application=@{Program='Any';Package='Any'}; service=@{Service='Any'};
        interface=@{InterfaceAlias='Any'}; interfaceType=@{InterfaceType='Any'};
        security=@{LocalUser='Any';RemoteUser='Any';RemoteMachine='Any';Authentication='NotRequired';Encryption='NotRequired'};
        allProfilesEnabled=$true
    }
    $expected = @('198.51.100.0/24')
    if (-not (Test-SmbFirewallEvidence $base $expected)) { throw 'security-selftest-failed' }
    $count++
    foreach ($case in @(
        @('rule','Profile','Private'),@('rule','Enabled','False'),@('rule','Action','Allow'),
        @('rule','Direction','Outbound'),@('port','Protocol','UDP'),@('port','LocalPort','445'),
        @('port','RemotePort','445'),@('address','LocalAddress','192.0.2.10'),
        @('address','RemoteAddress','198.51.100.0/25'),@('application','Program','synthetic.exe'),
        @('application','Package','synthetic'),@('service','Service','synthetic'),
        @('interface','InterfaceAlias','synthetic'),@('interfaceType','InterfaceType','Wireless'),
        @('security','LocalUser','synthetic'),@('security','RemoteUser','synthetic'),
        @('security','RemoteMachine','synthetic'),@('security','Authentication','Required'),
        @('security','Encryption','Required'))) {
        $copy = @{}
        foreach ($key in $base.Keys) { $copy[$key] = if ($base[$key] -is [hashtable]) { $base[$key].Clone() } else { $base[$key] } }
        $copy[$case[0]][$case[1]] = $case[2]
        if (Test-SmbFirewallEvidence $copy $expected) { throw 'security-selftest-failed' }
        $count++
    }
    $base.allProfilesEnabled = $false
    if (Test-SmbFirewallEvidence $base $expected) { throw 'security-selftest-failed' }
    $count++
    $base.allProfilesEnabled = $true
    $base.Remove('rule')
    if (Test-SmbFirewallEvidence $base $expected) { throw 'security-selftest-failed' }
    $count++
    [pscustomobject]@{status='verified';testCount=$count;osMutations=0;rawValuesPrinted=0} | ConvertTo-Json -Compress
}
function Get-BlockedPrefixes([string[]]$Addresses) {
    $program = @'
import ipaddress,json,sys
allowed=[ipaddress.ip_address(a) for a in json.load(sys.stdin)]
result=[]
for version,whole in [(4,'0.0.0.0/0'),(6,'::/0')]:
    networks=[ipaddress.ip_network(whole)]
    for address in sorted(set(a for a in allowed if a.version==version)):
        next_networks=[]
        for network in networks:
            if address in network:
                next_networks.extend(network.address_exclude(ipaddress.ip_network(str(address)+('/32' if version==4 else '/128'))))
            else: next_networks.append(network)
        networks=next_networks
    result.extend(str(n) for n in networks)
print(json.dumps(sorted(result)))
'@
    $result = (ConvertTo-Json -InputObject @($Addresses) -Compress) | & python -B -c $program 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'network-address-invalid' }
    return @($result | ConvertFrom-Json)
}
try {
    if ($Action -eq 'SelfTest') { Invoke-SecuritySelfTest; exit 0 }
    if ([string]::IsNullOrWhiteSpace($Root)) { $Root = Split-Path -Parent $PSScriptRoot }
    $rootPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Root).ProviderPath).TrimEnd('\')
    $secretPath = Join-Path $rootPath '.secrets'
    $firewallName = 'AWX.ProjectSecrets.BlockOtherDevices.TCP'
    $cursor = Get-Item -LiteralPath $rootPath -Force
    while ($null -ne $cursor) {
        if ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'reparse-path' }
        $cursor = $cursor.Parent
    }
    if ($Action -in @('Plan','Configure')) {
        if ($AccountName.Count -lt 1 -or $DeviceAddress.Count -lt 1) { throw 'device-enrollment-evidence-needed' }
        $sids = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
        foreach ($name in $AccountName) {
            $identity = New-Object Security.Principal.NTAccount($name)
            $sidText = $identity.Translate([Security.Principal.SecurityIdentifier]).Value
            if ($sidText -notmatch '^S-1-5-21-') { throw 'broad-account-refused' }
            $sids += $sidText
        }
        $addresses = @($DeviceAddress | ForEach-Object { ([Net.IPAddress]::Parse($_)).ToString() }) + @('127.0.0.1','::1')
        $addresses = @($addresses | Sort-Object -Unique)
        $blocked = Get-BlockedPrefixes $addresses
        $shares = @(Get-SmbShare | Where-Object { $_.Path -and -not $_.Special -and
            ($rootPath -eq $_.Path.TrimEnd('\') -or $rootPath.StartsWith($_.Path.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) })
        if ($shares.Count -eq 0) { throw 'server-security-evidence-needed' }
        if ($Action -eq 'Plan' -or -not $Apply) {
            [pscustomobject]@{status='planned';accountCount=@($sids|Sort-Object -Unique).Count;
                addressCount=$addresses.Count;affectedShareCount=$shares.Count;blockedPrefixCount=$blocked.Count;
                firewallScope='host-wide-inbound-tcp-445-139';requiresAdministrator=$true;rawValuesWritten=0} | ConvertTo-Json -Compress
            exit 0
        }
        if (-not $AcknowledgeHostWideSmb) { throw 'host-wide-smb-acknowledgement-required' }
        $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
        if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'administrator-required' }
        if (-not (Test-Path -LiteralPath $secretPath -PathType Container)) { throw 'secrets-not-initialized' }
        if (Test-Path -LiteralPath (Join-Path $secretPath 'access-policy.json')) { throw 'existing-policy-preserved' }
        if (Get-NetFirewallRule -Name $firewallName -ErrorAction SilentlyContinue) { throw 'existing-firewall-rule-preserved' }
        if (@(Get-NetFirewallProfile | Where-Object { -not $_.Enabled }).Count -gt 0) { throw 'firewall-profile-disabled' }
        $oldAcl = Get-Acl -LiteralPath $secretPath
        if (-not $oldAcl.AreAccessRulesProtected) { throw 'inherited-secrets-acl' }
        $recoveryPath = Join-Path $secretPath ('access-before-'+[guid]::NewGuid().ToString('N')+'.json')
        [ordered]@{version=1;sddl=$oldAcl.Sddl;shareEncryption=@($shares|Select-Object Name,EncryptData);
            firewallRulePreviouslyAbsent=$true} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $recoveryPath -Encoding UTF8
        # Secure transport/network first. ACL enrollment is last; failures keep old readers.
        foreach ($share in $shares) { Set-SmbShare -Name $share.Name -EncryptData $true -Force | Out-Null }
        New-NetFirewallRule -Name $firewallName -DisplayName 'AWX project secrets: block other SMB devices' -Direction Inbound `
            -Action Block -Enabled True -Profile Any -Protocol TCP -LocalPort 445,139 -RemoteAddress $blocked | Out-Null
        $policy = [ordered]@{version=1;allowedSids=@($sids|Sort-Object -Unique);allowedAddresses=$addresses;firewallRule=$firewallName}
        $policyPath = Join-Path $secretPath 'access-policy.json'
        $policyBytes = [Text.Encoding]::UTF8.GetBytes(($policy | ConvertTo-Json -Depth 4))
        $stream = [IO.File]::Open($policyPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $stream.Write($policyBytes,0,$policyBytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        foreach ($sidText in @($sids|Sort-Object -Unique)) {
            $identity = New-Object Security.Principal.SecurityIdentifier($sidText)
            $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
            $oldAcl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $secretPath -AclObject $oldAcl
        # Continue into the live verification below; never treat configuration writes as proof.
    }
    if ($Action -eq 'Init') {
        if (Test-Path -LiteralPath $secretPath) { throw 'existing-secrets-preserved' }
        # Create empty and immediately remove inherited broad access before any value can be written.
        [IO.Directory]::CreateDirectory($secretPath) | Out-Null
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl = New-Object Security.AccessControl.DirectorySecurity
        $acl.SetAccessRuleProtection($true,$false)
        $acl.SetOwner($sid)
        foreach ($principal in @($sid.Value,'S-1-5-18','S-1-5-32-544')) {
            $identity = New-Object Security.Principal.SecurityIdentifier($principal)
            $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
            $acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $secretPath -AclObject $acl
        [pscustomobject]@{status='initialized';rawValuesWritten=0;remoteEnrollment='evidence_needed'} | ConvertTo-Json -Compress
        exit 0
    }
    if (-not (Test-Path -LiteralPath $secretPath -PathType Container)) { throw 'secrets-not-initialized' }
    if ((Get-Item -LiteralPath $secretPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'reparse-path' }
    # The operator-reviewed policy contains account SIDs and exact device network addresses only.
    $policyPath = Join-Path $secretPath 'access-policy.json'
    if (-not (Test-Path -LiteralPath $policyPath -PathType Leaf)) { throw 'device-enrollment-evidence-needed' }
    $policy = Get-Content -LiteralPath $policyPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($policy.version -ne 1 -or @($policy.allowedSids).Count -lt 1 -or @($policy.allowedAddresses).Count -lt 1) { throw 'device-enrollment-evidence-needed' }
    $allowed = @($policy.allowedSids) + @('S-1-5-18','S-1-5-32-544')
    $items = @((Get-Item -LiteralPath $secretPath -Force)) + @(Get-ChildItem -LiteralPath $secretPath -Force -Recurse)
    foreach ($item in $items) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'reparse-path' }
        $acl = Get-Acl -LiteralPath $item.FullName
        $ownerSid = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
        if ($item.FullName -eq $secretPath -and -not $acl.AreAccessRulesProtected) { throw 'inherited-secrets-acl' }
        foreach ($rule in $acl.Access) {
            $sidText = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            if ($rule.AccessControlType -eq 'Allow' -and -not (Test-AllowedSecretPrincipal $sidText $ownerSid $allowed)) { throw 'unexpected-secrets-principal' }
        }
    }
    $driveName = ([IO.Path]::GetPathRoot($rootPath)).TrimEnd('\',':')
    $drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
    if ($drive -and $drive.DisplayRoot) {
        if ($driveName -ine 'Y') { throw 'canonical-client-root-required' }
        $identityProof = & (Join-Path $rootPath 'scripts/verify_ydrive_backing_identity.ps1') `
            -CanonicalWorkspace 'Y:\' -ExpectedSha256 '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9' 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'backing-identity-unverified' }
        $mapping = $drive.DisplayRoot.Trim().TrimEnd('\')
        $parts = $mapping.TrimStart('\').Split('\')
        if ($parts.Count -lt 2) { throw 'backing-identity-unverified' }
        $connections = @(Get-SmbConnection | Where-Object { $_.ServerName -ieq $parts[0] -and $_.ShareName -ieq $parts[1] })
        if ($connections.Count -ne 1 -or -not $connections[0].Encrypted) { throw 'client-encrypted-smb-unverified' }
        $serverAddresses = @([Net.Dns]::GetHostAddresses($parts[0]) | ForEach-Object { $_.ToString() })
        $localAddresses = @(Get-NetTCPConnection -State Established -RemotePort 445 | Where-Object {
            $_.RemoteAddress -in $serverAddresses
        } | ForEach-Object LocalAddress | Sort-Object -Unique)
        if (-not (Test-ClientDeviceAddress @($policy.allowedAddresses) $localAddresses)) { throw 'client-device-not-enrolled' }
        [pscustomobject]@{status='available';scope='notebook-live-ntfs-encrypted-canonical-smb';rawValuesPrinted=0} | ConvertTo-Json -Compress
        exit 0
    }
    $shares = @(Get-SmbShare | Where-Object { $_.Path -and -not $_.Special -and
        ($rootPath -eq $_.Path.TrimEnd('\') -or $rootPath.StartsWith($_.Path.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) })
    if ($shares.Count -eq 0) { throw 'server-security-evidence-needed' }
    foreach ($share in $shares) {
        if (-not $share.EncryptData) { throw 'smb-encryption-required' }
    }
    $ownedRule = Get-NetFirewallRule -PolicyStore ActiveStore -Name $firewallName -ErrorAction SilentlyContinue
    if (-not $ownedRule -or $policy.firewallRule -ne $firewallName) { throw 'smb-firewall-evidence-needed' }
    $profiles = @(Get-NetFirewallProfile)
    $evidence = @{
        rule=$ownedRule; port=@($ownedRule | Get-NetFirewallPortFilter);
        address=@($ownedRule | Get-NetFirewallAddressFilter);
        application=@($ownedRule | Get-NetFirewallApplicationFilter);
        service=@($ownedRule | Get-NetFirewallServiceFilter);
        interface=@($ownedRule | Get-NetFirewallInterfaceFilter);
        interfaceType=@($ownedRule | Get-NetFirewallInterfaceTypeFilter);
        security=@($ownedRule | Get-NetFirewallSecurityFilter);
        allProfilesEnabled=($profiles.Count -eq 3 -and @($profiles | Where-Object { -not $_.Enabled }).Count -eq 0)
    }
    if (-not (Test-SmbFirewallEvidence $evidence @(Get-BlockedPrefixes @($policy.allowedAddresses)))) { throw 'smb-device-scope-unproven' }
    [pscustomobject]@{status='available';scope='desktop-live-ntfs-encrypted-smb-device-firewall';rawValuesPrinted=0} | ConvertTo-Json -Compress
    exit 0
} catch {
    # Exception messages/objects may contain account or configuration values.
    $known = @('reparse-path','existing-secrets-preserved','secrets-not-initialized','device-enrollment-evidence-needed',
        'inherited-secrets-acl','unexpected-secrets-principal','server-security-evidence-needed','smb-encryption-required',
        'smb-firewall-evidence-needed','smb-device-scope-unproven','canonical-client-root-required','backing-identity-unverified',
        'client-encrypted-smb-unverified','client-device-not-enrolled','network-address-invalid','broad-account-refused',
        'host-wide-smb-acknowledgement-required','administrator-required','existing-policy-preserved',
        'existing-firewall-rule-preserved','firewall-profile-disabled','security-selftest-failed')
    $reason = if ($_.Exception.Message -in $known) { $_.Exception.Message } else { 'access-transport-or-device-policy-unverified' }
    [pscustomobject]@{status='evidence_needed';reason=$reason;rawValuesPrinted=0} | ConvertTo-Json -Compress
    exit 2
}
