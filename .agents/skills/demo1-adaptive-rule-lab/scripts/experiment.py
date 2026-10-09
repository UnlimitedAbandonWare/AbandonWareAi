"""Persistent local experiment loop with frozen cases, budgets and task-only promotion."""
import argparse
import json
import os
import platform
import re
import sys
import unicodedata
from datetime import datetime
import time
from collections import Counter
from contextlib import contextmanager
from pathlib import Path

from evaluators import pin, check_pin, evaluate
from labio import digest, file_hash, identifier, immutable, read_json, safe_path, safe_note, utcnow, write_json
from metrics import DEFAULT_POLICY, compare, summarize, validate_policy

BASE = 'data/agent-handoff/adaptive-rule-lab/campaigns'
from catalog import GUIDANCE, guidance_scope, read_catalog, DEFAULT_CATALOG, guidance_evidence_path as evidence_path
REVIEWS = 'data/agent-handoff/adaptive-rule-lab/review-requests'


def directory(root, campaign):
    return safe_path(root, f'{BASE}/{identifier(campaign)}')


@contextmanager
def owned_lock(base):
    lock = base / '.operation.lock'
    fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    try:
        os.close(fd)
        yield
    finally:
        lock.unlink(missing_ok=True)


def environment():
    return digest({'python': platform.python_version(), 'platform': platform.system(), 'machine': platform.machine(), 'metrics': file_hash(Path(__file__).with_name('metrics.py')), 'runner': file_hash(__file__)})


def register(root, campaign, cases_path, baseline, hypothesis, policy=None):
    identifier(campaign)
    if not isinstance(hypothesis, str) or not hypothesis.strip():
        raise ValueError('missing-falsifiable-hypothesis')
    hypothesis = safe_note(hypothesis)
    policy = dict(policy or DEFAULT_POLICY)
    validate_policy(policy)
    cases = read_json(safe_path(root, cases_path))
    if not isinstance(cases, list) or not cases:
        raise ValueError('empty-cases')
    seen, groups = set(), set()
    for case in cases:
        identifier(case['caseId'])
        identifier(case['independenceGroup'])
        if case['caseId'] in seen or case['independenceGroup'] in groups:
            raise ValueError('duplicate-or-correlated-case-unit')
        if case['split'] not in ('development', 'confirmation'):
            raise ValueError('invalid-split')
        if case['split'] == 'confirmation' and (type(case.get('confirmationBatch', 0)) is not int or case.get('confirmationBatch', 0) < 0):
            raise ValueError('invalid-preregistered-batch')
        seen.add(case['caseId'])
        groups.add(case['independenceGroup'])
    base = directory(root, campaign)
    base.mkdir(parents=True, exist_ok=True)
    config = {'schemaVersion': 'awx.rule.campaign.v1', 'campaignId': campaign, 'cases': cases_path, 'casesHash': file_hash(safe_path(root, cases_path)), 'baseline': pin(root, baseline), 'policy': policy, 'hypothesis': hypothesis, 'environmentHash': environment(), 'createdAt': utcnow(), 'samplingAssumption': 'caller-declared independent groups; external representativeness is not established'}
    with owned_lock(base):
        write_json(base / 'campaign.json', config)
        write_json(base / 'state.json', {'configHash': digest(config), 'candidates': {}, 'runs': {}, 'usedConfirmationIds': [], 'confirmationLooks': 0, 'elapsedSeconds': 0.0, 'active': None})
    return {'campaignId': campaign, 'configHash': digest(config)}


def load(root, campaign):
    base = directory(root, campaign)
    config, state = read_json(base / 'campaign.json'), read_json(base / 'state.json')
    if digest(config) != state['configHash'] or file_hash(safe_path(root, config['cases'])) != config['casesHash'] or environment() != config['environmentHash']:
        raise ValueError('changed-frozen-campaign')
    check_pin(root, config['baseline'])
    return base, config, state


def save_state(base, state):
    write_json(base / 'state.json', state, file_hash(base / 'state.json'))


