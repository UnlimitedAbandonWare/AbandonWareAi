package com.example.lms.guard;

import com.example.lms.config.ConfigValueGuards;

/** Ensures provider/model/apiKey are all present. Avoids implicit fallbacks. */
public final class ModelGuard {
  private ModelGuard() {}
  public static void assertConfigured(String provider, String apiKey, String model) {
    if (provider == null || provider.isBlank()) throw new IllegalStateException("llm.provider missing");
    boolean missingApiKey = "openai-compatible".equalsIgnoreCase(provider)
            ? ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)
            : ConfigValueGuards.isMissing(apiKey);
    if (missingApiKey) throw new IllegalStateException(provider + " api key missing");
    if (model == null || model.isBlank()) throw new IllegalStateException(provider + " default model missing");
  }
}
