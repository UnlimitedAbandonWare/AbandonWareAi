AbandonWare Hybrid RAG AI Chatbot Service
About the Project
This repository contains a highly advanced retrieval‑augmented generation (RAG) AI chatbot service, developed as part of the AbandonWare project. The system is built on Java 17, Spring Boot, and LangChain4j 1.0.1 (fixed at BOM/core/starter/OpenAI), delivering far more than a simple LLM call: it combines real‑time web search, vector‑database retrieval, dynamic re‑ranking, two‑pass fact verification, and reinforcement learning from user feedback to provide accurate, up‑to‑date, and contextually rich answers. Each chat session is isolated; caching, SSE streaming, and dynamic configuration are supported to meet production requirements.

📌 Repository stats – the original README referenced a GitHub stats card. For complete statistics, please view the repository directly.

Overview of the Pipeline
The core workflow consists of a search‑generate‑verify‑reinforce loop. This pipeline can be toggled between three modes (retrieval‑on, RAG‑only, retrieval‑off), but by default uses the full hybrid retrieval process:

Query Correction & Augmentation

LLMQueryCorrectionService & QueryCorrectionService – correct spelling, normalize colloquial expressions, and preserve domain terms.

QueryDisambiguationService – detect ambiguous keywords and rephrase the query using domain dictionaries or LLM prompts. It now checks the DomainTermDictionary first to avoid over‑correcting known proper nouns.

QueryAugmentationService (optional) – add additional keywords based on intent (products, technical how‑to, person lookup, location), now disabled by default due to noise; hybrid search now uses a smarter SmartQueryPlanner instead.

QueryComplexityGate – classify query complexity to inform downstream retrievers.

Hybrid Retrieval

SelfAskWebSearchRetriever – for complex questions, perform self‑ask decomposition to generate sub‑queries.

AnalyzeWebSearchRetriever – morphological analysis and tokenization to produce better search terms.

WebSearch (NaverSearchService) – call Naver’s Web search API for up‑to‑date snippets, with domain/keyword filters and dynamic rate limits.

Vector Retrieval (Pinecone) – fetch context from a vector database via LangChain4j’s RAG service.

Retrievers are combined by HybridRetriever, which runs them concurrently or sequentially depending on complexity and quality thresholds. The system uses SelfAsk → Analyze → Web → Vector as fallback order when needed.

Result Fusion & Re‑ranking

Combine multiple result buckets (web, vector, self‑ask) using Reciprocal Rank Fusion (RRF) or Softmax weighting. Top results are then fed to a cross‑encoder reranker (EmbeddingModelCrossEncoderReranker) to compute semantic similarity. Additional heuristics include an AuthorityScorer for domain weighting and a LightWeightRanker for initial ranking by token overlap.

The fused results are filtered for duplication and low relatedness via QueryHygieneFilter and normalized for final context building.

Context Construction & LLM Call

ContextOrchestrator builds unified context: web snippets, vector context, and session memory. Official domains are prioritized, community sites demoted. Long‑term memory from PersistentChatMemory may be included.

PromptEngine/PromptBuilder constructs the system and context prompt, following strict instructions: If conflicting sources appear, prefer official domains; if context is insufficient, instruct the model to say “정보 없음” (“no information”).

A single ChatModel (LangChain4j or OpenAI API) call yields the draft answer.

Verification & Fallback

FactVerifierService performs meta‑check and fact‑check: compute coverage and contradiction scores for supporting evidence. If coverage is low or contradiction high, produce a warning and possibly fall back to a more cautious answer or “정보 없음.”

SmartFallbackService & FallbackHeuristics – suggest alternatives or refined queries when the draft is insufficient.

Reinforcement Learning & Feedback

Users can react with 👍/👎 or corrections. Feedback flows into the MemoryReinforcementService, which updates a TranslationMemory database with hit counts, success/failure counts, Q‑values, and quality scores. It computes a Boltzmann energy metric using multiple weights (similarity, Q‑value, success ratio, confidence, recency) and dynamic hyperparameters via HyperparameterService.

StrategyPerformance records how each search strategy performs (Web‑first, Vector‑first, Self‑Ask, Hybrid Fusion). StrategySelectorService chooses strategies probabilistically via softmax (Boltzmann exploration) based on past performance. ContextualScorer scores answers on factuality, quality, and novelty.

