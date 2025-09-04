// src/main/java/com/example/lms/service/KakaoFriendService.java
package com.example.lms.service;

import java.util.List;

/**
 * Kakao 친구-목록 조회 서비스 계약
 */
public interface KakaoFriendService {

    /** 한 페이지에 가져올 친구 수(전역 상수) */
    int PAGE_SIZE = 10;

    /** accessToken + offset 으로 직접 호출 (핵심 시그니처) */
    List<String> fetchFriendUuids(String accessToken, int offset);

    /** 첫 페이지(offset = 0) */
    default List<String> fetchFriendUuids(String accessToken) {
        return fetchFriendUuids(accessToken, 0);
    }

    /** pageNo → 내부 offset 계산 후 호출 */
    default List<String> fetchFriendUuidsByPage(String accessToken, int pageNo) {
        int offset = Math.max(0, pageNo - 1) * PAGE_SIZE;
        return fetchFriendUuids(accessToken, offset);
    }
}
