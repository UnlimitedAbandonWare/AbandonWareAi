'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawnSync,spawn} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {guardedSourceEdit, command} = require('./guarded_source_edit.js');
const options = {root:'C:/fixture', manifest:'C:/fixture/targets.json', topic:'fixture', owner:'test-owner',
  patch:'*** Begin Patch\n*** Update File: target.txt\n@@\n-old\n+new\n*** End Patch\n'};
const receipt = JSON.stringify({schema:'awx.source-edit-acquired.v1',acquired:true,topic:'fixture',
  root:'C:/fixture',taskId:'',leaseId:'c'.repeat(32),
  fingerprint:'a'.repeat(64),manifestHash:'b'.repeat(64),writePaths:['target.txt']});
test('pre-edit verify uses strict absent target checks',()=>{
  assert.match(command(options,'verify','a'.repeat(64)),/ -RequireAbsentTargets/);
  assert.doesNotMatch(command(options,'end','a'.repeat(64)),/ -RequireAbsentTargets/);
});
for(const root of ['.','C:/fixture/../fixture'])
  test(`noncanonical root ${root} is rejected before acquisition`,async()=>{
    let commands=0,edits=0;
    await assert.rejects(()=>guardedSourceEdit({
      exec_command:async()=>{commands++;return {exit_code:0,output:receipt}},
      apply_patch:async()=>{edits++}
    },{...options,root}),/invalid-edit-options/);
    assert.equal(commands,0);assert.equal(edits,0);
  });
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
    const created = path.join(dir,'new.txt');
    fs.writeFileSync(manifest,JSON.stringify({targets:[{path:'new.txt',sha256:null}]}));
    calls=0;
    const lateTools={...tools,exec_command:async(args)=>{
      const r=await tools.exec_command(args);
      if(args.cmd.includes(' -Action begin ') && r.exit_code===0) fs.writeFileSync(created,'foreign bytes');
      return r;
    },apply_patch:async()=>{calls++;fs.writeFileSync(created,'stale planned bytes');return {exit_code:0}}};
    const late=await guardedSourceEdit(lateTools,{...options,root:dir,manifest,
      patch:'*** Begin Patch\n*** Add File: new.txt\n+stale planned bytes\n*** End Patch\n'});
    assert.equal(calls,0,JSON.stringify(late));
    assert.equal(fs.readFileSync(created,'utf8'),'foreign bytes');
    assert.equal(late.verifyExitCode,6);
    assert.equal(late.released,true);
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
for(const mismatch of [{root:'C:/other'},{taskId:'older-task'},{leaseId:''}])
  test(`receipt identity mismatch ${Object.keys(mismatch)[0]} never edits`,async()=>{
    const r=await scenario([0,0,0],JSON.stringify({...JSON.parse(receipt),...mismatch}));
    assert.equal(r.calls,0);assert.equal(r.text,'old');assert.equal(r.commands,1);
  });
for (const code of [7,124,3]) test(`failed immediate verification ${code} blocks edit and releases only bound lease`,async()=>{
  const r=await scenario([0,code,0]);assert.equal(r.calls,0);assert.equal(r.text,'old');assert.equal(r.commands,3);assert.equal(r.result.released,true);
});
test('valid current lease edits once then releases',async()=>{
  const r=await scenario([0,0,0]);assert.equal(r.calls,1);assert.equal(r.text,'new');assert.equal(r.result.released,true);
});
test('waited resume without an immutable baseline cannot acquire or edit',async()=>{
  const r=await scenario([0,0,0],receipt,{waited:true});
  assert.equal(r.calls,0);assert.equal(r.text,'old');assert.equal(r.commands,0);
  assert.equal(r.result.reason,'resume-baseline-required');
});
const resumeOptions={...options,task:'',waited:true,resume:{beforePath:'C:/fixture/before.json',
  beforeSha256:'d'.repeat(64),goalRevision:'g1',planRevision:'1'}};
async function resumeScenario(proofs) {
  let commands=0,edits=0,gateCalls=0;
  const opts={...resumeOptions,task:'resume-task'};
  const fresh={...JSON.parse(receipt),taskId:opts.task};
  const result=await guardedSourceEdit({exec_command:async args=>{
    commands++;
    if(args.cmd.includes(' --gate ')) {
      const proof=proofs[gateCalls++];
      return {exit_code:proof.resumeAllowed?0:30,output:JSON.stringify(proof)};
    }
    return {exit_code:0,output:args.cmd.includes(' -Action begin ')?JSON.stringify(fresh):''};
  },apply_patch:async()=>{edits++;return {exit_code:0}}},opts);
  return {result,commands,edits,gateCalls};
}
for(const status of ['BLOCKED','REPLAN_REQUIRED']) test(`resume ${status} from actual gate blocks edit and releases`,async()=>{
  const r=await resumeScenario([{status,resumeAllowed:false,reason:'current-plan-evidence-required'}]);
  assert.equal(r.edits,0);assert.equal(r.result.editCalls,0);assert.equal(r.result.released,true);
  assert.equal(r.commands,3);assert.equal(r.gateCalls,1);
});
const gateProof={status:'APPLY',resumeAllowed:true,inputDigest:'e'.repeat(64),
  leaseId:'c'.repeat(32),fingerprint:'a'.repeat(64)};
