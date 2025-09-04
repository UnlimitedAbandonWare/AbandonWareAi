// src/main/java/com/example/lms/service/AdminDetailsServiceImpl.java

package com.example.lms.service;

import com.example.lms.domain.Administrator;
import com.example.lms.repository.AdminRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminDetailsServiceImpl implements UserDetailsService {

    private final AdminRepository adminRepository;

    /**
     * Spring Security가 로그인 요청을 가로챌 때 호출하는 메서드입니다.
     * @param username 사용자가 로그인 시 입력한 아이디
     * @return UserDetails를 구현한 Administrator 객체
     * @throws UsernameNotFoundException 해당 아이디의 사용자가 DB에 없을 경우 예외 발생
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // AdminRepository를 사용해 DB에서 관리자 정보를 조회합니다.
        return adminRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("관리자 계정을 찾을 수 없습니다: " + username));
    }
}