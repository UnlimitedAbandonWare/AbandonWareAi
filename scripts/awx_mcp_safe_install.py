"""Validate a complete producer kit and publish a reversible, no-clobber batch."""
from __future__ import annotations
import argparse
import json
from pathlib import Path, PurePosixPath
import re
import sys
import unicodedata

try:
    from scripts.awx_shared_state import Change, Conflict, apply_changes, digest, encode_config, merge_config, merge_three_way, parse_config, plain_path, read_optional
    from scripts.awx_host_runtime import local_state_root
    from scripts.awx_mcp_node_setup import source_isolation_evidence, DESKTOP_CANONICAL, render_config
except ModuleNotFoundError:
    from awx_shared_state import Change, Conflict, apply_changes, digest, encode_config, merge_config, merge_three_way, parse_config, plain_path, read_optional
    from awx_host_runtime import local_state_root
    from awx_mcp_node_setup import source_isolation_evidence, DESKTOP_CANONICAL, render_config


def safe_relative(value):
    if not isinstance(value,str) or not value or '\\' in value or ':' in value:
        raise Conflict('unsafe-kit-path')
    path=PurePosixPath(value)
    if path.is_absolute() or any(part in {'','.', '..'} for part in value.split('/')):
        raise Conflict('unsafe-kit-path')
    if any(part.rstrip(' .')!=part for part in path.parts):
        raise Conflict('unsafe-kit-path-alias')
    if any(re.match(r'(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)',part) or
           any(ord(char)<32 or char in '<>"|?*' for char in part) for part in path.parts):
        raise Conflict('unsafe-kit-path-alias')
    if value in {'INSTALL.macmini.sh','INSTALL.notebook.ps1','README.producer-kit.md'}:
        return path
    if value == 'config/project-resources.json':
        return path  # Names and declaration paths only; no other config or .secrets.
    if path.parts[0] not in {'scripts','.agents','agent-prompts','data','main','__patch_drop__','.codex'}:
        raise Conflict('kit-path-not-allowlisted')
    if path.parts[0]=='.codex' and value not in {'.codex/shared-runtime.json','.codex/hooks.json','.codex/hooks/source_edit_triage.py','.codex/hooks/source_edit_triage.ps1','.codex/hooks/source_edit_triage.sh'}:
        raise Conflict('host-config-cannot-be-installed-as-shared-asset')
    if path.parts[0]=='main' and not value.startswith('main/resources/mcp/'):
        raise Conflict('kit-path-not-allowlisted')
    if path.parts[0]=='data' and not value.startswith('data/agent-handoff/mcp-control-tower/'):
        raise Conflict('kit-path-not-allowlisted')
    return path


