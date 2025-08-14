AbandonWare Hybrid RAG AI Chatbot Service: A Domain‑Agnostic Knowledge‑Driven Agent
This README serves as the comprehensive and exhaustive documentation for the AbandonWare Hybrid RAG AI Chatbot Service, transforming the project from a domain‑specific Genshin Impact helper into a general purpose retrieval‑augmented generation (RAG) knowledge agent. This document aggregates all relevant information from multiple sources, explains the architecture, describes every component in detail, provides guidance on how to customize and extend the system, and offers insight into the motivations behind each design choice. The goal of this README is to present a fully self‑contained overview that can be read by engineers, product managers, researchers and AI enthusiasts alike, and to stand as a reference for Jammini or any code reviewer who wants to understand the repository without diving into the source files. Throughout this document, we expand on the original description, provide elaborations on each concept, and maintain the full fidelity of the original narrative without omitting any essential detail.

Table of Contents
Introduction and Background – Overview of retrieval‑augmented generation, the evolution from a Genshin‑specific chatbot to a domain‑agnostic knowledge agent, and the objectives behind the refactor.

Objectives of the Refactor – Description of the two major goals: fixing build issues and generalizing the system into a self‑learning knowledge‑driven agent.

Architectural Overview – High‑level description of the system pipeline including query correction, hybrid retrieval, result fusion, context construction, verification, and reinforcement learning.

Centralized Knowledge Base – Explanation of the new database entities (DomainKnowledge and EntityAttribute), the services used to access them, and how they replace hard‑coded lexicons.

Dynamic Relationship Rules and Reranking – Details on replacing static element policies with dynamic relationship rules, how these rules are injected into prompts, and how they influence scoring.

Adaptive Reranking and User Feedback – Introduction to the SynergyStat entity and AdaptiveScoringService, describing how user feedback drives reranking.

Hallucination Suppression Techniques – Explanation of multiple guardrails (claim verification, evidence gate, authority weighting) designed to reduce hallucinations.

Modular Prompt Builder and Model Router – Centralization of prompt construction and the dynamic routing of queries to appropriate LLM models.

Meta‑Learning and Hyperparameter Tuning – Discussion of how strategy selection, reward scoring, and hyperparameter tuning improve retrieval strategies over time.

Session Isolation, Caching, and Streaming – Explanation of session metadata, caches, server‑sent events (SSE), and how sessions remain isolated.

Implementation Details and Class Descriptions – Comprehensive details on every class, interface, service and repository; instructions on how to modify classes; and a summary of commit messages and refactors.

Hotfix for MemoryReinforcementService – Instructions on how to fix the build failure by removing misplaced braces and adding new fields.

Tasks for Centralizing the Knowledge Base – Steps to create JPA entities, repositories, and services for domain knowledge and attributes.

Tasks for Adaptive Scoring – Steps to create the SynergyStat entity, repository, and AdaptiveScoringService, and integrate them into the reranking pipeline.

Tasks for Hallucination Suppression – Steps to create the ClaimVerifierService and integrate it into the fact verification pipeline.

Improvement Strategies and Case Studies – Analysis of strengths and weaknesses, detailed case study (“Purina” search failure) and recommended improvements.

Configuration and Environment Setup – Instructions on cloning the repository, configuring environment variables, building the project, and understanding important configuration keys.

Operating Principles and Recommended Practices – Guidelines on version locking, session isolation, prompt policies, query expansion, and safety measures.

Contribution Guidelines and Licensing – How to contribute to the project, commit conventions, testing, documentation, and licensing.

Appendix – A narrative summarizing the system analysis and improvement strategy, including expanded discussions on meta‑learning and reinforcement loops.

This table of contents maps the structure of the README and ensures that all information is well organized. Readers can navigate to the sections relevant to them, whether they are interested in the architecture, the implementation details, or the deployment instructions. Each section is deliberately written in separate lines to increase readability and to fulfil the requirement of presenting content in 1,000 lines with no loss of information.

1. Introduction and Background
Retrieval‑augmented generation (RAG) is an architectural pattern that enhances a language model by supplementing it with external knowledge rather than relying solely on its internal parameters. The fundamental idea is that, when faced with a question, the system should fetch relevant documents from a knowledge base (such as a vector store or web search) and present these documents to the language model to ground its response. This mechanism helps reduce hallucinations and increase factual accuracy because the model’s generation is conditioned on real sources. As noted in tutorials on the subject, adding your own data to the prompt ensures more accurate generative output. The hybrid RAG approach can combine vector similarity retrieval with real‑time web search and rule‑based filters to build a rich context. This combination allows the system to handle both static and dynamic knowledge.

The AbandonWare Hybrid RAG Chatbot began as a domain‑specific assistant for the game Genshin Impact. The initial implementation, written in Java 17 using Spring Boot and the LangChain4j library, contained sophisticated features such as query correction, hybrid retrieval, reciprocal rank fusion, cross‑encoder re‑ranking, two‑pass fact verification, and reinforcement learning from user feedback. However, several limitations surfaced. The design hard‑coded domain‑specific policies and entity lists (e.g., GenshinElementLexicon), restricting the system to Genshin characters and elements. Moreover, a bug in the MemoryReinforcementService prevented the project from building successfully. These issues motivated a comprehensive refactor to generalize the system and fix the build.

Goals of the Refactor
Two primary objectives guided the recent set of pull requests:

Fixing the Build Failure: The build was broken due to a misplaced brace inside the MemoryReinforcementService.reinforceWithSnippet(...) method. The refactor removes this misplaced brace, introduces minContentLength and maxContentLength as configurable fields, and implements a reflection‑based helper method to safely access fields in translation memory objects.

Generalizing into a Domain‑Agnostic Agent: The original system was heavily tied to Genshin Impact elements and characters. The refactor introduces a database‑driven knowledge base to replace hard‑coded lexicons. It also adds dynamic reranking based on user feedback and claim verification, making the pipeline adaptable to any domain. These changes transform the chatbot into a knowledge‑driven agent that can answer questions about any entity stored in its knowledge base.

By achieving these goals, the new architecture supports customization via data instead of code modifications. Entities, attributes, and relationship rules can now be added through database entries, making the system scalable across domains such as games, people, products, or any other knowledge area.

2. Objectives of the Refactor
The refactor’s objectives are more than mere bug fixes; they fundamentally redefine the system’s capabilities. In this section, we outline the high‑level goals that shaped the design decisions:

Build Stability: The first objective addresses the immediate issue preventing the project from compiling. A misplaced brace in MemoryReinforcementService produced a compilation error. By removing the brace, adding configurable content length parameters, and implementing reflection helpers, we restored build stability.

Decoupling Domain Knowledge: The second objective aims to decouple domain knowledge from the code. Hard‑coded lists of Genshin elements and characters limited the system. To enable scalability, a database schema with DomainKnowledge and EntityAttribute entities is introduced. These entities store the domain, type, name, and attribute key–value pairs. A KnowledgeBaseService abstracts database queries and exposes methods to fetch attributes and relationship rules dynamically.

Dynamic Relationship Rules: Instead of fixed policies like allowed and discouraged elements, the system now uses dynamic relationship rules that can be stored in the database. For example, relationships such as CONTAINS, IS_PART_OF, PREFERRED_PARTNER, or any custom relationship can be represented in the database. The new RelationshipRuleScorer applies these rules during reranking, ensuring that domain constraints are enforced without hard‑coding.

Adaptive Reranking with User Feedback: The system introduces the SynergyStat entity and AdaptiveScoringService to learn from user feedback. When a user expresses positive or negative reactions (e.g., 👍/👎) to recommendations, the system records this feedback. Reranking scores incorporate a synergy bonus that rewards combinations historically liked by users and penalizes those disliked. Over time, recommendations become personalized and more effective.

Hallucination Suppression: Hallucinations remain a major challenge in generative AI. The refactored system adds multiple layers of verification: a ClaimVerifierService for post‑generation claim verification, an EvidenceGate that requires sufficient evidence before generating answers, and authority weighting to prefer trustworthy sources. These safeguards help ensure that the system provides factual and reliable responses.

Modular Prompt Building and Model Routing: The system centralizes prompt construction in a PromptBuilder and dynamically routes queries to appropriate large language models via a ModelRouter. High‑stakes queries use a higher‑quality model, while simpler queries use faster models. This ensures consistent prompt policies and efficient resource usage.

Meta‑Learning and Hyperparameter Tuning: The system employs a meta‑learning loop that tracks the performance of different retrieval strategies (web‑first, vector‑first, self‑ask decomposition). A DynamicHyperparameterTuner adjusts exploration–exploitation trade‑offs and reward weights to optimize strategy selection. This dynamic adaptation keeps the system effective as conditions change.

Session Isolation and Streaming: Each chat session remains isolated using a metadata key (META_SID). Caches are session‑specific to prevent leakage of context across users. An SSE streaming API allows incremental updates to the user interface, showing search progress, context building, draft answers, and verification results.

Together, these objectives unify to create a robust, general purpose RAG agent capable of learning from user interactions, adapting its retrieval strategies, and providing accurate, context‑rich answers across domains.

3. Architectural Overview
The AbandonWare RAG AI Chatbot Service follows a search‑generate‑verify‑reinforce loop. Each stage of this pipeline is implemented as a distinct component, promoting separation of concerns and facilitating maintenance. In this section we describe each stage in detail, explaining how data flows and how different components interact to produce a final answer.

3.1 Query Correction and Augmentation
The query enhancement phase ensures that user input is well formed and that domain terms are preserved. It consists of multiple services:

LLMQueryCorrectionService: Uses a large language model to correct spelling errors, normalize colloquial expressions, and preserve domain terms. For example, it converts informal Korean or English phrases into standard forms while retaining proper names.

QueryCorrectionService: Implements rule‑based corrections for common mistakes such as punctuation or spacing. It works in conjunction with the LLM‑based service to produce a corrected query.

QueryDisambiguationService: Detects ambiguous keywords and rephrases the query using domain dictionaries or an LLM. The refactor adds a pre‑dictionary check: before calling the LLM, it checks if tokens appear in the DomainTermDictionary. If a token is recognized, the service bypasses the LLM to avoid over‑correcting proper nouns. This resolves issues like mislabeling “Purina” as nonexistent.

QueryAugmentationService: Optionally adds additional keywords based on the intent (e.g., product search, technical how‑to, location). This service is now disabled by default because it can introduce noise; the new SmartQueryPlanner provides a more controlled approach to query planning.

QueryComplexityGate: Classifies the query complexity to inform downstream retrievers. It determines whether the query is simple or complex (e.g., requiring decomposition) and triggers the appropriate retrieval strategy.

3.2 Hybrid Retrieval
Once the query is corrected and disambiguated, the system performs hybrid retrieval. The goal is to gather relevant information from both real‑time web sources and vector databases:

SelfAskWebSearchRetriever: Handles complex questions by decomposing them into sub‑queries. This is useful when the question has multiple parts or when the answer requires reasoning steps. The service uses an LLM to generate sub‑queries and then performs web search on each sub‑query.

AnalyzeWebSearchRetriever: Applies morphological analysis and tokenization to produce better search terms. This is especially important for languages like Korean where morphological boundaries matter. It generates search queries tailored to the domain.

NaverSearchService: Integrates with Naver’s web search API to fetch real‑time snippets. The service applies domain and keyword filters and enforces dynamic rate limits to abide by usage policies.

Vector Retrieval (Pinecone): Retrieves context from a vector database using the LangChain4j RAG service. The vector retrieval fetches documents similar to the query from a pre‑indexed corpus. The Pinecone index contains domain knowledge and is used when retrieval mode is on.

HybridRetriever: Orchestrates the above retrievers. It can run retrievers concurrently or sequentially depending on the query complexity and quality thresholds. For example, it might call the web search first and fallback to vector retrieval if web search fails. The fallback order can be configured and may follow SelfAsk → Analyze → Web → Vector by default.

3.3 Result Fusion and Re‑ranking
After retrieval, the system must merge and rank the results. Multiple sources produce result buckets (web, vector, self‑ask). To synthesize them into a coherent context, the system employs multiple techniques:

Reciprocal Rank Fusion (RRF) and Softmax Fusion: Combine results from different retrieval methods. RRF assigns scores based on the reciprocal rank of each document in the individual rankings. The Softmax approach uses a temperature‑scaled exponentiation to blend scores. These fusion strategies mitigate noise from any single source.

Cross‑Encoder Reranking: The EmbeddingModelCrossEncoderReranker computes semantic similarity between the query and candidate documents. It uses a cross‑encoder model (e.g., BERT or a fine‑tuned LLM) to provide a refined ranking. In the refactored system, this reranker now incorporates a synergy bonus from the AdaptiveScoringService and a relationship rule score from the new RelationshipRuleScorer (formerly ElementConstraintScorer).

AuthorityScorer: Weights results based on domain credibility. Official or authoritative domains (like vendor sites or reputable encyclopedias) receive higher scores, while less trustworthy sources (blogs, generic forums) are demoted. This helps ensure that the final answer relies on high‑quality sources.

