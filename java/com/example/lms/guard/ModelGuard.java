package com.example.lms.guard;


/** Ensures provider/model/apiKey are all present. Avoids implicit fallbacks. */
public final class ModelGuard {
  private ModelGuard() {}
  public static void assertConfigured(String provider, String apiKey, String model) {
    if (provider == null || provider.isBlank()) throw new IllegalStateException("llm.provider missing");
    if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException(provider + " api key missing");
    if (model == null || model.isBlank()) throw new IllegalStateException(provider + " default model missing");
  }
}