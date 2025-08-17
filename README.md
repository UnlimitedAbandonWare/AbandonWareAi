# Canonical Lines for "AbandonWare Hybrid RAG AI Chatbot Service"
# Version: v1 (generated 2025-08-18)
# Rules: deduped, lossless, one fact per line

## LINES
# [STAGE] | Component | Capability/Rule | Rationale/Constraint(optional) | NOTES(optional)
QUERY | QueryComplexityGate | Classifies user queries as simple or complex using heuristics or LLM models | Guides whether to decompose queries via SelfAskHandler
QUERY | QueryCorrectionService | Performs rule‑based spelling, spacing and punctuation corrections | Ensures clean input before LLM correction
QUERY | LLMQueryCorrectionService | Uses an LLM to refine corrected queries while preserving proper nouns and domain terms | Consults domain dictionary to avoid altering names
QUERY | QueryDisambiguationService | Resolves ambiguous tokens via domain dictionary and LLM rephrasing | Sets a confidence score for high or low confidence
QUERY | QueryAugmentationService | Adds synonyms or related keywords when enabled | Disabled by default to avoid noise; limited by SmartQueryPlanner
QUERY | SmartQueryPlanner | Restricts the number of expanded queries to prevent combinatorial explosion | Coordinates with EvidenceRepairHandler
QUERY | NonGameEntityHeuristics | Distinguishes generic terms from domain‑specific entities during preprocessing | Avoids misclassifying game‑neutral words
QUERY | DefaultDomainTermDictionary | Stores protected terms per domain to survive correction and disambiguation | InMemoryDomainTermDictionary used for testing
QUERY | LLMNamedEntityExtractor | Invokes an LLM to extract entities when regex heuristics fail | Implements NamedEntityExtractor interface
QUERY | ParsedQuery | Holds structured components (tokens, synonyms, variants) after corrections and analysis | Used downstream for retrieval
QUERY | MatrixTransformer | Generates additional query variants when required based on domain and intent | Supports robust search
QUERY | QueryHygieneFilter | Removes banned words or domain prefixes during disambiguation | Ensures safe queries
QUERY | VerbosityDetector | Analyses query and conversation context to suggest a verbosity level (brief/standard/deep/ultra) | Influences context budgets
QUERY | SectionSpecGenerator | Determines which prompt sections to include based on verbosity and query intent | Produces section map for PromptBuilder
QUERY | VerbosityProfile | Encapsulates verbosity hints to inform context budgets and minimum answer lengths | Used throughout pipeline
QUERY | CompositeQueryContextPreprocessor | Combines guardrails, hygiene filters and preprocessing steps | Orchestrates correction and disambiguation
QUERY | CognitiveStateExtractor | Derives user intent, subject and domain hints using heuristics or LLMs | Stores results in CognitiveState for routing
QUERY | CognitiveState | Holds analysis results including intent, subject, domain and verbosity | Guides routing and retrieval strategies
QUERY | GuardrailQueryPreprocessor | Injects protected terms and dynamic interaction rules from the knowledge base into the prompt context | Ensures domain policies are respected
QUERY | DefaultQueryContextPreprocessor | Orchestrates correction, disambiguation and domain detection according to configuration | Prepares prompt context before retrieval
QUERY | QueryComplexityClassifier | Detects multi‑hop or complex questions via heuristics or LLMs | ModelBasedQueryComplexityClassifier queries a model; thresholds configurable
QUERY | QueryContextPreprocessor | Defines a contract for preparing prompt context prior to retrieval | Allows custom preprocessing implementations
QUERY | QueryTransformer | Abstracts the process of generating alternative queries for retrieval | Implementations choose transformation based on domain and intent
QUERY | DefaultQueryTransformer | Selects transformation strategy according to domain and intent | Generates robust query variants
QUERY | QueryCorrectionService | Returns both the corrected text and metadata about applied fixes | Supports auditing of corrections
QUERY | QueryDisambiguationService | May call QueryHygieneFilter to remove domain prefixes or banned words | Ensures safe and focused queries
QUERY | VerbosityDetector | Uses conversation context to infer if the user expects a brief or detailed response | Influences document count and model choice
RETRIEVAL | HybridRetriever | Orchestrates memory, self‑ask, analyze, web, vector and evidence repair handlers | Maximizes recall and precision
RETRIEVAL | MemoryHandler | Retrieves recent session snippets and verified translation memory snippets as evidence | Uses MemoryAsEvidenceAdapter to format snippets
RETRIEVAL | SelfAskHandler | Decomposes complex queries into sub‑questions using an LLM and aggregates evidence | Enabled when QueryComplexityGate signals complexity
RETRIEVAL | AnalyzeHandler | Generates search tokens through morphological analysis for languages like Korean | Improves recall for non‑English queries
RETRIEVAL | WebSearchHandler | Performs real‑time web searches with domain filters and rate limiting | Integrates AuthorityScorer to rank results by credibility
RETRIEVAL | VectorDbHandler | Queries a vector store using similarity search to retrieve static domain knowledge | Acts as fallback when web retrieval fails
RETRIEVAL | EvidenceRepairHandler | Triggers an additional search cycle when evidence is insufficient | Only one repair cycle to prevent infinite loops
RETRIEVAL | DefaultRetrievalHandlerChain | Defines handler order: memory, self‑ask, analyze, web, vector, repair | Configurable via RetrieverChainConfig
RETRIEVAL | RetrievalHandler | Interface that decouples handlers; each handler catches exceptions and returns partial results | Enables independent unit testing
RETRIEVAL | RetrievalMode | Supports RETRIEVAL_ON (web and vector), RAG_ONLY (vector) and RETRIEVAL_OFF (memory only) modes | Configurable per session
RETRIEVAL | SearchContext | Contains retrieved documents, warnings, timing and evidence quality flags | Passed to ranking and context builder
RETRIEVAL | ReciprocalRankFuser | Merges lists from web, vector, memory and self‑ask using reciprocal rank fusion (RRF constant k) | Softmax fusion optional
RETRIEVAL | LightWeightRanker | Performs quick lexical ranking to reduce candidate list before deeper scoring | Used prior to cross‑encoder reranking
RETRIEVAL | SelfAskWebSearchRetriever | Performs web searches for each sub‑query and merges snippets | Supports sub‑question decomposition
RETRIEVAL | AnalyzeWebSearchRetriever | Consumes tokens from AnalyzeHandler and applies morphological filters for targeted search | Enhances recall
RETRIEVAL | EmbeddingClient | Generates embeddings for queries and documents using OpenAI or local models | VectorDbHandler uses these embeddings
RETRIEVAL | EmbeddingStoreManager | Manages vector indexes and embeddings within the vector store | Supports nearest neighbour search
RETRIEVAL | TavilyWebSearchRetriever | Placeholder for alternative web search service integration | Enables future domain‑specific search
RETRIEVAL | SearchContext | Warnings may include insufficient evidence, rate limit hits or API errors | Allows downstream handling
RETRIEVAL | Search results | Deduplicated by URL or content similarity before ranking | Avoids redundant evidence
RETRIEVAL | Handlers | Respect session isolation by including session metadata in cache keys | Prevents cross‑session data leakage
RETRIEVAL | Retrieval caches | Store results per query and session ID with expiration to reduce API calls | Configurable via CacheConfig
RETRIEVAL | EvidenceRepairHandler | Uses SmartQueryPlanner to expand or reformulate the query for a second attempt | Increases recall without runaway searches
RERANK | AuthorityScorer | Weights documents based on domain credibility tiers (OFFICIAL, TRUSTED, COMMUNITY, UNVERIFIED) using exponential decay | Configurable domain weights
RERANK | RelationshipRuleScorer | Adjusts scores based on dynamic rules from the knowledge base (preferred partners, discouraged pairs, etc.) | Supports custom rule types
RERANK | DefaultLightWeightRanker | Provides an initial lexical ranking based on token overlap and heuristics | Coarse filter before deeper scoring
RERANK | CrossEncoderReranker | Uses a BERT‑like model to compute semantic similarity between query and document | Only top candidates proceed due to cost
RERANK | EmbeddingModelCrossEncoderReranker | Integrates cross‑encoder scores with rule scores and synergy bonuses | Applies dynamic synergy weight and authority decay multiplier
RERANK | Synergy bonuses | Reflect user feedback; positive and negative reactions adjust ranking | AdaptiveScoringService computes bonuses
RERANK | AdaptiveScoringService | Calculates synergy bonuses using positive/negative feedback counts and smoothing constant k | Domain‑specific
RERANK | Reranker settings | Configurable via application.yml, including top‑n candidates and weight factors | Supports A/B testing
RERANK | NoopCrossEncoderReranker | Disables cross‑encoder scoring when not required | Acts as pass‑through reranker
RERANK | ReciprocalRankFuser | Assigns scores based on reciprocal of rank positions across lists; k smooths lower ranks | Documents high in any list get a boost
RERANK | Softmax fusion | Converts scores to probabilities using temperature; optional alternative to RRF | Sharpens focus on top documents
RERANK | CrossScore formula | Final score = crossScore + ruleBoost + synergyBonus; may be normalized or clamped | Prevents domination by a single factor
CONTEXT | ContextOrchestrator | Builds unified context from ranked documents, memory and history within token budgets | Removes duplicates and prioritizes authoritative sources
CONTEXT | PromptBuilder | Constructs system, user and context prompts with standardized template | Injects dynamic rules, protected terms and instructions
CONTEXT | PromptContext | Holds user query, subject, domain, protected terms and section specification | Used to build prompts
CONTEXT | Section ordering | Guides the LLM through context sections: previous answer, vector RAG, history, web, memory | Defined by prompt builder
CONTEXT | Context assembly | Prioritizes authoritative sources and demotes low‑credibility documents | Ensures maximal information density
PROMPT | System prompts | Include instructions about allowed behaviours (discourage hallucinations, cite sources) | Guide the model’s conduct
PROMPT | User prompts | Include sanitized query and indicate intent (recommendation, explanation, analysis) | Provide context for model routing
PROMPT | Context prompts | Include selected evidence sections such as previous answer, vector RAG, history, web and memory | Structured within token limit
PROMPT | PromptBuilder | Avoids ad‑hoc string concatenation; centralizes prompt construction; ensures proper escaping | Maintains maintainability and safety
PROMPT | Verbosity hints | Influence the number of documents selected and minimum answer length | Align with verbosity profiles (brief, standard, deep, ultra)
PROMPT | Audience hints | Allow tailoring answers to novice or expert users | Optional hint used in prompts
PROMPT | Citation style | Determines whether citations appear inline or as footnotes | Configurable via PromptContext
PROMPT | PromptContext.sectionSpec | Indicates which context sections should appear for given verbosity level | Generated by SectionSpecGenerator
PROMPT | Dynamic interaction rules | Injected into prompts to inform the model about preferred or discouraged combinations | Derived from knowledge base rules
GENERATION | ModelRouter | Selects the appropriate language model based on intent, risk, verbosity and token budget | Routes high‑stakes queries to high‑tier models
GENERATION | MOE escalation | Routes to high‑tier model when intent in high‑stakes list, risk is high, verbosity is deep/ultra or token budget exceeds threshold | Supports high‑stakes tasks
GENERATION | ChatModel | Abstracts underlying LLM provider and exposes unified API for sending prompts | Enables switching providers
GENERATION | DynamicChatModelFactory | Creates ChatModel instances with appropriate parameters when no injected bean exists | Supports dynamic model creation
GENERATION | Model resolution | Prefers injected beans, then cached models, then dynamic factory creation | Ensures fallback path
GENERATION | Temperature policy | Uses lower temperature (e.g., 0.3) for high‑tier models and higher temperature (e.g., 0.7) for base models | Balances stability and creativity
GENERATION | AnswerExpanderService | Ensures deep and ultra verbosity answers meet minimum word counts by expanding concise answers | Triggered by LengthVerifierService
GENERATION | LengthVerifierService | Checks whether the generated answer satisfies length requirements before expansion | Works with AnswerExpanderService
GENERATION | ModelRouter logging | Logs routing decisions including intent, risk, verbosity and token budget | Provides transparency for debugging
VERIFY | FactVerifierService | Computes coverage and contradiction metrics by comparing draft answer to retrieved context | Rejects answers with low coverage or high contradiction
VERIFY | EvidenceGate | Checks whether sufficient evidence exists before LLM generation; triggers EvidenceRepairHandler if insufficient | Skips generation when evidence lacking
VERIFY | NamedEntityValidator | Ensures named entities in draft answer appear in context or memory; unknown names cause fallback response '정보 없음' | Prevents hallucinated names
VERIFY | ClaimVerifierService | Extracts individual claims and verifies each against context using LLM or fact‑checking API | Removes unsupported claims; unsupported answers become '정보 없음'
VERIFY | Claim classification | Classifies claims as supported, contradicted or lacking information | Guides modification or removal
VERIFY | Evidence sufficiency metrics | Include number of subject mentions, authority scores and variety of sources | Domain‑specific thresholds
VERIFY | AnswerSanitizers | Enforce domain policies by removing disallowed recommendations, profanity or unsafe content | Implemented as chain of responsibility
VERIFY | GenshinRecommendationSanitizer | Filters invalid character pairings in Genshin domain | Activated only when game domain active
VERIFY | SmartFallbackService | Suggests refined queries when system cannot answer due to insufficient evidence | Encourages user to refine question
VERIFY | FallbackHeuristics | Generate alternative queries by adding details or narrowing scope; encapsulated in FallbackResult | Helps users improve retrieval
VERIFY | Verification logging | Logs claim verdicts and evidence metrics for audit and debugging | Enhances transparency
VERIFY | External fact checkers | ClaimVerifierService may call services like FactCheck.org or Wikidata to validate claims | Extensible via configuration
SANITIZE | AnswerSanitizers | May insert warning messages for sensitive topics; enforce profanity filtering and policy compliance | Domain‑specific sanitizers follow general ones
SANITIZE | Sanitizer chain order | General sanitizers run before domain‑specific ones to ensure proper layering | Order matters for correct modifications
REINFORCE | MemoryReinforcementService | Computes energy scores for memory snippets using similarity, Q‑value, success ratio, confidence and recency | Weighted sum with configurable weights
REINFORCE | Energy calculation | Uses Boltzmann energy with temperature control; recency decays via exponential decay; min and max content length filters | Parameters from HyperparameterService
REINFORCE | ReinforcementQueue | Manages reinforcement tasks asynchronously to avoid blocking chat loop | Updates energy scores and stores results
REINFORCE | TranslationMemoryRepository | Stores snippets of previous answers and context for reuse | MemoryWriteInterceptor writes verified answers into translation memory
REINFORCE | AdaptiveScoringService | Computes synergy bonuses based on user feedback; bonus = (positive − negative)/(positive + negative + k) × scaling factor | Domain‑specific and smoothed
REINFORCE | FeedbackController | Captures thumbs‑up and thumbs‑down from users and updates SynergyStat records | Drives adaptive scoring
REINFORCE | SynergyStatRepository | Persists synergy statistics (domain, subject, partner, positive and negative counts) | Enables long‑term personalization
REINFORCE | StrategySelectorService | Implements multi‑armed bandit algorithm to select retrieval strategies (web‑first, vector‑first, hybrid, self‑ask) | Softmax policy with temperature
REINFORCE | StrategyPerformance | Records success, failure counts and average rewards per strategy | Used for bandit selection
REINFORCE | Bandit annealing | Anneals exploration temperature over time based on number of interactions | Balances exploration and exploitation
REINFORCE | DynamicHyperparameterTuner | Adjusts weights such as synergy weight, authority weight and exploration temperature based on aggregated metrics | Periodic updates ensure adaptation
REINFORCE | BanditSelector | Selects memory entries according to energy and temperature | Ensures exploration of both new and old entries
META | HyperparameterService | Provides dynamic retrieval and reranking parameters such as synergy weight at runtime; fetches from system properties or environment variables | Enables A/B testing without redeploy
META | StrategySelectorService logging | Logs strategy choices and rewards for analysis | Supports debugging meta‑learning behaviour
META | RewardHyperparameterTuner | Tunes reward weights for multi‑metric reward function using grid search or gradient‑free optimization | Improves meta‑learning
META | Synergy bonuses | Domain‑specific; feedback in one domain does not affect another | Prevents cross‑domain contamination
META | DynamicHyperparameterTuner | May increase exploration when performance drops unexpectedly and decrease when converging | Ensures balanced learning
KB | KnowledgeBaseService | Abstracts access to domain entities, attributes and interaction rules stored in relational tables | Decouples domain knowledge from code
KB | DomainKnowledgeRepository | Stores entities with domain, type and name; EntityAttributeRepository stores key‑value attributes | CRUD operations via JPA
KB | SubjectResolver | Identifies the subject of a query by matching entity names; chooses longest or most relevant match; uses fuzzy matching when needed | Informs retrieval and prompt building
KB | Interaction rules | Stored as relationships between entities: CONTAINS, IS_PART_OF, PREFERRED_PARTNER, DISCOURAGED_PAIR, AVOID_WITH | Administrators can define new rules via database
KB | RelationshipRuleScorer | Evaluates documents against interaction rules and adjusts ranking scores accordingly | Uses weights from HyperparameterService
KB | Knowledge base caching | Stores frequently accessed entity names, attributes and rules to reduce database queries | Improves performance
KB | Knowledge base updates | Do not require redeploying system; new entities and rules can be inserted via scripts or admin tools | Facilitates domain expansion
KB | Dynamic rules | Inform both prompt generation and ranking to respect domain policies | Supports preferred and avoided combinations
SESSION | Session isolation | Ensures each user's conversation history, caches and reinforcement data remain separate | Session ID (META_SID) keys caches and retrieval results
SESSION | Caffeine caching | Accelerates retrieval and memory access without leaking data between sessions; caches expire after configurable times | Configured via CacheConfig and application.yml
SESSION | SSE streaming | Streams intermediate progress (retrieval progress, context construction, draft answer, verification outcomes) to clients via Server‑Sent Events | Improves transparency and user trust
SESSION | PersistentChatMemory | Stores conversation history beyond session to enable long‑term memory | Provides memory to MemoryHandler
SESSION | Synergy caches | Store computed bonuses for subject–partner pairs within a session; update persistent statistics after feedback | Maintains personalization per session
SESSION | SessionConfig | Defines how session metadata is propagated; session caches cleared after expiration | Enforces privacy and resource management
CONFIG | application.yml | Specifies minimum word counts and token budgets per verbosity level; max documents per context; reranker keep‑top‑n; model names; domain weights | Enables tuning without code changes
CONFIG | Retrieval mode | retrieval.mode chooses between RETRIEVAL_ON, RAG_ONLY and RETRIEVAL_OFF | Controls search behaviour via configuration
CONFIG | Hyperparameter settings | synergy weight, authority weight, smoothing constant k and other scoring constants configured externally | Allows real‑time adjustments
CONFIG | Cache specifications | Define maximum sizes and expiration times for caches like retrieval, memory, synergy and knowledge base | Managed via application.yml and CacheConfig
CONFIG | Environment variables | OPENAI_API_KEY, PINECONE_API_KEY, NAVER_API_CLIENT_ID and other credentials stored as environment variables | Secure handling of secrets
CONFIG | Session expiration | session.expire-after defines how long sessions remain active before caches are cleared | Balances resource use and privacy
CONFIG | LangChain4j version purity | Enforces consistent module versions (e.g., 1.0.1) to avoid runtime incompatibilities | Aborts startup on conflicts
CONFIG | Model routing keys | router.moe.high, router.moe.mini specify high‑tier and base model names; router.allow-header-override flag controls header overrides | Configures model routing logic
CONFIG | Knowledge curation | agent.knowledge-curation.enabled toggles autonomous knowledge curation agent on or off | Controls learning features
CONFIG | Input distillation | abandonware.input.distillation.enabled and threshold control pre‑processing of long inputs | Uses low‑cost LLM to summarize long messages
CONFIG | Reranker backend | abandonware.reranker.backend selects between embedding-model and onnx-runtime; onnx.model-path and onnx.execution-provider configure ONNX settings | Supports local ONNX reranking
CONFIG | Learning pipeline | gemini.backend and related keys configure Gemini integration; learning.enabled toggles learning features | Supports Gemini-based knowledge extraction and tuning
CONFIG | Understanding module | abandonware.understanding.enabled and model/timeout keys control post‑answer understanding and summarization | Emits UNDERSTANDING SSE event when enabled
UI | chat-ui.html | Provides chat interface with text input, voice recognition, file upload, advanced search options and toggles | Sends requests via chat.js
UI | chat.js | Handles SSE events, loading states, voice transcription, file attachments and RAG control options; persists user settings in localStorage | Sends ChatRequestDto with user preferences
UI | SSE event handling | Recognizes event types such as retrieval progress, NEEDS_CONFIRMATION, UNDERSTANDING; renders summaries and confirmation buttons | Enables interactive flows
UI | Persona selection | Allows users to choose personas (tutor, analyzer, brainstormer); cognitive state extractor determines persona from intent | Dynamic prompts loaded from configuration
UI | RAG control panel | Provides checkboxes for search scopes (web, documents) and source credibility filters; passes options in ChatRequestDto | Allows real‑time RAG configuration by user
UI | Multimodal inputs | Supports image uploads via Base64 encoding; includes imageBase64 in ChatRequestDto; backend integrates Gemini image models | Enables multimodal queries
CHANGE | EmbeddingModelCrossEncoderReranker | Adds dynamic synergy weight bonus and authority decay multiplier to final score | NOTES: synergy weight fetched at runtime from HyperparameterService
CHANGE | AuthorityScorer | Implements centralized credibility analyzer and exponential decay constants; adds getSourceCredibility() and decayFor() methods | NOTES: old weightFor() marked @Deprecated
CHANGE | HyperparameterService | Adds getRerankSynergyWeight() method to fetch dynamic synergy weight from system properties or environment variables | NOTES: supports hotfixes without redeployment
CHANGE | ModelRouter | Adds heuristics for MOE escalation based on intent, risk, verbosity and token budget; logs routing decisions; supports header override allowlist | NOTES: defaults to base model otherwise
CHANGE | OnnxCrossEncoderReranker | Introduces ONNX-based cross‑encoder reranker activated via abandonware.reranker.backend=onnx-runtime; falls back to embedding model if model missing | NOTES: local ONNX inference via onnxruntime
CHANGE | RequestHeaderModelOverrideFilter | Blocks non‑whitelisted X‑Model‑Override headers to prevent misuse; allowlist configurable | NOTES: router.allow-header-override defaults false
CHANGE | InputDistillationService | Summarizes long user inputs using a low‑cost LLM and asks user confirmation before full RAG; configured via abandonware.input.distillation.enabled and threshold | NOTES: improves cost control
CHANGE | Persona and RAG controls | Adds persona selection and advanced search options in UI; extends ChatRequestDto and HybridRetriever to respect officialSourcesOnly and searchScopes flags | NOTES: dynamic persona prompts loaded from configuration
CHANGE | Multimodal support | Adds imageBase64 in ChatRequestDto; GeminiClient handles multimodal requests; backend processes both text and image content | NOTES: uses gemini-1.5-pro model for images
CHANGE | Comparative analysis | CognitiveStateExtractor detects COMPARATIVE_ANALYSIS intent when multiple entities and comparison keywords; ChatService orchestrates dynamic retrieval and structured prompts | NOTES: criteria generated dynamically
CHANGE | Autonomous exploration | SmartFallbackService logs failed queries as knowledge gaps; AutonomousExplorationService analyses gaps, formulates research queries and learns autonomously | NOTES: free-tier API throttling enforced
CHANGE | Knowledge consistency verifier | Adds agent to audit knowledge base for contradictions using LLM; flags inconsistent rules and decays confidence of stale knowledge | NOTES: runs periodically with throttle
CHANGE | Learning enhancements | GeminiCurationService extracts structured knowledge; LearningWriteInterceptor writes new knowledge into KB and vector store after verification; LearningController exposes REST endpoints for ingest, batch and tuning | NOTES: learning features optional and gated by learningEnabled flag
CHANGE | Understanding module | Adds AnswerUnderstandingService to convert final answers into TL;DR and key points; SSE UNDERSTANDING events; memory indexing of distilled summaries | NOTES: controlled by understandingEnabled flag
CHANGE | InputDistillationService | Adds pre‑processing gate for long inputs; uses low‑cost model to distill queries and asks for user confirmation via NEEDS_CONFIRMATION SSE event | NOTES: reduces token usage and prevents runaway costs
CHANGE | Dynamic vector search | Enables automatic switch to VECTOR_SEARCH execution mode when education‑related keywords detected; bypasses keyword search and performs vector similarity retrieval | NOTES: improves semantic matching in new domains