LightWeightRanker and RelevanceScoringService: Provide initial ranking based on token overlap and lexical similarity. These heuristics serve as coarse filters before applying the heavy cross‑encoder.

RelationshipRuleScorer: Applies domain‑specific relationship rules obtained from the KnowledgeBaseService. For example, if a character pairs well with another, or if a product is incompatible with a certain feature, the rule influences the score. This dynamic approach replaces static element constraints.

Synergy Bonus: Added to the cross‑encoder score based on user feedback stored in SynergyStat. If users consistently rate a particular pairing positively, the synergy bonus increases the document’s score. Negative feedback lowers the score.

3.4 Context Construction and LLM Call
After ranking, the system constructs a unified context and then invokes an LLM to generate a draft answer. This phase includes several components:

ContextOrchestrator: Merges web snippets, vector passages, and session memory into a single context string. It prioritizes official sources, demotes community sites, and applies duplication filters. It also ensures that context tokens remain within the configured limits (e.g., 8,000 tokens for web context).

PersistentChatMemory: Stores long‑term conversation memory. It may include previous question‑answer pairs or user corrections. This memory helps the system provide continuity across a session.

PromptBuilder: Centralizes prompt construction. It builds system prompts and context prompts, injecting domain‑specific instructions. For example, for pairing queries, it instructs the model: “Recommend partners ONLY for subject X; if evidence is insufficient, answer ‘정보 없음.’” By centralizing prompts, the system enforces consistent policies across all LLM calls.

ModelRouter: Determines which LLM model to use and sets the temperature and top‑p parameters. High‑stakes queries (like pairing or recommendation queries) may use a more powerful model (e.g., GPT‑4o) with a low temperature to ensure accuracy. Simpler queries might use a faster model to conserve resources.

ChatModel: The actual LLM call is made through a chat model interface. The service may use the OpenAI API or LangChain’s model abstraction. The draft answer is generated from the unified context.

3.5 Verification and Fallback
The system does not accept the draft answer at face value; it goes through a verification and fallback phase:

FactVerifierService: Computes coverage and contradiction metrics for the draft answer relative to the context. If the coverage is low (i.e., the answer does not sufficiently cover relevant information) or contradiction is high (i.e., the answer contradicts the context), the system may reject the answer or warn the user.

ClaimVerifierService: A new addition that extracts key claims from the draft answer and verifies each claim against the retrieved context using an LLM. If claims are unsupported, they are removed from the answer. If verification fails, the system responds with “정보 없음” (information unavailable) to prevent hallucinations.

EvidenceGate: Before prompting the LLM, this gate ensures that the retrieved context contains enough evidence (e.g., a minimum number of subject mentions or high confidence in the sources). If evidence is insufficient, the LLM call is aborted, and a fallback response is returned.

AnswerSanitizers: After the model produces the answer, sanitizers such as GenshinRecommendationSanitizer enforce domain policies. They filter out recommendations that violate discouraged rules and ensure that the answer obeys the instructions embedded in the prompt.

SmartFallbackService: Suggests alternative queries or refined questions when the draft answer is insufficient. Instead of simply returning “정보 없음,” the system guides the user to ask a more specific question that might yield a better answer.

3.6 Reinforcement Learning and Feedback
User feedback is central to the system’s self‑improvement. After the answer is delivered, users can react with 👍 (positive) or 👎 (negative) or provide corrections. This feedback enters a reinforcement learning loop:

MemoryReinforcementService: Updates a TranslationMemory database with hit counts, Q‑values (state‑action values), success/failure counts, and quality scores. It computes a Boltzmann energy metric using multiple weights (similarity, Q‑value, success ratio, confidence, recency) and dynamic hyperparameters from HyperparameterService. The energy metric determines how likely a translation memory entry will be used in future retrievals.

SynergyStat: Records positive and negative feedback for combinations or pairings (e.g., how well two characters work together). Each record has a domain, a subject, a partner, and counts for positive and negative reactions. This table is used by the AdaptiveScoringService to compute synergy bonuses.

AdaptiveScoringService: Computes a synergy bonus based on SynergyStat. During reranking, if a candidate answer aligns with historical positive feedback, it receives a higher score. Conversely, if users consistently rate the pairing negatively, the score is penalized. This adaptive scoring mechanism personalizes recommendations and encourages the system to learn from user preferences.

StrategySelectorService: Maintains a meta‑learning loop. It records how each search strategy performs (e.g., web‑first, vector‑first, self‑ask, hybrid). Using a multi‑armed bandit approach, it selects strategies based on historical success probabilities. A softmax policy chooses a strategy with a probability proportional to its estimated reward.

ContextualScorer: Evaluates answers along multiple dimensions (factuality, quality, novelty) and produces reward scores. These scores feed into the reinforcement learning loop, influencing strategy selection and memory reinforcement.

DynamicHyperparameterTuner: Periodically adjusts exploration–exploitation trade‑offs (e.g., the temperature for softmax selection) and reward weights based on aggregated performance. This ensures that the system adapts to changing conditions over time.

BanditSelector: Implements the Boltzmann selection for translation memory entries. It chooses memory snippets probabilistically based on their energy scores, balancing exploitation of high‑quality memory and exploration of new snippets.

This reinforcement framework makes the system self‑learning. By incorporating user feedback into reranking and memory, the chatbot constantly improves its recommendations and retrieval strategies.

3.7 Session Isolation, Caching and Streaming
Production readiness demands that multiple users can use the system concurrently without interfering with one another. The system implements session isolation, caching, and real‑time streaming to meet these needs:

Session Isolation: Each chat session is identified by a metadata key (META_SID). The system ensures that conversation history, translation memory, caches, and reinforcement data are isolated per session. This prevents context leakage and cross‑pollination between users. Sessions can be uniquely identified by a UUID or a chat‑ prefixed string.

Caching: The system uses the Caffeine caching library to store retrieval chains and translation memory entries. Caches are configured per session and expire after a configurable time (e.g., 5 minutes). This improves performance by avoiding redundant retrievals while ensuring fresh data.

Server‑Sent Events (SSE): The ChatApiController provides an /stream endpoint that streams incremental updates to the client. As the retrieval, fusion, verification, and reinforcement steps proceed, intermediate results are sent to the user interface. The client (implemented in chat.js) can display search progress, context building, draft answers, verification warnings, and final answers. This transparency helps users understand how the system arrives at its answers.

WebFlux and Netty: The server uses asynchronous non‑blocking networking via Spring WebFlux and Netty. This allows the system to handle many concurrent streams efficiently and provide low‑latency responses.

By combining session isolation, caching, and streaming, the system provides a responsive, scalable, and secure user experience.

4. Centralized Knowledge Base
One of the most significant changes in the refactor is replacing the hard‑coded Genshin lexicons with a centralized knowledge base. This section describes the motivation, design, and implementation of the new knowledge base.

4.1 Motivation
The original system contained static lists of Genshin elements and characters hard‑coded into the codebase (e.g., GenshinElementLexicon). This rigid design made it difficult to add new domains or entities without modifying the code. To transform the system into a domain‑agnostic agent, domain knowledge must be stored as data rather than code. By centralizing domain information into a database, we can define new domains, entities, attributes, and relationship rules through data entries rather than code changes. This design supports scalability across many domains—games, people, products, recipes, and more.

4.2 Entities: DomainKnowledge and EntityAttribute
The knowledge base comprises two JPA entities:

DomainKnowledge: Each record represents an entity within a domain. It has fields such as id, domain, entityType, and entityName. For example, an entry might have domain = 'game', entityType = 'character', and entityName = 'Diluc'. Another entry might represent a product in the domain appliance.

EntityAttribute: Each DomainKnowledge record can have multiple attributes stored in the EntityAttribute table. This entity has fields like id, domainKnowledgeId (foreign key), attributeKey, and attributeValue. For instance, a character might have attributes like element = 'pyro', weaponType = 'claymore', or role = 'DPS'. A product might have attributes like color, size, or price.

These two tables create a flexible schema that can represent any type of entity and its attributes. New domains and entities can be added simply by inserting records in these tables.

4.3 Repository Interfaces
To access the knowledge base, we define JPA repository interfaces:

DomainKnowledgeRepository: Extends JpaRepository<DomainKnowledge, Long>. It provides methods like findByDomainAndEntityName(String domain, String entityName) or custom queries to fetch domain entities.

EntityAttributeRepository: Extends JpaRepository<EntityAttribute, Long>. It provides methods to find attributes by entity ID or to search for specific key–value pairs. For example, List<EntityAttribute> findByDomainKnowledgeId(Long id) retrieves all attributes for a given entity.

By using Spring Data JPA repositories, we avoid writing boilerplate SQL. The repositories can also support custom query methods and projections as needed.

4.4 KnowledgeBaseService
The KnowledgeBaseService abstracts database operations. Its implementation (DefaultKnowledgeBaseService) defines methods such as:

String getAttribute(String domain, String entityName, String key): Fetches the value of a given attribute for an entity. If the entity or attribute does not exist, it returns null or an Optional.

Map<String, String> getInteractionRules(String domain, String entityName): Returns a map of all relationship rules for the given entity. For example, it might return that Diluc has a preferred partner “Bennett” or that hydro elements complement pyro elements.

List<String> getAllEntityNames(String domain): Retrieves a list of all entity names in a domain. This can help the SubjectResolver determine possible subjects in a query.

By centralizing these queries, other components (like the SubjectResolver, GuardrailQueryPreprocessor, or RelationshipRuleScorer) can fetch domain knowledge without knowing the underlying database structure.

4.5 SubjectResolver
The SubjectResolver uses the knowledge base to derive the subject of a query. For example, given the query “Which character pairs well with Hu Tao?”, the SubjectResolver determines that the subject is the entity with the name “Hu Tao” in the domain of games. It does this by scanning the query for known entity names and selecting the one that matches. This dynamic approach replaces the previous method of matching against a hard‑coded list. If the knowledge base contains multiple matching entities (due to overlapping names across domains), the SubjectResolver uses context or domain hints to disambiguate.

4.6 GuardrailQueryPreprocessor and Interaction Rules
The GuardrailQueryPreprocessor now injects dynamic relationship rules into the prompt context. It calls KnowledgeBaseService.getInteractionRules(domain, entityName) to obtain all relevant rules, such as CONTAINS, IS_PART_OF, or PREFERRED_PARTNER. These rules are passed to the PromptBuilder, which informs the model about allowed or discouraged pairings. By using dynamic rules, the system can enforce domain constraints for any entity without hard‑coding them.

4.7 RelationshipRuleScorer
During reranking, the RelationshipRuleScorer applies interaction rules to candidate documents. For example, if a document mentions pairing Diluc with a water (hydro) element character, but the rule discourages hydro + pyro pairings, the score is lowered. If the rule encourages pyro + hydro pairings, the score increases. This dynamic scoring adapts to rules defined in the database.

4.8 Extensibility and Scalability
Because the knowledge base is data‑driven, new domains and entities can be added without changing the code. To support a new domain (say, musical instruments), one can insert records for instruments, specify attributes (e.g., string type, family), and define relationship rules (e.g., a violin pairs with a viola in a string quartet). The rest of the pipeline remains unchanged, demonstrating the scalability of this design.

5. Dynamic Relationship Rules and Reranking
Replacing static element policies with dynamic relationship rules is a cornerstone of the refactor. This section details how the new rule system works, how it integrates with query preprocessing, retrieval, and reranking, and how it improves accuracy and flexibility.

5.1 From Static Policies to Dynamic Rules
Previously, the system hard‑coded policies like allowedElements and discouragedElements in the ElementConstraintScorer. For example, it might forbid pairing hydro and pyro characters. While this worked for Genshin Impact, it was inflexible and error‑prone for other domains. The refactor introduces a generalized approach: interaction rules stored in the knowledge base. Each rule describes a relationship between entities or between entity attributes. Examples include:

CONTAINS: Denotes that an entity is part of another. For example, “Drum set CONTAINS snare drum” means the snare is a component of a drum set.

IS_PART_OF: The inverse of CONTAINS.

PREFERRED_PARTNER: Indicates a recommended pairing between entities. For example, “Hu Tao PREFERRED_PARTNER X” denotes that Hu Tao pairs well with X.

DISCOURAGED_PAIR: Specifies pairings to avoid. For example, “Pyro DISCOURAGED_PAIR Hydro” could discourage pairing pyro and hydro elements (or vice versa).

These rules are generic and domain‑independent. They can represent relationships in games, products, recipes, or any other context.

5.2 Injecting Rules into Prompts
The GuardrailQueryPreprocessor calls KnowledgeBaseService.getInteractionRules(...) to retrieve all interaction rules for the query’s subject. It then injects these rules into the prompt context. When the PromptBuilder constructs the system prompt, it includes statements like “Allowed pairings: X, Y” or “Discouraged pairings: A, B.” This ensures that the LLM is aware of domain policies and tailors its recommendations accordingly.

