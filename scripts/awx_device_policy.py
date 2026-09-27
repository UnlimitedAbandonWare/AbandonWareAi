"""Device facts and measured routing policy. No registration or startup on import."""
from __future__ import annotations

import ctypes
import math
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import subprocess
import time

try:
    from .awx_host_runtime import host_facts
    from .awx_shared_state import Conflict
except ImportError:
    from awx_host_runtime import host_facts
    from awx_shared_state import Conflict

ROLES = {'desktop', 'notebook', 'macmini'}
KINDS = {'directive', 'design', 'research', 'light-edit', 'verify', 'build', 'test',
         'integration', 'rag', 'orchestration', 'arm-native', 'server', 'embedding',
         'preprocess', 'automation'}
SNAPSHOT_SECONDS = 300


def require(condition, reason):
    if not condition:
        raise Conflict(reason)


def identifier(value):
    require(isinstance(value, str) and re.fullmatch(r'[a-z0-9][a-z0-9_.-]{0,95}', value), 'invalid-id')
    require(value not in {'con', 'prn', 'aux', 'nul'} and not re.fullmatch(r'(com|lpt)[0-9]', value), 'reserved-id')
    return value


def number(value, low, high, optional=False):
    if value is None and optional:
        return value
    require(type(value) in (int, float) and math.isfinite(value) and low <= value <= high, 'invalid-metric')
    return value


def validate_requirements(req):
    require(isinstance(req, dict) and not (set(req)-{'cpuCount','memoryMb','gpuBackend','gpuMemoryMb','system','arch'}), 'invalid-requirements')
    for key in ('cpuCount','memoryMb','gpuMemoryMb'):
        if key in req: number(req[key], 1, 100000000)
    if 'gpuBackend' in req: require(req['gpuBackend'] in {'cuda','metal','rocm'}, 'invalid-gpu-backend')
    if 'system' in req: require(req['system'] in {'Windows','Darwin','Linux'}, 'invalid-system')
    if 'arch' in req: require(req['arch'] in {'x86_64','arm64'}, 'invalid-arch')
    require('gpuMemoryMb' not in req or 'gpuBackend' in req, 'gpu-backend-required')
    return req


def validate_device(row):
    allowed = {'deviceId','role','system','arch','cpuCount','memoryAvailableMb','cpuLoad',
               'gpuBackends','gpuMemoryAvailableMb','gpuLoad','observedAt','maxWorkers','probeReason'}
    require(isinstance(row,dict) and not (set(row)-allowed), 'invalid-device-fields')
    identifier(row.get('deviceId'))
    require(row.get('role') in ROLES, 'invalid-device-role')
    require(row.get('system') in {'Windows','Darwin','Linux'}, 'invalid-system')
    require(row.get('arch') in {'x86_64','arm64','unknown'}, 'invalid-arch')
    for key,low,high in [('cpuCount',1,65536),('memoryAvailableMb',0,100000000),('cpuLoad',0,1),
                         ('gpuMemoryAvailableMb',0,100000000),('gpuLoad',0,1)]:
        number(row.get(key), low, high, optional=True)
    number(row.get('observedAt'), 1, 1e12)
    require(type(row.get('maxWorkers')) is int and 1 <= row['maxWorkers'] <= 16, 'invalid-worker-capacity')
    require(isinstance(row.get('gpuBackends'),list) and set(row['gpuBackends']) <= {'cuda','metal','rocm'}, 'invalid-gpu-backend')
    if 'probeReason' in row: identifier(row['probeReason'])
    return row


def _output(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=5, check=False)
        return result.stdout.strip() if result.returncode == 0 else ''
    except (OSError, subprocess.TimeoutExpired):
        return ''


def probe(root: Path, role=None):
    facts = host_facts(root)
    role = role or {'macbook':'macmini','posix':'macmini'}.get(facts['role'], facts['role'])
    require(role in ROLES, 'invalid-device-role')
    arch = {'amd64':'x86_64','x86_64':'x86_64','aarch64':'arm64','arm64':'arm64'}.get(platform.machine().lower(),'unknown')
    row = dict(deviceId='host-'+facts['hostId'].rsplit('-',1)[-1], role=role, system=platform.system(),
               arch=arch, cpuCount=os.cpu_count(), memoryAvailableMb=None, cpuLoad=None,
               gpuBackends=[], gpuMemoryAvailableMb=None, gpuLoad=None, observedAt=time.time(),
               maxWorkers=2 if role=='desktop' else 1, probeReason='partial-observation')
    try:
        if os.name == 'nt':
            class Memory(ctypes.Structure):
                _fields_ = [('length',ctypes.c_ulong),('load',ctypes.c_ulong)] + [(k,ctypes.c_ulonglong) for k in
                    ('totalPhys','availPhys','totalPage','availPage','totalVirtual','availVirtual','extended')]
            mem=Memory(); mem.length=ctypes.sizeof(mem)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(mem)):
                row['memoryAvailableMb']=mem.availPhys//(1024*1024)
            def cpu():
                values=[ctypes.c_ulonglong() for _ in range(3)]
                if not ctypes.windll.kernel32.GetSystemTimes(*(ctypes.byref(x) for x in values)):
                    raise OSError()
                return [x.value for x in values]
            before=cpu(); time.sleep(.1); after=cpu()
            idle,kernel,user=[b-a for a,b in zip(before,after)]
            if kernel+user>0: row['cpuLoad']=max(0,min(1,1-idle/(kernel+user)))
        else:
            row['cpuLoad']=min(1, os.getloadavg()[0]/max(1,os.cpu_count() or 1))
            if platform.system()=='Darwin':
                report=_output(['vm_stat'])
                page=re.search(r'page size of (\d+) bytes',report)
                counts=[re.search(r'Pages '+name+r':\s+(\d+)',report) for name in ['free','inactive','speculative']]
                if page and all(counts): row['memoryAvailableMb']=int(page[1])*sum(int(m[1]) for m in counts)//(1024*1024)
            else:
                report=Path('/proc/meminfo').read_text()
                match=re.search(r'MemAvailable:\s+(\d+)',report)
                if match: row['memoryAvailableMb']=int(match[1])//1024
        executable=shutil.which('nvidia-smi')
        if executable:
            report=_output([executable,'--query-gpu=memory.free,utilization.gpu','--format=csv,noheader,nounits'])
            pairs=[tuple(float(v.strip()) for v in line.split(',')) for line in report.splitlines() if line]
            healthy=[p for p in pairs if len(p)==2 and all(math.isfinite(v) and v>=0 for v in p) and p[1]<=100]
            if healthy:
                memory,load=max(healthy,key=lambda p:p[0]*(1-p[1]/100))
                row.update(gpuBackends=['cuda'],gpuMemoryAvailableMb=memory,gpuLoad=load/100)
    except (OSError,ValueError,AttributeError):
        pass
    row['observedAt']=time.time()
    if row['cpuLoad'] is not None and row['memoryAvailableMb'] is not None: row['probeReason']='capacity-observed'
    return validate_device(row)


