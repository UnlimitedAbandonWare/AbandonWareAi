package com.example.lms.moe;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a minimal RGB soak report and writes it to disk.
 */
@Service
public class RgbSoakReportService {

    private static final Logger log = LoggerFactory.getLogger(RgbSoakReportService.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final UnifiedRagOrchestrator orchestrator;
    private final ObjectMapper om;
    private final RgbMoeProperties props;

    public RgbSoakReportService(UnifiedRagOrchestrator orchestrator,
                               ObjectMapper om,
                               RgbMoeProperties props) {
        this.orchestrator = orchestrator;
        this.om = om;
        this.props = props;
    }

    public RgbSoakReport run(String sessionId,
                             List<String> queries,
                             RgbStrategySelector.Decision decision,
                             int blueCalls,
                             boolean writeFile) {
        return run(sessionId, queries, decision, blueCalls, writeFile, null);
    }

    /**
     * Same as {@link #run(String, List, RgbStrategySelector.Decision, int, boolean)} but allows
     * additional debug payload to be embedded into the report.
     */
    public RgbSoakReport run(String sessionId,
                             List<String> queries,
                             RgbStrategySelector.Decision decision,
                             int blueCalls,
                             boolean writeFile,
                             Map<String, Object> extraDebug) {

        Instant started = Instant.now();

        Map<String, RgbSoakMetrics> metricsByStrategy = new HashMap<>();
        Map<String, Object> debug = new HashMap<>();
        debug.put("blueCalls", blueCalls);

        if (decision != null) {
            debug.put("reasons", decision.reasons());
            if (decision.scoreCard() != null) {
                debug.put("scoreCard", decision.scoreCard());
            }
        }

        if (extraDebug != null && !extraDebug.isEmpty()) {
            debug.putAll(extraDebug);
        }

        // Evaluate primary + up to 2 fallbacks (avoid long runtimes)
        int evaluated = 0;
        if (decision != null) {
            evaluated += evalStrategy(metricsByStrategy, queries, decision.primaryStrategy());
            for (RgbStrategySelector.Strategy fb : decision.fallbackStrategies()) {
                if (evaluated >= 3) break;
                evaluated += evalStrategy(metricsByStrategy, queries, fb);
            }
        }

        Instant ended = Instant.now();

        RgbSoakReport report = new RgbSoakReport(
                sessionId,
                started,
                ended,
                decision == null ? null : String.valueOf(decision.primaryStrategy()),
                decision == null ? List.of() : decision.fallbackStrategies().stream().map(String::valueOf).toList(),
                decision == null ? List.of() : decision.reasons(),
                queries,
                metricsByStrategy,
                debug
        );

        if (writeFile) {
            Path out = writeReportFile(report);
            if (out != null) {
                debug.put("reportFile", out.toString());
            }
        }

        log.info("[RGB] soak report saved: strategies={} queries={} blueCalls={}",
                metricsByStrategy.keySet(),
                queries == null ? 0 : queries.size(),
                blueCalls);

        return report;
    }

    private int evalStrategy(Map<String, RgbSoakMetrics> out,
                             List<String> queries,
                             RgbStrategySelector.Strategy strategy) {
        if (strategy == null || out == null || queries == null || queries.isEmpty()) return 0;

        boolean useWeb = true;
        boolean useVector = true;
        boolean enableOnnx = true;
        boolean enableBi = true;

        // conservative deltas (only offline soak):
        switch (strategy) {
            case G_ONLY -> {
                // lighter: reduce heavy reranks
                enableOnnx = false;
                enableBi = false;
            }
            case B_ONLY -> {
                // BLUE isn't used for search itself; keep defaults.
            }
            case RG_ENSEMBLE, GB_FALLBACK, RB_ENSEMBLE, RGB_ENSEMBLE, R_ONLY -> {
                // default
            }
        }

        long totalLatencyMs = 0L;
        int calls = 0;
        int hits = 0;
        int docCount = 0;
        int evidenceCount = 0;
        int fallbackCount = 0;

        for (String q : queries) {
            if (q == null || q.isBlank()) continue;
            UnifiedRagOrchestrator.QueryRequest req = new UnifiedRagOrchestrator.QueryRequest();
            req.query = q;
            req.topK = 8;
            req.useWeb = useWeb;
            req.useVector = useVector;
            req.useKg = false;
            req.useBm25 = false;
            req.enableOnnx = enableOnnx;
            req.enableBiEncoder = enableBi;
            req.enableDiversity = true;
            req.aggressive = false;
            req.planId = "rgb.soak." + strategy.name();

            long t0 = System.nanoTime();
            UnifiedRagOrchestrator.QueryResponse r = orchestrator.query(req);
            long dtMs = (System.nanoTime() - t0) / 1_000_000L;
            totalLatencyMs += dtMs;
            calls++;

            if (r != null && r.results != null && !r.results.isEmpty()) {
                hits++;
                for (UnifiedRagOrchestrator.Doc d : r.results) {
                    if (d == null) continue;
                    docCount++;
                    boolean hasSnippet = d.snippet != null && !d.snippet.isBlank();
                    boolean hasUrl = d.meta != null && d.meta.get("url") != null && !String.valueOf(d.meta.get("url")).isBlank();
                    if (hasSnippet || hasUrl) evidenceCount++;
                }
            }

            if (r != null && r.debug != null && r.debug.containsKey("fallback")) {
                fallbackCount++;
            }
        }

        double hitRate = calls <= 0 ? 0.0 : ((double) hits / (double) calls);
        double evidence = docCount <= 0 ? 0.0 : ((double) evidenceCount / (double) docCount);
        double avgLatency = calls <= 0 ? 0.0 : ((double) totalLatencyMs / (double) calls);
        double fallbackRate = calls <= 0 ? 0.0 : ((double) fallbackCount / (double) calls);

        // BLUE calls are tracked at the runner (not here)
        RgbSoakMetrics m = new RgbSoakMetrics(calls, hitRate, evidence, avgLatency, calls, 0, fallbackRate);
        out.put(strategy.name(), m);
        return 1;
    }

    private Path writeReportFile(RgbSoakReport report) {
        try {
            String dir = props.getSoakReportDir();
            if (dir == null || dir.isBlank()) dir = "./soak_reports";

            Path outDir = Path.of(dir);
            Files.createDirectories(outDir);

            LocalDate d = LocalDate.ofInstant(report.startedAt(), ZoneId.systemDefault());
            String name = YYYYMMDD.format(d) + "_rgb.json";
            Path out = outDir.resolve(name);

            om.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
            return out;
        } catch (Exception e) {
            log.warn("[RGB] failed to write report file: {}", e.getMessage());
            return null;
        }
    }
}
