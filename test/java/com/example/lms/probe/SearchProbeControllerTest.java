package com.example.lms.probe;

import com.example.lms.probe.dto.SearchProbeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SearchProbeController.class)
@TestPropertySource(properties = {
        "probe.search.enabled=true", "probe.admin-token=secret123"
})
class SearchProbeControllerTest {
    @Autowired MockMvc mvc;
    @MockBean SearchProbeService service;

    @Test void unauthorized_without_token() throws Exception {
        mvc.perform(post("/api/probe/search").contentType("application/json")
                .content("{\"query\":\"hi\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void ok_with_token() throws Exception {
        when(service.run(any())).thenReturn(new com.example.lms.probe.dto.SearchProbeResponse());
        mvc.perform(post("/api/probe/search").contentType("application/json")
                .header("X-Probe-Token","secret123")
                .content("{\"query\":\"크로스 인코더\"}"))
            .andExpect(status().isOk());
    }
}