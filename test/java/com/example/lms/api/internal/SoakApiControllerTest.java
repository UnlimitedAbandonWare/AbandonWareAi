package com.example.lms.api.internal;

import com.example.lms.service.soak.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SoakApiController.class)
class SoakApiControllerTest {
    @Autowired MockMvc mvc;
    @MockBean SoakTestService service;

    @Test void run_ok() throws Exception {
        SoakReport.Metrics m = new SoakReport.Metrics();
        SoakReport rep = new SoakReport(10, "all", 2, m, List.of(), java.time.Instant.now(), java.time.Instant.now());
        Mockito.when(service.run(10, "all")).thenReturn(rep);

        mvc.perform(get("/internal/soak/run?k=10&topic=all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.k").value(10))
            .andExpect(jsonPath("$.topic").value("all"));
    }
}