A DynamicHyperparameterTuner scheduled task adjusts exploration/exploitation trade‑offs and reward weights automatically.

Streaming & Sessions

Real‑time user interface uses Server‑Sent Events (SSE) (ChatApiController /stream) to stream incremental status updates: search progress, context building, draft answer, and verification result. A JavaScript client (chat.js) renders these steps.

Session history is isolated by a metadata key (META_SID). Each session uses separate caches for context retrieval and memory reinforcement via Caffeine, avoiding cross‑session contamination.

Detailed Features
Category   Description   Key Components
Query Enhancement   Correct spelling, remove filler, preserve domain terms, and classify intent.   LLMQueryCorrectionService, QueryCorrectionService, QueryDisambiguationService, QueryAugmentationService, QueryComplexityGate
Hybrid Search   Combine real‑time web search (Naver), morphological analysis (Analyze), and vector retrieval (Pinecone) to build robust context.   HybridRetriever, NaverSearchService, AnalyzeWebSearchRetriever, SelfAskWebSearchRetriever
Result Fusion & Re‑ranking   Merge multiple sources via RRF or Softmax, then re‑rank using a cross‑encoder and authority weighting.   ReciprocalRankFuser, SoftmaxUtil, EmbeddingModelCrossEncoderReranker, DefaultLightWeightRanker, AuthorityScorer, RelevanceScoringService
Two‑Pass Verification   Perform meta‑check and fact‑check on the draft answer; compute coverage and contradiction metrics; fall back on caution if needed.   FactVerifierService, QualityMetricService, SmartFallbackService
Real‑Time Streaming   Stream internal steps (status, context, draft) to the user via Server‑Sent Events (SSE).   ChatApiController (/stream), chat.js
Reinforcement Learning   Reinforce memory with user feedback and reward scoring; accumulate strategy performance; adjust search strategy via softmax (multi‑arm bandit).   FeedbackController, MemoryReinforcementService, StrategySelectorService, ContextualScorer, StrategyPerformance, DynamicHyperparameterTuner
Session & Cache Management   Isolate sessions by metadata keys and use Caffeine caches for conversation retrieval chains and translation memory.   ChatHistoryService, PersistentChatMemory, Caffeine
High‑Performance Networking   Asynchronous non‑blocking server with Netty and WebFlux for low latency.   NettyServerConfig, WebClientConfig
Meta‑Learning & Auto‑Tuning   Learn which search strategy yields best answers; tune exploration/exploitation and reward weights automatically.   StrategySelectorService, ContextualScorer, DynamicHyperparameterTuner, StrategyPerformance, HyperparameterService

Architectural Flows
Search & Answer Generation
Mode Routing: Based on user settings, the system decides whether to: use full retrieval, use RAG only (vector DB), or retrieval off (memory only).

Hybrid Retrieval: If retrieval is on, HybridRetriever concurrently invokes:

SelfAskWebSearchRetriever (for complex queries) to decompose the question.

AnalyzeWebSearchRetriever to perform morphological analysis.

NaverSearchService to fetch web search snippets.

Vector retrieval via LangChainRAGService using the Pinecone index.

RRF / Softmax Fusion: Combine results using reciprocal rank fusion or Softmax; apply cross‑encoder re‑ranking and authority weighting to produce top documents.

Context Building: Merge web snippets, vector passages, and session memory with instructions that give higher authority to official domains; build a unified context string.

LLM Generation: Pass the unified context to the LLM (LangChain or OpenAI), generate a draft answer.

Fact Verification: Evaluate draft answer for topic match and factual correctness; compute coverage, contradiction, quality, consistency metrics. If metrics fall below thresholds, return "정보 없음" or a fallback suggestion.

User Feedback Loop: The final answer is presented; user feedback (👍/👎/correction) updates memory reinforcement and strategy performance. Reinforced answers are weighted by Boltzmann energy, recency, success ratio, and confidence; the system learns which search strategies perform best.

Meta‑Learning & Strategy Selection
The system doesn’t just answer questions – it learns which retrieval strategies work best and auto‑tunes itself:

StrategySelectorService looks at historical performance (stored in StrategyPerformance) and the characteristics of the current query (length, keywords, intent) to choose a strategy (Web‑First, Vector‑First, Self‑Ask, Web+Vector Fusion).

