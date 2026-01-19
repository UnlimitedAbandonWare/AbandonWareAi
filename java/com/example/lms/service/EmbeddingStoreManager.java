package com.example.lms.service;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.dto.learning.MemorySnippet;
import com.example.lms.service.guard.VectorPoisonGuard;
import com.example.lms.service.guard.VectorScopeGuard;
import com.example.lms.service.knowledge.DefaultKnowledgeBaseService;
import com.example.lms.service.vector.VectorSidService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads translation memory rows into the embedding store at startup.
 *
 * <p>
 * Null/blank strings are filtered out first so that
 * {@link TextSegment#from(String)}
 * never receives an empty value.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class EmbeddingStoreManager {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreManager.class);

    // Evidence / citation detection (fail-soft)
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[(W|V|D)\\d+\\]");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final TranslationMemoryRepository memoryRepo;

    @Autowired(required = false)
    private VectorPoisonGuard vectorPoisonGuard;

    @Autowired(required = false)
    private VectorScopeGuard vectorScopeGuard;

    @Autowired(required = false)
    private VectorSidService vectorSidService;

    @Autowired(required = false)
    private DefaultKnowledgeBaseService knowledgeBaseService;

    @Value("${vector.bootstrap.startup.enabled:false}")
    private boolean startupBootstrapEnabled;

    @Value("${vector.bootstrap.startup.limit:200}")
    private int startupBootstrapLimit;

    @Value("${vectorstore.quarantine.rewrite-stable-id:true}")
    private boolean quarantineRewriteStableId;

    // 동일 sid는 프로세스 lifetime 동안 1회만 lazy bootstrap
    private final ConcurrentHashMap<String, Boolean> bootstrapOnce = new ConcurrentHashMap<>();

    // MERGE_HOOK:PROJ_AGENT::EMBED_BOOTSTRAP_IDS_SID_V1
    private record IndexedSegment(String id, TextSegment segment) {
    }

    @PostConstruct
    @Transactional(readOnly = true)
    public void init() {
        log.info("🗂️  Embedding Store 초기화를 시작합니다/* ... */");

        if (!startupBootstrapEnabled) {
            log.info("🗂️  (startup bootstrap) disabled: vector.bootstrap.startup.enabled=false");
            return;
        }

        int cap = Math.max(1, Math.min(startupBootstrapLimit, 5000));
        List<TranslationMemory> bootMemories = memoryRepo.findAll(
                PageRequest.of(0, cap, Sort.by(Sort.Order.desc("lastUsedAt"), Sort.Order.desc("createdAt")))
        ).getContent();

        List<IndexedSegment> items = bootMemories.stream()
                .map(this::toIndexedSegment)
                .filter(Objects::nonNull)
                .toList();

        if (items.isEmpty()) {
            log.info("Embedding Store에 추가할 유효한 문장이 없습니다.");
            return;
        }

        // Batch embed & upsert with stable ids.
        int batchSize = 256;
        for (int from = 0; from < items.size(); from += batchSize) {
            List<IndexedSegment> batch = items.subList(from, Math.min(from + batchSize, items.size()));
            List<TextSegment> segments = batch.stream().map(IndexedSegment::segment).toList();
            List<String> ids = batch.stream().map(IndexedSegment::id).toList();
            var embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(ids, embeddings, segments);
        }

        log.info("✅  {}개의 문장을 Embedding Store에 성공적으로 적재했습니다.", items.size());
    }

    private String activeGlobalSid() {
        try {
            if (vectorSidService == null) return LangChainRAGService.GLOBAL_SID;
            String s = vectorSidService.resolveActiveSid(LangChainRAGService.GLOBAL_SID);
            return (s == null || s.isBlank()) ? LangChainRAGService.GLOBAL_SID : s.trim();
        } catch (Exception ignore) {
            return LangChainRAGService.GLOBAL_SID;
        }
    }

    /**
     * Public helper for schedulers/controllers: returns the currently active global sid
     * (after sid-rotation mapping). Fail-soft: falls back to logical global sid.
     */
    public String getActiveGlobalSid() {
        return activeGlobalSid();
    }

    private String resolveActiveSid(String logicalSid) {
        String k = (logicalSid == null) ? "" : logicalSid.trim();
        if (k.isBlank()) return LangChainRAGService.GLOBAL_SID;
        if (vectorSidService == null) return k;
        try {
            return vectorSidService.resolveActiveSid(k);
        } catch (Exception ignore) {
            return k;
        }
    }



    /**
     * 필요 시점(검색 결과 비었을 때)에만 세션 메모리 제한적 부트스트랩.
     * 동일 sid는 프로세스 lifetime 동안 1회만 실행.
     */
    public boolean bootstrapForSession(String sid, int limit) {
        String key = (sid == null || sid.isBlank()) ? LangChainRAGService.GLOBAL_SID : sid.trim();
        if (bootstrapOnce.putIfAbsent(key, Boolean.TRUE) != null) {
            return false;
        }
        try {
            int cap = Math.max(1, Math.min(limit, 500));
            List<String> sids = buildSidVariants(sid);
            List<TranslationMemory> memories = loadForBootstrap(sids, cap);
            if (memories.isEmpty()) {
                bootstrapOnce.remove(key);
                return false;
            }
            List<IndexedSegment> items = memories.stream()
                    .map(this::toIndexedSegment)
                    .filter(Objects::nonNull)
                    .toList();
            if (items.isEmpty()) {
                bootstrapOnce.remove(key);
                return false;
            }
            batchEmbed(items);
            log.info("✅ (lazy bootstrap) sid={} indexed={}", key, items.size());
            return true;
        } catch (Exception e) {
            bootstrapOnce.remove(key);
            log.warn("(lazy bootstrap) fail-soft sid={}: {}", key, e.toString());
            return false;
        }
    }

    private List<TranslationMemory> loadForBootstrap(List<String> sessionIds, int limit) {
        if (sessionIds == null || sessionIds.isEmpty()) return List.of();
        int cap = Math.max(1, Math.min(limit, 500));
        try {
            return memoryRepo.findBySessionIdIn(
                    sessionIds,
                    PageRequest.of(0, cap, Sort.by(Sort.Order.desc("lastUsedAt"), Sort.Order.desc("createdAt")))
            ).getContent();
        } catch (Exception e) {
            log.debug("loadForBootstrap fail-soft: {}", e.toString());
            return List.of();
        }
    }

    private static List<String> buildSidVariants(String sid) {
        LinkedHashSet<String> sids = new LinkedHashSet<>();
        sids.add(LangChainRAGService.GLOBAL_SID);
        if (sid != null && !sid.isBlank()) {
            String s = sid.trim();
            sids.add(s);
            if (s.matches("\\d+")) sids.add("chat-" + s);
            else if (s.startsWith("chat-") && s.length() > 5) sids.add(s.substring(5));
        }
        return new ArrayList<>(sids);
    }

    private IndexedSegment toIndexedSegment(TranslationMemory tm) {
        if (tm == null) return null;

        // Do not index unstable/quarantined memories.
        try {
            TranslationMemory.MemoryStatus st0 = tm.getStatus();
            if (st0 == TranslationMemory.MemoryStatus.PENDING || st0 == TranslationMemory.MemoryStatus.QUARANTINED) {
                return null;
            }
        } catch (Exception ignore) {
        }

        String text = chooseIndexText(tm);
        if (text == null || text.isBlank()) return null;

        String logicalSid = (tm.getSessionId() == null || tm.getSessionId().isBlank())
                ? LangChainRAGService.GLOBAL_SID
                : tm.getSessionId().trim();

        String sid = resolveActiveSid(logicalSid);

        String st = normalizeSourceTag(tm.getSourceTag());

        int cc = detectCitationCount(text);
        boolean verified = computeVerified(st, tm.getConfidenceScore(), cc);

        // assistant 기록은 verified=false면 인덱싱하지 않음 (환각/무근거 문장 축적 방지)
        boolean isAssistant = "ASSISTANT".equalsIgnoreCase(st);
        if (isAssistant && !verified) return null;

        Map<String, Object> meta = new HashMap<>();
        meta.put(LangChainRAGService.META_SID, sid);
        if (!Objects.equals(sid, logicalSid)) {
            meta.put(VectorMetaKeys.META_SID_LOGICAL, logicalSid);
        }
        meta.put(VectorMetaKeys.META_SOURCE_TAG, st);
        meta.putIfAbsent(VectorMetaKeys.META_DOC_TYPE, "MEMORY");
        meta.put(VectorMetaKeys.META_ORIGIN, deriveOrigin(st));
        meta.put(VectorMetaKeys.META_VERIFIED, String.valueOf(verified));
        meta.put(VectorMetaKeys.META_CITATION_COUNT, cc);
        if (tm.getId() != null) meta.put("tm_id", tm.getId());
        meta.put("tm_field", (tm.getCorrected() != null && !tm.getCorrected().isBlank()) ? "corrected" : "content");

        // [VECTOR_POISON] Guard: block/sanitize log-like or orchestration trace dumps.
        if (vectorPoisonGuard != null) {
            try {
                VectorPoisonGuard.IngestDecision dec = vectorPoisonGuard.inspectIngest(sid, text, meta, "bootstrap.tm");
                if (dec == null || !dec.allow()) {
                    return null;
                }
                text = dec.text();
                if (dec.meta() != null) {
                    meta = dec.meta();
                }
            } catch (Throwable ignore) {
                return null;
            }
        }

        // [VECTOR_SCOPE] Label (and optionally quarantine) segments to avoid scope-mismatch retrieval.
        if (vectorScopeGuard != null) {
            try {
                String docType = String.valueOf(meta.getOrDefault(VectorMetaKeys.META_DOC_TYPE, "MEMORY"));
                VectorScopeGuard.IngestDecision sdec = vectorScopeGuard.inspectIngest(docType, text, meta);
                if (sdec != null && sdec.metaEnrich() != null) {
                    meta.putAll(sdec.metaEnrich());
                }
                if (sdec != null && !sdec.allow()) {
                    // Fail-soft: prefer quarantine routing over drop (if VectorSidService exists)
                    if (vectorSidService != null) {
                        String qsid = vectorSidService.quarantineSid();
                        meta.put(VectorMetaKeys.META_SID_LOGICAL, logicalSid);
                        meta.put(LangChainRAGService.META_SID, qsid);
                        meta.put(VectorMetaKeys.META_DOC_TYPE, "QUARANTINE");
                        meta.put(VectorMetaKeys.META_VERIFIED, "false");
                    } else {
                        return null;
                    }
                }
            } catch (Throwable ignore) {
                // no-op
            }
        }

        String stableId = (tm.getId() != null)
                ? "tm:" + tm.getId()
                : "tm:" + DigestUtils.sha256Hex(text);

        // When routing to quarantine, rewrite the id so it does NOT overwrite stable ids.
        String id = stableId;
        try {
            if (quarantineRewriteStableId && vectorSidService != null) {
                String qsid = vectorSidService.quarantineSid();
                String metaSid = String.valueOf(meta.getOrDefault(LangChainRAGService.META_SID, ""));
                if (qsid != null && !qsid.isBlank() && qsid.equals(metaSid)) {
                    meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_ID, stableId);
                    meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_SID, logicalSid);
                    id = qsid + ":" + DigestUtils.sha1Hex(logicalSid + "|" + stableId);
                }
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        return new IndexedSegment(id, TextSegment.from(text, Metadata.from(meta)));
    }

    private void batchEmbed(List<IndexedSegment> items) {
        if (items == null || items.isEmpty()) return;

        int batchSize = 256;
        for (int from = 0; from < items.size(); from += batchSize) {
            List<IndexedSegment> batch = items.subList(from, Math.min(from + batchSize, items.size()));
            List<TextSegment> segments = batch.stream().map(IndexedSegment::segment).toList();
            List<String> ids = batch.stream().map(IndexedSegment::id).toList();
            var embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(ids, embeddings, segments);
        }
    }

    /**
     * 신규 학습으로 생성된 메모리 스니펫을 벡터 DB에 인덱싱합니다.
     * 빈 목록이나 null 입력은 무시됩니다.
     */
    public void index(List<MemorySnippet> memories) {
        if (memories == null || memories.isEmpty())
            return;
        try {
            // [HARDENING] index new snippets with GLOBAL_SID metadata + contamination
            // tracking meta
            List<IndexedSegment> batch = new ArrayList<>();
            for (MemorySnippet ms : memories) {
                if (ms == null)
                    continue;
                String text = ms.text();
                if (text == null || text.isBlank())
                    continue;

                int cc = detectCitationCount(text);

                Map<String, Object> meta = new HashMap<>();
                String activeGlobalSid = activeGlobalSid();
                meta.put(LangChainRAGService.META_SID, activeGlobalSid);
                meta.put(VectorMetaKeys.META_SID_LOGICAL, LangChainRAGService.GLOBAL_SID);
                meta.put(VectorMetaKeys.META_SOURCE_TAG, "SYSTEM");
                meta.putIfAbsent(VectorMetaKeys.META_DOC_TYPE, "KB");
                meta.put(VectorMetaKeys.META_ORIGIN, "SYSTEM");
                meta.put(VectorMetaKeys.META_VERIFIED, String.valueOf(cc > 0));
                meta.put(VectorMetaKeys.META_CITATION_COUNT, cc);
                if (ms.subject() != null && !ms.subject().isBlank()) {
                    meta.put("ms_subject", ms.subject());
                }

                if (vectorPoisonGuard != null) {
                    try {
                        VectorPoisonGuard.IngestDecision dec = vectorPoisonGuard.inspectIngest(
                                activeGlobalSid, text, meta, "bootstrap.ms");
                        if (dec == null || !dec.allow()) {
                            continue;
                        }
                        text = dec.text();
                        if (dec.meta() != null) {
                            meta = dec.meta();
                        }
                    } catch (Throwable ignore) {
                        continue;
                    }
                }

                // [VECTOR_SCOPE] Label (and optionally quarantine) snippets as KB scoped.
                if (vectorScopeGuard != null) {
                    try {
                        String docType = String.valueOf(meta.getOrDefault(VectorMetaKeys.META_DOC_TYPE, "KB"));
                        VectorScopeGuard.IngestDecision sdec = vectorScopeGuard.inspectIngest(docType, text, meta);
                        if (sdec != null && sdec.metaEnrich() != null) {
                            meta.putAll(sdec.metaEnrich());
                        }
                        if (sdec != null && !sdec.allow()) {
                            if (vectorSidService != null) {
                                String qsid = vectorSidService.quarantineSid();
                                meta.put(VectorMetaKeys.META_SID_LOGICAL, LangChainRAGService.GLOBAL_SID);
                                meta.put(LangChainRAGService.META_SID, qsid);
                                meta.put(VectorMetaKeys.META_DOC_TYPE, "QUARANTINE");
                                meta.put(VectorMetaKeys.META_VERIFIED, "false");
                            } else {
                                continue;
                            }
                        }
                    } catch (Throwable ignore) {
                        // no-op
                    }
                }

                String stableId = "ms:"
                        + DigestUtils.sha256Hex((ms.subject() == null ? "" : ms.subject()) + "|" + text);

                // Quarantine id rewrite (avoid overwriting stable ids)
                String id = stableId;
                try {
                    if (quarantineRewriteStableId && vectorSidService != null) {
                        String qsid = vectorSidService.quarantineSid();
                        String metaSid = String.valueOf(meta.getOrDefault(LangChainRAGService.META_SID, ""));
                        if (qsid != null && !qsid.isBlank() && qsid.equals(metaSid)) {
                            meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_ID, stableId);
                            meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_SID, LangChainRAGService.GLOBAL_SID);
                            id = qsid + ":" + DigestUtils.sha1Hex(LangChainRAGService.GLOBAL_SID + "|" + stableId);
                        }
                    }
                } catch (Exception ignore) {
                    // fail-soft
                }

                batch.add(new IndexedSegment(id, TextSegment.from(text, Metadata.from(meta))));
            }

            if (!batch.isEmpty()) {
                List<String> ids = batch.stream().map(IndexedSegment::id).toList();
                List<TextSegment> segments = batch.stream().map(IndexedSegment::segment).toList();
                var embeddings = embeddingModel.embedAll(segments).content();
                embeddingStore.addAll(ids, embeddings, segments);
            }
        } catch (Exception e) {
            log.debug("Failed to index memory snippets: {}", e.toString());
        }
    }

    private static String chooseIndexText(TranslationMemory tm) {
        if (tm == null)
            return null;
        String corrected = tm.getCorrected();
        if (corrected != null && !corrected.isBlank()) {
            return corrected;
        }
        String content = tm.getContent();
        if (content != null && !content.isBlank()) {
            return content;
        }
        return null;
    }

    /** Normalize various source tags into a small stable set. */
    private static String normalizeSourceTag(String tag) {
        if (tag == null)
            return "UNKNOWN";
        String t = tag.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "ASSISTANT", "LLM", "AI_GENERATED" -> "ASSISTANT";
            case "USER", "USER_CORRECTION" -> "USER";
            case "OFFICIAL" -> "OFFICIAL";
            case "SYSTEM" -> "SYSTEM";
            default -> "WEB";
        };
    }

    /** Derive origin based on normalized source tag. */
    private static String deriveOrigin(String sourceTag) {
        String st = (sourceTag == null) ? "UNKNOWN" : sourceTag.toUpperCase(Locale.ROOT);
        return switch (st) {
            case "ASSISTANT" -> "LLM";
            case "USER" -> "USER";
            case "OFFICIAL", "WEB" -> "WEB";
            case "SYSTEM" -> "SYSTEM";
            default -> "SYSTEM";
        };
    }

    /** Cheap citation signal detector: URLs + evidence markers. */
    private static int detectCitationCount(String text) {
        if (text == null || text.isBlank())
            return 0;
        int count = 0;
        try {
            var m = URL_PATTERN.matcher(text);
            while (m.find())
                count++;
        } catch (Exception ignore) {
        }
        try {
            var m = CITATION_MARKER_PATTERN.matcher(text);
            while (m.find())
                count++;
        } catch (Exception ignore) {
        }
        return count;
    }

    /**
     * Decide whether the chunk should be treated as verified.
     *
     * <p>
     * Fail-soft: this is only used to block clearly contaminated ASSISTANT chunks.
     * For other origins the value is mostly informational.
     * </p>
     */
    private static boolean computeVerified(String sourceTag, Double confidenceScore, int citationCount) {
        String st = (sourceTag == null) ? "UNKNOWN" : sourceTag.toUpperCase(Locale.ROOT);

        // Non-assistant content is treated as verified by default.
        if (!"ASSISTANT".equals(st)) {
            return true;
        }

        // Assistant content: require explicit evidence signal OR high confidence.
        if (citationCount > 0) {
            return true;
        }
        double conf = (confidenceScore == null) ? 0.0 : confidenceScore.doubleValue();
        return conf >= 0.85;
    }

    // MERGE_HOOK:PROJ_AGENT::VECTOR_ADMIN_REBUILD_V1
    public record AdminRebuildReport(
            String logicalSid,
            String activeSid,
            int kbIndexed,
            int memoryIndexed
    ) {
    }

    /** Admin: rotate the logical global sid and return the new active sid. */
    public synchronized String rotateGlobalSid() {
        if (vectorSidService == null) {
            throw new IllegalStateException("VectorSidService is not configured");
        }
        String next = vectorSidService.rotateSid(LangChainRAGService.GLOBAL_SID);
        // allow bootstrap on the new active sid
        bootstrapOnce.remove(LangChainRAGService.GLOBAL_SID);
        bootstrapOnce.remove(next);
        return next;
    }

    /**
     * Admin: force a bootstrap-style rebuild into the current active sid.
     * This bypasses the bootstrap-once guard.
     */
    public int forceBootstrapToActiveSid(int limit) {
        return forceBootstrapToActiveSid(LangChainRAGService.GLOBAL_SID, limit);
    }

    /**
     * Admin: force a bootstrap-style rebuild for a logical sid into its current active sid.
     * For the global sid this is used after rotate-sid to repopulate the pool.
     */
    public int forceBootstrapToActiveSid(String logicalSid, int limit) {
        String key = (logicalSid == null || logicalSid.isBlank()) ? LangChainRAGService.GLOBAL_SID : logicalSid.trim();
        String active = resolveActiveSid(key);

        bootstrapOnce.remove(key);
        bootstrapOnce.remove(active);

        int cap = Math.max(1, Math.min(limit, 5000));
        List<String> sids = buildSidVariants(key);
        List<TranslationMemory> memories = loadForBootstrap(sids, cap);
        if (memories.isEmpty()) {
            return 0;
        }

        List<IndexedSegment> items = memories.stream()
                .map(this::toIndexedSegment)
                .filter(Objects::nonNull)
                .toList();

        if (items.isEmpty()) {
            return 0;
        }

        batchEmbed(items);
        bootstrapOnce.put(key, Boolean.TRUE);
        bootstrapOnce.put(active, Boolean.TRUE);

        log.info("[VectorAdmin] forceBootstrap sid={} active={} indexed={}", key, active, items.size());
        return items.size();
    }

    /**
     * Admin: rebuild helper for the rotate-sid flow. Reindexes KB rows (optional) and
     * bootstraps translation memories for the logical sid into its active sid.
     */
    public AdminRebuildReport adminRebuild(String logicalSid, int kbLimit, int memoryLimit, boolean includeKb) {
        String key = (logicalSid == null || logicalSid.isBlank()) ? LangChainRAGService.GLOBAL_SID : logicalSid.trim();
        String active = resolveActiveSid(key);

        int kb = 0;
        if (includeKb && knowledgeBaseService != null && LangChainRAGService.GLOBAL_SID.equals(key)) {
            try {
                kb = knowledgeBaseService.adminReindexAllToVectorStore(kbLimit, false);
            } catch (Exception e) {
                log.warn("[VectorAdmin] KB reindex fail-soft: {}", e.toString());
            }
        }

        int mem = 0;
        try {
            mem = forceBootstrapToActiveSid(key, memoryLimit);
        } catch (Exception e) {
            log.warn("[VectorAdmin] memory rebuild fail-soft: {}", e.toString());
        }

        return new AdminRebuildReport(key, active, kb, mem);
    }

}
