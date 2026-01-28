package ai.abandonware.nova.orch.llm;

import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * A ChatModel that always returns a precomputed assistant message.
 *
 * <p>Used to keep UX non-blank in "expected failure" scenarios.</p>
 */
public final class ExpectedFailureChatModel implements ChatModel {

    private final String message;
    private final String nameForDebug;

    public ExpectedFailureChatModel(String message, String nameForDebug) {
        this.message = (message == null) ? "" : message;
        this.nameForDebug = (nameForDebug == null) ? "" : nameForDebug;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(message))
                .build();
    }

    @Override
    public String toString() {
        return "ExpectedFailureChatModel(" + nameForDebug + ")";
    }
}