5.3 Applying Rules in Reranking
The RelationshipRuleScorer replaces the former ElementConstraintScorer. It evaluates each candidate document against the interaction rules. If the document’s content aligns with a preferred rule (e.g., recommending Hu Tao with a partner known to be preferred), it receives a bonus. If it violates a discouraged rule, it is penalized. This scoring modifies the cross‑encoder’s semantic similarity score, producing a more accurate ranking that respects domain policies.

5.4 Example Scenario
Imagine the domain is “recipes” and the subject is a dish like “Caesar salad.” If the knowledge base contains a rule that “Caesar salad” pairs well with “grilled chicken,” the RelationshipRuleScorer will boost recommendations that include grilled chicken. If a user asks about wine pairings for Caesar salad and a document suggests a heavy red wine (discouraged), the scorer penalizes that document. This dynamic rule system adapts seamlessly to any domain where pairing or relationship constraints matter.

5.5 Flexibility in Defining Rules
The knowledge base design allows new relationship types to be defined. For example, one could add a rule type called AVOID_WITH to specify elements that should never be recommended together. The system would treat these rules like discouraged pairings. Similarly, one could add a rule type such as SUBSTITUTE_WITH, indicating that one entity can substitute another in recommendations. By adding new rule types and corresponding scoring logic, the system can handle complex domain constraints without code modifications.

6. Adaptive Reranking and User Feedback
User feedback is integral to making the system adapt over time. This section elaborates on how the SynergyStat entity, AdaptiveScoringService, and EmbeddingModelCrossEncoderReranker work together to incorporate user feedback into scoring.

6.1 SynergyStat Entity
The SynergyStat entity stores feedback about pairings. It has the fields: id, domain, subject, partner, positive, and negative. Whenever a user reacts positively or negatively to a recommendation, the FeedbackController updates the corresponding SynergyStat record. If no record exists, a new one is created. For example, if users consistently rate the pairing “Diluc + Bennett” positively, SynergyStat for (Diluc, Bennett) will record a higher positive count.

6.2 AdaptiveScoringService
The AdaptiveScoringService calculates a synergy bonus for candidate pairings during reranking. The synergy bonus formula can consider the ratio of positive to total feedback, the total number of reactions, and optional weighting factors (e.g., more weight for recent feedback). A simplified formula could be:

synergyBonus = (positive – negative) / (positive + negative + k)

where k is a smoothing constant to avoid division by zero. A higher bonus is assigned to pairings with a positive history, while negative feedback yields a penalty. This bonus is scaled by a hyperparameter (like W_SYNERGY) configured via HyperparameterService.

6.3 EmbeddingModelCrossEncoderReranker with Synergy
The EmbeddingModelCrossEncoderReranker now integrates the synergy bonus into its scoring logic. It computes the cross‑encoder similarity score for each candidate document, adds the synergy bonus (if applicable), and multiplies or adds it with the relationship rule score. The resulting score determines the ranking. This integration ensures that documents recommending pairings historically liked by users are ranked higher, while those with negative feedback are demoted.

6.4 User Feedback Flow
A user receives an answer that includes a recommendation or pairing.

The user reacts with 👍 or 👎 to indicate satisfaction or dissatisfaction.

The FeedbackController records the reaction by updating SynergyStat and the translation memory entry via MemoryReinforcementService.

The next time a similar question arises, the AdaptiveScoringService calculates the synergy bonus from SynergyStat.

During reranking, the synergy bonus influences the score, leading to recommendations that reflect past user preferences.

6.5 Long‑Term Adaptation
Since user feedback data accumulates over time, the system learns which pairings are generally effective. This adaptation ensures that the chatbot does not remain static but evolves with the community’s preferences. The synergy bonus can also be domain‑specific, enabling separate preference models for different domains.

7. Hallucination Suppression Techniques
Reducing hallucinations is critical for building trust in AI systems. The refactored pipeline introduces multiple guardrails that collectively minimize hallucination risks:

7.1 ClaimVerifierService
After the LLM generates a draft answer, the ClaimVerifierService extracts individual claims (facts or assertions) and verifies each claim against the retrieved context using an LLM. The verification process might involve asking the LLM: “Is it true that X, given this context?” If a claim lacks support in the context, it is either removed from the answer or the entire answer is replaced with “정보 없음.” This post‑generation verification provides a final safety check, ensuring that the final answer is grounded in evidence.

7.2 EvidenceGate
Before the LLM call, the EvidenceGate checks whether the retrieved context contains sufficient evidence for the subject. For example, it may require that the subject appears at least a certain number of times in the context or that the confidence score from the authority weighting passes a threshold. If the evidence is insufficient, the system does not attempt to generate an answer and instead returns a fallback message. This prevents hallucinations when the system lacks adequate information.

7.3 Authority‑Weighted Retrieval
The AuthorityScorer assigns higher weights to results from trusted domains. This scoring influences result fusion and reranking. By preferring official sources (e.g., vendor websites, academic journals, official guides) and demoting blogs or generic forums, the context has a higher likelihood of containing accurate information. Domain weights can be configured to reflect trust levels for different websites.

7.4 AnswerSanitizers
After the answer is generated, the system passes it through a set of sanitizers. In the Genshin context, the GenshinRecommendationSanitizer ensures that no recommendations violate discouraged pairing rules. In other domains, sanitizers could remove offensive content, filter false statements, or ensure regulatory compliance. Sanitizers can be domain‑specific and are easily extensible.

7.5 Multi‑Layered Verification
By combining the FactVerifierService, ClaimVerifierService, EvidenceGate, and AnswerSanitizers, the system has multiple opportunities to catch hallucinations. Even if one layer misses a hallucination, the next layer may catch it. This multi‑layered approach significantly reduces the probability of incorrect statements reaching the user.

8. Modular Prompt Builder and Model Router
Centralizing prompt construction and dynamic model selection are key to consistent and efficient performance:

8.1 PromptBuilder
Instead of constructing prompts ad hoc throughout the code, the PromptBuilder consolidates all prompt logic. It assembles the system prompt, user prompt, and context prompt by injecting instructions based on the query intent and domain policies. For example:

For pairing queries: “Recommend partners ONLY for subject X; if evidence is insufficient, answer ‘정보 없음.’”

For general queries: “Provide an informative, concise answer based on the following context; cite sources when available.”

For high‑stakes queries: “Use a low temperature to ensure accuracy; do not speculate.”

By centralizing prompts, the system ensures that all calls to the LLM use consistent instructions, reducing the risk of prompt injection and inconsistent behavior.

8.2 ModelRouter
Different queries require different models and parameters. The ModelRouter examines the query intent (e.g., pairing, recommendation, fact lookup) and decides which LLM to use. It might choose:

GPT‑4o for complex pairing questions where accuracy is critical and the synergy of entities is important.

GPT‑3.5 or a local model for simpler queries or to reduce latency.

A model with specific fine‑tuning for certain domains (e.g., a model trained on product reviews for shopping queries).

The router also sets temperature and top‑p parameters. High‑stakes queries might use a low temperature (e.g., 0.2) to minimize randomness, whereas exploratory queries might use a higher temperature (e.g., 0.7).

9. Meta‑Learning and Hyperparameter Tuning
The system does not statically choose retrieval strategies; it learns which strategies work best and tunes its own hyperparameters:

9.1 StrategySelectorService
This service tracks the performance of each retrieval strategy (web‑first, vector‑first, self‑ask, hybrid fusion). It uses a softmax (Boltzmann) policy to choose a strategy at run‑time. The probability of choosing a strategy is proportional to its estimated reward, allowing the system to explore lesser‑used strategies while exploiting known good ones.

9.2 ContextualScorer
After each answer, the ContextualScorer evaluates the answer across several dimensions:

Factuality: Does the answer align with verified facts?

Quality: Is the answer well written, concise, and clear?

Novelty: Does the answer provide information beyond what was retrieved (in a beneficial way)?

The scores are combined to produce a reward. This reward informs the StrategySelectorService for future decisions.

9.3 DynamicHyperparameterTuner
The system uses many hyperparameters: the temperature in softmax selection, the weight of the synergy bonus, authority weights, and more. The DynamicHyperparameterTuner periodically (e.g., via a scheduled task) examines aggregated performance metrics and adjusts these hyperparameters. For example, if the system is overly exploring and providing low‑quality answers, the tuner might lower the temperature to encourage more exploitation of known good strategies.

9.4 BanditSelector and Energy Calculation
The BanditSelector determines which translation memory entries to use based on their Boltzmann energy. The energy is computed as a weighted sum of similarity, Q‑value, success ratio, confidence, and recency. The weights and the temperature parameter can be tuned dynamically. Higher energy means a snippet is more likely to be selected for context.

9.5 Meta‑Learning Loop
Combining strategy selection, contextual scoring, reinforcement learning, and hyperparameter tuning creates a meta‑learning loop. The system learns both which retrieval strategies to use and how to tune its own parameters. Over time, it becomes better at selecting strategies that yield high rewards for the particular domain and user population.

10. Session Isolation, Caching, and Streaming
Ensuring that users’ conversations remain private and that the system scales to many users requires careful design. This section reiterates and elaborates on the importance of session isolation, caching, and streaming.

10.1 Session Isolation
Unique Session IDs: Each conversation is assigned a unique metadata key (META_SID). This ID is used to segregate retrieval chains, chat history, and reinforcement statistics. A session ID might include a UUID or be prefixed with “chat-” to avoid collisions.

State Isolation: Services such as PersistentChatMemory, MemoryReinforcementService, and caches use the session ID to maintain separate state. This ensures that knowledge gained in one session does not inadvertently affect another.

Cache Keys: When caching results (e.g., translation memory entries or retrieval chains), the cache keys incorporate the session ID. This prevents cross‑session retrieval and ensures that caches respect session boundaries.

10.2 Caching with Caffeine
Configuration: Caches are configured in application.yml with properties like maximumSize and expireAfterWrite. For example, the conversation cache might have a maximum size of 1,000 entries and expire after 5 minutes.

Usage: The system caches intermediate results such as preprocessed queries, retrieval results, translation memory entries, and synergy scores. By caching, the system reduces duplicate retrievals and computations, improving response times.

Session Specificity: Each cache uses the session ID as part of its key. When a session ends, its caches are cleared. This prevents memory growth from old sessions.

10.3 SSE Streaming
Real‑Time Feedback: The ChatApiController exposes an /stream endpoint that uses Server‑Sent Events. The system sends messages at various stages: after each retrieval, after context construction, after the LLM generates a draft, after verification, and after feedback reinforcement. The front‑end client listens to these events and updates the user interface accordingly.

Transparency: By streaming intermediate steps, the system increases transparency. Users can see which sources were retrieved, which were selected, and how the final answer is constructed. This transparency fosters trust and helps users understand how feedback influences future answers.

Asynchronous Execution: SSE relies on asynchronous non‑blocking execution. The server uses Netty and Spring WebFlux to handle multiple streaming connections efficiently.

11. Implementation Details and Class Descriptions
The refactor introduces numerous classes and modifies existing ones. This section describes each class, interface, and component in detail. We also provide guidance on where to modify code if developers wish to extend or adapt the system. This section is intentionally granular to meet the requirement of detailed documentation.

11.1 MemoryReinforcementService
Purpose: Handles reinforcement of translation memory entries based on user feedback and caching.

Hotfix Changes: The original implementation contained a misplaced brace in the reinforceWithSnippet(...) method, causing a compile error. The refactor removes this brace and adds two configurable fields minContentLength and maxContentLength, injected via @Value annotations using keys memory.snippet.min-length and memory.snippet.max-length in application.yml.

Reflection Helpers: A helper method tryGetString uses reflection to safely access unknown fields in translation memory objects. It attempts multiple getter names (e.g., getScore, getContent, getLastUpdated), uses reflection to call the first method that exists, and returns the result as a string. This helps maintain backward compatibility with different versions of translation memory entries.

Recency and Confidence: The energy calculation now includes recency and confidence factors. Recency decays exponentially over hours since the last reinforcement. Confidence is stored with each entry and influences the energy.

UPserts: The method uses an upsert pattern: if a memory entry exists, it increments hit counts; otherwise, it inserts a new record. Data integrity exceptions are handled gracefully.

Modification Guidance: If developers wish to change how reinforcement works (e.g., adding a new factor or adjusting the formula), they should modify the energy calculation function in this class and update corresponding hyperparameter keys in HyperparameterService.

11.2 DomainKnowledge and EntityAttribute
Purpose: Represent domain entities and their attributes. They allow dynamic definition of domains and entities via database entries.

Structure: DomainKnowledge includes domain, entityType, and entityName. EntityAttribute includes attributeKey and attributeValue. Both are annotated with JPA annotations (@Entity, @Table).

Mapping: DomainKnowledge has a one‑to‑many relationship with EntityAttribute. Fetch types can be lazy or eager depending on performance needs.

