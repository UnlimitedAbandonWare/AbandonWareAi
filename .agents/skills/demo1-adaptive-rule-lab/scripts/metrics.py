"""Paired case metrics with conservative finite-sample promotion evidence."""
import math
import statistics
import random

DEFAULT_POLICY = {'schemaVersion': 'awx.rule.metrics.v1', 'taskType': 'query', 'latencyTargetMs': 50.0, 'latencyCapMs': 1000.0, 'minEffectPoints': 3.0, 'familyAlpha': 0.05, 'maxConfirmationLooks': 9, 'maxCandidates': 3, 'maxRevisions': 3, 'maxCampaignSeconds': 900, 'maxEvaluationSeconds': 60, 'maxLatencyRegressionMs': 25.0, 'qualityTolerance': 0.0, 'successTolerance': 0.0, 'errorTolerance': 0.0}


def number(value, name, low=0, high=None):
    if type(value) not in (int, float) or not math.isfinite(value) or value < low or (high is not None and value > high):
        raise ValueError('invalid-' + name)
    return value


def validate_policy(policy):
    for field in DEFAULT_POLICY:
        if field not in policy:
            raise ValueError('missing-policy-' + field)
    for field in ('latencyTargetMs', 'latencyCapMs', 'minEffectPoints', 'maxLatencyRegressionMs', 'qualityTolerance', 'successTolerance', 'errorTolerance'):
        number(policy[field], field)
    if policy['latencyTargetMs'] >= policy['latencyCapMs'] or policy['taskType'] not in ('query', 'debugging'):
        raise ValueError('invalid-policy')
    number(policy['familyAlpha'], 'familyAlpha', 0.000001, 0.1)
    for field in ('maxConfirmationLooks', 'maxCandidates', 'maxRevisions', 'maxCampaignSeconds', 'maxEvaluationSeconds'):
        if type(policy[field]) is not int or policy[field] < 1:
            raise ValueError('invalid-' + field)


def validate_rows(rows):
    if not isinstance(rows, list) or not rows:
        raise ValueError('missing-measurements')
    seen = set()
    for row in rows:
        for key in ('caseId', 'attemptId', 'status'):
            if not isinstance(row.get(key), str) or not row[key]:
                raise ValueError('missing-' + key)
        if row['caseId'] in seen:
            raise ValueError('duplicate-independent-case')
        seen.add(row['caseId'])
        if type(row.get('success')) is not bool or type(row.get('debugVerified')) is not bool:
            raise ValueError('invalid-boolean')
        number(row.get('quality'), 'quality', 0, 1)
        number(row.get('latencyMs'), 'latencyMs')
        if row['status'] not in ('ok', 'failed', 'timeout', 'error'):
            raise ValueError('invalid-status')
        if row['status'] != 'ok' and (row['success'] or row['quality'] or row['debugVerified']):
            raise ValueError('failed-attempt-cannot-pass')
        if row['status'] in ('timeout', 'error') and not row.get('errorClass'):
            raise ValueError('missing-error-class')
        if type(row.get('capabilityLoss', False)) is not bool:
            raise ValueError('invalid-capability-loss')


def utility(row, policy):
    if row['status'] != 'ok' or not row['success']:
        return 0.0
    target, cap = policy['latencyTargetMs'], policy['latencyCapMs']
    return max(0.0, min(1.0, (cap - row['latencyMs']) / (cap - target)))


def case_score(row, policy):
    quality = row['quality'] if policy['taskType'] == 'query' else float(row['debugVerified'])
    return 100 * (0.5 * row['success'] + 0.3 * quality + 0.2 * utility(row, policy))


def percentile(values, quantile):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * quantile) - 1)]


def summarize(rows, policy=DEFAULT_POLICY):
    validate_policy(policy)
    validate_rows(rows)
    return {'n': len(rows), 'successRate': statistics.mean(r['success'] for r in rows), 'errorRate': statistics.mean(not r['success'] for r in rows), 'quality': statistics.mean(r['quality'] for r in rows), 'debugVerifiedRate': statistics.mean(r['debugVerified'] for r in rows), 'latencyUtility': statistics.mean(utility(r, policy) for r in rows), 'latencyP50Ms': percentile([r['latencyMs'] for r in rows], .5), 'latencyP95Ms': percentile([r['latencyMs'] for r in rows], .95), 'latencyCensored': any(r['status'] == 'timeout' for r in rows), 'score': statistics.mean(case_score(r, policy) for r in rows), 'denominator': 'all-independent-cases-including-failures-and-timeouts'}


def compare(baseline, candidate, policy=DEFAULT_POLICY):
    a, b = summarize(baseline, policy), summarize(candidate, policy)
    am, bm = {r['caseId']: r for r in baseline}, {r['caseId']: r for r in candidate}
    if am.keys() != bm.keys():
        raise ValueError('unpaired-populations')
    differences = [case_score(bm[key], policy) - case_score(am[key], policy) for key in sorted(am)]
    delta = statistics.mean(differences)
    alpha = policy['familyAlpha'] / policy['maxConfirmationLooks']
    # Hoeffding: paired differences are bounded in [-100,100]. Valid without
    # normality or bootstrap variance, assuming independently sampled case units.
    radius = 200 * math.sqrt(math.log(1 / alpha) / (2 * len(differences)))
    lower = max(-100.0, delta - radius)
    reasons = []
    if any(r.get('capabilityLoss', False) for r in candidate):
        reasons.append('protected-capability-loss')
    for metric, tolerance, reason in [('quality', 'qualityTolerance', 'quality-regression'), ('successRate', 'successTolerance', 'success-regression'), ('debugVerifiedRate', 'qualityTolerance', 'debug-regression')]:
        if b[metric] + policy[tolerance] < a[metric]:
            reasons.append(reason)
    if b['errorRate'] > a['errorRate'] + policy['errorTolerance']:
        reasons.append('error-regression')
    if b['latencyP95Ms'] > a['latencyP95Ms'] + policy['maxLatencyRegressionMs']:
        reasons.append('latency-regression')
    if lower <= policy['minEffectPoints']:
        reasons.append('insufficient-effect-evidence')
    rng = random.Random(1939)
    boot = [statistics.mean(rng.choices(differences, k=len(differences))) for _ in range(1000)]
    wins = sum(bm[k]['success'] and not am[k]['success'] for k in am)
    losses = sum(am[k]['success'] and not bm[k]['success'] for k in am)
    discordant = wins + losses
    binary_p = sum(math.comb(discordant, k) for k in range(wins, discordant + 1)) / (2 ** discordant) if discordant else 1.0
    return {'eligible': not reasons, 'reasons': reasons, 'baseline': a, 'candidate': b, 'meanDifference': delta, 'lowerBound': lower, 'confidenceMethod': 'one-sided-Hoeffding-paired-independent-cases', 'pairedBootstrapDiagnostic': {'lower': percentile(boot, alpha), 'upper': percentile(boot, 1 - alpha), 'seed': 1939, 'replicates': 1000, 'degenerate': len(set(differences)) == 1, 'authorizesPromotion': False}, 'pairedBinaryDiagnostic': {'candidateOnlySuccess': wins, 'baselineOnlySuccess': losses, 'oneSidedExactMcNemarP': binary_p, 'authorizesPromotion': False}, 'alphaPerLook': alpha, 'familyAlpha': policy['familyAlpha'], 'minEffectPoints': policy['minEffectPoints'], 'uncertaintyScope': 'score magnitude only; guardrails check observed regressions, not equivalence of population rates', 'measuredImprovementScope': 'registered case population and frozen evaluator only'}