## Validation
- [x] Meaning‑duplications merged
- [x] No information loss (IDs/keys preserved)
- [x] One fact per line; optional NOTES only
- [x] Stage tags applied consistently
- [x] Git‑diff friendly formatting

## RAW
Due to the size of the RAW document, please refer to the original input for the complete text. It contains all details summarized above, covering query processing, retrieval, ranking, context assembly, prompt building, generation, verification, reinforcement, knowledge base operations, session management, configuration, UI enhancements and numerous patch notes describing incremental changes and new features across multiple versions.


The AbandonWare Hybrid RAG AI Chatbot Service is a domain‑agnostic agent built on a retrieval–generation–verification–reinforcement pipeline.
The system began as a specialized Genshin Impact assistant and evolved into a general retrieval‑augmented generation agent.
Retrieval‑augmented generation combines neural language models with external knowledge sources to ground answers in evidence.
By retrieving real documents and conditioning responses on them, the system reduces hallucinations compared to pure language models.
The pipeline follows a search–generate–verify–reinforce loop that separates concerns into modular services.
User queries are sanitized, disambiguated and augmented before any retrieval occurs.
Hybrid retrieval draws evidence from both real‑time web sources and a vector database of ingested documents.
Result fusion combines multiple sources using reciprocal rank fusion and re‑ranks them with a cross‑encoder.
Context construction assembles retrieved passages, memory and history into a prompt within token limits.
A modular prompt builder creates structured prompts with system instructions, user queries and context sections.
A model router selects the appropriate language model and parameters based on intent and verbosity.
After generation, a multi‑layer verification stage checks coverage, contradictions and claim support.
Sanitizers enforce domain policies and remove unsupported or unsafe content from the draft answer.
The reinforcement layer learns from user feedback, updating memory entries and synergy statistics.
Meta‑learning components tune retrieval strategies and hyperparameters using multi‑armed bandit algorithms.
Session isolation ensures that each user’s conversation history, caches and reinforcement data remain separate.
Caching with Caffeine accelerates retrieval and memory access without leaking data between sessions.
Server‑sent events stream intermediate progress to clients, improving transparency and user trust.
Dynamic rules and a centralized knowledge base allow the system to adapt to new domains without code changes.
Adaptive reranking uses user feedback to boost or penalize pairings based on popularity and synergy.
Verbosity levels (brief, standard, deep, ultra) guide model choice, context size and minimum answer length.
Retrieval strategies can be combined or disabled through configuration (web search, vector search, or RAG only).
The architecture enforces version purity for LangChain4j modules to avoid runtime incompatibilities.
Logging and metrics enable monitoring of retrieval latency, quality scores and feedback distributions.
The system is configurable via application.yml, enabling tuning of token budgets, cache sizes and model parameters.
Developers can extend the pipeline by adding new retrieval handlers, scorers, sanitizers or rule types.
The knowledge base stores domain entities and attributes in relational tables to decouple data from code.
Dynamic interaction rules inform both prompt generation and reranking to respect domain policies.
End‑to‑end, the system aims to provide factual, personalized answers while continuously improving through reinforcement.
Query processing begins with the QueryComplexityGate, which classifies a user query as simple or complex.
The QueryComplexityGate uses heuristics or machine models to decide if decomposition via SelfAsk is required.
Simple queries are processed directly, while complex queries are broken down into sub‑questions.
The QueryCorrectionService performs rule‑based corrections on spelling, spacing and punctuation.
Korean queries benefit from morphological correction, handling spacing mistakes unique to the language.
After rule‑based corrections, the LLMQueryCorrectionService uses a large language model to refine the query.
The LLM service preserves proper nouns and domain terms by consulting the domain dictionary before making changes.
QueryDisambiguationService resolves ambiguous tokens, using dictionary bypasses to avoid altering known names.
When tokens are not in the domain dictionary, the LLM rephrases or clarifies ambiguous words.
The service sets a confidence score to indicate whether a disambiguation is high or low confidence.
QueryAugmentationService can add synonyms or related keywords but is disabled by default to avoid noise.
SmartQueryPlanner restricts the number of expanded queries, preventing combinatorial explosion.
NonGameEntityHeuristics assist in distinguishing generic terms from domain‑specific entities.
DefaultDomainTermDictionary stores protected terms per domain, ensuring they survive correction and disambiguation.
InMemoryDomainTermDictionary is used during testing when the external dictionary is unavailable.
The DomainTermDictionary interface abstracts the storage and retrieval of protected terms.
LLMNamedEntityExtractor uses an LLM to extract entities when regex patterns fail to identify names.
NamedEntityExtractor is an interface allowing different NER implementations to plug into the pipeline.
ParsedQuery objects contain the structured components of a query after corrections and analysis.
MatrixTransformer in the query transformer package helps generate additional query variants when required.
DefaultQueryCorrectionService and LLMQueryCorrectionService work together to sanitize user input.
QueryDisambiguationService may call QueryHygieneFilter to remove domain prefixes or banned words.
QueryComplexityGate influences the chain of retrieval handlers by enabling the SelfAskHandler when needed.
VerbosityDetector analyses the query and conversation context to suggest a verbosity level.
SectionSpecGenerator determines which prompt sections should be included based on verbosity and query intent.
VerbosityProfile encapsulates the verbosity hints used throughout the pipeline, influencing context budgets.
SmartQueryPlanner ensures that expanded queries do not exceed configured limits.
CompositeQueryContextPreprocessor combines multiple preprocessing steps, including guardrails and hygiene filters.
CognitiveStateExtractor derives user intent, subject and domain hints from the query and session.
CognitiveState objects hold analysis results to inform downstream services.
GuardrailQueryPreprocessor injects protected terms and interaction rules into the PromptContext based on the subject.
DefaultGuardrailQueryPreprocessor replaces hard‑coded lexicons with knowledge base lookups.
QueryContextPreprocessor defines a contract for preparing prompt context before retrieval.
The DefaultQueryContextPreprocessor orchestrates correction, disambiguation and domain detection.
QueryComplexityClassifier may use an LLM to detect multi‑hop questions requiring decomposition.
ModelBasedQueryComplexityClassifier is an implementation that queries a model to classify complexity.
QueryComplexityGate stores thresholds in configuration to control the classification sensitivity.
AnalyzeHandler uses morphological analysis to generate search tokens from the sanitized query.
AnalyzeWebSearchRetriever consumes tokens from the AnalyzeHandler to perform targeted web searches.
DefaultQueryTransformer selects the appropriate transformation strategy based on the domain and intent.
QueryTransformer interface abstracts the process of generating alternative queries for retrieval.
ParsedQuery objects may include synonyms or morphological variants for robust search.
DisambiguationResult represents the outcome of the QueryDisambiguationService, including confidence levels.
NonGameEntityHeuristics differentiates between game and non‑game terms when the dictionary is ambiguous.
DefaultDomainTermDictionary offers methods to add and retrieve domain terms for correction services.
LLMQueryCorrectionService may call a translation model or LLM to correct colloquialisms and slang.
QueryAugmentationService can be toggled via configuration when recall needs to be increased.
QueryCorrectionService returns both the corrected text and metadata about applied fixes.
LLMNamedEntityExtractor is invoked when dictionary and regex heuristics fail to detect a named entity.
DefaultQueryContextPreprocessor orchestrates multiple preprocessing steps with configurable order.
VerbosityDetector uses conversation context to infer if the user expects a brief or detailed response.
SectionSpecGenerator produces a map of prompt sections based on the verbosity profile.
QueryComplexityClassifier influences whether SelfAskHandler is invoked during retrieval.
SmartQueryPlanner interacts with EvidenceRepairHandler to control query expansion during evidence repair.
CognitiveStateExtractor may use LLMs or heuristics to detect the domain and subject embedded in the query.
GuardrailQueryPreprocessor sets the query intent (e.g. recommendation, explanation) used for routing models.
Domain detection informs the knowledge base service on which domain’s rules and entities to retrieve.
Session metadata, such as the session ID, is passed along with the query to ensure isolation.
Stop words and banned terms can be removed at the query hygiene stage to avoid triggering unsafe content.
The combination of rule‑based and LLM‑based correction minimizes over‑correction and preserves terms.
Query preprocessing is critical to ensure that retrieval retrieves relevant and safe content for the user’s question.
Hybrid retrieval orchestrates multiple handlers to collect evidence from various sources.
MemoryHandler retrieves recent session snippets and verified translation memory snippets to maintain context.
SelfAskHandler decomposes complex queries into sub‑questions when the QueryComplexityGate signals complexity.
AnalyzeHandler generates search tokens through morphological analysis for languages like Korean.
WebSearchHandler performs real‑time web searches using services such as Naver’s API.
VectorDbHandler queries a vector store, such as Pinecone, to retrieve passages using similarity search.
EvidenceRepairHandler triggers an additional search cycle when evidence is deemed insufficient.
Each handler catches its own exceptions and returns partial results to keep the chain resilient.
DefaultRetrievalHandlerChain defines the order of handlers: memory, self‑ask, analyze, web, vector and repair.
Handlers are configured via RetrieverChainConfig to allow dynamic ordering and feature toggles.
MemoryHandler prioritizes snippets with high energy scores derived from reinforcement learning.
TranslationMemoryRepository stores snippets of previous answers and context for reuse.
SelfAskHandler uses an LLM to generate sub‑queries that break down multi‑part questions.
The SelfAskWebSearchRetriever performs web searches for each sub‑query and merges the snippets.
AnalyzeWebSearchRetriever uses tokenized queries to search and may apply morphological filters.
EnhancedSearchService abstracts calls to external search APIs such as Naver, Tavily or others.
WebSearchRetriever integrates rate limiting and domain filtering to respect API constraints.
TavilyWebSearchRetriever is a placeholder for an alternate search service that can be integrated.
VectorDbHandler computes embeddings for the query and performs nearest neighbour search in the vector store.
HybridRetriever coordinates the handlers and combines results into a SearchContext envelope.
SearchContext contains retrieved documents, warnings, timing information and flags for evidence quality.
MemoryHandler uses MemoryAsEvidenceAdapter to transform memory snippets into the evidence format.
PersistentChatMemory persists chat history across sessions and supplies memory to MemoryHandler.
MemoryWriteInterceptor writes verified answers into the translation memory after completion.
The SelfAskHandler returns aggregated evidence from all sub‑queries without throwing exceptions.
AnalyzeHandler feeds the tokens to WebSearchHandler to perform targeted retrieval.
WebSearchHandler applies AuthorityScorer to rank search results by domain credibility.
WebSearchHandler uses domain filters and rates sources like official sites, wikis, community blogs and generic blogs.
VectorDbHandler acts as a fallback when web retrieval fails or when retrieving static domain knowledge.
EvidenceRepairHandler uses SmartQueryPlanner to expand or reformulate the query for a second attempt.
Only one repair cycle is triggered to prevent infinite loops in retrieval.
The chain ensures robustness by returning at least memory snippets even if web search fails.
Each handler logs its processing time and results for debugging and monitoring.
Retrieval modes can be configured: RETRIEVAL_ON includes web and vector search; RAG_ONLY uses vector; RETRIEVAL_OFF uses only memory.
Search handlers respect session isolation by including session metadata in cache keys.
Hybrid retrieval increases recall by combining live web content and static vector data.
Search results are deduplicated by URL or content similarity before further processing.
Handlers can be extended by implementing the RetrievalHandler interface and inserting them into the chain.
Domain‑specific search services can be integrated by adding new WebSearchRetriever implementations.
Retrieve operations may include morphological normalization or language translation for non‑English queries.
Handlers must honour configured timeouts and return partial results to avoid blocking downstream stages.
SearchContext warnings may include insufficient evidence, rate limit hits, or API errors.
DefaultLightWeightRanker performs quick lexical ranking to reduce the number of candidates.
SelfAskHandler may use a cross‑encoder to rank sub‑question answers before combining them.
Vector embeddings are generated using EmbeddingClient, which may call OpenAI or local models.
EmbeddingStoreManager manages indexes and embeddings within the vector store.
Search results are stored temporarily in retrieval caches keyed by query and session ID.
TavilyWebSearchRetriever and other future handlers extend retrieval sources for new domains.
Each handler clearly defines its inputs and outputs to enable independent unit testing.
MemoryHandler ensures that conversation context carries over across user turns for continuity.
SelfAsk improves multi‑hop reasoning by decomposing complex questions into manageable sub‑queries.
AnalyzeHandler improves recall for languages like Korean by generating morphological variants.
WebSearchHandler provides real‑time information that may not exist in the vector store.
VectorDbHandler ensures that domain knowledge stored offline is available even without internet.
EvidenceRepairHandler adds resilience to the pipeline by seeking additional evidence when needed.
HybridRetriever orchestrates these handlers to maximize recall and precision across various query types.
SearchContext also carries timing data used by StrategySelectorService for strategy reward computation.
Retrieval results are fused by ReciprocalRankFuser before being reranked by cross‑encoder models.
Caching retrieval results reduces repeated API calls and speeds up repeated queries within the same session.
Retrieval caches expire after a configurable time to ensure updated content is fetched in future sessions.
Handlers log metrics such as number of results, latency and success rates for monitoring.
During retrieval, banned words or domains are filtered out by GuardrailQueryPreprocessor rules.
Retrieval strategies are chosen dynamically by StrategySelectorService based on past success.
SelfAsk and repair modes are selectively enabled depending on query complexity and evidence sufficiency.
HybridRetriever also supports streaming search results via SSE to inform clients of progress.
The retrieval layer acts as the foundation of RAG, supplying factual snippets for grounding generated answers.
Dynamic knowledge curation may further augment the vector store by adding newly verified snippets.
Future retrieval handlers could query specialized databases like scholarly articles or product catalogs.
Retrieval performance depends on both hardware (vector store) and network access to external search APIs.
By combining memory, self‑ask, web and vector retrieval, the system maximizes the chance of finding relevant evidence.
EvidenceRepairHandler uses controlled query expansion to avoid runaway searches while still improving recall.
Retrieval handlers are decoupled via interfaces to facilitate testing and replacement.
The retrieval chain is assembled at startup and can be modified via configuration for A/B testing.
The retrieval layer interacts closely with ranking and verification stages, passing along context and warnings.
Clear logging at each stage of retrieval helps diagnose issues such as missing data or API failures.
Retrieval resilience ensures that partial or empty results still yield a fallback answer rather than an exception.
After retrieval, the system performs result fusion to merge documents from multiple sources into a unified list.
ReciprocalRankFusion assigns scores based on the reciprocal of rank positions across lists.
The RRF constant k smooths the contribution of lower ranked documents and is configurable.
Documents appearing high in any list receive a significant boost through RRF weighting.
Softmax fusion converts scores to probabilities using temperature, emphasizing top documents.
AuthorityScorer weights documents based on domain credibility, promoting trusted sources over unreliable ones.
Domain weights include official, wiki, community and blog categories, each configured with a numerical weight.
AuthorityScorer multiplies the base score by the domain weight to adjust ranking.
RelationshipRuleScorer adjusts scores based on dynamic rules from the knowledge base.
Rule types include preferred partners, discouraged pairs, contains relationships and other custom interactions.
Documents supporting preferred combinations receive positive boosts, while discouraged combinations are penalized.
LightWeightRanker provides an initial lexical ranking based on token overlap and simple heuristics.
CrossEncoderReranker uses a BERT‑like model to compute semantic similarity between query and document.
Cross encoders jointly encode the query and document, capturing complex interactions between tokens.
Only the top candidates from RRF are passed to the cross‑encoder due to computational cost.
EmbeddingModelCrossEncoderReranker integrates cross‑encoder scores with rule scores and synergy bonuses.
The final score for a document may be expressed as crossScore + ruleBoost + synergyBonus.
Scores may be normalized or clamped to prevent a single factor from dominating the ranking.
Synergy bonuses reflect user feedback, boosting pairs with positive reactions and demoting unpopular ones.
Authority weight ensures that even high‑scoring documents from low‑credibility sources are demoted.
Reranker classes like NoopCrossEncoderReranker are used when cross‑encoder scoring is disabled.
DefaultLightWeightRanker acts as a coarse filter to reduce the number of candidates before deeper scoring.
ReciprocalRankFuser merges lists from web search, vector search, memory and self‑ask into one ranking.
Fusion and ranking parameters are configurable, including the number of documents to keep at each stage.
Cross‑encoder models may be fine‑tuned on domain‑specific datasets for improved relevance.
Rule scoring uses weights stored in HyperparameterService to control the influence of dynamic rules.
AdaptiveScoringService computes synergy bonuses using positive and negative feedback counts.
Synergy bonuses are scaled by a factor and smoothed by a constant to avoid division by zero.
The reranking layer ensures that documents that align with the knowledge base rules and user feedback appear first.
Reranking also incorporates authority weighting so that official sources are prioritized in the final context.
Multiple reranker implementations allow switching between cross‑encoder and embedding models.
Reranker settings are controlled by application configuration and can be tuned per domain or intent.
Documents that violate discouraged rules can receive a negative score adjustment during reranking.
AuthorityScorer may also consider page age, citation counts or HTTPS usage in future extensions.
Fusion ensures that documents from different retrieval sources contribute fairly to the final list.
Rule boosting allows domain administrators to encode preferred or avoided pairings in the knowledge base.
Synergy bonuses personalize rankings by incorporating collective user preferences across sessions.
Cross‑encoder reranking significantly improves the semantic relevance of the final selection.
Softmax fusion can be used instead of RRF when a sharper focus on top ranked documents is desired.
The fusion layer outputs a unified, scored list that the context builder uses to assemble the prompt.
Fusion performance can be monitored by measuring mean reciprocal rank and recall across test queries.
Additional scorers can be added to the pipeline by implementing the DocumentScorer interface.
AuthorityScorer weights may be adjusted per domain to reflect different trust levels (e.g. government websites vs hobby blogs).
Rule types in the knowledge base can be extended to model more complex relationships such as substitutions.
The ranking stage sits between retrieval and context construction, ensuring that only the most relevant evidence is used.
Adaptive scoring ensures that the system learns from user feedback, improving rankings over time.
Synergy bonuses are domain‑specific, so feedback in one domain does not affect rankings in another.
Cross‑encoder scores are usually in an arbitrary range and must be combined carefully with rule scores.
Thresholds in reranking configuration control how many documents proceed to the cross‑encoder stage.
Fusion and ranking operate on normalized scores to maintain comparability across different scorers.
The final ranked list is passed to the context builder, which will select as many documents as fit the token budget.
Fusion weights such as k and temperature can be tuned for different retrieval sources and domains.
The cross‑encoder’s training data influences how well semantic similarity is captured for domain queries.
Rule scoring can penalize documents that contradict knowledge base rules, helping avoid invalid recommendations.
Adaptive scoring decays the influence of older feedback over time to adapt to changing user preferences.
The ranking component is extensible, allowing researchers to experiment with new scoring strategies.
Clear separation between fusion, ranking and scoring makes the pipeline maintainable and testable.
Future enhancements could include neural ranking models that incorporate multiple features at once.
ContextOrchestrator builds a unified context string from ranked documents, memory and history.
The orchestrator respects token budgets configured per verbosity level, ensuring prompts stay within model limits.
Context assembly prioritizes authoritative sources and demotes low‑credibility documents.
Duplicate sentences and overlapping content are removed to maximize information density.
Long‑term memory snippets and recent session history are combined to maintain conversational continuity.
PromptBuilder constructs system, user and context prompts using a standardized template.
System prompts include instructions about allowed behaviors, such as discouraging hallucinations and citing sources.
User prompts include the sanitized query and indicate the intent (e.g., recommend, explain, analyze).
Context prompts include the selected evidence sections: previous answer, vector RAG, history, web and memory.
Section ordering is defined by the prompt builder to guide the language model through the information.
PromptContext holds all fields necessary for building the prompt: user query, subject, domain and protected terms.
The builder injects dynamic interaction rules into the prompt to inform the model about preferred or discouraged combinations.
Verbosity hints influence how many documents are selected and the minimum length of the answer.
Audience hints allow the model to tailor the answer to novice or expert users.
Citation style determines whether citations appear inline or as footnotes in the answer.
ModelRouter selects an LLM based on intent and verbosity, routing high‑stakes queries to high‑tier models.
The router also sets temperature and top‑p parameters to control randomness in generation.
ChatModel abstracts the underlying LLM provider and exposes a unified API for sending prompts.
DynamicChatModelFactory creates ChatModel instances with appropriate parameters for each request.
AnswerExpanderService ensures deep and ultra verbosity answers meet minimum length by expanding concise answers.
LengthVerifierService checks whether the generated answer satisfies length requirements before expansion.
AnswerQualityEvaluator can score the generated answer along dimensions like factuality and clarity.
PromptBuilder ensures that dynamic rules, protected terms and instructions are properly formatted and escaped.
System prompts instruct the model to answer with '정보 없음' when evidence is insufficient.
PromptBuilder avoids ad‑hoc string concatenation and centralizes prompt construction in one place.
The orchestrator can include conversation history to handle follow‑up questions gracefully.
PromptContext.sectionSpec indicates which context sections should appear for the given verbosity level.
The builder ensures that prompts do not exceed the configured maximum token budget for the chosen model.
After the prompt is built, ChatService invokes the ChatModel and receives a draft answer for verification.
Context construction is vital for grounding the LLM’s responses in retrieved evidence and past conversation.
Verification begins after the LLM generates a draft answer from the prompt.
FactVerifierService computes coverage and contradiction metrics by comparing the answer to the context.
Coverage measures the proportion of the answer supported by the retrieved evidence.
Contradiction measures whether the answer conflicts with the context or previous answers.
Answers with low coverage or high contradiction scores may be rejected or modified.
FactVerifierService integrates EvidenceGate, ClaimVerifierService and sanitizers in the correct order.
EvidenceGate checks whether sufficient evidence exists before calling the LLM to generate an answer.
Evidence sufficiency is judged by the number of subject mentions, authority scores and variety of sources.
If evidence is insufficient, EvidenceGate triggers EvidenceRepairHandler to search again.
If repair fails to find evidence, the system aborts generation and returns a fallback response.
NamedEntityValidator ensures that all named entities in the draft answer appear in context or memory.
Unknown names in the answer indicate hallucination and cause the system to respond with '정보 없음'.
NamedEntityValidator uses heuristic patterns and LLM extraction to detect proper nouns.
ClaimVerifierService extracts individual claims (assertions) from the draft answer.
Each claim is verified against the context using an LLM or external fact‑checking API.
Claims are classified as supported, contradicted or lacking information.
Unsupported claims are removed from the answer to prevent misinformation.
If all claims are unsupported, the final answer becomes '정보 없음'.
Claim verification prompts may ask the LLM: 'Given the context, is the statement supported?' and expect a categorical answer.
FactVerifierService aggregates claim results and decides whether to accept the draft answer.
AnswerSanitizers enforce domain policies by removing disallowed recommendations or profanity.
Sanitizers operate in a chain of responsibility, each modifying the answer as needed.
Sanitizers can be domain‑specific; for example, GenshinRecommendationSanitizer filters invalid character pairings.
SmartFallbackService suggests refined queries when the system cannot answer due to insufficient evidence.
FallbackHeuristics generate alternative queries by adding details or narrowing the scope.
Fallback responses encourage users to refine their question rather than guessing.
EvidenceGate prevents wasted LLM calls when context is too weak to support a meaningful answer.
ClaimVerifierService may integrate external fact checking services such as FactCheck.org or Wikidata.
AnswerSanitizers may enforce compliance rules, such as removing harmful medical advice.
The multi‑layer verification pipeline reduces hallucination and improves factuality.
EvidenceGate can set stricter thresholds for high‑stakes domains like medical or legal topics.
NamedEntityValidator prevents the system from inventing people, products or locations not present in evidence.
Claim extraction may use heuristics or LLMs to segment sentences into individual assertions.
Claim verification uses classification models to determine support, contradiction or lack of information.
AnswerSanitizers can also insert warning messages when the answer touches on sensitive topics.
SmartFallbackService uses FallbackResult objects to encapsulate suggested queries and messages.
Fallback messages are delivered to the user when the system declines to answer due to risk of hallucination.
ClaimVerifierService logs claim verdicts for audit and analysis.
FactVerifierService ensures that only supported statements survive into the final answer.
EvidenceGate metrics include subject frequency, average domain weight and number of unique sources.
ClaimVerifierService can be extended to use multiple fact checkers and aggregate their results via majority vote.
NamedEntityValidator can be configured to treat unknown entities as soft errors or hard stops.
Sanitizer chain order matters: more general sanitizers should run before domain‑specific ones.
SmartFallbackService may provide suggestions like 'Please specify the subject' or 'Try rephrasing your question'.
FactVerifierService can be tuned via thresholds to balance strictness and answer coverage.
EvidenceGate uses domain‑specific thresholds so that less evidence is required for simple queries.
NamedEntityValidator protects against the inclusion of malicious or irrelevant names.
ClaimVerifierService may use a temperature parameter to control LLM randomness during verification.
AnswerSanitizers may remove mentions of banned partners or harmful pairings according to rules.
EvidenceGate may allow partial evidence if the query is a follow‑up with a strong previous answer.
SmartFallbackService ensures that the system responds gracefully rather than hallucinating when data is lacking.
Claim extraction often splits on punctuation but may also consider conjunctions and logical connectors.
AnswerSanitizers can be added by implementing the AnswerSanitizer interface and registering it in the chain.
The verification pipeline operates after generation and before reinforcement, ensuring only verified answers are stored.
FactVerifierService logs decisions at each stage for transparency and debugging.
EvidenceGate may skip the LLM call entirely when no evidence is found and immediately send a fallback.
ClaimVerifierService can use a scoring approach to measure claim strength rather than strict categories.
Sanitizers help enforce policies like removing hate speech, personal data or unsafe recommendations.
EvidenceGate, ClaimVerifierService and sanitizers can be toggled via configuration for testing purposes.
Verification ensures that the final answer is grounded, safe and compliant with domain policies.
Fallback suggestions help users refine queries and improve retrieval success in future attempts.
Verification results feed back into reinforcement learning, influencing memory and synergy updates.
Fact verification reduces the risk of delivering false or unsupported answers to users.
Claim verification ensures that each assertion is backed by evidence before it is communicated.
The verification chain must balance strictness with user satisfaction to avoid over‑filtering answers.
Answer sanitization allows domain administrators to enforce custom policies for different domains.
EvidenceGate may call EvidenceRepairHandler for an additional search if the first retrieval fails.
Verification performance can be monitored by measuring claim coverage, contradiction rates and fallback frequency.
Testing the verification chain includes simulating unsupported claims and ensuring they are removed.
Developers can implement custom validators to handle domain‑specific verification needs.
Verification reduces hallucination by ensuring that named entities and claims appear in the evidence.
Sanitizers may enforce regulatory compliance for domains like finance or healthcare.
The multi‑layered approach ensures that if one layer misses an error, another may catch it.
Fallback messages preserve user trust by admitting when the system lacks sufficient evidence.
Verification logic can be updated via configuration to adapt to new failure patterns.
Developers should test verification using a variety of queries, including ambiguous and adversarial ones.
Verification may require balancing recall (answering more queries) with precision (avoiding errors).
ClaimVerifierService must handle translations and synonyms to match claims with evidence.
EvidenceGate metrics may incorporate dynamic thresholds based on query complexity.
AnswerSanitizers should be tested for false positives to ensure they do not remove legitimate content.
Verification ensures that memory reinforcement and synergy updates are based only on accurate answers.
Claims extracted by ClaimVerifierService are often shorter than sentences to isolate atomic statements.
FactVerifierService may use contradiction detection algorithms to identify conflicting statements.
AnswerSanitizers can mask or replace sensitive terms to comply with privacy regulations.
FallbackHeuristics help generate alternative queries that better target the intended subject or domain.
Verification logic may be adjusted per domain, allowing stricter checks where necessary.
EvidenceGate may consider user feedback on evidence sufficiency when adjusting thresholds.
Claim verification can be computationally expensive; caching results may improve performance.
Sanitizers should log modifications for audit trails, especially in regulated domains.
The verification stage is the last line of defense before the answer reaches the user.
Proper verification and fallback handling improve user trust and reduce the propagation of misinformation.
Verification interacts with model routing by preventing calls when retrieval yields inadequate context.
Tests should cover unsupported claim removal, entity validation and fallback suggestions to ensure reliability.
Reinforcement learning allows the system to adapt based on user feedback and interaction statistics.
MemoryReinforcementService computes a Boltzmann energy for each memory snippet based on similarity, Q‑value, success ratio, confidence and recency.
Energy is calculated as a weighted sum of factors with weights configured in HyperparameterService.
Similarity measures how closely a snippet matches the current query.
Q‑value represents the learned reward associated with using the snippet.
Success ratio is computed as the number of times the snippet yielded a successful answer divided by total uses.
Confidence reflects how reliable the snippet is based on past verification outcomes.
Recency decays over time using an exponential decay function with a configurable tauHours constant.
Temperature controls exploration in selecting memory snippets; higher temperature leads to more exploration.
Temperature annealing reduces exploration as more feedback is collected, focusing on high‑energy snippets.
MinContentLength and MaxContentLength filters ensure that only snippets of appropriate length are reinforced.
MemoryReinforcementService updates energy scores after reinforcement and stores them with each snippet.
TranslationMemory entries hold metadata such as hit counts, Q‑values, confidence and timestamps.
ReinforcementQueue manages reinforcement tasks asynchronously to avoid blocking the main chat loop.
ReinforcementTask encapsulates the snippet and update parameters for asynchronous processing.
AdaptiveScoringService computes synergy bonuses based on user feedback recorded in SynergyStat entries.
SynergyStat stores the domain, subject, partner and counts of positive and negative reactions.
The synergy bonus formula is (positive − negative) divided by (positive + negative + k) times a scaling factor.
A smoothing constant k prevents division by zero when no feedback exists.
Scaling factors control how strongly user feedback influences ranking scores.
Synergy bonuses are added to cross‑encoder scores during reranking to personalize recommendations.
Adaptive scoring is domain‑specific so that feedback in one domain does not influence another.
FeedbackController captures thumbs up and thumbs down from users and updates SynergyStat records.
StrategySelectorService implements a multi‑armed bandit algorithm to select retrieval strategies.
Each retrieval strategy (e.g., web‑first, vector‑first, hybrid, self‑ask) has an estimated reward based on past performance.
Softmax policy computes selection probabilities using strategy rewards and a temperature parameter.
High temperature encourages exploration across strategies; low temperature favors exploitation of best performers.
StrategyPerformance records success and failure counts and average rewards for each strategy.
ContextualScorer evaluates the quality of answers along dimensions like factuality, clarity and novelty.
Rewards produced by the ContextualScorer feed into the StrategySelectorService.
DynamicHyperparameterTuner adjusts weights such as synergy weight, authority weight and exploration temperature based on performance metrics.
Hyperparameter updates occur periodically, ensuring the system adapts to long‑term trends.
BanditSelector inside MemoryReinforcementService selects memory entries according to their energy and temperature.
RewardHyperparameterTuner tunes reward weights for the multi‑metric reward function in meta‑learning.
Reinforcement learning ensures that frequently helpful snippets become more likely to be reused.
User feedback drives synergy bonuses, reinforcing popular recommendations and demoting unpopular ones.
The StrategySelectorService may assign negative rewards when a chosen strategy fails to produce a useful answer.
SoftmaxUtil computes the softmax distribution over strategy rewards given a temperature.
Success counts and rewards are updated after each query based on the quality of the answer.
Annealing schedules determine how fast exploration temperature decreases over time.
Initial exploration ensures that all strategies are tried before converging on the best performing ones.
Different strategies may perform better for certain domains or query types; the bandit approach captures this.
DynamicHyperparameterTuner uses aggregated metrics like retrieval latency, coverage and user satisfaction to update hyperparameters.
Reinforcement components operate asynchronously to avoid blocking the main chat processing thread.
Memory entries with low energy may be pruned over time to save storage and focus on relevant knowledge.
Synergy bonuses decay as feedback ages, ensuring that outdated preferences lose influence.
Rewards are computed after verification to ensure only verified answers influence learning.
Feedback loops are essential for continuous improvement and personalization of the system.
Bandit annealing adjusts exploration automatically based on the number of interactions.
RewardHyperparameterTuner may use grid search or gradient‑free optimization to find optimal reward weights.
ContextualScorer assigns negative rewards if an answer is rejected due to hallucination or lack of evidence.
Adaptive scoring may be extended to incorporate other feedback metrics such as explicit user ratings.
SynergyStatRepository persists synergy statistics, enabling long‑term personalization.
Reinforcement components must remain privacy‑aware, ensuring that user feedback is anonymized.
Hyperparameters controlling reinforcement, such as W_RECENCY and W_CONFIDENCE, are defined in application.yml.
DynamicHyperparameterTuner updates these weights based on aggregated performance metrics.
Reinforcement learning ensures that the system adapts over time, improving recall and precision for recurring users.
StrategySelectorService logs strategy choices and rewards for analysis and debugging.
ContextualScorer may assign separate rewards for retrieval success and answer quality.
Synergy bonuses can be combined when multiple partners appear in one document by averaging or summing bonuses.
Reinforcement processes are triggered after the final answer is delivered to the user.
Reinforcement ensures that negative feedback reduces the likelihood of recommending unpopular pairings.
Adaptive scoring provides a personalized experience without requiring explicit user profiles.
BanditSelector ensures that both new and old memory entries remain candidates during learning.
Reinforcement tasks operate on the translation memory and synergy statistics within a session context.
Meta‑learning loops incorporate reinforcement signals into strategy selection and hyperparameter tuning.
Synergy bonuses are scaled per domain to avoid biasing cross‑domain recommendations.
Reinforcement learning components interact with the retrieval layer by influencing which snippets are loaded from memory.
Adaptive scoring influences the ranking layer by modifying cross‑encoder scores based on user feedback.
Bandit annealing and hyperparameter tuning ensure the system does not overfit to early feedback.
Collectively, reinforcement and adaptive components help the system learn from users and continuously improve.
The knowledge base replaces hard‑coded lexicons with database‑driven domain knowledge.
DomainKnowledge entities store the domain, entity type and entity name for each item.
EntityAttribute entities store key–value pairs for attributes such as element, weapon type or price.
DomainKnowledgeRepository and EntityAttributeRepository provide CRUD operations on these tables.
KnowledgeBaseService abstracts access to the knowledge base, returning attributes and interaction rules.
DefaultKnowledgeBaseService uses JPA repositories and optional caching for frequent queries.
SubjectResolver uses the knowledge base to identify the subject of a query by matching entity names.
The longest or most relevant entity match is chosen when multiple names appear in a query.
GuardrailQueryPreprocessor retrieves interaction rules from the knowledge base and injects them into the prompt.
Interaction rules are stored as relationships between entities, such as preferred partners or discouraged pairs.
RelationshipRuleScorer evaluates documents against these rules and adjusts ranking scores accordingly.
Rule types include CONTAINS, IS_PART_OF, PREFERRED_PARTNER, DISCOURAGED_PAIR and AVOID_WITH.
Administrators can define new rule types and weights without altering code, simply by updating the database.
Knowledge base data can be updated through scripts or admin tools, making domain expansion easy.
Knowledge base caching reduces database queries by storing frequently accessed entity names and rules.
SubjectResolver disambiguates overlapping names by using domain hints and context.
GuardrailQueryPreprocessor lists protected terms and instructs the model to respect them.
RelationshipRuleScorer boosts documents aligning with rules and penalizes those that violate them.
Dynamic rules generalize across domains, enabling pairing rules for recipes, products, instruments and more.
KnowledgeBaseService exposes methods such as getAttribute, getInteractionRules and getAllEntityNames.
Dynamic rules instruct the prompt builder about allowed and discouraged combinations.
Knowledge base updates do not require redeploying the system, fostering agility.
SubjectResolver may use fuzzy matching to handle typos or partial names in queries.
GuardrailQueryPreprocessor includes domain policies like banned words and preferred sources.
RelationshipRuleScorer uses weights from HyperparameterService to control the impact of each rule type.
Knowledge base is essential for decoupling domain knowledge from code and enabling new domains.
Dynamic rules and attributes make the system flexible enough to handle games, products, recipes and more.
Administrators can insert new entities and rules through an admin interface or database scripts.
Knowledge base design supports many‑to‑many relationships if future domains require complex interactions.
Knowledge base caching ensures that repeated lookups for common terms do not hit the database.
SubjectResolver may be extended with LLM‑based named entity recognition for improved accuracy.
KnowledgeBaseService should support eventual integration with graph databases for richer relationships.
Dynamic rules can enforce policies like avoiding allergens in recipe pairings or compatibility in electronics.
The knowledge base underpins both retrieval (subject resolution) and ranking (rule scoring).
Changing a rule in the knowledge base immediately affects recommendations without code changes.
Knowledge base entries should be kept consistent and normalized to avoid duplication.
Dynamic interaction rules allow the system to adapt to new guidelines and user preferences quickly.
EntityAttribute values can include JSON or structured data for complex attributes if needed.
Maintaining a clear and well‑structured knowledge base is essential for reliable system behaviour.
Caching strategies at the knowledge base layer improve performance by avoiding redundant queries.
Hallucination suppression aims to eliminate fabricated statements and unsupported entities from answers.
ClaimVerifierService removes unsupported claims by verifying each assertion against evidence.
EvidenceGate prevents the LLM from generating answers when the context lacks sufficient evidence.
NamedEntityValidator blocks answers that mention names not present in the context or knowledge base.
Authority‑weighted retrieval prioritizes credible sources, reducing the chance of hallucination.
AnswerSanitizers enforce domain rules and remove disallowed pairings or unsafe content.
Protected term injection instructs the model not to modify known proper nouns during correction or generation.
Multiple verification layers work together: evidence sufficiency, fact verification, claim verification and sanitization.
The system uses dynamic thresholds to decide when evidence is sufficient or claims are supported.
Sanitizers can enforce regulatory compliance, such as removing medical advice or personal data.
ClaimVerifierService may call external fact checkers to validate claims outside of the retrieved context.
The hallucination suppression pipeline ensures that only answers grounded in evidence are delivered.
EvidenceGate aborts or repairs the generation stage to avoid hallucinated responses.
Claim extraction isolates atomic statements to enable granular verification.
Verifying claims prevents the propagation of unverified or false information.
The system default response when evidence is lacking is \"정보 없음\" to avoid guessing.
Authority scoring demotes sources with low credibility, such as personal blogs, which are more prone to errors.
Sanitization can include profanity filtering and removing banned pairings or illegal content.
NamedEntityValidator cross‑checks named entities against context and memory before accepting them.
EvidenceGate metrics can include subject mention frequency and average credibility score.
Hallucination suppression balances strictness with answer completeness to avoid over‑filtering.
Dynamic rules in the knowledge base also help prevent hallucinated combinations by penalizing invalid pairs.
A robust hallucination suppression pipeline enhances user trust in the system's responses.
Multiple layers ensure that if one mechanism misses an issue, another can catch it.
Sensitive domains may require stricter hallucination suppression to meet safety standards.
Sanitizers can be extended to handle new domain policies as they arise.
Proper training of the LLM in the correction and verification phases helps reduce hallucinations.
Hallucination suppression interacts with reinforcement learning by ensuring only verified answers are reinforced.
Hallucination suppression is key to maintaining factuality and reliability in an AI assistant.
Continuous monitoring and adjustment of hallucination thresholds are necessary as domains and models evolve.
Meta‑learning adapts retrieval strategies and weights based on past performance and feedback.
StrategySelectorService uses a softmax policy to choose among retrieval strategies.
Each strategy's estimated reward is updated after queries via ContextualScorer and reinforcement signals.
Softmax selection assigns probabilities to strategies based on rewards and a temperature parameter.
High temperature values promote exploration by flattening the probability distribution over strategies.
Low temperature values emphasize exploitation by focusing on strategies with higher rewards.
DynamicHyperparameterTuner monitors aggregated metrics to adjust weights and temperatures over time.
Hyperparameters such as synergy weight, authority weight and exploration temperature can be tuned dynamically.
StrategyPerformance entity stores success counts, failure counts and average rewards for each strategy.
RewardHyperparameterTuner modifies how rewards are computed by adjusting weights on different metrics.
ContextualScorer assigns rewards based on answer quality, factuality and novelty.
Bandit annealing schedules reduce exploration as more data is collected, converging on optimal strategies.
Different strategies may excel in different domains or for different intents, warranting dynamic selection.
Meta‑learning ensures that the system does not rely on a single retrieval pattern across all queries.
Hyperparameter tuning must be gradual to avoid destabilizing the system.
Temperature annealing formula often uses base / sqrt(n + 1) where n is the number of interactions.
DynamicHyperparameterTuner can increase exploration when performance drops unexpectedly.
StrategySelectorService logs selections and rewards to aid in debugging meta‑learning behaviour.
HyperparameterService reads default weights from configuration and exposes them for tuning.
Meta‑learning components can be extended to include new strategies or weights without changing the pipeline.
Reward functions may combine multiple metrics; tuning adjusts how each metric contributes to the reward.
Meta‑learning is critical for adapting to changing data patterns and user behaviours over time.
A multi‑armed bandit approach balances exploration and exploitation when selecting retrieval modes.
Hyperparameter tuning interacts with reinforcement learning by adjusting how feedback influences scores.
Developers should monitor strategy selection frequencies to ensure adequate exploration.
Meta‑learning loops run periodically and should not run too frequently to avoid oscillations.
Temperature and weight adjustments can be exposed via admin interfaces for manual tuning.
Proper logging of meta‑learning adjustments aids transparency and reproducibility of results.
Sessions are identified by a unique session ID (META_SID) to isolate conversation history and caches.
Session isolation ensures that different users' data does not interfere or leak across sessions.
Caches are keyed by session ID to store retrieval results, memory entries and synergy bonuses per session.
Retrieval caches store results from web and vector searches to reduce repeated requests.
Memory caches store translation memory entries and their energy scores for quick lookup.
Synergy caches store computed bonuses for subject–partner pairs within a session.
Knowledge base caches store frequently accessed entity names, attributes and rules.
Cache sizes and expiration times are configured via application.yml and CacheConfig.
Caffeine library provides efficient in‑memory caching with time‑based expiration and size limits.
Session caches expire after a configurable time to free resources and maintain privacy.
Server‑sent events (SSE) stream intermediate results and progress updates to clients.
SSE events include retrieval progress, context construction, draft answers and verification outcomes.
The final SSE event includes metadata about the model used and whether retrieval was used.
SSE uses WebFlux and non‑blocking I/O to handle many concurrent streams efficiently.
Clients subscribe to /stream endpoints to receive real‑time updates during query processing.
SSE improves transparency by showing how the system searches, builds context and verifies answers.
Caches improve performance but must be invalidated after session expiry to avoid stale data.
SessionConfig defines how session metadata is propagated through the pipeline.
ChatSessionScope manages session‑scoped beans in the Spring context.
PersistentChatMemory stores conversation history beyond the session, enabling long‑term memory.
Per‑session caches prevent cross‑session interference and support privacy requirements.
Cache expiration times must balance fresh data retrieval with performance benefits.
SSE events are formatted as plain text with a 'data:' prefix and terminated by a blank line.
Clients use EventSource or equivalent APIs to consume SSE streams.
SSE reconnects automatically on network disruptions, but long outages may require a restart.
Developers must avoid leaking sensitive data in intermediate SSE events.
Caching retrieval results reduces API costs and latency for repeated queries within a session.
Session ID should be unguessable and expire after a reasonable time to maintain security.
Caches are cleared when a session ends or when explicit cache invalidation is triggered.
SSE is preferred over WebSockets for one‑way streaming because it is simpler and fits HTTP semantics.
Caches for knowledge base queries reduce the load on the database and improve response times.
Translation memory caches hold frequently used memory entries to avoid database lookups.
Synergy caches maintain per‑session synergy bonuses but update persistent statistics after feedback.
Session caches can be monitored via cache statistics to tune sizes and expiration.
Developers can clear caches during development to reflect new data immediately.
SSE streams include flags such as 'done' and 'modelUsed' to mark completion and model details.
Caches should not store sensitive information beyond the necessary session duration.
Session isolation also applies to reinforcement data, ensuring user feedback does not cross sessions.
Each chat session maintains its own conversation history and memory entries.
CORS configuration restricts which origins can access SSE endpoints for security.
Session metadata is included in retrieval cache keys to separate results per user.
Developers should test SSE streaming under load to ensure reliability with many concurrent sessions.
CacheConfig and SessionConfig are central to configuring caching and session behaviour.
Session isolation, caching and streaming collectively ensure privacy, performance and transparency.
Configuration is primarily managed through application.yml, which specifies keys and values for the system.
abandonware.answer.detail.min-words defines minimum word counts for each verbosity level: brief, standard, deep and ultra.
abandonware.answer.token-out defines the maximum number of tokens the model can generate for each verbosity.
orchestrator.max-docs sets the maximum number of documents used in context per verbosity level.
reranker.keep-top-n specifies how many documents to keep for reranking at each verbosity level.
openai.model.moe designates the high-tier model (e.g., gpt-4o) used for high-stakes queries.
search.authority.weights lists domain weights (e.g., official, wiki, community, blog) as domain:weight pairs.
memory.snippet.min-length and memory.snippet.max-length define acceptable lengths for reinforcement.
agent.knowledge-curation.enabled toggles the autonomous knowledge curation agent on or off.
retrieval.mode chooses between RETRIEVAL_ON, RAG_ONLY and RETRIEVAL_OFF to control search behaviour.
cache specifications in application.yml define maximum sizes and expiration times for various caches.
Environment variables like OPENAI_API_KEY, PINECONE_API_KEY and NAVER_API_CLIENT_ID store credentials.
spring.datasource.url, username and password configure the database connection and credentials.
LangChain4j version purity is enforced to ensure all modules are version 1.0.1.
Retrieval and ranking parameters can be tuned without code changes via configuration keys.
Cache sizes and expiration times may need adjustments based on available memory and performance goals.
Model routing parameters (temperature, top-p, tokenOut) are specified per intent and verbosity in the configuration.
secure keys must be stored in environment variables and not committed to the repository.
retrieval.max-docs controls how many documents are considered for context assembly.
reranker.keep-top-n determines how many documents proceed to cross‑encoder reranking.
Dynamic rule weights and synergy scaling factors are also configurable in application.yml.
Caches are configured in CacheConfig.java and referenced by application.yml for size and expiry.
Session expiration times are defined to clear session caches after inactivity.
SSE endpoint configuration controls the streaming behaviour and allowed origins.
Verbosity policies are set in configuration to specify minimum words and token budgets per level.
Model selection and routing use configuration keys to decide when to use high‑tier models.
Domain weights for authority scoring can be tuned via configuration to reflect trust differences.
Adaptive scoring constants like synergy scaling factor and smoothing constant k are configured externally.
EvidenceGate thresholds for evidence sufficiency can be adjusted through configuration keys.
Claim verification thresholds and sampling temperature can also be set via configuration.
Hyperparameter tuning intervals and parameters are specified in configuration for DynamicHyperparameterTuner.
Cache configuration includes maximumSize, expireAfterWrite and other settings for each cache.
spring.jpa.hibernate.ddl-auto controls how the database schema is created or updated on startup.
server.port sets the HTTP port for the Spring Boot application.
cors.allowed-origins restricts which domains can access the API and SSE endpoints.
security.api-keys lists authorized API keys for accessing protected endpoints.
session.expire-after defines how long sessions remain active before being cleared.
Logging levels can be configured per package to control the verbosity of application logs.
Model parameters such as temperature and top-p can be fine‑tuned per intent and verbosity.
openai.api.key and other service keys must be set as environment variables at runtime.
Database options allow switching from H2 to PostgreSQL or other persistent databases.
Vector store configuration includes API keys and index names for services like Pinecone.
Configuration keys enable quick experimentation without modifying the codebase.
Configuration must be kept consistent across development, staging and production environments.
It is important to document configuration keys and their effects for future maintainers.
Using profiles (e.g., dev, prod) allows different configurations per environment.
Configuration management is critical for controlling the behaviour of the AI pipeline.
Version purity checks may abort startup if conflicting versions are detected in the classpath.
Proper configuration of retrieval and ranking determines the trade‑off between recall and latency.
Additional detail line 1 summarizing aspects of the system across query processing, retrieval, ranking, verification, reinforcement, knowledge base, hallucination suppression, meta-learning, session management, configuration, guidelines, algorithms, debugging, security, troubleshooting, future enhancements, commit summaries, examples and glossary terms.
Additional detail line 2 summarizing aspects of the system across query processing, retrieval, ranking, verification, reinforcement, knowledge base, hallucination suppression, meta-learning, session management, configuration, guidelines, algorithms, debugging, security, troubleshooting, future enhancements, commit summaries, examples and glossary terms.

