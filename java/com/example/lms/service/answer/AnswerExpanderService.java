package com.example.lms.service.answer;

import com.example.lms.service.verbosity.VerbosityProfile;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;
import java.util.List;



@Service
public class AnswerExpanderService {

    /**
     * Backward-compatible overload used by legacy call sites that do not provide
     * explicit evidence snippets. Delegates to the full variant with an empty list.
     */
    public String expandWithLc(String draft, VerbosityProfile vp, ChatModel model) {
        return expandWithLc(draft, vp, model, java.util.Collections.emptyList());
    }



    private static String buildExpandPrompt(String draft, VerbosityProfile vp, List<String> evidenceSnippets) {
    String sections = (vp.sections() == null || vp.sections().isEmpty())
            ? ""
            : "- Use these section headers (Korean): " + String.join(", ", vp.sections()) + "\n";

    String evidenceBlock = "";
    if (evidenceSnippets != null && !evidenceSnippets.isEmpty()) {
        evidenceBlock = "\n[검색 결과 기반 정보]\n"
                + String.join("\n", evidenceSnippets)
                + "\n\n위 정보만을 신뢰 가능한 외부 근거로 사용해.\n"
                + "검색 결과에 없는 사실을 '추측해서' 만들지 마.\n\n";
    }

    return """
           You are a Korean technical editor that ONLY restructures existing content.

           HARD RULES:
           - DO NOT add any new facts, entities, character names, places, dates, or numbers not present in the DRAFT or EVIDENCE.
           - DO NOT guess or invent background information to reach the target length.
           - If the DRAFT lacks sufficient detail AND no EVIDENCE is provided, reply EXACTLY: [NO_EVIDENCE]

           ALLOWED:
           - Reorder sentences for better flow
           - Add transition phrases using only info already in DRAFT or EVIDENCE
           - Split into paragraphs or sections
           %s

           Target minimum length: %d Korean words (but quality over quantity - never fabricate).

           ## DRAFT
           %s
           """.formatted(evidenceBlock, Math.max(1, vp.minWordCount()), draft);
}

    public String expandWithLc(String draft, VerbosityProfile vp, ChatModel model, List<String> evidenceSnippets) {
        try {
            var msgs = java.util.List.of(
                    SystemMessage.from("You are a cautious Korean editor. Restructure only; never invent."),
                    UserMessage.from(buildExpandPrompt(draft, vp, evidenceSnippets))
            );
            String result = model.chat(msgs).aiMessage().text();

            // [NO_EVIDENCE] 프로토콜 처리
            if (result != null && result.trim().equals("[NO_EVIDENCE]")) {
                return null; // 확장하지 않고 원문 그대로 사용
            }

            // 너무 짧게 요약한 경우도 원문 사용
            if (result != null && result.length() < draft.length() * 0.8) {
                return null;
            }

            return result;
        } catch (Exception e) {
            return null; // 확장 실패 시 원문 사용
        }
    }
}
