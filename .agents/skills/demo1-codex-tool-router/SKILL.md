---
name: demo1-codex-tool-router
description: Use when Codex runs the R2/Jev hotfix audit and must pick exactly one judgement tool per step (baseline, ZIP anchors, Gradle truth, web repro, log redaction)
---

# Demo1 Codex Tool Router (hotfix R2 scabbard)

Principle: the tool stays sheathed until its step arrives — pull exactly one
per phase. Pair with `docs/codex/HOTFIX_R2_TOOLKIT.md` for the full command
index. All paths below are absolute under
`<repo>`; every script accepts `--root <tree>` so a
Codex worktree can probe a different checkout without writing to it.

| 단계 | 뽑는다 | 뽑지 않는다 |
|---|---|---|
| 착수 | `python -B scripts/baseline_sync_probe.py --root <tree> --expect-head 4150b2822f2c --expect-branch codex/owned-runtime-browser-restart` | GitHub snapshot을 로컬보다 우선 |
| 원인 탐침 | Superpowers systematic-debugging, 원인 1개씩 | 새 서비스·새 계층 |
| 경로 확인 | `python -B scripts/zip_path_to_sourceset.py --root <tree> --directive <md> --zip %USERPROFILE%\Downloads\mfwasainx.zip` | ZIP 줄번호 그대로 인용 |
| RED/GREEN | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\gradle_truth_gate.ps1 -GradleArgs "test --tests <fqcn> --rerun-tasks"` (`--tests` 필수) | 전체 suite, wrapper exit만 보고 |
| 빌드 실패 분류 | `python -B scripts/log_redact.py --in <log> --out <san> --spine-only` → `python -B tools/build_error_miner.py scan --in <san> --out <pfx>` | 복구용 AWX(기본이 정상일 때), 원본 스택 재확인 생략 |
| 공식 규격 | Exa, 도메인 제한(docs.spring.io, playwright.dev, docs.github.com, docs.oracle.com, vercel.com, docs.ollama.com, h2database.github.io) + 확인일·적용 버전 기록 | 블로그 예제 이식 |
| 웹 재현 | `python -B scripts/web_repro_matrix.py --port 18180` | 저장된 인증 상태로 로그인 성공 주장 |
| 로컬 UI 조작 | Computer (Browser로 못 여는 것만) | 소스 탐색·수정에 Computer |
| 1차 수정 후 | GLM 반박 검토 per `$demo1-glm-route-guard` (`docs/codex/glm-rebuttal-review-template.md`; native glm_worker spawn 금지 — ChatGPT 로그인 400; spawn 전 1회 `python -B scripts/spawn_preflight.py <role>`) | glm 동의 = 검증 성공 |
| Jev/Gateway | Vercel: env 이름 존재·로그인/OIDC 상태·공식 문서만 | 키 값 출력, 배포·설정·DNS 변경, env pull 파일 커밋 |

## 멈춤 규칙 (그 자리에서 보고, 다음 칼로 넘어가지 않는다)

- `BASELINE_MISMATCH` (baseline_sync_probe) — 어떤 트리를 기준으로 할지 사용자 확인
- `BASELINE_BLOCKED` (gradle_truth_gate) — 구성/컴파일 실패는 RED로 세지 않음
- `SERVER_DOWN` (web_repro_matrix) — 서버를 대신 띄우지 않음
- Vercel `auth-blocked` — 자격 증명이 없다는 판정이지 대상 결함이 아님

## 경계

- 이 스킬은 도구 선택만 담당한다. plugin on/off SSOT는 `$demo1-codex-plugin-roles`,
  소스 수정 순서/lease는 `demo1-work-ledger` + `source_edit_session.ps1`,
  빌드 로그 패턴 카탈로그는 `tools/build_error_miner.py`가 각각 소유한다.
- 제품 소스·테스트·`*.gradle*`·`application*.yml`을 이 스킬 경로로 고치지 않는다.