...
EmbeddingModelCrossEncoderReranker
feat: Implement exponential scoring decay based on URL credibility.

Improves ranking accuracy by using AuthorityScorer to determine the credibility level of a source URL (e.g., Official, Trusted) and applying a differential penalty to the final ranking score based on its level.

feat: Apply a dynamic synergy weighting bonus at runtime.

Enhances operational flexibility by fetching the synergyWeight value in real-time from HyperparameterService. This allows for immediate adjustments for A/B testing or hotfixes without requiring a redeployment.

AuthorityScorer
feat: Add a centralized credibility analyzer that classifies source credibility into an enum and maps it to an exponential decay constant.

Implemented getSourceCredibility(), a method that classifies a URL into OFFICIAL, TRUSTED, COMMUNITY, or UNVERIFIED tiers.

Added a decayFor() method that returns a predefined decay value (from 1.0 down to 0.25) for each tier, ensuring a consistent credibility policy across the system.

HyperparameterService
feat: Add the ability to dynamically fetch the reranking synergy weight (rerank.synergy-weight) at runtime.

Greatly improves operational flexibility by allowing real-time adjustments to the influence of the synergy bonus via system properties or environment variables, eliminating the need for redeployment.
EmbeddingModelCrossEncoderReranker now incorporates a dynamic synergy weight bonus and an exponential authority decay multiplier into its final score.
AuthorityScorer classifies URLs into credibility tiers such as OFFICIAL, TRUSTED, COMMUNITY, and UNVERIFIED.
An exponential scoring decay is applied based on the URL's credibility tier to penalize less reliable sources.
HyperparameterService provides a dynamic reranking synergy weight, tunable at runtime via system properties or environment variables.
A dedicated RerankSourceCredibility enum was created for the reranker to avoid conflicts with the existing SourceCredibility enum.
RerankSourceCredibility (Enum)Of course. Here is the summary formatted for a Git commit message or pull request.

