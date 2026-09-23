"""Source-bound semantic sidecar: flexible facets, explicit evidence, no source moves."""
import argparse
import json
import re
import unicodedata
from collections import Counter
from pathlib import Path

from labio import digest, file_hash, immutable, identifier, read_json, safe_path, write_json

RELATIONS = {'related_to', 'requires', 'produces_input_for', 'alternative_to', 'overlaps_with', 'conflicts_with', 'duplicate_candidate', 'supersedes'}
FACETS = ('functions', 'purposes', 'inputs', 'outputs', 'constraints', 'dependencies')
DEFAULT_CATALOG = '.agents/skills/semantic-catalog.yaml'
DEFAULT_INDEX = '.agents/skills/semantic-index.json'
USAGE = 'data/agent-handoff/adaptive-rule-lab/usage'


def read_catalog(path):
    # JSON is a strict YAML subset. Existing YAML sidecars are accepted read-only.
    text = Path(path).read_text(encoding='utf-8-sig')
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        import yaml
        return yaml.safe_load(text)


def inventory(root, external_roots=()):
    root = Path(root).resolve()
    paths = set()
    for name in ('AGENTS.md', 'AGENTS.override.md'):
        if (root / name).is_file():
            paths.add(name)
    routes = {}
    route_index = root / '.agents/skills/INDEX.md'
    if route_index.exists():
        block = re.search(r'```yaml\s*\n(.*?)```', route_index.read_text(encoding='utf-8-sig'), re.S)
        if block:
            import yaml
            for row in yaml.safe_load(block.group(1)).get('routes', []):
                source = row.get('source', '')
                if '#' not in source:
                    routes[source] = row
                    if source and (root / source).is_file():
                        paths.add(source)
    for folder in ('.agents/skills', 'agent-prompts', 'docs/superpowers/specs'):
        base = safe_path(root, folder)
        if base.exists():
            paths.update(p.relative_to(root).as_posix() for p in base.rglob('*.md'))
    entries, diagnostics = [], []
    for relative in sorted(paths):
        if Path(relative).name in ('INDEX.md', 'SEMANTIC_INDEX.md'):
            continue
        try:
            path = safe_path(root, relative)
            if path.stat().st_size > 2_000_000:
                diagnostics.append({'source': relative, 'reason': 'size-limit'})
                continue
            text = path.read_text(encoding='utf-8-sig')
        except (OSError, ValueError, UnicodeError):
            diagnostics.append({'source': relative, 'reason': 'unreadable-or-unsafe'})
            continue
        source_hash = file_hash(path)
        lines = text.splitlines()
        if relative.startswith('AGENTS'):
            sections = [(i, line.lstrip('# ').strip()) for i, line in enumerate(lines) if line.startswith('## ')]
            if not sections:
                sections = [(0, 'Root rules')]
            for position, (start, title) in enumerate(sections):
                end = sections[position + 1][0] if position + 1 < len(sections) else len(lines)
                anchor = re.sub(r'[^\w-]+', '-', title.lower()).strip('-')
                entries.append({'id': f'repo|rule|{relative}#{anchor}', 'kind': 'rule', 'canonicalId': f'{relative}#{anchor}', 'scope': 'repo', 'source': relative, 'sourceHash': source_hash, 'title': title, 'description': title, 'line': start + 1, '_text': '\n'.join(lines[start:end])})
            continue
        is_skill = path.name == 'SKILL.md'
        name_match = re.search(r'^name:\s*[\"\']?([^\n\"\']+)', text, re.M)
        desc_match = re.search(r'^description:\s*(.+)', text, re.M)
        title_match = re.search(r'^#\s+(.+)', text, re.M)
        route = routes.get(relative, {})
        canonical = route.get('canonicalId') or (name_match.group(1).strip() if is_skill and name_match else relative)
        kind = route.get('kind') or ('skill' if is_skill else ('directive' if relative.startswith('agent-prompts/') else 'reference'))
        entries.append({'id': f'repo|{kind}|{canonical}', 'kind': kind, 'canonicalId': canonical, 'scope': 'repo', 'source': relative, 'sourceHash': source_hash, 'title': title_match.group(1).strip() if title_match else path.stem, 'description': desc_match.group(1).strip(' "\'') if desc_match else '', 'line': 1, '_text': text})
    for external in external_roots:
        base = Path(external['path']).resolve()
        scope = identifier(external['scope'])
        if not base.is_dir():
            diagnostics.append({'scope': scope, 'reason': 'unreadable-external-root'})
            continue
        for path in sorted(base.glob('*/SKILL.md')):
            try:
                path = safe_path(base, path.relative_to(base).as_posix())
                text = path.read_text(encoding='utf-8-sig')
                name = re.search(r'^name:\s*(.+)', text, re.M)
                desc = re.search(r'^description:\s*(.+)', text, re.M)
                canonical = name.group(1).strip(' "\'') if name else path.parent.name
                entries.append({'id': f'{scope}|skill|{canonical}', 'kind': 'skill', 'canonicalId': canonical, 'scope': scope, 'source': path.as_posix(), 'sourceHash': file_hash(path), 'title': canonical, 'description': desc.group(1).strip(' "\'') if desc else '', 'line': 1, '_text': text, 'readOnlyOrigin': True})
            except (OSError, ValueError, UnicodeError):
                diagnostics.append({'scope': scope, 'source': path.name, 'reason': 'unreadable-or-unsafe'})
    duplicates = [key for key, count in Counter(unicodedata.normalize('NFKC', e['id']).casefold() for e in entries).items() if count > 1]
    diagnostics.extend({'id': key, 'reason': 'duplicate-identity'} for key in duplicates)
    return {'entries': entries, 'diagnostics': diagnostics, 'scope': ['AGENTS*.md sections', '.agents/skills/**/*.md', 'agent-prompts/**/*.md', 'docs/superpowers/specs/**/*.md', 'explicit typed-index tool sources'] + [e['scope'] for e in external_roots]}


