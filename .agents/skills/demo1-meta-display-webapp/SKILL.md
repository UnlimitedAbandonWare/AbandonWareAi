---
name: demo1-meta-display-webapp
description: Use when starting or coordinating a Meta Ray-Ban Display RAG webapp task in demo-1
---

# Meta Ray-Ban Display 작업 진입

첫 목표는 질문/preset → 기존 sync → 처리 상태 → 답변·출처 카드 → 방향키 이동이다. 이 스킬은 현재 요청에 맞는 역할 하나를 선택한다. 실제 단계의 판단과 검증은 선택한 역할이 맡는다.

## 역할 선택

| 현재 작업 | 사용할 역할 |
|---|---|
| E0 접수, 중단된 작업, 다음 단계, 누락·오래된 기록 | [demo1-meta-display-resume](../demo1-meta-display-resume/SKILL.md) |
| E1 요청/응답 처리, E2 카드·입력·focus 구현이나 client 결함 | [demo1-meta-display-sync-client](../demo1-meta-display-sync-client/SKILL.md) |
| Display 서버 켜기, Ollama 자동 시작, 시작 전 필수 서비스 진단 | [demo1-meta-display-verification](../demo1-meta-display-verification/SKILL.md)의 서버 시작 절차 |
| E3 실제 Spring sync/세션, E4 공식 Simulator, 완료 증거 판단 | [demo1-meta-display-verification](../demo1-meta-display-verification/SKILL.md) |
| 브라우저에서 핵심 동선·실패 경로를 반복 재현하고 최소 수정·회귀 확인 | [실제 검증 역할](../demo1-meta-display-verification/SKILL.md)의 [browser-repair 절차](../demo1-meta-display-browser-repair/SKILL.md) |
| Fold 안경 표시 설정: 전사/힌트 유지, 페이지 간격, **힌트 생성 주기**(20s hold · 2.5s quiet · 10s cooldown · 180s force는 기본값) | [demo1-meta-display-simple-caption](../demo1-meta-display-simple-caption/SKILL.md) — 기존 `#lens-display`에 연결·저장·복원. resume이 아님. YAML 초 단위는 고정 계약이 아니다 |

이미 단계가 명확한 요청은 해당 역할로 바로 들어간다. 처음 여러 단계가 섞인 요청은 resume로 현재 상태를 확인한 뒤 다음 역할로 이어간다. 같은 selector 결과와 근거를 재사용하고 세 역할이 동일한 심사를 반복하지 않는다. E1/E2의 집중 검증은 구현 역할에 포함된다.

## 공통 경계

- 현재 사용자 요청과 기존 source-owner gate가 소스 변경을 소유한다. 이미 승인된 구현은 최소 패치·집중 검증·rollback까지 이어가며 같은 지시나 승인을 다시 요구하지 않는다. 분석/상태/스킬 준비 요청은 그 범위를 지킨다.
- 현재 계약은 [source-contract.md](references/source-contract.md), 단계 기록은 [continuation-contract.md](references/continuation-contract.md), 실행 대상은 [작업서](../../../agent-prompts/meta_rayban_display_sync_v1_20260912.md)를 공유한다. 실제로 필요한 항목만 읽는다.
- 기존 `/api/chat/sync`, DTO, cookie 소유권, PromptBuilder, SSE와 source guard를 재사용한다. 새 BFF/API/provider/debugger/watcher 또는 중복 승인 절차를 만들지 않는다. 내부 모순은 같은 증거에서 반증하고 해당 경계만 고친다.
- 대상은 Meta Ray-Ban Display다. 화면 없는 Gen1/Gen2, DAT raw mic/camera, 공개 HTTPS·기기 구매는 이 첫 client 작업의 자동 후속 범위가 아니다.
- 자동 선택은 Desktop 실행 증명이 아니다. Notebook은 supporting-only이며 E0 접수는 실제 Desktop의 기존 canonical intake를 따른다. selector가 권한을 만들거나 새 patch를 실행하지 않는다.

## 운영 계약

입력: `userScope, selectedStage, currentRoot, existingEvidence, verificationBudget`.
출력: `selectedRole, selectedStage, reusedEvidence, nextSingleProof, desktopFinalProof`.

이 진입점은 판단만 하며 새로운 상태 파일이나 소스를 쓰지 않는다. 기본 조사 15분/20개 근거, 진단 30초, 보고 30행이며 구현 시간은 현재 GoalContract를 따른다. 근거가 충분하면 곧바로 해당 역할로 이동한다.

원문 질문/답변/cookie/token/internal path를 보고서에 남기지 않고 기존 secret scanner의 count/hash/reason을 사용한다. 소유권·preimage·secret 위반은 해당 변경만 fail-closed, Simulator/서버 부재는 해당 실행 단계만 보류한다. 과거 PASS나 파일 존재만으로 provider·실기기·Desktop 완료를 주장하지 않는다.

분리된 역할은 기존 단일 스킬의 구현·재개·실행 검증 책임을 나눈 것이다. 공통 계약·선택기·검증 도구를 복제하지 않았다. 복구는 이번 역할 파일과 라우팅·task binding 변경에 한정하고 기존 소스·실행 근거를 보존한다. 진입점/역할 문서가 바뀌면 selector의 taskBinding이 달라져 기존 기록의 재확인을 요구한다.

반증 사례: 단순 다음 단계 조회에서 client를 수정하거나, client 구현마다 세 역할에 같은 fixture를 반복시키거나, 600×600 preview만으로 E4 완료를 주장하면 역할 선택을 수정한다.
