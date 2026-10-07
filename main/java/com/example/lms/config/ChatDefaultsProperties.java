package com.example.lms.config;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "chat.defaults")
@Validated
@Data
public class ChatDefaultsProperties {
    @NotBlank private String model;
    @NotBlank @Pattern(regexp = "preferred|strict|auto") private String modelSelectionMode;
    @NotNull private com.example.lms.domain.enums.ExecutionMode executionMode = com.example.lms.domain.enums.ExecutionMode.AUTO;
    @NotNull @DecimalMin("0") @DecimalMax("2") private Double temperature;
    @NotNull @DecimalMin("0") @DecimalMax("1") private Double topP;
    @NotNull @DecimalMin("-2") @DecimalMax("2") private Double frequencyPenalty;
    @NotNull @DecimalMin("-2") @DecimalMax("2") private Double presencePenalty;
    @NotNull @Min(1) private Integer maxTokens;
    @NotNull private Boolean useRag;
    @NotNull private Boolean useWebSearch;
    @NotNull private Boolean googleSearchRescueEnabled = false;
    @NotBlank @Pattern(regexp = "AUTO|OFF|FORCE_LIGHT|FORCE_DEEP") private String searchMode;
    @NotBlank @Pattern(regexp = "adaptive|evidence_only") private String ragAnswerPolicy;
    @NotBlank private String defaultsVersion;
    private String customInstructions = "";
    private String responseTone = "neutral";
    private String responseLength = "standard";
    private String responseLanguage = "auto";
    private String memoryMode = "hybrid";

    @AssertTrue(message = "sampling defaults must be finite")
    public boolean isFinite() {
        return java.util.stream.Stream.of(temperature, topP, frequencyPenalty, presencePenalty)
                .allMatch(v -> v != null && Double.isFinite(v));
    }

    public Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("model", model); values.put("modelSelectionMode", modelSelectionMode);
        values.put("executionMode", executionMode.name());
        values.put("temperature", temperature); values.put("topP", topP);
        values.put("frequencyPenalty", frequencyPenalty); values.put("presencePenalty", presencePenalty);
        values.put("maxTokens", maxTokens); values.put("useRag", useRag);
        values.put("useWebSearch", useWebSearch); values.put("searchMode", searchMode);
        values.put("googleSearchRescueEnabled", googleSearchRescueEnabled);
        values.put("ragAnswerPolicy", ragAnswerPolicy);
        values.put("customInstructions", customInstructions); values.put("responseTone", responseTone);
        values.put("responseLength", responseLength); values.put("responseLanguage", responseLanguage);
        values.put("memoryMode", memoryMode);
        return Map.copyOf(values);
    }
}
