// src/main/java/com/example/lms/dto/ModelInfoDto.java
package com.example.lms.dto;

import lombok.Data;



/**
 * OpenAI에서 가져온 모델 메타데이터를 담는 DTO
 */
@Data
public class ModelInfoDto {
    private String id;        // OpenAI API가 주는 model identifier
    private String object;    // 응답의 "object" 필드
    private long   created;   // UNIX timestamp
    private String ownedBy;   // 응답의 "owned_by" 필드
    private String root;      // 필요시 추가 필드
}