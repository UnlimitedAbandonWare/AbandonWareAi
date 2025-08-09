/*-------------------------------------------------------------
 | TranslationMemoryRepository.java
 | 번역 메모리(TranslationMemory) 엔티티용 Spring-Data JPA 리포지토리
 |-------------------------------------------------------------
 |  • source(원문)로 단건 조회할 수 있는 커스텀 메서드 findBySource 제공
 |  • 기타 CRUD 메서드는 JpaRepository가 자동 구현
 *------------------------------------------------------------*/
package com.example.lms.repository;

import com.example.lms.domain.TranslationMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TranslationMemoryRepository
        extends JpaRepository<TranslationMemory, Long> {

    /** 원문(source)으로 번역 메모리 한 건 조회 */
    Optional<TranslationMemory> findBySourceHash(String sourceHash);
}