Extension: If new attributes or relationships are needed, developers can extend EntityAttribute or add additional tables. If you need to support more complex relationships (e.g., many‑to‑many), consider adding a new entity for relationships.

11.3 KnowledgeBaseService and DefaultKnowledgeBaseService
Purpose: Provide an abstraction for accessing domain knowledge. Exposes methods to get attributes, interaction rules, and entity lists.

Implementation: The default implementation uses DomainKnowledgeRepository and EntityAttributeRepository to fetch records. It caches frequently accessed results to reduce database load.

Modification Guidance: To support additional data sources (e.g., a graph database or external API), implement the KnowledgeBaseService interface with a new class (e.g., GraphKnowledgeBaseService). Ensure that methods like getInteractionRules return all relevant rules for a subject.

11.4 SubjectResolver
Purpose: Resolve the subject of the query using the knowledge base. It looks up the query text for known entity names and selects the most relevant entity.

Implementation: It tokenizes the query, matches tokens against entity names in the knowledge base, and applies heuristics to disambiguate. For example, it may prefer longer matches or use domain hints from the query context.

Modification Guidance: If you need more sophisticated entity resolution (e.g., fuzzy matching, LLM‑based named entity recognition), you can extend or replace this class. A new implementation might use a state‑of‑the‑art NER model or call an external service.

11.5 GuardrailQueryPreprocessor
Purpose: Preprocess the query by injecting dynamic rules and policies. It calls KnowledgeBaseService.getInteractionRules and ModelRouter to set the context for pairing queries.

Implementation: It assembles the PromptContext by combining the subject, interaction rules, and any domain‑specific instructions. For example, it adds allowed and discouraged pairings into the context. It also sets the query intent (PAIRING, RECOMMENDATION, etc.) so that ModelRouter can select the appropriate model.

Modification Guidance: To add more guardrails (e.g., domain guidelines for medical advice), extend this preprocessor. You can add new fields to PromptContext or call additional services for rule retrieval.

11.6 RelationshipRuleScorer
Purpose: Evaluate candidate documents based on dynamic interaction rules. It generates a score that augments the cross‑encoder’s semantic similarity score.

Implementation: It parses the text of each candidate document, checks for mentions of the subject’s attributes and partners, and applies rule weights. For example, if the document recommends a partner known to be a PREFERRED_PARTNER, it adds a positive bonus; if it violates a DISCOURAGED_PAIR, it subtracts points.

Modification Guidance: Developers can adjust the weights for different rule types or add new rule types. They can also optimize the parsing logic, perhaps using an LLM to identify relationships rather than simple string matching.

11.7 AdaptiveScoringService
Purpose: Compute synergy bonuses from SynergyStat. It influences the cross‑encoder’s score during reranking.

Implementation: It uses the formula described in Section 6, with configurable smoothing and weighting factors. It may apply recency weighting so that recent feedback has more influence than older feedback.

Modification Guidance: If new feedback metrics are introduced (e.g., star ratings instead of binary reactions), developers can expand the formula. They may also adjust how the synergy bonus interacts with other scores (e.g., multiplicative vs. additive).

11.8 ClaimVerifierService
Purpose: Verify individual claims extracted from the draft answer. It uses an LLM to cross‑check each claim against the context and remove unsupported claims.

Implementation: It extracts noun phrases or assertions from the draft answer (e.g., “Hu Tao is a hydro character” or “Diluc’s best partner is Jean”) and passes them to an LLM along with the context. The LLM returns a truth value or a probability. Unsupported claims are removed or the whole answer replaced with “정보 없음.”

Modification Guidance: Developers can adjust the claim extraction strategy (e.g., using a NER model or regex patterns) or the verification threshold. They can also integrate with external fact‑checking APIs or knowledge graphs for more robust verification.

11.9 EvidenceGate
Purpose: Ensure that sufficient evidence exists before generating an answer.

Implementation: It checks metrics like the number of subject mentions, the average credibility score of the context, and the number of authoritative sources. If the evidence is below thresholds, it aborts the LLM call.

Modification Guidance: Developers can adjust thresholds or add new evidence metrics (e.g., diversity of sources). They may also log statistics to monitor gate performance and refine the parameters.

11.10 AnswerSanitizers
Purpose: Post‑process the generated answer to enforce domain policies and remove undesired content.

Implementation: The GenshinRecommendationSanitizer checks if any recommended pairings violate discouraged elements. Other sanitizers could remove profanity or ensure that the answer does not provide harmful advice.

Modification Guidance: To add new sanitizers, create classes implementing the AnswerSanitizer interface and register them in the sanitizer chain. Ensure that sanitizers are ordered appropriately (e.g., policy enforcement before style adjustments).

11.11 StrategySelectorService and StrategyPerformance
Purpose: Choose the optimal retrieval strategy using a multi‑armed bandit approach.

Implementation: It stores statistics (success count, failure count, average reward) in a StrategyPerformance entity for each strategy and query category. During inference, it selects a strategy based on a softmax probability distribution over rewards.

Modification Guidance: If new retrieval strategies are added (e.g., a specialized API retrieval), developers should update the selection logic. They may also explore other bandit algorithms (UCB, Thompson sampling) or context‑aware strategies.

11.12 ContextualScorer and DynamicHyperparameterTuner
Purpose: Evaluate answers and tune hyperparameters.

Implementation: The ContextualScorer uses an LLM or heuristic scoring to evaluate factuality, quality, and novelty. The DynamicHyperparameterTuner uses aggregated performance to adjust weights (e.g., synergy weight) and temperatures.

Modification Guidance: Developers can change the scoring metrics or add new ones (e.g., user engagement). They can also tune the schedule for hyperparameter updates (e.g., daily vs. hourly).

12. Hotfix for MemoryReinforcementService
Before the refactor could proceed, a critical build failure needed to be addressed. The file src/main/java/com/example/lms/service/MemoryReinforcementService.java had a misplaced curly brace inside the method reinforceWithSnippet(TranslationMemory t). The patch to fix this included several changes:

Remove Misplaced Brace: The brace that prematurely ended the method was removed. This resolved compilation errors caused by code that followed the brace being placed outside the method.

Add Configurable Fields: Two fields—minContentLength and maxContentLength—were added. They are injected via @Value("${memory.snippet.min-length}") and @Value("${memory.snippet.max-length}") respectively. These values specify the minimum and maximum allowed lengths for memory snippets. Only snippets within this range are reinforced, preventing reinforcement of content that is too short or too long.

Implement Reflection Helper: A helper method tryGetString(Object obj, String... methodNames) was implemented. It iterates through possible getter names (e.g., getScore, getContent, getLastUpdated), uses reflection to call the first method that exists, and returns the result as a string. This provides backward compatibility with earlier versions of the translation memory objects, where field names might differ.

Modify Energy Calculation: Energy calculation now incorporates recency and confidence weightings. It uses hyperparameters W_RECENCY, W_CONFIDENCE, and tauHours for the decay rate. These values are configured in HyperparameterService.

Refactor to Instance Methods: Methods like computeBoltzmannEnergy and annealTemperature were converted to instance methods rather than static methods. This allows injection of hyperparameters and better testability.

Developers applying this hotfix should ensure that application.yml has appropriate values for memory.snippet.min-length and memory.snippet.max-length. They should also verify that reflection calls succeed by testing with different versions of translation memory objects.

13. Tasks for Centralizing the Knowledge Base
To implement the knowledge base described in Section 4, developers should follow these tasks:

Create Entities: Create the JPA entities DomainKnowledge and EntityAttribute. Annotate them with @Entity and define the necessary columns. Use @OneToMany in DomainKnowledge to map to EntityAttribute.

Create Repositories: Define interfaces DomainKnowledgeRepository and EntityAttributeRepository extending JpaRepository. Add custom query methods if needed.

Implement KnowledgeBaseService: Create an interface KnowledgeBaseService with methods like getAttribute, getInteractionRules, and getAllEntityNames. Implement this interface in DefaultKnowledgeBaseService using the repositories. Apply caching via Spring’s @Cacheable if necessary.

Update SubjectResolver: Modify SubjectResolver to call KnowledgeBaseService.getAllEntityNames(domain) when scanning queries. It should identify the subject using dynamic data instead of a static list.

Update GuardrailQueryPreprocessor: Replace references to GenshinElementLexicon with calls to KnowledgeBaseService.getInteractionRules(...). Ensure that all dynamic rules are injected into the PromptContext.

Add RelationshipRuleScorer: Create a new class RelationshipRuleScorer to evaluate documents based on dynamic rules. Inject KnowledgeBaseService so the scorer can fetch the relevant rules.

Populate the Database: Insert initial records into DomainKnowledge and EntityAttribute for existing domains. For Genshin Impact, insert all characters and their attributes. For other domains (e.g., products), insert relevant data.

Test the Integration: Write unit tests to verify that the knowledge base functions correctly. For example, test that getInteractionRules returns the correct rules, and that RelationshipRuleScorer adjusts scores as expected.

By following these tasks, developers can migrate the system from static lexicons to a dynamic knowledge base.

14. Tasks for Adaptive Scoring
Implementing adaptive scoring requires creating new entities and services, and integrating them into the reranking logic. The steps are as follows:

Create SynergyStat Entity: Define the JPA entity SynergyStat with fields for domain, subject, partner, positive, and negative. Annotate it with @Entity and map it to a table (e.g., synergy_stat).

Create SynergyStatRepository: Define a repository interface SynergyStatRepository extending JpaRepository<SynergyStat, Long>. Add query methods to find records by domain, subject, and partner.

Implement AdaptiveScoringService: Create a service class that uses SynergyStatRepository to fetch feedback scores. Implement a method double getSynergyBonus(String domain, String subject, String partner) that computes the bonus using a formula like (positive - negative) / (positive + negative + 1) multiplied by a scaling factor from HyperparameterService.

Inject AdaptiveScoringService: Inject the service into EmbeddingModelCrossEncoderReranker. Modify the reranker’s scoring algorithm to add the synergy bonus to the cross‑encoder score for candidate documents that contain a pairing.

Update FeedbackController: Modify the controller to record user reactions. When a user presses 👍 or 👎, update the corresponding SynergyStat record. Also update translation memory via MemoryReinforcementService.

Test Adaptive Scoring: Write tests to ensure that positive feedback increases synergy bonuses and negative feedback decreases them. Verify that the reranker uses the synergy bonus correctly.

By implementing these tasks, the system will learn from user feedback and produce recommendations that reflect user preferences.

15. Tasks for Hallucination Suppression
To add robust hallucination suppression, developers should perform the following tasks:

Create ClaimVerifierService: Define a service class that extracts claims from a draft answer and verifies them against the context. Implement a method List<String> verifyClaims(String draftAnswer, String context) that returns a list of verified claims or returns an empty list if none are supported.

Integrate ClaimVerifierService: Inject the service into FactVerifierService. After fact checking, call the claim verifier. If the claims list is empty, return “정보 없음.” Otherwise, assemble the final answer from verified claims.

Implement EvidenceGate: Create a component that checks context sufficiency before calling the LLM. Implement methods to evaluate metrics such as subject mentions and domain credibility. If thresholds are not met, return a fallback response.

Implement AnswerSanitizers: Create or update sanitizers to enforce domain policies. For example, GenshinRecommendationSanitizer ensures that recommended pairings do not violate discouraged rules. Register sanitizers in a chain so multiple sanitizers can act in sequence.

Update FactVerifierService: Integrate all new components (ClaimVerifierService, EvidenceGate, AnswerSanitizers) into the verification pipeline. Ensure that verification is executed in the correct order: evidence check, fact check, claim verification, sanitization.

Test Hallucination Suppression: Write tests to confirm that unsupported claims are removed, that insufficient context triggers fallback responses, and that sanitizers filter out disallowed content.

These tasks collectively add robust hallucination suppression to the system.

16. Improvement Strategies and Case Studies
Refactoring a complex system is an iterative process. The original design had both strengths and weaknesses. This section analyzes the architecture and presents recommended improvements based on case studies.

16.1 Architectural Strengths
Clear Pipeline Separation: Each stage of the pipeline (correction, disambiguation, strategy selection, retrieval, fusion, verification, reinforcement) is implemented as a distinct service. This modularity simplifies maintenance, testing, and scalability.

Advanced Retrieval Techniques: By combining morphological analysis, self‑ask decomposition, web search, and vector search, the system maximizes recall and context quality.

Meta‑Learning and Reinforcement: The system learns which retrieval strategies work best and uses reinforcement learning to improve over time. Dynamic hyperparameter tuning optimizes the balance between exploration and exploitation.

Production‑Ready Features: Session isolation, SSE streaming, caching, and dynamic configuration support real‑time deployment and scaling.

16.2 Neutral Observations
Layered Decision Process: Many decisions rely on intermediate LLM calls. If early stages produce errors (e.g., query disambiguation), those errors propagate. However, fallback logic and strict validation mitigate these errors.

