package com.example.lms.service;

import com.example.lms.dto.KakaoFriends;
import java.util.List;

/** Kakao OAuth + 메시지 전송 공통 API */
public interface KakaoOAuthService {

    /* ───────── 기존 ───────── */
    /** 인가 코드를 액세스 토큰으로 교환 */
    String exchangeCodeForToken(String code);

    /** “나에게 보내기” – 기본(Default) 템플릿 */
    void sendMemoDefault(String accessToken, String text, String linkUrl);

    /* ────── [추가] 친구 API ────── */
    /** 친구 목록 1페이지 반환 */
    KakaoFriends friends(String accessToken, int offset);

    /** 편의 함수: UUID 리스트만 가져오기 */
    List<String> fetchFriendUuids(String accessToken, int offset);
}
