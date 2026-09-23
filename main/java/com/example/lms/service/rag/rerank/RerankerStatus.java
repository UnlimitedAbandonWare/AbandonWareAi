package com.example.lms.service.rag.rerank;


/** 재랭커 동작 상태 표준 표현 */
public record RerankerStatus(
        boolean active,   // 재랭커가 실제로 동작 중인지
        String backend,   // "noop" | "onnx-runtime" | "embedding" 등
        String detail     // 상태 상세 메시지
) {}