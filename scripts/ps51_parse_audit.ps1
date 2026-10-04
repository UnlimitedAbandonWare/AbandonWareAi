[CmdletBinding()]
param(
    [string]$Root = '',
    [string]$OutputDirectory = '',
    [switch]$SelfTest
)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
if ($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1) {
    throw 'Run this audit with Windows powershell.exe 5.1.'
}

function Get-TokenLines {
    param($Tokens, [string[]]$Kinds)
    $lines = @()
    foreach ($token in $Tokens) {
        if ([string]$token.Kind -notin $Kinds) { continue }
        $parts = $token.Text -split '\r?\n'
        for ($i = 0; $i -lt $parts.Count; $i++) {
            if ($parts[$i] -match '[가-힣ㄱ-ㅎㅏ-ㅣ]') { $lines += $token.Extent.StartLineNumber + $i }
        }
    }
    return @($lines | Sort-Object -Unique)
}

function Get-ParseRecord {
    param([string]$Path, [string]$RelativePath)
    $bytes = [IO.File]::ReadAllBytes($Path)
    $bom = 'none'
    $encoding = [Text.Encoding]::UTF8
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) {
        $bom = 'utf8'
    } elseif ($bytes.Length -ge 4 -and $bytes[0] -eq 255 -and $bytes[1] -eq 254 -and $bytes[2] -eq 0 -and $bytes[3] -eq 0) {
        $bom = 'utf32le'; $encoding = [Text.Encoding]::UTF32
    } elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 255 -and $bytes[1] -eq 254) {
        $bom = 'utf16le'; $encoding = [Text.Encoding]::Unicode
    } elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 254 -and $bytes[1] -eq 255) {
        $bom = 'utf16be'; $encoding = [Text.Encoding]::BigEndianUnicode
    }
    $tokens = $null; $errors = $null
    # Actual 5.1 file decoding is authoritative for ParseErrors.
    [void][Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    # Decode original UTF-8 explicitly for Korean location; ParseFile may have garbled it.
    $source = $encoding.GetString($bytes).TrimStart([char]0xFEFF)
    $sourceTokens = $null; $sourceErrors = $null
    [void][Management.Automation.Language.Parser]::ParseInput($source, [ref]$sourceTokens, [ref]$sourceErrors)
    $stringKinds = @('StringLiteral', 'StringExpandable', 'HereStringLiteral', 'HereStringExpandable')
    $strings = @(Get-TokenLines $sourceTokens $stringKinds)
    $comments = @(Get-TokenLines $sourceTokens @('Comment'))
    $operators = @($sourceTokens | Where-Object {
        ([string]$_.Kind -notin ($stringKinds + @('Comment'))) -and $_.Text -in @('&&', '||')
    } | ForEach-Object { [ordered]@{ line = $_.Extent.StartLineNumber; operator = $_.Text } })
    $errorRows = @($errors | ForEach-Object {
        [ordered]@{ line = $_.Extent.StartLineNumber; column = $_.Extent.StartColumnNumber; errorId = $_.ErrorId }
    })
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $sha = ([BitConverter]::ToString($hasher.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose() }
    $grade = 'OK'
    if ($errorRows.Count -gt 0) { $grade = 'BREAKS' }
    elseif ($bom -eq 'none' -and $strings.Count -gt 0) { $grade = 'GARBLE' }
    elseif ($bom -eq 'none' -and $comments.Count -gt 0) { $grade = 'COSMETIC' }
    return [ordered]@{
        path = $RelativePath; sha256 = $sha
        bom = $bom; grade = $grade; parseErrors = $errorRows
        koreanStringLines = $strings; koreanCommentLines = $comments; chainOperators = $operators
    }
}

if ($SelfTest) {
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('awx-ps51-audit-' + [guid]::NewGuid().ToString('N'))
    try {
        [void][IO.Directory]::CreateDirectory($temp)
        $cases = @(
            @{ name = 'bom.ps1'; text = "Write-Output '한글'"; bom = $true; grade = 'OK'; ops = 0 },
            @{ name = 'no-bom.ps1'; text = "Write-Output '한글'"; bom = $false; grade = 'GARBLE'; ops = 0 },
            @{ name = 'comment.ps1'; text = "# 한글`nWrite-Output 'ok'"; bom = $false; grade = 'COSMETIC'; ops = 0 },
            @{ name = 'and.ps1'; text = 'echo a && echo b'; bom = $true; grade = 'BREAKS'; ops = 1 },
            @{ name = 'or.ps1'; text = 'echo a || echo b'; bom = $true; grade = 'BREAKS'; ops = 1 },
            @{ name = 'quoted.ps1'; text = "Write-Output '&& ||' # && ||"; bom = $true; grade = 'OK'; ops = 0 },
            @{ name = 'here.ps1'; text = "@'`n&& ||`n'@"; bom = $true; grade = 'OK'; ops = 0 }
        )
        foreach ($case in $cases) {
            $path = Join-Path $temp $case.name
            [IO.File]::WriteAllText($path, $case.text, (New-Object Text.UTF8Encoding([bool]$case.bom)))
            $row = Get-ParseRecord $path $case.name
            if ($row.grade -ne $case.grade -or $row.chainOperators.Count -ne $case.ops) { throw ('self-test mismatch: ' + $case.name) }
        }
        [ordered]@{ selfTest = 'PASS'; cases = $cases.Count; powershellVersion = $PSVersionTable.PSVersion.ToString() } | ConvertTo-Json -Compress
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    }
    exit 0
}

$Root = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $Root 'var/codex-assist-ps51' }
$files = @(Get-ChildItem -LiteralPath $Root -Filter '*.ps1' -File)
foreach ($directory in @('scripts', '__patch_drop__')) {
    $folder = Join-Path $Root $directory
    if (Test-Path -LiteralPath $folder) {
        $files += @(Get-ChildItem -LiteralPath $folder -Filter '*.ps1' -File -Recurse | Where-Object {
            $_.FullName -notmatch '(?i)[\\/][^\\/]*(archive|quarantine)[^\\/]*[\\/]'
        })
    }
}
$rows = @($files | Sort-Object FullName -Unique | ForEach-Object {
    Get-ParseRecord $_.FullName $_.FullName.Substring($Root.Length + 1).Replace('\', '/')
})
$batRows = @()
foreach ($bat in (Get-ChildItem -LiteralPath $Root -Filter '*.bat' -File | Sort-Object Name)) {
    $lines = [IO.File]::ReadAllLines($bat.FullName)
    $aliases = @()
    foreach ($item in $lines) {
        if ($item -match '(?i)^\s*set\s+"?(?<alias>[A-Za-z0-9_]+)=[^"]*powershell(?:\.exe)?"?\s*$') { $aliases += [regex]::Escape($Matches.alias) }
    }
    $commandPattern = '(?i)(?<![\w-])powershell(?:\.exe)?"?\s+-[A-Za-z]'
    if ($aliases.Count -gt 0) { $commandPattern += '|%(?:' + ($aliases -join '|') + ')%"?\s+-[A-Za-z]' }
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '(?i)^\s*@?(rem\b|::|echo\b|set\b)') { continue }
        $number = $i + 1
        while ($line.TrimEnd().EndsWith('^') -and $i + 1 -lt $lines.Count) { $i++; $line += ' ' + $lines[$i] }
        if ($line -notmatch $commandPattern) { continue }
        $batRows += [ordered]@{
            path = $bat.Name; line = $number
            noProfile = [bool]($line -match '(?i)-NoProfile\b')
            executionPolicy = [bool]($line -match '(?i)-ExecutionPolicy\b')
            chcp65001 = [bool](($lines -join "`n") -match '(?im)^\s*@?chcp\s+65001\b')
        }
    }
}
$counts = [ordered]@{}
foreach ($grade in @('BREAKS', 'GARBLE', 'COSMETIC', 'OK')) { $counts[$grade] = @($rows | Where-Object { $_.grade -eq $grade }).Count }
$report = [ordered]@{
    schemaVersion = 'awx.ps51-parse-audit.v1'; capturedAtUtc = [DateTime]::UtcNow.ToString('o')
    powershellVersion = $PSVersionTable.PSVersion.ToString(); root = $Root; fileCount = $rows.Count
    excludedDirectories = @('archive', 'quarantine'); counts = $counts; files = $rows; batInvocations = $batRows
}
[void][IO.Directory]::CreateDirectory($OutputDirectory)
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'audit.json'), ($report | ConvertTo-Json -Depth 9), (New-Object Text.UTF8Encoding($false)))
$md = @('# Windows PowerShell 5.1 parse audit', '', ('Engine: ' + $report.powershellVersion), ('Files: ' + $rows.Count), '', '| File | Grade | BOM | Error lines | Korean string lines | Korean comment lines | Chain lines |', '|---|---|---|---|---|---|---|')
foreach ($row in $rows) {
    $md += '| ' + $row.path + ' | ' + $row.grade + ' | ' + $row.bom + ' | ' + (($row.parseErrors | ForEach-Object { $_.line }) -join ',') + ' | ' + ($row.koreanStringLines -join ',') + ' | ' + ($row.koreanCommentLines -join ',') + ' | ' + (($row.chainOperators | ForEach-Object { $_.line }) -join ',') + ' |'
}
$md += @('', '| BAT | Line | NoProfile | ExecutionPolicy | chcp 65001 |', '|---|---|---|---|---|')
foreach ($row in $batRows) { $md += '| ' + $row.path + ' | ' + $row.line + ' | ' + $row.noProfile + ' | ' + $row.executionPolicy + ' | ' + $row.chcp65001 + ' |' }
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'audit.md'), ($md -join "`r`n"), (New-Object Text.UTF8Encoding($false)))
[ordered]@{ fileCount = $rows.Count; counts = $counts; batInvocations = $batRows.Count; output = $OutputDirectory } | ConvertTo-Json -Compress