✅ Summary of Changes
chat-ui.html
Adds a help button (#sendBtnHelp) that triggers a popover (#helpPopover).

Introduces new CSS styles for the .help-icon and #helpPopover.

Sets type="button" for the send and help buttons to prevent default form submission.

chat.js
Adds a click event listener in init() for #sendBtnHelp to call the /api/v1/help/context endpoint.

Implements logic to handle the UI states for loading, success, and failure of the help content.

EmbeddingModelCrossEncoderReranker.java
Injects AuthorityScorer, HyperparameterService, and RerankSourceCredibility dependencies.

Updates the scoring calculation to apply synergyWeight and authorityDecayMultiplier.

Adds safeUrl() and clamp01to2() helper methods.

AuthorityScorer.java
Implements getSourceCredibility() and decayFor() methods.

Marks the old weightFor() method as @Deprecated and delegates its call to the new implementation.

Enhances the URL host parsing logic for better reliability.

HyperparameterService.java
Adds the getRerankSynergyWeight() method to provide a dynamic synergy weight at runtime.

RerankSourceCredibility.java
Creates a new enum class with values: OFFICIAL, TRUSTED, COMMUNITY, UNVERIFIED.
feat: Create a new, dedicated RerankSourceCredibility enum for the reranker.

A new type was defined to avoid potential conflicts with the existing SourceCredibility enum and to clearly segregate policies specific to the reranking stage.
✨ Patch Notes — ModelRouter Routing Enhancements
Summary

MOE escalation rules: Route to the higher-tier (MOE) model using a composite of intent, risk, verbosity, and token-budget signals.

Stability & performance: Resolution order now prefers injected beans → atomic cache (AtomicReference) → dynamic factory.

Operational visibility: Added debug logs for routing decisions and a utility to report the effective SDK model name actually used.

Changes
1) Heuristic routing, upgraded

