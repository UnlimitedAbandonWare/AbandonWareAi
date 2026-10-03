# Codex 조수 서브에이전트 (한 장 카드)

소스 수정은 Codex 본체만 한다. 옆에서 **브라우저 검증·원인 추적·라우팅 정리·
패치 검토**를 맡는 읽기/실행 전용 조수 4종이 `.codex/agents/`에 있다.

| 조수 | 하는 일 | 쓰기 |
|---|---|---|
| `assist_browser_verifier` | 로컬 `127.0.0.1:18180/chat`에서 지정 시나리오만 실행 → PASS/FAIL 표 + 스크린샷 + trace-dock 값 | 장부 `browser/` 산출물만 |
| `assist_rag_trace_analyst` | 실패 1건의 코드 경로 추적 → 원인 가설 1개 + file:line + 최소 반증 시험 | 없음 |
| `assist_routing_mapper` | 역할별 현재-vs-API우선 라우팅 표 (live 호출 0) | 없음 |
| `assist_patch_reviewer` | 본체 diff를 금지 목록과 대조 → `ACCEPT`/`REVISE` | 없음 |

## Codex에게 할 말 (예시)

```text
assist_browser_verifier로 C2 시나리오만 검증해 줘. 먼저
python -B scripts/codex_assist.py packet --role assist_browser_verifier
  --objective "C2 PASS/FAIL" --ledger <이 작업 장부>
를 돌리고 나온 delegateText로 위임해. 끝나면 collect + guard verify까지.
```

```text
assist_rag_trace_analyst가 C7 실패 원인을 하나만 찝어 줘.
```

```text
assist_patch_reviewer로 방금 diff를 검토해 줘.
```

## 상한

- 동시 실행 최대 2개. 같은 파일을 두 조수에 동시에 맡기지 않는다.
- 브라우저 전송: 위임 1건당 ≤10회, 유료 호출 0, 서버 재기동 0, 공개 URL 0.
- 조수 결과는 증거일 뿐 — 최종 판단·수정·커밋은 본체가 직접 재확인 후에만.

## 되돌리는 법 (전부 신규 파일 — 삭제로 끝)

```powershell
Remove-Item .codex\agents\assist_*.toml, scripts\codex_assist.py, scripts\codex_assist_guard.py,
  scripts\test_codex_assist.py, scripts\test_codex_assist_guard.py,
  docs\codex\CODEX_ASSIST_SUBAGENTS_KO.md, docs\codex\ASSIST_BRIEF_CLAUSE.txt -Force
Remove-Item .agents\skills\demo1-codex-assist-subagents -Recurse -Force
```

## 만약 Codex가 "agent type is currently not available"라고 하면

일부 버전에서 프로젝트 범위 에이전트 spawn이 실패한다(openai/codex#26408).
그 경우 **사용자가 직접** 4개 파일을 전역 위치로 복사하면 된다(본체/Devin은
전역을 건드리지 않는다):

```powershell
Copy-Item .codex\agents\assist_*.toml "$env:USERPROFILE\.codex\agents\"
```
