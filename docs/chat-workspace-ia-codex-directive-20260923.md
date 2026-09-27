# Codex 지시서: AbandonWare AI 채팅 워크스페이스

작성: Grok, 2026-09-23. 모드: DRAFT. 적용 주체: Codex.
프로젝트 루트: `C:\AbandonWare\demo-1\demo-1\src`.
이 문서는 소스 수정 지시서다. 이 문서를 쓴 턴에서는 채팅 템플릿·CSS·JS를 고치지 않았다.

목표: 평소 화면은 대화만 읽고, 모델·검색·RAG·진단·관리자 기능은 같은 화면에서 접어 두었다가 연다. 데스크톱은 왼쪽 대화 목록 + 중앙 대화. 좁은 화면은 그 목록을 접는다.

## 0. 확인 범위와 무변경 경계

라이브 파일 SHA-256 (2026-09-23, 이 지시서 작성 직전):

| 파일 | SHA-256 |
|---|---|
| `main/resources/templates/chat-ui.html` | `8c98c20bcf5d64fab67dc9229c65f706405957fb96c9bac919bc7bf77f086023` |
| `main/resources/static/css/chat-style.css` | `6c0c7fa62203e1ca5018461ede6f414b485a7c9399b240ed357a3e49bd590343` |
| `main/resources/static/js/chat.js` | `816ca2a4944fbf1ffb801dba20ef63dae29d2750a419fcb719c973915b67a8f2` |
| `main/java/com/example/lms/web/PageController.java` | `33de6f6738f8f7de70e56648cfe7f86170d3d2778a1242f6b8922620476f2bd5` |
| `main/java/com/example/lms/api/ChatApiController.java` | `1edbcd80f3371cc6430e6050f28bbd6fce836cfe4c99c163ab9ed2f9b99b1e74` |
| `main/java/com/example/lms/service/ChatHistoryServiceImpl.java` | `6c5f18476db1cc117c91b0beff22b0ff0b4e6b2f095aefb5d3953c0041c52f4a` |

패치 직전에 같은 경로를 다시 읽어 해시가 같으면 이 지시서의 줄 번호를 쓴다. 해시가 다르면 그 파일은 멈추고, 아래 동작 계약만 다시 대조한다.

서빙 경로 (소스): `PageController.home()`은 `demo.interview.enabled`가 꺼져 있으면 `/`를 `/chat`으로 보낸다. `chatUi()`는 템플릿 이름 `"chat-ui"`를 반환한다. `?surface=compact`만 `chatSurface=compact`다. 그 외는 `web`. 진단 블록은 `chatDiagnosticsEnabled` = 인증 주체의 `ROLE_ADMIN`일 때만 렌더된다 (`PageController` 323–345). `frontend/`의 Next 페이지와 `bin/main/` 복사본은 이 경로가 아니다. 수정하지 않는다.

활성 임대 (preflight 2026-09-23T03:54Z, `chat-repair-errors-a`, 만료 `2026-09-23T06:49:14Z`):

- `main/resources/static/js/chat.js`
- `main/java/com/example/lms/security/ChatOpenSecurityConfig.java`
- `src/test/java/com/example/lms/security/ChatAnonymousPolicyIntegrationTest.java`
- `src/test/js/chat-failure-recovery.test.cjs`

저널 `chat-repair-dynamic-ui-0868f566`는 `in_progress`다. 진행 완료로 읽지 않는다. P0은 위 네 파일을 열지 않는다. P1은 임대가 끝난 뒤에만 `chat.js`를 연다. 임대가 겹치면 그 파일은 건너뛰고 P0만 끝낸다.

하지 말 것:

- Git 조회·커밋·복원. `bin/main/**` 편집. 렌즈/`assets/display`/Fold 설정/Nova Focus. 인증 체인, owner 쿠키, 세션 403 규칙, 포트, 시크릿.
- 로그인 화면·가입·역할 추가. 익명 채팅과 게스트 세션 목록은 유지한다.
- 카드·그림자를 더 얹는 화장. 기존 `--shadow-soft`를 대화판에 유지하는 것도 이번 목표와 어긋난다.
- ChatGPT/Claude 레이아웃·카피·아이콘의 복제.
- 메시지 본문 검색 API. 목록 응답에는 제목만 있다.

## 1. 현재 화면이 밍밍하고 쓰임이 막히는 지점

원인 네 개가 소스에 같이 있다.

