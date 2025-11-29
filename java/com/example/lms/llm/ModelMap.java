package com.example.lms.llm;


/**
 * Centralises the mapping between logical model tiers and the concrete model
 * names.  The actual model identifiers are resolved from environment
 * variables to allow administrators to configure the underlying LLMs at
 * deployment time without recompiling the code.  Each accessor falls back
 * to a sensible default if the environment variable is unset.
 *
 * <p>This class does not perform any API calls.  It simply returns the
 * configured model names.  See {@link QuotaManager} for quota enforcement
 * and {@link DynamicChatModelFactory} for actual model instantiation.</p>
 */
public final class ModelMap {

    private ModelMap() {}

    /**
     * Returns the model name for the low-cost, Flash-Lite tier.  This model
     * should be used for high-frequency, inexpensive tasks such as query
     * correction, named entity extraction and short summarisation.  The
     * environment variable {@code GEMINI_FLASH_LITE_MODEL} is consulted,
     * otherwise a default of {@code gemini-2.5-flash-lite} is used.
     */
    public static String getFlashLiteModel() {
        String m = System.getProperty("GEMINI_FLASH_LITE_MODEL");
        return (m == null || m.isBlank()) ? "gemini-2.5-flash-lite" : m;
    }

    /**
     * Returns the model name for the mid-tier Flash model.  This model is
     * appropriate for moderate cost tasks such as the SelfAskHandler and
     * cross-encoder reranking when limited to a handful of candidates.  The
     * environment variable {@code GEMINI_FLASH_MODEL} is consulted,
     * otherwise {@code gemini-2.5-flash} is returned.
     */
    public static String getFlashModel() {
        String m = System.getProperty("GEMINI_FLASH_MODEL");
        return (m == null || m.isBlank()) ? "gemini-2.5-flash" : m;
    }

    /**
     * Returns the model name for the high-tier Pro model.  This model is
     * reserved for one-shot escalations on high risk queries or when the
     * generated answer requires deep reasoning and longer outputs.  The
     * environment variable {@code GEMINI_PRO_MODEL} is consulted,
     * otherwise {@code gemini-2.5-pro} is returned.
     */
    public static String getProModel() {
        String m = System.getProperty("GEMINI_PRO_MODEL");
        return (m == null || m.isBlank()) ? "gemini-2.5-pro" : m;
    }

    /**
     * Returns the model name for large context summarisation.  Tasks such
     * as EvidenceRepairHandler may require feeding in hundreds of thousands
     * of tokens; these should use a model with an enlarged context window.
     * The environment variable {@code GEMINI_LARGE_CONTEXT_MODEL} is
     * consulted, otherwise {@code gemini-1.5-flash-legacy} is used.
     */
    public static String getLargeContextModel() {
        String m = System.getProperty("GEMINI_LARGE_CONTEXT_MODEL");
        return (m == null || m.isBlank()) ? "gemini-1.5-flash-legacy" : m;
    }

    /**
     * Returns the embedding backend identifier.  This value is used by the
     * embedding model configuration in LangChain4j.  The environment
     * variable {@code EMBEDDING_BACKEND} is consulted, otherwise
     * {@code text-embedding-3-small} is returned.  If you wish to use
     * Gemini embeddings then set this variable to the appropriate name
     * (e.g. {@code gemini-embedding-001}).
     */
    public static String getEmbeddingModel() {
        String m = System.getProperty("EMBEDDING_BACKEND");
        return (m == null || m.isBlank()) ? "text-embedding-3-small" : m;
    }

    /**
     * Returns the model name for the final answer generation.  Under the
     * guidelines, GPT Pro (e.g. gpt-4) should be used as the
     * primary generator and only on final drafts.  The environment
     * variable {@code GPT_PRO_MODEL} is consulted, otherwise
     * {@code gpt-4} is returned.
     */
    public static String getGptProModel() {
        String m = System.getProperty("GPT_PRO_MODEL");
        return (m == null || m.isBlank()) ? "qwen2.5-7b-instruct" : m;
    }
}