package com.example.lms.api;

import com.example.lms.dto.ChatResponseDto;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Normal chat JSON only. Live assist/audio/card controllers are deliberately outside this boundary. */
@ControllerAdvice(assignableTypes=ChatApiController.class)
public final class ChatRequestCompletionAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter) {
        return method.getContainingClass()==ChatApiController.class;
    }
    @Override public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,
            Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
        if(body instanceof ChatResponseDto && request instanceof ServletServerHttpRequest servlet
                && response instanceof ServletServerHttpResponse output
                && output.getServletResponse().getStatus()>=200 && output.getServletResponse().getStatus()<300)
            ChatGenerationAdmissionFilter.completion(servlet.getServletRequest()).accept(body);
        return body;
    }
}