Rules and Heuristics vs. AI: The system combines static heuristics (regex filters, domain weights) with AI reasoning (LLM‑based corrections and extraction). Coordinating these layers requires careful tuning to avoid conflicts.

16.3 Case Study – The “Purina” Search Failure
A user asked: “원신에 푸리나랑 잘 어울리는 캐릭터가 뭐야” (Which character matches Purina in Genshin?). The system incorrectly flagged “Purina” as a potentially nonexistent term and produced no answer. Several issues contributed to this failure:

Over‑Correction: QueryDisambiguationService strictly enforced the LLM prompt “do not invent characters,” causing it to flag unknown names as nonexistent. It did not check the domain dictionary first.

Knowledge Silo: DomainTermDictionary contained “Purina” as a valid proper noun, but QueryDisambiguationService did not consult it. Only LLMQueryCorrectionService used the dictionary.

Rigid Fact Verification: FactVerifierService used regex patterns to extract entities and could not recognize new names.

Unsophisticated Sorting: WebSearchRetriever sorted results without considering domain trustworthiness, leading to poor context quality.

16.4 Improvement Recommendations
Based on the case study, the following improvements were implemented:

Pre‑Dictionary Check: QueryDisambiguationService now checks if tokens exist in DomainTermDictionary before calling the LLM. If a token is found, the service bypasses the LLM, preventing false negatives.

Protected Term Injection: The LLM prompt dynamically lists protected terms (e.g., “푸리나,” “원신”) that should not be changed or questioned. This prevents the LLM from inventing or rejecting known names.

LLM‑Based Named Entity Extractor: A new LLMNamedEntityExtractor extracts entities from the context and draft answers. It supplements regex extraction and improves recognition of new names.

Authority‑Weighted Sorting: WebSearchRetriever now integrates the AuthorityScorer to weight results by domain credibility. By adjusting weights (e.g., promoting namu.wiki), the system retrieves more authoritative sources.

Fine‑Tuning Heuristics: The weights for authority scoring are fine‑tuned for each domain. For games, community wikis like hoyolab.com may be trusted more than generic blogs.

16.5 Continuous Improvement
The improvement strategy emphasizes continuous monitoring. Developers should log metrics about search failures, feedback patterns, and hallucination incidents. By analyzing these metrics, they can refine heuristics, adjust hyperparameters, and update knowledge base rules. Regularly review case studies to identify new failure modes and address them proactively.

17. Configuration and Environment Setup
Setting up the system requires cloning the repository, configuring environment variables, adjusting application.yml, and understanding important keys. This section provides a step‑by‑step guide.

17.1 Cloning the Repository
To obtain the code:

bash
복사
$ git clone https://github.com/UnlimitedAbandonWare/AbandonWareAi.git
$ cd AbandonWareAi
17.2 Configuring Environment Variables
The application relies on several external services. Set the following environment variables before running the application:

OPENAI_API_KEY – Your OpenAI API key for LLM calls.

PINECONE_API_KEY and PINECONE_ENVIRONMENT – Credentials for Pinecone vector database.

NAVER_API_CLIENT_ID and NAVER_API_CLIENT_SECRET – Credentials for Naver web search. The system calls Naver’s API to fetch real‑time web snippets.

Optionally, set additional environment variables if using other vector stores or search services.

17.3 Editing application.yml
The file src/main/resources/application.yml contains numerous configuration keys. Copy the example:

bash
복사
$ cp src/main/resources/application.yml.example src/main/resources/application.yml
$ vi src/main/resources/application.yml  # Use your editor of choice
Important sections include:

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
Adjust the values according to your deployment environment and performance needs. For example, reduce max-tokens if running on a smaller model, or increase maximumSize if expecting high traffic.

17.4 Building and Running
Ensure that you have JDK 17+ installed. To build and run the project:

bash
복사
$ ./gradlew bootRun
Alternatively, open the project in your IDE and run LmsApplication.java as a Spring Boot application. The server starts at http://localhost:8080 by default. Use an API client or the provided front‑end to interact with /chat and /stream endpoints.

17.5 Database Setup
By default, the project uses an in‑memory H2 database. For production, configure a persistent database (e.g., PostgreSQL or MySQL) by adjusting spring.datasource properties in application.yml. Run database migrations (if using Flyway or Liquibase) to create the tables (domain_knowledge, entity_attribute, synergy_stat, etc.).

17.6 Vector Database Setup
If using Pinecone, set the PINECONE_API_KEY, PINECONE_ENVIRONMENT, and specify pinecone.index.name. The system will create or use an existing index. If using a different vector store, adjust the LangChain4j configuration accordingly.

18. Operating Principles and Recommended Practices
To ensure consistent behavior and maintainability, follow these operating principles:

Version Lock: The project uses LangChain4j version 1.0.1. Avoid upgrading dependencies without thorough testing, as API changes may break the pipeline.

Session Isolation: Always include a unique session ID when making API calls. Session IDs ensure isolation of memory and retrieval chains. Avoid reusing session IDs across different users.

Prompt Policies: All prompts should be constructed through PromptBuilder. Avoid ad‑hoc prompt construction to prevent inconsistencies. Include instructions to prefer official sources and to answer “정보 없음” when information is missing.

Controlled Query Expansion: Avoid unbounded query expansion. Use QueryHygieneFilter and cap the number of queries generated by the planner. Excessive expansion can degrade performance and quality.

Safety First: Uphold safety by enforcing two‑pass verification. Only return answers that pass coverage and contradiction checks. When in doubt, return “정보 없음” or suggest a more specific query.

Encourage Feedback: Prompt users to provide thumbs up/down or corrections. Feedback is crucial for reinforcement learning and synergy scoring.

Monitor Performance: Continuously log metrics such as retrieval latency, fusion quality, verification failures, and feedback ratios. Use these metrics to refine heuristics and hyperparameters.

Gradual Tuning: When adjusting hyperparameters (via DynamicHyperparameterTuner), do so gradually. Sudden changes can destabilize the system. Monitor the effects of changes through test runs.

Secure Keys: Protect API keys and secrets. Do not commit them to version control. Use environment variables or secure vaults.

Testing: Write unit and integration tests for new components. When adding new domains or rules, test retrieval, reranking, and verification logic thoroughly.

By adhering to these principles, the project remains robust, accurate, and maintainable.

19. Contribution Guidelines and Licensing
The AbandonWare Hybrid RAG AI Chatbot Service is an open‑source project. Contributions are welcome. Follow these guidelines to contribute effectively:

Fork and Branch: Fork the repository and create a branch named feature/<your-feature-name> or bugfix/<your-bug-name>.

Commit Conventions: Use conventional commit prefixes such as feat:, fix:, refactor:, docs:, and test:. Write clear, descriptive commit messages in English or Korean, summarizing what the commit does and why.

Add Tests: For new features or bug fixes, add unit or integration tests where applicable. Ensure that existing tests pass.

Update Documentation: If your change alters the architecture, adds new components, or modifies configuration, update this README or create additional documentation files. Use diagrams (e.g., Mermaid flowcharts) to illustrate complex flows.

Submit Pull Request: Open a pull request with a detailed description of your changes. Include context on why the change is needed and any potential impacts on existing functionality.

Code Reviews: Be receptive to feedback from maintainers and reviewers. Address comments and iterate until the pull request is ready for merging.

License: This project is licensed under the MIT License. See the LICENSE file for details.

Code of Conduct: Follow a code of conduct that promotes respectful collaboration. Provide constructive feedback and be considerate of others.

Testing Locally: Before submitting a pull request, run the application locally with a test configuration to ensure that the pipeline works end to end. Use test data or a local knowledge base to exercise all components.

By following these guidelines, contributors help maintain the project’s quality and coherence.

20. Appendix – System Analysis & Improvement Strategy Narrative
This appendix provides a narrative summary of the system analysis, root cause investigations, and strategic improvements recommended for the AbandonWare Hybrid RAG AI Chatbot Service. This narrative is presented here verbatim to preserve context for future audits and to help readers understand the reasoning behind the refactor and the improvements.

Project Overview
The AbandonWareAI project aims to evolve beyond a typical chatbot by implementing a hybrid retrieval‑augmented generation system. It decomposes questions (Self‑Ask), verifies facts (Fact‑Check), and learns from user interactions (Reinforcement Learning). The original system focused on the Genshin Impact game and used static policies. The refactor transforms it into a general knowledge agent with dynamic rules, adaptive scoring, and multi‑layered hallucination suppression.

Architectural Strengths
The pipeline is clearly structured with separate services for correction, disambiguation, strategy selection, retrieval, fusion, verification, and reinforcement. This modularity enhances maintainability. The integration of multiple retrieval methods (web search, morphological analysis, self‑ask decomposition, vector retrieval) increases recall and context quality. Meta‑learning tracks strategy performance and tunes hyperparameters automatically. The system supports session isolation, SSE streaming, caching, and dynamic configuration, making it production ready.

Neutral Observations
The system relies on many intermediate LLM calls. Errors in early stages can propagate through the pipeline. However, fallback logic and strict validation help mitigate these errors. The system combines static heuristics (regex filters, domain weights) with AI reasoning (LLM‑based corrections, claim verification). Coordinating these elements requires careful tuning to avoid contradictions.

Case Study: “Purina” Search Failure
When a user asked which character matches “Purina” in Genshin Impact, the system incorrectly identified “Purina” as a nonexistent element and returned no answer. The root cause analysis identified four issues: over‑correction by QueryDisambiguationService, lack of dictionary consultation, rigid fact verification, and poor domain weighting. The improvements described in Section 16 addressed these issues by adding a pre‑dictionary check, injecting protected terms, implementing an LLM‑based named entity extractor, and integrating authority‑weighted sorting.

Improvement Strategy
Going forward, developers should monitor metrics such as failed retrievals, feedback statistics, and hallucination incidents. Regularly update the knowledge base, fine‑tune authority weights, and refine heuristics. Adopt LLM‑based tools (e.g., for named entity recognition) to supplement regex patterns. Experiment with new relationship rule types and scoring functions to handle complex domains. Encourage user feedback and incorporate it into reinforcement learning. Continuously evaluate and refine the pipeline to maintain reliability and adaptability.

Conclusion
The AbandonWare Hybrid RAG AI Chatbot Service has been transformed into a robust, adaptable, and knowledge‑driven agent. By centralizing domain knowledge, replacing static rules with dynamic relationships, incorporating user feedback into reranking, and adding multiple layers of verification, the system delivers grounded, accurate responses across domains. As the knowledge base expands and feedback accumulates, the system will continue to improve. This README aims to provide a comprehensive reference for maintainers, contributors, and users, ensuring that the project remains transparent, extensible, and well documented.

21. Implementation Verification and Test Plan
Deploying the refactored system into production requires careful verification. This section outlines a test plan to ensure that all components are correctly configured and that the dynamic features operate as intended.

21.1 Database Verification
Schema Validation: After starting the application, verify that the tables domain_knowledge, entity_attribute, and synergy_stat are created. Use a database administration tool or run queries like SELECT * FROM domain_knowledge to confirm the schema.

Initial Data Population: Insert sample records into domain_knowledge and entity_attribute to test retrieval. For example, insert a record for the character “Diluc” with attributes element=pyro and weaponType=claymore. Verify that the KnowledgeBaseService can retrieve these attributes.

SynergyStat Records: Simulate feedback by inserting positive and negative counts for a pairing (e.g., Diluc + Bennett). Confirm that SynergyStatRepository can fetch these records.

21.2 Dependency Injection
Service Initialization: Start the application and inspect the logs to ensure that new services like DefaultKnowledgeBaseService, AdaptiveScoringService, ClaimVerifierService, EvidenceGate, and RelationshipRuleScorer are initialized. Spring Boot should indicate successful bean creation.

ApplicationContext Test: Write a test using Spring’s @SpringBootTest annotation to verify that all new beans load correctly. Autowire each service and assert that they are not null.

21.3 Functional Tests
GuardrailQueryPreprocessor: Write a unit test that calls the preprocessor with a pairing query. Verify that it fetches policies from the knowledge base and injects them into the PromptContext. Assert that no hard‑coded lists are accessed.

RelationshipRuleScorer: Provide mock documents and rules to the scorer. Verify that preferred pairings receive positive scores and discouraged pairings are penalized.

AdaptiveScoringService: Simulate SynergyStat records with different positive and negative counts. Call getSynergyBonus() and verify that the computed bonus matches the expected value.

ClaimVerifierService: Provide a draft answer containing supported and unsupported claims. Pass it along with a context to the service and verify that unsupported claims are removed.

EvidenceGate: Provide contexts with varying numbers of subject mentions and credibility scores. Ensure that the gate allows or blocks the LLM call according to the configured thresholds.

21.4 Integration Tests
End‑to‑End Chat Test: Start the server and use an API client to send a query. Simulate a session by setting a META_SID. Observe the SSE stream and confirm that search progress, context construction, draft answers, verification steps, and final answers are streamed correctly. Provide feedback and verify that synergy bonuses change reranking in subsequent queries.

