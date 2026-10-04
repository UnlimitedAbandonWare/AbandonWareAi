---
id: ADR-NNNN
title: Decision title
status: DECLINED
date: YYYY-MM-DD
approvedBy: agent
tech: canonical-stack-fit-tech
context: Problem and verified existing owner
decision: Scoped decision
alternatives: Smallest existing alternative and file:line
revisitWhen: Concrete product requirement and explicit user approval
---

Status values: DECLINED | ACCEPTED | SUPERSEDED (case-insensitive to the guard).
An override requires ACCEPTED + approvedBy: user + the exact SSOT `tech`.
Multiple explicitly approved technologies may be comma-separated in `tech`.
Do not mark agent preference or a technology mentioned in prose as user approval.
Record date, evidence, scope, acceptance criteria, and reversible recovery below.
Retiring an accepted decision requires marking that ADR SUPERSEDED.

## Context and evidence

Describe the actual product problem and current `file:line` owner.

## Decision and alternatives

Explain the scoped outcome and the smallest existing alternative.

## Verification and recovery

State observed results separately from not_observed evidence.
