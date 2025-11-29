# PATCH_NOTES_src_95

## 개요
- **멀티모델 운영**을 위해 모델 매니페스트(`configs/models.manifest.yaml`)와 브릿지 컴포넌트 추가.
- `AgentApplication` 스캔 범위 확장(`com.example.lms`) → LLM 빈 미등록으로 인한 대화 불능 해결.
- `DynamicChatModelFactory` 개선 → `baseUrl` 항상 명시, 키 접두어 제한 제거(OpenAI‑호환 게이트웨이 호환).
- 운영자/에이전트용 문서 추가:
  - `docs/system_prompt__kchat_gpt_pro.multimodel.md` (지시서)
  - `traits/_ko.md` ( 붙여넣기 자리)
- `app/src/main/resources/application.yml`에 멀티모델 섹션 추가.

## 변경 파일
- app/src/main/java/com/abandonware/ai/agent/AgentApplication.java (스캔 확장)
- src/main/java/com/example/lms/llm/DynamicChatModelFactory.java (대체)
- src/main/java/com/example/lms/manifest/** (신규 4파일)
- app/src/main/resources/application.yml (섹션 추가)
- configs/models.manifest.yaml (신규)
- docs/system_prompt__kchat_gpt_pro.multimodel.md (신규)
- traits/_ko.md (신규)

## 실행 전제
- 게이트웨이 키를 환경변수로 주입:
  - OpenRouter: `export OPENROUTER_API_KEY=sk-or-v1-...` (또는 `OPENAI_API_KEY`에 동일 값)
  - Groq: `export GROQ_API_KEY=...` (선택, 모델 카탈로그에 포함됨)
- 필요 시 게이트웨이 베이스 URL 지정:
  - `export OPENAI_BASE_URL=https://openrouter.ai/api`

## 부트 & 점검
```bash
./gradlew -p app clean bootRun

# 헬스체크
curl -s localhost:8080/actuator/health

# LLM 핑(게이트웨이)
curl -s -H "Authorization: Bearer $OPENAI_API_KEY" \
     -H "Content-Type: application/json" \
     -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}]}' \
     $OPENAI_BASE_URL/v1/chat/completions | jq
```

## 주의
- 실제 API 키 값은 로그/응답에 절대 출력하지 않습니다.
- Per‑model 서로 다른 base‑url 라우팅은 v2 과제입니다(현재는 단일 게이트웨이).
