[CmdletBinding()]
param(
    [string]$Domain = "abandonwareai.kro.kr",
    [string]$ConnectHost = "",
    [int]$HttpsPort = 443,
    [int]$HttpPort = 80,
    [ValidateSet("auto", "embedded", "offload")]
    [string]$TlsMode = "auto",
    [switch]$RequireRunning,
    [switch]$RequireTrustedCertificate,
    [switch]$Json
)

$ErrorActionPreference = "Stop"

$checks = New-Object System.Collections.Generic.List[object]
$tcpHost = if ([string]::IsNullOrWhiteSpace($ConnectHost)) { $Domain } else { $ConnectHost.Trim() }

function Add-Check {
    param(
        [string]$Name,
        [bool]$Ok,
        [string]$Classification,
        [object]$Detail = $null
    )
    $checks.Add([ordered]@{
        name = $Name
        ok = $Ok
        classification = $Classification
        detail = $Detail
    }) | Out-Null
}

function Env-Present {
    param([string]$Name)
    return -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name))
}

function Env-Value {
    param([string]$Name)
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ($null -eq $value) {
        return ""
    }
    return $value.Trim()
}

function Get-DistinctPasswordKeyStoreCertificate {
    $probeRoot = Join-Path ([IO.Path]::GetTempPath()) ("awx-keystore-probe-" + [Guid]::NewGuid().ToString("N"))
    $probeSource = Join-Path $probeRoot "AwxKeyStoreProbe.java"
    try {
        New-Item -ItemType Directory -Path $probeRoot -ErrorAction Stop | Out-Null
        $javaSource = @'
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;

public class AwxKeyStoreProbe {
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    public static void main(String[] args) throws Exception {
        char[] storePassword = env("SERVER_SSL_KEY_STORE_PASSWORD").toCharArray();
        String keyText = env("SERVER_SSL_KEY_PASSWORD");
        char[] keyPassword = (keyText.isBlank() ? env("SERVER_SSL_KEY_STORE_PASSWORD") : keyText).toCharArray();
        try {
            String path = env("SERVER_SSL_KEY_STORE").trim();
            String type = env("SERVER_SSL_KEY_STORE_TYPE").trim();
            String requestedAlias = env("SERVER_SSL_KEY_ALIAS").trim();
            KeyStore keyStore = KeyStore.getInstance(type.isBlank() ? "PKCS12" : type);
            try (InputStream input = Files.newInputStream(Path.of(path))) {
                keyStore.load(input, storePassword);
            }
            String alias = requestedAlias;
            if (alias.isBlank()) {
                Enumeration<String> aliases = keyStore.aliases();
                while (aliases.hasMoreElements()) {
                    String candidate = aliases.nextElement();
                    if (keyStore.isKeyEntry(candidate)) {
                        alias = candidate;
                        break;
                    }
                }
            }
            if (alias.isBlank() || !keyStore.isKeyEntry(alias)) {
                throw new IllegalStateException("key_alias_missing");
            }
            Key key = keyStore.getKey(alias, keyPassword);
            Certificate certificate = keyStore.getCertificate(alias);
            if (!(key instanceof PrivateKey) || !(certificate instanceof X509Certificate)) {
                throw new IllegalStateException("private_key_or_certificate_missing");
            }
            System.out.println("CERT_BASE64=" + Base64.getEncoder().encodeToString(certificate.getEncoded()));
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }
}
'@
        [IO.File]::WriteAllText($probeSource, $javaSource, [Text.UTF8Encoding]::new($false))
        $probeOutput = @(& java $probeSource 2>$null)
        if ($LASTEXITCODE -ne 0) {
            throw [InvalidOperationException]::new("java_keystore_probe_failed")
        }
        $certificateLine = @($probeOutput | Where-Object { $_ -is [string] -and $_.StartsWith("CERT_BASE64=") }) | Select-Object -Last 1
        if ([string]::IsNullOrWhiteSpace($certificateLine)) {
            throw [InvalidOperationException]::new("java_keystore_probe_failed")
        }
        $certificateBytes = [Convert]::FromBase64String($certificateLine.Substring("CERT_BASE64=".Length))
        return [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($certificateBytes)
    } finally {
        if (Test-Path -LiteralPath $probeSource -PathType Leaf) {
            Remove-Item -LiteralPath $probeSource -Force
        }
        if (Test-Path -LiteralPath $probeRoot -PathType Container) {
            Remove-Item -LiteralPath $probeRoot -Force
        }
    }
}

function Test-TcpQuiet {
    param(
        [string]$HostName,
        [int]$Port
    )
    $client = $null
    $async = $null
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne([TimeSpan]::FromMilliseconds(1500))) {
            return $false
        }
        $client.EndConnect($async)
        return [bool]$client.Connected
    } catch {
        return $false
    } finally {
        if ($null -ne $async -and $null -ne $async.AsyncWaitHandle) {
            $async.AsyncWaitHandle.Close()
        }
        if ($null -ne $client) {
            $client.Dispose()
        }
    }
}