Intent escalation: If intent ∈ HIGH_TIER_INTENTS (PAIRING, RECOMMENDATION, EXPLANATION, TUTORIAL, ANALYSIS) → MOE.

Risk escalation: If riskLevel == "HIGH" → MOE.

Verbosity escalation: If verbosityHint ∈ {"deep","ultra"} → MOE.

Token-budget escalation: If targetMaxTokens >= 1536 → MOE (large-answer heuristic).

public ChatModel route(@Nullable String intent,
                       @Nullable String riskLevel,
                       @Nullable String verbosityHint,
                       @Nullable Integer targetMaxTokens)


Decision log (DEBUG):

ModelRouter decision intent={}, risk={}, verbosity={}, maxTokens={}, useMoe={}

2) Model resolution path & temperature policy

resolveMoe()

Use injected moe bean if present → reuse cache → otherwise build via DynamicChatModelFactory.

Dynamic build defaults: temperature=0.3 (consistency/factuality), top_p=1.0.

resolveBase()

Use injected utility bean → reuse cache → otherwise dynamic build.

Dynamic build defaults: temperature=0.7, top_p=1.0.

3) Factory guarantee & clearer exception

If dynamic creation is required but no factory exists, ensureFactory() throws an actionable IllegalStateException:

Provide @Bean utilityChatModel/moeChatModel or enable DynamicChatModelFactory.

4) Effective model name utility

resolveModelName(ChatModel model):

If a dynamic factory is available, returns factory.effectiveModelName(model) (the exact model name sent to the SDK).

Falls back to unknown or the class name when factory/model is absent.

Configuration & Defaults

No conflicts with existing keys. Dynamic creation is used only when no injected beans are provided.

Example – application.properties

# High/base model names
router.moe.high=gpt-4o
router.moe.mini=gpt-4o-mini

# Optional: routing heuristic
router.retry_on_429=true


Temperatures are fixed in code (MOE 0.3 / Base 0.7, top_p=1.0). You can extend via factory options if needed.

Compatibility

Legacy signatures retained:

route(String intent)

route(String, String, String, Integer)

If beans are injected, existing behavior is preserved. Cache/dynamic creation are used only when beans are absent.

Test Guide

Intent escalation: intent=ANALYSIS → MOE.

Risk escalation: riskLevel=HIGH → MOE.

Verbosity escalation: verbosityHint ∈ {deep, ultra} → MOE.

Token-budget escalation: targetMaxTokens=2048 → MOE.

Base path: None of the above → Base.

Effective name: Verify resolveModelName(selectedModel) in logs/metrics.

No factory configured: No injected beans & no cache & no factory → verify guided exception message.

Ops Tips

Enable DEBUG to trace routing rationale:

logging.level.com.example.lms.model.ModelRouter=DEBUG