def add_candidate(root, campaign, candidate, spec, hypothesis, changed_basis, parent=None):
    identifier(candidate)
    if not hypothesis.strip() or not changed_basis.strip():
        raise ValueError('missing-hypothesis-or-changed-basis')
    hypothesis, changed_basis = safe_note(hypothesis), safe_note(changed_basis)
    base = directory(root, campaign)
    with owned_lock(base):
        base, config, state = load(root, campaign)
        if candidate in state['candidates']:
            raise ValueError('candidate-already-exists')
        pinned = pin(root, spec)
        if any(digest(read_json(base / f'candidates/{key}.json')['evaluator']) == digest(pinned) for key in state['candidates']):
            raise ValueError('same-experiment-without-changed-mechanism')
        if parent:
            if parent not in state['candidates']:
                raise ValueError('missing-parent')
            prior = read_json(base / f'candidates/{parent}.json')
            completed = [r for r in state['runs'].values() if r['candidateId'] == parent and r['status'] == 'complete']
            if not completed:
                raise ValueError('revision-needs-observed-error-analysis')
            revision, family = prior['revision'] + 1, prior['family']
            if revision > config['policy']['maxRevisions']:
                raise ValueError('revision-budget')
            if sum(read_json(base / f'candidates/{key}.json')['family'] == family for key in state['candidates']) >= config['policy']['maxRevisions']:
                raise ValueError('family-revision-budget')
            evidence = [r['reportHash'] for r in completed]
        else:
            if sum(v['revision'] == 1 for v in state['candidates'].values()) >= config['policy']['maxCandidates']:
                raise ValueError('candidate-budget')
            revision, family, evidence = 1, candidate, []
        proposal = {'candidateId': candidate, 'family': family, 'revision': revision, 'parent': parent, 'evaluator': pinned, 'hypothesis': hypothesis, 'changedBasis': changed_basis, 'priorReportHashes': evidence, 'createdAt': utcnow(), 'sharedRuleMutationAuthorized': False}
        immutable(base / f'candidates/{candidate}.json', proposal)
        state['candidates'][candidate] = {'revision': revision, 'proposalHash': digest(proposal)}
        save_state(base, state)
    return proposal


