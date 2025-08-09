// src/main/java/com/example/lms/dto/AssignmentDTO.java
package com.example.lms.dto;

import lombok.*;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignmentDTO {

    private Long      id;
    private String    title;
    private String    description;
    private LocalDate dueDate;     // 제출 마감일
    private boolean   submitted;   // 이미 제출했는지 여부

    /**
     * Entity → DTO 변환용 팩토리 메서드
     *
     * @param e          Assignment 엔티티
     * @param submitted  이미 제출했는지 여부
     * @return AssignmentDTO
     */
    public static AssignmentDTO of(com.example.lms.domain.Assignment e,
                                   boolean submitted) {
        return AssignmentDTO.builder()
                .id(e.getId())
                .title(e.getTitle())
                .description(e.getDescription())
                .dueDate(e.getDueDate())   // LocalDate 필드와 일치
                .submitted(submitted)
                .build();
    }
}
