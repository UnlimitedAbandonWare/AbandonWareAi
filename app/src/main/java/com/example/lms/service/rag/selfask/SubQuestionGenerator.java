package com.example.lms.service.rag.selfask;

import java.util.List;
import java.util.Map;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.selfask.SubQuestionGenerator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.selfask.SubQuestionGenerator
role: config
*/
public interface SubQuestionGenerator {
    List<SubQuestion> generate(String userQuery, Map<String,Object> ctx);
}