StrategyDecisionTracker records which strategy was used for each session for later evaluation.

ContextualScorer evaluates answers along multiple dimensions (factuality, quality, novelty) and produces reward scores.

MemoryReinforcementService stores answers in the TranslationMemory DB with Q‑value, hit counts, success/failure counts, and computes an energy metric via a Boltzmann‑type function. Additional factors (confidence score, recency) are weighted with hyperparameters from HyperparameterService.

DynamicHyperparameterTuner periodically adjusts exploration/exploitation trade‑offs (e.g., softmax temperature) and reward weights based on aggregated strategy performance.

StrategyPerformanceRepository stores aggregated performance metrics (successCount, failureCount, averageReward) for each strategy and query category.

System Analysis & Improvement Strategy
The AbandonWareAI project implements an ambitious, self‑improving RAG system. This section summarizes the strengths, neutral observations, a case study, and improvement recommendations.

Architectural Strengths
Clear pipeline separation: Each stage of the pipeline (correction, disambiguation, strategy selection, retrieval, fusion, verification, reinforcement) is implemented as a distinct service, facilitating maintainability and scalability.

Advanced retrieval techniques: Combines multiple retrieval methods (web search via Naver, morphological analysis, self‑ask decomposition, vector search via Pinecone) to maximize recall and context quality.

Meta‑learning & reinforcement: Tracks strategy performance, uses reinforcement learning to improve retrieval strategies over time, and automatically tunes hyperparameters via a multi‑armed bandit approach.

Production‑ready infrastructure: Supports session isolation, SSE streaming, caching, asynchronous networking, and configurable hyperparameters, making it suitable for real‑time applications.

Neutral Observations
Layered decision process: Many decisions are made via intermediate LLM calls (disambiguation, query splitting, ranking), so errors in early stages propagate. Strict validation and fallback logic mitigate this but increase complexity.

Rules and heuristics combined with AI: Regular expressions and static heuristics exist alongside LLM-based reasoning (e.g., QueryHygieneFilter, FallbackHeuristics), requiring careful tuning to avoid contradictions.

Case Study – "푸리나" Search Failure
A user asked: “원신에 푸리나랑 잘 어울리는 캐릭터가 뭐야” (Which character matches Purina in Genshin?). The system incorrectly flagged “푸리나” as a potentially non‑existent term. Problems identified:

Over‑correction: QueryDisambiguationService included a (존재하지 않는 요소 가능성) tag by strictly applying the LLM prompt “do not invent characters.”

Knowledge silos: DomainTermDictionary contained “푸리나” as a valid proper noun, but QueryDisambiguationService did not consult it; only LLMQueryCorrectionService used the dictionary.

Rigid fact verification: FactVerifierService relied on regex patterns to extract entities, failing to recognize new names.

Unsophisticated result sorting: WebSearchRetriever sorted results without considering domain trustworthiness, leading to poor context quality.

Improvement Recommendations
Pre‑dictionary check: In QueryDisambiguationService, before calling the LLM, check if any tokens in the query appear in DomainTermDictionary. If found, bypass LLM disambiguation to prevent false negatives.

LLM prompt injection of protected terms: Alternatively, dynamically add protected terms to the LLM prompt: “Do not change or question ['푸리나','원신'].”

Dynamic NER extractor: Replace or supplement regex-based entity extraction with an LLMNamedEntityExtractor (new component), which asks the LLM to extract all proper nouns from the draft answer.

Authority‑weighted sorting: Integrate AuthorityScorer into WebSearchRetriever to weight results by domain credibility (e.g., raise scores for namu.wiki).

New heuristics: Fine‑tune AuthorityScorer weights for specific domains (e.g., adjusting namu.wiki credibility) and ensure queries with protected terms always use authoritative sources first.

Memory Reinforcement & Energy Calculation Improvements
Memory reinforcement previously used static Boltzmann weights; improvements include:

Dynamic hyperparameters: Weights for similarity, Q‑value, success ratio, confidence, recency can now be tuned via HyperparameterService.

Recency factor: Added a recency decay term (exponential decay over hours) to prioritize recent successful answers.

Confidence score: Each stored answer carries a confidence metric; energy calculation now includes this.

