
        package com.example.lms.health;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;



@Component
public class LlmHealth implements ApplicationRunner {

  // openaiApiKey 필드 제거: llm.api-key로 통일
  @Value("${llm.api-key:}")
  private String llmApiKey;

  @Value("${llm.provider:openai}")
  private String provider;
  @Value("${llm.base-url:}")
  private String baseUrl;
  @Value("${llm.chat-model:gpt-5-mini}")
  private String modelName;

  private static String cleanse(String s) {
    if (s == null) return "";
    return s.trim().replace("\"", "").replace("'", "");
  }

  // provider에 맞는 키 형식인지 검증하는 헬퍼 메서드 추가
  private static boolean isGroqKey(String k) {
    return k.startsWith("gsk_");
  }

  private static boolean isOpenAiKey(String k) {
    return k.startsWith("sk-") || k.startsWith("sk_") || k.startsWith("sk-proj-");
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      // 1) 키 로드 로직 변경: 오직 llm.api-key 만 사용
      String key = cleanse(llmApiKey);
      if (key.isBlank()) {
        throw new IllegalStateException("[LLM 점검] llm.api-key 가 비어있습니다.");
      }

      // 2) 최종 base URL 계산
      String effectiveBaseUrl;
      if (baseUrl != null && !baseUrl.isBlank()) {
        effectiveBaseUrl = baseUrl; // 사용자가 지정한 값 우선
      } else if ("groq".equalsIgnoreCase(provider)) {
        effectiveBaseUrl = "https://api.groq.com/openai/v1";
      } else {
        effectiveBaseUrl = "https://api.openai.com/v1"; // OpenAI 기본 URL에 /v1 추가
      }

      // 3) 키 형식 검증 로직 추가 (provider와 키 프리픽스 매칭)
      if ("groq".equalsIgnoreCase(provider) && !isGroqKey(key)) {
        throw new IllegalStateException("[LLM 점검] provider=groq 인데 키 형식이 OpenAI(sk-)처럼 보입니다.");
      }
      if ("openai".equalsIgnoreCase(provider) && !isOpenAiKey(key)) {
        throw new IllegalStateException("[LLM 점검] provider=openai 인데 키 형식이 Groq(gsk_)처럼 보입니다.");
      }

      // 4) 모델 생성 (기존과 동일)
      OpenAiChatModel model = OpenAiChatModel.builder()
              .apiKey(key)
              .modelName(modelName)
              .baseUrl(effectiveBaseUrl)
              .build();

      // 5) 로그 + 핑 (기존과 동일)
      String maskedKey = key.length() >= 12 ? key.substring(0, 7) + "/* ... *&#47;" + key.substring(key.length() - 4) : "****";
      System.out.println("[LLM Health Check] BaseURL=" + effectiveBaseUrl +
              ", Model=" + modelName + ", Key=" + maskedKey);

      model.chat("ping");

    } catch (Exception e) {
      // 예외 처리 메시지는 기존과 동일하게 유지
      throw new IllegalStateException(
              """
              [LLM 키/엔드포인트/모델 점검 실패]
              - llm.provider(openai|groq), base-url, api-key, chat-model 조합을 확인하세요.
              - Groq: https://api.groq.com/openai/v1
              - OpenAI: https://api.openai.com
              """, e);
    }
  }
}