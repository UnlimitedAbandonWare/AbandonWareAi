package com.example.lms.service;

import com.example.lms.domain.Notice;
import com.example.lms.domain.NoticeType;
import java.util.List;

/** 공지 조회·등록·삭제 인터페이스 */
public interface NoticeService {

    /* 전체 공지 */
    List<Notice> findAll();

    /* 타입별·타깃별 공지 */
    List<Notice> findByTypeAndTarget(NoticeType type, Long targetId);

    /* 공지 등록 */
    void postWithType(String title, String content,
                      NoticeType type, Long deptId, Long userId);

    /* 공지 삭제 */
    void delete(Long id);
}
