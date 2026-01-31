/**
//* [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
//* Module: Unknown
//* Role: config
//* Thread-Safety: appears stateless.
//*/
/* agent-hint:
id: Unknown
role: config
//*/
package com.abandonware.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "llm")
public record LLMProperties(
        @DefaultValue("auto") String engine,          // auto | jlama | djl | onnx | tf
        @DefaultValue("tjake/Llama-3.2-1B-Instruct-JQ4") String modelId,
        @DefaultValue("0.7") double temperature,
        @DefaultValue("256") int maxTokens,
        @DefaultValue("false") boolean preload
) {}