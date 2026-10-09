param(
    [ValidateSet("manual", "pre-commit", "pre-push")]
    [string]$Mode = "manual",
    [switch]$ScanAll,
    [switch]$SelfTest,
    [string[]]$Path = @(),
    [string]$Commit = "HEAD"
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

# Pre-commit inspects the index itself. Manual working-tree scans cannot prove it.
if ($Mode -eq 'pre-commit' -and -not $SelfTest) {
    if ($ScanAll -or $Path.Count -gt 0) { throw 'pre-commit-scope-override-forbidden' }
    & python -B (Join-Path $PSScriptRoot 'git_staged_guard.py') --root (Get-Location).Path
    if ($LASTEXITCODE -ne 0) { exit 1 }
    exit 0
}

$HighConfidencePatterns = @(
    [pscustomobject]@{ Id = "openai"; Regex = [regex]"(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])" },
    [pscustomobject]@{ Id = "google-ai"; Regex = [regex]"(?<![A-Za-z0-9_-])AIza[0-9A-Za-z_-]{20,}(?![A-Za-z0-9_-])" },
    [pscustomobject]@{ Id = "groq"; Regex = [regex]"(?<![A-Za-z0-9_-])gsk_[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])" },
    [pscustomobject]@{ Id = "pinecone"; Regex = [regex]"(?<![A-Za-z0-9_-])pcsk_[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])" },
    [pscustomobject]@{ Id = "supabase-api-key"; Regex = [regex]"(?<![A-Za-z0-9_-])sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])" },
    [pscustomobject]@{ Id = "supabase-access-token"; Regex = [regex]"(?<![A-Za-z0-9_-])sbp_[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])" },
    [pscustomobject]@{ Id = "private-key"; Regex = [regex]"-----BEGIN (?:RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----" }
)

$SensitiveAssignmentPattern = [regex]'(?i)(?<![$A-Za-z0-9_-])(?:api[-_]?key|apikey|client[-_]?secret|service[-_]?role(?:[-_]?key)?|owner[-_]?token|subscription[-_]?token|authorization|bearer|password|secret|token)\b\s*[:=]\s*[''"]?(?!\$\{)(?!__MISSING__)(?!<)(?!(?:dummy|test|changeme|change-me|sk-local|ollama|null|none|missing)\b)[A-Za-z0-9_./+=:-]{24,}'

function Resolve-RepoRoot {
    $gitRoot = ""
    try {
        $oldErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        $gitRoot = (& git rev-parse --show-toplevel 2>$null | Select-Object -First 1)
        $ErrorActionPreference = $oldErrorActionPreference
        if (-not [string]::IsNullOrWhiteSpace($gitRoot)) {
            return [IO.Path]::GetFullPath($gitRoot.Trim())
        }
    } catch {
        $ErrorActionPreference = "Stop"
        # Fall back below.
    }
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
}

function Test-GitAvailable {
    try {
        $oldErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        $inside = (& git rev-parse --is-inside-work-tree 2>$null | Select-Object -First 1)
        $ErrorActionPreference = $oldErrorActionPreference
        return (([string]$inside).Trim().Equals("true", [StringComparison]::OrdinalIgnoreCase))
    } catch {
        $ErrorActionPreference = "Stop"
        return $false
    }
}

function Convert-ToRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$InputPath
    )
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $full = [IO.Path]::GetFullPath($InputPath)
    if ($full.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($rootFull.Length).Replace('\', '/')
    }
    return $InputPath.Replace('\', '/')
}

function Test-TemplatePath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $leaf = Split-Path -Leaf $RelativePath
    if ($leaf -match "(?i)^\.env(\..*)?\.(example|sample|template)$" -or $leaf -match "(?i)^\.env\.(example|sample|template)$") {
        return $true
    }
    return ($RelativePath -match "(?i)(^|/)[^/]*(example|sample|template)[^/]*\.(ya?ml|properties|json|env|txt|md)$")
}

