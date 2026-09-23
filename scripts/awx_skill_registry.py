"""Read-only personal/shared skill collision inventory; no skill installation."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import unicodedata


def scan_skills(shared: Path, personal_roots: list[Path]):
    entries=[]; groups={}
    for scope,root in [('shared',shared),*[('personal',p) for p in personal_roots]]:
        for path in sorted(Path(root).glob('*/SKILL.md')):
            raw=path.read_bytes();text=raw.decode('utf-8-sig')
            match=re.search(r'\A---\s*\r?\n(.*?)\r?\n---(?:\r?\n|$)',text,re.S)
            name=re.search(r'^name:\s*([^\r\n]+)',match.group(1),re.M) if match else None
            if not name:raise ValueError('skill-frontmatter-name-missing')
            declared=name.group(1).strip().strip('\"\'')
            normalized=unicodedata.normalize('NFKC',declared).casefold()
            entry={'name':declared,'scope':scope,'sha256':hashlib.sha256(raw).hexdigest(),
                   'relativePath':path.relative_to(shared).as_posix() if scope=='shared' else None,
                   'pathHash':hashlib.sha256(str(path.resolve()).encode()).hexdigest()}
            entries.append(entry);groups.setdefault(normalized,[]).append(entry)
    collisions=[]
    for group in groups.values():
        if len(group)>1:
            personal=[entry for entry in group if entry['scope']=='personal']
            collisions.append({'name':group[0]['name'],'kind':'same-content' if len({entry['sha256'] for entry in group})==1 else 'different-content',
                               'selectedScope':'personal' if len(personal)==1 else 'ambiguous',
                               'candidateCount':len(group)})
    return {'schemaVersion':'awx.skills.registry.v1','resolutionPolicy':'personal-first-no-write',
            'codexPrecedenceChanged':False,'entries':entries,'collisions':collisions,
            'conflictCount':sum(item['kind']=='different-content' for item in collisions),
            'sharedCount':sum(item['scope']=='shared' for item in entries),
            'personalCount':sum(item['scope']=='personal' for item in entries)}


def personal_priority_config(existing: bytes, shared: Path, report: dict):
    """Append path-specific disable entries; never rewrite either skill copy."""
    try:
        from scripts.awx_shared_state import Conflict, parse_config
    except ModuleNotFoundError:
        from awx_shared_state import Conflict, parse_config
    config=parse_config(existing,'toml') if existing.strip() else {}
    rows=config.get('skills',{}).get('config',[])
    if not isinstance(rows,list):raise Conflict('skills-config-shape-conflict')
    disabled=[]
    for collision in report['collisions']:
        if collision['selectedScope']!='personal':raise Conflict('ambiguous-skill-name')
        key=unicodedata.normalize('NFKC',collision['name']).casefold()
        for entry in report['entries']:
            if entry['scope']!='shared' or unicodedata.normalize('NFKC',entry['name']).casefold()!=key:continue
            path=str((shared/entry['relativePath']).absolute())
            matches=[row for row in rows if isinstance(row,dict) and str(row.get('path','')).replace('\\','/').casefold()==path.replace('\\','/').casefold()]
            if matches:
                if any(row.get('enabled') is not False for row in matches):raise Conflict('personal-skill-priority-conflict')
                continue
            disabled.append({'path':path,'enabled':False})
    if not disabled:return existing
    text=existing.decode('utf-8-sig')+'\n'+''.join('\n[[skills.config]]\npath = '+json.dumps(row['path'])+'\nenabled = false\n' for row in disabled)
    result=text.encode(); parsed=parse_config(result,'toml')
    expected={**config,'skills':{**config.get('skills',{}),'config':rows+disabled}}
    if parsed!=expected:raise Conflict('skill-priority-roundtrip-mismatch')
    return result


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--root',default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument('--personal-root',action='append')
    args=parser.parse_args()
    personal=[Path(p) for p in args.personal_root] if args.personal_root else [Path.home()/'.agents/skills',Path.home()/'.codex/skills']
    print(json.dumps(scan_skills(Path(args.root)/'.agents/skills',personal),ensure_ascii=True))
    return 0


if __name__=='__main__':raise SystemExit(main())
