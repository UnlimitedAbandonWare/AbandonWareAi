package com.example.lms.service;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.storage.LocalFileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 첨부 파일을 저장하고 메타데이터를 관리하는 서비스.
 *
 * 현재는 간단한 MVP 구현으로 인메모리 저장소를 사용하여 첨부 메타를 관리합니다.
 * 필요 시 JPA 저장소로 교체할 수 있습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final LocalFileStorageService storage;
    /** In-memory 저장소로 첨부 메타를 보관합니다. */
    private final Map<String, AttachmentDto> repo = new ConcurrentHashMap<>();

    /**
     * 여러 MultipartFile을 저장하고 AttachmentDto 목록을 반환합니다.
     *
     * @param files 업로드할 파일 목록
     * @return 저장된 파일 메타 정보 목록
     */
    public List<AttachmentDto> saveAll(List<MultipartFile> files) {
        List<AttachmentDto> out = new ArrayList<>();
        if (files == null) return out;
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            String id = UUID.randomUUID().toString();
            // LocalFileStorageService.save 의 시그니처는 (MultipartFile file, String subPath)
            // 루트 업로드 디렉터리 하위에 "chat" 폴더를 생성하여 파일을 저장합니다.
            String url = storage.save(f, "chat");
            AttachmentDto dto = new AttachmentDto(
                    id,
                    f.getOriginalFilename(),
                    f.getSize(),
                    f.getContentType(),
                    url
            );
            repo.put(id, dto);
            out.add(dto);
        }
        return out;
    }

    /** 특정 ID의 첨부 메타를 조회합니다. */
    public Optional<AttachmentDto> find(String id) {
        return Optional.ofNullable(repo.get(id));
    }

    /**
     * 첨부 메타를 삭제합니다. 실제 파일 삭제는 정책에 따라 선택적으로 수행합니다.
     * @param id 첨부 ID
     */
    public void delete(String id) {
        AttachmentDto dto = repo.remove(id);
        if (dto != null) {
            log.debug("Attachment deleted: {}", id);
            // 실제 파일 삭제는 필요 시 LocalFileStorageService에 메서드를 추가하여 호출할 수 있습니다.
        }
    }
}