def install(kit: Path, root: Path, state: Path, role: str, dry_run=False):
    kit=plain_path(kit).resolve();root=plain_path(root).resolve()
    state=local_state_root(root,state)
    isolation=source_isolation_evidence(str(root),root,DESKTOP_CANONICAL,'macmini' if role=='macbook' else role)
    if isolation['sourceRootKind']!='local-worktree':
        if isolation['sourceRootKind'] in {'not-git-root','git-nested-root'}:raise Conflict('producer-git-root-invalid')
        raise Conflict('producer-source-isolation-violation')
    manifest_raw=read_optional(kit/'producer-kit.manifest.json')
    if manifest_raw is None:raise Conflict('producer-kit-manifest-missing')
    manifest=parse_config(manifest_raw,'json')
    if manifest.get('schemaVersion')!='awx.mcp.producer_kit.v1' or not isinstance(manifest.get('files'),list):
        raise Conflict('producer-kit-manifest-invalid')
    rows=[];seen=set()
    for row in manifest['files']:
        if not isinstance(row,dict):raise Conflict('producer-kit-manifest-invalid')
        relative=safe_relative(row.get('path'))
        key=unicodedata.normalize('NFKC',str(relative)).casefold()
        if key in seen:raise Conflict('duplicate-normalized-kit-path')
        seen.add(key)
        path=plain_path(kit/str(relative))
        raw=read_optional(path)
        if raw is None or digest(raw)!=row.get('sha256'):
            raise Conflict('producer-kit-manifest-mismatch')
        # Entry-point installers are verified with the manifest but never copied into source.
        if len(relative.parts)>1:rows.append((str(relative),raw))
    catalog_hash=manifest.get('toolCatalogHash')
    if catalog_hash and digest(read_optional(kit/'main/resources/mcp/awx-control-tower-tools.json'))!=catalog_hash:
        raise Conflict('producer-tool-catalog-mismatch')
    receipt_path=state/'install-receipt.json'
    receipt_before=read_optional(receipt_path)
    receipt=parse_config(receipt_before,'json') if receipt_before else {}
    root_hash=digest(str(root).encode())
    if receipt and receipt.get('sourceRootHash')!=root_hash:
        raise Conflict('install-receipt-root-mismatch')
    prior=receipt.get('installed',{})
    if not isinstance(prior,dict):raise Conflict('install-receipt-invalid')
    incoming_paths={rel for rel,_ in rows}
    if set(prior)-incoming_paths:raise Conflict('removed-assets-require-reconciliation')
    changes=[];installed={};baselines={}
    for rel,raw in rows:
        path=plain_path(root/rel)
        before=read_optional(path)
        if before is None or before==raw:
            after=raw
        elif path.suffix in {'.json','.toml'}:
            baseline=receipt.get('configBaselines',{}).get(rel)
            if path.suffix=='.json' and isinstance(baseline,dict):
                after=encode_config(merge_three_way(parse_config(before,'json'),baseline,parse_config(raw,'json')),'json')
            else:after=merge_config(before,raw,path.suffix[1:])
        elif prior.get(rel)==digest(before):
            after=raw
        else:
            raise Conflict('local-modification-conflict')
        if path.suffix=='.py':
            try:compile(after,str(path),'exec')
            except SyntaxError:raise Conflict('installed-python-parse-failed') from None
        elif path.suffix in {'.json','.toml'}:
            parse_config(after,path.suffix[1:])
        if path.suffix=='.json':baselines[rel]=parse_config(raw,'json')
        changes.append(Change(path,before,after));installed[rel]=digest(after)
    config_path=state/'awx-control-tower.mcp.json'
    if 'scripts/awx_mcp_stdio_server.py' in incoming_paths:
        current=read_optional(config_path)
        desired=encode_config(render_config(role,root,state),'json')
        changes.append(Change(config_path,current,merge_config(current or b'{}',desired,'json')))
    after_receipt={'schemaVersion':'awx.mcp.install_receipt.v1','sourceRootHash':root_hash,
                   'kitHash':digest(manifest_raw),'sourceRevision':manifest.get('sourceRevision'),
                   'toolCatalogHash':catalog_hash,'nodeRole':role,'installed':installed,'configBaselines':baselines}
    changes.append(Change(receipt_path,receipt_before,encode_config(after_receipt,'json')))
    if dry_run:
        return {'ok':True,'status':'planned','changedCount':sum(item.before!=item.after for item in changes),'sourceIsolation':isolation}
    result=apply_changes(changes,state)
    return {'ok':True,**result,'sourceIsolation':isolation,'receiptPath':str(receipt_path),
            'configPath':str(config_path) if config_path.exists() else None,'externalHostProof':'not_observed'}


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--kit-root',required=True)
    parser.add_argument('--producer-root',required=True)
    parser.add_argument('--node-role',choices=['notebook','macmini','macbook'],required=True)
    parser.add_argument('--state-root')
    parser.add_argument('--dry-run',action='store_true')
    args=parser.parse_args()
    try:
        root=Path(args.producer_root)
        result=install(Path(args.kit_root),root,local_state_root(root,args.state_root),args.node_role,args.dry_run)
        print(json.dumps(result,ensure_ascii=True));return 0
    except (Conflict,OSError) as error:
        print(json.dumps({'ok':False,'changedCount':0,'reason':str(error) if isinstance(error,Conflict) else 'installation-io-failed'}))
        return 2


if __name__=='__main__':raise SystemExit(main())