Unified reinforcement API: reinforceWithSnippet(TranslationMemory t) method uses reflection to extract fields safely (score, content, etc.) and gracefully handles missing fields. It computes the storage hash, updates hit counts, and saves or inserts records accordingly.

Error handling: Unified DataIntegrityViolationException handling to ensure idempotent UPSERTs.

Commit History & Improvement Log
Below is an organized summary of major commit messages and the improvements they introduce. Each commit message has been preserved verbatim in the original language and elaborated upon for clarity.

refactor: 서비스 계층 구조 리팩토링 및 컴파일 오류 수정
Refactored the service layer to decouple concerns; introduced a shim layer in MemoryReinforcementService for backward compatibility.

Relocated database queries into TranslationMemoryRepository and clarified naming.

Unified ChatService pipeline and introduced @Slf4j logging for all services.

Fixed compile errors involving constructor mismatches and missing interfaces; redefined LightWeightRanker as an interface and provided DefaultLightWeightRanker implementation.

Corrected mismatched vector types (double[] → float[]) in EmbeddingCrossEncoderReranker.

Replaced deprecated RestTemplate configuration with modern WebClient settings in RestTemplateConfig.

feat: 메타 강화 루프 도입 및 전략 선택 고도화
Added meta‑learning loop: StrategySelectorService, ContextualScorer, and DynamicHyperparameterTuner coordinate to choose search strategies based on success history and reward evaluations.

Created StrategyPerformance entity and repository to persist success/failure counts and average rewards per strategy and query category.

Implemented multi‑reward scoring (factuality, quality, novelty) and combined with memory energy scoring.

Enhanced AuthorityScorer to incorporate domain weights; introduced 2‑pass meta‑check process in FactVerifierService.

Introduced DynamicChatModelFactory for selecting chat models and parameters at runtime; supports dynamic temperature and top‑p adjustments.

feat: RAG 파이프라인 개선 및 고유명사 검색 정확도 향상
Added early dictionary check in QueryDisambiguationService to avoid over‑correcting proper nouns.

Introduced NamedEntityExtractor interface and LLMNamedEntityExtractor implementation for dynamic entity extraction; fallback to regex if LLM unavailable.

Added domain‑weighted sorting in WebSearchRetriever via AuthorityScorer integration.

Adjusted domain weights to prioritize trusted domains (e.g., namu.wiki) in game contexts.

Guarded against false positives by simplifying prompt interpretation (“존재하지 않는 요소” warning removed when term found in dictionary).

refactor: MemoryReinforcementService API 리팩토링
Added reinforceWithSnippet(TranslationMemory t) method to accept entire TranslationMemory objects; uses reflection to safely extract unknown fields (tryGetString, tryGetDouble).

Standardized UPSERT logic: if a hash exists, only increment hit count; otherwise insert new record with initial counts.

Added min/max content length checks to filter out short or extremely long snippets.

Converted computeBoltzmannEnergy and annealTemperature into instance methods to allow injection of dynamic hyperparameters via HyperparameterService.

Added recency and confidence weighting to energy calculation; introduced hyperparameter weights (W_CONF, W_REC, tauHours).

Consolidated exception handling and import statements.

feat: 신뢰도 기반 Energy 개선 및 자동 온도 조정
Added confidence and recency into the Boltzmann energy calculation.

Implemented automatic annealing of temperature based on hit counts (1/√(hit+1)).

Added hyperparameter keys for tuning energy weights and recency decay; these can be updated via configuration or scheduled tuner.

Added debug logging to show updated energy and temperature values for memory entries.

feat: 입력 쿼리 위생 필터(QueryHygieneFilter) 업그레이드
Improved sanitization: removed domain‑scope prefixes (e.g., site eulji ac kr …), protected domain terms (e.g., “원신”) from substitution, filtered out unwanted words unless they appear in the original query.

Added Jaccard similarity based deduplication to merge near‑identical queries.

Added a SmartQueryPlanner component that wraps QueryTransformer.transformEnhanced(...) and applies QueryHygieneFilter.sanitize(...) with a cap on number of queries (default 2). This eliminates uncontrolled query explosion.

