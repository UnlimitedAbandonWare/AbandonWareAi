package ai.abandonware.nova.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Guard to prevent routing "Responses-only" OpenAI models to the Chat Completions endpoint.
 *
 * <p>This is designed as a fail-soft ladder:
 * <ul>
 *   <li>FAIL_FAST: return a safe assistant message (no blank chat)</li>
 *   <li>SUBSTITUTE_CHAT: swap to a chat-compatible model and proceed</li>
 *   <li>ROUTE_RESPONSES: call /v1/responses via an adapter ChatModel</li>
 * </ul>
 * </p>
 */
@ConfigurationProperties(prefix = "nova.orch.model-guard")
@Validated
public class NovaModelGuardProperties {

    public enum Mode {
        FAIL_FAST,
        SUBSTITUTE_CHAT,
        ROUTE_RESPONSES
    }

    /** Enable the model guard. */
    private boolean enabled = true;

    /** Guard behavior when a chat-incompatible model is requested. */
    private Mode mode = Mode.SUBSTITUTE_CHAT;

    /**
     * If true, the guard triggers only when the effective baseUrl points to api.openai.com.
     * This avoids interfering with local gateways that may provide their own compatibility layer.
     */
    private boolean openAiBaseOnly = true;

    /** Model to substitute when {@link Mode#SUBSTITUTE_CHAT} is active. */
    private String substituteChatModel = "";

    /**
     * Prefixes for models that are known (or strongly suspected) to be "Responses-only" and fail on
     * /v1/chat/completions with errors like "This is not a chat model".
     *
     * <p>Matching rule: exact match or prefix + '-' snapshot suffix.</p>
     */
    private List<String> responsesOnlyPrefixes = new ArrayList<>(List.of(
            "gpt-5.2-pro",
            "gpt-5-pro",
            "gpt-5.2-codex",
            "gpt-5.1-codex",
            "gpt-5-codex",
            "o3-deep-research",
            "o4-mini-deep-research"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public boolean isOpenAiBaseOnly() {
        return openAiBaseOnly;
    }

    public void setOpenAiBaseOnly(boolean openAiBaseOnly) {
        this.openAiBaseOnly = openAiBaseOnly;
    }

    public String getSubstituteChatModel() {
        return substituteChatModel;
    }

    public void setSubstituteChatModel(String substituteChatModel) {
        this.substituteChatModel = substituteChatModel;
    }

    public List<String> getResponsesOnlyPrefixes() {
        return responsesOnlyPrefixes;
    }

    public void setResponsesOnlyPrefixes(List<String> responsesOnlyPrefixes) {
        this.responsesOnlyPrefixes = responsesOnlyPrefixes;
    }
}
