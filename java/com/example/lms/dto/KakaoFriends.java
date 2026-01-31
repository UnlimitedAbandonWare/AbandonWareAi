// src/main/java/com/example/lms/dto/KakaoFriends.java
package com.example.lms.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;




/**
 * Kakao “친구 목록” REST 응답을 그대로 매핑하는 DTO
 * - 필요한 필드(uuid)만 정의했습니다.
 */
@Data
public class KakaoFriends {

    /** REST 응답의 "elements" 배열을 매핑 */
    @JsonProperty("elements")
    private List<Element> elements;

    @Data
    public static class Element {
        /** 친구의 UUID */
        private String uuid;
        // 필요하다면 nickname, profileThumbnail 등 추가 필드 정의 가능
    }
}