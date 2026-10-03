package com.example.lms.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DebugStudioControllerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class))
            .withUserConfiguration(StudioOnly.class);

    @Configuration
    @Import(DebugStudioController.class)
    static class StudioOnly {
    }

    @Test
    void flagOffRegistersNoBeanSoRouteIsNotFound() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DebugStudioController.class));
        runner.withPropertyValues("debug.studio.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DebugStudioController.class));
    }

    @Test
    void flagOnLoopbackForwardsToInterviewStudio() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DebugStudioController()).build();
        mvc.perform(get("/debug/studio"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/assets/interview/index.html"));
        mvc.perform(get("/debug/display"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/assets/display/index.html"));
    }

    @Test
    void flagOnNonLoopbackPeerIsNotFound() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DebugStudioController()).build();
        mvc.perform(get("/debug/studio").with(req -> { req.setRemoteAddr("203.0.113.10"); return req; }))
                .andExpect(status().isNotFound());
    }

    @Test
    void tunneledPublicHostIsNotFoundEvenWithLoopbackPeer() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DebugStudioController()).build();
        mvc.perform(get("/debug/studio").with(req -> { req.setRemoteAddr("127.0.0.1"); req.setServerName("abandonwareai.kro.kr"); return req; }))
                .andExpect(status().isNotFound());
    }
}