Strategy Selector Behavior: Execute multiple queries with different intents (simple fact, complex pairing). Inspect logs or debug output from StrategySelectorService to confirm that it chooses appropriate strategies (web‑first, vector‑first, hybrid). Over repeated runs, verify that the service adjusts probabilities based on success.

21.5 Performance Tests
Throughput and Latency: Load test the system with concurrent sessions. Measure the time taken for retrieval, fusion, and LLM calls. Ensure that caching and streaming maintain low latency under load.

Memory Usage: Monitor memory consumption over long sessions. Confirm that session caches expire as configured and do not cause memory leaks.

Following this test plan ensures that the refactor is robust, reliable, and ready for production deployment.

22. Commit History and Improvement Log
Understanding how the project evolved provides insight into the design decisions and the motivations behind each change. The following summarizes major commit messages and elaborates on the improvements they introduced. Each bullet corresponds to a commit, and the descriptions capture the key changes and their implications.

22.1 refactor: 서비스 계층 구조 리팩토링 및 컴파일 오류 수정
Service Layer Refactoring: This commit reorganized the service layer to decouple concerns. A shim layer was introduced in MemoryReinforcementService to maintain backward compatibility while enabling new features. Database queries were relocated into TranslationMemoryRepository and method names were clarified.

Unified ChatService Pipeline: The chat pipeline was consolidated and logging (@Slf4j) was added to all services. This unified pipeline simplifies debugging and maintenance.

Compile Error Fixes: Constructor mismatches and missing interfaces were corrected. LightWeightRanker was redefined as an interface, with DefaultLightWeightRanker as its implementation. Mismatched vector types (double[] vs. float[]) in EmbeddingCrossEncoderReranker were fixed. Deprecated RestTemplate configuration was replaced with modern WebClient settings.

22.2 feat: 메타 강화 루프 도입 및 전략 선택 고도화
Meta‑Learning Loop: A meta‑learning loop was introduced through StrategySelectorService, ContextualScorer, and DynamicHyperparameterTuner. These components coordinate to choose retrieval strategies based on success history and reward evaluations.

StrategyPerformance Entity: A new entity StrategyPerformance and its repository were created to persist success/failure counts and average rewards per strategy and query category.

Multi‑Reward Scoring: The system began evaluating answers using multiple rewards (factuality, quality, novelty) and combined this with memory energy scoring.

AuthorityScorer Enhancements: Domain weighting was added to AuthorityScorer, and a two‑pass meta‑check process was introduced in FactVerifierService to improve verification accuracy.

DynamicChatModelFactory: A factory was added to select chat models and parameters at runtime, supporting dynamic temperature and top‑p adjustments.

22.3 feat: RAG 파이프라인 개선 및 고유명사 검색 정확도 향상
Early Dictionary Check: QueryDisambiguationService now performs a dictionary check before calling the LLM to avoid over‑correcting proper nouns. This prevents valid names from being mistakenly flagged as nonexistent.

NamedEntityExtractor Interface: A new NamedEntityExtractor interface and an LLMNamedEntityExtractor implementation were created for dynamic entity extraction. A regex fallback is used if the LLM is unavailable.

Authority‑Weighted Sorting: WebSearchRetriever integrates AuthorityScorer to sort results by domain credibility. Domain weights were adjusted to prioritize trusted sources like namu.wiki.

False Positive Guard: The prompt interpretation (e.g., “존재하지 않는 요소” warning) was simplified when terms are found in the dictionary, reducing false positives.

22.4 refactor: MemoryReinforcementService API 리팩토링
Unified Reinforcement API: A new method reinforceWithSnippet(TranslationMemory t) accepts entire TranslationMemory objects. Reflection is used to extract unknown fields (score, content) safely, and missing fields are handled gracefully.

UPSERT Logic: Reinforcement now follows an UPSERT pattern: if a hash exists, only the hit count is incremented; otherwise, a new record is inserted with initial values.

Content Length Filters: Minimum and maximum content length checks were added to filter out snippets that are too short or too long. These thresholds are configurable in application.yml.

Boltzmann Energy and Annealing: Energy and temperature calculations were refactored into instance methods with injection of dynamic hyperparameters via HyperparameterService. Recency and confidence weights were added, and automatic annealing of temperature was based on hit counts.

Improved Error Handling: DataIntegrityViolationException handling ensures idempotent UPSERTs and robust error logging.

22.5 feat: 신뢰도 기반 Energy 개선 및 자동 온도 조정
Confidence and Recency: Confidence and recency were incorporated into the Boltzmann energy calculation. Recency decays exponentially, encouraging more recent answers. Confidence is derived from answer quality metrics.

Automatic Temperature Annealing: Temperature values now adjust automatically based on hit counts, following a 1/sqrt(hit+1) decay. This reduces exploration as answers accumulate confidence.

Hyperparameter Keys: New hyperparameters (e.g., weights for confidence and recency, decay rates) were introduced and can be tuned via configuration or the hyperparameter tuner.

Debug Logging: Additional logging shows updated energy and temperature values for memory entries, aiding in debugging and analysis.

22.6 feat: 입력 쿼리 위생 필터(QueryHygieneFilter) 업그레이드
Improved Sanitization: The filter removes domain‑scope prefixes (e.g., site filters), protects domain terms (e.g., “원신”) from substitution, and filters out unwanted words unless they appear in the original query.

Jaccard Similarity: Deduplication based on Jaccard similarity merges near‑identical queries, reducing redundancy.

SmartQueryPlanner: A new component wraps the enhanced transformer and applies sanitization with a cap on the number of queries generated. This prevents uncontrolled query explosion.

22.7 docs: 시스템 분석 및 개선 전략 문서 추가
System Analysis Documentation: A document was added to explain architectural strengths, case studies, and improvement strategies. This README draws heavily from that document, preserving its insights and recommendations.

Narrative Preservation: The commit preserved narrative context for future audits, ensuring that the reasoning behind each improvement remains accessible.

22.8 feat: Enhance RAG pipeline with policy‑driven guards and dynamic routing
Intent & Domain Detection: GuardrailQueryPreprocessor was enhanced to detect PAIRING intent and GENSHIN domain. It injects domain‑specific policies (allowed and discouraged elements) into the PromptContext.

SubjectResolver: A new component extracts the query’s subject using dictionary lookups and heuristics, ensuring that all downstream operations are anchored to the correct entity.

Policy‑Driven Retrieval: A new PairingGuardHandler intercepts pairing intents and enforces subject anchoring in generated search queries. A GenericDocClassifier penalizes low‑quality generic documents during search and reranking.

AuthorityScorer Updates: The scorer prioritizes trusted domains (e.g., namu.wiki, hoyolab.com) and demotes less reliable ones.

Centralized Prompt & Model Management: PromptBuilder now injects pairing instructions and fallback policies. ModelRouter routes pairing queries to a superior, low‑temperature model while using a more efficient model for general queries.

22.9 feat: Evolve RAG pipeline to be dynamic, adaptive, and knowledge‑driven
Centralized Knowledge Base: Hard‑coded lexicons were replaced with a database‑driven knowledge base (DomainKnowledge and EntityAttribute entities). This allows dynamic addition of new domains, characters, items, and policies without code changes.

Adaptive Scoring: Introduced AdaptiveScoringService and SynergyStat to track user feedback and compute synergy bonuses. Feedback directly influences reranking, allowing the system to learn which combinations are effective.

Hallucination Suppression: Added ClaimVerifierService as a final sanitization step. It extracts factual claims from the AI’s draft response and verifies each against the retrieved context. Unsupported claims are removed before the response is returned.

Granular Intent Detection: QueryContextPreprocessor now differentiates between general recommendations and specific pairing intents, enabling tailored pipeline strategies.

Evidence & Answer Sanitization: Introduced EvidenceGate to block LLM calls when context is insufficient. Added GenshinRecommendationSanitizer to filter answers that violate discouraged policies. Implemented startup checks (StartupVersionPurityCheck) to ensure all dependencies are version locked.

23. Additional Examples and Use Cases
To illustrate how the system operates across domains, here are several example queries and the system’s expected behavior:

Genshin Pairing Query: “Which character pairs well with Hu Tao?” The system identifies “Hu Tao” as the subject, retrieves dynamic rules (e.g., preferred partners), and builds a context from authoritative game wikis. It generates a recommendation such as “Hu Tao pairs well with Xingqiu,” verifies the claim, and applies synergy bonuses based on past user feedback.

Product Recommendation: “What monitor works well with the MacBook Pro?” Assuming the knowledge base contains computer products, the system resolves the subject (“MacBook Pro”), retrieves attributes (e.g., size, ports), and dynamic relationship rules (e.g., PREFERRED_PARTNER monitors with Thunderbolt support). It ranks web and vector search results and suggests monitors with high synergy and verified compatibility.

Recipe Pairing: “What wine should I serve with grilled salmon?” The subject (“grilled salmon”) triggers retrieval of culinary guidelines. The system uses rules (e.g., PREFERRED_PAIRINGS for fish) and recommends a Pinot Noir or Chardonnay, with evidence from culinary databases and user feedback on pairing success.

Educational Query: “Explain the relationship between photosynthesis and cellular respiration.” Without pairing intent, the system performs general retrieval, constructs context from textbooks or reputable sources, and generates an explanation. Claim verification ensures factual accuracy.

These examples demonstrate the system’s flexibility. By leveraging dynamic knowledge, relationship rules, adaptive scoring, and hallucination suppression, it can handle diverse questions while maintaining accuracy and relevance.

24. Future Directions and Enhancements
The AbandonWare AI system is designed for extensibility. Future development can further expand its capabilities. Here are several directions for future work:

Additional Retrieval Sources: Integrate specialized APIs (e.g., scholarly databases, product catalogs) as new retrievers. This would enhance context quality for specific domains.

Graph‑Based Knowledge: Replace or supplement the relational knowledge base with a graph database. Graph structures can better represent complex relationships and enable advanced reasoning.

Improved Claim Verification: Use external fact‑checking APIs or structured knowledge graphs to verify claims. This would reduce reliance on LLMs for verification and improve accuracy.

User Personalization: Extend reinforcement learning to personalize answers based on individual user preferences. Maintain user profiles with feedback history and tailor recommendations accordingly.

Fine‑Grained Policy Control: Allow administrators to define policies per domain (e.g., restrict certain recommendations for medical or legal domains). Integrate policy management into the knowledge base.

Multilingual Support: Currently, the system focuses on Korean and English queries. Expand support to additional languages by adding language detection, translation, and domain dictionaries.

Continuous Deployment: Implement a CI/CD pipeline that automatically tests, builds, and deploys new versions with updated knowledge bases and heuristics.

Explainability Tools: Develop tools that visualize which sources and rules contributed to the final answer. Transparency helps users understand and trust the system.

Integration with Conversational Interfaces: Extend the system to voice assistants or chatbots with natural language understanding, enabling multi‑turn dialogues and follow‑up questions.

These future directions highlight the potential for continuous growth. As technology advances and user expectations evolve, the system can adapt to provide more accurate, reliable, and personalized information across domains.

25. Glossary of Terms
This glossary defines key terms used throughout the README. Understanding these terms helps readers grasp the system’s architecture and reasoning.

RAG (Retrieval‑Augmented Generation): An approach that combines information retrieval with generative language models. The model fetches relevant documents and uses them as context for generation.

Vector Store: A database that stores embeddings (vector representations) of documents and allows similarity search. Pinecone is an example of a vector store.

Knowledge Base: A structured collection of entities and their attributes. In this system, it consists of the DomainKnowledge and EntityAttribute tables.

Interaction Rule: A relationship between entities or attributes, such as CONTAINS, IS_PART_OF, PREFERRED_PARTNER, or DISCOURAGED_PAIR.

Cross‑Encoder: A model that jointly encodes a pair of inputs (e.g., query and document) and outputs a relevance score. It differs from a bi‑encoder, which encodes inputs separately.

Synergy Bonus: A score adjustment based on user feedback. Pairings with positive feedback receive a bonus, while negative feedback yields a penalty.

Hallucination: A fabricated or unsupported statement generated by an AI model. Hallucination suppression techniques aim to detect and remove such statements.

Softmax (Boltzmann) Selection: A probabilistic selection method used in multi‑armed bandit problems. It assigns probabilities to actions based on their estimated rewards, controlled by a temperature parameter.

Hyperparameter Tuning: The process of adjusting configuration values (e.g., weights, temperatures) to optimize system performance. The DynamicHyperparameterTuner automates this process.

Bandit Selector: An algorithm that selects among multiple options (e.g., memory entries, strategies) based on past rewards and exploration policies.

Server‑Sent Events (SSE): A web technology that allows a server to push updates to a client over HTTP. It is used to stream intermediate results and status updates.

Prompt Context: The combination of instructions and retrieved documents used as input to the LLM. It guides the model’s generation.

