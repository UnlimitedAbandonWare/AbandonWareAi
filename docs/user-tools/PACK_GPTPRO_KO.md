# Pack-GPTPro — 사용자 전용 GPT Pro 업로드용 소스 zip

> **[USER-ONLY]** 이 도구는 사용자 수동 전용이다. 에이전트(Codex/Devin/Grok/agy)는
> 사용자가 명시적으로 요청하지 않는 한 **실행·수정·자동 호출 금지**.

## 사용법 (3줄)

1. 레포 루트의 `Pack-GPTPro.bat` 더블클릭 → 기본 `core` 프로필로 zip 생성.
2. 또는 `Pack-GPTPro.bat main` / `full` / `core --dry-run`.
3. 끝나면 탐색기가 `zipHome`의 새 zip을 선택해 연다 → ChatGPT(GPT Pro)에 수동 업로드.

## 프로필 차이

| 프로필 | 내용 | 용도 |
|---|---|---|
| `main` | `main/java` + `main/resources`만 | 예전처럼 백엔드만 보낼 때 |
| `core` (기본) | main + Gradle 빌드 파일 + configs + frontend(src/scripts/설정) + AGENTS.md·README + docs 핵심 3종 | 전체 구조 분석 |
| `full` | core + scripts(코드만) + 전체 docs/*.md + src/test | 최대 범위 |

## 빠지는 것

- 비밀값: `.env*`(`.env.example`은 포함), `shared.env`, `apikey*`,
  `application-secrets*`, `.secrets/`, `auth.json`, `credentials*`, 키 재질
  (`.pem/.key/.p12/.pfx/.jks/.keystore`), DB·로그·zip·jar·이미지·영상.
- 내용 검사: 남을 파일에 키 패턴(`sk-`, `ghp_`, `AKIA`, private key 등)이
  보이면 그 파일은 통째로 빠진다 — 경로+줄+패턴 이름만 요약에 나오고 값은 안 나온다.
- 디렉터리: `.git`, `node_modules`, `.next`, `build`, `__patch_drop__`, `data`,
  `logs`, `agent-prompts` 등. 2MB 초과 파일도 제외(목록에 표시).

## 결과 위치

`C:\Users\nninn\OneDrive\Desktop\zipHome\demo1_<profile>_<날짜-시각>_<sha7>[-dirty].zip`
+ 같은 이름의 `..._MANIFEST.md` 사본. zip 안에는 `_MANIFEST.md`(무엇이 들어갔는지)와
`_README_FOR_GPTPRO.md`(프로젝트 요약 + 붙여 쓸 질문 틀)이 같이 들어 있다.

## 주의

- zip은 ChatGPT 공식 지원 목록에 명시 없음(실무로는 읽힘). 업로드가 실패하면
  주요 파일 몇 개를 직접 올린다. 파일당 512MB·문서당 2M 토큰 한도 —
  근거는 `data/agent-handoff/devin-gptpro-zip-pack-*/OFFICIAL.md`.
- zip이 10개 넘으면 정리 안내만 뜬다(자동 삭제 없음).
- 비대화 실행: `GPTPRO_NOPAUSE=1`, `GPTPRO_NOEXPLORER=1` 환경변수 또는
  `--no-pause` / `--no-explorer` 인자.
