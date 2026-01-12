package ai.abandonware.nova.orch.storage;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import com.example.lms.service.MemoryReinforcementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Periodically consumes (drains) pending events stored in {@link DegradedStorage} and
 * tries to promote them into memory via {@link MemoryReinforcementService}.
 *
 * <p>Fail-soft by design.
 */
public class DegradedStorageDrainer {

    private static final Logger log = LoggerFactory.getLogger(DegradedStorageDrainer.class);

    private static final Pattern HAS_URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_W_CITATION = Pattern.compile("\\[W\\d+\\]", Pattern.CASE_INSENSITIVE);

    private final DegradedStorage storage;
    private final MemoryReinforcementService memory;
    private final NovaOrchestrationProperties props;
    private final Environment env;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public DegradedStorageDrainer(
            DegradedStorage storage,
            MemoryReinforcementService memory,
            NovaOrchestrationProperties props,
            Environment env
    ) {
        this.storage = storage;
        this.memory = memory;
        this.props = props;
        this.env = env;
    }

    @Scheduled(
            initialDelayString = "${nova.orch.degraded-storage.drain.initial-delay-ms:60000}",
            fixedDelayString = "${nova.orch.degraded-storage.drain.fixed-delay-ms:300000}"
    )
    public void tick() {
        if (storage == null || memory == null || props == null) {
            return;
        }

        NovaOrchestrationProperties.DegradedStorageProps ds = props.getDegradedStorage();
        if (ds == null || ds.getDrain() == null || !ds.getDrain().isEnabled()) {
            return;
        }

        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            int batch = Math.max(1, ds.getDrain().getBatchSize());

            // MERGE_HOOK:PROJ_AGENT::DEGRADED_OUTBOX_PARTIAL_ACK_V1
            // If the storage supports claim/ack semantics, we can ack successful items and keep only failures.
            if (storage instanceof DegradedStorageWithAck ackable) {
                // best-effort retention enforcement & stuck inflight recovery
                try {
                    ackable.sweep();
                } catch (Exception ignore) {
                }

                List<DegradedStorageWithAck.ClaimedPending> claimed = ackable.claim(batch);
                if (claimed == null || claimed.isEmpty()) {
                    return;
                }

                boolean memoryEnabled = getBool("memory.enabled", false);
                if (!memoryEnabled) {
                    // no promotion possible; release everything (do NOT count as failures)
                    for (DegradedStorageWithAck.ClaimedPending c : claimed) {
                        if (c == null) {
                            continue;
                        }
                        try {
                            ackable.release(c.token());
                        } catch (Exception ignore) {
                        }
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("[degraded-drain] memory.enabled=false; released {} claimed items", claimed.size());
                    }
                    return;
                }

                int promoted = 0;
                int released = 0;
                int failed = 0;

                for (DegradedStorageWithAck.ClaimedPending c : claimed) {
                    if (c == null || c.event() == null) {
                        continue;
                    }

                    PendingMemoryEvent e = c.event();
                    String snippet = sanitizeSnippet(e.answerSnippet());
                    if (!shouldPromote(ds.getDrain(), snippet)) {
                        try {
                            ackable.release(c.token());
                        } catch (Exception ignore) {
                        }
                        released++;
                        continue;
                    }

                    try {
                        String query = (e.userQueryHash() == null || e.userQueryHash().isBlank())
                                ? "qhash:unknown"
                                : "qhash:" + e.userQueryHash();

                        double score = scoreFor(ds.getDrain(), snippet);
                        memory.reinforceWithSnippet(e.sessionKey(), query, snippet, "degraded:" + e.reason(), score);
                        try {
                            ackable.ack(c.token());
                        } catch (Exception ignore) {
                        }
                        promoted++;
                    } catch (Exception ex) {
                        try {
                            ackable.nack(c.token(), ex.toString());
                        } catch (Exception ignore) {
                            // if nack fails, the item will remain inflight and should be recovered by sweep.
                        }
                        failed++;
                        log.warn("[degraded-drain] promote failed; kept in outbox. reason={} err={}", e.reason(), ex.toString());
                    }
                }

                if (promoted > 0 || released > 0 || failed > 0) {
                    log.info("[degraded-drain] claimed={} promoted={} released={} failed={}", claimed.size(), promoted, released, failed);
                }

                return;
            }

            // Fallback: legacy drain() + requeue
            List<PendingMemoryEvent> drained = storage.drain(batch);
            if (drained == null || drained.isEmpty()) {
                return;
            }

            boolean memoryEnabled = getBool("memory.enabled", false);
            if (!memoryEnabled) {
                // no promotion possible; requeue everything
                for (PendingMemoryEvent e : drained) {
                    if (e != null) {
                        storage.putPending(e);
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[degraded-drain] memory.enabled=false; requeued {} events", drained.size());
                }
                return;
            }

            int promoted = 0;
            int requeued = 0;

            for (PendingMemoryEvent e : drained) {
                if (e == null) {
                    continue;
                }

                String snippet = sanitizeSnippet(e.answerSnippet());
                if (!shouldPromote(ds.getDrain(), snippet)) {
                    storage.putPending(e);
                    requeued++;
                    continue;
                }

                try {
                    String query = (e.userQueryHash() == null || e.userQueryHash().isBlank())
                            ? "qhash:unknown"
                            : "qhash:" + e.userQueryHash();

                    double score = scoreFor(ds.getDrain(), snippet);
                    memory.reinforceWithSnippet(e.sessionKey(), query, snippet, "degraded:" + e.reason(), score);
                    promoted++;
                } catch (Exception ex) {
                    storage.putPending(e);
                    requeued++;
                    log.warn("[degraded-drain] promote failed; requeued. reason={} err={}", e.reason(), ex.toString());
                }
            }

            if (promoted > 0 || requeued > 0) {
                log.info("[degraded-drain] drained={} promoted={} requeued={}", drained.size(), promoted, requeued);
            }
        } finally {
            running.set(false);
        }
    }

    private boolean getBool(String key, boolean def) {
        try {
            if (env == null) {
                return def;
            }
            Boolean v = env.getProperty(key, Boolean.class);
            return v == null ? def : v;
        } catch (Exception ignore) {
            return def;
        }
    }

    private static boolean shouldPromote(NovaOrchestrationProperties.DegradedStorageProps.DrainProps dp, String snippet) {
        if (dp == null) {
            return false;
        }
        if (snippet == null) {
            return false;
        }

        String s = snippet.trim();
        if (s.length() < Math.max(1, dp.getMinSnippetLen())) {
            return false;
        }

        if (!dp.isRequireEvidence()) {
            return true;
        }

        return HAS_URL.matcher(s).find() || HAS_W_CITATION.matcher(s).find();
    }

    private static double scoreFor(NovaOrchestrationProperties.DegradedStorageProps.DrainProps dp, String snippet) {
        if (snippet == null) {
            return 0.65d;
        }
        boolean evid = HAS_URL.matcher(snippet).find() || HAS_W_CITATION.matcher(snippet).find();
        return evid ? 0.80d : 0.65d;
    }

    private static String sanitizeSnippet(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();

        // Avoid reinforcing the banner itself.
        if (s.startsWith("※ [DEGRADED MODE]")) {
            int idx = s.indexOf("\n\n");
            if (idx > 0 && idx + 2 < s.length()) {
                s = s.substring(idx + 2).trim();
            }
        }

        // cap size
        if (s.length() > 1200) {
            s = s.substring(0, 1200);
        }

        return s.trim();
    }
}
