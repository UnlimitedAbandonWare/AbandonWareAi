package com.example.lms.service;

/**
 * 벡터스토어 청크에 부착할 표준 메타데이터 키.
 *
 * <p>오염 추적/차단/필터링의 근거로 사용합니다.</p>
 */
public final class VectorMetaKeys {
    private VectorMetaKeys() {
    }

    /** 세션/테넌트 격리 키 (LangChainRAGService.META_SID와 동일값 사용) */
    public static final String META_SID = "sid";

    /** 논리 sid(회전 전) - sid rotation/격리 재처리에서 추적용 */
    public static final String META_SID_LOGICAL = "sid_logical";

    /** 문서 타입: KB | WEB | MEMORY | LOG | TRACE | LEGACY */
    public static final String META_DOC_TYPE = "doc_type";

    /** 오염 사유 기록용 */
    public static final String META_POISON_REASON = "poison_reason";

    /** 정제(sanitize) 적용 여부 */
    public static final String META_SANITIZED = "sanitized";

    /** 출처 태그: ASSISTANT | WEB | OFFICIAL | USER */
    public static final String META_SOURCE_TAG = "source_tag";

    /** 원천: LLM | WEB | USER | SYSTEM */
    public static final String META_ORIGIN = "origin";

    /** 검증 여부: true | false */
    public static final String META_VERIFIED = "verified";

    /** 도메인: GAME | GENERAL | EDU (선택) */
    public static final String META_DOMAIN = "domain";

    /** 엔티티명 (선택) */
    public static final String META_ENTITY = "entity";

    // ===== Scope 라벨링 (부품/범위 구분) =====
    public static final String META_SCOPE_ANCHOR_KEY = "scope_anchor_key";
    public static final String META_SCOPE_KIND = "scope_kind"; // WHOLE | PART
    public static final String META_SCOPE_PART_KEY = "scope_part_key";
    public static final String META_SCOPE_CONF = "scope_conf"; // 0~1
    public static final String META_SCOPE_REASON = "scope_reason";

    // ===== Retrieval 정책 힌트 =====
    public static final String META_ALLOWED_DOC_TYPES = "allowed_doc_types"; // "KB,MEMORY,TRAIN" etc

    /** 인용 횟수 (선택) */
    public static final String META_CITATION_COUNT = "citation_count";

    /** Embedding fingerprint: provider|model|dimensions */
    public static final String META_EMB_FP = "emb_fp";

    /** Embedding id (human-readable): provider|model|dimensions */
    public static final String META_EMB_ID = "emb_id";

    public static final String META_EMB_PROVIDER = "emb_provider";
    public static final String META_EMB_MODEL = "emb_model";
    public static final String META_EMB_DIM = "emb_dim";

    // ===== 장애/오염 탐지 기반 격리(quarantine) & DLQ =====

    /** 어떤 컴포넌트가 격리시켰는지 (예: INGEST_PROTECTION) */
    public static final String META_QUARANTINED_BY = "quarantined_by";

    /** 격리 사유 (예: embedder_down, pinecone_validation_error 등) */
    public static final String META_QUARANTINE_REASON = "quarantine_reason";

    /** 격리 시 원본 stable-id(덮어쓰기 방지용 복제 upsert 시 기록) */
    public static final String META_ORIGINAL_ID = "original_id";

    /** 격리 시 원본 sid 기록 */
    public static final String META_ORIGINAL_SID = "original_sid";

    /**
     * Strict write flag.
     *
     * <p>When set to true("true"/"1"), vector store upsert errors must propagate to the caller
     * (i.e. do NOT silently swallow) so buffered writes can retry safely.
     *</p>
     */
    public static final String META_STRICT_WRITE = "strict_write";

    /** Marks a segment as replay/reingested from a DLQ/quarantine pipeline (optional). */
    public static final String META_DLQ_REPLAY = "dlq_replay";

    /** DLQ row id (string) for traceability (optional). */
    public static final String META_DLQ_ID = "dlq_id";

    /** Quarantine vector id that this replay originated from (optional). */
    public static final String META_DLQ_QUARANTINE_ID = "dlq_quarantine_id";

    // ===== Verification / Shadow-write (staging) =====

    /** When true, the content requires external verification before merging into the primary/global index. */
    public static final String META_VERIFICATION_NEEDED = "verification_needed";

    /** Count of sources that look like credible URLs (used by retrieval/quality guards). */
    public static final String META_CITATION_URL_COUNT = "citation_url_count";

    /** Upstash namespace hint (optional). */
    public static final String META_NAMESPACE = "ns";

    /** Document id (stable id) (optional). */
    public static final String META_DOC_ID = "doc_id";

    /** KB domain hint (optional). */
    public static final String META_KB_DOMAIN = "kb_domain";

    /** Shadow-write flag: stage into a shadow namespace instead of directly contaminating the global pool. */
    public static final String META_SHADOW_WRITE = "shadow_write";
    public static final String META_SHADOW_REASON = "shadow_reason";
    public static final String META_SHADOW_RUN_ID = "shadow_run_id";
    public static final String META_SHADOW_TARGET_SID = "shadow_target_sid";
    public static final String META_SHADOW_SID = "shadow_sid";
    public static final String META_SHADOW_VECTOR_ID = "shadow_vector_id";
    public static final String META_SHADOW_STATE = "shadow_state";
    public static final String META_SHADOW_BYPASS = "shadow_bypass";
}
