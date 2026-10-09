'use strict';

// One invocation owns begin -> verify -> edit -> end. Inject the real Codex
// exec_command/apply_patch tools; do not call apply_patch separately afterwards.
// This is cooperative orchestration, not a sandbox for other tools/processes.
const quote = value => "'" + String(value).replace(/'/g, "''") + "'";
const tail = (value, max = 400) => String(value == null ? '' : value).slice(-max);
const failureLine = text => {
  const line = String(text || '').split(/\r?\n/).reverse()
    .find(l => l.includes('source-edit-child-'));
  return line ? tail(line.trim(), 600) : '';
};
function canonical(value) {
  const p = String(value).replace(/\\/g, '/');
  if (!p || p.startsWith('/') || p.includes(':') || p.split('/').some(x => !x || x === '.' || x === '..'))
    throw new Error('invalid-patch-path');
  return p.toLowerCase();
}
function patchPaths(patch) {
  if (!patch.startsWith('*** Begin Patch\n') || !patch.trimEnd().endsWith('*** End Patch'))
    throw new Error('invalid-patch');
  const paths = [...patch.matchAll(/^\*\*\* (?:Add File|Update File|Delete File|Move to): (.+)$/gm)]
    .map(match => canonical(match[1]));
  if (!paths.length) throw new Error('empty-patch');
  return paths;
}
function command(options, action, fingerprint, expectedInputDigest) {
  const script = options.root.replace(/\\/g, '/') + '/__patch_drop__/source_edit_session.ps1';
  const resume = options.resume;
  const resumeChild = action === 'resume-check' ? '& python -B '
    + quote(options.root.replace(/\\/g,'/') + '/scripts/lease_resume_check.py')
    + ' --gate --before ' + quote(resume.beforePath) + ' --before-sha256 ' + quote(resume.beforeSha256)
    + ' --root ' + quote(options.root) + ' --task ' + quote(options.task)
    + ' --goal-revision ' + quote(resume.goalRevision) + ' --plan-revision ' + quote(resume.planRevision)
    + ' --receipt-base64 ' + quote(Buffer.from(JSON.stringify(fingerprint),'utf8').toString('base64'))
    + ' --patch-sha256 ' + quote(require('node:crypto').createHash('sha256').update(options.patch,'utf8').digest('hex'))
    + ' --decision-base64 ' + quote(Buffer.from(JSON.stringify(resume.decision || null),'utf8').toString('base64'))
    + (expectedInputDigest ? ' --expected-input-digest ' + quote(expectedInputDigest) : '')
    + '; exit $LASTEXITCODE' : null;
  const child = resumeChild || "$ProgressPreference='SilentlyContinue'; Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Force; & "
    + quote(script) + ' -Action ' + action + ' -Root ' + quote(options.root)
    + ' -Role desktop -Topic ' + quote(options.topic) + ' -OwnerId ' + quote(options.owner)
    + ' -TargetManifest ' + quote(options.manifest)
    + (options.task ? ' -TaskId ' + quote(options.task) : '')
    + (fingerprint ? ' -LeaseFingerprint ' + quote(fingerprint) : '')
    + (action === 'verify' ? ' -RequireAbsentTargets' : '')
    + (action === 'begin' ? ' -Json' : '') + '; exit $LASTEXITCODE';
  // Read the actual child Process.ExitCode, never an inherited LASTEXITCODE in
  // the calling shell. Explicit timeout is independent of native-error settings.
  return `$child=${quote(child)}
$p=[Diagnostics.Process]::new()
$p.StartInfo.FileName='powershell.exe'
$p.StartInfo.Arguments='-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand '+[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($child))
$p.StartInfo.UseShellExecute=$false
$p.StartInfo.CreateNoWindow=$true
$p.StartInfo.RedirectStandardOutput=$true
$p.StartInfo.RedirectStandardError=$true
$p.StartInfo.EnvironmentVariables.Remove('PSModulePath')
try {
  [void]$p.Start()
  $stdout=$p.StandardOutput.ReadToEndAsync()
  $stderr=$p.StandardError.ReadToEndAsync()
  $errTail=''
  if(-not $p.WaitForExit(25000)) {
    try { $p.Kill() } catch { }
    [void]$p.WaitForExit(5000)
    if($stderr.IsCompleted) { try { $errTail=$stderr.Result } catch { } }
    $errTail=($errTail -replace '[A-Za-z0-9+/_=-]{24,}','<redacted>' -replace '\\r?\\n',' ')
    if($errTail.Length -gt 400){$errTail=$errTail.Substring($errTail.Length-400)}
    [Console]::Error.WriteLine('source-edit-child-timeout exitCode=124 stderrTail=' + $errTail)
    exit 124
  }
  $code=$p.ExitCode
  [Console]::Out.Write($stdout.GetAwaiter().GetResult())
  if($code -ne 0) {
    if($stderr.IsCompleted) { try { $errTail=$stderr.Result } catch { } }
    $errTail=($errTail -replace '[A-Za-z0-9+/_=-]{24,}','<redacted>' -replace '\\r?\\n',' ')
    if($errTail.Length -gt 400){$errTail=$errTail.Substring($errTail.Length-400)}
    [Console]::Error.WriteLine('source-edit-child-failed exitCode=' + $code + ' stderrTail=' + $errTail)
  }
  exit $code
} finally { $p.Dispose() }`;
}
async function guardedSourceEdit(tools, options) {
  options = {...options, resume: options.resume && JSON.parse(JSON.stringify(options.resume))};
  const root = String(options.root || '').replace(/\\/g,'/').replace(/\/+$/,'');
  if (!/^[a-zA-Z0-9_.-]+$/.test(options.topic) || !options.owner || !options.manifest
    || !/^[a-zA-Z]:\//.test(root)
    || root.slice(3).split('/').some(part => part === '.' || part === '..' || !part))
    throw new Error('invalid-edit-options');
  const paths = patchPaths(options.patch);
  const result = {status: 'hold', phase: 'begin', editCalls: 0, released: false};
  if ((options.waited || options.resume) && (!options.resume || !options.task
      || !options.resume.beforePath || !/^[a-f0-9]{64}$/.test(options.resume.beforeSha256 || '')
      || !options.resume.goalRevision || !options.resume.planRevision)) {
    result.reason = 'resume-baseline-required';
    return result;
  }
  let receipt;
  async function run(action, fingerprint, expectedInputDigest) {
    return await tools.exec_command({cmd: command(options, action, fingerprint, expectedInputDigest), workdir: options.root,
      yield_time_ms: 1000, max_output_tokens: 2000});
  }
  async function completed(action, fingerprint, expectedInputDigest) {
    let r = await run(action, fingerprint, expectedInputDigest);
    const parts = [r.output || ''];
    chunks.push(r.output || '');
    // PTY session IDs are ongoing commands, never successful acquisition.
    while (r.session_id && r.exit_code == null) {
      r = await tools.write_stdin({session_id: r.session_id, chars: '', yield_time_ms: 1000, max_output_tokens: 2000});
      // Receipt output must be retained across yields.
      chunks.push(r.output || '');
      parts.push(r.output || '');
    }
    return {...r, output: parts.join('\n')};
  }
  async function resumeGate(expectedInputDigest) {
    const checked = await completed('resume-check', receipt, expectedInputDigest);
    const row = String(checked.output || '').split(/\r?\n/).reverse()
      .find(line => line.startsWith('{') && line.includes('resumeAllowed'));
    let proof;
    try { proof = row ? JSON.parse(row) : null; } catch { proof = null; }
    result.resume = proof;
    const allowed = checked.exit_code === 0 && proof?.resumeAllowed === true
      && ['APPLY','SKIP_ALREADY_DONE'].includes(proof.status)
      && /^[a-f0-9]{64}$/.test(proof.inputDigest || '')
      && proof.leaseId === receipt.leaseId && proof.fingerprint === receipt.fingerprint;
    if (!allowed) {
      result.status = proof?.status === 'REPLAN_REQUIRED' ? 'replan-required' : 'hold';
      result.reason = proof?.reason || 'resume-gate-unproven';
    }
    return allowed ? proof : null;
  }
  let chunks = [];
  try {
    // Keep all chunks including output before an asynchronous yield.
    let begun = await run('begin');
    chunks.push(begun.output || '');
    while (begun.session_id && begun.exit_code == null) {
      begun = await tools.write_stdin({session_id: begun.session_id, chars: '', yield_time_ms: 1000, max_output_tokens: 2000});
      chunks.push(begun.output || '');
    }
    result.beginExitCode = begun.exit_code ?? null;
    if (begun.exit_code !== 0) {
      result.timedOut = begun.exit_code === 124;
      result.outputTail = tail(chunks.join(''));
      result.failureLine = failureLine(chunks.join(''));
      return result;
    }
    const row = chunks.join('\n').split(/\r?\n/).find(line => line.startsWith('{') && line.includes('awx.source-edit-acquired.v1'));
    receipt = row ? JSON.parse(row) : null;
    if (!receipt || receipt.acquired !== true || receipt.topic !== options.topic
      || !/^[a-f0-9]{32}$/.test(receipt.leaseId)
      || String(receipt.root || '').replace(/\\/g,'/').replace(/\/+$/,'').toLowerCase()
        !== options.root.replace(/\\/g,'/').replace(/\/+$/,'').toLowerCase()
      || receipt.taskId !== (options.task || '')
      || !/^[a-f0-9]{64}$/.test(receipt.fingerprint) || !/^[a-f0-9]{64}$/.test(receipt.manifestHash)) {
      receipt = null;
      result.reason = 'fresh-acquisition-receipt-missing';
      return result;
    }
    result.phase = 'scope';
    const allowed = new Set(receipt.writePaths.map(canonical));
    if (paths.some(path => !allowed.has(path))) { result.reason = 'patch-outside-declared-targets'; return result; }
    let resumeProof;
    if (options.resume) {
      result.phase = 'resume';
      resumeProof = await resumeGate();
      if (!resumeProof) return result;
    }
    result.phase = 'verify';
    const verified = await completed('verify', receipt.fingerprint);
    result.verifyExitCode = verified.exit_code ?? null;
    if (verified.exit_code !== 0) {
      result.timedOut = verified.exit_code === 124;
      result.outputTail = tail(chunks.join(''));
      result.failureLine = failureLine(chunks.join(''));
      return result;
    }
    if (resumeProof) {
      result.phase = 'resume-final';
      resumeProof = await resumeGate(resumeProof.inputDigest);
      if (!resumeProof) return result;
      if (resumeProof.status === 'SKIP_ALREADY_DONE') {
        result.status = 'skipped-already-done';
        return result;
      }
    }
    result.phase = 'edit';
    result.editCalls++;
    const edited = await tools.apply_patch(options.patch);
    if (edited?.isError || (edited?.exit_code != null && edited.exit_code !== 0))
      throw new Error('edit-tool-failed');
    result.status = 'applied';
    return result;
  } finally {
    if (receipt) {
      const ended = await completed('end', receipt.fingerprint);
      result.releaseExitCode = ended.exit_code ?? null;
      result.released = ended.exit_code === 0;
      if (!result.released) result.status = 'hold';
    }
  }
}
module.exports = {guardedSourceEdit, command, patchPaths};