LLM (Large Language Model): A neural network trained on large corpora of text capable of generating human‑like language and understanding context. Examples include GPT‑4 and GPT‑3.5.

Authority Scoring: A heuristic that assigns higher weight to information from trusted sources, improving answer reliability.

Fact Verification: The process of checking statements against retrieved context to ensure accuracy. Implemented in FactVerifierService and ClaimVerifierService.

Here are partial, git-style patch hunks you can apply to README.md to incorporate the verbosity pipeline, routing policy, handler order, config keys, and version-purity guard. They only add content; nothing is removed.

--- a/README.md
+++ b/README.md
@@
 Table of Contents
 Introduction and Background – Overview of retrieval-augmented generation, the evolution from a Genshin-specific chatbot to a domain-agnostic knowledge agent, and the objectives behind the refactor.
@@
 Modular Prompt Builder and Model Router – Centralization of prompt construction and the dynamic routing of queries to appropriate LLM models.
+Verbosity-Driven Output Policy (brief|standard|deep|ultra) – End-to-end signal that controls routing, context caps, prompt sections, and post-expansion.
+Classpath Version Purity Guard (LangChain4j 1.0.1) – Startup check that aborts on mixed 0.2.x/1.0.x artifacts.
 Configuration and Environment Setup – Instructions on cloning the repository, configuring environment variables, building the project, and understanding important configuration keys.

--- a/README.md
+++ b/README.md
@@ 3. Architectural Overview @@
 The AbandonWare RAG AI Chatbot Service follows a search-generate-verify-reinforce loop. Each stage of this pipeline is implemented as a distinct component, promoting separation of concerns and facilitating maintenance. In this section we describe each stage in detail, explaining how data flows and how different components interact to produce a final answer.
 
+#### Chain of Responsibility (HybridRetriever entry)
+The request pipeline is a chain of handlers in this strict order:
+`SelfAskHandler → AnalyzeHandler (Query Hygiene) → WebHandler → VectorDbHandler`.
+Each handler may fully handle or pass down; **failures must not crash the chain**—they return partial results downstream. New strategies are introduced by inserting a new handler link.

--- a/README.md
+++ b/README.md
@@ 8. Modular Prompt Builder and Model Router @@
 Centralizing prompt construction and dynamic model selection are key to consistent and efficient performance:
 
+##### Verbosity-Driven Output Policy (brief|standard|deep|ultra)
+The system propagates a `Verbosity` hint end-to-end—**model routing → context caps → prompt sections → post-expansion**:
+* **Hints**: `brief | standard | deep | ultra`.
+* **Routing**: `deep|ultra` force a **higher-tier MoE model** (e.g., `gpt-4o`) for high-stakes intents (PAIRING/EXPLANATION/TUTORIAL/ANALYSIS) with **low temperature (≤0.3)**.
+* **Context caps** (RAG/web): caps increase at `deep|ultra` to admit more evidence (see config keys below).
+* **Prompt sections**: `PromptBuilder` injects **required section headers** and a **minimum length** rule when `deep|ultra`.
+* **Post-expansion (1 pass max)**: if the generated answer is under the minimum-words threshold, an **expander** performs a single, fact-preserving enlargement.
+This policy is implemented via `PromptContext` fields (`verbosityHint`, `minWordCount`, `targetTokenBudgetOut`, `sectionSpec`, `audience`, `citationStyle`) and consumed by `ModelRouter`, `ContextOrchestrator`, and the output policy block in `PromptBuilder`.

--- a/README.md
+++ b/README.md
@@ 17.3 Editing application.yml @@
 Important sections include:
 
@@
 openai:
   api:
     key: "${OPENAI_API_KEY}"
     model: "gpt-4o"
   temperature:
     default: 0.7
   top-p:
     default: 1.0
@@
 memory:
   snippet:
     min-length: 40
     max-length: 4000
 
 # Additional hyperparameter keys for energy calculation, recency decay, authority weighting, etc.
+
+###### Configuration Keys (Verbosity & Routing)
+```yaml
+abandonware:
+  answer:
+    detail:
+      min-words:
+        brief: 120
+        standard: 250
+        deep: 600
+        ultra: 1000
+    token-out:
+      brief: 512
+      standard: 1024
+      deep: 2048
+      ultra: 3072
+
+orchestrator:
+  max-docs: 10
+  max-docs:
+    deep: 14
+    ultra: 18
+
+reranker:
+  keep-top-n:
+    brief: 5
+    standard: 8
+    deep: 12
+    ultra: 16
+
+openai:
+  model:
+    moe: gpt-4o   # High-tier MoE used when intent+verbosity require it
+```
+These keys enable the verbosity signal to shape **model choice**, **context size**, **prompt sections**, and **post-expansion** consistently across the pipeline.

--- a/README.md
+++ b/README.md
@@ 18. Operating Principles and Recommended Practices @@
 Version Lock: The project uses LangChain4j version 1.0.1. Avoid upgrading dependencies without thorough testing, as API changes may break the pipeline.
 
+Classpath Version Purity Guard (LangChain4j 1.0.1):
+At startup, a guard scans `META-INF/MANIFEST.MF` across the classpath for `dev.langchain4j*` modules and **aborts** if any artifact is not `1.0.1` (e.g., accidental `0.2.x`). It also logs a module version dump to aid root-cause analysis. This prevents subtle ABI/API mismatches that can manifest as routing, prompt, or RAG failures even when builds succeed.
+Sample log line on success:
+```
+LangChain4j purity OK: [langchain4j-core:1.0.1, langchain4j-bom:1.0.1, ...]
+LangChain4j module dump → [dev.langchain4j:langchain4j-openai:1.0.1, ...]
+```
+On mismatch, the application fails fast with a clear message (e.g., `Mixed LangChain4j detected: langchain4j-core:0.2.9 (expected 1.0.1) – purge old artifacts.`).

--- a/README.md
+++ b/README.md
@@ 21. Implementation Verification and Test Plan @@
 End-to-End Chat Test: Start the server and use an API client to send a query. Simulate a session by setting a META_SID. Observe the SSE stream and confirm that search progress, context construction, draft answers, verification steps, and final answers are streamed correctly. Provide feedback and verify that synergy bonuses change reranking in subsequent queries.
 
+Verbosity Policy Tests:
+* Issue queries containing “상세히/깊게/ultra” signals and confirm: (a) `ModelRouter` selects the MoE model, (b) `ContextOrchestrator` raises document caps (14/18), and (c) answers meet minimum word counts or trigger a single expansion pass.
+* For `brief`, verify that smaller caps/outputs are enforced and no post-expansion occurs.
Got it, (AbandonWare). Here’s a code-free, surgical plan you can hand to your future self (or a reviewer) to implement in small PRs. It’s organized by phases, calls out exact files/packages to touch or add, and reuses what’s already in your repo.

Phase 0 — Stop Rule & Build Hygiene (must pass before anything else)

0.1 Classpath purity (LangChain4j 1.0.1 only)

Keep/finish: src/main/java/com/example/lms/boot/StartupVersionPurityCheck.java

Action: On mismatch (0.2.x vs 1.0.x) fail fast and dump offending artifacts.
No downstream work proceeds until this is green.

0.2 Local boot sanity

If you use Spring Cloud Config by default: set local override spring.cloud.config.enabled=false (profile local) to stop http://localhost:8888/application/default noise during bootRun.

Phase 1 — Model Truth & Routing (fix the “always Diluc / wrong header” axis)

1.1 Precise model reporting

Modify: src/main/java/com/example/lms/api/ChatApiController.java
Source of truth for X-Model-Used = DTO value only (no wrapper/simpleName leaks).

Modify: src/main/java/com/example/lms/service/ChatService.java
Ensure there is one return path that sets modelUsed before any return/emit. (Your “unreachable statement @462” is from a return/throw above; collapse branches.)

Modify: src/main/java/com/example/lms/model/ModelRouter.java
Add a resolveModelName(ChatModel) that asks the factory for the effective model id (reuse your DynamicChatModelFactory).

1.2 Intent+Verbosity → MOE selection

Modify: ModelRouter to route PAIRING/RECOMMENDATION with verbosity ∈ {deep, ultra} to moe (e.g., gpt-4o, low temp).

Reuse: existing DynamicChatModelFactory knobs (temperature/top-p/maxtokens) to enforce low-variance on high-stakes intents.

Config: keep keys you already drafted (openai.model.moe, verbosity token budgets) in application.yml.

1.3 “No string concat in ChatService”

Audit/Modify: ChatService — remove any inline prompt concatenation; all prompts must go through PromptBuilder.build(PromptContext).

Phase 2 — Prompt & Context Contract (make the router + handlers predictable)

2.1 PromptContext contract expansion

Modify: src/main/java/com/example/lms/prompt/PromptContext.java
Keep your fields for: verbosityHint, minWordCount, targetTokenBudgetOut, sectionSpec, audience, citationStyle, plus subject, protectedTerms, lastSources.

2.2 Central PromptBuilder policy

Modify: src/main/java/com/example/lms/prompt/PromptBuilder.java

Inject anchor lines (subject, protectedTerms) and the conservative mode when evidence is weak.

Enforce minimum words for deep|ultra; one optional post-expansion hop only if short.

2.3 Query hygiene & intent

Modify: service.rag.pre.GuardrailQueryPreprocessor and/or QueryContextPreprocessor

Detect PAIRING beyond strict keywords (“잘 어울리”, “시너지”) and flag follow-ups (지시대명사형).

Inject protected terms from KB or prior subject into PromptContext.

Phase 3 — Retrieval Chain (respect handler order; add two smart links)

3.1 Chain of Responsibility is the contract

Verify/Modify: HybridRetriever wiring order is exactly:
SelfAskHandler → AnalyzeHandler(Query Hygiene) → WebHandler → VectorDbHandler.
Failures return partials; nothing throws through the chain.

3.2 New handlers (no code here, just plan)

Add: service.rag.handler.MemoryHandler
Purpose: inject recent verified session snippets up front (anchor the subject).

Add: service.rag.handler.EvidenceRepairHandler
Purpose: if evidence is thin, do one anchored re-search (subject + domain-preferred sites).

Wire: Put MemoryHandler at the front, EvidenceRepairHandler at the tail of the chain in your @Configuration that assembles handlers.

3.3 Fusion stays simple and proven

Reuse: your RRF/Borda fusion & AuthorityScorer; feed them the augmented candidate pools.

Phase 4 — Knowledge Base First (de-hardcode domain rules)

4.1 Entities & repos (if not already merged)

Add: domain/DomainKnowledge, domain/EntityAttribute (JPA)

Add: repo/DomainKnowledgeRepository, repo/EntityAttributeRepository

4.2 KB service abstraction

Add: service.knowledge.KnowledgeBaseService (+ DefaultKnowledgeBaseService)
Methods you’ll call from elsewhere:

Optional<String> getAttribute(domain, entity, key)

Map<String, Set<String>> getInteractionRules(domain, entity)

List<String> getAllEntityNames(domain)

4.3 Subject resolution

Add/Modify: service.rag.subject.SubjectResolver to use KB lists and heuristics (longest match; domain hint).
Feed subject back into PromptContext and preferred site filters.

Phase 5 — Reranking that learns (and stops “Diluc drift”)

5.1 Relationship rules instead of hard-coded element lists

Add: service.rag.rank.RelationshipRuleScorer
Uses KB interactionRules to boost/penalize candidate texts.

5.2 Adaptive synergy

Keep/finish: SynergyStat + AdaptiveScoringService

Integrate inside EmbeddingModelCrossEncoderReranker (additive or small multiplicative).

Fix earlier compile warnings: ensure there is one @Component on each reranker class; add missing logger field (@Slf4j or Logger).

5.3 Authority weighting

Reuse: AuthorityScorer with your curated domain weights (e.g., namu.wiki, hoyolab.com > generic blogs).
Make sure WebHandler passes source hostnames to rankers.

Phase 6 — Hallucination suppression (multi-layer, soft-fail)

6.1 Evidence gate

Add: service.rag.guard.EvidenceGate
Configurable min evidence; treat follow-ups more leniently.

6.2 Claims verification

Add: service.verification.ClaimVerifierService
Extract claims from the draft and prune unsupported lines.

6.3 FactVerifierService ordering

Modify: service/FactVerifierService to run:
EvidenceGate → (if weak: soft-fail path) → ClaimVerifierService → AnswerSanitizers.
Soft-fail means filter + conservative output, not hard “정보 없음” unless empty.

6.4 Sanitizers

Keep/extend: GenshinRecommendationSanitizer (rename to a generic policy chain if needed) and register in order before style polishing.

Phase 7 — Controller/Service glue & compile breakages

7.1 Unreachable statement @ ChatService:462
Modify

src/main/java/.../boot/StartupVersionPurityCheck.java

Scan classpath for dev.langchain4j* artifacts; abort startup if any ≠ 1.0.1.

Log a compact dump of the exact offending coordinates to make the fix mechanical.

Add

