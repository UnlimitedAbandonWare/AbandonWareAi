// src/main/java/com/example/lms/config/WebConfig.java
package com.example.lms.config;

import org.springframework.context.annotation.Configuration;

/**
 * 웹 전반 설정용 Config 클래스
 * — RestTemplate 빈 정의는 RestTemplateConfig로 옮겼으므로
 *   여기서는 더 이상 RestTemplate 관련 빈을 정의하지 않습니다.
 */
@Configuration
public class WebConfig {

    // TODO: 필요한 CORS 설정, MessageConverter 설정 등은
    //       이 클래스에 그대로 남겨 두세요.

}
