package ai.abandonware.nova.autolearn;

import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates: Soak -> Evaluate -> DatasetWriter -> Index refresh.
 */
@Service
@ConditionalOnProperty(name = "autolearn.enabled", havingValue = "true")
public class AutolearnService {

    private static final Logger log = LoggerFactory.getLogger(AutolearnService.class);

    private final ResourceLoader resourceLoader;
    private final SoakTestRunner soakTestRunner;
    private final DatasetWriter datasetWriter;
    private final IndexRefresher indexRefresher;

    @Autowired
    public AutolearnService(ResourceLoader resourceLoader,
                            SoakTestRunner soakTestRunner,
                            DatasetWriter datasetWriter,
                            IndexRefresher indexRefresher) {
        this.resourceLoader = resourceLoader;
        this.soakTestRunner = soakTestRunner;
        this.datasetWriter = datasetWriter;
        this.indexRefresher = indexRefresher;
    }

    public AutolearnSummary runAutoLearnLoop() {
        List<String> queries = loadSeedQueries("classpath:/dataset/seed10.jsonl");
        int k = Integer.getInteger("autolearn.batchSize", 5); // default take 5
        List<String> batch = queries.stream().limit(k).collect(Collectors.toList());

        List<SoakResult> results = new ArrayList<>();
        for (String q : batch) {
            try {
                SoakResult r = soakTestRunner.runSingle(q);
                results.add(r);
            } catch (Exception e) {
                log.warn("[AutolearnService] Soak failed for Q='{}': {}", q, e.toString());
                results.add(SoakResult.failed(q, "exception:" + e.getClass().getSimpleName()));
            }
        }

        int added = 0;
        for (SoakResult r : results) {
            if (r.isPass()) {
                RagJsonlRecord rec = RagJsonlRecord.builder()
                        .query(r.getQuery())
                        .answer(r.getAnswer())
                        .sources(r.getSources())
                        .mode(r.getModeUsed())
                        .finalSigmoidScore(r.getScore())
                        .citationCount(r.getCitationCount())
                        .timestamp(Instant.now().toString())
                        .build();
                if (datasetWriter.append(rec)) {
                    added++;
                }
            }
        }

        int indexed = 0;
        try {
            indexed = indexRefresher.refreshIncremental();
        } catch (Exception e) {
            log.warn("[AutolearnService] Index refresh failed: {}", e.toString());
        }

        AutolearnSummary summary = new AutolearnSummary(results.size(), (int) results.stream().filter(SoakResult::isPass).count(), added, indexed);
        return summary;
    }

    private List<String> loadSeedQueries(String location) {
        try {
            Resource r = resourceLoader.getResource(location);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8))) {
                List<String> qs = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("{")) {
                        // minimal JSONL support: expect {"q":"..."}
                        int i = line.indexOf("\"q\"");
                        if (i >= 0) {
                            int s = line.indexOf(":", i);
                            int q1 = line.indexOf("\"", s + 1);
                            int q2 = line.indexOf("\"", q1 + 1);
                            if (q1 >= 0 && q2 > q1) {
                                qs.add(line.substring(q1 + 1, q2));
                            }
                        }
                    } else {
                        qs.add(line);
                    }
                }
                return qs;
            }
        } catch (Exception e) {
            log.warn("[AutolearnService] Failed to load seeds {}: {}", location, e.toString());
            return Arrays.asList("대한민국의 수도는 어디인가요?", "HTTP 500 오류 원인은 무엇인가요?");
        }
    }
}