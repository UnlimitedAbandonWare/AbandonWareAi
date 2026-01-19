
package com.example.lms.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


import java.util.HexFormat; // ✨ [개선] Java 17+ 의 HexFormat 사용

/**
 * 해시 관련 유틸리티 함수를 제공하는 클래스.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE) // 인스턴스화 방지
public final class HashUtil {
    private static final Logger log = LoggerFactory.getLogger(HashUtil.class);

    private static final MessageDigest SHA256_DIGEST;

    static {
        try {
            SHA256_DIGEST = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 알고리즘을 찾을 수 없습니다.", e);
            throw new IllegalStateException("필수 암호화 알고리즘(SHA-256)을 사용할 수 없습니다.", e);
        }
    }

    /**
     * 주어진 텍스트의 SHA-256 해시 값을 계산합니다.
     * @param text 해싱할 원문 텍스트
     * @return 64자의 16진수 해시 문자열
     */
    public static String sha256(String text) {
        if (text == null) {
            return null;
        }
        // MessageDigest는 thread-safe 하지 않으므로 synchronized 사용 또는 매번 새로 생성
        // 여기서는 동기화된 인스턴스를 복제하여 사용
        try {
            MessageDigest digest = (MessageDigest) SHA256_DIGEST.clone();
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (CloneNotSupportedException e) {
            // 이 예외는 발생해서는 안 됨
            throw new RuntimeException("SHA-256 MessageDigest 복제 실패", e);
        }
    }
}