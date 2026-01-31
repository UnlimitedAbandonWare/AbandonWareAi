package com.example.lms.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import java.time.Duration;



/**
 * Configure the default {@link ChatModel} used by the application.  This
 * implementation selects between OpenAI and Groq based on the
 * {@code llm.provider} property.  Both providers expose an OpenAI-compatible
 * API, so the LangChain4j {@link OpenAiChatModel} can be used for either.
 *
 * The configuration reads the appropriate base URL, API key and model name
 * from the {@code llm.<provider>.*} namespace.  These values are defined in
 * {@code application.yml} and can be overridden via environment variables.
 */
// Disabled by default: canonical ChatModel beans live in LlmConfig.
@Configuration
@ConditionalOnProperty(name = "legacy.model-config.enabled", havingValue = "true")
public class ModelConfig {

  /**
   * Construct a {@link ChatModel} using the provider specified by
   * {@code llm.provider}.  When the property is set to {@code groq} the
   * Groq endpoint and credentials are used; otherwise the OpenAI
   * configuration is applied.  Additional providers can be added with
   * further {@code else if} branches.
   *
   * @param env Spring environment for property lookup
   * @return a configured {@link ChatModel}
   */
  @Bean
  public ChatModel chatModel(Environment env) {
    String provider = env.getProperty("llm.provider", "openai");
    // Resolve generic llm properties first
    String baseUrl = env.getProperty("llm.base-url");
    String apiKey  = env.getProperty("llm.api-key");
    String model   = env.getProperty("llm.chat-model");
    if (provider != null && provider.equalsIgnoreCase("groq") || provider.equalsIgnoreCase("local")) {
      // When Groq is selected use the provided base URL, API key and model.  Groq
      // exposes an OpenAI-compatible API; the base URL must include the /openai/v1 path.
      return OpenAiChatModel.builder()
          .baseUrl(baseUrl)
          .apiKey(apiKey)
          .modelName(model)
          .timeout(Duration.ofSeconds(30))
          .build();
    }
    // Fallback: OpenAI configuration.  If generic llm properties are blank
    // attempt to resolve provider specific openai.* values.
    String openBaseUrl = (baseUrl != null && !baseUrl.isBlank())
            ? baseUrl
            : env.getProperty("openai.api.url", "${llm.base-url}");
    String openApiKey  = (apiKey != null && !apiKey.isBlank())
            ? apiKey
            : env.getProperty("openai.api.key");
    String openModel   = (model != null && !model.isBlank())
            ? model
            : env.getProperty("openai.chat.model.default");
    return OpenAiChatModel.builder()
        .baseUrl(openBaseUrl)
        .apiKey(openApiKey)
        .modelName(openModel)
        .timeout(Duration.ofSeconds(30))
        .build();
  }
}