1. 대화 위에 운영 콘솔 크롬이 쌓여 있다. `chat-ui.html` 16–32 상단 바는 브랜드, `Admin tools`, `Sign in`, 모델명이다. 36–48 대화 헤더는 eyebrow `Operator chat`, h1 `Dynamic RAG workspace`, 설명 문장, `New chat`, 그리고 Model/DPP/CFVM/Supabase 배지다. 51–81은 접히는 Response settings. 83–123은 Route·Model·Context·RAG·Trace·Quality·Health와 Decision 6단계다. 124는 세션 칩. 303–308 오른쪽 열은 `project-intro` 설명문이다. 대화 로그(`#chatWindow`)는 그 아래다.
2. 여백과 옅은 면이 기본값이다. `chat-style.css` 1–25 주석이 `calm-premium-operator-console`이고, 페이지 `#f3f6f5`, 카드 흰 면, 반지름 22px, `--shadow-soft`다. `.chat-layout`(77–87)은 최대 1240px에 가운데 정렬, 열 간격 20px, 바깥 여백 20px다. `.conversation-header`(1225–1231) 패딩은 22px. `#chatWindow`(1467–1470) 하단 패딩은 96px. 넓은 화면의 빈 가장자리와 떠 있는 카드가 밀도 부족으로 보인다.
3. 이전 대화로 돌아가는 왼쪽 목록이 없다. 세션 UI는 `.session-mode-list`(html 124, css 319–345)이고, 대화 기둥 안의 가로 칩이다. 배경 `#fffaf2`. `chat.js` `SESSION_LIST_LIMIT = 12`(265행). `renderSessionList`(882–934)는 버튼 문구를 `제목 - Mode: … - trace`로 만들고, 같은 컨테이너에 `data-session-mode-row` 진단 줄(`upsertSessionModeBadgeInList`, 2100–2118, 문구 `session:<id> <mode> <trace>`)을 넣는다. 검색 입력은 없다. 서버 목록 API에도 `q` 파라미터가 없다.
4. 그 배치가 테스트로 잠겨 있다. `scripts/chat_ui_stream_contract_tests.js` 1491–1494는 대화 섹션이 `project-intro`보다 앞에 있어야 한다고 보고, 1664–1677은 데스크톱 그리드를 `minmax(0, 1fr) minmax(240px, 320px)`, 소개 열을 2열로 고정한다. 1679–1683은 헤더가 카피 + 배지 두 칸이어야 한다. `ChatUiTemplateIntegrityTest`는 `<nav class="app-menu-bar"`부터 `<!-- === Scripts` 사이에 소개문 두 문장이 그대로 있어야 한다. 화면을 대화 중심으로 바꾸려면 이 단언을 같은 패치에서 바꾼다.

세션 데이터는 이미 있다. 없는 것은 내비게이션 구조다.

- `GET /api/chat/sessions` (`ChatApiController` 4628–4650)는 `SessionInfo(id, title, answerMode, lastTraceTurnId)` 배열이다. 기본 limit 50, 상한 100 (`ChatHistoryService` 14–15, 188–189).
- 게스트 목록은 `ChatHistoryServiceImpl.getSessionsForUser` 466–515에서 owner 쿠키와 IP 해시 후보로 거른다. 키가 없으면 빈 배열이다. 로그인 사용자만의 기능이 아니다.
- 상세 `GET /api/chat/sessions/{id}`는 게스트 owner 쿠키가 다르면 403 (`ChatApiController` 4749–4758). `ChatOpenSecurityConfig`는 `/api/chat/**`를 permitAll한다. 목록 핸들러 자체는 401을 만들지 않는다. `chat.js` 969–971의 “대화 기록을 보려면 로그인이 필요합니다.”는 클라이언트가 401을 받았을 때의 문구다.
- 현재 세션 포인터는 `sessionStorage["chat.currentSessionId"]` (`chat.js` 262, 778–786). 탭마다 따로다. 목록은 쿠키 범위라서, 사이드바가 보이면 새 탭에서도 이 브라우저의 이전 대화가 보여야 한다.
- `startNewChatSession`(1185–1226)은 저장 포인터를 지우고 로그를 비운다. 서버 세션은 지우지 않는다. `syncSelectedSessionRow`와 `refreshSessionList`는 호출하지 않아서, 새 채팅 뒤에도 이전 행의 `data-session-selected="true"`가 남을 수 있다. 이 수정은 P1 (`chat.js`)이다.
- `?surface=compact`는 `th:if="${chatSurface != 'compact'}"`로 설정·상태줄·세션 목록·소개 열을 템플릿에서 뺀다. 안경/동반 화면 계약이다. 웹 사이드바와 섞지 않는다.

## 2. 유지 / 기본에서 접기 / 위치 이동

