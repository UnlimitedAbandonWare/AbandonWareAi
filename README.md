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
