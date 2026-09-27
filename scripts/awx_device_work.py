"""On-demand cooperative work queue over the existing shared handoff directory.

No source edits, arbitrary command execution, scheduler or lease replacement.
Unknown locks/claims are retained. Cooperating agents execute returned references.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import unicodedata
import uuid

try:
    from .awx_shared_state import Conflict, plain_path, digest, parse_config
    from .awx_device_policy import KINDS, identifier, number, probe, rank, require, validate_device, validate_requirements
except ImportError:
    from awx_shared_state import Conflict, plain_path, digest, parse_config
    from awx_device_policy import KINDS, identifier, number, probe, rank, require, validate_device, validate_requirements

TERMINAL={'succeeded','failed','cancelled'}


def encoded(value):
    return (json.dumps(value,sort_keys=True,ensure_ascii=True,allow_nan=False,indent=2)+'\n').encode()


def relative(value):
    require(isinstance(value,str) and 0<len(value)<=240, 'invalid-relative-path')
    value=unicodedata.normalize('NFC',value.replace('\\','/'))
    parts=value.split('/')
    require(all(p and p not in {'.','..'} and not p.endswith((' ','.')) for p in parts), 'invalid-relative-path')
    require(not re.search(r'[:*?<>|\x00-\x1f]',value) and not value.startswith('/'), 'invalid-relative-path')
    for p in parts:
        require(not re.fullmatch(r'(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\..*)?',p), 'reserved-path')
        require(p.lower() not in {'.git','.codex','.secrets'} and not p.lower().startswith('.env'), 'protected-path')
    require(Path(value).suffix.lower() not in {'.key','.pem','.pfx','.p12','.jks'}, 'protected-path')
    return value


class WorkQueue:
    def __init__(self, root):
        self.root=plain_path(Path(root))
        self.base=plain_path(self.root/'data/agent-handoff/device-work')

    def _path(self, value):
        return plain_path(self.root/relative(value))

    def _read(self, path):
        path=plain_path(path)
        require(path.is_file() and path.stat().st_size<=2*1024*1024, 'missing-or-oversize-record')
        return parse_config(path.read_bytes(),'json')

    def _write(self, path, value):
        path=plain_path(path); path.parent.mkdir(parents=True,exist_ok=True)
        temp=plain_path(path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp'))
        try:
            with temp.open('xb') as stream:
                stream.write(encoded(value)); stream.flush(); os.fsync(stream.fileno())
            os.replace(temp,path)
        finally:
            if temp.exists(): temp.unlink()

    @contextmanager
    def _operation(self, directory):
        directory=plain_path(directory); directory.mkdir(parents=True,exist_ok=True)
        lock=plain_path(directory/'.operation')
        try: lock.mkdir()
        except FileExistsError: raise Conflict('task-operation-busy-or-interrupted') from None
        try: yield
        finally:
            # Remove only our empty operation marker. Unknown contents are retained.
            lock.rmdir()

    def _taskdir(self, task_id):
        return plain_path(self.base/'tasks'/identifier(task_id))

    def _load(self, task_id):
        directory=self._taskdir(task_id)
        spec,state=self._read(directory/'spec.json'),self._read(directory/'state.json')
        require(state.get('taskId')==task_id and spec.get('taskId')==task_id, 'task-identity-changed')
        require(state.get('recordHash')==digest(encoded(spec)), 'task-spec-integrity-failed')
        return spec,state

    def _hash(self, name):
        path=self._path(name)
        require(not path.is_dir(),'file-target-required')
        if not path.exists(): return None
        h=hashlib.sha256()
        with path.open('rb') as stream:
            for block in iter(lambda:stream.read(1024*1024),b''): h.update(block)
        return h.hexdigest()

    def publish(self, row):
        row=validate_device(dict(row))
        with self._operation(self.base/'devices'/row['deviceId']):
            self._write(self.base/'devices'/row['deviceId']/'snapshot.json',row)
        return row

    def _devices(self):
        return [validate_device(self._read(p)) for p in sorted((self.base/'devices').glob('*/snapshot.json'))]

    def enqueue(self, value):
        allowed={'taskId','kind','workloadKey','targets','context','outputs','dependsOn','requirements','resources','timeoutSeconds'}
        require(isinstance(value,dict) and not(set(value)-allowed),'invalid-task-fields')
        task=dict(value); identifier(task.get('taskId')); identifier(task.get('workloadKey'))
        require(task.get('kind') in KINDS,'invalid-task-kind')
        task['requirements']=validate_requirements(task.get('requirements',{}))
        task.setdefault('timeoutSeconds',1800); number(task['timeoutSeconds'],1,14400)
        for key in ['targets','context','outputs','dependsOn','resources']:
            items=task.setdefault(key,[])
            require(isinstance(items,list) and len(items)<=256,'invalid-task-list')
            task[key]=[(relative(x) if key in {'targets','context','outputs'} else identifier(x)) for x in items]
            require(len({x.casefold() for x in task[key]})==len(items),'duplicate-path-or-id')
        require(task['taskId'] not in task['dependsOn'],'self-dependency')
        if task['kind']=='light-edit': require(task['targets'],'source-targets-required')
        for dep in task['dependsOn']: self._load(dep)
        for name in task['targets']+task['context']:
            in_queue=name.casefold().startswith('data/agent-handoff/device-work/')
            artifact_context=(name in task['context'] and
                re.fullmatch(r'data/agent-handoff/device-work/tasks/[a-z0-9][a-z0-9_.-]{0,95}/artifacts/.+',name))
            require((not in_queue or artifact_context) and not name.casefold().startswith('__patch_drop__/source-edit-locks/'),'queue-or-lease-target-forbidden')
            self._path(name)
        task['contextRefs']=[{'path':p,'sha256':self._hash(p)} for p in task.pop('context')]
        require(all(r['sha256'] for r in task['contextRefs']),'missing-context')
        spec_hash=digest(encoded(task))
        directory=self._taskdir(task['taskId'])
        with self._operation(directory):
            if (directory/'spec.json').exists():
                old,state=self._load(task['taskId'])
                require(state['specHash']==spec_hash,'task-id-spec-conflict')
                return state
            require(not (directory/'state.json').exists(),'incomplete-task-record')
            task['targetRefs']=[{'path':p,'sha256':self._hash(p)} for p in task.pop('targets')]
            task['outputRefs']=[{'path':str((directory/'artifacts'/p).relative_to(self.root)).replace('\\','/'),
                                'sha256':None} for p in task.pop('outputs')]
            for row in task['outputRefs']:
                require(self._hash(row['path']) is None,'output-already-exists')
                self._path(row['path']).parent.mkdir(parents=True,exist_ok=True)
            self._write(directory/'targets.json',{'targets':task['targetRefs']})
            self._write(directory/'spec.json',task)
            state=dict(taskId=task['taskId'],specHash=spec_hash,recordHash=digest(encoded(task)),status='queued',assignedDevice=None,
                       createdAt=time.time(),startedAt=None,endedAt=None,changedFiles=[],resourcesHeld=[],reasonCode='queued')
            self._write(directory/'state.json',state)
            return state

    def history(self):
        history=[]
        for path in sorted((self.base/'tasks').glob('*/state.json')):
            spec,state=self._load(path.parent.name)
            if state['status']=='succeeded':
                history.append(dict(deviceId=state['assignedDevice'],kind=spec['kind'],workloadKey=spec['workloadKey'],
                    requirements=spec['requirements'],status=state['status'],durationSeconds=state['durationSeconds'],
                    transferSeconds=state.get('transferSeconds',0),endedAt=state['endedAt']))
        return history

    def route(self, task_id, preferred=None):
        spec,state=self._load(task_id)
        devices=self._devices()
        available=[]; busy=[]
        for device in devices:
            slots=any(not self._reservation_path('slot:'+device['deviceId']+':'+str(i)).exists()
                      for i in range(device['maxWorkers']))
            keys=self._resource_keys(spec,device['deviceId'])
            free=all(not self._reservation_path('resource:'+key).exists() for key in keys)
            if slots and free: available.append(device)
            else: busy.append(dict(deviceId=device['deviceId'],eligible=False,reasonCode='resource-or-capacity-busy',score=0,medianSeconds=None))
        # Busy hosts still supply the measured baseline; only admission excludes them.
        result=rank(spec,devices,self.history(),preferred=preferred)
        blocked={row['deviceId']:row for row in busy}
        result['candidates']=[blocked.get(row['deviceId'],row) for row in result['candidates']]
        eligible=[r for r in result['candidates'] if r['eligible'] and (preferred is None or r['deviceId']==preferred)]
        eligible.sort(key=lambda row:(-row['score'],row['deviceId']))
        result.update(selectedDevice=eligible[0]['deviceId'] if eligible else None,
                      reasonCode='assigned' if eligible else 'device-evidence-needed')
        if state['status']!='queued': result.update(selectedDevice=None,reasonCode='task-already-owned-or-terminal')
        if any(self._load(d)[1]['status']!='succeeded' for d in spec['dependsOn']):
            result.update(selectedDevice=None,reasonCode='dependency-not-succeeded')
        return result

    def _resource_keys(self, spec, device_id):
        keys=list(spec['resources'])
        if spec['kind'] in {'build','test','integration','rag','orchestration'}:
            keys.append('heavy-'+device_id)
        return sorted(set(keys))

    def _reservation_path(self, key):
        return plain_path(self.base/'reservations'/(digest(key.encode())+'.json'))

    def _reserve(self, key, task_id):
        path=self._reservation_path(key)
        path.parent.mkdir(parents=True,exist_ok=True)
        try:
            with path.open('xb') as stream:
                stream.write(encoded({'taskId':task_id})); stream.flush(); os.fsync(stream.fileno())
        except FileExistsError: raise Conflict('resource-or-capacity-busy') from None
        return path.name

    def _release(self, task_id, names):
        for name in names:
            require(re.fullmatch(r'[a-f0-9]{64}\.json',name),'invalid-reservation')
            path=plain_path(self.base/'reservations'/name)
            if path.exists():
                require(path.read_bytes()==encoded({'taskId':task_id}),'reservation-owner-changed')
                path.unlink()

    def claim(self, task_id, device_id):
        identifier(device_id)
        with self._operation(self._taskdir(task_id)):
            spec,state=self._load(task_id)
            require(state['status']=='queued','task-already-owned-or-terminal')
            decision=self.route(task_id,preferred=device_id)
            require(decision['selectedDevice']==device_id,decision['reasonCode'])
            device=next(d for d in self._devices() if d['deviceId']==device_id)
            held=[]; publication_started=False
            try:
                for slot in range(device['maxWorkers']):
                    try: held.append(self._reserve('slot:'+device_id+':'+str(slot),task_id)); break
                    except Conflict: continue
                require(held,'device-capacity-busy')
                for key in self._resource_keys(spec,device_id): held.append(self._reserve('resource:'+key,task_id))
                state.update(status='claimed',assignedDevice=device_id,claimedAt=time.time(),resourcesHeld=held,
                             reasonCode='claimed',routeDecision=decision)
                publication_started=True
                self._write(self._taskdir(task_id)/'state.json',state)
            except BaseException:
                if not publication_started: self._release(task_id,held)
                raise
            return self.packet(spec,state)

    def packet(self, spec, state):
        return dict(state,kind=spec['kind'],targets=spec['targetRefs'],context=spec['contextRefs'],outputs=spec['outputRefs'],
                    targetManifest=str((self._taskdir(spec['taskId'])/'targets.json').relative_to(self.root)).replace('\\','/'),
                    sourceWorkflow='existing-source-owner-guard' if spec['kind']=='light-edit' else 'read-or-artifact-only',
                    completionEvidence='caller-observed',automaticCommandExecution=False)

    def _check_inputs(self, spec, targets=True):
        rows=spec['contextRefs']+(spec['targetRefs'] if targets else [])
        require(all(self._hash(r['path'])==r['sha256'] for r in rows),'task-input-preimage-changed')

    def _ps(self, script, env):
        require(os.name=='nt','source-guard-windows-required')
        child={**os.environ,**env}; child.pop('PSModulePath',None)
        bootstrap="$ErrorActionPreference='Stop'; Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Force; "
        result=subprocess.run(['powershell','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-Command',bootstrap+script],
                              env=child,capture_output=True,timeout=30)
        require(result.returncode==0,'existing-source-guard-rejected')

    def _verify_source_lease(self, lease, spec):
        require(isinstance(lease,str) and lease.startswith('__patch_drop__/source-edit-locks/') and lease.endswith('/lease.json'), 'source-owner-lease-required')
        path=self._path(lease); row=self._read(path)
        require(isinstance(row.get('root'),str) and plain_path(Path(row['root']))==self.root,'source-lease-root-mismatch')
        require(row.get('mutationAllowed') is True and row.get('coordinationMode')=='target-scoped','source-lease-invalid')
        require({r['path'].casefold() for r in spec['targetRefs']}=={p.replace('\\','/').casefold() for p in row.get('targetPaths',[])}, 'source-lease-scope-mismatch')
        env={'AWX_WORK_ROOT':str(self.root),'AWX_WORK_TOPIC':row['topic'],'AWX_WORK_MANIFEST':str(self._taskdir(spec['taskId'])/'targets.json'),
             'AWX_WORK_FINGERPRINT':digest(path.read_bytes())}
        if row.get('role')=='notebook':
            require(str(self.root).replace('\\','/').lower()=='y:/','notebook-canonical-root-required')
            require(os.environ.get('AWX_SOURCE_EDIT_OWNER'),'notebook-owner-evidence-needed')
            run_id=row['topic'].removeprefix('macsrc-'); identifier(run_id)
            env['AWX_WORK_RUN']=run_id
            self._ps("& (Join-Path $env:AWX_WORK_ROOT '.agents/skills/demo1-macsrc-smb-direct-patch/scripts/macsrc_smb_patch_guard.ps1') -Mode Verify -Root $env:AWX_WORK_ROOT -RunId $env:AWX_WORK_RUN -OwnerId $env:AWX_SOURCE_EDIT_OWNER; exit $LASTEXITCODE",env)
        else:
            require(row.get('role') in {'desktop','desktop-consumer'},'source-owner-role-invalid')
            env['AWX_WORK_OWNER']=row['ownerId']
            self._ps("& (Join-Path $env:AWX_WORK_ROOT '__patch_drop__/source_edit_session.ps1') -Action verify -Root $env:AWX_WORK_ROOT -Topic $env:AWX_WORK_TOPIC -OwnerId $env:AWX_WORK_OWNER -TargetManifest $env:AWX_WORK_MANIFEST -LeaseFingerprint $env:AWX_WORK_FINGERPRINT; exit $LASTEXITCODE",env)
        return dict(path=lease,sha256=digest(path.read_bytes()))

    def _lease_alive(self, ref):
        path=self._path(ref['path'])
        if not path.is_file() or digest(path.read_bytes())!=ref['sha256']: return False
        lease=self._read(path)
        expiry=datetime.fromisoformat(lease['expiresAtUtc'].replace('Z','+00:00'))
        return expiry>datetime.now(timezone.utc)

    def start(self, task_id, device_id, lease=None):
        with self._operation(self._taskdir(task_id)):
            spec,state=self._load(task_id)
            require(state['status']=='claimed' and state['assignedDevice']==device_id,'task-owner-or-state-mismatch')
            self._check_inputs(spec)
            if spec['kind']=='light-edit': state['sourceLease']=self._verify_source_lease(lease,spec)
            state.update(status='running',startedAt=time.time(),reasonCode='running')
            self._write(self._taskdir(task_id)/'state.json',state)
            return self.packet(spec,state)

    def complete(self, task_id, device_id, outcome, transfer_seconds=0):
        require(outcome in TERMINAL,'invalid-outcome'); number(transfer_seconds,0,86400)
        with self._operation(self._taskdir(task_id)):
            spec,state=self._load(task_id)
            require(state['assignedDevice']==device_id,'task-owner-mismatch')
            if state['status'] in TERMINAL:
                require(state['status']==outcome,'terminal-outcome-conflict')
                self._release(task_id,state['resourcesHeld'])
                return state
            require(state['status']=='running' or outcome in {'cancelled','failed'} and state['status']=='claimed','task-state-mismatch')
            refs=spec['targetRefs']+spec['outputRefs']
            post=[dict(path=r['path'],sha256=self._hash(r['path'])) for r in refs]
            changed=[r['path'] for before,r in zip(refs,post) if before['sha256']!=r['sha256']]
            changed_inputs=[r['path'] for before,r in zip(spec['targetRefs'],post) if before['sha256']!=r['sha256']]
            reason=outcome
            if spec['kind']=='light-edit' and state.get('sourceLease'):
                require(self._lease_alive(state['sourceLease']),'source-lease-no-longer-current')
            elif changed_inputs: outcome='failed'; reason='read-input-drift'
            if outcome=='succeeded' and any(self._hash(r['path']) is None for r in spec['outputRefs']):
                outcome='failed'; reason='declared-output-missing'
            if any(self._hash(r['path'])!=r['sha256'] for r in spec['contextRefs']): outcome='failed'; reason='context-input-drift'
            ended=time.time(); elapsed=max(0,ended-(state.get('startedAt') or ended))
            if elapsed>spec['timeoutSeconds']: outcome='failed'; reason='task-deadline-exceeded'
            state.update(status=outcome,endedAt=ended,durationSeconds=elapsed,transferSeconds=transfer_seconds,
                         changedFiles=changed,postimages=post,reasonCode=reason)
            self._write(self._taskdir(task_id)/'state.json',state)
            self._release(task_id,state['resourcesHeld'])
            return state

    def status(self):
        rows=[]
        for path in sorted((self.base/'tasks').glob('*/spec.json')):
            spec,state=self._load(path.parent.name)
            state['needsReconciliation']=bool(state['status'] in {'claimed','running'} and
                time.time()-(state.get('startedAt') or state['claimedAt'])>spec['timeoutSeconds'])
            state['operationInterrupted']=(path.parent/'.operation').exists()
            rows.append(state)
        return dict(tasks=rows,devices=self._devices(),physicalRemoteProof='not_observed')


def device_work(payload):
    allowed={'action','root','role','task','taskId','preferredDevice','lease','outcome','transferSeconds'}
    try:
        require(isinstance(payload,dict) and not(set(payload)-allowed),'invalid-work-envelope')
        queue=WorkQueue(payload.get('root') or Path(__file__).absolute().parents[1])
        action=payload.get('action','status')
        # Root identity is checked on the actual Notebook before shared metadata writes.
        if action not in {'status','route'} and str(queue.root).replace('\\','/').lower().startswith('y:'):
            require(str(queue.root).replace('\\','/').lower()=='y:/','notebook-canonical-root-required')
            queue._ps("$proof = & (Join-Path $env:AWX_WORK_ROOT 'scripts/verify_ydrive_backing_identity.ps1') -ExpectedSha256 '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9' | ConvertFrom-Json; if ($proof.backingShareIdentityVerified -ne $true) { exit 2 }; exit 0",{'AWX_WORK_ROOT':str(queue.root)})
        if action=='status': result=queue.status()
        elif action=='enqueue': result=queue.enqueue(payload['task'])
        elif action=='route': result=queue.route(payload['taskId'],payload.get('preferredDevice'))
        elif action in {'probe','claim','next','start','complete'}:
            current=probe(queue.root,payload.get('role'))
            if action in {'probe','claim','next'}: queue.publish(current)
            if action=='probe': result=current
            elif action=='claim': result=queue.claim(payload['taskId'],current['deviceId'])
            elif action=='next':
                result={'reasonCode':'no-eligible-work','selectedTask':None}
                for row in queue.status()['tasks']:
                    if row['status']=='queued' and queue.route(row['taskId'])['selectedDevice']==current['deviceId']:
                        try: result=queue.claim(row['taskId'],current['deviceId']); break
                        except Conflict: continue
            elif action=='start': result=queue.start(payload['taskId'],current['deviceId'],payload.get('lease'))
            else: result=queue.complete(payload['taskId'],current['deviceId'],payload['outcome'],payload.get('transferSeconds',0))
        else: raise Conflict('unknown-work-action')
        return {'ok':True,'schemaVersion':'awx.device-work.v1','action':action,'result':result,'sourceMutation':False}
    except (Conflict,OSError,ValueError,KeyError,TypeError,subprocess.TimeoutExpired) as error:
        return {'ok':False,'schemaVersion':'awx.device-work.v1','reasonCode':str(error) if isinstance(error,Conflict) else 'device-work-evidence-invalid-or-unavailable','sourceMutation':False,'repositoryWideHold':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-json',default='-',help='JSON input file or - for stdin')
    args=parser.parse_args()
    try:
        raw=sys.stdin.buffer.read() if args.input_json=='-' else Path(args.input_json).read_bytes()
        result=device_work(parse_config(raw,'json'))
    except (OSError,Conflict): result={'ok':False,'reasonCode':'invalid-input-json'}
    print(json.dumps(result,ensure_ascii=True,allow_nan=False))
    return 0 if result['ok'] else 2


if __name__=='__main__': raise SystemExit(main())