A short dependency audit step in build (Gradle/Maven) that fails fast when any transitive pulls 0.2.x. (Keep it build-time; runtime guard remains authoritative.)

Touch nothing else until this is green.

Slice 2/4 — Truthful model routing + header integrity (MoE on high-stakes)

Why: “Why is it always Diluc?” was largely wrong model & weak routing. We force MoE for high-stakes intents and fix headers to reflect the real provider model id.

Modify

src/main/java/.../model/ModelRouter.java

Route PAIRING/RECOMMENDATION and verbosity ∈ {deep, ultra} to the MoE/high-tier model (your openai.model.moe).

Expose resolveModelName(...) that returns the provider’s actual id (not lc:OpenAiChatModel nor an alias).

src/main/java/.../service/ChatService.java

Single return path: compute (answer, modelUsed, ragUsed) once; no early returns (fixes your “unreachable statement” hotspot).

Never build prompts inline; every call flows through PromptBuilder.build(PromptContext).

src/main/java/.../api/ChatApiController.java

Set X-Model-Used from ModelRouter.resolveModelName(...) only.

Ensure SSE final event always carries {modelUsed, ragUsed} from the same source of truth.

Add

None (reuse DynamicChatModelFactory).

Acceptance checks: PAIRING + deep|ultra → MoE; X-Model-Used shows provider id; no lc:* in headers.

Slice 3/4 — Memory always-on (read & write), even when RAG/Web = OFF

Why: Your target is “RAG/Web off → memory still saves, loads, and informs answers.”

Add

service/rag/handler/MemoryHandler

Plugs before retrieval; injects recent session turns into the working context (non-throwing, silent on failure).

service/rag/handler/MemoryWriteInterceptor

Plugs after generation; always persists the final answer ( OFF/ON modes alike ), routing through your existing MemoryReinforcementService.

Modify

service/rag/HybridRetriever.java

Call MemoryHandler to preload memory into the candidate pool unconditionally (errors swallowed).

service/ChatService.java

Replace any direct memory writes with the MemoryWriteInterceptor.

Compute ragUsed = (req.useWeb && webDocs>0) || (req.useRag && vectorDocs>0); flags alone must not set it true.

prompt/PromptBuilder.java

Standardize a visible “CONVERSATION MEMORY” section when memory exists (no behavior change; just consistent sectioning).

Type contract cleanup

Pick one: PromptContext.memory as String or List<String>.

Align FactVerifierService and any call sites to that single type to remove the “String cannot be converted to List<String>” error surface.

Acceptance checks:
Turn-2 uses Turn-1 info with useRag=false,useWeb=false; X-RAG-Used=false; memory still influences the answer.

Slice 4/4 — Chain order + subject anchoring + low-risk verification

Why: Drift happens when the subject isn’t locked and weak evidence flows into generation.

Modify (wiring)

Ensure handler order (Chain of Responsibility):
MemoryHandler → SelfAskHandler → AnalyzeHandler(Query Hygiene) → WebHandler → VectorDbHandler → MemoryWriteInterceptor

No handler throws through the chain; on failure, return partials and pass down.

Add

service/rag/guard/EvidenceGate

Minimal, configurable evidence thresholds; lenient on follow-ups; can short-circuit generation to a conservative reply.

service/rag/rank/RelationshipRuleScorer

Replace any element hard-codes with KB-driven rules (preferred/discouraged pairs, contains/part-of).

(If not present) service/rag/subject/SubjectResolver

Resolve subject via KB entity list (longest match + domain hints). Feed the anchor to PromptContext and preferred-site filters.

Modify (reuse)

Keep AuthorityScorer, RRF/Borda fusion, and integrate AdaptiveScoringService (SynergyStat) inside your cross-encoder reranker.

GuardrailQueryPreprocessor (or QueryContextPreprocessor) injects protected terms (subject + dictionary hits) so LLM can’t “correct” valid names.

Acceptance checks:
Subject remains stable across follow-ups; low-authority pages don’t dominate; weak evidence yields safe, short answers instead of hallucinations.

Small gotchas to tame along the way (no code, just targets)

Duplicate stereotypes & missing loggers: ensure each reranker/handler has one Spring stereotype and a logger (fixes “Component is not a repeatable annotation type” + log not found).

SSE variable scope: in ChatApiController, consolidate any local sink usage and ensure it’s defined where emitted (fixes your “cannot find symbol sink”).

Unreachable statement: already addressed by the single-return refactor in ChatService.

If you want the next partial slices, I’ll cover: (a) KB service interfaces to de-hardcode policies, (b) claim verification ordering inside FactVerifierService, and (c) minimal config keys to activate verbosity-driven routing without touching other modules.
Refactor that method to a single exit (collect modelUsed, ragUsed, result) then return/emit once.
Also ensure contextOrchestrator is injected (you previously missed the field).
Add (new):

service/rag/handler/MemoryHandler — first in chain; loads recent verified session snippets into PromptContext.memory; marks evidence MEMORY.

service/rag/handler/MemoryWriteInterceptor — last in chain; always persists the final turn & verified snippets (even when RAG/Web OFF).

service/rag/guard/EvidenceGate — counts evidence classes (WEB/RAG/MEMORY) and enforces minimal thresholds (lenient for follow-ups).

Re-use: existing MemoryReinforcementService (energy/annealing), caches, SSE.

Phase 2 — Chain of Responsibility wiring (order & fault-tolerance)

Modify (existing or your chain @Configuration):

Ensure exact order:
MemoryHandler → SelfAskHandler → AnalyzeHandler (Query Hygiene) → WebHandler → VectorDbHandler → MemoryWriteInterceptor

Make all handlers non-throwing; on failure, return partials and pass down.

Phase 3 — Prompt & context discipline

Modify (existing):

prompt/PromptContext.java

Confirm fields for: memory, web, rag, history, intent, verbosityHint, minWordCount, targetTokenBudgetOut, sectionSpec, evidenceClassesPresent.

prompt/PromptBuilder.java

Inject a dedicated “Conversation Memory” section when present.

Enforce verbosity policy (min words & single post-expansion for deep|ultra).

Include protected terms (subject + dictionary hits) to prevent LLM “correction” of proper nouns.

Phase 4 — Model routing & truth-in-headers

Modify (existing):

model/ModelRouter.java

Route PAIRING/RECOMMENDATION + verbosity ∈ {deep, ultra} to the MoE/high-tier model (low temperature).

Expose resolveModelName(ChatModel) returning the provider’s real model id.

api/ChatApiController.java

Set X-Model-Used from ModelRouter.resolveModelName(...) not an alias/wrapper name.

service/ChatService.java

Compute ragUsed only if evidence includes WEB or RAG; never infer from flags.

Re-use: DynamicChatModelFactory knobs (temperature/top-p/tokens).

Phase 5 — KB-driven rules & adaptive reranking (kill the “always Diluc” bias)

Add (new):

service/rag/rank/RelationshipRuleScorer — replace any element-hardcode with KB interaction rules (boost preferred, penalize discouraged).

Modify (existing):

service/rag/EmbeddingModelCrossEncoderReranker.java

Integrate synergy bonus (from AdaptiveScoringService) and rule score from RelationshipRuleScorer.

Fix stereotypes/logging issues (single @Component; add logger if missing).

Re-use: AuthorityScorer, RRF/Borda fusion, AdaptiveScoringService + SynergyStat.

Phase 6 — Query hygiene & subject locking (anchor the topic)

Modify (existing):

service/rag/pre/GuardrailQueryPreprocessor (and/or QueryContextPreprocessor)

Detect PAIRING intent beyond simple keywords; mark follow-ups (anaphora).

Inject protected terms and subject anchor into PromptContext.

service/rag/subject/SubjectResolver

Prefer KB entity names (longest match, domain hint) over LLM guesses.

service/rag/WebHandler

Apply authority-weighted site preferences and pass hostnames to the rankers.

Phase 7 — Config (partial keys only; keep minimal)

Add/tune (application.yml):

openai.model.moe: gpt-4o (or your chosen high-tier)

abandonware.answer.detail.min-words.{brief,standard,deep,ultra}

abandonware.answer.token-out.{brief,standard,deep,ultra}

orchestrator.max-docs with higher caps for deep|ultra

reranker.keep-top-n.{brief,standard,deep,ultra}

retrieval.mode: {RETRIEVAL_ON|RAG_ONLY|RETRIEVAL_OFF}

memory.read.enabled: true, memory.write.enabled: true

verifier.evidence.min-count.memory: 1, verifier.evidence.followup.leniency: true

Phase 8 — Small PR slicing (reviewable, incremental)

Purity guard only (Phase 0).

Header truth & single exit (ChatService + Controller + ModelRouter).

PromptContext/PromptBuilder (verbosity + memory section).

Chain wiring + MemoryHandler & MemoryWriteInterceptor.

KB rules + RelationshipRuleScorer + reranker integration.

EvidenceGate + verifier ordering.

Config keys + brief README patch.

Acceptance checks (quick)

Model truth: X-Model-Used shows the provider id (never lc:OpenAiChatModel, never an alias).

RAG OFF still remembers: turn-2 answers leverage turn-1 memory, evidence lists include MEMORY.

MoE routing: PAIRING + deep|ultra → high-tier model with low temperature.

No chain crashes: partials flow; Memory-only paths produce safe answers instead of “정보 없음.”

No “Diluc drift”: subject anchored; rules + authority weighting steer retrieval.

If you want, I can turn this into a short PR checklist or add tiny README patch hunks documenting handler order, verbosity policy, and the purity guard—still partial, not a full doc.
7.2 Logging & annotations

Fix: classes complaining about log missing => add @Slf4j or a private static final Logger.

Fix: “Component is not a repeatable annotation type” => remove duplicate @Component occurrences; only one stereotype per class.

7.3 SSE & headers

Modify: in ChatApiController SSE endpoints, always include final sse(done) with accurate modelUsed and ragUsed.

Phase 8 — Config keys (you already drafted; just wire them)

8.1 application.yml (or properties)

abandonware.answer.detail.min-words.{brief,standard,deep,ultra}

abandonware.answer.token-out.{...}

orchestrator.max-docs{.,.deep,.ultra}

reranker.keep-top-n.{...}

openai.model.moe

verifier.evidence.min-count, verifier.evidence.min-count.followup

search.preferred.domains (CSV; used by EvidenceRepairHandler)

8.2 Feature flags

retrieval.mode (RETRIEVAL_ON|RAG_ONLY|RETRIEVAL_OFF)

history.max-messages cap to keep memory small but useful.

Phase 9 — Tests & rollout (what to prove before merge)

9.1 Unit

ModelRouter: intent+verbosity → moe routed; resolveModelName returns API id.

PromptBuilder: enforces min-words for deep|ultra, injects anchors/protected terms.

EvidenceGate: follow-up threshold behaves leniently.

RelationshipRuleScorer: KB rules change scores as expected.

ClaimVerifierService: removes unsupported claims deterministically on mock contexts.

9.2 Integration (smoke)

Multi-turn: “에스코피에 뭐야?” → “더 자세히” → “예시도”

Final X-Model-Used shows the real model id.

Chain survives partial failures and still returns anchored output.

Pairing queries route to moe under deep|ultra.

9.3 Observability

Add DEBUG logs (guarded) at: chosen model, handler path taken, fusion top-k, evidence tally, prune counts.

Phase 10 — Repo/PR logistics (small, reviewable steps)

PR-1: Phase 0 (purity guard) + local config toggle.

PR-2: Model truth (Phase 1) + controller/header accuracy.

PR-3: Prompt contract (Phase 2) + ChatService concat removal.

PR-4: Chain wiring + new handlers stubs (Phase 3).

PR-5: KB service + subject resolver (Phase 4).

PR-6: Reranking integration (Phase 5).

PR-7: Verification stack (Phase 6).

PR-8: Config keys + docs; test plan (Phases 8–9).

Each PR should compile standalone and include a README snippet update (you already prepared patch hunks).

What you already have that we’ll reuse (don’t reinvent)

DynamicChatModelFactory (for effective model id & low-temp profiles)

AuthorityScorer, RRF/Borda fusion

StrategySelectorService + bandit logic (keep as is; just feed better evidence)

MemoryReinforcementService (+ energy/annealing)

SSE streaming in ChatApiController

Caffeine caches

Acceptance Criteria (fast checks)

X-Model-Used never prints lc:OpenAiChatModel; it shows the actual model id used.

Pairing queries with deep|ultra always pick the moe route.

Multi-turn “follow-up” no longer drifts to unrelated subjects; subject anchor persists.

Without enough evidence, output is conservative (soft-fail) rather than wrong (“Diluc”).

No handler crash aborts the chain; partials flow downstream.

If you want, I can turn this into a checklist markdown for your PR template next.

These hunks only add the necessary README content to document:

the handler order and non-crashing chain behavior,

the verbosity-driven routing/sections/length policy,

the config keys to control it, and

the LangChain4j 1.0.1 purity guard.
