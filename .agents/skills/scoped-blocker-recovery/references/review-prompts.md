# Existing three-query preflight prompts

Use one frozen, redacted evidence snapshot. Keep scenario IDs aligned with the
repository preflight. Record decisions and short evidence reasons, not private
reasoning transcripts. Do not add another review or decide by majority vote.

**POSITIVE_QUERY:** Find the smallest work permitted by current evidence and user
authorization. State its actual resource dependencies, expected benefit and proof
needed. Do not assume environment recovery or acceptance success.

**NEGATIVE_QUERY:** Challenge the same scenarios with observable counterexamples
for concurrent writes, wrong ownership, secret exposure, excess external requests
and false completion. Specify the condition that makes each failure possible and
the smallest probe that could disconfirm it.

**NEUTRAL_QUERY:** Compare both packets using actual dependencies and frozen
evidence. Decide permitted work, held scope and next observation. Mark unsupported
claims `evidence_needed`. Evaluate positive-negative and negative-positive order;
require the same verdict and decisive evidence set, not agreement of every opinion.
