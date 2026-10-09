"""Bind the seven owned postimages to the final focused test run."""
import datetime, difflib, hashlib, json, pathlib

root = pathlib.Path(__file__).resolve().parents[4]
outdir = pathlib.Path(__file__).parent
first = root / 'data/agent-handoff/codex-autonomy/codex-current-question-5b43f617/cycle-01'
second = root / 'data/agent-handoff/codex-autonomy/codex-focus-fast-default-19d8934a/cycle-01'
run = json.loads((outdir / 'final115/run.json').read_text(encoding='utf-8-sig'))
expected = {pathlib.Path(x['path']).resolve(): x['sha256'] for x in run['sourceIdentity']}
owned = [(first, i, t) for i, t in enumerate(json.loads((first / 'manifest.json').read_text(encoding='utf-8-sig'))['targets'])]
owned += [(second, 0, json.loads((second / 'manifest.json').read_text(encoding='utf-8-sig'))['targets'][0])]
rows, diffs = [], []
for cycle, index, target in owned:
    rel = target['path']
    before = (cycle / 'before' / (str(index) + '.bin')).read_bytes()
    after = (root / rel).read_bytes()
    before_hash = hashlib.sha256(before).hexdigest()
    after_hash = hashlib.sha256(after).hexdigest()
    assert before_hash == target['preimageSha256'], 'preimage changed: ' + rel
    diff = list(difflib.unified_diff(before.decode('utf-8-sig').splitlines(True), after.decode('utf-8-sig').splitlines(True), fromfile='a/' + rel, tofile='b/' + rel))
    diffs.extend(diff)
    rows.append({'path': rel, 'preimageSha256': before_hash, 'currentSha256': after_hash,
                 'matchesFinal115': expected.get((root / rel).resolve()) == after_hash,
                 'addedLines': sum(x.startswith('+') and not x.startswith('+++') for x in diff),
                 'deletedLines': sum(x.startswith('-') and not x.startswith('---') for x in diff)})
assert all(row['matchesFinal115'] for row in rows), 'current source differs from tested source'
(outdir / 'final-owned-change.diff').write_text(''.join(diffs), encoding='utf-8')
result = {'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'testRunId': run['runId'], 'basis': 'preserved task preimages; original checkpoint remains prepared/HOLD, second checkpoint verified',
          'files': rows}
(outdir / 'final-owned-file-evidence.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps({'files': len(rows), 'allMatchFinal115': True, 'addedLines': sum(x['addedLines'] for x in rows), 'deletedLines': sum(x['deletedLines'] for x in rows)}))