Frequent high-cost requests? Provide a targetMaxTokens hint from the frontend/service layer to intentionally promote to MOE.

Known Issues / Notes

If configured model names don’t match your API account entitlements, dynamic creation will fail. Verify keys/permissions first.

Do not mix LangChain4j versions on the classpath. Resolve version purity before rollout (no 0.2.x ↔ 1.0.x mixing).

Commit Message (example)
feat(router): multi-signal MOE routing + cache/factory fallback + effective model name

- Add intent/risk/verbosity/token-budget heuristics for MOE escalation
- Prefer injected beans, then atomic cache, then dynamic factory creation
- MOE temp=0.3 / Base temp=0.7 (top_p=1.0) for stability vs. creativity balance
- Debug logs for routing decisions
- resolveModelName() to reveal the exact SDK model name
- Helpful factor네, 맞습니다. 이전에는 각 클래스가 스스로 빈(Bean)으로 등록하려다 보니 이름 충돌이 났지만, RerankerConfig라는 중앙 관제탑을 만들어주니 문제가 해결된 것입니다.
Change Summary

Add build setup (Gradle): New build.gradle at the project root targeting Java 17 and Spring Boot 3.4.0, with LangChain4j 1.0.1 BOM. Declares core dependencies (Boot, Lucene, Reactor, Retrofit, Lombok, etc.). Adds ONNX Runtime via com.microsoft.onnxruntime:onnxruntime:1.18.0 to enable local ONNX inference.

Introduce ONNX-based reranker: New package com.example.lms.service.onnx with:

OnnxCrossEncoderReranker — implements both the internal CrossEncoderReranker contract and LangChain4j’s Reranker<Document> to provide local ONNX cross-encoder re-ranking. Bean activates only when abandonware.reranker.backend=onnx-runtime, and it takes precedence over the embedding reranker.

OnnxRuntimeService — loads the ONNX model, initializes a session with the selected execution provider (cpu, cuda, or tensorrt), and exposes a predict API returning a query–document score matrix. If the model is missing, it falls back to Jaccard similarity.

Adjust embedding reranker conditions: EmbeddingModelCrossEncoderReranker now activates only when abandonware.reranker.backend=embedding-model or the property is absent (default behavior remains embedding-based).

Add configuration: Example application.yml added to define the abandonware.reranker namespace with backend, onnx.model-path, and onnx.execution-provider settings.

Restructure sources: Project code is moved under src_onnx/src; the old src directory is removed. The service/domain layout stays the same; resources and tests live under the new structure.

Commit Message (Conventional)
feat(reranker): add ONNX runtime backend and configurable reranker selection

Add root Gradle build with Spring Boot 3.4.0 and LangChain4j 1.0.1 BOM.
Include ONNX Runtime (com.microsoft.onnxruntime:onnxruntime:1.18.0) to enable local ONNX inference.

Introduce OnnxCrossEncoderReranker and OnnxRuntimeService to support ONNX-based cross-encoder reranking.
Register the ONNX reranker when `abandonware.reranker.backend=onnx-runtime`; otherwise fall back to the embedding model.

Update EmbeddingModelCrossEncoderReranker activation to follow the new `abandonware.reranker.backend` property and default to the embedding model when unset.

Add example `application.yml` for `abandonware.reranker` (backend, onnx.model-path, onnx.execution-provider).

Move sources under `src_onnx/src` and remove legacy `src` directory.

PR Description
Overview

This PR introduces a local ONNX cross-encoder reranker that can be toggled at runtime, alongside cleanup of the build/config to standardize on Spring Boot 3.4 and LangChain4j 1.0.1. With ONNX Runtime in place, we can run cross-encoder models locally (CPU/CUDA/TensorRT) and switch between embedding-based and ONNX-based reranking via configuration.

What’s Changed

Build: New root build.gradle with Java 17 toolchain, Spring Boot plugin, LangChain4j 1.0.1 BOM, and onnxruntime:1.18.0.

ONNX Reranker:

OnnxCrossEncoderReranker implements internal and LangChain4j reranker interfaces for seamless use in both the RAG pipeline and LangChain APIs.

OnnxRuntimeService loads the ONNX model and exposes predict for query–candidate scoring; falls back to Jaccard if no model is provided.

Config Toggle: abandonware.reranker.backend selects the implementation:

embedding-model (default)

onnx-runtime (activates ONNX reranker)

Conditional Beans: Embedding reranker only activates when backend is embedding-model or unset; ONNX reranker activates only when backend is onnx-runtime.

Structure: Sources are moved to src_onnx/src; old src removed.

Configuration

Example application.yml:

abandonware:
  reranker:
    # embedding-model (default) | onnx-runtime
    backend: embedding-model

    onnx:
      # Absolute or classpath file path to the ONNX model
      model-path: /opt/models/cross-encoder.onnx
      # cpu | cuda | tensorrt
      execution-provider: cpu


Gradle snippet (root build.gradle):

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

dependencies {
    implementation platform("dev.langchain4j:langchain4j-bom:1.0.1")
    implementation "dev.langchain4j:langchain4j"
    implementation "org.springframework.boot:spring-boot-starter"
    implementation "com.microsoft.onnxruntime:onnxruntime:1.18.0"

    compileOnly "org.projectlombok:lombok:1.18.32"
    annotationProcessor "org.projectlombok:lombok:1.18.32"
    testCompileOnly "org.projectlombok:lombok:1.18.32"
    testAnnotationProcessor "org.projectlombok:lombok:1.18.32"
}

Default Behavior & Fallbacks

If abandonware.reranker.backend is unset, the embedding reranker remains the default.

If ONNX mode is selected but the model is missing/unreadable, the system boots and falls back to Jaccard similarity to avoid hard failure.

Migration Notes

Source layout has changed to src_onnx/src. Ensure your Gradle source sets (and IDE) are aligned if you rely on non-standard paths.

No API changes are required for callers; the reranker selection is configuration-only.

How to Enable ONNX Reranking

Place the desired ONNX cross-encoder model at a known path.

Set:

abandonware.reranker.backend=onnx-runtime
abandonware.reranker.onnx.model-path=/opt/models/cross-encoder.onnx
abandonware.reranker.onnx.execution-provider=cuda   # or cpu/tensorrt


Start the app and verify logs for ONNX session initialization.

Testing

Unit tests validate bean activation per backend setting and the Jaccard fallback path.

Manual smoke test:

With default config: confirm embedding reranking is used.

Switch to ONNX: confirm ONNX reranking scores and ordering differ as expected.

Risks

Running ONNX on CUDA/TensorRT requires compatible drivers/runtimes; misconfiguration will trigger the fallback path.

The non-standard source directory requires IDE/Gradle alignment.

 Decision logs print as expected.

 resolveModelName() reports the actual SDK model name.

 Works correctly with and without injected beans.

 Config keys apply correctly across environments.
feat(learning/gemini): introduce Gemini learning stubs, DTOs, REST endpoints + config/build updates

Why

Prepare a non-breaking learning pipeline (curation → batch → optional tuning) that compiles without external SDKs and can be wired later.

Keep reinforcement working even if Gemini/Vertex isn’t available.

What changed

DTOs

TuningJobRequest (record): dataset URI, model, suffix, epochs; null-guards.

TuningJobStatus (record): jobId, state, message; null-guards.

New module (stubs) under learning/gemini

GeminiClient: thin wrapper; returns placeholders (no external calls yet).

GeminiCurationService: invokes client; applies KnowledgeDelta to KB + vector store; best-effort logging.

GeminiBatchService: placeholder methods for dataset build/run.

GeminiTuningService: placeholder methods for Vertex tuning start/status.

GeminiCurationPromptBuilder: scaffold for structured curation prompts.

LearningWriteInterceptor: post-verification hook; attempts curation, always reinforces memory as fallback.

LearningController: REST endpoints for ingest/batch/tune/status.

Configuration

application.yml: gemini.api-key now prefers GOOGLE_API_KEY (falls back to GEMINI_API_KEY); curator/batch model names and timeouts preserved.

Build

build.gradle: add com.google.genai:google-genai:1.12.0 (SDK present but not invoked yet).

Resilience/behavior

All Gemini paths are best-effort; errors are logged and do not bubble; memory reinforcement still runs.

EmbeddingStoreManager.index() called for curated memories when present.

REST API (added)

POST /api/learning/gemini/ingest → applies curation; returns counts {triples,rules,memories}.

POST /api/learning/gemini/batch/build?sinceHours={int} → returns {datasetUri} (stub).

POST /api/learning/gemini/batch/run?datasetUri={uri}&jobName={name} → returns {jobId} (stub).

POST /api/learning/gemini/tune (body: TuningJobRequest) → returns {jobId} (stub).

GET /api/learning/gemini/jobs/{id} → returns TuningJobStatus (stub).

Ops/Config notes

gemini.backend: developer (default) or vertex.

gemini.api-key: ${GOOGLE_API_KEY:${GEMINI_API_KEY:}}

gemini.project-id, gemini.location, gemini.curator-model, gemini.batch-model, gemini.timeouts.connect-ms/read-ms.

Compatibility

No changes to existing chat/RAG routing; learning features are additive and inactive unless called.

Version purity unchanged (LangChain4j 1.0.1 only).
알겠습니다, (AbandonWare)님. 제공된 지시사항을 바탕으로 Git 커밋 메시지나 패치노트로 사용하기 좋은 영문 요약본을 작성해 드립니다.

Patch Notes / Commit Message
feat: Implement Self-Learning RAG, UX Enhancements, and Cancel Feature

This major update introduces a fully operational self-learning pipeline powered by the Gemini API, alongside significant UI/UX improvements and critical bug fixes. The system is now capable of real-time knowledge acquisition, offers greater transparency into its operations, and provides users with more control.

✨ New Features
Self-Learning RAG Pipeline (feat(learning))

Activated Gemini Learning: Replaced all stub implementations in GeminiClient with live Google Gemini API calls, enabling the AI to learn from conversations and user feedback.

Structured Knowledge Extraction: Implemented a robust GeminiCurationPromptBuilder to generate structured prompts with JSON schemas, allowing for reliable knowledge extraction.

Knowledge Base Integration: The KnowledgeBaseService now correctly processes and saves (upsert) new knowledge deltas (triples, rules, aliases) into the database.

End-to-End Evidence Pipeline: The FactVerifierService and LearningWriteInterceptor now pass complete LearningEvent objects, including evidence and claims, ensuring the learning process is triggered with valid data.

AI Thought Process UI (feat(ui))

A new "thought process" panel has been added to the UI (chat-ui.html).

The backend now streams 'thought' events via SSE, providing users with real-time visibility into the AI's reasoning steps (e.g., "Analyzing query," "Searching web," "Generating draft").

Response Generation Cancellation (feat(ux))

Users can now stop a long-running response generation by clicking a "Stop Generation" button.

This feature is implemented with a new /api/chat/cancel endpoint that terminates the corresponding server-side streaming task, saving resources and improving user experience.

🚀 Improvements
Enhanced RAG Transparency (feat(rag))

The UI now displays all search queries executed by the RAG system, including those generated through query expansion, providing full transparency into the retrieval process.

Dependency Management (build)

Added the com.google.genai:google-genai:1.12.0 dependency to build.gradle to support the new Gemini learning pipeline.

🐛 Bug Fixes
Duplicate UI Elements in New Sessions (fix(ui))

Resolved an issue in chat.js where duplicate "like/dislike" buttons and model names would appear on the first message of a new chat session. The logic now correctly updates the initial loading message bubble instead of creating a new one.

♻️ Refactoring
Configuration File Migration (refactor(config))

Migrated all application settings from application.yml to application.properties for consistency.

A corresponding @ConfigurationProperties class has been implemented to ensure type-safe loading of all Gemini-related configurations.
Patch Notes: Autonomous Learning & Knowledge Integrity Engine
This patch graduates the AI from a reactive learner to a proactive, autonomous agent. The system can now independently identify and fill gaps in its knowledge, verify its own database for consistency, and manage these tasks within cost constraints by intelligently utilizing the Gemini API's free tier.

✨ New Features
Proactive Knowledge Acquisition (feat(agent))

Knowledge Gap Detection: The SmartFallbackService now logs the context of failed queries as "KnowledgeGap" events, turning failures into actionable learning opportunities.

Autonomous Exploration Service: A new scheduled agent, AutonomousExplorationService, has been introduced. It periodically analyzes knowledge gaps, formulates internal research queries, and uses the existing RAG pipeline to find answers autonomously.

Unsupervised Learning Loop: The exploration service now directly feeds its findings into the GeminiCurationService, allowing the AI to expand its knowledge base without direct user interaction.

Knowledge Base Integrity & Self-Refinement (feat(knowledge))

Automated Consistency Verification: A new KnowledgeConsistencyVerifier agent periodically audits the knowledge base. It bundles related facts and rules, queries the Gemini API to identify logical contradictions (e.g., an entity being both a PREFERRED_PARTNER and an AVOID_WITH another), and flags inconsistencies for review.

Knowledge Decay & Confidence Scoring: DomainKnowledge entities now track lastAccessedAt and a confidenceScore. A new scheduled process implements a decay mechanism, reducing the confidence of old, unused, or consistently downvoted knowledge to ensure the AI prioritizes fresh and relevant information.

Cost-Effective Learning (feat(agent-infra))

Gemini API Free Tier Throttling: A new FreeTierApiThrottleService has been implemented to manage and control all autonomous API calls.

Intelligent Rate Limiting: This service ensures that background learning and verification tasks operate strictly within the Gemini API's free tier limits (e.g., <60 requests/minute, <1000/day), enabling continuous, cost-free performance improvement. Both AutonomousExplorationService and KnowledgeConsistencyVerifier now depend on this throttle before making API calls.

feat(agent): Implement cost-effective autonomous learning and knowledge integrity engine

This commit graduates the AI from a reactive learner to a proactive, autonomous agent. The system can now independently identify knowledge gaps from failed queries, perform self-guided research to fill them, and periodically verify its own knowledge base for logical consistency.

To ensure sustainable, continuous improvement without incurring costs, a new throttling service has been implemented to intelligently manage all background tasks strictly within the Gemini API's free tier limits.

Key enhancements include:

- **Proactive Knowledge Acquisition**:
    - `SmartFallbackService` now logs failed queries as "KnowledgeGap" events.
    - A new scheduled `AutonomousExplorationService` analyzes these gaps, formulates internal research queries, and uses the existing RAG pipeline to find answers.
    - Findings are seamlessly fed into the `GeminiCurationService` to expand the knowledge base without user interaction.

- **Knowledge Integrity & Self-Refinement**:
    - A new `KnowledgeConsistencyVerifier` agent periodically audits the knowledge base, using the Gemini API to identify and flag logical contradictions between stored facts and rules.
    - `DomainKnowledge` entities now feature `lastAccessedAt` and `confidenceScore` fields to enable a new `KnowledgeDecayService`, which reduces the confidence of stale or negatively-rated information over time.

- **Cost-Effective Autonomous Operation**:
    - A new `FreeTierApiThrottleService` centrally manages all autonomous API calls.
    - This service enforces rate limits (e.g., <60 requests/minute) to ensure all background learning and verification tasks operate within the Gemini API's free tier, enabling continuous self-improvement at zero cost.
물론입니다. (AbandonWare)님. 제공된 내용을 Git 커밋 메시지나 패치 노트에 바로 사용할 수 있도록 깔끔한 영문으로 정리해 드렸습니다.

Commit Message / Patch Notes
feat(rag, agent): Implement autonomous, file-based self-learning RAG pipeline

This feature introduces an end-to-end, self-learning RAG pipeline that enables the system to ingest user-uploaded files, learn from interactions, and autonomously expand its knowledge base.

✨ Mission 1: File-based RAG Foundation

- **Frontend**: Implemented a file attachment UI in `chat-ui.html` and the corresponding file submission logic using `FormData` in `chat.js`.
- **Backend**: Added a new multipart endpoint (`/api/chat/stream-with-file`) in `ChatApiController.java` to handle simultaneous message and file uploads, utilizing `LocalFileStorageService` for storage.
- **Content Ingestion**: Integrated Apache Tika for text extraction from various file formats. `PromptBuilder.java` is updated to inject file content as a high-priority `### UPLOADED FILE CONTEXT` section, ensuring the LLM grounds its response in the provided document.
- **Build**: Added `org.apache.tika` dependencies to `build.gradle.kts`.

