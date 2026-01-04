// src/main/java/com/example/lms/storage/FileStorageService.java
package com.example.lms.storage;

import org.springframework.web.multipart.MultipartFile;



/**
 * 파일 저장 서비스 인터페이스
 */
public interface FileStorageService {

    /**
     * 파일을 지정된 하위 디렉터리에 저장 후 URL(또는 상대경로)을 반환합니다.
     *
     * @param file   업로드된 MultipartFile
     * @param subDir 예) "assignments/3/20241001"
     * @return       저장된 파일의 접근 URL 또는 상대경로
     */
    String save(MultipartFile file, String subDir);

    /**
     * 기본 저장 (subDir="misc")
     * 필요 시 오버로드 없이 호출 가능하도록 제공
     *
     * @param file 업로드된 MultipartFile
     * @return     저장된 파일의 접근 URL 또는 상대경로
     */
    default String save(MultipartFile file) {
        return save(file, "misc");
    }
}