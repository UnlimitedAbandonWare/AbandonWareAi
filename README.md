
# Condensed Canonical Technical Specification for AbandonWare Hybrid RAG AI Chatbot Service (Lossless Compression)
# Version: v1.2 (generated 2025‑08‑18)
# Notes: This file condenses the 2 300+ line specification into approximately 500 lines while preserving all unique technical facts.  Each line follows the format `[STAGE] | Component | Capability/Rule | Rationale/Constraint (optional) | NOTES (optional)`.  Duplicate statements were merged and verbose prose was rephrased into concise, unambiguous facts.

## Core Pipeline
QUERY | QueryComplexityGate | Classifies queries as simple or complex using heuristics or LLM models | Signals whether to decompose queries via SelfAskHandler
QUERY | QueryCorrectionService | Performs rule‑based spelling, spacing and punctuation corrections | Ensures clean input before LLM‑based correction
QUERY | LLMQueryCorrectionService | Uses a large language model to refine corrected queries while preserving proper nouns and domain terms | Consults domain dictionary to avoid altering names
QUERY | QueryDisambiguationService | Resolves ambiguous tokens via domain dictionary and LLM rephrasing | Emits a confidence score indicating high or low confidence
QUERY | QueryAugmentationService | Adds synonyms or related keywords when enabled | Disabled by default to avoid noise; limited by SmartQueryPlanner
QUERY | SmartQueryPlanner | Restricts the number of expanded queries to prevent combinatorial explosion | Coordinates with EvidenceRepairHandler to control query expansion
QUERY | NonGameEntityHeuristics | Distinguishes generic terms from domain‑specific entities during preprocessing | Prevents misclassification of game‑neutral words
QUERY | DefaultDomainTermDictionary | Stores protected terms per domain to survive correction and disambiguation | InMemoryDomainTermDictionary used for testing
QUERY | LLMNamedEntityExtractor | Invokes an LLM to extract entities when regex heuristics fail | Implements the NamedEntityExtractor interface
QUERY | ParsedQuery | Holds structured components (tokens, synonyms, variants) after corrections and analysis | Used downstream for retrieval and ranking
QUERY | MatrixTransformer | Generates additional query variants when required based on domain and intent | Supports robust search and recall
QUERY | QueryHygieneFilter | Removes banned words or domain prefixes during disambiguation | Ensures safe and policy‑compliant queries
QUERY | VerbosityDetector | Analyses query and conversation context to suggest a verbosity level (brief/standard/deep/ultra) | Influences context budgets and model choice
QUERY | SectionSpecGenerator | Determines which prompt sections to include based on verbosity and query intent | Produces a section map for PromptBuilder
QUERY | VerbosityProfile | Encapsulates verbosity hints to inform context budgets and minimum answer lengths | Passed through the pipeline
QUERY | CompositeQueryContextPreprocessor | Combines guardrails, hygiene filters and preprocessing steps | Orchestrates correction and disambiguation according to configuration
QUERY | CognitiveStateExtractor | Derives user intent, subject and domain hints using heuristics or LLMs | Stores results in CognitiveState for routing
QUERY | CognitiveState | Holds analysis results including intent, subject, domain and verbosity | Guides routing, model selection and retrieval strategies
QUERY | GuardrailQueryPreprocessor | Injects protected terms and dynamic interaction rules from the knowledge base into the prompt context | Enforces domain policies and safety
QUERY | DefaultQueryContextPreprocessor | Orchestrates correction, disambiguation and domain detection according to configuration | Prepares prompt context before retrieval
QUERY | QueryComplexityClassifier | Detects multi‑hop or complex questions via heuristics or LLMs | ModelBasedQueryComplexityClassifier queries a model; thresholds configurable
QUERY | QueryContextPreprocessor | Defines a contract for preparing prompt context prior to retrieval | Allows custom preprocessing implementations
QUERY | QueryTransformer | Abstracts the process of generating alternative queries for retrieval | Implementations choose transformation based on domain and intent
QUERY | DefaultQueryTransformer | Selects transformation strategy according to domain and intent | Generates robust query variants to maximise recall
QUERY | QueryCorrectionService | Returns both the corrected text and metadata about applied fixes | Supports auditing of corrections
QUERY | QueryDisambiguationService | May call QueryHygieneFilter to remove domain prefixes or banned words | Ensures safe and focused queries
QUERY | VerbosityDetector | Uses conversation context to infer if the user expects a brief or detailed response | Influences document count and model selection

## Retrieval
RETRIEVAL | HybridRetriever | Orchestrates memory, self‑ask, analyse, web, vector and evidence repair handlers | Maximises recall and precision across retrieval sources
RETRIEVAL | MemoryHandler | Retrieves recent session snippets and verified translation memory snippets as evidence | Uses MemoryAsEvidenceAdapter to format snippets
RETRIEVAL | SelfAskHandler | Decomposes complex queries into sub‑questions using an LLM and aggregates evidence | Enabled when QueryComplexityGate signals complexity
RETRIEVAL | AnalyzeHandler | Generates search tokens through morphological analysis for languages like Korean | Improves recall for non‑English queries
RETRIEVAL | WebSearchHandler | Performs real‑time web searches with domain filters and rate limiting | Integrates AuthorityScorer to rank results by credibility
RETRIEVAL | VectorDbHandler | Queries a vector store using similarity search to retrieve static domain knowledge | Acts as fallback when web retrieval fails or is disabled
RETRIEVAL | EvidenceRepairHandler | Triggers an additional search cycle when evidence is insufficient | Runs at most once to prevent infinite loops
RETRIEVAL | DefaultRetrievalHandlerChain | Defines handler order: memory → self‑ask → analyse → web → vector → repair | Configurable via RetrieverChainConfig
RETRIEVAL | RetrievalHandler | Interface that decouples handlers; each handler catches exceptions and returns partial results | Enables independent unit testing and fault isolation
RETRIEVAL | RetrievalMode | Supports RETRIEVAL_ON (web and vector), RAG_ONLY (vector) and RETRIEVAL_OFF (memory only) | Configurable per session
RETRIEVAL | SearchContext | Contains retrieved documents, warnings, timing and evidence quality flags | Passed to ranking and context builder
RETRIEVAL | ReciprocalRankFuser | Merges lists from web, vector, memory and self‑ask using reciprocal rank fusion (RRF constant k) | Softmax fusion optional
RETRIEVAL | LightWeightRanker | Performs quick lexical ranking to reduce candidate list before deeper scoring | Used prior to cross‑encoder reranking
RETRIEVAL | SelfAskWebSearchRetriever | Performs web searches for each sub‑query and merges snippets | Supports decomposition of complex questions
RETRIEVAL | AnalyzeWebSearchRetriever | Consumes tokens from AnalyzeHandler and applies morphological filters for targeted search | Enhances recall for languages with complex morphology
RETRIEVAL | EmbeddingClient | Generates embeddings for queries and documents using OpenAI or local models | VectorDbHandler uses these embeddings for similarity search
RETRIEVAL | EmbeddingStoreManager | Manages vector indexes and embeddings within the vector store | Supports nearest neighbour search and index management
RETRIEVAL | TavilyWebSearchRetriever | Placeholder for alternative web search service integration | Enables future domain‑specific search integration
RETRIEVAL | Handlers | Respect session isolation by including session metadata in cache keys | Prevent cross‑session data leakage
RETRIEVAL | Retrieval caches | Store results per query and session ID with expiration to reduce API calls | Configurable via CacheConfig
RETRIEVAL | EvidenceRepairHandler | Uses SmartQueryPlanner to expand or reformulate the query for a second attempt | Increases recall without runaway searches

## Reranking
RERANK | AuthorityScorer | Weights documents based on domain credibility tiers (OFFICIAL, TRUSTED, COMMUNITY, UNVERIFIED) using exponential decay | Domain weights configurable via application.yml
RERANK | RelationshipRuleScorer | Adjusts scores based on dynamic rules from the knowledge base (preferred partners, discouraged pairs, contains, etc.) | Supports custom rule types and weights
RERANK | DefaultLightWeightRanker | Provides an initial lexical ranking based on token overlap and heuristics | Acts as a coarse filter before deeper scoring
RERANK | CrossEncoderReranker | Uses a BERT‑like model to compute semantic similarity between query and document | Only top candidates proceed due to computational cost
RERANK | EmbeddingModelCrossEncoderReranker | Integrates cross‑encoder scores with rule scores and synergy bonuses | Applies dynamic synergy weight and authority decay multiplier
RERANK | Synergy bonuses | Reflect user feedback; positive and negative reactions adjust ranking | AdaptiveScoringService computes bonuses per domain
RERANK | AdaptiveScoringService | Calculates synergy bonuses using positive/negative feedback counts and smoothing constant k | Domain‑specific and smoothed
RERANK | Reranker settings | Configurable via application.yml, including top‑n candidates and weight factors | Supports A/B testing of ranking strategies
RERANK | NoopCrossEncoderReranker | Disables cross‑encoder scoring when not required | Acts as pass‑through reranker in low‑cost scenarios
RERANK | ReciprocalRankFuser | Assigns scores based on reciprocal of rank positions across lists; k smooths lower ranks | Documents high in any list receive a boost
RERANK | Softmax fusion | Converts scores to probabilities using temperature; optional alternative to RRF | Sharpens focus on top documents
RERANK | CrossScore formula | Final score = crossScore + ruleBoost + synergyBonus; may be normalized or clamped | Prevents domination by a single factor

## Context & Prompt
CONTEXT | ContextOrchestrator | Builds unified context from ranked documents, memory and history within token budgets | Removes duplicates and prioritizes authoritative sources
CONTEXT | PromptBuilder | Constructs system, user and context prompts with standardized template | Injects dynamic rules, protected terms and instructions
CONTEXT | PromptContext | Holds user query, subject, domain, protected terms and section specification | Used to build prompts
CONTEXT | Section ordering | Guides the LLM through context sections: previous answer, vector RAG, history, web, memory | Defined by PromptBuilder
CONTEXT | Context assembly | Prioritizes authoritative sources and demotes low‑credibility documents | Ensures maximal information density
PROMPT | System prompts | Include instructions about allowed behaviours (discourage hallucinations, cite sources) | Guide the model’s conduct and style
PROMPT | User prompts | Include sanitized query and indicate intent (recommendation, explanation, analysis, comparative) | Provide context for model routing
PROMPT | Context prompts | Include selected evidence sections such as previous answer, vector RAG, history, web and memory | Structured within token limit according to verbosity
PROMPT | PromptBuilder | Avoids ad‑hoc string concatenation; centralizes prompt construction; ensures proper escaping | Improves maintainability and safety
PROMPT | Verbosity hints | Influence the number of documents selected and minimum answer length | Align with verbosity profiles (brief, standard, deep, ultra)
PROMPT | Audience hints | Allow tailoring answers to novice or expert users | Optional hint used in prompts
PROMPT | Citation style | Determines whether citations appear inline or as footnotes | Configurable via PromptContext
PROMPT | PromptContext.sectionSpec | Indicates which context sections should appear for given verbosity level | Generated by SectionSpecGenerator
PROMPT | Dynamic interaction rules | Injected into prompts to inform the model about preferred or discouraged combinations | Derived from knowledge base rules