def validate_annotations(snapshot, annotations):
    problems = list(snapshot.get('diagnostics', []))
    live = {e['id']: e for e in snapshot['entries']}
    seen = set()
    for row in annotations.get('entries', []):
        key = row['id']
        if key in seen:
            problems.append({'id': key, 'reason': 'duplicate-annotation'})
        seen.add(key)
        if key not in live:
            problems.append({'id': key, 'reason': 'missing-annotated-source'})
        elif row.get('sourceHash') != live[key]['sourceHash']:
            problems.append({'id': key, 'reason': 'stale-annotation'})
        elif not row.get('evidenceLines') or any(type(x) is not int or x < 1 or x > len(live[key]['_text'].splitlines()) + live[key]['line'] - 1 for x in row['evidenceLines']):
            problems.append({'id': key, 'reason': 'invalid-evidence-lines'})
        for facet in FACETS:
            if facet in row and (not isinstance(row[facet], list) or any(not isinstance(x, str) for x in row[facet])):
                problems.append({'id': key, 'reason': 'invalid-facet'})
    graph = {}
    for edge in annotations.get('relations', []):
        a, b = live.get(edge.get('from')), live.get(edge.get('to'))
        reason = None
        if not a or not b:
            reason = 'missing-relation-endpoint'
        elif edge.get('fromHash') != a['sourceHash'] or edge.get('toHash') != b['sourceHash']:
            reason = 'stale-relation'
        elif edge.get('type') not in RELATIONS or not edge.get('activationCondition') or not edge.get('evidenceLines') or not edge.get('mergeVerdict'):
            reason = 'incomplete-relation'
        if reason:
            problems.append({'from': edge.get('from'), 'to': edge.get('to'), 'reason': reason})
        elif edge['type'] == 'requires':
            graph.setdefault(edge['from'], []).append(edge['to'])
    def visit(node, stack, done):
        if node in stack:
            return True
        if node in done:
            return False
        stack.add(node)
        cycle = any(visit(n, stack, done) for n in graph.get(node, []))
        stack.remove(node)
        done.add(node)
        return cycle
    if any(visit(n, set(), set()) for n in graph):
        problems.append({'reason': 'dependency-cycle'})
    return problems


