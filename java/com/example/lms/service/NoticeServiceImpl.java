package com.example.lms.service;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.Notice;
import com.example.lms.domain.NoticeType;
import com.example.lms.repository.NoticeRepository;
import com.example.lms.service.AdminService;
import com.example.lms.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;




@Service
@Transactional
@RequiredArgsConstructor
public class NoticeServiceImpl implements NoticeService {

    private final NoticeRepository repo;
    private final AdminService adminService;

    /* ✨ 인터페이스 요구사항 충족 - 전체 공지 조회 */
    @Override
    @Transactional(readOnly = true)
    public List<Notice> findAll() {
        return repo.findAll();
    }

    /* 타입·타깃별 조회 */
    @Override
    @Transactional(readOnly = true)
    public List<Notice> findByTypeAndTarget(NoticeType type, Long targetId) {
        return switch (type) {
            case DEPARTMENT -> repo.findByTypeAndTargetDeptId(type, targetId);
            case PERSONAL   -> repo.findByTypeAndTargetUserId(type, targetId);
            case GLOBAL     -> repo.findByType(type);
        };
    }

    /* 공지 등록 */
    @Override
    public void postWithType(String title,
                             String content,
                             NoticeType type,
                             Long deptId,
                             Long userId) {

        Administrator admin = adminService.getCurrentAdmin();

        Notice notice = new Notice();
        notice.setTitle(title);
        notice.setContent(content);
        notice.setType(type);

        switch (type) {
            case DEPARTMENT -> notice.setTargetDeptId(deptId);
            case PERSONAL   -> notice.setTargetUserId(userId);
            case GLOBAL     -> { /* extra target 없음 */ }
        }
        notice.setCreatedBy(admin);

        repo.save(notice);
    }

    /* 공지 삭제 */
    @Override
    public void delete(Long id) {
        repo.deleteById(id);
    }
}