## Generation
GENERATION | ModelRouter | Selects the appropriate language model based on intent, risk, verbosity and token budget | Routes high‑stakes queries to high‑tier models
GENERATION | MOE escalation | Routes to high‑tier model when intent in high‑stakes list, risk is high, verbosity is deep/ultra or token budget exceeds threshold | Supports high‑stakes tasks
GENERATION | ChatModel | Abstracts underlying LLM provider and exposes unified API for sending prompts | Enables switching providers without code changes
GENERATION | DynamicChatModelFactory | Creates ChatModel instances with appropriate parameters when no injected bean exists | Supports dynamic model creation and caching
GENERATION | Model resolution | Prefers injected beans, then cached models, then dynamic factory creation | Ensures fallback path when provider unavailable
GENERATION | Temperature policy | Uses lower temperature (e.g., 0.3) for high‑tier models and higher temperature (e.g., 0.7) for base models | Balances stability and creativity
GENERATION | AnswerExpanderService | Ensures deep and ultra verbosity answers meet minimum word counts by expanding concise answers | Triggered by LengthVerifierService
GENERATION | LengthVerifierService | Checks whether the generated answer satisfies length requirements before expansion | Works with AnswerExpanderService
GENERATION | ModelRouter logging | Logs routing decisions including intent, risk, verbosity and token budget | Provides transparency for debugging and monitoring

## Verification & Sanitization
VERIFY | FactVerifierService | Computes coverage and contradiction metrics by comparing draft answer to retrieved context | Rejects answers with low coverage or high contradiction
VERIFY | EvidenceGate | Checks whether sufficient evidence exists before LLM generation; triggers EvidenceRepairHandler if insufficient | Skips generation when evidence is lacking
VERIFY | NamedEntityValidator | Ensures named entities in draft answer appear in context or memory; unknown names cause fallback response '정보 없음' | Prevents hallucinated names
VERIFY | ClaimVerifierService | Extracts individual claims and verifies each against context using an LLM or fact‑checking API | Removes unsupported claims; unsupported answers become '정보 없음'
VERIFY | Claim classification | Classifies claims as supported, contradicted or lacking information | Guides modification or removal of unsupported assertions
VERIFY | Evidence sufficiency metrics | Include number of subject mentions, authority scores and variety of sources | Domain‑specific thresholds enforced
VERIFY | AnswerSanitizers | Enforce domain policies by removing disallowed recommendations, profanity or unsafe content | Implemented as chain of responsibility
VERIFY | GenshinRecommendationSanitizer | Filters invalid character pairings in Genshin domain | Activated only when game domain is active
VERIFY | SmartFallbackService | Suggests refined queries when system cannot answer due to insufficient evidence | Encourages user to refine question or narrow scope
VERIFY | FallbackHeuristics | Generate alternative queries by adding details or narrowing scope; encapsulated in FallbackResult | Helps users improve retrieval
VERIFY | Verification logging | Logs claim verdicts and evidence metrics for audit and debugging | Enhances transparency and traceability
VERIFY | External fact checkers | ClaimVerifierService may call services like FactCheck.org or Wikidata to validate claims | Extensible via configuration
SANITIZE | AnswerSanitizers | May insert warning messages for sensitive topics; enforce profanity filtering and policy compliance | Domain‑specific sanitizers follow general ones
SANITIZE | Sanitizer chain order | General sanitizers run before domain‑specific ones to ensure proper layering | Order matters for correct modifications

## Reinforcement Learning
REINFORCE | MemoryReinforcementService | Computes energy scores for memory snippets using similarity, Q‑value, success ratio, confidence and recency | Weighted sum with configurable weights
REINFORCE | Energy calculation | Uses Boltzmann energy with temperature control; recency decays via exponential decay; min and max content length filters | Parameters from HyperparameterService
REINFORCE | ReinforcementQueue | Manages reinforcement tasks asynchronously to avoid blocking chat loop | Updates energy scores and stores results
REINFORCE | TranslationMemoryRepository | Stores snippets of previous answers and context for reuse | MemoryWriteInterceptor writes verified answers into translation memory
REINFORCE | AdaptiveScoringService | Computes synergy bonuses based on user feedback; bonus = (positive − negative)/(positive + negative + k) × scaling factor | Domain‑specific and smoothed
REINFORCE | FeedbackController | Captures thumbs‑up and thumbs‑down from users and updates SynergyStat records | Drives adaptive scoring and personalization
REINFORCE | SynergyStatRepository | Persists synergy statistics (domain, subject, partner, positive and negative counts) | Enables long‑term personalization
REINFORCE | StrategySelectorService | Implements multi‑armed bandit algorithm to select retrieval strategies (web‑first, vector‑first, hybrid, self‑ask) | Softmax policy with temperature
REINFORCE | StrategyPerformance | Records success, failure counts and average rewards per strategy | Used for bandit selection and reward computation
REINFORCE | Bandit annealing | Anneals exploration temperature over time based on number of interactions | Balances exploration and exploitation
REINFORCE | DynamicHyperparameterTuner | Adjusts weights such as synergy weight, authority weight and exploration temperature based on aggregated metrics | Periodic updates ensure adaptation
REINFORCE | BanditSelector | Selects memory entries according to energy and temperature | Ensures exploration of both new and old entries

## Meta & Knowledge Base
META | HyperparameterService | Provides dynamic retrieval and reranking parameters such as synergy weight at runtime; fetches from system properties or environment variables | Enables A/B testing without redeploy
META | StrategySelectorService logging | Logs strategy choices and rewards for analysis | Supports debugging meta‑learning behaviour
META | RewardHyperparameterTuner | Tunes reward weights for multi‑metric reward function using grid search or gradient‑free optimization | Improves meta‑learning performance
META | Synergy bonuses | Domain‑specific; feedback in one domain does not affect another | Prevents cross‑domain contamination
META | DynamicHyperparameterTuner | May increase exploration when performance drops unexpectedly and decrease when converging | Ensures balanced learning across sessions
KB | KnowledgeBaseService | Abstracts access to domain entities, attributes and interaction rules stored in relational tables | Decouples domain knowledge from code
KB | DomainKnowledgeRepository | Stores entities with domain, type and name; EntityAttributeRepository stores key‑value attributes | CRUD operations via JPA
KB | SubjectResolver | Identifies the subject of a query by matching entity names; chooses longest or most relevant match; uses fuzzy matching when needed | Informs retrieval and prompt building
KB | Interaction rules | Stored as relationships between entities: CONTAINS, IS_PART_OF, PREFERRED_PARTNER, DISCOURAGED_PAIR, AVOID_WITH | Administrators can define new rules via database or admin tools
KB | RelationshipRuleScorer | Evaluates documents against interaction rules and adjusts ranking scores accordingly | Uses weights from HyperparameterService
KB | Knowledge base caching | Stores frequently accessed entity names, attributes and rules to reduce database queries | Improves performance and scalability
KB | Knowledge base updates | Do not require redeploying system; new entities and rules can be inserted via scripts or admin tools | Facilitates domain expansion and agility
KB | Dynamic rules | Inform both prompt generation and ranking to respect domain policies | Supports preferred and avoided combinations across domains

## Session & Caching
SESSION | Session isolation | Ensures each user's conversation history, caches and reinforcement data remain separate | Session ID (META_SID) keys caches and retrieval results
SESSION | Caffeine caching | Accelerates retrieval and memory access without leaking data between sessions; caches expire after configurable times | Configured via CacheConfig and application.yml
SESSION | SSE streaming | Streams intermediate progress (retrieval progress, context construction, draft answer, verification outcomes) to clients via Server‑Sent Events | Improves transparency and user trust
SESSION | PersistentChatMemory | Stores conversation history beyond session to enable long‑term memory | Provides memory to MemoryHandler across sessions
SESSION | Synergy caches | Store computed bonuses for subject–partner pairs within a session; update persistent statistics after feedback | Maintains personalization per session
SESSION | SessionConfig | Defines how session metadata is propagated; session caches cleared after expiration | Enforces privacy and resource management

## Configuration & Environment
CONFIG | application.yml | Specifies minimum word counts and token budgets per verbosity level; max documents per context; reranker keep‑top‑n; model names; domain weights | Enables tuning without code changes
CONFIG | Retrieval mode | retrieval.mode chooses between RETRIEVAL_ON, RAG_ONLY and RETRIEVAL_OFF | Controls search behaviour via configuration
CONFIG | Hyperparameter settings | synergy weight, authority weight, smoothing constant k and other scoring constants configured externally | Allows real‑time adjustments
CONFIG | Cache specifications | Define maximum sizes and expiration times for caches like retrieval, memory, synergy and knowledge base | Managed via application.yml and CacheConfig
CONFIG | Environment variables | OPENAI_API_KEY, PINECONE_API_KEY, NAVER_API_CLIENT_ID and other credentials stored as environment variables | Secure handling of secrets and tokens
CONFIG | Session expiration | session.expire-after defines how long sessions remain active before caches are cleared | Balances resource use and privacy
CONFIG | LangChain4j version purity | Enforces consistent module versions (e.g., 1.0.1) to avoid runtime incompatibilities | Aborts startup on conflicts
CONFIG | Model routing keys | router.moe.high, router.moe.mini specify high‑tier and base model names; router.allow-header-override flag controls header overrides | Configures model routing logic
CONFIG | Knowledge curation | agent.knowledge-curation.enabled toggles autonomous knowledge curation agent on or off | Controls learning features
CONFIG | Input distillation | abandonware.input.distillation.enabled and threshold control pre‑processing of long inputs | Uses low‑cost LLM to summarize long messages and asks for user confirmation
CONFIG | Reranker backend | abandonware.reranker.backend selects between embedding-model and onnx-runtime; onnx.model-path and onnx.execution-provider configure ONNX settings | Supports local ONNX reranking
CONFIG | Learning pipeline | gemini.backend and related keys configure Gemini integration; learning.enabled toggles learning features | Supports Gemini‑based knowledge extraction and tuning
CONFIG | Understanding module | abandonware.understanding.enabled and model/timeout keys control post‑answer understanding and summarization | Emits UNDERSTANDING SSE event when enabled

