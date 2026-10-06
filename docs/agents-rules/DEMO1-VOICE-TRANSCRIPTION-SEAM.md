<!-- BEGIN DEMO1-VOICE-TRANSCRIPTION-SEAM -->
## Voice Transcription (ASR) Seam Boundary

- 마이크 PCM 수신은 **페어링 폰/모바일/웹 클라이언트**가 담당하고, 렌즈(안경)는
  텍스트·힌트 수신만 한다. 상세 계약: `docs/design/UNHOOKED_SEAMS_CONTRACT_SPEC.md`
  §S2. 프로브: `python -B scripts/probe_unhooked_seams_guard.py --dry-run`.
- A1 게이트: `/api/assist/display/audio/*`만 PCM을 받고 `audioGuard()`가
  `conversate.display.audio.enabled`(기본 `false`)/`asr==null`을 이중 차단한다.
  렌즈 폴링(`/api/assist/display/lens*`)에 오디오 디코딩을 붙이지 않는다.
- A2 생산자 바인딩: `paired_phone_required` + `requireProducer(clientId)` —
  바인딩되지 않은 클라이언트·렌즈 측 요청은 PCM을 넣을 수 없다.
- A3 전사 백엔드는 `ConversateAsrBridge` 하나로 수렴(`conversate.asr.provider`:
  `local` 자식 whisper | `deepgram`/`soniox`/`auto` 클라우드). 새 ASR 경로를
  추가할 때 브리지를 우회하는 직접 스트림을 만들지 않는다. 운영 기본값은
  브라우저/클라우드 우선(로컬 whisper는 opt-in — spec §S2.A7 미결).
- A4 수용된 전사는 `ChatConversationContext.Transcript`(서버 스코프, RAM-only,
  ≤12 turns/≤2000 tokens, redacted toString)로만 LLM 컨텍스트에 진입한다.
  원시 오디오·전사 원문을 로그/메모리/프롬프트에 직접 넣지 않는다.
- A5 오디오 lane 장애(`ASR_INPUT_LOST`, transport close)는 `text_fallback`으로
  강등하고 텍스트/힌트 경로를 죽이지 않는다. 실물 마이크/안경 의존 검증은
  `NOT_RUN_DEVICE`로 분리 표기.
<!-- END DEMO1-VOICE-TRANSCRIPTION-SEAM -->