test('input drift after verification is caught by final resume gate with zero edits',async()=>{
  const r=await resumeScenario([gateProof,{status:'BLOCKED',resumeAllowed:false,reason:'input-changed-before-mutation'}]);
  assert.equal(r.edits,0);assert.equal(r.result.editCalls,0);assert.equal(r.result.released,true);
  assert.equal(r.result.reason,'input-changed-before-mutation');assert.equal(r.gateCalls,2);
});
test('fresh already-done gate skips edit and releases the exact acquisition',async()=>{
  const proof={...gateProof,status:'SKIP_ALREADY_DONE'};
  const r=await resumeScenario([proof,proof]);
  assert.equal(r.edits,0);assert.equal(r.result.editCalls,0);assert.equal(r.result.released,true);
  assert.equal(r.result.status,'skipped-already-done');assert.equal(r.gateCalls,2);
});
test('fresh resumed plan passes both gates then performs exactly one edit',async()=>{
  const r=await resumeScenario([gateProof,gateProof]);
  assert.equal(r.edits,1);assert.equal(r.result.editCalls,1);assert.equal(r.result.released,true);
  assert.equal(r.result.status,'applied');assert.equal(r.gateCalls,2);
});

for(const mode of ['apply','already-done','manifest-drift','acquire-drift','config-drift','wait-release','rename-path','recreate-path']) test(`real owned lease and bound goal receipt: ${mode}`,async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'awx-resume-real-'));
  let edits=0,waitProcess,foreignOptions,foreignReceipt;
  try {
    const scriptRoot=__dirname;
    const setup=spawnSync('python',['-B','-c',
      "import sys,json,hashlib; from pathlib import Path; sys.path.insert(0,sys.argv[1]); "
      +"from test_lease_resume_check import gate_fixture; "
      +"args,decision,patch=gate_fixture(sys.argv[2],value='fixed\\n' if sys.argv[3]=='already-done' else 'old\\n'); "
      +"print(json.dumps({'beforePath':str(args[0]),'beforeSha256':args[1],'goalRevision':'g1','planRevision':'1','decision':decision,'patch':patch}))",
      scriptRoot,dir,mode],{encoding:'utf8',timeout:35000});
    assert.ifError(setup.error);assert.equal(setup.status,0,setup.stderr);
    const fixture=JSON.parse(setup.stdout.trim());
    fs.mkdirSync(path.join(dir,'scripts'));
    // Execute the actual repository gate and validator in the isolated test root.
    // This bootstrap is test-only and adds no production service or wrapper.
    fs.writeFileSync(path.join(dir,'scripts','lease_resume_check.py'),
      'import sys,runpy\nsys.path.insert(0,'+JSON.stringify(scriptRoot)+')\nrunpy.run_path('
      +JSON.stringify(path.join(scriptRoot,'lease_resume_check.py'))+",run_name='__main__')\n");
    fs.mkdirSync(path.join(dir,'__patch_drop__'));
    for(const name of ['source_edit_session.ps1','source_edit_lease_contract.ps1'])
      fs.copyFileSync(path.join(__dirname,'..','__patch_drop__',name),path.join(dir,'__patch_drop__',name));
    fs.appendFileSync(path.join(dir,'__patch_drop__','source_edit_lease_contract.ps1'),'\nfunction Get-CimInstance { @() }\n');
    assert.equal(spawnSync('git',['init','--quiet',dir]).status,0);
    const target=path.join(dir,'target.txt'),manifest=path.join(dir,'targets.json');
    const targetBefore=fs.readFileSync(target);
    fs.writeFileSync(manifest,JSON.stringify({targets:[{path:'target.txt',sha256:require('node:crypto')
      .createHash('sha256').update(fs.readFileSync(target)).digest('hex')}]}));
    const {patch,...resume}=fixture;
    if(mode==='wait-release') {
      foreignOptions={...options,root:dir,manifest,topic:'peer-wait',owner:'peer-owner',task:'peer-task'};
      const runPeer=action=>spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',
        command(foreignOptions,action,action==='end'?foreignReceipt.fingerprint:undefined)],
        {cwd:dir,encoding:'utf8',timeout:35000});
      const begun=runPeer('begin');assert.equal(begun.status,0,begun.stderr);
      foreignReceipt=JSON.parse(begun.stdout.trim());
      const waitCode="import sys,json,time; from pathlib import Path; sys.path.insert(0,sys.argv[1]); "
        +"import codex_auto_unblock as c; root=Path(sys.argv[2]); "
        +"kw=dict(enqueue=True,task='resume-task',root=root,authoritative=False,heartbeat_fn=lambda:None,"
        +"wait_dir=root/'var/wait',wait_id='w1',context={'goalRevision':'g1','planRevision':1,'inputPaths':['config.json','check.py']})\n"
        +"for i in range(2):\n clock=[0.0]\n r=c.lease_wait(['target.txt'],root/'__patch_drop__/source-edit-locks',max_min=20,interval=10,"
        +"now_fn=lambda:clock[0],sleep_fn=lambda s:clock.__setitem__(0,clock[0]+s),**kw)\n"
        +" print(json.dumps({'round':i+1,'state':r['waitState'],'result':r['result'],'reason':r.get('reason'),'ownsTargets':r['ownsTargets']}),flush=True)\n"
        +"r=c.lease_wait(['target.txt'],root/'__patch_drop__/source-edit-locks',max_min=.1,interval=.05,**kw)\n"
        +"print(json.dumps({'final':r['result'],'state':r['waitState'],'beforeSha256':r.get('beforeSha256'),'ownsTargets':r['ownsTargets']}),flush=True)\n";
      waitProcess=spawn('python',['-B','-u','-c',waitCode,scriptRoot,dir],{cwd:dir});
      let waitOutput='',waitErrors='';
      const ready=new Promise((resolve,reject)=>{
        const deadline=setTimeout(()=>reject(new Error('wait fixture readiness deadline')),12000);
        waitProcess.stdout.on('data',chunk=>{
          waitOutput+=chunk.toString();
          if(waitOutput.includes('"round": 2')) {clearTimeout(deadline);resolve();}
        });
        waitProcess.on('error',error=>{clearTimeout(deadline);reject(error);});
        waitProcess.on('exit',code=>{if(!waitOutput.includes('"round": 2')) {
          clearTimeout(deadline);reject(new Error('wait fixture exited '+code+' '+waitErrors));}});
      });
      waitProcess.stderr.on('data',chunk=>{waitErrors+=chunk.toString();});
      const finished=new Promise(resolve=>waitProcess.once('close',resolve));
      await ready;
      const ended=runPeer('end');assert.equal(ended.status,0,ended.stderr);
      assert.equal(await finished,0,waitErrors);
      const observations=waitOutput.trim().split(/\r?\n/).map(line=>JSON.parse(line));
      assert.deepEqual(observations.slice(0,2).map(row=>row.state),['WAITING','WAITING'],JSON.stringify(observations));
      assert.equal(observations[2].final,'free');assert.equal(observations[2].state,'READY_TO_ACQUIRE');
      assert.equal(observations[2].beforeSha256,resume.beforeSha256);
      assert.equal(observations.every(row=>row.ownsTargets===false),true);
    }
    if(mode==='manifest-drift') fs.writeFileSync(target,'foreign before acquire\n');
    if(mode==='rename-path' || mode==='recreate-path') {
      fs.renameSync(target,path.join(dir,'moved-target.txt'));
      if(mode==='recreate-path') fs.writeFileSync(target,targetBefore);
    }
    const result=await guardedSourceEdit({exec_command:async args=>{
      const r=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',args.cmd],
        {cwd:dir,encoding:'utf8',timeout:35000});
      assert.ifError(r.error);
      if(mode==='acquire-drift' && args.cmd.includes(' -Action begin ') && r.status===0)
        fs.writeFileSync(path.join(dir,'config.json'),'{"peer":true}\n');
      if(mode==='config-drift' && args.cmd.includes(' -Action verify ') && r.status===0)
        fs.writeFileSync(path.join(dir,'config.json'),'{"peer":true}\n');
      return {exit_code:r.status,output:(r.stdout||'')+(r.stderr||'')};
    },apply_patch:async()=>{edits++;fs.writeFileSync(target,'fixed\n');return {exit_code:0}}},
      {...options,root:dir,manifest,task:'resume-task',patch,waited:true,resume});
    assert.equal(result.released,!['manifest-drift','rename-path'].includes(mode),JSON.stringify(result));
    assert.equal(fs.existsSync(path.join(dir,'__patch_drop__','source-edit-locks','fixture.lock')),false);
    if(mode==='apply' || mode==='wait-release') {
      assert.equal(result.status,'applied',JSON.stringify(result));assert.equal(edits,1);
    } else if(mode==='already-done') {
      assert.equal(result.status,'skipped-already-done',JSON.stringify(result));assert.equal(edits,0);
    } else if(mode==='manifest-drift') {
      assert.notEqual(result.beginExitCode,0,JSON.stringify(result));assert.equal(edits,0);
      assert.equal(fs.readFileSync(target,'utf8'),'foreign before acquire\n');
    } else if(mode==='rename-path' || mode==='recreate-path') {
      assert.equal(edits,0,JSON.stringify(result));
      assert.deepEqual(fs.readFileSync(path.join(dir,'moved-target.txt')),targetBefore);
      if(mode==='rename-path') {
        assert.notEqual(result.beginExitCode,0,JSON.stringify(result));assert.equal(fs.existsSync(target),false);
      } else {
        assert.equal(result.reason,'path-reevaluation-required',JSON.stringify(result));
        assert.deepEqual(fs.readFileSync(target),targetBefore);
      }
    } else if(mode==='acquire-drift') {
      assert.equal(result.reason,'decision-input-mismatch',JSON.stringify(result));assert.equal(edits,0);
      assert.deepEqual(fs.readFileSync(target),targetBefore);
    } else {
      assert.equal(result.reason,'input-changed-before-mutation',JSON.stringify(result));assert.equal(edits,0);
      assert.deepEqual(fs.readFileSync(target),targetBefore);
    }
  } finally {
    if(waitProcess && waitProcess.exitCode===null) waitProcess.kill();
    if(foreignReceipt && fs.existsSync(path.join(dir,'__patch_drop__','source-edit-locks','peer-wait.lock')))
      spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',
        command(foreignOptions,'end',foreignReceipt.fingerprint)],{cwd:dir,encoding:'utf8',timeout:35000});
    fs.rmSync(dir,{recursive:true,force:true});
  }
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

