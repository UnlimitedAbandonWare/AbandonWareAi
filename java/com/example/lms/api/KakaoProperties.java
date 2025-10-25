package com.example.lms.api;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;



/**
 * 카카오 API 연동을 위한 설정 프로퍼티
 */
@Data
@Component
@ConfigurationProperties(prefix = "kakao")
public class KakaoProperties {
    /**
     * REST API 호출용 Admin 키
     */
    private String adminKey;

    /**
     * 링크 템플릿 기본 URL (optional)
     */
    private String defaultWebUrl;

    /**
     * 카카오 API 기본 호스트 (예: https://kapi.kakao.com)
     */
    private String apiBaseUrl;

    /**
     * 친구 메시지 전송 엔드포인트 경로
     */
    private String sendFriendsMessagePath;

    /**
     * 나에게 보내기(메모) 전송 엔드포인트 경로
     */
    private String sendMemoPath;

    /**
     * 알림톡(비즈 API) 템플릿 ID
     */
    private String bizTemplateId;

    /**
     * 알림톡(비즈 API) 전송 엔드포인트 경로
     */
    private String sendBizPath;

    /**
     * WebClient 연결 타임아웃 (밀리초)
     */
    private int webclientConnectTimeoutMs;

    /**
     * WebClient 읽기 타임아웃 (밀리초)
     */
    private int webclientReadTimeoutMs;
}