function Test-SkippedPath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $p = "/" + $RelativePath.Replace('\', '/').TrimStart('/')
    foreach ($fragment in @(
            "/.git/",
            "/.gradle/",
            "/build/",
            "/build-logs/",
            "/logs/",
            "/node_modules/",
            "/target/",
            "/out/",
            "/.idea/",
            "/.vscode/",
            "/__patch_drop__/"
        )) {
        if ($p.IndexOf($fragment, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $true
        }
    }
    return $false
}

function Get-PathBlockReason {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $p = $RelativePath.Replace('\', '/')
    $leaf = Split-Path -Leaf $p
    $isTemplate = Test-TemplatePath $p

    if ($p -match "(?i)(^|/)shared\.env($|\.)") {
        return "shared-env-file"
    }
    if ($p -match "(?i)(^|/)\.env($|\.)" -and -not $isTemplate) {
        return "env-file"
    }
    if ($leaf -match "(?i)^(apikey|api-key).*\.(txt|ps1|env|json|ya?ml|properties)$") {
        return "api-key-file"
    }
    if ($leaf -match "(?i)^(id_rsa|id_dsa|id_ecdsa|id_ed25519)$") {
        return "ssh-private-key"
    }
    if ($leaf -match "(?i)\.(pem|key|p12|pfx|jks|keystore|der)$") {
        return "credential-file-extension"
    }
    if ($p -match "(?i)(^|/)(main/resources|src/main/resources|app/src/main/resources)/application-(secrets?|local|dev|prod|machine|private)[^/]*\.(ya?ml|properties)$" -and -not $isTemplate) {
        return "spring-secret-profile"
    }
    if ($p -match "(?i)(^|/)(main/resources|src/main/resources|app/src/main/resources)/(application|bootstrap)\.properties$" -and -not $isTemplate) {
        return "spring-concrete-properties"
    }
    if ($p -match "(?i)(^|/)(main/resources|src/main/resources|app/src/main/resources)/[^/]*(secret|credential|token|api[-_]?key)[^/]*\.(ya?ml|properties|json)$" -and -not $isTemplate) {
        return "sensitive-config-name"
    }
    return ""
}

function Test-GenericAssignmentScanPath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $extension = [IO.Path]::GetExtension($RelativePath).ToLowerInvariant()
    if ($extension -in @(".yml", ".yaml", ".properties", ".json", ".toml", ".ini", ".env", ".txt", ".ps1", ".sh")) {
        return $true
    }
    if ((Split-Path -Leaf $RelativePath) -match "(?i)^\.env") {
        return $true
    }
    return $false
}

function Read-TextFileOrNull {
    param([Parameter(Mandatory = $true)][string]$AbsolutePath)
    try {
        $item = Get-Item -LiteralPath $AbsolutePath -ErrorAction Stop
        if ($item.Length -gt 2MB) {
            return $null
        }
        $bytes = [IO.File]::ReadAllBytes($AbsolutePath)
        if ([Array]::IndexOf($bytes, [byte]0) -ge 0) {
            return $null
        }
        return [Text.UTF8Encoding]::new($false, $false).GetString($bytes)
    } catch {
        return $null
    }
}

function Get-GitPathList {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$ModeValue,
        [switch]$All
    )
    Push-Location $Root
    try {
        $oldErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        if ($ModeValue -eq "pre-commit" -and -not $All) {
            $paths = @(& git --no-optional-locks diff --cached --name-only --diff-filter=ACMR 2>$null | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            $ErrorActionPreference = $oldErrorActionPreference
            return $paths
        }
        $paths = @(& git --no-optional-locks diff --cached --name-only --diff-filter=ACMR 2>$null | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($All -or $paths.Count -eq 0) {
            $paths = @(& git ls-files --cached --others --exclude-standard 2>$null | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        }
        $ErrorActionPreference = $oldErrorActionPreference
        return $paths
    } finally {
        $ErrorActionPreference = "Stop"
        Pop-Location
    }
}

function Get-FallbackPathList {
    param([Parameter(Mandatory = $true)][string]$Root)
    $paths = New-Object System.Collections.Generic.List[string]
    foreach ($filePattern in @(
            ".gitignore",
            ".gitattributes",
            "AGENTS.md",
            "README.md",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts"
        )) {
        $absolute = Join-Path $Root $filePattern
        if (Test-Path -LiteralPath $absolute -PathType Leaf) {
            $paths.Add((Convert-ToRelativePath $Root $absolute)) | Out-Null
        }
    }

    foreach ($relativeRoot in @("main", "app/src/main", ".githooks")) {
        $absoluteRoot = Join-Path $Root $relativeRoot
        if (-not (Test-Path -LiteralPath $absoluteRoot -PathType Container)) {
            continue
        }
        foreach ($file in @(Get-ChildItem -LiteralPath $absoluteRoot -Recurse -File -ErrorAction SilentlyContinue)) {
            $paths.Add((Convert-ToRelativePath $Root $file.FullName)) | Out-Null
        }
    }

    $scriptsRoot = Join-Path $Root "scripts"
    if (Test-Path -LiteralPath $scriptsRoot -PathType Container) {
        foreach ($file in @(Get-ChildItem -LiteralPath $scriptsRoot -File -Include *.ps1,*.py,*.sh -ErrorAction SilentlyContinue)) {
            $paths.Add((Convert-ToRelativePath $Root $file.FullName)) | Out-Null
        }
    }

    return @($paths.ToArray() | Sort-Object -Unique)
}

function ConvertFrom-GitQuotedPath {
    # git ls-files/diff (quotepath 기본값)은 비ASCII 경로를 "dir/\ooo\ooo" 처럼
    # 따옴표+8진법 UTF-8 바이트로 출력한다. 이 따옴표 문자열을 그대로 Test-Path에
    # 넣으면 " 문자 때문에 ArgumentException이 나서 스캔 전체가 죽고, 억지로
    # 넘기면 한글 이름 파일이 스캔에서 빠진다. 여기서 원래 경로로 되돌린다.
    param([Parameter(Mandatory = $true)][string]$Line)
    if ($Line.Length -lt 2 -or -not $Line.StartsWith('"') -or -not $Line.EndsWith('"')) {
        return $Line
    }
    $inner = $Line.Substring(1, $Line.Length - 2)
    $bytes = New-Object System.Collections.Generic.List[byte]
    $i = 0
    while ($i -lt $inner.Length) {
        $c = $inner[$i]
        if ($c -eq '\' -and $i + 1 -lt $inner.Length) {
            $n = $inner[$i + 1]
            if ($n -ge '0' -and $n -le '7') {
                $value = 0
                $digits = 0
                while ($digits -lt 3 -and $i + 1 -lt $inner.Length) {
                    $d = $inner[$i + 1]
                    if ($d -lt '0' -or $d -gt '7') { break }
                    $value = $value * 8 + ([int][char]$d - 48)
                    $i++
                    $digits++
                }
                if ($digits -gt 0) { $bytes.Add([byte]$value); $i++; continue }
            }
            switch ($n) {
                'n'  { $bytes.Add([byte]10);  $i += 2; continue }
                't'  { $bytes.Add([byte]9);   $i += 2; continue }
                '\'  { $bytes.Add([byte]92);  $i += 2; continue }
                '"'  { $bytes.Add([byte]34);  $i += 2; continue }
                default { $bytes.Add([byte][char]$n); $i += 2; continue }
            }
        }
        $bytes.Add([byte][char]$c)
        $i++
    }
    return [Text.UTF8Encoding]::new($false, $false).GetString($bytes.ToArray())
}

function Start-GitReader {
    param([string]$Root, [string]$Arguments)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = (Get-Command git -ErrorAction Stop).Source
    $info.Arguments = "--no-optional-locks " + $Arguments
    $info.WorkingDirectory = $Root
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    [void]$process.Start()
    return $process
}

function Get-CommittedBlobMap {
    param([string]$Root, [string]$Revision)
    Push-Location $Root
    try {
        $revisionArg = $Revision + '^{commit}'
        $oid = [string](& git rev-parse --verify --end-of-options $revisionArg 2>$null)
        if ($LASTEXITCODE -ne 0 -or $oid -notmatch '^[0-9a-f]{40,64}$') { throw 'push-commit-unavailable' }
        $process = Start-GitReader $Root ("ls-tree -r -z --full-tree " + $oid)
        try {
            $raw = $process.StandardOutput.ReadToEnd()
            $process.WaitForExit()
            if ($process.ExitCode -ne 0) { throw 'push-tree-unavailable' }
        } finally { $process.Dispose() }
        $map = [Collections.Hashtable]::new([StringComparer]::Ordinal)
        foreach ($row in $raw.Split([char]0)) {
            if (-not $row) { continue }
            if ($row -notmatch '^\d+ blob ([0-9a-f]+)\t([\s\S]+)$') { continue }
            $map[$Matches[2]] = $Matches[1]
        }
        return $map
    } finally { Pop-Location }
}

function Read-CommittedTextOrNull {
    param($Process, [string]$Oid)
    $Process.StandardInput.WriteLine($Oid)
    $Process.StandardInput.Flush()
    $reader = [IO.BinaryReader]::new($Process.StandardOutput.BaseStream)
    $header = New-Object Text.StringBuilder
    do {
        $b = $reader.ReadByte()
        if ($b -lt 0) { throw 'push-blob-unavailable' }
        if ($b -ne 10) { [void]$header.Append([char]$b) }
    } while ($b -ne 10)
    $parts = $header.ToString().Split(' ')
    if ($parts.Count -ne 3 -or $parts[0] -ne $Oid -or $parts[1] -ne 'blob') { throw 'push-blob-unavailable' }
    $size = [long]$parts[2]
    $bytes = $null
    if ($size -le 2MB) {
        $bytes = $reader.ReadBytes([int]$size)
        if ($bytes.Length -ne $size) { throw 'push-blob-incomplete' }
    } else {
        $remaining = $size
        while ($remaining -gt 0) {
            $chunk = $reader.ReadBytes([int][Math]::Min($remaining, 65536))
            if ($chunk.Length -eq 0) { throw 'push-blob-incomplete' }
            $remaining -= $chunk.Length
        }
    }
    if ($reader.ReadByte() -ne 10) { throw 'push-blob-framing' }
    if ($null -eq $bytes -or [Array]::IndexOf($bytes, [byte]0) -ge 0) { return $null }
    return [Text.UTF8Encoding]::new($false, $false).GetString($bytes)
}

function Find-SecretGuardFindings {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$RelativePaths,
        [hashtable]$BlobMap = @{},
        $BlobProcess = $null
    )
    $findings = New-Object System.Collections.Generic.List[object]
    $scanPaths = if ($null -ne $BlobProcess) {
        @($RelativePaths | Sort-Object -Unique -CaseSensitive)
    } else { @($RelativePaths | Sort-Object -Unique) }
    foreach ($relative in $scanPaths) {
        # git 출력은 비ASCII 경로를 따옴표+8진법으로 인용하므로 먼저 되돌린다.
        $rel = (ConvertFrom-GitQuotedPath $relative).Replace('\', '/')
        # Block the entire private area before skipped directories/template rules,
        # and never open its values or recovery files during a generic scan.
        if ($rel -match '(?i)(^|/)(\.secrets|config/secrets)(/|$)') {
            $findings.Add([pscustomobject]@{ Path = '<project-secrets>'; Line = 0; Rule = 'project-secrets-area'; Detail = 'blocked private area' }) | Out-Null
            continue
        }
        if ([string]::IsNullOrWhiteSpace($rel) -or (Test-SkippedPath $rel)) {
            continue
        }

        $absolute = Join-Path $Root $rel
        $pathReason = Get-PathBlockReason $rel
        if (-not [string]::IsNullOrWhiteSpace($pathReason)) {
            $findings.Add([pscustomobject]@{ Path = $rel; Line = 0; Rule = $pathReason; Detail = "blocked sensitive path" }) | Out-Null
        }

        if ($null -ne $BlobProcess) {
            try {
                $text = Read-CommittedTextOrNull $BlobProcess $BlobMap[$rel]
            } catch {
                if ($_.Exception.Message -ne 'push-blob-unavailable') { throw }
                # 장시간 살아 있는 batch 프로세스는 시작 시점의 pack 목록만 본다.
                # 스캔 도중 자동 gc가 느슨 객체를 새 pack으로 옮겨 지우면 missing이
                # 나오므로 새 프로세스(최신 pack 뷰)로 한 번만 재시도한다.
                $retryProcess = Start-GitReader $Root 'cat-file --batch'
                try {
                    $text = Read-CommittedTextOrNull $retryProcess $BlobMap[$rel]
                } finally {
                    $retryProcess.StandardInput.Close()
                    $retryProcess.WaitForExit()
                    $retryProcess.Dispose()
                }
            }
        } else {
            try {
                # NTFS는 허용하지만 Win32가 금지하는 문자(|, <, >, ", 제어문자)가
                # 섞인 경로는 Test-Path가 비종결 ArgumentException으로 실패한다.
                # -ErrorAction Stop으로 잡아낼 수 있는 종결 오류로 바꾼 뒤,
                # 스캔을 멈추지 않고 건너뛰되 조용히 넘기지 않고 경고로 남긴다.
                if (-not (Test-Path -LiteralPath $absolute -PathType Leaf -ErrorAction Stop)) {
                    continue
                }
                $text = Read-TextFileOrNull $absolute
            } catch {
                Write-Warning ("[AWX][git-guard][SKIP] unreadable-path path=" + $rel)
                continue
            }
        }
        if ($null -eq $text) {
            continue
        }

        $matchedHighConfidencePatterns = @($HighConfidencePatterns | Where-Object { $_.Regex.IsMatch($text) })
        $scanGenericAssignments = (Test-GenericAssignmentScanPath $rel) -and $SensitiveAssignmentPattern.IsMatch($text)
        if ($matchedHighConfidencePatterns.Count -eq 0 -and -not $scanGenericAssignments) {
            continue
        }

        $lines = $text -split "\r?\n"
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            foreach ($pattern in $matchedHighConfidencePatterns) {
                if ($pattern.Regex.IsMatch($line)) {
                    $findings.Add([pscustomobject]@{ Path = $rel; Line = $i + 1; Rule = $pattern.Id; Detail = "high-confidence secret pattern" }) | Out-Null
                }
            }
            if ($scanGenericAssignments -and $SensitiveAssignmentPattern.IsMatch($line)) {
                $findings.Add([pscustomobject]@{ Path = $rel; Line = $i + 1; Rule = "sensitive-assignment"; Detail = "literal sensitive assignment" }) | Out-Null
            }
        }
    }
    return @($findings.ToArray())
}

function Invoke-SelfTest {
    $tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("awx-git-secret-guard-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    try {
        New-Item -ItemType Directory -Force -Path (Join-Path $tempRoot "main/resources") | Out-Null
        Set-Content -LiteralPath (Join-Path $tempRoot "README.md") -Value "safe docs`n" -Encoding UTF8
        $safe = @(Find-SecretGuardFindings -Root $tempRoot -RelativePaths @("README.md"))
        if ($safe.Count -ne 0) {
            throw "safe fixture produced findings"
        }

        $openAiValue = "sk-" + ("A" * 30)
        Set-Content -LiteralPath (Join-Path $tempRoot "main/resources/application.yml") -Value ("OPENAI_API_KEY=" + $openAiValue) -Encoding UTF8
        $openAi = @(Find-SecretGuardFindings -Root $tempRoot -RelativePaths @("main/resources/application.yml"))
        if (-not (@($openAi | Where-Object { $_.Rule -eq "openai" }).Count -gt 0)) {
            throw "OpenAI fixture was not detected"
        }

        $supabaseValue = "sb_secret_" + ("B" * 24)
        Set-Content -LiteralPath (Join-Path $tempRoot "main/resources/application.yml") -Value ("SUPABASE_SECRET_KEY=" + $supabaseValue) -Encoding UTF8
        $supabase = @(Find-SecretGuardFindings -Root $tempRoot -RelativePaths @("main/resources/application.yml"))
        if (-not (@($supabase | Where-Object { $_.Rule -eq "supabase-api-key" }).Count -gt 0)) {
            throw "Supabase fixture was not detected"
        }

        Set-Content -LiteralPath (Join-Path $tempRoot "main/resources/application-secrets.yml") -Value "placeholder: true`n" -Encoding UTF8
        $secretPath = @(Find-SecretGuardFindings -Root $tempRoot -RelativePaths @("main/resources/application-secrets.yml"))
        if (-not (@($secretPath | Where-Object { $_.Rule -eq "spring-secret-profile" }).Count -gt 0)) {
            throw "secret path fixture was not detected"
        }

        Set-Content -LiteralPath (Join-Path $tempRoot "shared.env") -Value "OPAQUE_KEY=short-fixture" -Encoding UTF8
        $sharedEnv = @(Find-SecretGuardFindings -Root $tempRoot -RelativePaths @("shared.env"))
        if (-not (@($sharedEnv | Where-Object { $_.Rule -eq "shared-env-file" }).Count -gt 0)) {
            throw "shared environment path was not blocked"
        }

        foreach ($privatePath in @('.secrets/providers.json','.secrets/build/example.json','config/secrets/sample.json','.secrets/recovery/0.bin')) {
            $blocked = @(Find-SecretGuardFindings -Root $tempRoot -RelativePaths @($privatePath))
            if ($blocked.Count -ne 1 -or $blocked[0].Rule -ne 'project-secrets-area') {
                throw 'shared secrets area bypass'
            }
        }
        Write-Host "[AWX][git-guard][self-test] PASS"
    } finally {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($SelfTest) {
    Invoke-SelfTest
    exit 0
}

$rootPath = Resolve-RepoRoot
$gitAvailable = Test-GitAvailable
$blobMap = @{}

if ($Mode -eq 'pre-push') {
    if (-not $gitAvailable -or $ScanAll -or $Path.Count -gt 0) { throw 'pre-push-scope-unavailable' }
    $blobMap = Get-CommittedBlobMap $rootPath $Commit
    $candidatePaths = @($blobMap.Keys)
} elseif ($Path.Count -gt 0) {
    $inputPaths = @($Path | ForEach-Object {
            $_ -split ","
        } | ForEach-Object {
            $_.Trim()
        } | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        })
    $candidatePaths = @($inputPaths | ForEach-Object {
            if ([IO.Path]::IsPathRooted($_)) {
                Convert-ToRelativePath $rootPath $_
            } else {
                $_.Replace('\', '/')
            }
        })
} elseif ($gitAvailable) {
    $candidatePaths = @(Get-GitPathList -Root $rootPath -ModeValue $Mode -All:$ScanAll)
} else {
    $candidatePaths = @(Get-FallbackPathList -Root $rootPath)
}

$candidatePaths = @($candidatePaths | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

$blobProcess = $null
try {
    if ($Mode -eq 'pre-push') { $blobProcess = Start-GitReader $rootPath 'cat-file --batch' }
    $findings = if ($candidatePaths.Count -eq 0) { @() } else {
        @(Find-SecretGuardFindings -Root $rootPath -RelativePaths $candidatePaths -BlobMap $blobMap -BlobProcess $blobProcess)
    }
} finally {
    if ($null -ne $blobProcess) {
        $blobProcess.StandardInput.Close()
        $blobProcess.WaitForExit()
        $blobProcess.Dispose()
    }
}
$findings = @($findings)
if ($Mode -eq 'pre-push' -and $findings.Count -gt 0) {
    # Only allowance lookup maps IDs; regexes and displayed IDs stay unchanged.
    $allowRuleMap = @{ openai = 'provider-key'; 'google-ai' = 'provider-key' }
    $metadata = @($findings | ForEach-Object {
        $rule = if ($allowRuleMap.ContainsKey($_.Rule)) { $allowRuleMap[$_.Rule] } else { $_.Rule }
        @{ path = $_.Path; rule = $rule; oid = [string]$blobMap[$_.Path] }
    })
    $decisions = ConvertTo-Json -InputObject $metadata -Compress | & python -B (Join-Path $PSScriptRoot 'git_guard_fast.py') --root $rootPath --check-allow-stdin
    if ($LASTEXITCODE -ne 0) { throw 'allowance-check-unavailable' }
    $decisions = ConvertFrom-Json -InputObject ($decisions -join "`n")
    if (@($decisions).Count -ne $findings.Count) { throw 'allowance-check-incomplete' }
    $blocked = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $findings.Count; $i++) {
        if ($decisions[$i] -isnot [bool]) { throw 'allowance-check-invalid' }
        $finding = $findings[$i]
        if ($decisions[$i]) {
            Write-Host "[AWX][git-guard] allowed path=$($finding.Path) rule=$($finding.Rule) oidPrefix=$($blobMap[$finding.Path].Substring(0, 12))"
        } else { $blocked.Add($finding) | Out-Null }
    }
    $findings = @($blocked.ToArray())
}
$findingCount = $findings.Count
Write-Host "[AWX][git-guard] mode=$Mode gitAvailable=$gitAvailable scanned=$(@($candidatePaths).Count) findings=$findingCount root=$rootPath"

if ($findingCount -gt 0) {
    foreach ($finding in @($findings | Select-Object -First 80)) {
        $lineText = if ($finding.Line -gt 0) { " line=$($finding.Line)" } else { "" }
        Write-Host "[AWX][git-guard][BLOCK] path=$($finding.Path)$lineText rule=$($finding.Rule) detail=$($finding.Detail)"
    }
    if ($findingCount -gt 80) {
        Write-Host "[AWX][git-guard][BLOCK] truncatedAdditionalFindings=$($findingCount - 80)"
    }
    Write-Host "[AWX][git-guard] Fix by moving secrets to env/local files, replacing values with placeholders, or unstaging ignored secret files."
    exit 1
}

exit 0
