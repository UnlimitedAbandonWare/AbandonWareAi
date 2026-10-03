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
    @NotNull @DecimalMin("0") @DecimalMax("2") private Double temperature;
    @NotNull @DecimalMin("0") @DecimalMax("1") private Double topP;
    @NotNull @DecimalMin("-2") @DecimalMax("2") private Double frequencyPenalty;
    @NotNull @DecimalMin("-2") @DecimalMax("2") private Double presencePenalty;
    @NotNull @Min(1) private Integer maxTokens;
    @NotNull private Boolean useRag;
    @NotNull private Boolean useWebSearch;
    @NotBlank @Pattern(regexp = "AUTO|OFF|FORCE_LIGHT|FORCE_DEEP") private String searchMode;
    @NotBlank @Pattern(regexp = "adaptive|evidence_only") private String ragAnswerPolicy;
    @NotBlank private String defaultsVersion;

    @AssertTrue(message = "sampling defaults must be finite")
    public boolean isFinite() {
        return java.util.stream.Stream.of(temperature, topP, frequencyPenalty, presencePenalty)
                .allMatch(v -> v != null && Double.isFinite(v));
    }

    public Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("model", model); values.put("modelSelectionMode", modelSelectionMode);
        values.put("temperature", temperature); values.put("topP", topP);
        values.put("frequencyPenalty", frequencyPenalty); values.put("presencePenalty", presencePenalty);
        values.put("maxTokens", maxTokens); values.put("useRag", useRag);
        values.put("useWebSearch", useWebSearch); values.put("searchMode", searchMode);
        values.put("ragAnswerPolicy", ragAnswerPolicy);
        return Map.copyOf(values);
    }
}