def _median(task, device_id, history, now):
    values=[]
    for row in sorted(history,key=lambda r:r.get('endedAt',0),reverse=True):
        if (row.get('deviceId')!=device_id or row.get('status')!='succeeded' or
            row.get('kind')!=task['kind'] or row.get('workloadKey')!=task['workloadKey'] or
            row.get('requirements',{})!=task.get('requirements',{}) or
            not 0 <= now-row.get('endedAt',0) <= 30*86400): continue
        elapsed=row.get('durationSeconds'); transfer=row.get('transferSeconds',0)
        if all(type(n) in (int,float) and math.isfinite(n) for n in [elapsed,transfer]) and elapsed>0 and transfer>=0:
            values.append(elapsed+transfer)
        if len(values)==30: break
    return statistics.median(values) if len(values)>=3 else None


def rank(task, devices, history, now=None, preferred=None):
    now=time.time() if now is None else now
    req=validate_requirements(task.get('requirements',{}))
    baseline=next((_median(task,d['deviceId'],history,now) for d in devices if d['role']=='desktop'),None)
    candidates=[]
    for d in devices:
        validate_device(d)
        reason=''; duration=_median(task,d['deviceId'],history,now)
        if not -5 <= now-d['observedAt'] <= SNAPSHOT_SECONDS: reason='stale-device'
        elif any(d.get(k) is None for k in ['cpuCount','memoryAvailableMb','cpuLoad']): reason='capacity-unobserved'
        elif d['cpuCount']<req.get('cpuCount',1) or d['memoryAvailableMb']<req.get('memoryMb',256): reason='insufficient-capacity'
        elif d['cpuLoad']>=.95: reason='device-busy'
        elif req.get('system',d['system'])!=d['system'] or req.get('arch',d['arch'])!=d['arch']: reason='platform-mismatch'
        elif task['kind']=='integration' and d['role']!='desktop': reason='desktop-final-owner-required'
        elif task['kind']=='light-edit' and d['role']=='macmini': reason='mac-source-worktree-required'
        elif req.get('gpuBackend') and req['gpuBackend'] not in d['gpuBackends']: reason='gpu-unobserved'
        elif req.get('gpuMemoryMb') and (d.get('gpuMemoryAvailableMb') is None or d['gpuMemoryAvailableMb']<req['gpuMemoryMb']): reason='gpu-memory-unobserved-or-insufficient'
        elif req.get('gpuBackend') and (d.get('gpuLoad') is None or d['gpuLoad']>=.95): reason='gpu-load-unavailable-or-busy'
        elif (d['role']=='macmini' and preferred!=d['deviceId'] and req.get('arch')!='arm64' and task['kind']!='arm-native'
              and (duration is None or baseline is None or duration>baseline*.9)): reason='mac-comparison-evidence-needed'
        elif task['kind']=='arm-native' and d['arch']!='arm64': reason='arm-required'
        primary='notebook' if task['kind'] in {'directive','design','research','light-edit'} else 'desktop'
        score=0 if reason else (40 if d['role']==primary else 0)-d['cpuLoad']*60+math.log2(d['cpuCount']+1)+min(d['memoryAvailableMb']/4096,8)
        if not reason and duration and baseline: score+=min(200,100*baseline/duration)
        candidates.append(dict(deviceId=d['deviceId'],eligible=not reason,reasonCode=reason or 'eligible',
                               score=round(score,3),medianSeconds=duration))
    eligible=[c for c in candidates if c['eligible'] and (preferred is None or c['deviceId']==preferred)]
    eligible.sort(key=lambda c:(-c['score'],c['deviceId']))
    return dict(selectedDevice=eligible[0]['deviceId'] if eligible else None,candidates=candidates,
                reasonCode='assigned' if eligible else 'device-evidence-needed',policy='measured-elapsed-v1')