def run(root, campaign, candidate, run_id, split='development', case_ids=None):
    identifier(run_id)
    base = directory(root, campaign)
    with owned_lock(base):
        base, config, state = load(root, campaign)
        if run_id in state['runs']:
            raise ValueError('run-already-consumed')
        if any(r['status'] == 'started' for r in state['runs'].values()):
            raise ValueError('interrupted-run-needs-recovery-audit')
        if candidate not in state['candidates'] or split not in ('development', 'confirmation'):
            raise ValueError('unregistered-run')
        proposal = read_json(base / f'candidates/{candidate}.json')
        if digest(proposal) != state['candidates'][candidate]['proposalHash']:
            raise ValueError('changed-proposal')
        check_pin(root, proposal['evaluator'])
        cases = [c for c in read_json(safe_path(root, config['cases'])) if c['split'] == split]
        if split == 'confirmation':
            unused = [c for c in cases if c['caseId'] not in state['usedConfirmationIds']]
            if not unused:
                raise ValueError('heldout-reuse')
            batch = min(c.get('confirmationBatch', 0) for c in unused)
            cases = [c for c in cases if c.get('confirmationBatch', 0) == batch]
        if case_ids is not None:
            if not case_ids or len(case_ids) != len(set(case_ids)) or not set(case_ids).issubset({c['caseId'] for c in cases}):
                raise ValueError('invalid-case-selection')
            if split == 'confirmation' and set(case_ids) != {c['caseId'] for c in cases}:
                raise ValueError('confirmation-subset-not-preregistered')
            cases = [c for c in cases if c['caseId'] in case_ids]
        if not cases:
            raise ValueError('empty-case-selection')
        selected = {c['caseId'] for c in cases}
        if split == 'confirmation':
            if selected & set(state['usedConfirmationIds']):
                raise ValueError('heldout-reuse')
            if state['confirmationLooks'] >= config['policy']['maxConfirmationLooks']:
                raise ValueError('confirmation-budget')
        if state['elapsedSeconds'] >= config['policy']['maxCampaignSeconds']:
            raise ValueError('campaign-time-budget')
        # Reserve before evaluation. Interrupted runs consume their holdout/look.
        state['runs'][run_id] = {'candidateId': candidate, 'status': 'started', 'split': split, 'caseIds': sorted(selected), 'startedAt': utcnow()}
        if split == 'confirmation':
            state['usedConfirmationIds'] += sorted(selected)
            state['confirmationLooks'] += 1
        save_state(base, state)
        run_dir = base / f'runs/{run_id}'
        started, rows = time.monotonic(), {'baseline': [], 'candidate': []}
        remaining = config['policy']['maxCampaignSeconds'] - state['elapsedSeconds']
        try:
            for position, case in enumerate(cases):
                order = ('baseline', 'candidate') if position % 2 == 0 else ('candidate', 'baseline')
                for arm in order:
                    available = remaining - (time.monotonic() - started)
                    spec = config['baseline'] if arm == 'baseline' else proposal['evaluator']
                    if available <= 0:
                        row = {'caseId': case['caseId'], 'attemptId': case['caseId'], 'success': False, 'quality': 0.0, 'debugVerified': False, 'status': 'timeout', 'errorClass': 'campaign-budget', 'latencyMs': config['policy']['maxEvaluationSeconds'] * 1000.0}
                    else:
                        row = evaluate(root, spec, case, min(config['policy']['maxEvaluationSeconds'], available))
                    row['attemptId'] = f'{run_id}-{arm}-{case["caseId"]}'
                    immutable(run_dir / f'events/{arm}-{case["caseId"]}.json', row)
                    rows[arm].append(row)
            decision = compare(rows['baseline'], rows['candidate'], config['policy'])
            residuals = [{'caseId': c['caseId'], 'reason': c['errorClass'] or ('semantic-miss' if not c['success'] else 'quality-regression')} for b, c in zip(rows['baseline'], rows['candidate']) if not c['success'] or c['quality'] < b['quality']]
            report = {'schemaVersion': 'awx.rule.run.v1', 'campaignId': campaign, 'runId': run_id, 'candidateId': candidate, 'split': split, 'decision': decision, 'errorAnalysis': {'counts': dict(Counter(r['reason'] for r in residuals)), 'residuals': residuals, 'nextAction': 'revise-falsifiable-hypothesis-and-mechanism' if residuals else 'confirm-on-fresh-heldout-cases' if split == 'development' else 'consider-task-local-promotion' if decision['eligible'] else 'retain-baseline-and-gather-independent-evidence'}, 'rowsHash': digest(rows), 'proposalHash': digest(proposal), 'configHash': digest(config), 'environmentHash': config['environmentHash'], 'samplingAssumption': config['samplingAssumption'], 'order': 'alternating-AB-BA', 'sharedRulesChanged': False}
            immutable(run_dir / 'measurements.json', rows)
            immutable(run_dir / 'report.json', report)
            state['runs'][run_id].update(status='complete', reportHash=digest(report))
            return report
        except BaseException:
            state['runs'][run_id]['status'] = 'interrupted'
            raise
        finally:
            state['elapsedSeconds'] += time.monotonic() - started
            save_state(base, state)


def promote(root, campaign, run_id):
    base = directory(root, campaign)
    with owned_lock(base):
        base, config, state = load(root, campaign)
        record = state['runs'].get(run_id)
        if not record or record['status'] != 'complete' or record['split'] != 'confirmation':
            raise ValueError('promotion-needs-complete-confirmation')
        report = read_json(base / f'runs/{identifier(run_id)}/report.json')
        rows = read_json(base / f'runs/{run_id}/measurements.json')
        proposal = read_json(base / f'candidates/{record["candidateId"]}.json')
        check_pin(root, proposal['evaluator'])
        if digest(report) != record['reportHash'] or digest(rows) != report['rowsHash'] or digest(proposal) != report['proposalHash']:
            raise ValueError('changed-promotion-evidence')
        decision = compare(rows['baseline'], rows['candidate'], config['policy'])
        if not decision['eligible']:
            raise ValueError('measured-improvement-not-established')
        pointer = {'candidateId': record['candidateId'], 'runId': run_id, 'reportHash': record['reportHash'], 'scope': 'this-campaign-only', 'previous': state['active']}
        immutable(base / f'promotions/{run_id}.json', pointer)
        state['active'] = pointer
        save_state(base, state)
        return pointer