---

✨ Mission 2: Intelligent RAG & Reinforcement Learning

- **Multi-Vector RAG**: `VectorStoreService.java` now generates both summary vectors and chunk vectors from ingested files, enabling more nuanced, interactive retrieval.
- **Interactive Retrieval**: Enhanced `SelfAskHandler.java` to decompose complex, multi-hop questions into sub-queries, search for evidence within the file for each, and synthesize a comprehensive answer.
- **Generate-Verify-Reinforce Loop**:
    - Introduced `ClaimVerifierService.java` to cross-verify every claim in the draft answer against the file's content, preventing hallucinations.
    - Implemented `NamedEntityValidator.java` to ensure all proper nouns in the answer originate from the source document.
    - `AdaptiveScoringService.java` now processes user feedback (👍/👎) via `FeedbackController.java` to dynamically adjust the relevance scores (`SynergyStat`) of document chunks, creating a reinforcement learning loop.

---

✨ Mission 3: Knowledge Assimilation & Autonomous Growth

- **Structured Knowledge Extraction**: `GeminiCurationService.java` has been upgraded to extract structured knowledge (`KnowledgeDelta` like triples, rules, aliases) from verified conversations and file content. This structured data is then persisted via `KnowledgeBaseService.java`.
- **Autonomous Learning Loop**:
    - `SmartFallbackService.java` is now configured to log failed queries as "Knowledge Gaps."
    - A new scheduled agent, `AutonomousExplorationService.java`, has been created. It proactively analyzes these knowledge gaps, formulates internal research questions, and uses the existing RAG pipeline to find and verify answers.

Of course. Here are the patch notes formatted for a Git commit message, based on your request.

Commit Message / Patch Notes
feat(ui, rag): Integrate intelligent speech-to-text interface

This feature introduces a speech recognition capability to the user interface, allowing users to input queries via voice. The implementation focuses on maximizing code reuse by integrating seamlessly with the existing RAG pipeline and minimizing backend changes.

✨ Mission 1: Frontend - Voice Recognition UI and Client Logic

- **UI/UX (`chat-ui.html`):**
    - Added a new microphone icon button to the chat input bar for initiating voice input.
    - Implemented CSS styles to provide visual feedback to the user during the recording state (e.g., color change, animation).

- **Client-Side Logic (`chat.js`):**
    - Implemented browser-based speech recognition using the **Web Speech API** (`SpeechRecognition` object).
    - The recognized speech is automatically populated into the `messageInput` field, allowing users to review and edit the transcribed text before sending.
    - **Crucially, this reuses the existing `sendMessage` function**, sending the transcribed text through the established chat API endpoint with no new backend routes required.

---

✨ Mission 2: Backend - Voice Input Context Awareness

- **Contextual Metadata (`ChatRequestDto.java`, `chat.js`):**
    - The client now sends an `inputType: "voice"` field in the request payload when a query is generated via speech recognition.
    - The `ChatRequestDto` has been updated to include the `inputType` field.

- **Intelligent Ingestion (`CognitiveStateExtractor.java`):**
    - The `CognitiveStateExtractor` service now detects the `"voice"` input type and records it in the `CognitiveState` object.
    - This provides crucial context to the RAG pipeline, laying the groundwork for fut
feat(rag): Implement Intelligent Distillation Gate for large inputs

This feature introduces a robust, cost-effective pre-processing gate to handle large, unstructured user inputs. To prevent excessive costs and potential token-limit errors in the complex RAG pipeline, this gate intelligently distills long messages into a concise summary and prompts the user for confirmation before engaging the full RAG chain.

✨ Mission 1: Large Input Detection and Distillation Pipeline

- **Configurable Input Gateway (`ChatApiController.java`):**
    - Implemented a defensive check at the beginning of the `streamChat` method.
    - Added new configuration properties in `application.yml` (`abandonware.input.distillation.enabled`, `abandonware.input.distillation.threshold`) to enable/disable the feature and control the character length threshold that triggers it.
    - If an input exceeds the threshold, it is now routed to the new `InputDistillationService` instead of the main chat service.

- **Cost-Effective Distillation Service (`InputDistillationService.java`):**
    - Created a new, single-responsibility `@Service` dedicated to summarizing long inputs.
    - This service explicitly uses a fast, low-cost LLM (e.g., `gemini-1.5-flash`), configured via `application.yml`, bypassing the main `ModelRouter` to ensure cost control.
    - The `distill()` method uses a targeted prompt to summarize the text into core questions or key points, returning the result asynchronously.

---
feat(rag): Implement Intelligent Distillation Gate for Large Inputs

This feature introduces a robust, cost-effective pre-processing gate to handle large, unstructured user inputs. To prevent excessive costs and potential token-limit errors in the complex RAG pipeline, this gate intelligently distills long messages into a concise summary and prompts the user for confirmation before engaging the full RAG chain.

✨ **Mission 1: Large Input Detection and Distillation Pipeline**

* **Configurable Input Gateway (`ChatApiController.java`):**
    * Implemented a defensive check at the beginning of the `streamChat` method.
    * Added new configuration properties in `application.yml` (`abandonware.input.distillation.enabled`, `abandonware.input.distillation.threshold`) to enable/disable the feature and control the character length threshold that triggers it.
    * If an input exceeds the threshold, it is now routed to the new `InputDistillationService` instead of the main chat service.

* **Cost-Effective Distillation Service (`InputDistillationService.java`):**
    * Created a new, single-responsibility `@Service` dedicated to summarizing long inputs.
    * This service explicitly uses a fast, low-cost LLM (e.g., `gemini-1.5-flash`), configured via `application.yml`, bypassing the main `ModelRouter` to ensure strict cost control.
    * The `distill()` method uses a targeted prompt to summarize the text into core questions or key points, returning the result asynchronously as a `Mono<String>`.

---

✨ **Mission 2: Asynchronous User Confirmation UX/UI Flow**

* **Confirmation-Needed Event (`ChatStreamEvent.java`, `ChatApiController.java`):**
    * Added a new `NEEDS_CONFIRMATION` event type to the `EventType` enum.
    * The `ChatApiController` now streams the distilled summary back to the client using this new event type via Server-Sent Events (SSE), creating an intermediate confirmation step.

* **Client-Side Confirmation Logic (`chat.js`):**
    * The SSE event handler is updated to recognize the `NEEDS_CONFIRMATION` event.
    * Upon receiving this event, the UI dynamically renders the summary message along with **[Proceed]** and **[Cancel]** buttons.
    * If the user clicks **[Proceed]**, the client re-submits the *summarized text* as a new message, which is now short enough to be processed safely and efficiently by the standard RAG pipeline.
✨ Mission 2: Asynchronous User Confirmation UX/UI Flow

- **Confirmation-Needed Event (`ChatStreamEvent.java`, `ChatApiController.java`):**
    - Added a new `NEEDS_CONFIRMATION` event type to the `EventType` enum.
    - The `ChatApiController` now streams the distilled summary back to the client using this new event type via Server-Sent Events (SSE), creating an intermediate confirmation step.

- **Client-Side Confirmation Logic (`chat.js`):**
    - The SSE event handler is updated to recognize the `NEEDS_CONFIRMATION` event.
    - Upon receiving this event, the UI dynamically renders the summary message along with **[Proceed]** and **[Cancel]** buttons.
    - If the user clicks **[Proceed]**, the client re-submits the *summarized text* as a new message, which is now short enough to be processed safely and efficiently by the standard RAG pipeline.
feat(agent): Implement dynamic personas, interactive RAG controls, and multimodal capabilities

This feature introduces a major evolution of the AI agent, enhancing its intelligence, user experience, and functional scope. The system can now dynamically adapt its personality, offer users granular control over the RAG pipeline, and process visual information through multimodal inputs.

---

✨ **Mission 1: Intelligence Enhancement - Dynamic Persona & Dialogue Strategy**

-   **Configuration-driven Personas (`application.yml`):**
    -   Added a new `abandonware.persona` namespace to define multiple AI personas (e.g., `tutor`, `analyzer`, `brainstormer`) and their corresponding system prompt instructions externally.

-   **Cognitive State Expansion (`CognitiveState.java`, `CognitiveStateExtractor.java`):**
    -   The `CognitiveState` record is now enhanced with a `persona` field.
    -   `CognitiveStateExtractor` has been upgraded to determine the most appropriate persona based on the user's query complexity and intent, which is then recorded in the cognitive state.

-   **Dynamic Prompt Generation (`PromptBuilder.java`):**
    -   The system now moves away from a static system prompt. `PromptBuilder` dynamically constructs the system prompt at runtime by loading the selected persona's instructions from the configuration, based on the context provided by `CognitiveState`.

---

✨ **Mission 2: UX Improvement - Interactive RAG Control Panel**

-   **RAG Control UI (`chat-ui.html`, `chat.js`):**
    -   Introduced an "Advanced Search Options" panel in the chat interface.
    -   This panel includes new UI components (e.g., checkboxes) that allow users to control RAG parameters in real-time, such as search scope (`Web`, `Documents`) and source credibility (`Official Sources Only`).

-   **Extended DTO (`ChatRequestDto.java`):**
    -   New fields (e.g., `boolean officialSourcesOnly`, `List<String> searchScopes`) have been added to the `ChatRequestDto` to transport the user's RAG preferences to the backend.

-   **Dynamic Retrieval Logic (`HybridRetriever.java`):**
    -   The `HybridRetriever` now accepts the new RAG control options from the DTO.
    -   It dynamically adjusts the behavior of its handlers, such as filtering out sources below a certain credibility tier in `WebSearchHandler` by leveraging `AuthorityScorer` when the `officialSourcesOnly` flag is active.

---

✨ **Mission 3: Capability Enhancement - Multimodal RAG (Image Analysis)**

-   **Image Upload UI (`chat-ui.html`, `chat.js`):**
    -   The frontend now supports image uploads via paste or file selection. Uploaded images are Base64-encoded and sent along with the text message.

-   **Multimodal API & DTO (`ChatRequestDto.java`, `ChatApiController.java`):**
    -   `ChatRequestDto` is updated with a new `imageBase64` field to handle the image data.
    -   The existing `/stream` API endpoint in `ChatApiController` is now capable of processing requests containing both text and image data.

-   **Multimodal Backend Logic (`GeminiClient.java`, `LangChainChatService.java`):**
    -   `GeminiClient` now includes a new method to handle multimodal requests, specifically designed to call image-capable models like `gemini-1.5-pro`.
    -   `LangChainChatService` has been extended to detect image data in requests and include both text and image content in the context when calling the new multimodal method in `GeminiClient`.

feat(RAG): Implement Dynamic Comparative Analysis Pipeline

This patch introduces a major enhancement to the RAG pipeline, enabling the system to autonomously understand and process comparative questions (e.g., "Which is better, A or B?") without any hardcoded logic for specific entities.

The system now dynamically identifies multiple entities within a user's query, formulates a multi-faceted retrieval plan, and generates a structured, comparative analysis.

Key Changes:
Pre-processing & Intent Recognition:

CognitiveState:

Added COMPARATIVE_ANALYSIS to the Intent enum.

Introduced comparisonEntities list to store dynamically identified subjects for comparison.

SubjectResolver:

Enhanced to resolve multiple known entities from the user's query against the DomainKnowledge database, moving beyond single-subject identification.

CognitiveStateExtractor:

Now identifies a COMPARATIVE_ANALYSIS intent when two or more entities are detected alongside comparison-related keywords (e.g., "vs", "compare", "better than").

Dynamic Retrieval Orchestration:

ChatService:

Acts as the central orchestrator for the new comparative analysis flow.

When the COMPARATIVE_ANALYSIS intent is detected, it dynamically generates a list of search queries by combining the identified entities with a set of standard evaluation criteria (e.g., "performance," "story," "synergy").

Orchestrates the retrieval of this information via the HybridRetriever.

Dynamic & Structured Prompt Engineering:

PromptBuilder:

Updated to handle the COMPARATIVE_ANALYSIS intent.

It now dynamically constructs a prompt template, instructing the LLM to perform a structured analysis based on the provided criteria and entities.

