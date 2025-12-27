package com.example.lms.service;

/**
 * 벡터스토어 청크에 부착할 표준 메타데이터 키.
 *
 * <p>오염 추적/차단/필터링의 근거로 사용합니다.</p>
 */
public final class VectorMetaKeys {
    private VectorMetaKeys() {
    }

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

    /** 인용 횟수 (선택) */
    public static final String META_CITATION_COUNT = "citation_count";

    /** Embedding fingerprint: provider|model|dimensions */
    public static final String META_EMB_FP = "emb_fp";

    /** Embedding id (human-readable): provider|model|dimensions */
    public static final String META_EMB_ID = "emb_id";

    public static final String META_EMB_PROVIDER = "emb_provider";
    public static final String META_EMB_MODEL = "emb_model";
    public static final String META_EMB_DIM = "emb_dim";
}