def rollback(root, campaign, expected_pointer_hash, reason):
    if not reason.strip():
        raise ValueError('missing-rollback-reason')
    base = directory(root, campaign)
    with owned_lock(base):
        base, config, state = load(root, campaign)
        if state['active'] is None or digest(state['active']) != expected_pointer_hash:
            raise ValueError('changed-active-pointer')
        previous = state['active']
        immutable(base / f'rollbacks/{expected_pointer_hash}.json', {'previous': previous, 'reasonCode': 'local-regression-recovery', 'reasonHash': digest(reason)})
        state['active'] = previous['previous']
        save_state(base, state)
        return {'restored': state['active'], 'sharedRulesChanged': False}



def guidance_id(value):
    value = unicodedata.normalize('NFKC', identifier(value)).casefold()
    if value.split('.')[0] in {'con', 'prn', 'aux', 'nul'} or re.fullmatch(r'(com|lpt)[1-9](\..*)?', value):
        raise ValueError('reserved-guidance-id')
    return value



def register_guidance(root, packet, latest_ref, expected_revision, expected_catalog_hash=None, fault=None):
    """Register a local technical reference from real receipts; never apply permission policy.

    Agent-curated semantics and current human authority are caller obligations. The
    deterministic gate proves structural/evidence consistency, not natural-language truth.
    Permission candidates prepare a local review request; no notification or rule application.
    """
    root = Path(root).resolve()
    if not isinstance(packet, dict) or len(json.dumps(packet, ensure_ascii=False, allow_nan=False)) > 64000:
        raise ValueError('candidate-size-or-shape')
    if packet.get('schemaVersion') != 'awx.guidance-candidate.v1' or packet.get('instructionRef') != latest_ref or not str(latest_ref).startswith('user:') or type(expected_revision) is not int or expected_revision < 1:
        raise ValueError('current-instruction-required')
    latest_ref = safe_note(latest_ref)
    key = guidance_id(packet.get('candidateId'))
    summary = safe_note(packet.get('summary'))
    if re.search(r'(?i)(raw[- ]?(session|conversation)|private[- ]?reasoning|chain[- ]of[- ]thought)', summary):
        raise ValueError('private-context-not-accepted')
    kind = packet.get('kind')
    if kind in {'permission', 'confirmation'}:
        if set(packet) != {'schemaVersion', 'candidateId', 'kind', 'summary', 'instructionRef', 'sourceRefs'} or not isinstance(packet['sourceRefs'], list) or not 1 <= len(packet['sourceRefs']) <= 8 or any(not isinstance(ref, str) or not ref.startswith(('user:', 'repo:')) for ref in packet['sourceRefs']):
            raise ValueError('review-candidate-shape')
        record = {'schemaVersion': 'awx.rule-review-request.v1', 'candidateId': key, 'kind': kind,
                  'summary': summary, 'instructionRef': latest_ref, 'taskRevision': expected_revision,
                  'sourceRefs': [safe_note(ref) for ref in packet['sourceRefs']], 'status': 'AWAITING_USER_CONFIRMATION',
                  'applicationAllowed': False, 'authorityChanged': False,
                  'requiredApplicationEvidence': 'official-rule-tool-and-in-app-approval-receipt'}
        relative = f'{REVIEWS}/{key}.json'
        immutable(safe_path(root, relative), record)
        return {'status': record['status'], 'path': relative, 'authorityChanged': False, 'reviewRequestPrepared': True, 'reviewRequestDelivered': False}
    fields = {'schemaVersion', 'candidateId', 'kind', 'summary', 'instructionRef', 'contractPath',
              'contractHash', 'taskRevision', 'stageId', 'redReceipt', 'greenReceipt', 'guidance', 'relatedIds'}
    if kind != 'project-technical' or set(packet) != fields:
        raise ValueError('technical-candidate-shape')
    guidance = packet['guidance']
    if not isinstance(guidance, dict) or set(guidance) != {'topic', 'steps', 'knowledgeRefs'} or not isinstance(guidance['steps'], list) or not 1 <= len(guidance['steps']) <= 20:
        raise ValueError('technical-guidance-shape')
    guidance = {'topic': safe_note(guidance['topic']), 'steps': [safe_note(step) for step in guidance['steps']], 'knowledgeRefs': guidance['knowledgeRefs']}
    if re.search(r'(?i)(permission|approval|authorization|confirmation|권한|승인|영구.*삭제|코드.*삭제|삭제.*코드|private[- ]?reasoning)', summary + ' ' + guidance['topic'] + ' ' + ' '.join(guidance['steps'])):
        raise ValueError('policy-content-requires-review')
    helpers = Path(__file__).resolve().parents[4] / 'scripts'
    if str(helpers) not in sys.path:
        sys.path.insert(0, str(helpers))
    from checkpoint_doctor import check_request_contract
    from run_verified_command import validate_bound_receipt
    contract_path = evidence_path(root, packet['contractPath'])
    doc = read_json(contract_path)
    checked = check_request_contract(doc, root=root, latest_ref=latest_ref, expected_revision=expected_revision)
    stages = [stage for stage in checked.get('stages', []) if stage['id'] == packet['stageId']]
    if checked['status'] == 'REJECTED' or len(stages) != 1 or stages[0]['status'] != 'READY':
        raise ValueError('request-contract-held-or-rejected')
    if packet['contractHash'] != checked['contractHash'] or packet['taskRevision'] != expected_revision:
        raise ValueError('stale-contract-candidate')
    stage = next(stage for stage in doc['stages'] if stage['id'] == packet['stageId'])
    facts = {item['id'] for item in doc['knowledge'] if item['kind'] == 'fact'}
    if not isinstance(guidance['knowledgeRefs'], list) or not guidance['knowledgeRefs'] or any(ref not in facts for ref in guidance['knowledgeRefs']):
        raise ValueError('guidance-needs-factual-basis')
    binding = {'taskId': doc['taskId'], 'revision': doc['revision'], 'instructionRef': latest_ref,
               'contractHash': checked['contractHash'], 'stageId': packet['stageId'],
               'sourceFiles': stage['sourceFiles'], 'testFiles': stage['testFiles']}
    receipts = []
    for phase, relative in [('RED', packet['redReceipt']), ('GREEN', packet['greenReceipt'])]:
        output = evidence_path(root, relative)
        receipt = validate_bound_receipt(output, binding=binding, expected_phase=phase, expected_root=root)
        if not receipt['ok'] or Path(receipt['report']['cwd']).resolve() != root:
            raise ValueError('invalid-' + phase.lower() + '-receipt')
        receipts.append(receipt['report'])
    red, green = receipts
    if red['runId'] == green['runId'] or datetime.fromisoformat(red['endedAt']) > datetime.fromisoformat(green['startedAt']) or red['argvSha256'] != green['argvSha256'] or red['contractBinding']['commandId'] != green['contractBinding']['commandId']:
        raise ValueError('red-green-command-or-order-mismatch')
    if len(stage['successTests']) != 1 or green['contractBinding']['commandId'] != stage['successTests'][0]['commandId']:
        raise ValueError('all-success-tests-must-be-covered-by-one-focused-command')
    red_hashes = {item['path']: item['sha256'] for item in red['sourceIdentityEnd']}
    hashes = {item['path']: item['sha256'] for item in green['sourceIdentityEnd']}
    if any(red_hashes.get(path) != hashes.get(path) for path in stage['testFiles']):
        raise ValueError('red-green-test-drift')
    for path in stage['sourceFiles'] + stage['testFiles']:
        if file_hash(evidence_path(root, path)) != hashes.get(path):
            raise ValueError('final-source-or-test-drift')
    catalog_path = safe_path(root, DEFAULT_CATALOG)
    catalog_hash = file_hash(catalog_path) if catalog_path.exists() else None
    if catalog_hash != expected_catalog_hash:
        raise ValueError('catalog-preimage-required-or-changed')
    annotations = read_catalog(catalog_path) if catalog_path.exists() else {'entries': [], 'relations': []}
    if not isinstance(packet['relatedIds'], list) or len(packet['relatedIds']) > 50 or any(not isinstance(key, str) for key in packet['relatedIds']):
        raise ValueError('related-entry-shape')
    scope = guidance_scope(root, annotations, stage['sourceFiles'] + stage['testFiles'], packet['relatedIds'])
    if scope['diagnostics']:
        raise ValueError('scoped-catalog-diagnostics')
    artifacts = {packet['contractPath']: file_hash(contract_path)}
    for name, receipt in zip(('redReceipt', 'greenReceipt'), receipts):
        for leaf in ['run.json', receipt['log']] + [item['path'] for item in receipt['resultFiles']]:
            relative = packet[name] + '/' + leaf
            artifacts[relative] = file_hash(evidence_path(root, relative))
    content = digest({'guidance': guidance, 'sourceHashes': hashes})
    record = {'schemaVersion': 'awx.project-guidance.v1', 'candidateId': key, 'kind': kind,
              'scope': 'project', 'status': 'REGISTERED', 'authorityChanged': False,
              'summary': summary, 'guidance': guidance, 'contentHash': content,
              'contractPath': packet['contractPath'], 'contractFileHash': file_hash(contract_path),
              'contractHash': checked['contractHash'], 'taskRevision': expected_revision,
              'instructionRef': latest_ref, 'stageId': packet['stageId'],
              'sourceFiles': stage['sourceFiles'], 'testFiles': stage['testFiles'], 'sourceHashes': hashes,
              'receipts': [{'path': packet[name], 'runId': receipt['runId'], 'sha256': file_hash(safe_path(root, packet[name] + '/run.json'))} for name, receipt in zip(('redReceipt', 'greenReceipt'), receipts)],
              'catalogScope': scope, 'relatedIds': packet['relatedIds'], 'evidenceHashes': artifacts}
    base = safe_path(root, GUIDANCE)
    base.mkdir(parents=True, exist_ok=True)
    with owned_lock(base):
        relative = f'{GUIDANCE}/{key}.json'
        target = safe_path(root, relative)
        if target.exists():
            prior = read_json(target)
            if {k: v for k, v in prior.items() if k != 'catalogScope'} != {k: v for k, v in record.items() if k != 'catalogScope'}:
                raise ValueError('guidance-identity-collision')
            return {'status': 'ALREADY_REGISTERED', 'path': relative, 'authorityChanged': False}
        for existing in sorted(base.glob('*.json')):
            prior = read_json(safe_path(root, existing.relative_to(root).as_posix()))
            if prior.get('contentHash') == content:
                return {'status': 'ALREADY_REGISTERED', 'path': existing.relative_to(root).as_posix(), 'authorityChanged': False}
            if str(prior.get('guidance', {}).get('topic', '')).casefold() == guidance['topic'].casefold():
                raise ValueError('guidance-topic-conflict')
        # Recheck current bytes immediately before exclusive publication, after all validation.
        if (file_hash(catalog_path) if catalog_path.exists() else None) != catalog_hash or file_hash(contract_path) != record['contractFileHash'] or any(file_hash(evidence_path(root, path)) != value for path, value in hashes.items()) or guidance_scope(root, annotations, stage['sourceFiles'] + stage['testFiles'], packet['relatedIds'])['snapshotHash'] != scope['snapshotHash']:
            raise ValueError('concurrent-evidence-change')
        for phase, name in [('RED', 'redReceipt'), ('GREEN', 'greenReceipt')]:
            fresh = validate_bound_receipt(evidence_path(root, packet[name]), binding=binding, expected_phase=phase, expected_root=root)
            if not fresh['ok'] or file_hash(safe_path(root, packet[name] + '/run.json')) != next(r['sha256'] for r in record['receipts'] if r['path'] == packet[name]):
                raise ValueError('concurrent-receipt-change')
        created = immutable(target, record)
        post_hash = file_hash(target)
        try:
            if fault:
                fault(target)
            if read_json(target) != record or file_hash(target) != post_hash:
                raise ValueError('registration-readback-drift')
        except Exception:
            if created and target.exists():
                if file_hash(target) != post_hash:
                    raise ValueError('rollback-postimage-drift')
                target.unlink()  # This operation exclusively created the unchanged artifact.
            raise
    return {'status': 'REGISTERED', 'path': relative, 'sha256': post_hash, 'authorityChanged': False,
            'globalDiagnosticCount': scope['globalDiagnosticCount'], 'scopedDiagnosticCount': 0}