Contextual data is organized by entity (e.g., ### [Entity A] Information, ### [Entity B] Information) to ensure the LLM can clearly distinguish and compare the retrieved information.
Commit Message / Patch Notes
feat(agent): Evolve into an Autonomous, Self-Learning AI Agent

This feature graduates the RAG pipeline from a simple information retriever to an intelligent agent capable of self-learning, contextual understanding, and dynamic optimization. The system now autonomously identifies user intent, verifies facts, learns from its failures, and adapts its strategy to provide more accurate and reliable answers.

🚀 Phase 1: Foundation Fortification - Intent & Trust
This phase establishes an "Intelligence Gate" to understand user intent beyond keywords and to prioritize trustworthy information.

CognitiveStateExtractor (Intent Analyzer):

Now identifies COMPARATIVE_ANALYSIS intent from user queries (e.g., "Which is better, A or B?").

Extracts multiple entities into a comparisonEntities list within the CognitiveState, enabling structured comparative analysis instead of simple lookups.

EmbeddingModelCrossEncoderReranker (Intelligent Reranker):

Introduces a multi-dimensional scoring model: Final Score = (Similarity * Authority Weight) + Synergy Bonus.

AuthorityScorer: Implemented to score retrieved documents based on source credibility (e.g., official wikis, major news outlets, personal blogs) and apply a corresponding weight.

AdaptiveScoringService: Activated to process user feedback (👍/👎), build SynergyStat, and apply a "Synergy Bonus" to reward information pairings that users find helpful, personalizing the ranking over time.

🧠 Phase 2: Intelligence & Reliability Engine - Dual-Verification Gate
This phase implements a "Dual-Verification Gate" to prevent hallucinations and ensure the factual accuracy of all generated answers.

EvidenceGate (Pre-Generation Verification):

Before calling the LLM, this gate automatically assesses whether the retrieved context is sufficient to generate a high-quality answer.

If evidence is insufficient, it triggers the EvidenceRepairHandler to perform a secondary, refined search, minimizing low-quality responses and LLM hallucinations.

ClaimVerifierService (Post-Generation Verification):

After the LLM generates a draft, this service deconstructs the answer into individual claims.

Each claim is then cross-verified against the source evidence. Unsupported claims are automatically removed or flagged as "unverified," drastically improving the final answer's reliability.

🤖 Phase 3: Autonomous Learning & Adaptation
This final phase transforms the system into a fully autonomous agent that learns from its mistakes and optimizes its own problem-solving strategies without manual intervention.

AutonomousExplorationService (Self-Learning Agent):

When the SmartFallbackService fails to find an answer, the query is now logged as a "Knowledge Gap."

This service runs periodically as a scheduled agent, analyzing these gaps, formulating its own research questions, and using the existing RAG pipeline to find answers, effectively creating a self-learning loop that expands the knowledge base.

StrategySelectorService (Dynamic Strategy Selector):

Implements a Multi-armed Bandit algorithm to dynamically select the optimal retrieval strategy.

Based on the query's intent (from CognitiveState), it chooses the most effective path (e.g., web-first, vector-DB-first, self-ask decomposition), optimizing for both performance and answer quality.
Of course. Here are the patch notes in English, formatted for Git.

Refactor: Generalize RAG Pipeline for Domain-Agnostic Processing and Dynamic Vector Search
Date: 2025-08-17

Version: v1.2.0

Author: System Architect

✨ Features
Implemented Dynamic 'EDUCATION' Domain Detection and Vector Search

Added a feature to automatically switch to the 'EDUCATION' domain when keywords such as 'academy' or 'government subsidy' are detected in the user's conversation context.

When the 'EDUCATION' domain is identified, the system now bypasses traditional keyword search and instead generates a query vector using an embedding model. This enables a vector similarity search, providing semantically richer and more accurate results.

Applied Dynamic Reranking Based on Vector Similarity

Improved the ranking logic to calculate the Cosine Similarity between the user's query vector and the retrieved documents. This ensures that the most relevant information is prioritized and passed to the LLM.

♻️ Refactor
Improved Query Preprocessor Logic

Removed the hardcoded dependency between GuardrailQueryPreprocessor and the game-specific GameDomainDetector.

The CognitiveStateExtractor now first determines the domain, allowing the preprocessor to dynamically apply the appropriate rules.

Generalized Authority Scorer System

Eliminated hardcoded site weights (e.g., namu.wiki) in the AuthorityScorer.

Domain-specific credibility weights can now be configured dynamically via application.yml. For the 'EDUCATION' domain, a higher weight is assigned to government (GOV) and educational (EDU) sources.

Generalized Rule-Based Scorer

Removed the fixed game-character relationship rules from the RelationshipRuleScorer.

The scorer now incorporates vector similarity scores for ranking in the 'EDUCATION' domain and is structured to dynamically fetch rules from the KnowledgeBaseService for other domains.

Conditional Activation of Domain-Specific Sanitizer

Modified the FactVerifierService to activate the GenshinRecommendationSanitizer only when the 'GAME' domain is active, isolating its logic and preventing it from affecting other domains.

🐛 Fixes
Resolved General-Purpose Query Contamination Issue

Fixed a critical bug where general queries (e.g., "Daejeon government subsidy academy") were misinterpreted as game-related terms, leading to query distortion and filtering. All queries are now processed through the appropriate domain-specific pipeline.

Resolved Ranking Bias Issue

Corrected the ranking bias that unfairly down-weighted official sources like hrd.go.kr by removing the preferential weighting for specific game community sites.

Feat(rag-pipeline): Implement Conditional Vector Search Pipeline
Date: 2025-08-17

Version: v1.3.0

Author: System Architect

This patch implements the required architectural changes to enable a dynamic, conditional RAG pipeline. The system can now switch between keyword-based and vector-based search strategies based on the query context.

✨ Key Changes
Introduced Execution Mode Flag in CognitiveState

Added an ExecutionMode enum (KEYWORD_SEARCH, VECTOR_SEARCH) to the CognitiveState object.

The CognitiveStateExtractor has been updated to set the mode to VECTOR_SEARCH when it detects education-related keywords like 'academy' or 'government subsidy'.

Updated GuardrailQueryPreprocessor for Conditional Logic

The preprocessor now checks the ExecutionMode. If set to VECTOR_SEARCH, it bypasses all existing keyword protection and transformation logic, returning the original, unmodified query text to be used for embedding.

Enhanced HybridRetriever for Dynamic Strategy Execution

The HybridRetriever is now aware of the ExecutionMode.

When in VECTOR_SEARCH mode, it exclusively queries the vector database, skipping the standard web/keyword search.

Results from the vector search are now sorted based on cosine similarity before being returned to the next stage of the pipeline.

Resolved Post-processing Contamination Issue

Blocked the game-specific recommendation logic from being applied to non-game-related answers, preventing contamination of the final output.
feat(rag): add prior-answer distillation, client-echo learning DTO, and config keys for augment/comparative

Summary

Introduce a lightweight distillation step for prior answers used in augmentation.

Add a DTO for client-echo learning batches.

Wire up configuration flags/limits for augmentation and comparative analysis in application.yml.

Key Changes

InputDistillationService: new service to condense the previous assistant answer before prompt injection during augmentation (fallback truncation when the distillation gate is enabled).

LearningItemDto: carries { q, a, evidence[], ts } for the opt-in client-echo learning mode.

Config (application.yml):

abandonware.input.distillation.enabled (bool)

abandonware.augment.max-prior-chars (int)

abandonware.comparative.default-criteria (list)

abandonware.learning.enabled / related switches

Why

Reduce token footprint and improve retrieval signal by seeding the current query with a concise, prior-answer context.

Prepare the backend to accept user-approved learning snippets without coupling it to any specific model backend.
Patch Notes / Commit Message

feat(rag, router, prompt, tools): integrate Gemini free-tier under GPT Pro agent (Files • Structured Output • Function Calling • Embedding • Live/opt)

Summary
Wire Gemini’s free capabilities beneath the default GPT Pro generator, without changing user-visible model. Key moves: (1) block header overrides, (2) insert Gemini delegates at Analyze/Web/Vector stages, (3) add responseSchema path in PromptBuilder, (4) escalate MOE routing when risk/verbosity/length demand it.

Added

RequestHeaderModelOverrideFilter (com.example.lms.web.filter)

Blocks non-whitelisted x-model-override headers.

Config: router.allow-header-override=false (default), router.header-override-allowlist=....

StructuredOutputSpec (com.example.lms.prompt.dto)

DTO to enforce structured output: schemaJson, enumValues, strict, exampleJson.

GeminiAnalyzeDelegate (com.example.lms.service.gemini)

Low-cost (Gemini 2.x Flash) schema-driven extraction for query type / candidates / evidence fields.

Short timeout (≤4s). Fails soft (returns empty).

FunctionToolBus (com.example.lms.tools)

Provider-agnostic tool registry + executor for function calling.

Built-ins:

web.search(query, topK) → EnhancedSearchService

vector.search(query, topK) → VectorDbGateway

files.get(fileId) → GeminiFilesService

GeminiFunctionAdapter (com.example.lms.tools.gemini)

Bridges model tool_calls to the shared FunctionToolBus (Gemini/OpenAI compatible).

GeminiFilesService (com.example.lms.service.gemini)

Files API wrapper (upload/reuse large docs).

upload, delete, inlineText(maxChars), splitToChunks(policy).

GeminiEmbeddingClient (com.example.lms.service.gemini)

Embedding backend/adapter (LangChain4j compatible).

(Optional PoC) GeminiLiveGateway (com.example.lms.service.gemini.live)

Live API (WS/voice) gateway for future real-time sessions.

Changed

ModelRouter (com.example.lms.model)

MOE escalation: intent∈{ANALYSIS,COMPARISON,RECOMMENDATION} or risk=HIGH or verbosity∈{deep,ultra} or tokenOut≥1536 ⇒ route to MOE.

Respects router.allow-header-override=false (override ignored).

Debug log: ModelRouter decision intent={}, risk={}, verbosity={}, maxTokens={}, useMoe={}.

PromptBuilder (com.example.lms.prompt)

Accepts responseSchema from PromptContext; emits schema-focused system hints; tightens temperature to ~0.0–0.2 for extraction.

Preserves “no ad-hoc string concat” policy.

AnalyzeHandler (com.example.lms.service.rag)

When gemini.structured-output.enabled=true, calls GeminiAnalyzeDelegate; on failure, falls back to legacy hygiene/morphology.

WebHandler (com.example.lms.service.rag.web)

Keeps existing Naver search path.

Adds parallel function-calling path via FunctionToolBus; failures are ignored (partial results preserved).

VectorDbHandler (com.example.lms.service.rag.vector)

Embedding backend switch: embedding.backend∈{openai,gemini} (default: gemini).

Reuses long context via Files API fileId to cut tokens/latency.

HybridRetriever

Stronger partial-result semantics & diagnostics.

SearchContext flags: usedGeminiStructuredOutput, usedFunctionTools, fileIdsInContext.

LangChainChatService (or ChatService)

Ensures all prompts go through PromptBuilder.build(ctx).

Injects FunctionToolBus ToolSpecs into supported ChatModels.

Configuration (application.properties)
router.allow-header-override=false
gemini.structured-output.enabled=true
embedding.backend=gemini

gemini.api-key=${GOOGLE_API_KEY:${GEMINI_API_KEY:}}
gemini.curator-model=gemini-2.5-flash
gemini.embedding.model=embedding-001
gemini.timeouts.connect-ms=2000
gemini.timeouts.read-ms=8000

Behavior Notes

Generation stays on GPT Pro (gpt-5-chat-latest); Gemini handles assist roles:

Analyze: schema extraction (bias reduction on A vs B).

Web: function calls → ToolBus (search/vector/files).

Vector: embeddings + long-doc reuse via Files API.

All Gemini paths fail soft; the chain never crashes—partial evidence returns.

Test Plan

Override blocking: with allow=false, verify x-model-override cannot change routing.

Comparative queries: AnalyzeHandler yields {candidateA,candidateB,evidence[]} via schema; no “locked to one side” outputs.

Function calling: tool_calls route through ToolBus; on tool errors, pipeline continues.

Long-doc RAG: upload once, reuse fileId; confirm token/latency drop.

Embedding switch: embedding.backend=gemini quality/limits; graceful degrade on quota.

Version purity: detect any dev.langchain4j:0.2.x and STOP with conflict report.

Security/Ops

Header override is opt-in via allowlist.

No breaking changes to public APIs; defaults preserve current behavior.

All new services log at INFO/DEBUG with PII-safe payloads.Commit Message / Patch Notes

feat(rag, router, prompt, gemini): header-override guard, structured output plumbing, Gemini stubs, embedding backend toggle

Summary
Prepared updated codebase as src10.zip. This patch hardens request safety, adds structured-output wiring, and introduces Gemini integration stubs so the GPT Pro agent can later leverage Gemini (Files/Embedding/Analyze) with minimal code churn.

Changes

Safety Guard

Add RequestHeaderModelOverrideFilter to whitelist-gate X-Model-Override; ignored by default.

Config: router.allow-header-override=false (default), router.header-override-allowlist=...

Structured Output Support

Add StructuredOutputSpec DTO.

Extend PromptContext with responseSchema.

Update PromptBuilder to detect schema and steer models toward JSON-shaped responses (no ad-hoc string concat).

Gemini Delegation (Stubs for future integration)

Add GeminiAnalyzeDelegate, GeminiEmbeddingClient, GeminiFilesService as extensible scaffolds for upcoming real API wiring.

Embedding Backend Switch

Enable embedding.backend=gemini to activate GeminiEmbeddingClient.
feat(memory,rag): post-answer “Understanding” module (TL;DR, key points, actions) + SSE + memory/index

- Add AnswerUnderstanding DTO (+ glossary/entities/citations/confidence)
- Implement AnswerUnderstandingService with strict JSON schema + safe fallback
- Wire UnderstandAndMemorizeInterceptor after verification, before reinforcement
- Emit UNDERSTANDING SSE event; render memory-friendly string & index embeddings
- ChatRequestDto: add understandingEnabled; frontend toggle with localStorage
- Reuse GeminiClient logging/retry/timeout/safety guards
작업을 완료했습니다. 수정된 소스 코드를 새로운 src18.zip 파일로 압축하여 제공해 드립니다. 주요 변경 사항은 다음과 같습니다.

GeminiClient 개선: 쿼리스트링 대신 x-goog-api-key 헤더를 사용하며, HTTP 오류 및 예외를 상세히 로그하도록 수정했습니다. 응답 파싱을 위해 firstTextSafe, parseDelta 등 헬퍼 메서드를 추가하고, 실제 지식 큐레이션 로직을 구현했습니다.

ChatRequestDto 확장: learningEnabled 필드를 추가하여 클라이언트가 학습 파이프라인 동작 여부를 제어할 수 있도록 했습니다.

ChatService 수정: 요청에서 learningEnabled가 참일 때만 LearningWriteInterceptor를 호출하도록 조건을 추가했습니다.

프론트엔드 업데이트: HTML에 Gemini 학습 토글을 추가하고, chat.js에서 상태를 localStorage에 저장/복원하도록 구현했습니다. 또한 전송 payload에 learningEnabled 값을 포함시켰습니다.

아래 파일을 다운로드하여 변경 사항을 확인해 주세요.
Introduced a post-answer Understanding module that converts the final answer into a structured JSON summary (TL;DR, keyPoints, actionItems, decisions, risks, followUps, glossary, entities, citations, confidence).

Wired the module into the chat pipeline after verification/sanitization and before reinforcement. Execution is gated by ChatRequestDto.understandingEnabled and presence of a final answer.

Emitted UNDERSTANDING SSE events to the UI and stored a distilled memory (TL;DR/KeyPoints/ActionItems) plus optional embedding indexing.

Enforced strict JSON schema (draft-07) with safe parsing and a robust fallback summarizer when LLM JSON is incomplete.

Reused existing Gemini client (logging, timeout, retry, circuit-breaker, safety guards). Model=gemini-2.5-pro, timeout=12s.

Added a frontend toggle with localStorage persistence; SSE handler renders Understanding cards.

Added configuration keys and full “off switch” that prevents interceptor entry and disables runtime behavior without touching the rest of the pipeline.

Kept existing RAG/Web/Verifier/Reinforcement behavior intact; no debugging payload leaks; PII length-only logging.

Pipeline placement & trigger

Placement: FactVerifierService → AnswerSanitizers → Understand & Memorize (new) → Reinforcement

Trigger: ChatRequestDto.understandingEnabled == true AND final answer string exists

New/modified files

DTO: com/example/lms/dto/answer/AnswerUnderstanding.java
Fields: tldr, keyPoints[], actionItems[], decisions[], risks[], followUps[], glossary(term, definition)[], entities(name, type)[], citations(url, title)[], confidence(double)

Prompt builder: com/example/lms/service/understanding/AnswerUnderstandingPromptBuilder.java
Builds “STRICT JSON only. No prose.” prompts; escapes question/answer via PromptEscaper

Service: com/example/lms/service/understanding/AnswerUnderstandingService.java
Uses GeminiClient.postJson(model, prompt, timeout); validates against JSON Schema; tolerant mapping with warnings; fallback summarizer (first sentence TL;DR + 3–6 bullets)

Pipeline interceptor: com/example/lms/service/chat/interceptor/UnderstandAndMemorizeInterceptor.java
Runs from ChatWriteInterceptor.afterVerified(); saves distilled memory (TranslationMemoryRepository.saveSnippet), indexes embeddings (EmbeddingStoreManager.index), and emits UNDERSTANDING via ChatStreamEmitter

DTO & SSE extension: com/example/lms/web/dto/ChatRequestDto.java (+ boolean understandingEnabled)
ChatStreamEvent.EventType += UNDERSTANDING; ChatStreamEmitter.emitUnderstanding(sessionId, AnswerUnderstanding) serializes to JSON and streams

Frontend
HTML: toggle control to show/save Understanding
JS: localStorage key aw.understanding.enabled; include understandingEnabled in request payload; handle UNDERSTANDING SSE to render TL;DR/KeyPoints/ActionItems

Configuration (application.properties)
abandonware.understanding.enabled=true
abandonware.understanding.model=gemini-2.5-pro
abandonware.understanding.timeout-ms=12000

Safety & reliability

NPE/Safety guards reused from firstTextSafe(); finishReason=SAFETY → warn and fallback

Logging: prompt/response log length only (PII masked)

Retry: 429/5xx → exponential backoff with jitter (2 attempts)

Fully deactivatable: …enabled=false bypasses the interceptor and suppresses SSE

Acceptance criteria (AC)

With toggle ON: UNDERSTANDING SSE arrives after final answer; TL;DR present; keyPoints length ~3–7

Memory write and embedding index each invoked exactly once (observable in logs)

Incomplete LLM JSON still yields a fallback summary; no exceptions propagate

With toggle OFF: module is not entered and no UNDERSTANDING SSE is sent

finishReason=SAFETY → warning log + fallback behavior

Build passes with unchanged existing tests

Deliverables in 10.zip

Updated source tree

README_understanding.md (overview, toggle usage, config keys, event format, limitations)

CHANGELOG.md (summary, file list, migration notes)

samples/understanding.json (example SSE payload)

Optional test skeletons (1–2)

Breaking changes

None. All additions are opt-in and respect the global enable/disable switch.

Suggested commit message (copy/paste)
feat(memory,rag,ui,sse): post-answer “Understanding” module (TL;DR, key points, actions) with SSE + memory/index
– Add AnswerUnderstanding DTO (incl. glossary/entities/citations/confidence)
– Strict JSON schema + safe fallback in UnderstandingService (Gemini 2.5 Pro, 12s)
– Interceptor after verification, before reinforcement; distilled memory + embeddings
– UNDERSTANDING SSE; ChatRequestDto.understandingEnabled + frontend toggle/localStorage
– Reuse existing logging/retry/timeout/safety policies; full off-switch via config
