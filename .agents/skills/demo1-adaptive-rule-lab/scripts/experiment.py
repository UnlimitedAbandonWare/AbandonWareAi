"""Persistent local experiment loop with frozen cases, budgets and task-only promotion."""
import argparse
import json
import os
import platform
import time
from collections import Counter
from contextlib import contextmanager
from pathlib import Path

from evaluators import pin, check_pin, evaluate
from labio import digest, file_hash, identifier, immutable, read_json, safe_path, safe_note, utcnow, write_json
from metrics import DEFAULT_POLICY, compare, summarize, validate_policy

BASE = 'data/agent-handoff/adaptive-rule-lab/campaigns'


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


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['register', 'candidate', 'run', 'compare', 'status', 'promote', 'rollback'])
    p.add_argument('--root', default=str(Path(__file__).resolve().parents[4]))
    p.add_argument('--campaign', required=True)
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
    if a.action == 'register':
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