function Test-TrustedTls {
    param(
        [string]$ServerName,
        [string]$ConnectHostName,
        [int]$Port
    )
    $result = [ordered]@{
        trusted = $false
        policyErrors = ""
        reason = ""
    }
    $client = $null
    $stream = $null
    $script:AwxDomainTlsPolicyErrors = ""
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $client.Connect($ConnectHostName, $Port)
        $callback = {
            param($sender, $certificate, $chain, $sslPolicyErrors)
            $script:AwxDomainTlsPolicyErrors = $sslPolicyErrors.ToString()
            return $sslPolicyErrors -eq [System.Net.Security.SslPolicyErrors]::None
        }
        $stream = [System.Net.Security.SslStream]::new($client.GetStream(), $false, $callback)
        $stream.AuthenticateAsClient($ServerName)
        $result.trusted = $true
        $result.policyErrors = "None"
    } catch {
        $result.reason = $_.Exception.GetType().Name
        if (-not [string]::IsNullOrWhiteSpace($script:AwxDomainTlsPolicyErrors)) {
            $result.policyErrors = $script:AwxDomainTlsPolicyErrors
        }
    } finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
        if ($null -ne $client) {
            $client.Dispose()
        }
    }
    return $result
}

function Get-PublicIp {
    try {
        return ((Invoke-WebRequest -Uri "https://api.ipify.org" -UseBasicParsing -TimeoutSec 10).Content).Trim()
    } catch {
        return ""
    }
}

function Get-LocalIpv4 {
    try {
        return @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object { $_.IPAddress -notmatch '^169\.254\.' } |
            Select-Object -ExpandProperty IPAddress)
    } catch {
        return @()
    }
}

