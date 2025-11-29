package com.example.lms.service.answer;

import com.example.lms.service.verbosity.VerbosityProfile;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;



@Service
public class AnswerExpanderService {

    private static String buildExpandPrompt(String draft, VerbosityProfile vp) {
        String sections = (vp.sections() == null || vp.sections().isEmpty())
                ? ""
                : "- Use these section headers (Korean): " + String.join(", ", vp.sections()) + "\n";
        return """
               You expand the given Korean draft without changing its facts.
               - Target minimum length: %d Korean words.
               - Keep the original meaning and claims; clarify with examples, rationale, caveats, and next steps.
               %s
               - Do not add speculative claims. Keep citations inline if any exist.
               ## DRAFT
               %s
               """.formatted(Math.max(1, vp.minWordCount()), sections, draft);
    }

    public String expandWithLc(String draft, VerbosityProfile vp, ChatModel model) {
        try {
            var msgs = java.util.List.of(
                    SystemMessage.from("Expand with structure and rich details; do not invent facts."),
                    UserMessage.from(buildExpandPrompt(draft, vp))
            );
            return model.chat(msgs).aiMessage().text();
        } catch (Exception e) {
            return draft;
        }
    }
}