// src/main/java/com/example/lms/payload/KakaoWebhookPayload.java
package com.example.lms.payload;

import lombok.*;
import java.util.Map;

/**
 * 카카오톡 Webhook 콜백 페이로드 매핑용 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KakaoWebhookPayload {

    /**
     * Webhook intent, ex: "ASSIGNMENT_SUBMIT"
     */
    private String intent;

    /**
     * 파라미터 맵, ex: {"asgId":"123"}
     */
    private Map<String, String> parameters;

    /**
     * 사용자 정보 (user.id → 카카오 userKey)
     */
    private User user;

    /**
     * parameters 맵에서 키에 해당하는 값을 편하게 꺼내옵니다.
     *
     * @param key 파라미터 키
     * @return 해당 키의 값 또는 null
     */
    public String getParameter(String key) {
        return parameters == null ? null : parameters.get(key);
    }

    /**
     * 카카오톡 사용자 정보
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class User {
        /** 카카오톡 고유 UUID */
        private String id;
    }
}
