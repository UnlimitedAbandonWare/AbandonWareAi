"""Host-local AWX paths; importing this module has no startup side effects."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath, PurePosixPath
import platform
import shutil
import socket
import subprocess
import sys

try:
    from scripts.awx_shared_state import Conflict, plain_path
except ModuleNotFoundError:
    from awx_shared_state import Conflict, plain_path


def host_facts(root, system=None, environ=None):
    system = system or platform.system()
    env = os.environ if environ is None else environ
    text = str(root).replace('\\','/')
    role = 'notebook' if text.lower().startswith('y:/') else ('desktop' if system=='Windows' else 'macbook' if system=='Darwin' else 'posix')
    if system=='Windows':
        home = PureWindowsPath(env.get('USERPROFILE',str(Path.home())))
        base = PureWindowsPath(env.get('LOCALAPPDATA',str(home/'AppData/Local')))/'AWX'
    else:
        home = PurePosixPath(env.get('HOME',str(Path.home())))
        base = home/'Library/Application Support/AWX' if system=='Darwin' else PurePosixPath(env.get('XDG_STATE_HOME',str(home/'.local/state')))/'AWX'
    identity = hashlib.sha256((system+'|'+socket.gethostname()).encode()).hexdigest()[:12]
    return {'system':system,'role':role,'hostId':role+'-'+identity,'stateBase':str(base),
            'shell':'ps1' if system=='Windows' else 'sh','python':sys.executable}


def local_state_root(root: Path, override=None):
    root = plain_path(root)  # Preserve canonical Y: spelling; never resolve it to UNC.
    facts = host_facts(root)
    key = hashlib.sha256(os.path.normcase(str(root)).encode()).hexdigest()[:16]
    base = Path(override) if override else Path(facts['stateBase'])/'workspaces'/key/facts['hostId']
    base = plain_path(base)
    if base == root or root in base.parents:
        raise Conflict('host-state-inside-source')
    text = str(base).replace('\\','/').lower()
    if text.startswith(('//','y:/','/volumes/','/mnt/','/media/')):
        raise Conflict('host-state-on-shared-storage')
    if os.name=='nt':
        import ctypes
        if ctypes.windll.kernel32.GetDriveTypeW(str(base.anchor))==4:
            raise Conflict('host-state-on-network-drive')
    return base


def child_environment(root: Path, state: Path):
    env = os.environ.copy()
    try:
        from scripts.awx_project_secrets import runtime_environment, environment, enabled, catalog, manual_values_active
        from scripts.awx_device_bus import registry_status
    except ModuleNotFoundError:
        from awx_project_secrets import runtime_environment, environment, enabled, catalog, manual_values_active
        from awx_device_bus import registry_status
    if enabled(root) and not manual_values_active(root):
        names = {n for ns in catalog(root)['providers'].values() for n in ns}
        env.update(environment(names))  # Newly launched children see current User values.
    env = runtime_environment(root, env)
    local_state_root(root,env.get('CODEX_HOME') or Path.home()/'.codex')
    env.update({'PYTHONDONTWRITEBYTECODE':'1','AWX_SPLIT_BUILD_OUTPUTS':'1',
                'AWX_BUILD_HOST_ID':host_facts(root)['hostId'],
                'GRADLE_USER_HOME':str(state/'gradle-user'),
                'AWX_LOCAL_STATE_ROOT':str(state),
                'AWX_SOURCE_ROOT':str(root),
                'AWX_CAPABILITY_REGISTRY_ROOT':str(root/'data/device-resources/registry'),
                'AWX_CAPABILITY_CONTEXT':json.dumps(registry_status(root), ensure_ascii=True),
                'RUNTIME_TOOLKIT_EVIDENCE_PATH':str(state/'toolkit-current.json'),
                'AWX_PROJECT_CACHE_DIR':str(state/'gradle-project')})
    return env


def runtime_command(root: Path, runtime: str, state: Path):
    if runtime=='gradle':
        return [str(root/('gradlew.bat' if os.name=='nt' else 'gradlew')),
                '--project-dir',str(root),'--project-cache-dir',str(state/'gradle-project'),
                '-Pawx.splitBuildOutputs=true','-Pawx.buildHostId='+host_facts(root)['hostId']]
    if runtime=='control-tower':
        return [sys.executable,str(root/'scripts/awx_mcp_stdio_server.py')]
    if runtime=='toolkit':
        return [sys.executable,'-B',str(root/'scripts/awx_mcp_toolbox.py'),'runtime']
    if runtime=='glm':
        java = os.environ.get('AWX_JAVA') or (str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')) if os.environ.get('JAVA_HOME') else shutil.which('java'))
        jar = os.environ.get('AWX_GLM_JAR')
        if not java or not Path(java).is_file():raise Conflict('java-runtime-missing')
        if not jar:
            candidates=list((state/'artifacts').glob('glm-agent-mcp*.jar'))
            if len(candidates)!=1:raise Conflict('local-glm-jar-evidence-needed')
            jar=str(candidates[0])
        jar_path=plain_path(Path(jar))
        local_state_root(root,jar_path.parent)
        if not jar_path.is_file():raise Conflict('glm-jar-missing')
        return [java,'-jar',str(jar_path),'--transport=stdio']
    raise Conflict('unknown-runtime')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('action',choices=['status','run'])
    parser.add_argument('--root',default=str(Path(__file__).absolute().parents[1]))
    parser.add_argument('--runtime',choices=['control-tower','glm','gradle','toolkit'],default='control-tower')
    parser.add_argument('--state-root')
    args, extra=parser.parse_known_args()
    if extra and args.runtime!='gradle':parser.error('extra arguments only allowed for gradle')
    try:
        root=plain_path(Path(args.root)); state=local_state_root(root,args.state_root)
        env=child_environment(root,state)
        if args.action=='status':
            print(json.dumps({'ok':True,**host_facts(root),'stateRoot':str(state),'sourceRootHash':hashlib.sha256(str(root).encode()).hexdigest(),'externalHostProof':'not_observed'}));return 0
        command=runtime_command(root,args.runtime,state)+extra
        state.mkdir(parents=True,exist_ok=True)
        return subprocess.call(command,cwd=state,env=env)
    except (Conflict,OSError) as error:
        print(json.dumps({'ok':False,'reason':str(error) if isinstance(error,Conflict) else 'runtime-os-error'}),file=sys.stderr)
        return 2


if __name__=='__main__':raise SystemExit(main())
