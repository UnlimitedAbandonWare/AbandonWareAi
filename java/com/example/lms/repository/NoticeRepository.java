// src/main/java/com/example/lms/repository/NoticeRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Notice;
import com.example.lms.domain.NoticeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoticeRepository extends JpaRepository<Notice, Long> {

    // GLOBAL 공지
    List<Notice> findByType(NoticeType type);

    // DEPARTMENT 공지: targetDeptId 기준
    List<Notice> findByTypeAndTargetDeptId(NoticeType type, Long targetDeptId);

    // PERSONAL 공지: targetUserId 기준
    List<Notice> findByTypeAndTargetUserId(NoticeType type, Long targetUserId);
}
