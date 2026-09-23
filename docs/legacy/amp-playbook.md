# AMP Playbook for src54core

This playbook outlines recommended practices for interacting with the
augmented `src54core` system in a production environment.

## Path‑Conformity Scoring

* Enable the multiplier via `scoring.path-alignment.enabled=true` in
  `application.yml` (on by default). This feature rewards answers whose
  decision paths align with previously reinforced trajectories.
* The multiplier can dramatically boost or penalise contextual scores; use
  with caution when little historical data exists.
* Reinforced paths are persisted by `NeuralPathFormationService` when
  consistency scores exceed `path.formation.threshold` (default 0.9).

## Version Purity Checks

* All `dev.langchain4j` dependencies must resolve to version 1.0.1.
* The Gradle task `versionPurityCheck` runs automatically during
  `check`; failures print offending coordinates and abort the build.

## MoE Routing and Disambiguation

* The integration does not modify the existing model routing logic but
  emphasises that high uncertainty, high complexity or high impact queries
  should trigger an escalation to a high‑tier model. Ensure that
  `router.moe.high` and `router.moe.mini` are set appropriately in your
  configuration.

## Failure Softness

* Retrieval handlers should never propagate exceptions; partial results must
  be aggregated and returned even when downstream services fail.

## Further Enhancements

* Future work may include the addition of a `KGHandler` and further
  disambiguation logic as described in the full specification. Those
  components were not implemented in this iteration.