for (const code of [0,3,6,124,undefined]) test(`caller-bound renewal before write exit ${code}`,async()=>{
  let now=0,edits=0,heartbeats=0;
  const r=await guardedSourceEdit({exec_command:async args=>{
    if(args.cmd.includes(' -Action verify ')){now=100;return {exit_code:0,output:''};}
    if(args.cmd.includes(' -Action heartbeat ')){heartbeats++;assert.match(args.cmd,/ -LeaseFingerprint /);return {exit_code:code,output:''};}
    return {exit_code:0,output:args.cmd.includes(' -Action begin ')?receipt:''};
  },apply_patch:async()=>{edits++;return {exit_code:0};}}, {...options,renewIntervalMs:10,now:()=>now});
  assert.equal(heartbeats,1);assert.equal(edits,code===0?1:0);assert.equal(r.released,true);
});
test('abort after strict verify prevents the edit and releases bound generation',async()=>{
  const controller=new AbortController();let edits=0;
  const r=await guardedSourceEdit({exec_command:async args=>{
    if(args.cmd.includes(' -Action verify ')) controller.abort();
    return {exit_code:0,output:args.cmd.includes(' -Action begin ')?receipt:''};
  },apply_patch:async()=>{edits++;return {exit_code:0};}}, {...options,signal:controller.signal});
  assert.equal(edits,0);assert.equal(r.reason,'caller-cancelled');assert.equal(r.released,true);
});

