package com.rc111.merge21.llm;

import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.Duration;

@Component
public class LlamaCppClient {
    private final OkHttpClient http;
    private final String baseUrl;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public LlamaCppClient(@Value("${llamacpp.server.base-url:http://localhost:8080}") String baseUrl,
                          @Value("${llamacpp.server.timeout-ms:30000}") long timeoutMs) {
        this.baseUrl = baseUrl;
        this.http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofMillis(timeoutMs))
                .readTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public String complete(String prompt, int maxTokens, double temperature) throws IOException {
        String body = "{"
                + "\"prompt\":\"" + escape(prompt) + "\","
                + "\"n_predict\":" + maxTokens + ","
                + "\"temperature\":" + temperature
                + "}";
        Request req = new Request.Builder()
                .url(baseUrl + "/completion")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("llama.cpp server error: " + res.code());
            return res.body() != null ? res.body().string() : "";
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
