package com.acme.aicore.domain.ports;

import com.acme.aicore.domain.model.Prompt;
import com.acme.aicore.domain.model.PromptParams;
import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.SessionContext;
import java.util.List;




/**
 * Abstraction responsible for constructing prompts from session context and
 * ranked documents.  Implementations may incorporate system instructions,
 * user queries and evidence into a single prompt object.
 */
public interface PromptPort {
    Prompt buildPrompt(SessionContext ctx, List<RankedDoc> docs, PromptParams params);
}