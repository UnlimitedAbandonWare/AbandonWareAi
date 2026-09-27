# 데빈 지시서 — /chat 첨부파일 + 음성전사 뼈대·연결 (2026-09-25)

목표: 공개 채팅 `/chat` 컴포저에 (1) 파일 첨부 (2) 음성→텍스트(STT) 뼈대를 만들고,
이미 있는 Spring API/ASR에 연결한다. 새 마이크로서비스·새 Whisper/Deepgram 클라이언트 금지.

Project Root: C:\AbandonWare\demo-1\demo-1\src

중요 (프로덕션 심): 라이브 https://abandonwareai.kro.kr/chat 은 Next.js가 아니라 Spring이다.
- PageController → @GetMapping({"/chat","/chat-ui","/chat-ui.html"}) → chat-ui
- templates/chat-ui.html + static/js/chat.js + static/css/chat-style.css
- Next frontend/src/app/chat/ 는 로컬 RAG Console용 — 이번 심에서 고치지 말 것.

현재 갭:
- 컴포저 파일 첨부: 라이브 /chat UI 없음 (#messageInput/#sendBtn/#stopBtn 만).
  이미 있는 곳: POST /api/attachments/upload + ChatRequestDto.attachmentIds
- 음성→텍스트: mic/STT 없음. 이미 있는 곳: /conversate, ConversateAsrBridge,
  ConversateCloudStt.transcribe
- 전송 시 첨부: UI가 attachmentIds 안 넣음. ChatApiController /api/chat/stream 은 이미 소비.
- 주의: chat.js의 attach=true 는 SSE 재연결이지 파일 첨부가 아님.

재사용할 API / 클래스:
- api/AttachmentController.java — POST /api/attachments/upload (files + optional sessionId)
  → AttachmentDto(id,name,size,contentType,url)
- dto/ChatRequestDto.java — attachmentIds; api/ChatApiController.java
- AttachmentService, AttachmentContextHandler, ChatAttachmentQuestionDetector
- 선택: POST /api/attachments/inspect (OCR 미리보기)
- 쓰지 말 것(기본값): POST /api/attachments/conversation-archive/ingest
- ASR: ConversateCloudStt.transcribe(byte[] pcm) (complete_utterance)
- 스트리밍 Display audio/start|chunk 는 Fold6/Display 전용 — /chat에 통째로 이식 금지
- 마이크 패턴 참고: static/conversate/pcm-worklet.js, static/assets/display/display-voice.js

THE ONE 심: main/resources/templates/chat-ui.html + main/resources/static/js/chat.js

A) 첨부 뼈대
- 컴포저: hidden input[type=file multiple] + 클립 버튼 + 선택 파일 chip 목록
- 업로드: FormData → POST /api/attachments/upload (files, 가능하면 sessionId)
- 반환 id 보관 → 전송 시 /api/chat/stream (또는 /api/chat) JSON에 attachmentIds 포함
- 선택: inspect 미리보기. 기본으로 archive ingest 호출 금지
- 한도: public.request-budget.chat.max-attachment-ids 준수

B) 음성 STT 뼈대
- 같은 컴포저에 push-to-talk mic (PCM Int16; pcm-worklet.js 패턴 재사용)
- 기존 ConversateCloudStt.transcribe 를 얇은 chat용 래퍼로 호출
  (예: POST /api/chat/transcribe 또는 assist one-shot). Display pairing/Fold6 세션 필수 금지
- 전사 결과를 #messageInput에 넣고 사용자가 고친 뒤 Send
- Deepgram/Soniox/로컬 whisper 새 클라이언트 금지 — conversate.* 빈 재사용
- 배포 전: 공개 /chat 프로필에서 conversate.enabled / conversate.asr.* 확인

설정 노브 (이름만): conversate.enabled, conversate.asr.enabled,
conversate.asr.provider, conversate.asr.cloud.utterance-provider /
CONVERSATE_STT_UTTERANCE_PROVIDER, Soniox/Deepgram env 키 이름만,
public.request-budget.chat.max-attachment-ids, multipart max size

성공 기준:
- /chat에서 파일 선택→업로드→전송 메시지에 attachmentIds가 실려 답변 컨텍스트에 반영
- mic → 전사 텍스트가 입력창에 들어가 전송 가능
- Next frontend/ 미수정. archive ingest/Display 스트리밍을 /chat 기본 경로로 쓰지 않음
- 최소 diff; 시크릿 출력·push·히스토리 재작성 금지. soft-auto git만

검증:
- 브라우저로 /chat 열어 paperclip·mic UI 확인
- 작은 txt/pdf 첨부 후 질문 → 세션 attachment 반영
- mic 한 문장 → textarea 채움 → Send
- Network: upload 200 + stream body에 attachmentIds
