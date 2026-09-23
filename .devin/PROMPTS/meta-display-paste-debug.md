# Meta Ray-Ban Display — 디버그/증거용 (복붙)

힌트가 안 뜨거나 증거/검색/라이브 재현이 필요할 때만.

@objective-executor @demo1-devin-source-orchestrator @meta-rayban-display @frontend-display-debug @demo1-conversate-hint-context @demo1-meta-display-simple-caption @demo1-evidence-debugging @safe-source-edit @demo1-agent-api-spend-guard

폴드/안경으로 재현한 뒤에는 패치 전에:
`python -B scripts/devin_task_orchestrate.py capture --role wear --invoke --task <이 세션 taskId>`
`data/agent-handoff/display-debug/latest.json` 의 `patchHints`를 읽고 그 이음새만 고친다. var/debug 만 보고 패치하지 말 것.

붙여넣은 긴 브리프는 먼저 `python -B scripts/devin_task_orchestrate.py plan --brief-file <path>`.

라이브 ForceRestart는 계획에만 넣고 실행 전 확인. 최소 diff.

목적:
