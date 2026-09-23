package com.example.lms.api;
import com.example.lms.dto.ChatResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ChatRequestCompletionAdviceTest {
    @Test void adviceCapturesOnlySuccessfulNormalChatDtoAndReturnsOriginalBody()throws Exception{
        var advice=new ChatRequestCompletionAdvice();var count=new AtomicInteger();
        var request=new MockHttpServletRequest();request.setAttribute(ChatGenerationAdmissionFilter.class.getName()+".completion",(java.util.function.Consumer<Object>)body->count.incrementAndGet());
        var response=new MockHttpServletResponse();var body=new ChatResponseDto("fixture",1L,"fixture",false);
        var method=java.util.Arrays.stream(ChatApiController.class.getMethods()).filter(m->m.getName().equals("chatSync")).findFirst().orElseThrow();var parameter=new org.springframework.core.MethodParameter(method,-1);
        assertTrue(advice.supports(parameter,MappingJackson2HttpMessageConverter.class));
        assertSame(body,advice.beforeBodyWrite(body,parameter,MediaType.APPLICATION_JSON,MappingJackson2HttpMessageConverter.class,new ServletServerHttpRequest(request),new ServletServerHttpResponse(response)));assertEquals(1,count.get());
        response.setStatus(403);advice.beforeBodyWrite(body,parameter,MediaType.APPLICATION_JSON,MappingJackson2HttpMessageConverter.class,new ServletServerHttpRequest(request),new ServletServerHttpResponse(response));assertEquals(1,count.get());
        var assist=com.example.lms.assist.ConversateController.class.getMethod("status",org.springframework.security.core.Authentication.class,String.class);assertFalse(advice.supports(new org.springframework.core.MethodParameter(assist,-1),MappingJackson2HttpMessageConverter.class));
    }
}
