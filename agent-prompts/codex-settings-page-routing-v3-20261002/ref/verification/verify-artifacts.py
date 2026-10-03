#!/usr/bin/env python3
"""산출물 참조·해시·포장만 검사한다. Java/JS 앱이나 제공 소스는 실행하지 않는다."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath

def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def main() -> int:
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--package-root',type=Path,required=True)
    ap.add_argument('--input-dir',type=Path,required=True)
    ap.add_argument('--zip',type=Path)
    args=ap.parse_args(); root=args.package_root; src=args.input_dir
    results=[]
    def check(label: str, condition: bool, detail: str='') -> None:
        if not condition:
            raise AssertionError(label + (': '+detail if detail else ''))
        results.append(dict(check=label,result='PASS',detail=detail))
    def load(relative: str):
        return json.loads((root/relative).read_text(encoding='utf-8'))
    manifest=load('evidence/input-manifest.json')
    check('input_original_hashes_unchanged',all(digest((src/i['name']).read_bytes())==i['sha256_before']==i['sha256_after'] for i in manifest['inputs']),f"{len(manifest['inputs'])} inputs")
    text=(root/'FIND_X_settings_uaw_harmony_v3_ko.md').read_text()
    check('download_copies_match',(src/'FIND_X_settings_uaw_harmony_v3_ko.md').read_text()==text==(src/'FIND_X_settings_uaw_harmony_v3_ko.txt').read_text())
    check('addendum_copy_matches',(root/'Abandon_X_settings_routing_v3_addendum.txt').read_bytes()==(src/'Abandon_X_settings_routing_v3_addendum.txt').read_bytes())
    check('v2_document_unchanged',(root/'reference/FIND_X_settings_routing_v2_ko.md').read_bytes()==(src/'FIND_X_settings_routing_v2_ko.md').read_bytes())
    anchors=load('evidence/source-anchors-v3.json'); old=load('reference/v2-source-anchors.json'); reads=load('evidence/read-manifest.json')
    check('new_anchor_ids_unique',len(anchors)==44 and len({a['id'] for a in anchors})==44)
    ids={a['id'] for a in anchors}
    with zipfile.ZipFile(src/'FIND_X.zip') as z:
        check('archive_listing',len(z.infolist())==3037 and sum(not a.is_dir() for a in z.infolist())==2394)
        for group in [anchors,old]:
            for a in group:
                data=z.read(a['path']); lines=data.decode('utf-8-sig').splitlines()
                assert digest(data)==a['file_sha256'],a['id']
                assert 1<=a['start_line']<=a['end_line']<=len(lines),a['id']
                if 'range_sha256' in a:
                    assert digest('\n'.join(lines[a['start_line']-1:a['end_line']]).encode())==a['range_sha256'],a['id']
        for name,entry in reads.items():
            b=z.read(name)
            assert digest(b)==entry['sha256'] and len(b.decode('utf-8-sig').splitlines())==entry['lines'],name
    check('new_and_reference_anchor_hashes_and_ranges',len(old)==52,'44 current anchors; 52 prior reference anchors. Prior claim semantics not all re-reviewed.')
    check('selected_read_manifest',len(reads)==manifest['selected_source_files_read']==35 and {a['path'] for a in anchors}<=set(reads) and not any('/interview/' in k for k in reads),'35 selected source files; no studio file content read')
    cw=load('contracts/uaw-crosswalk.json'); uaw_lines=(src/'UAW.txt').read_text(encoding='utf-8-sig').splitlines()
    for c in cw:
        assert set(c['current_evidence'])<=ids
        for s in c['uaw_lines'].split(';'):
            lo,hi=map(int,s.split('-')); assert 1<=lo<=hi<=len(uaw_lines)
    check('uaw_crosswalk_refs',len(cw)==18 and len({c['id'] for c in cw})==18,'18 intent/implementation mappings')
    tests=load('contracts/v3-test-matrix.json'); prior=load('reference/v2-test-matrix.json')
    for t in tests:
        assert set(t['source_refs'])<=ids and t['status']=='NOT_RUN'
        assert t['given'] and t['expected'] and 1<=t['work_package']<=5
        assert t['id'] in text
    check('test_spec_ids_refs_and_not_run',len(tests)==32 and len(prior)==64 and len({t['id'] for t in tests+prior})==96 and all(t['status']=='NOT_RUN' for t in prior),'96 specifications; 0 application tests executed')
    ex=load('contracts/view-examples.json'); oldex=load('reference/v2-payload-examples.json')
    check('profile_schema_and_safe_examples',ex['saveProfileUnchanged']==oldex['saveRequest']['profile'] and not ex['newApiEndpoints'] and len(ex['existingV2DraftEndpoints'])==3 and all(ex['previewView'][k]==0 for k in ['modelCalls','externalCalls','writes','stateMutations']))
    for c in ex['settingsView']['capabilities']:
        assert not c['editable'] and c['configuredValue'] is None and c['effectiveValue'] is None and c['observedValue'] is None
        assert set(c['sourceRefs'])<=ids
    check('unknown_values_preserved',True)
    check('document_sections_and_placeholders',len(re.findall(r'^### WP[1-5] ',text,re.M))==5 and not re.search(r'\{\{[^\n]*\}\}',text) and sum(1 for line in text.splitlines() if line.startswith('```'))%2==0,'5 integrated work packages; balanced code fences; no template placeholders')
    all_files=[p for p in root.rglob('*') if p.is_file()]
    prohibited={'FIND_X.zip','UAW.txt','Abandon_X.txt'}
    check('no_original_source_or_private_runtime_bundle',not any(p.name in prohibited or p.suffix in {'.java','.class','.jar','.mkv','.har'} or p.is_symlink() for p in all_files),'document/contract/verification bundle only')
    if args.zip:
        with zipfile.ZipFile(args.zip) as z:
            assert z.testzip() is None
            names=z.namelist()
            assert len(names)==len(set(names))
            for name in names:
                pp=PurePosixPath(name)
                assert not pp.is_absolute() and '..' not in pp.parts and '\\' not in name
                assert z.read(name)==(root/name).read_bytes(),name
            assert set(names)=={p.relative_to(root).as_posix() for p in all_files}
        for line in (root/'SHA256SUMS.txt').read_text().splitlines():
            d,name=line.split('  ',1); assert digest((root/name).read_bytes())==d,name
        check('zip_crc_members_and_sha256_manifest',True,f'{len(names)} entries')
    print(json.dumps(dict(scope='ARTIFACT_VALIDATION_ONLY',checks=results,check_groups=len(results),application_tests='NOT_RUN',runtime_startup='NOT_RUN',external_model_calls=0),ensure_ascii=False,indent=2))
    return 0

if __name__=='__main__':
    try:
        sys.exit(main())
    except (AssertionError,OSError,ValueError,KeyError,zipfile.BadZipFile) as e:
        print(json.dumps({'scope':'ARTIFACT_VALIDATION_ONLY','status':'FAIL','error':str(e)},ensure_ascii=False),file=sys.stderr)
        sys.exit(1)
