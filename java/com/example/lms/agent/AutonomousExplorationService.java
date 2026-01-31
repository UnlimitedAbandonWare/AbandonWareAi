package com.example.lms.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.lms.agent.KnowledgeGapLogger.GapEvent;
import com.example.lms.dto.learning.EvidenceSnippet;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.learning.gemini.GeminiCurationService;
import com.example.lms.search.QueryExpander;
import com.example.lms.search.SmartQueryPlanner;
import com.example.lms.service.rag.HybridRetriever;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;



/**
 * The AutonomousExplorationService periodically analyses logged knowledge gap events and proactively
 * performs internal research to fill those gaps.  It uses the existing SmartQueryPlanner,
 * QueryExpander and HybridRetriever to formulate and execute searches without direct user
 * interaction.  The retrieved evidence is passed into the Gemini curation pipeline to extract
 * structured knowledge and update the knowledge base.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.autonomous-exploration", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AutonomousExplorationService {
    private static final Logger log = LoggerFactory.getLogger(AutonomousExplorationService.class);

    private final KnowledgeGapLogger gapLogger;
    private final SmartQueryPlanner planner;
    private final QueryExpander expander;
    private final HybridRetriever hybridRetriever;
    private final GeminiCurationService geminiCurationService;
    private final FreeTierApiThrottleService throttleService;

    /**
     * Scheduled method that processes one knowledge gap at a time.  Runs periodically with
     * configurable delay.  If no gaps are recorded or the throttle denies API usage, the cycle
     * exits early.
     */
    @Scheduled(initialDelayString = "${agent.autonomous-exploration.initial-delay-ms:60000}",
            fixedDelayString = "${agent.autonomous-exploration.period-ms:900000}")
    public void explore() {
        try {
            Optional<KnowledgeGapLogger.GapEvent> maybeGap = gapLogger.poll();
            if (maybeGap.isEmpty()) {
                return;
            }
            KnowledgeGapLogger.GapEvent gap = maybeGap.get();
            log.info("[AutonomousExploration] Exploring knowledge gap: query='{}', domain='{}', subject='{}'", gap.getQuery(), gap.getDomain(), gap.getSubject());

            // 1. Plan core queries based on the gap query
            List<String> coreQueries = planner.plan(gap.getQuery());
            if (coreQueries == null || coreQueries.isEmpty()) {
                coreQueries = List.of(gap.getQuery());
            }
            // 2. Expand the queries with additional keywords.  We do not yet have snippets at this point,
            //    so pass an empty list to the expander.  The expander caches results and returns
            //    short keyword suggestions.
            Set<String> allQueries = new LinkedHashSet<>(coreQueries);
            for (String q : coreQueries) {
                try {
                    allQueries.addAll(expander.expand(q, List.of()));
                } catch (Exception e) {
                    log.warn("[AutonomousExploration] Query expansion failed for '{}': {}", q, e.toString());
                }
            }
            // 3. Perform hybrid retrieval across web and vector DB sources
            List<Content> results;
            try {
                results = hybridRetriever.retrieveAll(new ArrayList<>(allQueries), 5);
            } catch (Exception e) {
                log.warn("[AutonomousExploration] Retrieval failed: {}", e.toString());
                return;
            }
            if (results == null || results.isEmpty()) {
                log.info("[AutonomousExploration] No evidence found for gap '{}'", gap.getQuery());
                return;
            }
            // 4. Convert results into evidence snippets.  The third-party Content type does not expose
            //    rich metadata in our current dependency, so we conservatively use its string
            //    representation as the evidence text and leave URL/title blank.  All snippets are
            //    marked with an "UNVERIFIED" credibility tier.
            List<EvidenceSnippet> evidence = new ArrayList<>();
            for (Content c : results) {
                try {
                    String text = c == null ? "" : c.toString();
                    evidence.add(new EvidenceSnippet("", "", text, "UNVERIFIED"));
                } catch (Exception e) {
                    evidence.add(new EvidenceSnippet("", "", "", "UNVERIFIED"));
                }
            }
            // 5. Build a learning event to feed into the curation pipeline.  We provide an empty
            //    finalized answer since this is an internal research task; coverage and contradiction
            //    are set to zero.  Session id uses a timestamp to avoid collisions.
            LearningEvent event = new LearningEvent(
                    "auto-" + Instant.now().toEpochMilli(),
                    gap.getQuery(),
                    "", // no finalized answer; this will be replaced by curation
                    evidence,
                    List.of(),
                    0.0,
                    0.0
            );
            // 6. Ensure we stay within the free-tier limits before calling the Gemini API
            if (throttleService != null && !throttleService.canProceed()) {
                log.info("[AutonomousExploration] Skipping curation due to throttle limits");
                return;
            }
            // 7. Invoke the curation service.  Best-effort: any exceptions are logged and do not
            //    propagate.
            try {
                geminiCurationService.ingest(event);
            } catch (Exception e) {
                log.warn("[AutonomousExploration] Curation failed: {}", e.toString());
            }
        } catch (Exception ex) {
            log.error("[AutonomousExploration] Unexpected error", ex);
        }
    }
}