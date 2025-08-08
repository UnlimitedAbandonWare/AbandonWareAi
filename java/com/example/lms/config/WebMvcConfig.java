package com.example.lms.config;

import com.example.lms.common.ReqLogInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ReqLogInterceptor reqLogInterceptor;

    /**
     * 모든 요청에 대해 ReqLogInterceptor를 등록합니다.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(reqLogInterceptor)
                .addPathPatterns("/**");
    }

    /**
     * 정적 뷰 매핑을 설정합니다.
     * "/"      → templates/index.html
     * "/chat-ui" → templates/chat-ui.html
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("index");
        registry.addViewController("/chat-ui").setViewName("chat-ui");
    }
}