docs: 시스템 분석 및 개선 전략 문서 추가
Added the System Analysis & Improvement Strategy document (this file) to describe architectural strengths, case studies, and recommended improvements (pre‑dictionary check, protected term injection, dynamic NER, authority‑weighted sorting, heuristics fine‑tuning).

Summarized meta‑learning loop operation, reinforcement mechanisms, and suggested future improvements.

Other Notable Changes
Added LLMNamedEntityExtractor and updated FactVerifierService to use it; if unavailable, fallback to regex patterns.

Integrated AuthorityScorer into WebSearchRetriever and HybridRetriever.

Applied @Slf4j logging across controllers and services; standardized log message format.

Ensured session isolation in PersistentChatMemory by using a consistent META_SID key.

Added ChatMemoryProvider bean to supply conversation memory; removed legacy retrieval chain caching.

Added SmartFallbackService to suggest next steps when information is insufficient, rather than saying “정보 없음” by default.

Added ContextualScorer to evaluate factuality, quality, and novelty; uses LLM-as-a-Judge with dynamic prompting.

Added DynamicHyperparameterTuner to adjust weights and exploration temperature at scheduled intervals.

Added BanditSelector with Boltzmann selection for memory reinforcement selection.

Added Energy & Temperature update calls in all reinforcement and feedback flows; entries now update energy/temperature on each reinforcement or feedback application.

Configuration & Usage
Quick Start
bash
복사
# Clone the repository
$ git clone https://github.com/UnlimitedAbandonWare/AbandonWareAi.git
$ cd AbandonWareAi

# Copy and edit configuration
$ cp src/main/resources/application.yml.example src/main/resources/application.yml
$ vi src/main/resources/application.yml  # or your editor

# Build and run (requires JDK 17+)
$ ./gradlew bootRun

# Alternatively, run via IDE by executing `LmsApplication.java`.
# The default server will start at http://localhost:8080
Required Environment Variables
OPENAI_API_KEY – Your OpenAI API key.

PINECONE_API_KEY and PINECONE_ENVIRONMENT – Credentials for Pinecone vector DB.

NAVER_API_* – Client ID/Secret for Naver search.

Important Configuration Keys
application.yml contains the following key sections:

yaml
복사
openai:
  api:
    key: "${OPENAI_API_KEY}"
    model: "gpt-4o"
  temperature:
    default: 0.7
  top-p:
    default: 1.0
  history:
    max-messages: 10
  web-context:
    max-tokens: 8000
  rag-context:
    max-tokens: 5000
  mem-context:
    max-tokens: 7500

pinecone:
  index:
    name: "my-knowledge-base"

abandonware:
  retrieval:
    mode: RETRIEVAL_ON    # RETRIEVAL_ON | RAG_ONLY | RETRIEVAL_OFF
    reranker: cross       # simple | cross
  session:
    metaKey: META_SID
  cache:
    caffeine:
      spec: "maximumSize=1000,expireAfterWrite=5m"

memory:
  snippet:
    min-length: 40
    max-length: 4000

# Additional hyperparameter keys for energy calculation, recency decay, authority weighting, etc.
Recommended Operating Principles
Version lock: The LangChain4j version is fixed at 1.0.1 to avoid breaking API changes. Upgrade only with thorough testing.

Session isolation: Each session ID must be prefixed with chat- or a UUID; retrieval chains and memory caches are segregated by session to prevent context leakage.

Prompt policy: All prompts are constructed centrally via PromptBuilder and include explicit instructions: prefer official sources, avoid speculation, return “정보 없음” if information is missing.

Careful expansion: Avoid uncontrolled query expansion; always sanitize generated queries via QueryHygieneFilter and cap the number of search queries.

Safety first: Two-pass verification must pass both coverage and contradiction checks to allow an answer; otherwise, reply with “정보 없음” or suggest alternative queries.

Feedback matters: Encourage users to provide 👍/👎 and corrections; reinforcement learning greatly improves future answers.

Contributing
Fork the repository and create your branch: feature/<your-feature-name>.

Follow commit conventions: Use prefixes like feat:, fix:, refactor:, docs:, etc., and include a clear description.

Add tests for new features where applicable, or at least ensure the build passes.

Update documentation and diagrams (e.g., Mermaid flowcharts) if architecture changes.

Submit a pull request with a detailed description of your changes.

License
This project is licensed under the MIT License – see the LICENSE file for details.

