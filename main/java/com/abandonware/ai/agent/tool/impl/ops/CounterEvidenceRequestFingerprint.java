package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.example.lms.trace.SafeRedactor;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CounterEvidenceRequestFingerprint {

    private static final int MAX_TEXT_LENGTH = 4_096;
    private static final int MAX_QUERY_LENGTH = 512;
    private static final int MAX_DOCUMENTS = 9;
    private static final int MAX_MILLIS = 60_000;

    private CounterEvidenceRequestFingerprint() {
    }

    static String canonicalRequestHash(String originalClaim,
                                       String decisionQuestion,
                                       Object rawQueries,
                                       Object rawBudget) {
        String claim = requiredText(originalClaim, "original_claim_required", MAX_TEXT_LENGTH);
        String question = requiredText(decisionQuestion, "decision_question_required", MAX_TEXT_LENGTH);
        EnumMap<QuerySlot, String> queries = parseQueries(rawQueries);
        RetrievalBudget budget = parseBudget(rawBudget);
        StringBuilder canonical = new StringBuilder("operator-probe-request.v1");
        appendCanonical(canonical, claim);
        appendCanonical(canonical, question);
        for (QuerySlot slot : QuerySlot.values()) {
            appendCanonical(canonical, slot.name());
            appendCanonical(canonical, queries.get(slot));
        }
        appendCanonical(canonical, String.valueOf(budget.maxDocuments()));
        appendCanonical(canonical, String.valueOf(budget.maxMillis()));
        return SafeRedactor.hashValue(canonical.toString());
    }

    private static EnumMap<QuerySlot, String> parseQueries(Object raw) {
        if (!(raw instanceof List<?> rows) || rows.size() != QuerySlot.values().length) {
            throw ToolInvocationException.badRequest("counter_query_slots_invalid");
        }
        EnumMap<QuerySlot, String> queries = new EnumMap<>(QuerySlot.class);
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                throw ToolInvocationException.badRequest("counter_query_slots_invalid");
            }
            QuerySlot slot;
            try {
                slot = QuerySlot.valueOf(requiredText(map.get("slot"), "counter_query_slots_invalid", 64)
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw ToolInvocationException.badRequest("counter_query_slots_invalid");
            }
            String query = requiredText(map.get("query"), "counter_query_required", MAX_QUERY_LENGTH);
            if (queries.putIfAbsent(slot, query) != null) {
                throw ToolInvocationException.badRequest("counter_query_slots_invalid");
            }
        }
        if (queries.size() != QuerySlot.values().length) {
            throw ToolInvocationException.badRequest("counter_query_slots_invalid");
        }
        return queries;
    }

    private static RetrievalBudget parseBudget(Object raw) {
        if (!(raw instanceof Map<?, ?> budget)) {
            throw ToolInvocationException.badRequest("counter_retrieval_budget_required");
        }
        int maxDocuments = requiredInt(budget.get("maxDocuments"), 3, MAX_DOCUMENTS,
                "counter_document_budget_invalid");
        int maxMillis = requiredInt(budget.get("maxMillis"), 1, MAX_MILLIS,
                "counter_time_budget_invalid");
        return new RetrievalBudget(maxDocuments, maxMillis);
    }

    private static void appendCanonical(StringBuilder out, String value) {
        String safe = value == null ? "" : value;
        out.append('|').append(safe.length()).append(':').append(safe);
    }

    private static String requiredText(Object raw, String errorCode, int maxLength) {
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isEmpty() || value.length() > maxLength) {
            throw ToolInvocationException.badRequest(errorCode);
        }
        return value;
    }

    private static int requiredInt(Object raw, int min, int max, String errorCode) {
        try {
            int value = raw instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(raw).trim());
            if (value < min || value > max) {
                throw ToolInvocationException.badRequest(errorCode);
            }
            return value;
        } catch (ToolInvocationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw ToolInvocationException.badRequest(errorCode);
        }
    }

    private enum QuerySlot {
        AUTHORITATIVE_CONSTRAINT,
        ALTERNATIVE_OR_UNKNOWN,
        PROVENANCE_AND_TIME
    }

    private record RetrievalBudget(int maxDocuments, int maxMillis) {
    }
}
