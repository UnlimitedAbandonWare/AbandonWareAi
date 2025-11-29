package com.example.lms.api;

import com.example.lms.jobs.JobService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = N8nWebhookController.class)
@TestPropertySource(properties = {"n8n.webhook.secret=testsecret"})
class N8nWebhookControllerTest {
    @Autowired MockMvc mvc;
    @MockBean JobService jobs;

    @Test void unauthorized_when_signature_invalid() throws Exception {
        mvc.perform(post("/hooks/n8n").contentType(MediaType.APPLICATION_JSON)
                .content("{\"a\":1}").header("X-Signature","sha256=deadbeef"))
            .andExpect(status().isUnauthorized());
    }

    @Test void accepted_when_signature_valid() throws Exception {
        String body = "{\"a\":1}";
        String sig = "sha256=" + hex(hmac(body.getBytes(), "testsecret"));
        Mockito.when(jobs.enqueue(body)).thenReturn("job-1");

        mvc.perform(post("/hooks/n8n").contentType(MediaType.APPLICATION_JSON)
                .content(body).header("X-Signature", sig))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-1"));
    }

    private static byte[] hmac(byte[] data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        return mac.doFinal(data);
    }
    private static String hex(byte[] b){ StringBuilder sb=new StringBuilder(); for(byte x:b) sb.append(String.format("%02x",x)); return sb.toString(); }
}