| 기능 | 처리 | 근거 |
|---|---|---|
| `#chatWindow`, `#chatForm`, `#messageInput`, `#sendBtn`, `#stopBtn`, 빠른 질문 3개(`data-q`와 버튼 문구) | 유지, 대화 기둥의 본체 | `ChatUiTemplateIntegrityTest`, `ChatFrontendSecurityTest`가 빠른 질문 문구를 고정 |
| `id="modelSelect"`, `id="searchModeSelect"`, `id="useRagToggle"`, `id="newChatBtn"`, `id="responseSettingsSummary"` | id 각 1개 유지. 위치만 사이드바 | 스트림 계약 1538–1540 |
| 검색 값 `AUTO` `OFF` `FORCE_LIGHT` `FORCE_DEEP`, HTML 기본 선택 `OFF` | 값·기본 선택 유지 | `chat.js`가 `defaultSelected`를 읽고, 전송은 `useWebSearch: value !== "OFF"` |
| RAG 체크박스, 세션 복원 시 `applyRestoredSessionSettings` | 유지 | 세션 재진입이 모델·검색·RAG를 다시 심는다 |
| `[data-session-mode-list]`, `[data-session-list-row]`, `[data-session-mode-row]` | 같은 컨테이너에 유지. 목록은 세로, 진단 행은 CSS로 감춤 | `chat.js`가 둘 다 이 노드에 붙인다. P0에서 JS를 못 고친다 |
| Health `role="status"` `aria-live="polite"`, Decision 6단계, Diagnostics 내부 하트비트 마크업 | 노드 유지. 상태줄은 한 줄로 압축 | 스트림 계약 1514–1536, 1518 |
| Admin 링크 5개(`/admin/brain-state`, `/admin/pipeline-status`, `/admin/rag-ops-cockpit`, `/admin/vector-diagnostics`, `/model-settings`)와 `<summary>Admin tools</summary>` | 문구·href 유지, 사이드바 하단으로 이동 | 스트림 계약 1497–1498 |
| `Sign in` → `/login` | 유지, 사이드바 하단. 상단 주 메뉴에서 뺀다 | 익명 사용이 기본. 로그인은 주인 전용 입구 |
| 소개문 두 문장 | 문자 그대로 유지, 접힌 `<details>` 안 | `ChatUiTemplateIntegrityTest` 19–21 |
| `?surface=compact`에서 설정·목록·상태·소개를 빼는 조건 | 유지 | 동반 화면 |
| 상단 모델 강한 글자 `data-current-model`, eyebrow, h1 설명, 오른쪽 소개 열, 가로 세션 칩 | 기본 화면에서 치운다 | 사용자 목표 |
| 진단 카드 그리드 | 기본 닫힘. DOM에서 지우지 않는다 | 관리자만 렌더, 이미 `<details>` |

## 3. 외부 IA에서 가져올 것과 가져오지 않을 것

확인일 2026-09-23.

