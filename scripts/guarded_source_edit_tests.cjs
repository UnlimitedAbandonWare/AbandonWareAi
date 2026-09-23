'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {guardedSourceEdit, command} = require('./guarded_source_edit.js');
const options = {root:'C:/fixture', manifest:'C:/fixture/targets.json', topic:'fixture', owner:'test-owner',
  patch:'*** Begin Patch\n*** Update File: target.txt\n@@\n-old\n+new\n*** End Patch\n'};
const receipt = JSON.stringify({schema:'awx.source-edit-acquired.v1',acquired:true,topic:'fixture',
  fingerprint:'a'.repeat(64),manifestHash:'b'.repeat(64),writePaths:['target.txt']});
test('real scoped lease: one edit, normal release, foreign lease unchanged',async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'awx-real-edit-'));
  fs.mkdirSync(path.join(dir,'__patch_drop__'));
  for(const name of ['source_edit_session.ps1','source_edit_lease_contract.ps1'])
    fs.copyFileSync(path.join(__dirname,'..','__patch_drop__',name),path.join(dir,'__patch_drop__',name));
  // Match existing repository fixtures: unrelated Desktop process inventory is
  // excluded; Git metadata, scope, leases and file hashes remain real.
  const contract=path.join(dir,'__patch_drop__','source_edit_lease_contract.ps1');
  fs.appendFileSync(contract,'\nfunction Get-CimInstance { @() }\n');
  assert.equal(spawnSync('git',['init','--quiet',dir]).status,0);
  const file=path.join(dir,'target.txt');fs.writeFileSync(file,'old');
  const manifest=path.join(dir,'targets.json');
  fs.writeFileSync(manifest,JSON.stringify({targets:[{path:'target.txt',sha256:require('node:crypto').createHash('sha256').update('old').digest('hex')}]}));
  let calls=0;
  const tools={exec_command:async(args)=>{
    const r=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',args.cmd],{cwd:dir,encoding:'utf8',timeout:35000});
    assert.ifError(r.error);return {exit_code:r.status,output:r.stdout};
  },apply_patch:async()=>{calls++;fs.writeFileSync(file,'new');return {exit_code:0}}};
  try {
    const passed=await guardedSourceEdit(tools,{...options,root:dir,manifest});
    assert.equal(passed.status,'applied',JSON.stringify(passed));assert.equal(calls,1);assert.equal(passed.released,true);
    assert.equal(fs.existsSync(path.join(dir,'__patch_drop__','source-edit-locks','fixture.lock')),false);
    fs.writeFileSync(file,'old');
    calls=0;
    const wrongOwnerTools={...tools,exec_command:async(args)=>tools.exec_command({
      ...args,cmd:args.cmd.includes(' -Action verify ')?command({...options,root:dir,manifest,owner:'wrong-owner'},'verify'):args.cmd
    })};
    const wrongOwner=await guardedSourceEdit(wrongOwnerTools,{...options,root:dir,manifest});
    assert.equal(wrongOwner.verifyExitCode,3);assert.equal(calls,0);assert.equal(fs.readFileSync(file,'utf8'),'old');
    assert.equal(wrongOwner.released,true);
    const foreign=await tools.exec_command({cmd:command({...options,root:dir,manifest,topic:'foreign',owner:'other-owner'},'begin')});
    assert.equal(foreign.exit_code,0);
    const lease=path.join(dir,'__patch_drop__','source-edit-locks','foreign.lock','lease.json');const before=fs.readFileSync(lease);
    calls=0;
    const blocked=await guardedSourceEdit(tools,{...options,root:dir,manifest});
    assert.equal(blocked.beginExitCode,7);assert.equal(calls,0);assert.equal(fs.readFileSync(file,'utf8'),'old');
    assert.deepEqual(fs.readFileSync(lease),before);
    const row=JSON.parse(foreign.output.trim());
    assert.equal((await tools.exec_command({cmd:command({...options,root:dir,manifest,topic:'foreign',owner:'other-owner'},'end',row.fingerprint)})).exit_code,0);
  } finally {fs.rmSync(dir,{recursive:true,force:true});}
});
async function scenario(codes, output=receipt, opts={}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(),'awx-edit-gate-'));
  const file = path.join(dir,'target.txt'); fs.writeFileSync(file,'old');
  let calls=0,commands=0;
  const tools={exec_command:async()=>({exit_code:codes[commands++],output:commands===1?output:''}),
    apply_patch:async()=>{calls++;fs.writeFileSync(file,'new');return {exit_code:0}}};
  try {const result=await guardedSourceEdit(tools,{...options,...opts});return {result,calls,commands,text:fs.readFileSync(file,'utf8')};}
  finally {fs.rmSync(dir,{recursive:true,force:true});}
}
for (const [label,code] of [['exit 7',7],['timeout',124],['owner mismatch',3],['unknown exit',undefined]]) {
  test(`failed acquisition ${label}: edit tool never invoked`,async()=>{
    const r=await scenario([code]);assert.equal(r.calls,0);assert.equal(r.text,'old');assert.equal(r.commands,1);
  });
}
test('zero exit without fresh acquisition receipt cannot edit or release an unknown lease',async()=>{
  const r=await scenario([0],'');assert.equal(r.calls,0);assert.equal(r.commands,1);assert.equal(r.text,'old');
});
for (const code of [7,124,3]) test(`failed immediate verification ${code} blocks edit and releases only bound lease`,async()=>{
  const r=await scenario([0,code,0]);assert.equal(r.calls,0);assert.equal(r.text,'old');assert.equal(r.commands,3);assert.equal(r.result.released,true);
});
test('valid current lease edits once then releases',async()=>{
  const r=await scenario([0,0,0]);assert.equal(r.calls,1);assert.equal(r.text,'new');assert.equal(r.result.released,true);
});
test('out of scope patch invokes no edit',async()=>{
  const r=await scenario([0,0],receipt,{patch:options.patch.replace('target.txt','other.txt')});
  assert.equal(r.calls,0);assert.equal(r.text,'old');assert.equal(r.commands,2);
});
test('release failure is not reported as successful completion',async()=>{
  const r=await scenario([0,0,3]);assert.equal(r.result.status,'hold');assert.equal(r.result.released,false);
});
test('actual Windows PowerShell child exit 7 survives a stale successful parent LASTEXITCODE',async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'awx-exit7-'));fs.mkdirSync(path.join(dir,'__patch_drop__'));
  fs.writeFileSync(path.join(dir,'__patch_drop__','source_edit_session.ps1'),'exit 7\n');
  const target=path.join(dir,'target.txt');fs.writeFileSync(target,'old');let edits=0;
  try {
    const result=await guardedSourceEdit({exec_command:async(args)=>{
      const r=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command','$global:LASTEXITCODE=0;\n'+args.cmd],{encoding:'utf8',timeout:35000});
      assert.ifError(r.error);return {exit_code:r.status,output:r.stdout};
    },apply_patch:async()=>{edits++;fs.writeFileSync(target,'new')}},{...options,root:dir});
    assert.equal(result.beginExitCode,7);assert.equal(edits,0);assert.equal(fs.readFileSync(target,'utf8'),'old');
  } finally {fs.rmSync(dir,{recursive:true,force:true});}
});
test('actual stalled Windows PowerShell child times out with no edit or file change',async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'awx-timeout-'));fs.mkdirSync(path.join(dir,'__patch_drop__'));
  fs.writeFileSync(path.join(dir,'__patch_drop__','source_edit_session.ps1'),'Start-Sleep -Seconds 60; exit 0\n');
  const target=path.join(dir,'target.txt');fs.writeFileSync(target,'old');let edits=0;
  try {
    const result=await guardedSourceEdit({exec_command:async(args)=>{
      const r=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',args.cmd],{encoding:'utf8',timeout:35000});
      assert.ifError(r.error);return {exit_code:r.status,output:(r.stdout||'')+(r.stderr||'')};
    },apply_patch:async()=>{edits++;fs.writeFileSync(target,'new')}},{...options,root:dir});
    assert.equal(result.beginExitCode,124);assert.equal(result.timedOut,true);
    assert.match(result.failureLine,/source-edit-child-timeout exitCode=124/);
    assert.equal(edits,0);assert.equal(fs.readFileSync(target,'utf8'),'old');
  } finally {fs.rmSync(dir,{recursive:true,force:true});}
});
test('failed Windows PowerShell child surfaces exit code and masked stderr tail',async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'awx-evid-'));fs.mkdirSync(path.join(dir,'__patch_drop__'));
  fs.writeFileSync(path.join(dir,'__patch_drop__','source_edit_session.ps1'),
    "[Console]::Error.Write('denied ' + ('x'*40) + ' reason'); exit 7\n");
  const target=path.join(dir,'target.txt');fs.writeFileSync(target,'old');let edits=0;
  try {
    const result=await guardedSourceEdit({exec_command:async(args)=>{
      const r=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',args.cmd],{encoding:'utf8',timeout:35000});
      assert.ifError(r.error);return {exit_code:r.status,output:(r.stdout||'')+(r.stderr||'')};
    },apply_patch:async()=>{edits++;fs.writeFileSync(target,'new')}},{...options,root:dir});
    assert.equal(result.beginExitCode,7);assert.equal(result.timedOut,false);assert.equal(edits,0);
    assert.match(result.failureLine,/source-edit-child-failed exitCode=7/);
    assert.match(result.failureLine,/stderrTail=.*<redacted>/);
    assert.doesNotMatch(result.failureLine,/x{40}/);
    assert.equal(fs.readFileSync(target,'utf8'),'old');
  } finally {fs.rmSync(dir,{recursive:true,force:true});}
});
