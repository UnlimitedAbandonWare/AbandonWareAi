// src/main/java/com/example/lms/service/KakaoFriendService.java
package com.example.lms.service;

import java.util.List;



/**
 * Kakao 친구-목록 조회 서비스 계약
 */
public interface KakaoFriendService {

    /**
     * accessToken + offset + limit 으로 직접 호출.
     * <p>
     * 페이지 크기(limit)는 서비스 내부 하드코딩이 아니라 호출부(Controller/Config)에서 정책으로 주입한다.
     */
    List<String> fetchFriendUuids(String accessToken, int offset, int limit);
}