- OpenAI Help Center, ChatGPT Release Notes, 2024-11-22 “Updates to the ChatGPT Web experience” (https://help.openai.com/en/articles/6825453-chatgpt-release-notes): 사이드바의 최근 대화는 짧게 두고, 설정은 사이드바 하단에 둔다. 모바일 웹 사이드바는 플로팅이고 대화를 바꾸면 닫힌다. 새 대화는 모델 선택 옆에서 닿기 쉽게 둔다.
- 같은 릴리스 노트, 2026-07-14: 사이드바에서 과거 대화를 검색해 연다. 2026-09 안드로이드 노트: 사이드바 최근 대화는 여덟 개까지 바로 보여 준다.
- Claude Help Center, 2026-09-16 “Claude Cowork and chat are one Claude” (https://support.claude.com/en/articles/16761823-claude-cowork-and-chat-are-one-claude): 최근 항목은 한 목록이다. 모델 선택은 원래 자리를 유지한다. 같은 문서의 “웹 검색 토글을 없앤다”는 여기 적용하지 않는다. 이 제품의 Search/RAG는 명시 조작이고, 전송 필드 `searchMode`·`useRag`에 묶여 있다.
- Claude Help Center, 2026-09-15 “Use Claude’s chat search and memory…” (https://support.claude.com/en/articles/11817273-use-claude-s-chat-search-and-memory-to-build-on-previous-context): 과거 대화 검색과 메모리 주입은 다른 기능이다. 이번 작업은 검색·재진입만 한다. 메모리 주입은 범위 밖이다.

여기 적용:

- 최근 목록은 지금 클라이언트가 그리는 12개. 그 안을 제목으로 거른다. 서버가 주는 창은 기본 50, 상한 100. 그 밖 본문 검색은 만들지 않는다.
- 설정(모델·검색·RAG)과 Admin·Sign in·진단은 사이드바 하단.
- 모델 선택은 사이드바 설정 안에 두고, 접힌 summary에 현재 값(`#responseSettingsSummary`)을 그대로 보여 준다. 컴포저 안으로 복제하지 않는다. id는 하나다.
- 768이 아니라 이 파일의 기존 끊김 `760px`를 쓴다.

## 4. 데스크톱 / 좁은 화면 구조

### Before (현재 `web` 서피스)

```
[ AbandonWare AI | Admin tools | Sign in | 모델명 ]
[ Operator chat          ] [ Model DPP CFVM Supabase ]
[ Dynamic RAG workspace / 설명 / New chat            ]
[ Response settings (접힘)                           ]
[ Route Model Context RAG Trace Quality Health       ]
[ Decision 6칸                                       ]
[ 세션 칩이 가로로 섞임                              ]
[ 빈 상태 카피 또는 #chatWindow                      ]
[ composer                                           ]
                              [ 오른쪽 소개 카드 240–320px ]
```

좁은 화면(≤760px)은 위 크롬이 한 열로 쌓이고, 소개 카드가 대화 아래로 내려간다. 세션 칩은 여전히 왼쪽 목록이 아니다.

### After 데스크톱 (≥761px)

```
[ 대화 토글 | AbandonWare AI          | 모델명 ]
+------------------+-------------------------------+
| 새 채팅          |  (eyebrow·설명·오른쪽 소개 없음) |
| [대화 검색____]  |  #chatEmptyState 또는 #chatWindow |
| 최근 대화 세로    |                               |
|  (최대 12, 제목)  |                               |
|                  |                               |
| ─────────────── |  composer (Send / Stop)        |
| 모델·검색·RAG ▼  |                               |
| 진단 ▼ (관리자)  |  상태줄은 컴포저 위 한 줄       |
| Admin tools ▼    |                               |
| Sign in          |                               |
| 워크스페이스 설명 ▼|                               |
+------------------+-------------------------------+
```

그리드: `272px minmax(0, 1fr)`, 간격 0, 폭 100%, 바깥 여백 0. 대화 영역은 테두리·반지름·그림자 없음. 사이드바는 오른쪽 1px 구분선만.

### After 좁은 화면 (≤760px, `web`)

- 사이드바는 `position: fixed`, 기본 `translateX(-105%)`. `#sidebarToggle`이 체크되면 들어온다.
- 뒤 스크림은 같은 체크박스를 끄는 `<label>`.
- 대화 열은 항상 1열이고 사이드바 폭을 빼지 않는다.
- 대화 행을 누르면 인라인 스크립트가 체크를 해제한다. `chat.js`의 행 클릭(`selectSessionCandidate`)은 그대로 둔다.
- `?surface=compact`는 사이드바 노드를 렌더하지 않고 대화 1열만 둔다.

## 5. 새 채팅 → 검색 → 다시 들어가기

P0 (JS 임대 중에도 가능):

1. 사이드바의 기존 `#newChatBtn`이 `startNewChatSession`을 호출한다. 리스너는 이미 `chat.js` 6696에 있다. 버튼을 옮기기만 한다.
2. 이전 서버 세션은 남는다. 목록은 기존 `refreshSessionList("init")`(6719)와 응답 종료 시 갱신(5618)으로 채워진다.
3. `#sessionSearchInput`은 템플릿 하단 인라인 스크립트만 사용한다. `chat.js`에 넣지 않는다. 입력이 바뀔 때 `[data-session-list-row]`의 `textContent`에 소문자 포함되면 보이고, 아니면 `hidden`. `[data-session-mode-row]`는 검색 대상이 아니다.
4. 행 클릭은 기존 `selectSessionCandidate`가 `GET /api/chat/sessions/{id}`로 메시지를 바꾸고 `applyRestoredSessionSettings`로 모델·검색·RAG를 되돌린다.
5. 좁은 화면에서 행 클릭 또는 새 채팅 클릭 후 `#sidebarToggle.checked = false`.

P0에서 아는 한계:

- 버튼 문구는 P1 전까지 `제목 - Mode - trace`다. 한 줄 ellipsis로만 다룬다.
- 검색은 이미 그려진 최대 12행의 그 문구만 거른다. 목록이 다시 그려지면 필터가 풀린다. 스크립트는 `input` 이벤트만 듣는다.
- 새 채팅 뒤 선택 표시가 남을 수 있다. P1에서 `syncSelectedSessionRow(null)`과 `refreshSessionList("new-chat")`를 `startNewChatSession` 성공 경로 끝에 넣는다.
- 50(상한 100)을 넘는 오래된 대화와 메시지 본문 검색은 이번 범위에 없다.

P1 행 문구 (`chat.js` `renderSessionList` 911–924, 임대 해제 후):

- 보이는 텍스트는 `title`만. `answerMode`와 trace는 이미 `dataset.sessionAnswerMode`, `dataset.sessionTraceTurnId`에 있다.
- `aria-label`은 제목만. 모드 문구를 본문에서 뺀 뒤 `node scripts/chat_ui_stream_contract_tests.js`가 실패하면 그 단언이 문구를 요구하는 경우에만 문구를 되돌리고 CSS ellipsis를 유지한다.

P2는 하지 않는다. 제목 `q`를 서버에 붙이는 작업은 목록 창·owner 필터·계약 테스트를 따로 연 뒤에만 한다. 본문 검색은 지시하지 않는다.

## 6. 모델 · 검색 · RAG 한 패널

기존 `<details class="response-settings">` 한 개를 사이드바 하단으로 옮긴다. 내부를 새로 만들지 않는다.

- summary 첫 칸 문구는 `모델·검색·RAG`. `#responseSettingsSummary`는 `chat.js` 2421–2422가 `모델 | Search … | RAG …`로 갱신하므로 그대로 둔다.
- 컨트롤 순서: Model select, Search select, RAG checkbox. 라벨 한글은 `모델` `검색` `RAG`. `name`·`id`·`option value`는 유지.
- 기본은 닫힘 (`open` 속성을 넣지 않는다).
- 컴포저에 두 번째 select를 만들지 않는다.
- 패널을 연 상태의 높이 규칙은 `.chat-area-wrapper:has(> .response-settings[open])`에서 `.session-sidebar .response-settings[open]` 쪽으로 바꾸고, 스트림 계약의 같은 문자열 단언도 같이 바꾼다. 죽은 선택자를 남겨 테스트를 통과시키지 않는다.

## 7. Admin · 로그인 · 진단

사이드바 하단 순서:

1. `details.response-settings`
2. `details.diagnostics-disclosure` (기존 `th:if="${chatDiagnosticsEnabled}"` 유지). 비관리자 DOM에는 없다.
3. `details.admin-tools` + 기존 메뉴 5링크. summary 문구 `Admin tools` 유지.
4. `a.sign-in-link` href `/login`
5. `details.workspace-note` > 기존 소개문 두 문장을 한 글자도 바꾸지 않고 넣는다. 바깥 요소는 `<div class="project-intro">`로 둔다. `<aside class="project-intro">`는 사이드바 `<aside>` 안에 중첩하지 않는다. `ChatUiTemplateIntegrityTest`는 문장을 보고, 스트림 계약은 지금 `<aside class="project-intro"` 문자열을 본다. 후자는 이번 패치에서 고친다.

진단 하트비트·매트릭스·콕핏 마크업은 잘라 붙이기만 한다. 카드 문구를 고치지 않는다.

상태줄 `#coreStatusRail`은 대화 기둥에서 컴포저 바로 위에 한 줄로 남긴다. Health의 live 속성과 Decision 6단계는 그 안에 유지한다. 노드를 진단 details 안으로 넣지 않는다. 스트림 계약 1522–1527이 rail 안의 health → decision 순서를 본다.

상단 바에서 `nav.app-menu-bar`를 지우면 `ChatUiTemplateIntegrityTest`의 시작 앵커가 사라진다. `class="app-menu-bar"`는 사이드바 하단 묶음에 남긴다.

## 8. 고칠 파일

P0:

| 파일 | 할 일 |
|---|---|
| `main/resources/templates/chat-ui.html` | 아래 11절. 진단 내부를 재작성하지 말고 이동 |
| `main/resources/static/css/chat-style.css` | 레이아웃·밀도. 디버그 미디어 블록의 하트비트 규칙 문자열은 유지 |
| `scripts/chat_ui_stream_contract_tests.js` | 11절의 단언을 새 구조에 맞게 교체 |
| `src/test/java/com/example/lms/web/ChatUiTemplateIntegrityTest.java` | 앵커·두 문장·빠른 질문이 여전히 참인지 확인. 실패하면 앵커만 맞춘다. 문장을 지우지 않는다 |
| `scripts/chat_ui_geometry_contract_tests.js` | 상단 바 항목과 Admin 메뉴 위치 단언을 사이드바 기준으로 교체. 상태 알약 7개가 보이는 단언은 유지(한 줄이어도 visible) |

P1 (임대 해제 후, 별도 사이클):

- `main/resources/static/js/chat.js`만. 행 제목, 새 채팅 후 선택 해제·목록 갱신, 인라인 검색을 이 파일의 `renderSessionList` 뒤로 옮겨 새로고침 뒤에도 필터가 유지되게 한다. 옮긴 뒤 템플릿의 인라인 검색 스크립트는 제거한다.
- `?v=chat-ui-20260922-warn-evidence-v13`를 새 쿼리로 올린다. P0에서는 이 쿼리를 올리지 않는다.

P0에서 손대지 않는 경로: 위 임대 4파일, `frontend/**`, `bin/main/**`, `assets/display/**`, `PageController`(이미 compact·admin 플래그를 준다), `ChatApiController` 세션 메서드, `ChatHistoryServiceImpl`.

스타일시트 링크에만 `?v=chat-workspace-ia-p0`를 붙인다. `/js/chat.js?v=chat-ui-20260922-warn-evidence-v13`는 P0에서 유지한다.

## 9. P0 / P1 / P2

| 단계 | 끝나는 모습 | 막히면 |
|---|---|---|
| P0 | 데스크톱 왼쪽 목록 + 중앙 대화. 좁은 화면은 접히는 목록. 설정·Admin·Sign in·진단·소개문은 사이드바 하단. 대화판 그림자·가운데 1240px 여백 제거. 기존 id로 전송·세션 클릭·새 채팅 동작 | `chat.js`가 필요해지면 P0을 늘리지 말고 멈춘다 |
| P1 | 목록 버튼은 제목만. 새 채팅이 선택 표시를 지우고 목록을 다시 읽는다. 검색 필터가 목록 갱신 뒤에도 유지 | 임대가 살아 있으면 P1 보류. P0 완료로 남긴다 |
| P2 | 하지 않음. 서버 `q`는 별도 지시가 있을 때만, owner 범위 안의 제목만 | 본문 검색·권한 완화로 바꾸지 않는다 |

## 10. 밀도 (그림자·카드 추가 금지)

`chat-style.css`에서 대화 워크스페이스에 해당하는 규칙만 바꾼다.

- `.chat-layout`: 폭 `100%`, `margin: 0`, `gap: 0`, 열 `272px minmax(0, 1fr)`. 주석 토큰은 `conversation-workspace-grid`.
- `.chat-area-wrapper`: `grid-column: 2`는 사이드바가 있을 때. `border: 0`, `border-radius: 0`, `box-shadow: none`, 높이 `calc(100dvh - var(--top-bar-min-height))`. 기존 `height: calc(100vh - var(--top-bar-min-height) - 20px)` 단언은 테스트에서 새 식으로 바꾼다.
- `.conversation-header`: 패딩 `8px 16px`, 한 줄. h1 텍스트는 `새 대화`, 글자 크기 16px, 굵기 650. eyebrow와 설명 `p`는 `display: none` (노드는 두어도 된다). `.orch-signal-badges`는 진단 details 첫 자식으로 옮기고, 헤더의 2열 규칙을 제거한다.
- `#chatWindow` 패딩 `12px 16px 12px`. 하단 96px는 제거. `.message` 패딩 `8px 12px`, 반지름 `8px`.
- `.session-mode-list`: column, `overflow: auto`, 배경 투명, 노란 띠 제거. 행 높이 36px, 왼쪽 정렬, 투명 배경. `[data-session-selected="true"]`만 `#e7f2ec`. `[data-session-mode-row] { display: none }`.
- `.project-intro`의 `grid-column: 2`와 카드 그림자는 제거. 접힌 details 안의 본문으로만 둔다.
- 새 `box-shadow`를 추가하지 않는다. 사이드바는 `border-right: 1px solid var(--line)`.
- 기존 디버그 하트비트 미디어쿼리(`debug-heartbeat-mobile-priority`, `max-height: 44px`, `min-height: 30px` 등 스트림 계약 1621–1661이 찾는 문자열)는 그대로 둔다.

## 11. P0 최소 diff

작업 전: `__patch_drop__/source_edit_session.ps1 -Action status`에 이번 대상만 넣어 `targetConflict.allowed == true`인지 확인한다. `chat.js`와 보안 설정은 매니페스트에 넣지 않는다. 해시가 0절과 다르면 그 파일은 중단한다.

### 11.1 `chat-ui.html` body

아래 골격으로 기존 노드를 옮긴다. 진단 details 내부, 빠른 질문 `data-q`, 소개문 두 문장, 컨트롤 id는 복사한다.

```html
<body ...기존 data 속성...>
<input id="sidebarToggle" class="sidebar-toggle-input" type="checkbox">
<header class="top-utility-bar">
  <label for="sidebarToggle" class="sidebar-toggle">대화</label>
  <a class="brand-mark" href="/chat">AbandonWare AI</a>
  <strong data-current-model th:text="${currentModel} ?: 'gemma4:26b'">gemma4:26b</strong>
</header>
<main class="chat-layout">
  <aside class="session-sidebar" aria-label="대화" th:if="${chatSurface != 'compact'}">
    <div class="sidebar-head">
      <button id="newChatBtn" class="ghost new-chat-action" type="button" title="New chat" data-testid="chat-new-chat-button">새 채팅</button>
      <label class="session-search">
        <span class="visually-hidden">대화 검색</span>
        <input id="sessionSearchInput" type="search" placeholder="대화 검색" autocomplete="off" aria-label="대화 검색">
      </label>
    </div>
    <div class="session-mode-list" data-session-mode-list aria-label="Session history and mode diagnostics" aria-live="polite"></div>
    <div class="sidebar-foot">
      <nav class="app-menu-bar" aria-label="Workspace">
        <!-- 기존 response-settings details 전체 이동. summary 첫 글자만 모델·검색·RAG -->
        <!-- 기존 diagnostics-disclosure 전체 이동. th:if 유지 -->
        <!-- 기존 admin-tools details 전체 이동 -->
        <a class="sign-in-link" href="/login">Sign in</a>
        <details class="workspace-note">
          <summary>워크스페이스 설명</summary>
          <div class="project-intro">
            <p>질문 분석, 검색, 재랭킹, 증거 게이트, 회복 경로를 한 화면에서 추적합니다.</p>
            <p>Self-Ask, Anchor Compression, MoE routing, and trace breadcrumbs remain available in Diagnostics.</p>
          </div>
        </details>
      </nav>
    </div>
  </aside>
  <section class="chat-area-wrapper" aria-label="Chat">
    <header class="conversation-header">
      <div class="conversation-copy">
        <h1>새 대화</h1>
      </div>
    </header>
    <div class="chat-transcript-region">
      <!-- 기존 #chatEmptyState 와 #chatWindow. 빠른 질문 3개 문구·data-q 유지 -->
    </div>
    <!-- 기존 #coreStatusRail 전체. Health·Decision 순서 유지 -->
    <form id="chatForm" ...기존 속성...>
      <!-- textarea, send, stop 기존 id -->
    </form>
  </section>
</main>
<label for="sidebarToggle" class="sidebar-backdrop" aria-hidden="true"></label>
<script>
(function () {
  var input = document.getElementById("sessionSearchInput");
  var list = document.querySelector("[data-session-mode-list]");
  var toggle = document.getElementById("sidebarToggle");
  if (input && list) {
    input.addEventListener("input", function () {
      var q = input.value.trim().toLowerCase();
      Array.prototype.forEach.call(list.querySelectorAll("[data-session-list-row]"), function (row) {
        var text = String(row.textContent || "").toLowerCase();
        row.hidden = q.length > 0 && text.indexOf(q) === -1;
      });
    });
  }
  function closeOnNarrow() {
    if (!toggle || !window.matchMedia("(max-width: 760px)").matches) return;
    toggle.checked = false;
  }
  if (list) {
    list.addEventListener("click", function (event) {
      if (event.target.closest && event.target.closest("[data-session-list-row]")) closeOnNarrow();
    });
  }
  var newer = document.getElementById("newChatBtn");
  if (newer) newer.addEventListener("click", closeOnNarrow);
})();
</script>
<!-- === Scripts -->
</body>
```

`th:if="${chatSurface != 'compact'}"`는 사이드바 한 곳에만 둔다. 설정·목록·소개를 대화 기둥에 중복 렌더하지 않는다. compact는 사이드바 자체가 없으므로 기존과 같이 그 노드가 없다.

orch 배지 블록은 진단 details 안, 하트비트 앞으로 옮긴다. compact가 아닌데 비관리자라 진단 details가 없으면 배지는 사이드바 하단 `workspace-note` 앞에 `<div class="orch-signal-badges" ... th:if="${chatSurface != 'compact'}">`로 한 번만 둔다. id·배지 `data-orch-badge` 값은 유지한다.

### 11.2 CSS에서 바꿀 승자 규칙

같은 선택자가 파일에 여러 번 있다. 이기는 쪽을 고친다.

- 77행 근처 `.chat-layout`
- 162–188 `.chat-area-wrapper` 높이와 그림자
- 1215 `.project-intro`의 `grid-column: 2`
- 1225 `.conversation-header` 2열
- 1683 이후 `@media (max-width: 760px)`의 `.chat-layout` / `.project-intro` / `.conversation-header`
- 2036 `[data-chat-surface="compact"] .chat-layout`은 `grid-template-columns: 1fr`, `.chat-area-wrapper { grid-column: 1 }`

추가:

```css
.sidebar-toggle-input { position: absolute; opacity: 0; pointer-events: none; }
.sidebar-backdrop { display: none; }
.session-sidebar {
  grid-column: 1;
  grid-row: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--line);
  background: var(--surface);
}
.sidebar-head, .sidebar-foot { flex: 0 0 auto; padding: 8px; display: grid; gap: 8px; }
.session-search input { min-height: 36px; }
.visually-hidden {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0;
}
.chat-layout:has(.session-sidebar) {
  grid-template-columns: 272px minmax(0, 1fr);
}
.chat-layout:has(.session-sidebar) .chat-area-wrapper { grid-column: 2; }
@media (max-width: 760px) {
  .chat-layout, .chat-layout:has(.session-sidebar) { grid-template-columns: minmax(0, 1fr); }
  .chat-area-wrapper, .chat-layout:has(.session-sidebar) .chat-area-wrapper { grid-column: 1; }
  .session-sidebar {
    position: fixed; z-index: 30; top: 0; bottom: 0; left: 0;
    width: min(320px, 88vw);
    transform: translateX(-105%);
    background: var(--surface);
  }
  body:has(#sidebarToggle:checked) .session-sidebar { transform: none; }
  .sidebar-backdrop {
    display: none; position: fixed; inset: 0; z-index: 25; background: rgba(20, 33, 31, .35);
  }
  body:has(#sidebarToggle:checked) .sidebar-backdrop { display: block; }
  .top-utility-bar { z-index: 40; }
}
```

`.top-utility-bar`의 기존 `min-height: 44px` 터치 규칙(버튼, admin summary, response summary, diagnostics summary)은 유지한다. `.sidebar-toggle`도 `min-height: 44px`.

### 11.3 스트림 계약에서 바꿀 단언

`scripts/chat_ui_stream_contract_tests.js`:

- 1491–1494: `projectIntroIndex > chatRegionIndex` 삭제. 대신 `session-sidebar`와 `chat-area-wrapper`가 있고, 데스크톱 CSS가 사이드바를 1열·대화를 2열로 두는지 확인.
- 1664–1668: `minmax(0, 1fr) minmax(240px, 320px)`와 `.project-intro { grid-column: 2 }` 삭제. `conversation-workspace-grid`, `272px minmax(0, 1fr)`, `.session-sidebar`의 `grid-column: 1`, `:has(.session-sidebar) .chat-area-wrapper`의 `grid-column: 2`를 요구.
- 1670–1677: 모바일에서 `.project-intro { grid-row: 2 }` 삭제. `@media (max-width: 760px)` 안에 `.session-sidebar`의 `position: fixed`와 `translateX(-105%)`, `body:has(#sidebarToggle:checked)`를 요구. `.chat-area-wrapper`는 1열.
- 1679–1683: 헤더 2칸 배지 정규식 삭제. 헤더에 `conversation-copy`와 `h1`이 있는지만 확인. 배지는 `#coreStatusRail` 밖이어도 된다. `data-orch-badge` 네 개가 템플릿에 한 번씩 있는지는 유지.
- 1642–1654의 `.chat-area-wrapper` 높이 식은 새 `100dvh - var(--top-bar-min-height)`로 교체.
- `.chat-area-wrapper:has(> .response-settings[open])` 문자열 단언은 새 선택자로 교체.
- 1496–1536의 id·Health live·Decision 6단계·diagnostics 하트비트 단언은 유지한다. 이동만으로 통과해야 한다.

### 11.4 검증

P0 필수, 프로젝트 루트:

```
node scripts/chat_ui_stream_contract_tests.js
.\gradlew.bat test --tests com.example.lms.web.ChatUiTemplateIntegrityTest --tests com.example.lms.web.ChatFrontendSecurityTest --tests com.example.lms.web.BrainStateFrontendContractTest
```

기하 테스트(`scripts/chat_ui_geometry_contract_tests.js`)는 라이브 `/chat-ui`가 필요하다. 기본 URL이 `127.0.0.1:18183`이다. 그 서버를 이번 작업이 소유할 때만 실행한다. wear가 18180을 잡고 있거나 소유가 없으면 재시작하지 말고 `not_observed`로 적는다. 단언 소스는 11.3과 맞게 미리 고친다.

브라우저에서 볼 항목 (서버를 띄운 경우에만):

- 데스크톱: 왼쪽 목록, 중앙 로그, 1240px 바깥 여백이 없음, 오른쪽 소개 카드가 없음.
- 새 채팅이 로그를 비움. 이전 행이 목록에 남음.
- 검색 글자가 현재 12행을 거름.
- 행 클릭이 그 세션 메시지를 중앙에 그림.
- 모델·검색·RAG 패널이 사이드바에서 열리고, 전송 payload의 id는 하나.
- Admin tools와 Sign in이 상단 바가 아니라 사이드바 하단.
- 폭 390과 1280 둘 다. 390에서는 목록이 닫혀 있다가 토글로 열리고, 행을 누르면 닫힘.
- `?surface=compact`는 사이드바·설정·상태줄 없이 대화와 컴포저만.

완료 조건: P0 파일의 정적 테스트가  exit 0이고, 위 브라우저 항목은 실행했다면 통과, 못 했으면 `not_observed`와 그 이유. P1은 임대가 남아 있으면 완료 조건에 넣지 않는다.

## 12. 보고 형식

Codex 완료 보고는 이 표만 쓴다.

| 구분 | 내용 |
|---|---|
| 수정 파일 | 경로와 SHA-256 |
| 정적 테스트 | 명령, exit, 테스트 수 |
| 브라우저 | 실행한 폭, 또는 `not_observed`와 이유 |
| 런타임 | 재시작했으면 Close/Start 쌍과 READY. 안 했으면 그 이유 |
| P1 | 적용 또는 임대 때문에 보류 |
| 손대지 않은 계약 | owner 403, permitAll, compact, 빠른 질문 문구, 진단 마크업 |