test('heartbeat loss while edit runs preserves edit fact and reports lifecycle failure', async () => {
  let edits=0, renewed;
  const renewal = new Promise(resolve => {renewed=resolve;});
  const mock = {
    exec_command: async ({cmd}) => {
      if(cmd.includes('-Action heartbeat')) {renewed();return {exit_code:3,output:''};}
      return {exit_code:0,output:cmd.includes('-Action begin') ? receipt : ''};
    },
    apply_patch: async () => {edits++;await Promise.race([renewal,new Promise(resolve=>setTimeout(resolve,200))]);return {};}
  };
  const result=await guardedSourceEdit(mock,{...options,renewIntervalMs:10});
  assert.equal(edits,1); assert.equal(result.editCompleted,true);
  assert.equal(result.status,'hold'); assert.equal(result.renewExitCode,3);assert.equal(result.released,true);
});
test('renewal success cannot bypass a changed strict preimage', async () => {
  let now=0, verifies=0, edits=0;
  const mock={exec_command:async ({cmd})=>{
    if(cmd.includes('-Action verify')) {now=100;return {exit_code:++verifies===1?0:7,output:''};}
    return {exit_code:0,output:cmd.includes('-Action begin')?receipt:''};
  },apply_patch:async()=>{edits++;}};
  const result=await guardedSourceEdit(mock,{...options,renewIntervalMs:10,now:()=>now});
  assert.equal(verifies,2);assert.equal(edits,0);assert.equal(result.released,true);
});
test('cancel before acquisition starts no command or timer',async()=>{
  const controller=new AbortController();controller.abort();let commands=0;
  const result=await guardedSourceEdit({exec_command:async()=>{commands++;}},{...options,signal:controller.signal});
  assert.equal(commands,0);assert.equal(result.reason,'caller-cancelled');
});
