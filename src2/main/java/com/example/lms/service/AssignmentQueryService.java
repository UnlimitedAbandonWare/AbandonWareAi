// src/main/java/com/example/lms/service/AssignmentQueryService.java
package com.example.lms.service;

import com.example.lms.dto.AssignmentDTO;
import java.util.List;

/**
 * 학생용 과제 조회 서비스 인터페이스
 */
public interface AssignmentQueryService {
    /**
     * 해당 학생에게 할당된 과제 목록을 DTO 형태로 반환
     */
    List<AssignmentDTO> findForStudent(Long stuId);

    /**
     * 과제 단건 조회 (DTO)
     */
    AssignmentDTO findById(Long asgId);

}