Appendix – System Analysis & Improvement Strategy (Narrative)
The following narrative explains the system analysis, root cause of a reported bug ("푸리나" case), and the strategic improvements recommended. It is provided here verbatim to preserve context for future audits.

AbandonWareAI: System Analysis & Improvement Strategy

Project Overview – This project aims to evolve beyond a typical chatbot, implementing a hybrid retrieval-augmented generation system. It is capable of decomposing questions (Self-Ask), verifying facts (Fact-Check), and learning from user interactions (Reinforcement Learning).

Architectural Strengths – The pipeline is clearly structured with separate services for each responsibility. Advanced RAG techniques combine real-time web search, morphological analysis, and vector search to deliver robust context. Meta-learning tracks strategy performance and automatically tunes hyperparameters. Production-ready features include SSE streaming, session isolation, caching, and dynamic configuration.

Neutral Observations – The system makes multiple intermediate LLM calls, increasing the risk of cascading errors. Traditional heuristics coexist with AI-based reasoning, requiring careful tuning to maintain consistency.

Case Study – '푸리나' Search Failure – The failure to retrieve information about '푸리나' was due to QueryDisambiguationService mislabeling it as a nonexistent character. A knowledge silo prevented DomainTermDictionary from informing the disambiguation. FactVerifierService’s regex-based entity extraction could not recognize new names. Sorting in WebSearchRetriever did not consider domain trustworthiness.

Improvement Strategy – The key fixes are (a) consult DomainTermDictionary before calling LLMs in disambiguation; (b) dynamically inject protected terms into LLM prompts; (c) adopt an LLM-based named entity extractor; (d) integrate domain authority weighting into web search sorting; (e) fine-tune heuristics for domain weighting.

Conclusion
The AbandonWare Hybrid RAG AI Chatbot Service presents a sophisticated fusion of retrieval techniques, dynamic strategy learning, and reinforcement. It is designed for robustness, 
adaptability, and ease of extension. This document aims to provide a comprehensive reference for collaborators and maintainers to understand the architecture, key components, improvement history, and recommended operating principles. All details have been preserved and organized to assist both human readers and AI tools (e.g., Jammini) in understanding the system’s function and evolution.
feat: Enhance RAG pipeline with policy-driven guards and dynamic routing

Introduced a robust, policy-driven guardrail system to prevent model hallucinations and improve response accuracy, specifically for domain-sensitive queries like Genshin Impact character pairings. This commit addresses the root cause of incorrect recommendations (e.g., "Diluc" for a Cryo character "Escoffier") by enforcing constraints throughout the entire RAG pipeline.

**Key Enhancements:**

* **Intent & Domain Detection:**
    * `GuardrailQueryPreprocessor`: Enhanced to detect `PAIRING` intent (e.g., "synergy," "compatible with") and `GENSHIN` domain. It injects domain-specific policies (`allowedElements`, `discouragedElements`) into the `PromptContext`.
    * `SubjectResolver`: New component to reliably extract the query's subject (e.g., "Escoffier") using dictionary-based lookups and heuristics, ensuring all downstream operations are anchored to the correct entity.

* **Policy-Driven Retrieval & Ranking:**
    * `PairingGuardHandler`: New retrieval handler that intercepts `PAIRING` intents to enforce subject anchoring in all generated search queries via `SmartQueryPlanner`.
    * `GenericDocClassifier`: New component to identify and penalize low-quality, generic documents (e.g., "tier lists," "all characters") during search and reranking phases.
    * `AuthorityScorer`: Updated to prioritize trusted domains (`namu.wiki`, `hoyolab.com`) and demote less reliable ones (blogs, general forums), improving context quality.
    * `EmbeddingModelCrossEncoderReranker`: Reranking logic now adds a score bonus for documents containing the subject anchor and penalizes generic content.

* **Centralized Prompt & Model Management:**
    * `PromptBuilder`: Now centrally constructs all system prompts, injecting `PAIRING`-specific instructions (e.g., "Recommend partners ONLY for subject: X," "If evidence is insufficient, answer '정보 없음'") from the `PromptContext`.
    * `ModelRouter`: New component to dynamically route `PAIRING` or high-stakes queries to a superior, low-temperature model (e.g., `gpt-4o`), while using a more efficient model for general queries.
