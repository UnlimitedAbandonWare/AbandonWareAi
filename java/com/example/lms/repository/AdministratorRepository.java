// AdministratorRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Administrator;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;



public interface AdministratorRepository extends JpaRepository<Administrator, Long> {
    Optional<Administrator> findByUsername(String username);

    /**
     * 사용자 이름으로 존재 여부를 검사합니다. 이미 사용자명이 존재하면 true를 반환합니다.
     * 이 메서드는 관리자 계정 초기화가 idempotent 하게 동작하도록 돕기 위해 추가되었습니다.
     *
     * @param username 계정 이름
     * @return 해당 이름을 가진 관리자가 존재하면 true, 그렇지 않으면 false
     */
    boolean existsByUsername(String username);
}