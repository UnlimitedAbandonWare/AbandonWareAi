#requires -Version 5.1
# Scratch: re-encode listed files as UTF-8 with BOM (PS 5.1 parses Korean safely).
param([string[]]$Files)
$utf8noBom = [System.Text.UTF8Encoding]::new($false)
$utf8Bom   = [System.Text.UTF8Encoding]::new($true)
foreach ($f in $Files) {
  $p = (Resolve-Path $f).Path
  $t = [System.IO.File]::ReadAllText($p, $utf8noBom)
  [System.IO.File]::WriteAllText($p, $t, $utf8Bom)
  Write-Host "BOM-rewrote $f"
}