def record_usage(root, event):
    fields = {'eventId', 'routeId', 'stage', 'at', 'observationScope'}
    if set(event) != fields or event['stage'] not in ('retrieved', 'selected', 'invoked', 'completed') or not all(isinstance(v, str) and v for v in event.values()):
        raise ValueError('invalid-usage-event')
    identifier(event['eventId'])
    immutable(safe_path(root, f"{USAGE}/{event['eventId']}.json"), event)


def usage_events(root):
    base = safe_path(root, USAGE)
    return [read_json(safe_path(root, p.relative_to(root).as_posix())) for p in sorted(base.glob('*.json'))] if base.exists() else []


def build_index(root, annotations):
    snapshot = inventory(root, annotations.get('externalRoots', []))
    diagnostics = validate_annotations(snapshot, annotations)
    reviewed = {e['id']: e for e in annotations.get('entries', [])}
    events = usage_events(Path(root).resolve())
    entries = []
    for source in snapshot['entries']:
        entry = {k: v for k, v in source.items() if k != '_text'}
        annotation = reviewed.get(source['id'])
        status = 'unreviewed' if annotation is None else ('current' if annotation['sourceHash'] == source['sourceHash'] else 'stale')
        entry.update({facet: [] for facet in FACETS})
        if status == 'current':
            entry.update({k: v for k, v in annotation.items() if k in FACETS or k in ('reviewStatus', 'evidenceLines', 'aliases')})
        entry['annotationStatus'] = status
        observed = [e for e in events if e['routeId'] == entry['id']]
        invoked = [e for e in observed if e['stage'] == 'invoked']
        entry['usage'] = {'observedInvocations': len(invoked) if observed else None, 'eligibleOpportunities': None, 'windowStart': min((e['at'] for e in observed), default=None), 'windowEnd': max((e['at'] for e in observed), default=None), 'coverage': 'instrumented_events_only' if observed else 'unknown', 'observationScopes': sorted({e['observationScope'] for e in observed}), 'firstObservedAt': min((e['at'] for e in observed), default=None), 'lastObservedAt': max((e['at'] for e in observed), default=None)}
        entries.append(entry)
    bad_edges = {(e.get('from'), e.get('to')) for e in diagnostics if 'from' in e}
    result = {'schemaVersion': 'awx.semantic.index.v1', 'scope': snapshot['scope'], 'entries': entries, 'concepts': annotations.get('concepts', []), 'relations': [e for e in annotations.get('relations', []) if (e['from'], e['to']) not in bad_edges], 'diagnostics': diagnostics, 'semanticMethod': 'agent-reviewed source-bound facets and concept aliases; lexical ranking is only candidate retrieval', 'originalMutationAllowed': False}
    result['contentHash'] = digest(result)
    return result


def tokens(text):
    return set(re.findall(r'[\w]+', text.casefold()))


def search(index, query, facets=None, limit=10, mode='semantic'):
    needles = tokens(query)
    concepts = set()
    if mode == 'semantic':
        for concept in index.get('concepts', []):
            if any(tokens(label) & needles for label in [concept['id']] + concept.get('labels', [])):
                concepts.add(concept['id'])
                needles |= tokens(' '.join(concept.get('labels', []))) | tokens(concept['id'])
    ranked = []
    for entry in index['entries']:
        if facets and any(not set(values).issubset(entry.get(facet, [])) for facet, values in facets.items()):
            continue
        score = len(needles & tokens(entry['canonicalId'] + ' ' + entry['title'])) * 3
        reasons = ['title-token'] if score else []
        if mode == 'semantic':
            score += len(needles & tokens(entry.get('description', '')))
            hits = concepts & set(entry.get('functions', []))
            score += len(hits) * 12
            score += len(needles & tokens(' '.join(sum((entry.get(f, []) for f in FACETS), [])))) * 2
            reasons += sorted(hits)
        if score:
            ranked.append(dict(entry, retrievalScore=score, matchedConcepts=reasons))
    return sorted(ranked, key=lambda row: (-row['retrievalScore'], row['id']))[:limit]