## User Interface & Frontend
UI | chat-ui.html | Provides chat interface with text input, voice recognition, file upload, advanced search options and toggles | Sends requests via chat.js and displays results via SSE events
UI | chat.js | Handles SSE events, loading states, voice transcription, file attachments and RAG control options; persists user settings in localStorage | Sends ChatRequestDto with user preferences and toggles
UI | SSE event handling | Recognises event types such as retrieval progress, NEEDS_CONFIRMATION, UNDERSTANDING and thought events; renders summaries and confirmation buttons | Enables interactive flows and transparency
UI | Persona selection | Allows users to choose personas (tutor, analyzer, brainstormer); cognitive state extractor determines persona from intent | Dynamic persona prompts loaded from configuration
UI | RAG control panel | Provides checkboxes for search scopes (web, documents) and source credibility filters; passes options in ChatRequestDto | Allows real‑time RAG configuration by user
UI | Multimodal inputs | Supports image uploads via Base64 encoding; includes imageBase64 in ChatRequestDto; backend integrates Gemini image models | Enables multimodal queries combining text and images
UI | Voice input | Adds microphone icon; uses Web Speech API to transcribe speech into text; transcribed text populates message input | ChatRequestDto records inputType 'voice'
UI | Help button | Adds help button (#sendBtnHelp) triggering popover (#helpPopover); CSS styles defined for help icon and popover | Buttons set type="button" to prevent form submission
UI | Thought process panel | Displays AI reasoning steps streamed via thought SSE events | Allows users to see how the system analyses queries and retrieves evidence
UI | Cancel generation | Adds a stop button enabling users to cancel long‑running responses | Backend exposes /api/chat/cancel endpoint to terminate the streaming task

## Change Log & Enhancements
CHANGE | EmbeddingModelCrossEncoderReranker | Adds dynamic synergy weight bonus and authority decay multiplier to final score; synergy weight fetched at runtime from HyperparameterService | Integrates authority tiers into reranking
CHANGE | AuthorityScorer | Implements centralized credibility analyzer and exponential decay constants; adds getSourceCredibility() and decayFor() methods | Old weightFor() marked deprecated; URL host parsing enhanced
CHANGE | HyperparameterService | Adds getRerankSynergyWeight() method to fetch dynamic synergy weight from system properties or environment variables | Supports hotfixes and A/B testing without redeployment
CHANGE | RerankSourceCredibility | Creates new enum with values OFFICIAL, TRUSTED, COMMUNITY, UNVERIFIED specifically for reranking | Avoids conflicts with existing SourceCredibility enum
CHANGE | ModelRouter | Adds heuristics for MOE escalation based on intent, risk, verbosity and token budget; logs routing decisions; supports header override allowlist | Defaults to base model otherwise; preferences injected beans, cache, then dynamic factory
CHANGE | OnnxCrossEncoderReranker | Introduces ONNX‑based cross‑encoder reranker activated via abandonware.reranker.backend=onnx-runtime; falls back to embedding model if ONNX model missing | Supports local ONNX inference via onnxruntime
CHANGE | RequestHeaderModelOverrideFilter | Blocks non‑whitelisted X‑Model‑Override headers to prevent misuse; allowlist configurable | router.allow-header-override defaults false
CHANGE | InputDistillationService | Summarises long user inputs using a low‑cost LLM and asks user confirmation via NEEDS_CONFIRMATION SSE event | Configured via abandonware.input.distillation.enabled and threshold; reduces cost and prevents runaway tokens
CHANGE | Persona and RAG controls | Adds persona selection and advanced search options in UI; extends ChatRequestDto with officialSourcesOnly and searchScopes flags | HybridRetriever respects these flags and dynamic persona prompts loaded from configuration
CHANGE | Multimodal support | Adds imageBase64 in ChatRequestDto; GeminiClient handles multimodal requests; backend processes both text and image content | Uses gemini‑1.5‑pro model for images and integrates image context into retrieval
CHANGE | Comparative analysis | CognitiveStateExtractor detects COMPARATIVE_ANALYSIS intent when multiple entities and comparison keywords appear; ChatService orchestrates dynamic retrieval and structured comparative prompts | Criteria generated dynamically for evaluation
CHANGE | Autonomous exploration | SmartFallbackService logs failed queries as knowledge gaps; AutonomousExplorationService analyses gaps, formulates research queries and learns autonomously | Free‑tier API throttling enforced to control costs
CHANGE | Knowledge consistency verifier | Adds agent to audit knowledge base for contradictions using LLM; flags inconsistent rules and decays confidence of stale knowledge | Runs periodically with throttle to maintain knowledge integrity
CHANGE | Learning enhancements | GeminiCurationService extracts structured knowledge; LearningWriteInterceptor writes new knowledge into KB and vector store after verification; LearningController exposes REST endpoints for ingest, batch and tuning | Learning features optional and gated by learningEnabled flag
CHANGE | Understanding module | Adds AnswerUnderstandingService to convert final answers into TL;DR, key points, action items and other structured summaries; emits UNDERSTANDING SSE events; memory indexing of distilled summaries | Controlled by understandingEnabled flag; uses strict JSON schema with fallback summariser
CHANGE | Dynamic vector search | Enables automatic switch to VECTOR_SEARCH execution mode when education‑related keywords (e.g., 'academy', 'government subsidy') are detected; bypasses keyword search and performs vector similarity retrieval | Improves semantic matching in new domains
CHANGE | Self‑Learning RAG Pipeline Activation | Replaces stub GeminiClient implementations with live Google Gemini API calls, enabling the system to learn from conversations and user feedback | Structured knowledge extraction via GeminiCurationPromptBuilder
CHANGE | Structured Knowledge Extraction | Implements robust GeminiCurationPromptBuilder to generate JSON‑schema‑compliant prompts; extracts triples, rules and aliases from verified conversations and file content | KnowledgeBaseService persists these knowledge deltas
CHANGE | Knowledge Base Integration | LearningWriteInterceptor writes KnowledgeDelta objects into DomainKnowledgeRepository and VectorDb after verification | Ensures that only verified knowledge is assimilated
CHANGE | Evidence Pipeline | FactVerifierService and LearningWriteInterceptor pass complete LearningEvent objects including evidence and claims to the learning subsystem | Ensures learning triggers only with validated data
CHANGE | Thought Process UI | Adds new "thought process" panel to chat interface; backend streams 'thought' events via SSE showing reasoning steps like analysis, search and generation | Enhances user trust and transparency
CHANGE | Response Generation Cancellation | Users can stop long‑running response generation via a "Stop Generation" button in the UI; backend exposes /api/chat/cancel endpoint to terminate server‑side streaming tasks | Saves resources and improves user experience
CHANGE | Learning Stubs & DTOs | Adds TuningJobRequest and TuningJobStatus DTOs, GeminiCurationService, GeminiBatchService and GeminiTuningService stubs; LearningController exposes REST endpoints for ingest, batch and tuning jobs | Optional via learningEnabled configuration
CHANGE | Input Distillation Confirmation Flow | InputDistillationService returns a summary of large inputs and triggers a NEEDS_CONFIRMATION SSE event; chat.js renders summary with [Proceed] and [Cancel] buttons; user must confirm before full RAG processing | Reduces cost by avoiding unnecessary retrieval on verbose messages
CHANGE | Dynamic Personas | Adds configuration‑driven personas (tutor, analyzer, brainstormer) under abandonware.persona namespace; CognitiveState stores persona selection; PromptBuilder loads persona instructions dynamically | Enables tailored responses based on persona
CHANGE | RAG Control Panel | Introduces advanced search panel with checkboxes to select search scopes (web, documents) and an officialSourcesOnly toggle; ChatRequestDto carries these preferences; HybridRetriever filters sources accordingly | Allows users to fine‑tune retrieval behaviour
CHANGE | Multimodal API and DTO | ChatRequestDto includes imageBase64 field for image uploads; ChatApiController accepts multipart requests combining text and image; GeminiClient handles multimodal requests using gemini‑1.5‑pro; LangChainChatService includes image context when calling multimodal method | Enables multimodal RAG queries
CHANGE | Comparative Retrieval Orchestration | ChatService detects COMPARATIVE_ANALYSIS intent and dynamically generates search queries by combining identified entities with evaluation criteria (performance, story, synergy, etc.); HybridRetriever performs retrieval for each criterion | PromptBuilder constructs structured comparative prompts with separate sections per entity
CHANGE | Comparative Prompt Engineering | PromptBuilder organizes context into sections like "### [Entity A] Information" and "### [Entity B] Information"; instructs the LLM to analyse criteria individually and summarise differences | Ensures clear, structured comparative answers
CHANGE | Autonomous Exploration Service | AutonomousExplorationService periodically analyses logged KnowledgeGap events from SmartFallbackService, formulates research queries and uses the RAG pipeline to autonomously discover new knowledge; results feed into GeminiCurationService | Self‑learning loop operates within rate limits enforced by FreeTierApiThrottleService
CHANGE | Knowledge Integrity & Self‑Refinement | KnowledgeConsistencyVerifier audits the knowledge base by bundling related facts and rules, querying Gemini for contradictions and decaying the confidence of stale or contradictory knowledge; DomainKnowledge entities record lastAccessedAt and confidenceScore; KnowledgeDecayService reduces confidence over time | Ensures knowledge base remains consistent and relevant
CHANGE | FreeTierApiThrottleService | Implements centralised API rate limiting to stay within the Gemini free tier (e.g., <60 requests/minute, <1 000/day); AutonomousExplorationService and KnowledgeConsistencyVerifier depend on this service to avoid exceeding quotas | Enables continuous learning without cost
CHANGE | File‑Based RAG Foundation | Chat interface accepts file attachments; ChatRequestDto transmits files in multipart requests; LocalFileStorageService stores files; Apache Tika extracts text; PromptBuilder injects file content into the prompt under "### UPLOADED FILE CONTEXT"; VectorStoreService generates summary and chunk vectors for interactive retrieval | Enables document‑based question answering
CHANGE | Self‑Ask & File Retrieval | SelfAskHandler decomposes questions to search within uploaded files; SelfAskWebSearchRetriever retrieves evidence per sub‑query; ClaimVerifierService verifies claims against file content; NamedEntityValidator ensures proper nouns appear in file; MemoryReinforcementService updates energy scores based on file interactions | Ensures accurate answers grounded in uploaded documents
CHANGE | Learning Pipeline Integration | LearningWriteInterceptor writes verified answers into KnowledgeBaseService and vector store; GeminiCurationService extracts structured knowledge; LearningController provides REST endpoints for ingesting knowledge, building datasets and running tuning jobs; ChatRequestDto includes learningEnabled flag; UI exposes a toggle for users to enable or disable learning | Provides opt‑in knowledge assimilation
CHANGE | Intelligent Distillation Gate | ChatApiController checks input length; if above threshold, routes to InputDistillationService; the service summarises the input with a low‑cost model and returns a summary; a NEEDS_CONFIRMATION SSE event is emitted; if user accepts, summary becomes new input for full RAG; prevents token explosion and reduces costs | Configurable via abandonware.input.distillation.* keys
CHANGE | Voice Recognition Integration | chat-ui.html adds microphone button; chat.js uses Web Speech API to capture speech and populate messageInput; ChatRequestDto includes inputType field; CognitiveStateExtractor records input type to inform pipeline; voice queries use same retrieval and verification pipeline as text queries | Enables hands‑free interaction
CHANGE | Conditional Vector Search Execution | Added ExecutionMode enum with values KEYWORD_SEARCH and VECTOR_SEARCH to CognitiveState; CognitiveStateExtractor sets mode to VECTOR_SEARCH when education‑related keywords detected; GuardrailQueryPreprocessor bypasses keyword protection and transformation when vector search is active; HybridRetriever executes only vector retrieval and sorts results by cosine similarity | Improves semantic matching in domain‑agnostic contexts
CHANGE | Dynamic Reranking Based on Vector Similarity | When vector search is enabled, ranking logic calculates cosine similarity between query embedding and retrieved documents; higher similarity yields higher scores; AuthorityScorer remains in effect with domain‑specific weights; RelationshipRuleScorer integrated with vector similarity scoring | Prioritises semantically relevant documents
CHANGE | Query Preprocessor Refactor | Removed hard‑coded dependency between GuardrailQueryPreprocessor and game‑specific detectors; CognitiveStateExtractor now determines domain before guardrail processing; Non‑LLM hygiene enhancements include synonym/alias lexicon (e.g., 자바↔JVM/스프링) and lowercasing/whitespace normalization; QueryAugmentationService generates synonyms when enabled | Improves language support and reduces misclassification
CHANGE | Generalised Authority Scorer | Eliminated hard‑coded site weights; domain‑specific credibility weights loaded from configuration; high weights assigned to government (GOV) and educational (EDU) sources when education domain is active; AuthorityScorer multiplies base score by domain weight; weights adjustable via application.yml | Ensures fair ranking across domains
CHANGE | Rule‑Based Scorer Generalisation | RelationshipRuleScorer now incorporates vector similarity scores when education domain is detected; rule types and weights fetched dynamically from KnowledgeBaseService; discouraged combinations penalised; preferred combinations boosted | Supports complex interaction rules across domains
CHANGE | Conditional Activation of Domain‑Specific Sanitizers | FactVerifierService activates GenshinRecommendationSanitizer only when game domain is active; other domains unaffected | Prevents contamination of answers in unrelated domains
CHANGE | Prior‑Answer Distillation & Client Echo Learning | InputDistillationService condenses previous assistant answer before prompt injection during augmentation; new LearningItemDto carries {q, a, evidence[], ts} for client‑echo learning batches; configuration keys abandonware.augment.max-prior-chars and abandonware.learning.enabled control behaviour | Optimises token usage and enables client‑provided training data
CHANGE | Gemini Free‑Tier Integration (Files • Structured Output • Function Calling • Embedding) | Added StructuredOutputSpec DTO and responseSchema support in PromptContext; created GeminiAnalyzeDelegate for low‑cost schema‑driven query analysis; FunctionToolBus provides unified tool registry for web.search, vector.search and files.get; GeminiFilesService handles upload, delete and inlineText; GeminiEmbeddingClient supports embedding backend; GeminiFunctionAdapter bridges model tool_calls to FunctionToolBus; configuration properties enable selective Gemini usage; RequestHeaderModelOverrideFilter blocks header overrides by default | Prepares integration of Gemini features under GPT Pro agent
CHANGE | Gemini Delegation and Embedding Backend Switch | ChatService detects embedding.backend configuration; switches to GeminiEmbeddingClient when embedding.backend=gemini; fallback to OpenAI embedding when unset; OnnxCrossEncoderReranker activated when abandonware.reranker.backend=onnx-runtime; uses com.microsoft.onnxruntime runtime; OnnxRuntimeService loads model with CPU/CUDA/TensorRT execution provider; falls back to Jaccard similarity on missing model; build.gradle declares onnxruntime dependency | Enables local cross‑encoder inference and dynamic embedding backend
CHANGE | Post‑Answer Understanding Module | Added AnswerUnderstanding DTO with fields tldr, keyPoints[], actionItems[], decisions[], risks[], followUps[], glossary[], entities[], citations[], confidence; implemented AnswerUnderstandingService to call Gemini with strict JSON schema and fallback summariser; UnderstandAndMemorizeInterceptor invokes service after verification and before reinforcement; ChatStreamEmitter emits UNDERSTANDING SSE events; ChatRequestDto includes understandingEnabled flag; front‑end toggle persisted in localStorage; memory indexes distilled summaries into TranslationMemoryRepository and EmbeddingStoreManager | Provides structured TL;DR and key points to users and memory
CHANGE | Retrieval Chain Hardening & Query Morphing | DefaultRetrievalHandlerChain re‑ordered to memory → self‑ask (gated) → analyse (gated) → web → vector → repair; session memory hydration injects recent history into SearchContext; QueryComplexityGate decides whether to run SelfAsk and Analyze; Web search always attempted before vector search; EvidenceRepairHandler triggers additional search if evidence insufficient; early exit when topK fulfilled; morphological query variants generated for Korean (splitting continuous Hangul blocks and normalising hyphens); semantic variants added (e.g., 국비지원 학원); deduplication and hygiene applied; all handlers catch exceptions and return partial results | Improves recall and robustness
CHANGE | Version Purity & Diagnostics | Added VersionPurityCheck to verify a single dev.langchain4j major/minor line is present; startup aborts on conflicting versions; introduced StageSpan model to collect timing and hit counts per retrieval stage; RetrievalDiagnosticsCollector aggregates spans, warnings and hits; RetrievalDiagAspect wraps handlers to measure execution time and forward spans; TraceFilter and ReactorMdcLifter propagate sessionId and traceId across asynchronous boundaries; PromptMasker and PromptDebugLogger mask secrets in prompts and completions; DiagnosticsDumpService emits structured run records (sessionId, model usage, retrieval metrics, verification results, reinforcement feedback) at pipeline end; logs PII only as hashes or lengths | Enhances observability, performance analysis and privacy
CHANGE | Search Quality Stabilization via Gemini Hygiene | When Gemini query hygiene is enabled, LLM summarises and normalizes user questions to remove noise and unify formats; WebSearchHandler receives cleaner queries resulting in higher recall; for Gemini OFF mode, added non‑LLM hygiene improvements: strip code fences and newlines before JSON parse; synonym and alias lexicon for Korean; multi‑query augmentation and Reciprocal Rank Fusion; fail‑open JSON parsing fallback; morphological variants for improved recall | Stabilises search quality even without LLM assistance
CHANGE | GPT Pro Agent with Gemini Free‑Tier MOE Routing | GPT Pro remains primary generator; Gemini sub‑models used for hygiene, retrieval, analysis and verification within free‑tier limits; added global quota counters (RPM, RPD, TPM) aggregated per model; soft limit at 80% triggers degrade from Gemini Pro to Gemini Flash and Flash‑Lite; MOE routing escalates once per turn when risk high, verbosity deep/ultra, intent high‑stakes or token budget exceeds 1 536; EvidenceGate blocks generation when context insufficient and triggers repair; verification stack includes claim/entity checks and sanitization; retrieval chain hardened; reranker upgraded with dynamic synergy weight and authority decay; InputDistillationService for large inputs; UNDERSTANDING module with SSE; personas and RAG controls; multimodal image path; comparative analysis; diagnostics and safety; configuration keys for models, quotas and toggles | Provides robust, cost‑effective multi‑provider routing with safety guards
CHANGE | JSON Parsing Resilience for Understanding | AnswerUnderstandingService forces JSON mode in Gemini requests (response_mime_type=application/json); instructs model not to wrap output in code fences and to escape newlines; pre‑parser sanitizer strips fences, extracts first balanced JSON object and escapes control characters; optional lax parser (abandonware.understanding.parser.lax) allows unescaped control chars, single quotes and trailing commas; on parse failure, a one‑shot fixer escapes control characters inside strings and retries; on persistent failure, fallback summariser produces TL;DR; counters track strict parse, repair and fallback occurrences; logs only hashes and outcome labels | Eliminates JSON parsing failures from malformed LLM outputs
CHANGE | Retrieval Diagnostics & Hardening | StageSpan records duration and hitCount per handler; RetrievalDiagnosticsCollector aggregates spans and warns on partial failures; DefaultRetrievalHandlerChain early exits when enough evidence collected; duplicate pruning included; EvidenceRepairHandler runs at most once; retrieval chain continues after non‑fatal exceptions; prompt centralisation ensures all prompts built via PromptBuilder; ModelRouter heuristics remain deterministic; JSON parser resilience integrated into verification and understanding; EvidenceGate aborts or repairs generation when context weak; Claim and entity checks enforce supported or omitted responses; PII‑safe logging shows only lengths and hashes | Improves robustness, reduces errors and enables detailed telemetry
CHANGE | Retrieval Query Hygiene & Morphology Enhancements | AnalyzeWebSearchRetriever generates Korean spacing variants by splitting continuous Hangul blocks (e.g., “국비학원” → “국비 학원” and “국 비 학원”); normalises hyphens to spaces or removal; adds semantic variants like “국비지원 학원”; duplicates removed and capped via QueryHygieneFilter; morphological variant generation wrapped to never hard‑fail; synonyms and alias lexicon added for Java/JVM/스프링; stopword cleanup applied; multi‑query generation with Reciprocal Rank Fusion to boost recall; fail‑open on Understanding parse errors to prevent regressions when hygiene unavailable | Enhances search recall and query robustness for Korean and mixed‑language queries

## Bug Fixes & Miscellaneous
FIX | UI | Resolved duplicate "like/dislike" buttons and model names appearing on first message of new chat session; chat.js now updates initial loading bubble instead of creating a new one | Addresses UI duplication bug
FIX | Ranking Bias | Corrected ranking bias that unfairly down‑weighted official sources; removed preferential weighting for specific game community sites; ranking now based on authority scores configured via application.yml | Prevents bias towards untrusted sources
FIX | Query Contamination | Fixed bug where general queries were misinterpreted as game terms; removed hard‑coded dependency in GuardrailQueryPreprocessor and improved domain detection logic | Ensures domain‑agnostic processing
FIX | JSON Parse Failures | Eliminated recurrent JSON parsing failures in AnswerUnderstandingService caused by code fences and unescaped control characters in Gemini outputs | Ensures understanding module reliability
FIX | Session UI | Ensured SSE event handler recognises new events and displays appropriate UI components; prevented UI freeze when unknown events received | Improves user experience

## Glossary & Definitions
TERM | RAG | Retrieval‑augmented generation; combines neural language models with external knowledge sources to ground answers in evidence
TERM | SelfAsk | Technique where complex questions are decomposed into sub‑questions using an LLM; answers aggregated to answer original question
TERM | Reciprocal Rank Fusion (RRF) | Ranking method that combines multiple ranked lists by summing reciprocal rank values; smooths contributions using constant k
TERM | Cross‑Encoder | Model that jointly encodes a query and a document to compute semantic similarity; more accurate than embeddings but computationally expensive
TERM | Vector Search | Retrieval method using embeddings to find semantically similar documents in a vector store; supports nearest neighbour search
TERM | ONNX | Open Neural Network Exchange; format for representing neural networks that can be executed locally via onnxruntime
TERM | Gemini | Google Gemini API providing LLM and embedding models; used for query analysis, embeddings, multimodal processing and knowledge extraction
TERM | MOE | Mixture of Experts; dynamic model routing scheme that selects among multiple models based on intent, risk, verbosity and token budget
TERM | SSE | Server‑Sent Events; one‑way streaming protocol over HTTP used to deliver intermediate progress and structured data to clients
TERM | Knowledge Delta | Structured set of extracted knowledge (triples, rules, aliases) produced by curation services and integrated into the knowledge base

Core Pipeline — add

QUERY | TranslitNormalizer | Cross-lingual normalization of ambiguous names (ko↔en romanization, spacing/hyphen/diacritic variants, typo tolerance via token overlap/Levenshtein) | Increases recall for mixed-language & noisy inputs | Produces normalized variants for downstream candidate generation
QUERY | DisambiguationDecisionPolicy | Auto-resolve if Top-1 ≥ threshold and (Top-1 − Top-2) ≥ margin; otherwise trigger a single clarify step | Prevents premature guesses; formalizes “ask once” rule | Defaults: threshold=0.62, margin=0.08 (configurable)

Retrieval — add

RETRIEVAL | EntityDisambiguationHandler | Pre-handler before SelfAsk/Analyze; builds candidates (lexical + vector + optional web-seed) and returns DisambiguationResult {resolvedEntity?, candidates[], scores, decidedBy, confidence} | Decouples ambiguity resolution from retrieval; keeps chain fault-tolerant | Chain order: Memory → Disambiguation → SelfAsk → Analyze → Web → Vector → Repair
RETRIEVAL | CandidateSetBuilder | Lexical from aliases/regex/TranslitNormalizer; Vector from namespace="entity" top-K; Web-seed from OFFICIAL/TRUSTED domains (rate-limited) | Balances precision/recall while capping cost | Web-seed is optional; failures are ignored (chain continues)

Reranking — add

RERANK | DisambiguationScorer | Final score = α·cosine + β·authority + γ·locale + δ·domain + ε·tokenOverlap + ζ·priorSynergy | Combines semantic fit with meta priors | Defaults: α=0.55, β=0.20, γ=0.10, δ=0.10, ε=0.05, ζ=0–0.05

Context & Prompt — add

PROMPT | Entity Disambiguation section | Inject ### ENTITY DISAMBIGUATION via PromptBuilder only; no ad-hoc string concat | Keeps templates testable and safe | Limit to top-3 candidates, ≤240 chars per blurb; trim on token pressure

Generation — add

GENERATION | Low-confidence MOE escalation | Escalate once to high-tier model when confidence < 0.7 or intent ∈ {FACT_CHECK, HIGH_RISK} | Stabilizes high-stakes answers | Logged with reason; single-turn escalation only

Verification & Sanitization — add

VERIFY | Gate on resolution | If resolvedEntity missing or contradicts context, block generation via EvidenceGate and return safe fallback with SmartFallback suggestions | Avoids hallucinated entities | NamedEntityValidator only passes once entity is resolved

Session & Diagnostics — add

SESSION | Disambiguation metrics | Counters: disambig.auto, disambig.user, disambig.abort, alias.upsert.count | Operability & QA | N/A
SESSION | Retrieval span | Span disambiguation records {top1, top2, margin, decidedBy, candidateCount, confidence} | Observability for tuning | Add namespace, embedding_model_id, dim, topK as tags

Configuration & Environment — add

CONFIG | Disambiguation keys | abandonware.disambiguation.enabled=true; threshold; margin; top-k; authority-weight; locale-weight; domain-weight; prior-synergy-weight; web-seed-enabled | Runtime tuning without redeploy | Matches defaults in CHANGELOG 1.3.0
CONFIG | Entity vector namespace | abandonware.vector.entity.embedding-model-id; dim; create-if-missing=true; strict-dimension=true | Prevents embedding shape drift | Keep entity vectors separate from document vectors
CONFIG | Version purity gate | Enforce a single dev.langchain4j major/minor line (e.g., 1.0.1) across build files; STOP on mixed 0.2.x vs 1.0.x | Avoids runtime conflicts | Scan build.gradle* / settings.gradle / gradle.lockfile / versions.toml

User Interface & Frontend — add

UI | SSE: DISAMBIGUATION | One modal per turn with {query, candidates[{id,name,kind,blurb,country,snippets[]}], timeoutSec}; POST selection to resolver | Human-in-the-loop on low margins | Default timeout 12s; on timeout, safely abort and propose SmartFallback

Change Log & Enhancements — add

CHANGE | Generalized Entity Disambiguation (pre-chain) | Adds pre-handler, scorer, prompt section, SSE clarify, and diagnostics; supports ambiguous unknowns like “DW Akademie”, “Hanwha Veda/Beda”, etc. | Safer routing and fewer false resolutions | Config-gated, backwards compatible
BUILD | LangChain4j version purity | Hard gate in CI; report only conflicting coordinates on failure | Deterministic builds | Blocks startup on conflict
Core Pipeline — ADD

QUERY | TranslitNormalizer | Cross-lingual normalization of ambiguous names (ko↔en romanization, spacing/hyphen/diacritic variants, typo tolerance via Levenshtein/token overlap) | Boosts recall for mixed-language & noisy inputs | Emits normalized variants for candidate generation
QUERY | DisambiguationDecisionPolicy | Auto-resolve if Top-1 ≥ threshold and (Top-1 − Top-2) ≥ margin; otherwise trigger a single clarify step | Prevents premature guesses; formalizes “ask once” rule | Defaults: threshold=0.62, margin=0.08 (configurable)
QUERY | DisambiguationResult | Container for {resolvedEntity?, candidates[], scores, decidedBy, confidence} | Standardizes outputs for downstream handlers | Logged to diagnostics when enabled

Retrieval — ADD

RETRIEVAL | EntityDisambiguationHandler | Pre-handler before SelfAsk/Analyze; builds candidates (lexical + vector + optional web-seed) and returns DisambiguationResult | Decouples ambiguity resolution; keeps chain fault-tolerant | Chain: Memory → Disambiguation → SelfAsk → Analyze → Web → Vector → Repair
RETRIEVAL | CandidateSetBuilder | Sources candidates from aliases/regex/TranslitNormalizer, vector top-K (namespace="entity"), and OFFICIAL/TRUSTED web-seed | Balances precision/recall while capping cost | Web-seed optional; failures ignored (chain continues)
RETRIEVAL | RetrievalHandlerPostProcessor | Wraps all RetrievalHandler beans so EntityDisambiguationHandler always runs first | No edits to existing chain; pass-through on errors | Spring BeanPostProcessor

Reranking — ADD

RERANK | DisambiguationScorer | Final score = α·cosine + β·authority + γ·locale + δ·domain + ε·tokenOverlap + ζ·priorSynergy | Combines semantic fit with meta priors | Defaults: α=0.55, β=0.20, γ=0.10, δ=0.10, ε=0.05, ζ=0–0.05

Context & Prompt — ADD

PROMPT | Entity Disambiguation section | Inject “### ENTITY DISAMBIGUATION” via PromptBuilder only; no ad-hoc string concat | Keeps templates testable and safe | Limit top-3 candidates; ≤240 chars per blurb; trim on token pressure

Generation — ADD

GENERATION | Low-confidence MOE escalation | Escalate once to high-tier model when confidence < 0.70 or intent ∈ {FACT_CHECK, HIGH_RISK} | Stabilizes high-stakes answers | Logged with reason; single-turn escalation only

Verification & Sanitization — ADD

VERIFY | Gate on resolution | If resolvedEntity missing or contradicts context, block generation via EvidenceGate and return safe fallback with SmartFallback suggestions | Avoids hallucinated entities | NamedEntityValidator passes only after resolution

Session & Diagnostics — ADD

SESSION | Disambiguation metrics | Counters: disambig.auto, disambig.user, disambig.abort, alias.upsert.count | Operability & QA | Exported via diagnostics endpoint
SESSION | Retrieval span tags | Record {top1, top2, margin, decidedBy, candidateCount, confidence} in disambiguation span | Observability for tuning | Add namespace, embedding_model_id, dim, topK as tags

Configuration & Environment — ADD

CONFIG | Disambiguation keys | abandonware.disambiguation.enabled, threshold, margin, topK, authority-weight, locale-weight, domain-weight, prior-synergy-weight, web-seed-enabled | Runtime tuning without redeploy | Defaults match CHANGELOG 1.3.0
CONFIG | Entity vector namespace | abandonware.vector.entity.embedding-model-id, dim, create-if-missing=true, strict-dimension=true | Prevents embedding shape drift | Keep entity vectors separate from document vectors

Knowledge Base — ADD

KB | DomainEntity / EntityAlias / EntityFeature | Canonical entities, aliases and features stored as JPA entities | Decouples domain knowledge from code | Repositories: DomainEntityRepository, EntityAliasRepository, EntityFeatureRepository

User Interface & Frontend — ADD

UI | SSE: DISAMBIGUATION | One modal per turn {query, candidates[{id,name,kind,blurb,country,snippets[]}], timeoutSec}; POST selection to resolver | Human-in-the-loop on low margins | Default timeout 12s; on timeout abort and propose SmartFallback

Change Log & Enhancements — ADD

CHANGE | Generalized Entity Disambiguation (pre-chain) | Adds pre-handler, scorer, prompt section, SSE clarify, and diagnostics; supports ambiguous unknowns (e.g., “DW Akademie”, “Hanwha Veda/Beda”) | Safer routing and fewer false resolutions | Config-gated; backward compatible
ixes 🐛
Resolved Application Startup Failure: Fixed a critical error preventing the application from starting. The SecurityFilterChain bean conflict, caused by duplicate definitions in AppSecurityConfig.java and CustomSecurityConfig.java, was resolved by consolidating all security configurations into AppSecurityConfig.java and removing the redundant CustomSecurityConfig.java file.

Addressed 14 Compilation Errors: Resolved all cannot find symbol and package does not exist errors. The root cause was identified as missing class files and incorrect package references. The fix was implemented by creating stub classes and interfaces for the missing components to satisfy the compiler and correct invalid import statements across the project. This included generating stubs for:

com.example.lms.client.GeminiClient

com.example.lms.genshin.GenshinElementLexicon

com.example.lms.service.search.SearchDisambiguation

com.example.lms.service.verification.NamedEntityValidator

com.example.lms.service.help.ContextHelpService

Features ✨
This release implements the four core MVP functionalities:

Read-Only Knowledge Graph (KG) Handler:

A new KGHandler has been integrated into the HybridRetriever chain.

This handler performs read-only lookups of entities and relationships from the GraphStore.

In case of failure, it returns an empty result with a warning, ensuring the retrieval chain remains stable and does not halt execution. Data creation and inference are out of scope for this MVP.

Sequential Strategy Bandit:

The StrategySelectorService is now implemented to dynamically determine the execution order of retriever handlers.

For the MVP, this service uses a simple, rule-based selectPlan method. It adjusts the handler sequence (e.g., [WEB, VECTOR, KG]) based on simple query features like length.

Complex reinforcement learning, parallel execution, and hyperparameter tuning are deferred to future releases.

Basic Generative UI (UI-DSL):

The GenerativeUiService has been implemented to render LLM-generated JSON into safe HTML.

This initial version supports two UI-DSL types: table and card.

The service correctly parses these JSON structures from the LLM and streams the resulting HTML to the client via Server-Sent Events (SSE).

Next Steps & Future Enhancements 🚀
Building on this stable MVP, the following features are proposed to evolve the system towards an intelligent, self-learning agent:

Query Understanding Agent: Entity Disambiguation 🕵️

To address ambiguous user queries (e.g., "Vader" instead of "Veda"), an Entity Disambiguation Handler will be added to the start of the retrieval pipeline.

How it will work:

Candidate Generation: The system will use aliases, the entity DB, and vector similarity to generate a list of potential candidates (e.g., "Veda," "Vader," "Veda Bread").

Scoring & Resolution: Candidates will be scored based on contextual relevance. If a top candidate emerges with high confidence, it will be automatically selected. Otherwise, the user will be prompted with a clarifying question (e.g., "Which 'Vader' are you looking for?").

Expected Impact: Drastically improves search accuracy and enhances user experience by intelligently interpreting ambiguous inputs.

Autonomous Knowledge Graph Growth & Inference 🧠

The Knowledge Graph will be upgraded from a read-only component to a dynamic, self-growing knowledge base.

Key Features:

Automated Knowledge Extraction: An AutonomousKGConstructor will automatically extract knowledge triples (subject, relation, object) from new documents.

Cross-Validation & Confidence: New knowledge will be promoted to "verified fact" only after being corroborated by multiple trusted sources, increasing its confidence score.

Relational Inference: The system will infer new relationships (e.g., if A is part of B, and B is part of C, then A is part of C).

Knowledge Decay: Confidence scores for outdated or conflicting information will decay over time, ensuring the KG remains current.

Expected Impact: Creates a perpetually learning system that can provide deeper, more insightful answers through inference.

Intelligent Strategy Bandit 📈

The rule-based StrategySelectorService will be evolved into a true reinforcement learning agent that optimizes the RAG pipeline.

Key Features:

Reward System: User feedback (likes/dislikes), fact-checking results, and response times will be used to calculate a reward score.

Parallel Execution & Fusion: The bandit will learn to execute handlers (Web, Vector, KG) in parallel and intelligently fuse the results using techniques like Reciprocal Rank Fusion (RRF) for faster, richer context.

Automated Hyperparameter Tuning: A DynamicHyperparameterTuner will automatically adjust parameters like top-k and min_score based on performance.

Expected Impact: Enables the system to autonomously discover the most efficient retrieval strategy for any given query type, continuously improving performance over time.
Build — ADDPatch Notes: MVP Implementation and Critical Fixes
This update implements the core MVP requirements, including dynamic retrieval strategies, knowledge graph integration, and a generative UI service. It also resolves critical startup errors and cleans up the codebase by addressing missing classes and incorrect package structures.

Features & Enhancements
Unified Security Configuration: To resolve the duplicate SecurityFilterChain bean error, the bean definition in CustomSecurityConfig has been disabled. All security settings have been consolidated into AppSecurityConfig for a single, unified configuration.

Read-Only Knowledge Graph (KG) Handler: Implemented KgHandler to enable knowledge graph lookups within the HybridRetriever chain. The handler is designed to be resilient, catching exceptions to prevent chain interruptions while maintaining session isolation. It has been registered in RetrieverChainConfig to execute as part of the retrieval process.

Generative UI Service: Added GenerativeUiService and its implementation, DefaultGenerativeUiService. This service transforms JSON-based UI definitions generated by the LLM into safe HTML, supporting table and card components.

Dynamic Strategy Selection (Bandit): The existing StrategySelectorService has been enhanced with logic to dynamically determine the execution order of the WEB, VECTOR, and KG handlers. The selection is based on query characteristics such as length and domain-specific attributes.

Fixes & Refactoring
Missing Class & Package Cleanup:

Created stub implementations for missing classes, including GeminiClient, NamedEntityValidator, and ContextHelpService, to resolve compilation errors.

Relocated GenshinElementLexicon to its correct package and replaced other misplaced classes with wrappers where appropriate.

Corrected invalid comments and removed a duplicate package declaration in the AdminInitializer file.네, 알겠습니다. Git 레포지토리 패치 노트에 바로 사용할 수 있도록, 제공해주신 내용을 명확하고 전문적인 영문으로 작성해 드리겠습니다.

Fix: Resolve Compilation Failures by Adding Missing Role Enum
This patch addresses a critical build failure (cannot find symbol, package does not exist) caused by a missing Role definition.

Changes
Added com.example.lms.domain.Role Enum:

A new enum, Role, has been introduced in the com.example.lms.domain package.

It defines four essential user roles required by the application: ADMIN, USER, PROFESSOR, and STUDENT.

Impact
Resolves Compilation Errors: The addition of the Role enum resolves all cannot find symbol errors that were occurring in files referencing user roles, most notably within AdminInitializer and Spring Security configurations.

No Unnecessary Stubs Created: Analysis confirmed that other classes referenced in the error log (e.g., GeminiClient, NamedEntityValidator, KgHandler) were already present in the codebase. The root cause of the build failure was isolated to the missing Role enum, which this patch specifically corrects.

With this change, the project now compiles successfully, allowing for further development and deployment.
Patch Notes: RAG Pipeline Hardening & Amplification
This major update implements a comprehensive, three-phase amplification strategy to significantly enhance the precision, robustness, and cost-efficiency of the RAG pipeline. The core of this patch is the introduction of an advanced Entity Disambiguation pre-handler and the deep integration of its output throughout the retrieval, reranking, and generation stages.

Hard Gates (Non-Negotiable Constraints)
LangChain4j Version Purity: The build will fail if any dev.langchain4j:* dependency other than version 1.0.1 is detected.

Centralized Prompt Management: All LLM prompts must be generated exclusively via PromptBuilder.build(PromptContext). Direct string concatenation within services like ChatService is strictly prohibited.

Fault-Tolerant Chain: Individual handler failures within the retrieval chain must not propagate exceptions. Instead, they will be merged as partial results, ensuring the chain always completes.

Phase 1: Query Understanding Amplification 🧠
This phase focuses on robustly resolving ambiguity at the very beginning of the pipeline.

A1: Hybrid Candidate Generation & Context-Aware Fusion

New Components: CandidateGenerator (for lexical, fuzzy, and semantic matches), RankFusionService (using RRF), and ContextBooster.

Integration: A new EntityDisambiguationHandler is inserted as the first step after the MemoryHandler. It follows an internal pipeline: generate candidates → fuse with RRF (k≈60) → apply contextual boosts/penalties → pass to a DisambiguationDecisionPolicy.

Uncertainty Handling: If the confidence margin between the top two candidates is below a configurable threshold (τ), a one-time "Self-Ask Clarify" action is triggered via SSE to ask the user for input.

A2: Dense-to-Cross-Encoder Reranking

New Component: A DisambiguationReranker utilizing a cross-encoder model.

Integration: The final score for a disambiguation candidate is a normalized blend of the RRF score and the cross-encoder's semantic similarity score (final = α·RRF + (1−α)·s_ce).

Fallback: If the cross-encoder fails or times out, the system gracefully falls back to using the RRF score alone.

A3: Uncertainty Gate (Ask/Abstain Policy)

Logic: The system calculates an uncertainty score based on the entropy of candidate probabilities and the confidence margin. If this score exceeds a threshold (θ), it first attempts to clarify with the user once. If uncertainty remains high, it will abstain from answering and return a "Could not resolve" message.

Observability: The DecisionGate exposes the reasoning and metrics (scores, margin) via SSE trace events.

Phase 2: Intelligent Retrieval & Reranking Amplification 📈
This phase leverages the resolved entities and intents from Phase 1 to dramatically improve search precision.

B1: CognitiveState-Aware SmartQueryPlanner

Enhancement: The SmartQueryPlanner is now driven by the CognitiveState produced in Phase 1. It uses templated strategies based on user intent.

Example: For a COMPARATIVE_ANALYSIS intent, it will automatically generate distinct queries for each entity (Query for A, Query for B) and a comparative query (Query for A vs. B synergy), which are then processed by the HybridRetriever.

B2: Multi-Channel Fusion & Final Rerank

Integration: Before the final reranking step, the RankFusionService now uses RRF to merge the ranked lists from all retrieval channels (Web, Vector, Memory).

Final Pass: Only the top-K candidates from this fused list are sent to the CrossEncoderReranker for the final, computationally expensive scoring.

B3: Authority & Freshness Weighting

Heuristics: The fused score is now modulated by a weighted combination of source authority and content freshness: score' = score_fused × (a·w_authority + (1−a)·w_fresh).

Configuration: Authority tiers and decay rates are fully configurable per domain.

Phase 3: Dynamic Generation & Autonomous Learning Amplification 🤖
This phase optimizes model selection and enables the system to learn from its failures.

C1: Cost-Aware Mixture of Experts (MoE) Routing

Logic: The ModelRouter now implements a cost-aware escalation policy. The high-tier model is selected only if query complexity, uncertainty, or business impact exceeds configured thresholds. Otherwise, the base model is used.

Implementation: High-tier and base-tier models are explicitly injected using Spring's @Qualifier to avoid ambiguity with @Primary.

C2: Instantaneous Learning Trigger on Failure

Event-Driven Learning: When the SmartFallbackService determines an answer cannot be provided (NO_ANSWER or LOW_EVIDENCE), it now fires an asynchronous event to the AutonomousExplorationService.

Throttling: This immediate learning trigger is rate-limited by the FreeTierApiThrottleService to manage costs, with the actual knowledge ingestion handled by the existing curation scheduler.

C3: Evidence-Weighted Decoding

Enhancement: The generation process now maintains a mapping between generated sentences and the evidence snippets that support them. Sentences without strong evidence are flagged as "speculative" or suppressed, ensuring answers are grounded in retrieved facts.

Observability & Diagnostics
New SSE Events: Added DISAMBIGUATION, RRF, RERANK, and MOE_ROUTE events, each including a reason field and relevant metrics.

New Metrics: Tracking disambiguation outcomes (auto/user/abort) and key scores (top1, top2, margin, confidence).

Configuration
New configuration keys have been added under abandonware.disambiguation.*, abandonware.vector.entity.*, and router.moe.* with sane defaults to control all new features. All features are toggleable via *.enabled flags for safe rollback.
이 패치에는 엔티티 식별 모듈(CandidateSetBuilder, DisambiguationScorer, DisambiguationDecisionPolicy, EntityDisambiguationHandler)이 새로 추가되었고, 검색 체인을 수정해 엔티티 식별을 선행하도록 구성했습니다. 또한 application.yml에 관련 설정을 추가하고 변경사항을 정리한 CHANGELOG, DIFF_SUMMARY, TEST_REPORT 문서를 작성했습니다.
BUILD | VersionPurityGate (CI) | Hard-fail on mixed dev.langchain4j lines; report only conflicting coordinates | Deterministic builds | BOM remains pinned to 1.0.1
Changelog — src53 → src53core Upgrade

Date: 2025-08-19 (KST)
Scope: GPT-based Web Search plugin (Light/Deep/Auto), adaptive retrieval chain, UI controls, CI version-purity gate, docs/tests

TL;DR

Introduces GPT Web Search with three modes: LIGHT, DEEP, AUTO (hybrid gating).

Adds AdaptiveWebSearchHandler before legacy Web/Vector stages + WEB_ADAPTIVE RRF channel.

Enforces LangChain4j=1.0.1 purity (CI fails on mixed 0.2.x).

Centralizes prompts via PromptBuilder.build(PromptContext) (string concat in ChatService is forbidden).

Extends DTO/Controller/UI to expose search controls (mode, providers, official-only).

Ships tests, CI scripts, sample configs, and docs.

Packaging target for external delivery: src53core.zip.

Highlights
New

GPT Web Search plugin (module: gptsearch/*)

Providers: BING, TAVILY, GOOGLECSE, SERPAPI, MOCK (fallback if keys absent).

SearchMode: AUTO, OFF, FORCE_LIGHT, FORCE_DEEP.

Decision gate: SearchDecisionService combines rules + LLM tool-signal to decide shouldSearch, depth (LIGHT/DEEP), providers, topK, reason.

Evidence injection: PromptBuilder adds ### WEB EVIDENCE with {title|host|snippet|source}, authority & freshness-weighted; token-pressure reduces to one snippet/doc.

Caching & resilience: Caffeine session cache; HTTP client with timeout, 429/5xx exponential backoff (≤3), per-minute/daily quotas.

AdaptiveWebSearchHandler (precedes legacy Web/Vector)

Runs providers in parallel → RRF (pre) → lightweight ranker → optional Cross-Encoder rerank.

Writes results to SearchContext as channel=WEB_ADAPTIVE.

Fail-soft: exceptions are converted to partial warnings; chain continues.

RRF fusion update

Adds WEB_ADAPTIVE channel into existing fusion with authority × freshness multipliers (coeffs in yml).

Prompting policy

All prompts must be built via PromptBuilder.build(PromptContext); no string concatenation in ChatService.

Model routing (MoE)

Use @Qualifier("high") / @Qualifier("mini"); disallow @Primary.

One-shot escalation rule: if complexity=HIGH OR uncertainty≥0.70 OR intent ∈ {FACT_CHECK, HIGH_RISK, COMPARATIVE}, escalate once to high.

DTO / Controller / SSE

ChatRequestDto adds:

SearchMode searchMode (default AUTO)

boolean officialSourcesOnly

List<String> webProviders

Integer webTopK

ChatApiController interprets searchMode independently of retrieval on/off.

SSE events extended:
SEARCH_DECISION(reason, mode, providers),
SEARCH_PROGRESS(stage),
SEARCH_RESULT(count, fusedTopK),
MOE_ROUTE(reason).

UI (HTML/JS)

Adds controls: Search Mode (AUTO/OFF/LIGHT/DEEP), Official sources only, Provider multiselect.

Renders search decision/results in a neutral card (separate from answer bubbles) to prevent badge/button duplication.

Persists user choices in localStorage; fixes initial session duplicate controls.

Security & policy

Optional server proxy for external URLs; blocked domains/keywords configurable.

Logs: PII stored as length/hash only; URLs log host only; queries hashed.

CI: LangChain4j version purity

Enforces dev.langchain4j:* == 1.0.1 across build (including gradle.lockfile and version catalog).

If any 0.2.x artifact is detected, CI fails and prints conflict coordinates only.

Docs & tests

docs/CHANGELOG.md, DIFF_SUMMARY.md, TEST_REPORT.md, gptsearch-ops.md

tests/unit, it, smoke including decision/cache/escalation checks.

ci/VersionPurityCheck scripts + logs.

Chain & Behavior Changes

Chain order (backwards-compatible):

memory → self-ask (gated) → analyse (gated) → adaptive-web (NEW) → web → vector → repair


If adaptive-web succeeds, the legacy web stage may be skipped; reason logged to diagnostics.

RRF formula

score' = RRF × authority × freshness (coefficients configurable).

Configuration (samples)
# GPT Search
abandonware.gptsearch.enabled: true
abandonware.gptsearch.mode.default: AUTO        # AUTO|OFF|FORCE_LIGHT|FORCE_DEEP
abandonware.gptsearch.topK: 6
abandonware.gptsearch.providers: [BING, TAVILY]
abandonware.gptsearch.rrf.k: 60
abandonware.gptsearch.authority-weights:
  OFFICIAL: 1.0
  TRUSTED: 0.85
  COMMUNITY: 0.5
  UNVERIFIED: 0.2
abandonware.gptsearch.freshness.halfLifeDays: 14
abandonware.gptsearch.official-only: false

# Provider keys (via env/secret)
provider.bing.apiKey: ${BING_API_KEY:}
provider.tavily.apiKey: ${TAVILY_API_KEY:}
provider.googlecse.cx: ${GOOGLE_CSE_CX:}
provider.googlecse.apiKey: ${GOOGLE_CSE_KEY:}

# MoE routing
router.moe.high: <high-model-name>
router.moe.mini: <mini-model-name>
router.allow-header-override: false

# Build guard
build.guard.langchain4j.strictVersion: "1.0.1"

Packaging Layout (deliverable: src53core.zip)
core/
  query/, prompt/, router/, verify/, diagnostics/
  retrieval/chain/, retrieval/handler/, rerank/
gptsearch/
  web/          # adapters/services/handler/cache
  client/       # shared HTTP client, keys/quotas/retries
  decision/     # SearchDecision gate (rules+LLM)
  dto/          # request/response, provider/mode/filters
integration/
  handlers/     # AdaptiveWebSearchHandler, RankFusion wiring
  controller/   # ChatApiController + SSE events
  web/          # chat-ui.html, chat.js
config/
  application.yml.sample
  application-ultra.properties
tests/
  unit/, it/, smoke/
docs/
  CHANGELOG.md, DIFF_SUMMARY.md, TEST_REPORT.md, gptsearch-ops.md
ci/
  VersionPurityCheck/

Acceptance Criteria

Version Purity: any 0.2.x LangChain4j detection → CI fails with conflict coordinates.

AUTO (lightweight): simple queries trigger SEARCH_DECISION mode=LIGHT, ≤1 web call, quality parity.

DEEP (reinforced): comparative/fact-check queries show Self-Ask decomposition, pass Claim/FactVerifier, one MoE escalation.

Hybrid switching: within one session, simple→hard queries log LIGHT→DEEP switch.

RRF: WEB_ADAPTIVE contributes to fused Top-K.

UI: toggles & provider pickers populate DTO; SSE cards render without duplicate badges/buttons.

Fail-soft: provider failures logged as partial warnings; chain continues; diagnostics aggregates.

Breaking/Strict Policies

Prompt centralization: any prompt built outside PromptBuilder.build(PromptContext) is non-compliant.

Qualifier-only MoE injection: @Primary is prohibited for model beans.

Exception handling: retrieval handlers must not propagate; return partials + warnings.

Migration Checklist (minimal changes)

Register AdaptiveWebSearchHandler and insert before legacy Web handler.

Add WEB_ADAPTIVE to ReciprocalRankFuser.

Extend PromptBuilder with ### WEB EVIDENCE.

Implement SearchDecisionService (rules + LLM tool signal).

Wire WebSearchProvider implementations & priorities.

Extend ChatRequestDto & controller mapping.

Update chat-ui.html / chat.js (toggles, SSE cards, no bubble-class for traces).

Centralize authority/freshness weights in yml.

Enable CI VersionPurityCheck.

Notes & Guardrails

When quotas hit ≥80%, force LIGHT and disable Cross-Encoder rerank.

officialSourcesOnly=true lifts authority floor to ≥0.8.

Reuse SearchContext across immediate follow-ups to avoid duplicate calls.

Korean morphology normalization in AnalyzeHandler is reused for search query hygiene.

Known Limitations

MOCK provider is for tests only (no crawling).

Cross-Encoder rerank is disabled under heavy token pressure or quota throttling.

How to Verify Locally

Set provider keys (or rely on MOCK).

Start app, toggle AUTO/LIGHT/DEEP in UI; watch SSE: SEARCH_DECISION/RESULT/MOE_ROUTE.

Run tests/smoke to validate decision gating, caching, escalation.

Trigger CI to confirm LangChain4j=1.0.1 purity.

Deliverable

src53core.zip — full code, tests, docs, sample configs, and CI logs as described above.

This upgrade keeps the legacy HybridRetriever order intact, while adding an adaptive web layer, strict prompt/model policies, and observable, fail-soft behavior suitable for production.
Changelog — src50 → src50core (UI-inclusive)

Date: 2025-08-19 (KST)
Scope: GPT API File Search + Images end-to-end, Hybrid RAG integration, PromptBuilder/MoE policies, chat-ui.html & chat.js migration, CI version-purity gate.

TL;DR

Adds GPT API File Search and Images (generate/edit/variation) with full backend + UI wiring.

Keeps legacy HybridRetriever order; inserts FileSearchHandler and adds FILE channel to RRF.

Enforces LangChain4j=1.0.1 version purity (CI fails on 0.2.x contamination).

Centralizes prompts via PromptBuilder.build(PromptContext); no string concatenation in ChatService.

Expands SSE events and UI controls (toggles, image panel, trace & gallery panes).

MoE one-shot escalation removes “lower-model lock-in” under risk/uncertainty/multimodal intents.

Added

GPT API modules (gptapi/*)

File Search: GptFileSearchClient/Service (indexing, async chunk upload, MIME→text extraction ready), FileSearchHandler (fail-soft), FileSearchFusionPolicy.

Images: GptImageClient + ImageGenerationService (generate/edit/variation, base64/URL intake, safe-mode checks).

Chain updates

Entry: HybridRetriever retains order; FileSearchHandler inserted before Web; failures return partial results.

RRF fusion adds FILE channel; shared authority/freshness weights.

Prompting

New sections: ### FILES (title|path|snippet|source), ### IMAGE TASK (mode/prompt/size/mask).
수정 사항 요약:

RetrievalOrderService가 질의에 따라 Web, Vector, KG 채널의 순서를 동적으로 결정하도록 개선했습니다.

ModelRouter가 모델 선택 시 MOE_ROUTE 이벤트를 기록(또는 SSE 발행)하도록 변경했습니다.

애플리케이션 시작 시 중요한 설정 키의 존재 여부를 DEBUG 로그에 출력하는 ConfigKeysLogger 컴포넌트를 추가했습니다.

SSE 이벤트 발행을 처리하는 SseEventPublisher를 추가했습니다.

추가적인 검토가 필요하거나 다른 요청 사항이 있으면 알려주세요.
Model routing (MoE)

@Qualifier("high") / @Qualifier("mini"); @Primary disallowed.

Escalate once when complexity=HIGH OR uncertainty≥0.70 OR intent ∈ {FACT_CHECK, HIGH_RISK, MULTIMODAL_IMAGE}; SSE MOE_ROUTE emits reason.

Controller/DTO/SSE

ChatRequestDto: boolean fileSearch, ImageTask imageTask{mode,prompt,size,maskBase64?}.

ChatApiController: toggles FILE channel; triggers image flow; CSRF via CookieCsrfTokenRepository.withHttpOnlyFalse().

SSE events: trace, filesearch.trace, verification, DISAMBIGUATION, RRF, RERANK, MOE_ROUTE, image.started, image.done, optional image.progress.

Frontend (UI)

chat-ui.html:

Controls: “Use File Search” (#optFileSearch).

Image panel (collapsible): #imgMode, #imgPrompt, #imgMaskFile, #imgSize.

Upload: #fileUpload[multiple].

Neutral trace container: #tracePane (not an assistant bubble).

Image gallery: #imagePane (thumbnails → original).

Accessibility: all buttons type="button", labels for, aria-live="polite".

chat.js:
0.1 단일 버전으로 고정하고 실행 시 버전 순도를 검사합니다.

RetrieverChainConfig의 @Primary 어노테이션을 제거하여 명시적 주입을 강제했습니다.

VectorDbHandler는 실패 시 체인을 중단하지 않고 경고를 기록한 뒤 항상 다음 핸들러로 진행하도록 변경했습니다.

EvidenceGate에 coverageScore 메서드를 추가하고, 기존 hasSufficientCoverage 메서드들이 이를 활용하도록 리팩터링했습니다.

EntityDisambiguationHandler는 confidence threshold를 설정 값에 따라 판단하여 충분히 높은 확신이 있을 때만 질의를 재작성합니다.

OpenAiChatModel을 사용하는 mini/high 모델 빈을 제공하는 ModelConfig를 추가하고, ModelRouter에 복합 RouteSignal을 도입해 복잡도, 불확실성, 토큰 수 등을 기반으로 모델을 선택하도록 확장했습니다.

테스트와 문서 파일을 추가하여 설정 키 유지 및 향후 LLM 호출 예산 테스트를 위한 기반을 마련했습니다.
Request serialization: fileSearch, imageTask{mode,prompt,size,maskBase64?}; toBase64(file) util; chunk upload support.

SSE routing: trace/filesearch.trace → #tracePane; image.* → #imagePane; MOE_ROUTE badge; verification icons.

Duplicate-UI fix: reuse initial loader bubble; prevent duplicate recommendation/model badges.

State persistence: localStorage for fileSearch, imgMode, imgSize.

Security: client-side keyword guard; inject X-CSRF-TOKEN on all fetches.

Changed

Fail-soft chain policy: handler exceptions become partial warnings; chain continues.

Authority/Freshness weights shared across Web & File channels (YAML).

Diagnostics: session-scoped keys; PII logged as length/hash only; URLs log host only.

Security & Resilience

All GPT API clients: timeout + 429/5xx exponential backoff (≤3); RPM/RPD quotas.

File paths/metadata are session-scoped; external URLs optionally proxied.

Image tasks enforce safety categories (blocked on client & server).

Configuration (samples)
# GPT API (common)
gptapi.base-url: <redacted>
gptapi.api-key: ${GPT_API_KEY}
gptapi.timeout-ms: 30000
gptapi.retry.max: 3
gptapi.quota.rpm: 60
gptapi.quota.rpd: 1000

# File Search
abandonware.gptapi.filesearch.enabled: true
abandonware.gptapi.filesearch.topK: 6
abandonware.gptapi.filesearch.rrf.k: 60
abandonware.gptapi.filesearch.authority-weights:
  OFFICIAL: 1.0
  PRIVATE: 0.8
  COMMUNITY: 0.45
  UNVERIFIED: 0.2

# Images
abandonware.gptapi.images.enabled: true
abandonware.gptapi.images.default-size: 1024

# Global RAG/Rerank
abandonware.rerank.rrf.k: 60
abandonware.disambiguation.enabled: true
abandonware.verifier.enabled: true

# MoE
router.moe.high: <high-model-name>
router.moe.mini: <mini-model-name>
router.allow-header-override: false

# Build guard
build.guard.langchain4j.strictVersion: "1.0.1"

Packaging (deliverable: src50core.zip)
core/
  query/, prompt/, router/, verify/, diagnostics/
  retrieval/chain/, retrieval/handler/, rerank/
gptapi/
  filesearch/, images/, client/
integration/
  handlers/, dto/, web/        # Controller + SSE
frontend/
  chat-ui.html, js/chat.js
config/
  application.yml.sample, application-ultra.properties
tests/
  unit/, it/, smoke/ (+ comparison metrics)
docs/
  CHANGELOG.md, DIFF_SUMMARY.md, TEST_REPORT.md, gptapi-ops.md
ci/
  VersionPurityCheck/ (conflict coordinates only on failure)

Acceptance Criteria

Version purity: any LangChain4j 0.2.x detection → CI fail with conflict coordinates only.

FileSearch fusion: FILE channel contributes additional snippets; visible in RRF logs/metrics.

Images: one successful imageTask end-to-end; SSE image.started→image.done(url); gallery renders.

UI toggles: #optFileSearch, imgMode, imgSize reflect in payload and persist via localStorage.

MoE: escalation on uncertainty/high-risk/multimodal; SSE MOE_ROUTE shows reason.

Fail-soft: forced exceptions in FileSearch/Images still yield a safe response; diagnostics aggregates partials.

No duplicates: first-message recommendation/model badges render once (snapshot/manual check).

Migration Checklist (minimal touch)

Register FileSearchHandler (pre-Web) and enable FILE in ReciprocalRankFuser.

Extend PromptBuilder with ### FILES & ### IMAGE TASK.

Implement GptFileSearchClient/Service and GptImageClient/ImageGenerationService.

Update DTO/Controller for fileSearch & imageTask; wire SSE events.

Migrate chat-ui.html (control panel, trace/image panes) & chat.js (serialization/SSE/dup-guard/localStorage).

Share authority/freshness coefficients in YAML across Web & File channels.

Verify CSRF token propagation.

Run CI VersionPurityCheck.

Notes (Bias mitigation)

Maintains SynergyStat decay in RRF to avoid recency over-dominance.

Removes “lower-model bias”: on uncertainty/comparative/fact-check/multimodal image tasks, force single escalation to the high-tier model.
