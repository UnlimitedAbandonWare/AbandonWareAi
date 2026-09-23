package com.example.lms.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ScheduledJobGovernanceContractTest {

    private static final Path ACTIVE_SOURCE_ROOT = Path.of("main/java");
    private static final Pattern SCHEDULED_LINE = Pattern.compile("(?m)^\\s*@Scheduled\\b");
    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");
    private static final Pattern TYPE_LINE = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+)?(?:class|interface|enum|record)\\s+([A-Za-z0-9_]+)");
    private static final List<String> REQUIRED_FIELDS = List.of(
            "owner",
            "enablement",
            "schedule",
            "sideEffect",
            "defaultBehavior",
            "noClassGateReason");

    @Test
    void activeScheduledClassesAreRepresentedInGovernanceManifest() throws Exception {
        SortedMap<String, Path> scheduledClasses = scheduledClasses();
        assertFalse(scheduledClasses.isEmpty(), "expected at least one active @Scheduled class under main/java");

        Properties manifest = new Properties();
        try (StringReader reader = new StringReader(MANIFEST_TEXT)) {
            manifest.load(reader);
        }

        Set<String> manifestClasses = new TreeSet<>(manifest.stringPropertyNames());
        Set<String> missing = new TreeSet<>(scheduledClasses.keySet());
        missing.removeAll(manifestClasses);
        assertEquals(Set.of(), missing, "scheduled classes missing governance manifest entries");

        Set<String> stale = new TreeSet<>(manifestClasses);
        stale.removeAll(scheduledClasses.keySet());
        assertEquals(Set.of(), stale, "governance manifest entries no longer have active @Scheduled classes");

        for (String className : scheduledClasses.keySet()) {
            Map<String, String> fields = parseEntry(manifest.getProperty(className));
            Set<String> missingFields = new TreeSet<>(REQUIRED_FIELDS);
            missingFields.removeAll(fields.keySet());
            assertEquals(Set.of(), missingFields, className + " governance entry is missing fields");
            REQUIRED_FIELDS.forEach(field -> assertFalse(fields.get(field).isBlank(),
                    className + " has blank governance field: " + field));
        }
    }

    @Test
    void indexingSchedulerRequiresExplicitOwnerFlag() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/IndexingScheduler.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("ConditionalOnProperty"),
                "IndexingScheduler writes vector/memory paths and must be owner-gated");
        assertTrue(source.contains("name = \"indexing.scheduler.enabled\""),
                "IndexingScheduler owner flag must be indexing.scheduler.enabled");
        assertTrue(source.contains("havingValue = \"true\""),
                "IndexingScheduler should run only when the owner flag is true");
        assertTrue(source.contains("matchIfMissing = false"),
                "IndexingScheduler must be disabled by default");
    }

    @Test
    void vectorStoreBufferSchedulerRequiresExplicitOwnerFlag() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/VectorStoreBufferScheduler.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("ConditionalOnProperty"),
                "VectorStoreBufferScheduler flushes vector writes and must be owner-gated");
        assertTrue(source.contains("name = \"vector.flush.scheduler.enabled\""),
                "VectorStoreBufferScheduler owner flag must be vector.flush.scheduler.enabled");
        assertTrue(source.contains("havingValue = \"true\""),
                "VectorStoreBufferScheduler should run only when the owner flag is true");
        assertTrue(source.contains("matchIfMissing = false"),
                "VectorStoreBufferScheduler must be disabled by default");
    }

    @Test
    void adaptiveTranslationScheduledTuningRequiresExplicitOwnerFlag() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/AdaptiveTranslationService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("@Value(\"${translate.tuning.scheduler.enabled:false}\")"),
                "AdaptiveTranslationService scheduled tuning must have an explicit owner flag");
        assertTrue(source.contains("private boolean scheduledTuningEnabled;"),
                "AdaptiveTranslationService scheduled tuning flag should be stored separately from translation service state");
        assertEquals(2, source.split("if \\(!scheduledTuningEnabled\\) \\{", -1).length - 1,
                "both scheduled tuning methods must return unless translate.tuning.scheduler.enabled=true");
    }

    @Test
    void debugAiMetricsHistorySchedulerRequiresExplicitOptIn() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/debug/ai/DebugAiMetricsHistoryScheduler.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("name = \"scheduled\""),
                "debug history collection must keep its explicit owner flag");
        assertTrue(source.contains("havingValue = \"true\""),
                "debug history collection should run only when explicitly enabled");
        assertTrue(source.contains("matchIfMissing = false"),
                "debug history collection must be disabled by default");
    }

    private static SortedMap<String, Path> scheduledClasses() throws Exception {
        SortedMap<String, Path> result = new TreeMap<>();
        try (var files = Files.walk(ACTIVE_SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!SCHEDULED_LINE.matcher(source).find()) {
                    continue;
                }
                result.put(fqcn(source, file), file);
            }
        }
        return result;
    }

    private static String fqcn(String source, Path file) {
        var pkg = PACKAGE_LINE.matcher(source);
        var type = TYPE_LINE.matcher(source);
        assertTrue(pkg.find(), file + " must declare a package");
        assertTrue(type.find(), file + " must declare a top-level type");
        return pkg.group(1) + "." + type.group(1);
    }

    private static Map<String, String> parseEntry(String raw) {
        Map<String, String> fields = new TreeMap<>();
        if (raw == null) {
            return fields;
        }
        for (String segment : raw.split(";")) {
            int index = segment.indexOf('=');
            if (index <= 0) {
                continue;
            }
            fields.put(segment.substring(0, index).trim(), segment.substring(index + 1).trim());
        }
        return fields;
    }

    private static final String MANIFEST_TEXT = """
            ai.abandonware.nova.orch.storage.DegradedStorageDrainer=owner=nova-degraded-storage; enablement=internal guard via degraded-storage drain props and memory.enabled; schedule=nova.orch.degraded-storage.drain.fixed-delay-ms; sideEffect=file-write,memory-promotion; defaultBehavior=body guarded; noClassGateReason=uses runtime drain and memory guards
            ai.abandonware.nova.orch.storage.OutboxMicrometerMetrics=owner=nova-degraded-storage; enablement=internal guard via degraded-storage metrics props; schedule=nova.orch.degraded-storage.metrics.refresh-ms; sideEffect=read-only,metrics; defaultBehavior=body guarded; noClassGateReason=metrics refresh guard remains in degraded-storage ownership
            com.example.lms.agent.AutonomousExplorationService=owner=agent-autonomous-exploration; enablement=class gate agent.autonomous-exploration.enabled; schedule=agent.autonomous-exploration.initial-delay-ms and agent.autonomous-exploration.period-ms; sideEffect=network,memory-promotion; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.agent.KnowledgeConsistencyVerifier=owner=agent-knowledge-consistency; enablement=class gate agent.knowledge-consistency.enabled; schedule=agent.knowledge-consistency.initial-delay-ms and agent.knowledge-consistency.period-ms; sideEffect=read-only,diagnostics; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.agent.KnowledgeCurationScheduler=owner=agent-knowledge-curation; enablement=class gate agent.knowledge-curation.enabled; schedule=agent.knowledge-curation.initial-delay-ms and agent.knowledge-curation.period-ms; sideEffect=memory-promotion,network; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.agent.KnowledgeDecayService=owner=agent-knowledge-decay; enablement=class gate agent.knowledge-decay.enabled; schedule=agent.knowledge-decay.initial-delay-ms and agent.knowledge-decay.period-ms; sideEffect=parameter-mutation,memory-promotion; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.cfvm.CfvmSnapshotService=owner=cfvm-snapshot; enablement=internal guard buffer/repository availability; schedule=cfvm.snapshot.fixed-delay-ms and cfvm.snapshot.initial-delay-ms; sideEffect=db-write,cfvm-restore; defaultBehavior=body guarded; noClassGateReason=service remains active for CFVM restore and snapshot persistence
            com.example.lms.infra.resilience.NightmareBreaker=owner=nightmare-breaker; enablement=internal guard nightmare.breaker.enabled; schedule=nightmare.breaker.evict-interval-ms; sideEffect=state-eviction,diagnostics; defaultBehavior=body guarded; noClassGateReason=breaker bean remains active for synchronous guard calls
            com.example.lms.learning.ops.RagLearningOpsCurationCollector=owner=learning-ops; enablement=internal enabled guard; schedule=awx.learning-ops.collector.interval-ms; sideEffect=file-write,diagnostics; defaultBehavior=body guarded; noClassGateReason=collector keeps runtime guard inside scheduled body
            com.example.lms.plugin.image.jobs.ImageJobService=owner=image-plugin; enablement=conditional bean OpenAiImageService; schedule=image.jobs.relay-delay-ms; sideEffect=file-write,network; defaultBehavior=active only when image service bean exists; noClassGateReason=bean-gated by plugin dependency
            com.example.lms.scheduler.AutoEvolveScheduler=owner=rgb-moe-autoevolve; enablement=class gate rgb.moe.autoevolve.enabled; schedule=literal cron 0 */15 * * * *; sideEffect=network,file-write,parameter-mutation; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.scheduler.IndexingScheduler=owner=indexing; enablement=class gate indexing.scheduler.enabled; schedule=indexing.cron; sideEffect=vector-write,memory-promotion; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.scheduler.PendingMemorySoakScheduler=owner=memory-pending-soak; enablement=class gate memory.pending-soak.enabled; schedule=memory.pending-soak.interval-ms; sideEffect=memory-promotion,file-write; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.scheduler.QuarantineReprocessScheduler=owner=memory-quarantine-reprocess; enablement=class gate memory.quarantine-reprocess.enabled; schedule=memory.quarantine-reprocess.interval-ms; sideEffect=memory-promotion,file-write; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.scheduler.VectorDlqRedriveScheduler=owner=vector-dlq; enablement=internal guard vector.dlq.redrive.enabled; schedule=vector.dlq.redrive.interval-ms; sideEffect=vector-write; defaultBehavior=body guarded and disabled by default; noClassGateReason=body guard keeps redrive bean available for diagnostics
            com.example.lms.scheduler.VectorStoreBufferScheduler=owner=vector-buffer-flush; enablement=class gate vector.flush.scheduler.enabled; schedule=vector.flush.cron; sideEffect=vector-write; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.scheduler.VectorStoreFlushScheduler=owner=vector-store-flush; enablement=class gate vectorstore.flush.scheduler.enabled; schedule=vectorstore.flush.scheduler.period-ms; sideEffect=vector-write; defaultBehavior=enabled when missing; noClassGateReason=class-gated
            com.example.lms.scheduler.WhiteningRefitScheduler=owner=rag-mp-whitening; enablement=config bean gate rag.mp.enabled; schedule=rag.mp.refit.cron; sideEffect=parameter-mutation,file-write; defaultBehavior=disabled unless rag.mp enabled; noClassGateReason=bean-gated by MpWhiteningConfig
            com.example.lms.service.AdaptiveTranslationService=owner=adaptive-translation; enablement=internal guard translate.tuning.scheduler.enabled; schedule=fixedRate 3600000 and 7200000 with initialDelay 1800000; sideEffect=parameter-mutation; defaultBehavior=body guarded and disabled by default; noClassGateReason=service remains active for normal translation
            com.example.lms.service.AttachmentService=owner=attachment-retention; enablement=always-on service scheduler; schedule=attachments.retention.cleanup-interval-ms; sideEffect=in-memory,state-eviction; defaultBehavior=enabled with 24h metadata TTL and 5m cleanup; noClassGateReason=service remains active for synchronous attachment APIs
            com.example.lms.debug.ai.DebugAiMetricsHistoryScheduler=owner=debug-ai-metrics-history; enablement=class gate lms.debug.ai.history.scheduled; schedule=lms.debug.ai.history.interval-ms; sideEffect=diagnostics,in-memory; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.service.ModelFetchService=owner=model-fetch; enablement=class gate modelfetch.enabled; schedule=literal cron 0 0 * * * *; sideEffect=network,file-write; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.service.ModelSyncService=owner=model-fetch; enablement=class gate modelfetch.enabled; schedule=literal cron 0 0 0 * * *; sideEffect=network,file-write; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.service.soak.metrics.SoakWebKpiMinuteSummaryLogger=owner=soak-web-kpi-summary; enablement=class gate nova.orch.web-failsoft.soak-kpi-summary.enabled; schedule=nova.orch.web-failsoft.soak-kpi-summary.interval-ms; sideEffect=diagnostics,file-write; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.service.soak.runner.SoakQuickRunner=owner=soak-quick-runner; enablement=expression gate soak.enabled and soak.quick-runner.enabled plus props scheduled flag; schedule=soak.quick-runner.cron; sideEffect=network,file-write; defaultBehavior=disabled unless enabled; noClassGateReason=expression-gated
            com.example.lms.service.vector.VectorBackendHealthService=owner=vector-backend-health; enablement=internal guard vector.dlq.health.probe-enabled; schedule=vector.dlq.health.interval-ms; sideEffect=vector-write,diagnostics; defaultBehavior=body guarded and probe disabled by default; noClassGateReason=body guard keeps health snapshot available
            com.example.lms.service.vector.VectorIngestProtectionService=owner=vector-ingest-protection; enablement=internal guard vector.ingest-protection.enabled; schedule=vector.ingest-protection.gc-interval-ms; sideEffect=parameter-mutation,diagnostics; defaultBehavior=body guarded and disabled by default; noClassGateReason=body guard keeps protection state available
            com.example.lms.uaw.autolearn.UawAutolearnOrchestrator=owner=uaw-autolearn; enablement=internal guard uaw.autolearn.enabled plus idle/retrain gates; schedule=uaw.autolearn.tickMs or idle.pollMillis; sideEffect=file-write,network,memory-promotion; defaultBehavior=body guarded; noClassGateReason=body guard coordinates backward-compatible flags
            com.example.lms.uaw.autolearn.UawIdleTrainScheduler=owner=uaw-idle-train; enablement=internal guard train_idle.enabled and train_idle.cron.enabled; schedule=train_idle.cron.am and train_idle.cron.pm; sideEffect=file-write,network; defaultBehavior=body guarded and cron disabled by default; noClassGateReason=body guard handles two scheduled triggers
            com.example.lms.uaw.selfclean.UawSelfCleanOrchestrator=owner=uaw-selfclean; enablement=class gate uaw.selfclean.enabled; schedule=uaw.selfclean.schedule-ms; sideEffect=file-write,vector-write; defaultBehavior=disabled unless enabled; noClassGateReason=class-gated
            com.example.lms.uaw.thumbnail.UawThumbnailOrchestrator=owner=uaw-thumbnail; enablement=internal guard uaw.thumbnail.enabled plus idle and budget gates; schedule=uaw.thumbnail.tick-ms; sideEffect=file-write,network,memory-promotion; defaultBehavior=body guarded and disabled by default; noClassGateReason=body guard coordinates idle and budget checks
            """;
}
