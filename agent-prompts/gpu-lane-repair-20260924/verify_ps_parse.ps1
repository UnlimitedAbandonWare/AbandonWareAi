#requires -Version 5.1
# Scratch parse verifier for gpu-lane-repair-prep task (not a repo tool).
param([string[]]$Files)
$bad = 0
foreach ($f in $Files) {
  $errs = $null
  [void][System.Management.Automation.PSParser]::Tokenize((Get-Content $f -Raw), [ref]$errs)
  if ($errs.Count -gt 0) {
    $bad += $errs.Count
    foreach ($e in $errs) {
      Write-Host ("{0}:{1}: {2}" -f $f, $e.Token.StartLine, $e.Message)
    }
  } else {
    Write-Host "PARSE-OK $f"
  }
}
exit $bad