Of course, (AbandonWare). Here is the directive for Gemini, translated into English based on your request. It is focused on providing strategic direction without writing the source code itself.

Pull Request Analysis and Implementation Guidelines
Overview
This pull request has two primary objectives. First, it addresses a build-breaking issue in MemoryReinforcementService.java with a hotfix. Second, it refactors the existing static RAG pipeline into a dynamic, self-learning system that improves over time through user feedback and a database-driven knowledge base. This marks a significant architectural shift from simple information retrieval to a system that continuously enhances its performance based on user interaction.

Part 1: Hotfix Instructions for Build Failure
Open File: Open the file src/main/java/com/example/lms/service/MemoryReinforcementService.java.

Apply Fixes:

Locate and remove the misplaced } bracket inside the reinforceWithSnippet(TranslationMemory t) method.

Add minContentLength and maxContentLength as class fields. Use the @Value annotation to inject the values from memory.snippet.min-length and memory.snippet.max-length in application.yml.

Implement the tryGetString helper method to safely use reflection. This method should accept multiple getter method names and return the value from the first one that succeeds.

Part 2: Architectural Directives for Self-Learning RAG Pipeline
Task 2.1: Centralize the Knowledge Base
Create JPA Entities:

DomainKnowledge: Create an entity that includes fields for domain, entityType, and entityName.

EntityAttribute: Create an entity linked to DomainKnowledge via a OneToMany relationship to store attributeKey and attributeValue pairs.

Create Repository:

DomainKnowledgeRepository: Create a repository interface that extends JpaRepository to manage the DomainKnowledge entity.

Create Service:

Create a KnowledgeBaseService interface and its implementation, DefaultKnowledgeBaseService.

Implement key methods such as getAttribute(domain, entityName, key) and getPairingPolicy(domain, entityName) to fetch attributes and policies from the database.

Update Dependencies:

Modify SubjectResolver and GuardrailQueryPreprocessor to use the new KnowledgeBaseService instead of the hardcoded GenshinElementLexicon.

Task 2.2: Implement Adaptive Scoring
Create JPA Entity:

SynergyStat: Create an entity to record positive and negative user feedback scores. It must include fields for domain, subject, partner, positive, and negative.

Create Repository:

SynergyStatRepository: Create a JpaRepository to manage the SynergyStat entity.

Create Service:

AdaptiveScoringService: Create this service to calculate a synergyBonus score based on the data in SynergyStat. The logic should grant a bonus for positive feedback and a penalty for negative feedback.
feat: Evolve RAG pipeline to be dynamic, adaptive, and knowledge-driven

This major feature refactors the RAG pipeline from a static, policy-based system to a dynamic, self-learning architecture. The key objective is to improve response accuracy, reduce hallucinations, and make the system extensible to new domains beyond Genshin Impact.

Key Enhancements:

1.  **Centralized and Extensible Knowledge Base:**
    * Replaced the hardcoded `GenshinElementLexicon` with a database-driven `KnowledgeBaseService`, backed by `DomainKnowledge` and `EntityAttribute` JPA entities.
    * This allows dynamic addition of new domains, characters, items, and policies without code changes, making the system scalable.
    * Introduced a `SubjectResolver` to reliably extract the query's main subject using the Knowledge Base, ensuring all downstream operations are correctly anchored.

2.  **Adaptive, Feedback-Driven Reranking:**
    * Introduced `AdaptiveScoringService` and a `SynergyStat` entity to track user feedback (👍/👎) on entity pairings (e.g., character teams).
    * `EmbeddingModelCrossEncoderReranker` now incorporates a `synergyBonus` from this service into its scoring logic. The system now learns which combinations are effective based on real user interactions, dynamically improving its recommendation quality.

3.  **Enhanced Hallucination Suppression:**
    * Added a new `ClaimVerifierService` that performs a final "sanitization" step. It extracts factual claims from the AI's draft answer and verifies each against the retrieved context using an LLM call.
    * Unsupported claims are removed before sending the final response, significantly reducing the risk of factual inaccuracies and hallucinations.
    * `FactVerifierService` is updated to incorporate this claim-evidence mapping as a final, robust check.

