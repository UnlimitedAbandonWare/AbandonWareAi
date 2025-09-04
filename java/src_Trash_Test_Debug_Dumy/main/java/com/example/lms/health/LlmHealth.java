
        package com.example.lms.health;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class LlmHealth implements ApplicationRunner {
  @org.springframework.beans.factory.annotation.Autowired
    private com.example.lms.config.ModelGuard modelGuard;


  /**
   * Provider selection.  Supports "openai", "groq" or "local-llm".  Defaults
   * to OpenAI when unspecified.
   */
  @Value("${llm.provider:openai}")
  private String provider;

  // --- OpenAI configuration ---
  /** API key for OpenAI.  Prefer the openai.api.key property and fall back to the
   *  OPENAI_API_KEY environment variable when unspecified.  Using the
   *  environment variable enables configuration via secrets without editing
   *  application.yml. */
  @Value("${openai.api.key:${OPENAI_API_KEY:}}")
  private String openAiApiKey;
  /** Base URL for the OpenAI API.  Defaults to the official REST endpoint. */
  @Value("${openai.base-url:https://api.openai.com/v1}")
  private String openAiBaseUrl;
  /** Explicitly configured model name for OpenAI requests. */
  @Value("${openai.chat-model:}")
  private String openAiModelName;

  // --- Groq configuration ---
  /** API key for Groq.  Prefer groq.api.key property and fall back to the
   *  GROQ_API_KEY environment variable. */
  @Value("${groq.api.key:${GROQ_API_KEY:}}")
  private String groqApiKey;
  /** Base URL for Groq.  Defaults to the Groq OpenAI-compatible endpoint. */
  @Value("${groq.base-url:https://api.groq.com/openai/v1}")
  private String groqBaseUrl;
  /** Explicitly configured Groq model.  When unspecified a safe default is used. */
  @Value("${groq.chat-model:llama-3.1-8b-instant}")
  private String groqModelName;

  // --- Local LLM configuration (optional) ---
  /** Enable a local LLM provider.  When true the API key and base URL
   *  configured below will be used in place of OpenAI or Groq. */
  @Value("${local-llm.enabled:false}")
  private boolean localLlmEnabled;
  /** API key for the local LLM provider. */
  @Value("${local-llm.api-key:}")
  private String localLlmApiKey;
  /** Base URL for the local LLM provider. */
  @Value("${local-llm.base-url:}")
  private String localLlmBaseUrl;
  /** Explicitly configured local LLM model name. */
  @Value("${local-llm.model:}")
  private String localLlmModelName;

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

  /** Logger for health check informational output. */
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmHealth.class);

  @Override
  public void run(ApplicationArguments args) {
    // Fail-soft health check.  Do not throw exceptions on configuration
    // problems; instead log errors and return.  This ensures that the
    // application continues to start even when the LLM is misconfigured.
    try {
      // Determine configuration based on provider.  Local LLM settings take precedence
      // over Groq and OpenAI.  Cleanse the API keys to strip quotes and whitespace.
      String key;
      String effectiveBaseUrl;
      String chatModel;
      if (localLlmEnabled) {
        // Override provider for logging when local LLM is enabled
        provider = "local-llm";
        key = cleanse(localLlmApiKey);
        effectiveBaseUrl = localLlmBaseUrl;
        chatModel = localLlmModelName;
      } else if ("groq".equalsIgnoreCase(provider)) {
        key = cleanse(groqApiKey);
        effectiveBaseUrl = groqBaseUrl;
        chatModel = (groqModelName != null && !groqModelName.isBlank())
                ? groqModelName
                : modelGuard.fallbackFor("groq");
      } else {
        // Default to OpenAI
        provider = "openai";
        key = cleanse(openAiApiKey);
        effectiveBaseUrl = openAiBaseUrl;
        chatModel = (openAiModelName != null && !openAiModelName.isBlank())
                ? openAiModelName
                : modelGuard.fallbackFor("openai");
      }
      // Key must not be blank
      if (key == null || key.isBlank()) {
        log.error("[LLM Health Check] Provider '{}'의 API 키가 비어있습니다.", provider);
        return;
      }
      // Validate key prefix matches selected provider
      if ("groq".equalsIgnoreCase(provider) && key.startsWith("sk-")) {
        log.error("[LLM Health Check] provider=groq 인데 키 형식이 OpenAI(sk-)처럼 보입니다.");
        return;
      }
      if ("openai".equalsIgnoreCase(provider) && key.startsWith("gsk_")) {
        log.error("[LLM Health Check] provider=openai 인데 키 형식이 Groq(gsk_)처럼 보입니다.");
        return;
      }
      // Determine the model to use; fall back via ModelGuard when not provided
      String resolvedModel = (chatModel != null && !chatModel.isBlank())
              ? chatModel
              : modelGuard.fallbackFor(provider);
      OpenAiChatModel model = OpenAiChatModel.builder()
              .apiKey(key)
              .modelName(modelGuard.requireAllowedOrFallback(resolvedModel, provider))
              .baseUrl(effectiveBaseUrl)
              .build();
      // Mask the API key for logging.  Show first 7 and last 4 characters when length allows.
      String maskedKey = key.length() >= 12 ? key.substring(0, 7) + "..." + key.substring(key.length() - 4) : "****";
      log.info("[LLM Health Check] Provider={}, BaseURL={}, Model={}, Key={}", provider, effectiveBaseUrl, resolvedModel, maskedKey);
      // Perform a ping to detect connectivity issues.  Catch and log any exception.
      try {
        model.chat("ping");
      } catch (Exception ex) {
        log.error("LlmHealth ping failed: {}", ex.getMessage(), ex);
      }
    } catch (Exception ex) {
      // catch-all for unexpected errors; log and return
      log.error("LlmHealth failed: {}", ex.getMessage(), ex);
    }
  }
}