function Test-CertName {
    param(
        [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate,
        [string]$HostName
    )
    $names = @(Get-CertificateDnsNames -Certificate $Certificate)
    if ($names.Count -eq 0) {
        return $false
    }
    $normalizedHost = $HostName.Trim().ToLowerInvariant()
    foreach ($nameValue in $names) {
        $name = $nameValue.Trim().ToLowerInvariant()
        if ($name -eq $normalizedHost -or ($name.StartsWith("*.") -and $normalizedHost.EndsWith($name.Substring(1)))) {
            return $true
        }
    }
    return $false
}

function Get-CertificateDnsNames {
    param(
        [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate
    )
    $names = New-Object System.Collections.Generic.List[string]
    foreach ($extension in $Certificate.Extensions) {
        if ($extension.Oid -and $extension.Oid.Value -eq "2.5.29.17") {
            $formatted = $extension.Format($false)
            foreach ($match in [regex]::Matches($formatted, "DNS Name\s*=\s*([^,\r\n]+)")) {
                $value = $match.Groups[1].Value.Trim()
                if (-not [string]::IsNullOrWhiteSpace($value)) {
                    $names.Add($value) | Out-Null
                }
            }
        }
    }

    $fallbackName = $Certificate.GetNameInfo(
        [System.Security.Cryptography.X509Certificates.X509NameType]::DnsName,
        $false
    )
    if (-not [string]::IsNullOrWhiteSpace($fallbackName)) {
        $names.Add($fallbackName.Trim()) | Out-Null
    }
    return @($names | Select-Object -Unique)
}

function Hash-Text12 {
    param([string]$Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
        $hex = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace("-", "")
        return "hash:" + $hex.Substring(0, 12).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

$dnsIps = @()
try {
    $dnsIps = @(Resolve-DnsName -Name $Domain -Type A -ErrorAction Stop |
        Where-Object { $_.IPAddress } |
        Select-Object -ExpandProperty IPAddress)
    Add-Check "dns.a-record" ($dnsIps.Count -gt 0) "dns" @{ count = $dnsIps.Count; values = $dnsIps }
} catch {
    Add-Check "dns.a-record" $false "dns" @{ reason = $_.Exception.GetType().Name }
}

$publicIp = Get-PublicIp
$localIps = Get-LocalIpv4
$firstDnsIp = if ($dnsIps.Count -gt 0) { [string]$dnsIps[0] } else { "" }
$domainTargetsThisHost = (-not [string]::IsNullOrWhiteSpace($publicIp)) -and (($dnsIps -contains $publicIp) -or ($localIps -contains $firstDnsIp))
Add-Check "dns.targets-this-host" $domainTargetsThisHost "dns" @{
    publicIpPresent = -not [string]::IsNullOrWhiteSpace($publicIp)
    localIpCount = $localIps.Count
    dnsIpCount = $dnsIps.Count
}

if ($HttpPort -gt 0) {
    $tcpHttp = Test-TcpQuiet -HostName $tcpHost -Port $HttpPort
    Add-Check "tcp.$HttpPort" ($tcpHttp -or -not $RequireRunning) "public-listener-unreachable" @{
        reachable = $tcpHttp
        required = [bool]$RequireRunning
        role = "http"
        connectHost = $tcpHost
    }
}
$tcpHttps = Test-TcpQuiet -HostName $tcpHost -Port $HttpsPort
$trustedTlsOk = $false
$httpsTcpRequired = [bool]$RequireRunning -or [bool]$RequireTrustedCertificate
Add-Check "tcp.$HttpsPort" ($tcpHttps -or -not $httpsTcpRequired) "public-listener-unreachable" @{
    reachable = $tcpHttps
    required = $httpsTcpRequired
    role = "https"
    connectHost = $tcpHost
}
if ($RequireTrustedCertificate) {
    if ($tcpHttps) {
        $trustedTls = Test-TrustedTls -ServerName $Domain -ConnectHostName $tcpHost -Port $HttpsPort
        $trustedTlsOk = [bool]$trustedTls.trusted
        Add-Check "ssl.runtime.trusted-certificate" ([bool]$trustedTls.trusted) "ssl-risk" @{
            connectHost = $tcpHost
            port = $HttpsPort
            serverNameHash = (Hash-Text12 $Domain)
            policyErrors = $trustedTls.policyErrors
            reason = $trustedTls.reason
        }
    } else {
        Add-Check "ssl.runtime.trusted-certificate" $false "ssl-risk" @{
            connectHost = $tcpHost
            port = $HttpsPort
            serverNameHash = (Hash-Text12 $Domain)
            reason = "tcp_unreachable"
        }
    }
}

$offloadAccepted = $TlsMode -eq "offload" -or ($TlsMode -eq "auto" -and $tcpHttps -and (-not $RequireTrustedCertificate -or $trustedTlsOk))
$embeddedSslRequired = $TlsMode -eq "embedded" -or ($TlsMode -eq "auto" -and -not $offloadAccepted)
Add-Check "tls.offload.accepted" $true "ssl-risk" @{
    accepted = $offloadAccepted
    mode = $TlsMode
    embeddedSslRequired = $embeddedSslRequired
}

if ($embeddedSslRequired) {
    $sslEnabledValue = (Env-Value "SERVER_SSL_ENABLED").ToLowerInvariant()
    $sslEnabled = $sslEnabledValue -in @("true", "1", "yes", "y")
    Add-Check "env.SERVER_SSL_ENABLED" $sslEnabled "ssl-risk" @{ present = (Env-Present "SERVER_SSL_ENABLED"); enabled = $sslEnabled }

    $requiredEnv = @(
        "SERVER_SSL_KEY_STORE",
        "SERVER_SSL_KEY_STORE_PASSWORD"
    )
    foreach ($name in $requiredEnv) {
        Add-Check "env.$name" (Env-Present $name) "secret-required" @{ present = (Env-Present $name) }
    }
    Add-Check "env.SERVER_SSL_KEY_PASSWORD.optional" $true "ssl-risk" @{ present = (Env-Present "SERVER_SSL_KEY_PASSWORD") }
    Add-Check "env.SERVER_SSL_KEY_ALIAS.optional" $true "ssl-risk" @{ present = (Env-Present "SERVER_SSL_KEY_ALIAS") }

    $keyStore = Env-Value "SERVER_SSL_KEY_STORE"
    $keyStorePassword = Env-Value "SERVER_SSL_KEY_STORE_PASSWORD"
    $keyPassword = Env-Value "SERVER_SSL_KEY_PASSWORD"
    if ([string]::IsNullOrWhiteSpace($keyStore)) {
        Add-Check "ssl.keystore.file" $false "ssl-risk" @{ present = $false }
    } elseif (-not (Test-Path -LiteralPath $keyStore -PathType Leaf)) {
        Add-Check "ssl.keystore.file" $false "ssl-risk" @{ present = $true; exists = $false }
    } else {
        Add-Check "ssl.keystore.file" $true "ssl-risk" @{ present = $true; exists = $true }
        if (-not [string]::IsNullOrWhiteSpace($keyStorePassword)) {
            try {
                $distinctKeyPassword = -not [string]::IsNullOrWhiteSpace($keyPassword) -and $keyPassword -cne $keyStorePassword
                if ($distinctKeyPassword) {
                    $cert = Get-DistinctPasswordKeyStoreCertificate
                    $hasPrivateKey = $true
                    $probeMode = "java-distinct-key-password"
                } else {
                    $flags = [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
                    $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($keyStore, $keyStorePassword, $flags)
                    $hasPrivateKey = $cert.HasPrivateKey
                    $probeMode = "dotnet-store-password"
                }
                Add-Check "ssl.keystore.certificate" $true "ssl-risk" @{
                    hasPrivateKey = $hasPrivateKey
                    notAfter = $cert.NotAfter.ToString("o")
                    subjectHash = (Hash-Text12 $cert.Subject)
                    probeMode = $probeMode
                }
                Add-Check "ssl.certificate.has-private-key" $hasPrivateKey "ssl-risk" @{ hasPrivateKey = $hasPrivateKey }
                Add-Check "ssl.certificate.not-expired" ($cert.NotAfter -gt (Get-Date)) "ssl-risk" @{ notAfter = $cert.NotAfter.ToString("o") }
                Add-Check "ssl.certificate.matches-domain" (Test-CertName -Certificate $cert -HostName $Domain) "ssl-risk" @{
                    dnsNameHash = (Hash-Text12 ($cert.GetNameInfo([System.Security.Cryptography.X509Certificates.X509NameType]::DnsName, $false)))
                }
            } catch {
                $probeReason = if ($_.Exception.Message -eq "java_keystore_probe_failed") { "java_keystore_probe_failed" } else { $_.Exception.GetType().Name }
                Add-Check "ssl.keystore.certificate" $false "ssl-risk" @{ reason = $probeReason }
            }
        }
    }
}

$blocking = @($checks | Where-Object { -not [bool]$_["ok"] })
$ready = $blocking.Count -eq 0
$failureClassification = ""
if (-not $ready) {
    $failureClassification = [string]$blocking[0]["classification"]
}
$evidenceNeeded = New-Object System.Collections.Generic.List[string]
foreach ($item in $blocking) {
    $evidenceNeeded.Add([string]$item["name"]) | Out-Null
}

$summary = [ordered]@{
    schemaVersion = "awx.domain_public_https_preflight.v1"
    domain = $Domain
    connectHost = $tcpHost
    httpPort = $HttpPort
    httpsPort = $HttpsPort
    tlsMode = $TlsMode
    embeddedSslRequired = $embeddedSslRequired
    offloadAccepted = $offloadAccepted
    requireRunning = [bool]$RequireRunning
    requireTrustedCertificate = [bool]$RequireTrustedCertificate
    ready = $ready
    failureClassification = $failureClassification
    evidenceNeeded = @($evidenceNeeded)
    checks = @($checks.ToArray())
}

if ($Json) {
    $summary | ConvertTo-Json -Depth 12
} else {
    Write-Host "[AWX][domain-preflight] domain=$Domain connectHost=$tcpHost httpPort=$HttpPort httpsPort=$HttpsPort ready=$($summary.ready) failureClassification=$($summary.failureClassification) evidenceNeeded=$($summary.evidenceNeeded -join ',')"
    foreach ($check in $checks) {
        Write-Host "[AWX][domain-preflight] check=$($check["name"]) ok=$($check["ok"]) classification=$($check["classification"])"
    }
}

if ($summary.ready) {
    exit 0
}
exit 2