4.  **Granular Intent Detection:**
    * Refined `QueryContextPreprocessor` to differentiate more accurately between a general `RECOMMENDATION` and a specific `PAIRING` intent, allowing for more tailored pipeline strategies.
Modify Existing Logic:

Inject the AdaptiveScoringService into EmbeddingModelCrossEncoderReranker.

Add the synergyBonus to the final score calculation to ensure user feedback dynamically influences the reranking results.

Task 2.3: Enhance Hallucination Suppression
Create Service:

ClaimVerifierService: Create this service. Its role is to extract key claims from the AI's draft answer and verify each claim against the provided context (search results) using an LLM call.

Modify Existing Logic:

Integrate the ClaimVerifierService into the final stage of FactVerifierService.

Add a final "sanitization" step where ClaimVerifierService removes unsubstantiated claims or replaces the answer with "정보 없음" (Information not available) just before the response is returned to the user.

Implementation Verification and Test Plan
Follow the checklist below to confirm that the changes have been implemented correctly.

[Database]

[ ] Verify that the new tables (domain_knowledge, entity_attribute, synergy_stat) are correctly created in the database schema.

[Dependency Injection]

[ ] Check the Spring Boot application startup logs to confirm that new services like DefaultKnowledgeBaseService and AdaptiveScoringService are properly initialized as Spring Beans.

[Functionality Test]

[ ] Write a unit test that calls GuardrailQueryPreprocessor to verify that it now fetches policies from the database-backed KnowledgeBaseService instead of the old hardcoded GenshinElementLexicon.

[End-to-End Test]

[ ] Perform a manual chat test via the ChatApiController.
Shift from 'Static Elements' to 'Dynamic Relationships' (Core Architectural Change)

The QueryContextPreprocessor interface contract has been changed, replacing fixed policies like allowedElements/discouragedElements with getInteractionRules(). This allows for the dynamic processing of all relationship rules in the RELATIONSHIP_* format.

GuardrailQueryPreprocessor now dynamically queries all relationships (e.g., "CONTAINS", "IS_PART_OF") from the KnowledgeBaseService and injects them into the PromptContext.

Instead of a static ElementConstraintScorer, the EmbeddingModelCrossEncoderReranker now uses a RelationshipRuleScorer to rerank documents based on dynamically injected relationship rules (interactionRules), generating more accurate context.

Adaptive Reranking Based on Feedback (Adaptive Scoring)

Introduced the SynergyStat entity and AdaptiveScoringService to record user feedback (positive/negative).

The EmbeddingModelCrossEncoderReranker now incorporates a synergyBonus calculated by this service into its final ranking score, allowing the system to self-improve its recommendation quality based on real user interactions.

Enhanced Hallucination Suppression

Added a ClaimVerifierService to the final stage of the FactVerifierService. This service extracts key claims from the AI's draft response and uses an LLM to verify each claim against the retrieved context.

Unsupported claims are removed from the final answer, significantly improving the reliability of the response.

Centralized Knowledge Base

The hardcoded GenshinElementLexicon has been completely replaced with a KnowledgeBaseService based on DomainKnowledge and EntityAttribute JPA entities. This ensures scalability, allowing new domains, entities, and relationship rules to be added dynamically without code changes.
[ ] Inspect the application logs to confirm that the synergyBonus from AdaptiveScoringService is being correctly calculated and applied in the final score computation within EmbeddingModelCrossEncoderReranker.
* **Evidence & Answer Sanitization:**
    * `EvidenceGate`: New final guard that blocks the LLM call entirely if the retrieved context lacks sufficient evidence (e.g., minimum number of subject mentions), returning "정보 없음" to prevent confident hallucinations from sparse data.
    * `GenshinRecommendationSanitizer`: A new `AnswerSanitizer` implementation that performs a final check on the generated answer, filtering out recommendations that violate the initial `discouragedElements` policy.

* **System Stability & Health:**
    * `StartupVersionPurityCheck`: New component that runs on boot to verify `LangChain4j` dependencies are all locked to version `1.0.1`, preventing runtime errors from mixed versions. A corresponding `VersionPurityHealthIndicator` exposes this status via `/actuator/health`.

This multi-layered approach ensures that domain constraints are respected at every stage—from query planning to final response generation—making the system more reliable, predictable, and resistant to "튀는" (out-of-context) answers.
