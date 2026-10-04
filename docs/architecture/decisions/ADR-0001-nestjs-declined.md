---
id: ADR-0001
title: Reuse Spring controllers and Next BFF for HTTP work
status: DECLINED
date: 2026-10-04
approvedBy: user
tech: nestjs
context: Keep the Java Spring Boot 3 + Next BFF + RAG portfolio story coherent
decision: Decline a new NestJS layer until a separate Node product service is justified
alternatives: Spring controller and existing Next POST route handler
revisitWhen: 별도 Node 서비스가 실제 제품 요구가 되고 사용자가 승인할 때
---

## Context and evidence

The task objective records plan16's proposed Nest layer, localhost HTTP contact,
and BullMQ/Redis as a held seam. Current source already owns HTTP work in
`main/java/com/example/lms/api/ChatApiController.java:96` and
`frontend/src/app/api/chat/sync/route.js:6`.

`docs/agents-rules/DEMO1-PROTOTYPE-LIGHT.md:8` excludes new SaaS accounts,
daemons and background watchers. `docs/agents-rules/DEMO1-COOP-VERIFY-RAILS.md:9`
excludes a new state server. These are scoped operating constraints, not a claim
that every Node library is forbidden. The existing Soniox sidecar is declared at
`main/resources/soniox-sidecar/package.json:7` and remains a valid existing owner.

## Decision and alternatives

Use Spring for backend endpoints and the existing Next route for BFF forwarding.
For verification state, reuse `scripts/coop_verify.py:6` and
`scripts/work_journal.py:10`. Continue independent work when a new Nest seam is
declined. No empty Nest folder, service wrapper, or actual install is required.

The guard normally returns ADAPT for a new Nest request with these alternatives.
This DECLINED ADR records the architectural decision; it grants no OVERRIDE.
An explicit user instruction to proceed produces a separate ACCEPTED/user ADR
for `tech: nestjs` and concrete scope. It does not bypass unrelated safety gates.

## Verification and recovery

Offline tests cover Nest text, dependency and marker detections; nested-word and
read-only comparison cases remain FIT. No new server/dependency is installed.
Operational behavior of existing optional services is not_observed in this task.
Retire an accepted override with SUPERSEDED when its scope no longer applies.
