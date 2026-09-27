package com.example.lms.service.understanding;

import org.springframework.stereotype.Component;



/**
 * Builds a prompt instructing a language model to convert a free-form answer
 * into a strict JSON object conforming to the {@link com.example.lms.dto.answer.AnswerUnderstanding}
 * schema. The prompt includes both the user's original question and the final
 * assistant answer. The model must reply with strictly valid JSON and no prose.
 */
@Component
public class AnswerUnderstandingPromptBuilder {

    private static final String HEADER =
            "You are an assistant that converts final answers into structured summaries. "
                    +  "Return a SINGLE JSON object only.";

    private static final String SCHEMA = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "properties": {
            "tldr": {"type": ["string", "null"]},
            "keyPoints": {"type": ["array", "null"], "items": {"type": "string"}},
            "actionItems": {"type": ["array", "null"], "items": {"type": "string"}},
            "decisions": {"type": ["array", "null"], "items": {"type": "string"}},
            "risks": {"type": ["array", "null"], "items": {"type": "string"}},
            "followUps": {"type": ["array", "null"], "items": {"type": "string"}},
            "glossary": {"type": ["array", "null"], "items": {"type": "object", "properties": {"term": {"type": "string"}, "definition": {"type": "string"}}, "required": ["term", "definition"]}},
            "entities": {"type": ["array", "null"], "items": {"type": "object", "properties": {"name": {"type": "string"}, "type": {"type": "string"}}, "required": ["name", "type"]}},
            "citations": {"type": ["array", "null"], "items": {"type": "object", "properties": {"url": {"type": "string"}, "title": {"type": "string"}}, "required": ["url", "title"]}},
            "confidence": {"type": ["number", "null"], "minimum": 0, "maximum": 1}
          },
          "required": ["tldr","keyPoints","actionItems","decisions","risks","followUps","glossary","entities","citations","confidence"]
        }
        """;

    /**
     * Escape markdown backticks by doubling them.
     */
    private String escape(String text) {
        if (text == null) return "";
        return text.replace("`", "``");
    }

    public String build(String question, String finalAnswer) {
        String qEsc = escape(question);
        String aEsc = escape(finalAnswer);

        // IMPORTANT: text block must have a newline immediately after the opening delimiter.
        return String.format("""
                %s

                You will receive a Question and an Answer.
                Convert the Answer into a JSON object that follows the provided JSON Schema.
                      Strictly output JSON only - no comments, no prose.
                      Do NOT wrap the JSON in markdown code fences.
                      Do NOT insert raw line breaks inside any string value; use \\\\n instead.
                      Do NOT add trailing commas.
                

                ### JSON Schema
                %s

                ### Question
                ```
                %s
                ```

                ### Answer
                ```
                %s
                ```
                """, HEADER, SCHEMA, qEsc, aEsc);
    }
}