package com.example.lms.api;

import com.example.lms.infra.upstash.UpstashRedisClient;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatGenerationAdmissionDemoFocusedTest {
    @Test void explicitDemoReachesPipelineWithoutRedisJdbcOrAuthenticatedOwner() throws Exception {
        var redis=mock(UpstashRedisClient.class);var db=mock(DataSource.class);var owners=mock(ClientOwnerKeyResolver.class);
        var filter=new ChatGenerationAdmissionFilter(redis,db,owners);
        ReflectionTestUtils.setField(filter,"demoMode",true);
        var request=new MockHttpServletRequest("POST","/api/chat/sync");
        request.addHeader("Idempotency-Key","demo-once");
        var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
        filter.doFilter(request,response,(r,s)->{reached.set(true);ChatGenerationAdmissionFilter.completion(request).accept(new Object());});
        assertTrue(reached.get());assertEquals("demo-memory",response.getHeader("X-Admission-Mode"));
        filter.costCheckCurrentRequest().run();filter.purgeExpired();
        assertNotNull(request.getAttribute("chat.admission.demoPermit"));
        verifyNoInteractions(redis,db,owners);
    }
    @Test void localProfileAlsoUsesMemoryAdmission() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(mock(UpstashRedisClient.class),mock(DataSource.class),mock(ClientOwnerKeyResolver.class));
        var environment=new MockEnvironment();environment.setActiveProfiles("local");ReflectionTestUtils.setField(filter,"environment",environment);
        var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("POST","/api/chat/sync"),response,(r,s)->reached.set(true));
        assertTrue(reached.get());assertEquals("demo-memory",response.getHeader("X-Admission-Mode"));
    }
    @Test void productionStillFailsClosedWhenAdmissionIsUnavailable() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(mock(UpstashRedisClient.class),mock(DataSource.class),mock(ClientOwnerKeyResolver.class));
        var environment=new MockEnvironment();environment.setActiveProfiles("prod");ReflectionTestUtils.setField(filter,"environment",environment);
        var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("POST","/api/chat/sync"),response,(r,s)->reached.set(true));
        assertFalse(reached.get());assertEquals(503,response.getStatus());assertNull(response.getHeader("X-Admission-Mode"));
    }
    @Test void demoDoesNotAcceptMalformedIdempotencyHeader() throws Exception {
        var filter=new ChatGenerationAdmissionFilter(mock(UpstashRedisClient.class),mock(DataSource.class),mock(ClientOwnerKeyResolver.class));
        ReflectionTestUtils.setField(filter,"demoMode",true);
        var request=new MockHttpServletRequest("POST","/api/chat/sync");request.addHeader("Idempotency-Key","invalid key");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->fail("invalid request reached pipeline"));assertEquals(400,response.getStatus());
    }
}
