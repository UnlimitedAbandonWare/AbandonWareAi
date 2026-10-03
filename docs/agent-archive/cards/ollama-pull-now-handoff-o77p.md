# Desktop Ollama pull-now: canonical-root/reparse 가드 + 리터럴 'Plan'/'Execute'만 허용 + digest-pinned 4개 모델
- card-id: ollama-pull-now-handoff-o77p
- kind: repro
- status: 확인 필요 (results/*.json은 전부 status=HOLD — 실제 Execute가 끝났는지 미확인)
- date: 2026-08-02 KST
- evidence: data/agent-handoff/model-autopilot/desktop-pull-now-v1/DESKTOP_OLLAMA_PULL_NOW.md (+ DesktopOllamaPullNow.psm1, desktop_ollama_pull_now.ps1)
- reverify: `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\data\agent-handoff\model-autopilot\desktop-pull-now-v1\desktop_ollama_pull_now.ps1 -Mode Plan`

## 근거
- 권한 범위: 고정 4개 모델 다운로드만. 삭제/retag/역할 승격/env 편집/daemon/Git/자격증명 불가.
- `-Mode`는 정확한 대소문자 리터럴 `Plan`/`Execute`만; Execute만 POST 허용.
- 진입점이 canonical Desktop root 밖이나 reparse 경로면 provider 호출 전 거부.
- 카탈로그(고정): qwen3.5:9b(fast), gemma4:12b(fast), + main 레인 2종 — 각 full digest pin.