def rollback_guidance(root, candidate_id, expected_hash):
    """Remove only the exact technical artifact named by an unchanged owned receipt."""
    path = safe_path(root, f'{GUIDANCE}/{guidance_id(candidate_id)}.json')
    if not path.is_file():
        raise ValueError('technical-guidance-not-registered')
    with owned_lock(path.parent):
        if file_hash(path) != expected_hash:
            raise ValueError('rollback-postimage-drift')
        record = read_json(path)
        if record.get('kind') != 'project-technical' or record.get('authorityChanged') is not False:
            raise ValueError('policy-application-not-supported')
        recovery = safe_path(root, f'{GUIDANCE}/recovery/{path.stem}-{expected_hash}.json')
        immutable(recovery, record)
        if file_hash(path) != expected_hash:
            raise ValueError('rollback-postimage-drift')
        path.unlink()
    return {'status': 'ROLLED_BACK', 'recoveryPath': recovery.relative_to(root).as_posix(), 'authorityChanged': False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['register', 'candidate', 'run', 'compare', 'status', 'promote', 'rollback', 'register-guidance', 'rollback-guidance'])
    p.add_argument('--root', default=str(Path(__file__).resolve().parents[4]))
    p.add_argument('--campaign')
    p.add_argument('--packet', help='Bounded agent-curated candidate JSON; no raw sessions')
    p.add_argument('--instruction-ref')
    p.add_argument('--expected-revision', type=int)
    p.add_argument('--expected-catalog-hash')
    p.add_argument('--expected-guidance-hash')
    p.add_argument('--cases')
    p.add_argument('--spec', help='Repo-relative JSON evaluator specification')
    p.add_argument('--policy', help='Optional frozen JSON metric policy')
    p.add_argument('--hypothesis', default='')
    p.add_argument('--changed-basis', default='')
    p.add_argument('--candidate')
    p.add_argument('--parent')
    p.add_argument('--run-id')
    p.add_argument('--split', choices=['development', 'confirmation'], default='development')
    p.add_argument('--case-ids', nargs='+')
    p.add_argument('--expected-pointer-hash')
    p.add_argument('--reason', default='')
    a = p.parse_args()
    root = Path(a.root).resolve()
    if a.action not in {'register-guidance', 'rollback-guidance'} and not a.campaign:
        p.error('--campaign is required for experiment actions')
    if a.action == 'register-guidance':
        result = register_guidance(root, read_json(evidence_path(root, a.packet)), a.instruction_ref, a.expected_revision, a.expected_catalog_hash)
    elif a.action == 'rollback-guidance':
        result = rollback_guidance(root, a.candidate, a.expected_guidance_hash)
    elif a.action == 'register':
        result = register(root, a.campaign, a.cases, read_json(safe_path(root, a.spec)), a.hypothesis, read_json(safe_path(root, a.policy)) if a.policy else None)
    elif a.action == 'candidate':
        result = add_candidate(root, a.campaign, a.candidate, read_json(safe_path(root, a.spec)), a.hypothesis, a.changed_basis, a.parent)
    elif a.action == 'run':
        report = run(root, a.campaign, a.candidate, a.run_id, a.split, a.case_ids)
        result = {k: report[k] for k in ('runId', 'split', 'decision', 'errorAnalysis')}
    elif a.action == 'promote':
        result = promote(root, a.campaign, a.run_id)
    elif a.action == 'compare':
        base, config, state = load(root, a.campaign)
        report = read_json(base / f'runs/{identifier(a.run_id)}/report.json')
        rows = read_json(base / f'runs/{a.run_id}/measurements.json')
        if digest(report) != state['runs'][a.run_id]['reportHash'] or digest(rows) != report['rowsHash']:
            raise ValueError('changed-comparison-evidence')
        result = compare(rows['baseline'], rows['candidate'], config['policy'])
    elif a.action == 'rollback':
        result = rollback(root, a.campaign, a.expected_pointer_hash, a.reason)
    else:
        base, config, result = load(root, a.campaign)
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))


if __name__ == '__main__':
    main()
