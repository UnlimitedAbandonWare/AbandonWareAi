package com.example.lms.service.rag.fusion;

/**
 * 검색 결과/문서를 Deduplication 하기 위한 표준 인터페이스입니다.
 * <p>
 * 구현체는 URL, 콘텐츠 해시, ID 등 중복 판단에 사용할 안정적인 키를 반환해야 합니다.
 * 동일한 키를 가지는 항목은 중복으로 간주됩니다.
 * </p>
 */
public interface Dedupable {

    /**
     * 중복 판단에 사용할 키를 반환합니다.
     * 예: canonical URL, 콘텐츠 해시, 외부 시스템 ID 등.
     *
     * @return 중복 판단용 키 (null 또는 빈 문자열인 경우 고유 항목으로 취급)
     */
    String getDeduplicationKey();
}
