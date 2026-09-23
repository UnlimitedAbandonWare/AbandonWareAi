package com.example.lms.config;

import com.example.lms.common.ReqLogInterceptor;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;



@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    private final ReqLogInterceptor reqLogInterceptor;

    @Value("${lms.upload-dir:uploads}")
    private String uploadDir;

    @Value("${lms.upload-public-prefix:/uploads/}")
    private String uploadPublicPrefix;

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.mediaType("webmanifest", MediaType.parseMediaType("application/manifest+json"));
    }

    /**
     * 모든 요청에 대해 ReqLogInterceptor를 등록합니다.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(reqLogInterceptor)
                .addPathPatterns("/**");
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, clientDisconnectExceptionResolver());
    }

    /**
     * 정적 뷰 매핑을 설정합니다.
     * "/"      → templates/index.html
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("index");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/");
        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/static/css/");
        registry.addResourceHandler("/images/**")
                .addResourceLocations("classpath:/static/images/");
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/");

        try {
            String prefix = normalizePublicPrefix(uploadPublicPrefix);
            String location = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
            registry.addResourceHandler(prefix + "**")
                    .addResourceLocations(location);
        } catch (InvalidPathException ex) {
            log.warn("[AWX][upload] static upload handler disabled disabledReason=invalid_upload_dir exceptionType={}",
                    ex.getClass().getSimpleName());
        }
    }

    private static String normalizePublicPrefix(String value) {
        String prefix = (value == null || value.isBlank()) ? "/uploads/" : value.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix;
    }

    private static HandlerExceptionResolver clientDisconnectExceptionResolver() {
        return (request, response, handler, ex) -> {
            if (!(ex instanceof AsyncRequestNotUsableException)) {
                return null;
            }
            TraceStore.put("webmvc.clientDisconnect", true);
            TraceStore.put("webmvc.clientDisconnect.reason", "async_request_not_usable");
            if (request != null) {
                String path = request.getRequestURI();
                TraceStore.put("webmvc.clientDisconnect.pathHash", SafeRedactor.hashValue(path));
                TraceStore.put("webmvc.clientDisconnect.pathLength", path == null ? 0 : path.length());
            }
            TraceStore.put("webmvc.clientDisconnect.errorType",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            try {
                if (response != null && !response.isCommitted()) {
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                }
            } catch (RuntimeException statusError) {
                TraceStore.put("webmvc.clientDisconnect.statusSkipped", true);
                TraceStore.put("webmvc.clientDisconnect.statusSkipped.errorType",
                        SafeRedactor.traceLabelOrFallback(statusError.getClass().getSimpleName(), "unknown"));
            }
            return new ModelAndView();
        };
    }
}
