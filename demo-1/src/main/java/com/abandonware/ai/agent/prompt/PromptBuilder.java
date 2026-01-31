package com.abandonware.ai.agent.prompt;

import com.abandonware.ai.agent.model.ChatContext;
import com.abandonware.ai.agent.model.ChatMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class PromptBuilder {
    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    public static String build(ChatContext ctx) {
        StringBuilder sb = new StringBuilder();
        // base system prompt
        sb.append(loadResource("/traits/base.md"));
        // mode-specific trait
        if (ctx.getMode() == ChatMode.SAFE) {
            sb.append("\n").append(loadResource("/traits/safe_mode.md"));
        } else if (ctx.getMode() == ChatMode.BRAVE) {
            sb.append("\n").append(loadResource("/traits/brave_mode.md"));
        } else if (ctx.getMode() == ChatMode.ZERO_BREAK) {
            sb.append("\n").append(loadResource("/traits/zerobreak_mode.md"));
        }
        sb.append("\nUserQuestion: ").append(ctx.getUserQuestion() == null ? "" : ctx.getUserQuestion());
        return sb.toString();
    }

    private static String loadResource(String path) {
        try (InputStream is = PromptBuilder.class.getResourceAsStream(path)) {
            if (is == null) {
                log.warn("Trait resource not found: {}", path);
                return "";
            }
            return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Failed to load trait {}: {}", path, e.getMessage());
            return "";
        }
    }
}