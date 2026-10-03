# 점(dot)으로 Codex 지시서 "작성+저장" 한 번에 끝내기 — 사용자 카드

## 왜 막혔나

점이 대신 보낸 **후속** "저장해줘"는 첫 요청에 없던 범위라 Codex의 자동 승인
검토가 거절했다. 공식 문서도 "특정 지시는 그 범위 안의 후속 행동만 커버한다;
범위 밖 행동은 새 결정이 필요하다"고 설명한다(learn.chatgpt.com/docs/dots/controls).
`approval_policy="never"` + `danger-full-access`라도 위임 범위 밖이면 막힌다.

## 이제 이렇게 — 점에게 이 문장 틀로 맡긴다 (복사용)

```
[DOT-BRIEF] 코덱스한테 <대상 에이전트> <주제> 지시서 써달라고 해줘.
이 요청 범위: 지시서 작성 + `python -B scripts/dot_brief_save.py save --agent <AGENT> --topic <topic> --from <임시파일>`로 Downloads와 agent-prompts 두 곳에 저장 + 크기·sha 보고까지.
저장 외 쓰기·삭제·전송·소스 수정은 범위 밖.
```

`<AGENT>`는 대문자(`DEVIN`, `GROK` …), `<topic>`은 `소문자-하이픈`(예: `skill-harmony`),
날짜는 자동으로 오늘(`--date`로 바꿀 수 있음). 첫 메시지에 태그+저장 범위가 있어서
후속 승인 없이 저장까지 간다. 같은 이름 파일이 있으면 `_v2`로 새로 저장되고 덮어쓰기는 없다.

## 그래도 막히면

1. Codex 앱에서 그 스레드를 열어 승인 요청에 직접 응답한다. 또는
2. Temp에 남은 파일을 한 줄로 회수:

   ```
   python -B scripts/dot_brief_save.py rescue --apply
   ```

   (기본 24시간 내 `%TEMP%\demo1-*\PASTE_*.txt`만 본다. `--apply` 없이 쓰면 목록만.)

## 점 쪽 맞춤 지침 (선택, 사용자가 직접 설정)

점 프로필 → Settings > Personalization → Permissions 아래 **Custom rules** → Add에
이 3줄을 붙여 넣는다:

```
코덱스에 지시서 작성을 맡길 때는 첫 메시지에 [DOT-BRIEF] 태그와
저장 범위(scripts/dot_brief_save.py save 한 번)를 함께 넣는다.
저장 이외의 쓰기·삭제·전송은 요청하지 않는다.
```

처리 방식은 "Take action when you say so" 권장(무분별 자동 실행 방지). 안 넣어도
위 문장 틀만으로 동작한다 — 규칙은 선택 사항이다.

## 점과 상관없는 대화에는 영향 없음 (꼬임 0)

- 바뀐 전역·레포 지침 파일: **없음.** `~/.codex/AGENTS.md`, `config.toml`,
  `rules/*`, 레포 `.codex/hooks.json`, `AGENTS.md`, `scripts/brief_save.py`,
  `~/.gemini/*` 의 sha256이 작업 전 baseline과 동일함을 검증했다.
  (근거: `data/agent-handoff/devin-codex-dot-brief-save-d98c2c8c/forbidden-baseline.json`)
- 새 파일만 추가됨: `scripts/dot_brief_save.py`, `scripts/test_dot_brief_save.py`,
  `.agents/skills/dot-brief-save/SKILL.md`, 이 카드. 스킬은 `[DOT-BRIEF]` 태그가
  있을 때만 선택되는 description 게이트라 일반 대화에는 켜지지 않는다.
- 주의: 레포에 스킬이 100개를 넘어 Codex의 skills 컨텍스트 예산(2%)을 넘기면
  스킬 목록 자체가 생략될 수 있다(실측 경고 확인). 그래도 문장 틀에 명령이
  그대로 적혀 있으므로 저장 경로는 문장만으로 완결된다 — 스킬은 보조다.

## 되돌리기 (사용자가 직접, 한 줄)

```
Remove-Item scripts\dot_brief_save.py, scripts\test_dot_brief_save.py, docs\codex\DOT_BRIEF_SAVE_KO.md; Remove-Item -Recurse .agents\skills\dot-brief-save
```

## 처음 한 번 시험해 볼 문장

```
[DOT-BRIEF] 코덱스한테 DEVIN dot-save-smoke 지시서 써달라고 해줘. 이 요청 범위: 지시서 작성 + `python -B scripts/dot_brief_save.py save --agent DEVIN --topic dot-save-smoke --from <임시파일>`로 Downloads와 agent-prompts 두 곳에 저장 + 크기·sha 보고까지. 저장 외 쓰기·삭제·전송·소스 수정은 범위 밖.
```
