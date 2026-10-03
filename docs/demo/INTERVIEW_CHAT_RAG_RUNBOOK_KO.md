# 면접용 로컬 문서 RAG 시연
대상: http://127.0.0.1:18180/chat. 2026-10-02 작성. 실제 시연 가능 여부는 작업 장부의 최종 C2·C3·C4 판정으로 결정한다.

기동 한 줄(프로젝트 루트의 PowerShell):
`$env:AWX_RAG_NO_PAUSE='1'; . .\scripts\start_rag_stack.ps1 -MetaDisplay; function Test-RagHttpsOptIn { return $false }; $OpenBrowser=$false; Invoke-RagLauncher`
이는 기존 런처를 사용하며 이 호출에서만 선택적 공개 HTTPS 확인을 끈다. 기존 개발 서버를 재사용하면 새 Java 반영 증명이 되지 않는다. 소스 변경 후에는 소유권을 확인하고 Close-RAG.bat → 위 기동 → Verify-RAG.bat 순서로 검증한다.

1. /chat에서 로컬 카탈로그 모델을 선택하고 “선택 모델만 사용”으로 둔다. 짧은 인사(C1)의 첫 본문과 모델 표시를 확인한다.
2. 새 대화에 fixtures/interview-rag/A_project_codename.md를 첨부한다. 업로드 완료 후 RAG ON, 검색 OFF로 “이 문서 기준으로 프로젝트 코드명과 첫 시연일은?”을 묻는다(C2). 은하수-7, 2026-10-15와 A 파일 출처를 가리킨다.
3. 같은 세션에서 코드명을 다시 묻는다(C5). 새 대화에서 A+C를 첨부하고 “코드명이 뭐야?”(C3), “이 문서 기준으로 프로젝트 예산은?”(C4)을 묻는다. 폐기 메모를 구분하고 문서에 없는 예산을 만들지 않는지 확인한다.
4. 검색을 켜고 Spring Boot 3.3 패치 질문(C6)을 한다. URL 또는 provider 비활성/키 없음/실패 사유를 가리킨다. 실패 시 “검색 경로가 현재 제한되어 있고, 답변과 진단에서 확인합니다”라고 설명한다. 출처가 없는 최신 사실은 검증된 답으로 소개하지 않는다.
5. 다른 로컬 모델을 선택한다(C7). 요청 모델, 서버 보고 모델, 실제 실행 입증은 별개다. 화면의 “요청”과 “적용/응답” 표시만으로 actual model을 입증하지 않는다. Health/trace의 미관측을 그대로 설명한다.
6. /settings에서 provider·로컬/API·가용성 목록과 “이 브라우저의 새 대화 기본 모델”을 보여 준다. 서버 전역 기본값은 관리자 읽기 표시다.

재현 명령: 번들 Playwright의 NODE_PATH를 현재 셸에 둔 뒤 `node scripts/chat_rag_golden_browser.js --base http://127.0.0.1:18180 --repeat 3 --max-sends 20 --model <현재 카탈로그 로컬 ID>`. 현재 실행기와 fixture 테스트에는 외부 변경이 있어 이 명령의 최신 hash 검증은 대기 중이다. 작업의 생성 POST는 10/40, 자동 재기동은 3/3이며 추가 재기동 승인 없이 실행을 이어 가지 않는다. result.json은 output/playwright/chat-rag-golden 아래 해당 실행 폴더에 생성된다.

복구: 현재 소스 복구본은 data/agent-handoff/codex-autonomy/codex-chat-rag-browser-selfcheck-63b0c67a의 해당 cycle/preimage다. `codex_work_checkpoint.py restore --root . --run <해당 cycle>`은 postimage가 일치할 때만 사용한다. 다른 세션이 바꾼 파일은 덮어쓰지 않는다. 복구한 Java도 다시 compile 및 소유 runtime 반영이 필요하다.
