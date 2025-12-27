package com.example.lms.service.fallback;


/**
 * 스마트 폴백에서 '실제 폴백 답변인지' 식별하기 위한 경량 DTO.
 * 제안 본문(suggestion)과 폴백 여부 플래그(isFallback)를 포함합니다.
 * (JDK 14+ Record를 사용한 최종 버전)
 */
public record FallbackResult(String suggestion, boolean isFallback) {}