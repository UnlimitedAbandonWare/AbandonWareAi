# Grokbot default Project Root — copy blocks

자동 세션·새 채팅·새 에이전트/채널에서도 동일 루트를 쓰게 하는 복붙용 문구 모음.
SSOT는 `AGENTS.md` `DEMO1-PROJECT-ROOT` + `.agents/skills/demo1-project-root/SKILL.md`.
이 파일은 프로필/디스크립션에 넣을 문구만 제공한다 — Grok 앱 UI나 계정 설정은 손대지 않는다.

## Grok agent / channel description (KR, 1줄)

```
Project Root = C:\AbandonWare\demo-1\demo-1\src — 조사·검색·편집·Start-RAG·검증·핸드오프의 기본 cwd. 새 채팅/자동 세션도 동일. 첨부(`%USERPROFILE%\.codex\attachments`), Downloads, ZIP `main/`은 읽기 전용 입력이며 루트가 아니다. 예외는 사용자 명시 경로 또는 스킬의 bounded sibling뿐.
```

## Grok profile / memory line (EN, 1줄)

```
Default project root: C:\AbandonWare\demo-1\demo-1\src — all investigation, search, edits, Start-RAG, verification and handoffs, including auto-created sessions; attachment/Downloads/ZIP dirs are read-only inputs, never the root; override only on an explicit user path or a skill's bounded sibling.
```

## 참고

- Grok CLI를 이 루트에서 시작하면 `.grok/rules/`(`demo1-bridge.md` 등)가 프로젝트 룰로 로드된다 (`trusted_folders.toml`에 이미 등록됨). 다른 cwd로 열린 자동 세션은 프로젝트 룰을 못 보니 위 프로필 한 줄이 fallback이다.
- Grok 워크스페이스 메모리(`~/.grok/memory-v2/workspaces/*/topics/demo1-project-root.md`)에도 같은 루트가 기록돼 있다 — 프로필 문구와 내용을 일치시킬 것.
- Start-RAG.bat은 `%~dp0` 자기고정이라 어느 cwd에서 불러도 루트를 다시 추측하지 않는다.