def merge_assessment(a, b):
    differences = [f for f in ('scope', 'kind', 'constraints', 'inputs', 'outputs', 'dependencies') if a.get(f) != b.get(f)]
    return {'verdict': 'keep_distinct' if differences else 'review_candidate', 'constraintDifferences': differences, 'mutationAllowed': False, 'reason': 'Different contracts retain their owners.' if differences else 'Similarity or byte equality does not establish interchangeable authority.'}


def suggest(index, limit=30):
    candidates = []
    entries = [e for e in index['entries'] if e['kind'] in ('skill', 'directive')]
    for i, a in enumerate(entries):
        af = set(a['functions'])
        for b in entries[i + 1:]:
            bf = set(b['functions'])
            shared = af & bf
            if shared:
                score = len(shared) / len(af | bf)
                candidates.append({'from': a['id'], 'to': b['id'], 'fromHash': a['sourceHash'], 'toHash': b['sourceHash'], 'sharedFunctions': sorted(shared), 'candidateScore': score, 'assessment': merge_assessment(a, b)})
    return sorted(candidates, key=lambda e: (-e['candidateScore'], e['from'], e['to']))[:limit]


def markdown(index):
    lines = ['# Semantic skill and rule index', '', 'Generated from source-bound annotations. Originals retain authority. Unknown usage is not zero.', '', f"Content hash: `{index['contentHash']}`", '', '| Kind | Route / source | Functions | Review | Invocations |', '|---|---|---|---|---|']
    for entry in index['entries']:
        path = entry['source'] if entry.get('readOnlyOrigin') else entry['source'].replace('.agents/skills/', '', 1) if entry['source'].startswith('.agents/skills/') else '../../' + entry['source']
        title = entry['canonicalId'].replace('|', '\\|')
        lines.append(f"| {entry['kind']} | [{title}]({path}) | {', '.join(entry['functions'])} | {entry['annotationStatus']} | {entry['usage']['observedInvocations'] if entry['usage']['observedInvocations'] is not None else 'unknown'} |")
    lines += ['', '## Relationships', '']
    for edge in index['relations']:
        lines.append(f"- `{edge['from']}` → **{edge['type']}** → `{edge['to']}`: {edge['activationCondition']} ({edge['mergeVerdict']})")
    lines += ['', '## Diagnostics', '', '```json', json.dumps(index['diagnostics'], ensure_ascii=False, indent=2), '```', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inventory', 'build', 'validate', 'search', 'suggest', 'record-usage'])
    parser.add_argument('--root', default=str(Path(__file__).resolve().parents[4]))
    parser.add_argument('--catalog', default=DEFAULT_CATALOG)
    parser.add_argument('--query', default='')
    parser.add_argument('--event')
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    root = Path(args.root).resolve()
    if args.action == 'record-usage':
        record_usage(root, read_json(safe_path(root, args.event)))
        print('{"recorded":true}')
        return
    if args.action == 'inventory':
        result = inventory(root)
        result['entries'] = [{k: v for k, v in e.items() if k != '_text'} for e in result['entries']]
    else:
        index = build_index(root, read_catalog(safe_path(root, args.catalog)))
        result = index
        if args.action == 'search':
            result = search(index, args.query)
        elif args.action == 'suggest':
            result = suggest(index)
        elif args.action == 'validate':
            result = {'valid': not index['diagnostics'], 'entries': len(index['entries']), 'relations': len(index['relations']), 'diagnostics': index['diagnostics']}
        elif args.write:
            target = safe_path(root, DEFAULT_INDEX)
            write_json(target, index, file_hash(target) if target.exists() else None)
            human = safe_path(root, '.agents/skills/SEMANTIC_INDEX.md')
            human.write_text(markdown(index), encoding='utf-8')
            result = {'entries': len(index['entries']), 'relations': len(index['relations']), 'diagnostics': len(index['diagnostics']), 'contentHash': index['contentHash']}
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    if args.action == 'validate' and not result['valid']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
