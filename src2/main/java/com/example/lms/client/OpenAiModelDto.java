package com.example.lms.client;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI /v1/models 응답에서 각 모델 정보를 담는 DTO
 */
public class OpenAiModelDto {

    /** 모델 식별자 (예: "gpt-4", "o3" 등) */
    private String id;

    /** 생성 시각 (Unix epoch seconds) */
    private long created;

    // 필요시 추가 필드(json 파싱용)
    @JsonProperty("object")
    private String objectType;

    // --- getters & setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }
}
