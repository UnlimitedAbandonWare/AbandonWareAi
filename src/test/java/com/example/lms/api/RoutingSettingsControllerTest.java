package com.example.lms.api;

import com.example.lms.routing.*;
import com.example.lms.service.ChatModelCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RoutingSettingsControllerTest {
    @Test void readUsesOnlyThreePostContractAndReturnsRuntimeOff() throws Exception {
        var service=mock(RoutingSettingsService.class);var resolver=mock(RoutingProfileResolver.class);
        when(service.read()).thenReturn(new RoutingSettingsService.State(0,null,RoutingProfile.empty()));
        var mvc=standaloneSetup(new RoutingSettingsController(service,resolver,null,null)).build();
        mvc.perform(post("/api/settings/routing/read").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.runtimeEnabled").value(false))
            .andExpect(jsonPath("$.profileRevision").value(0));
        verify(service).read();verifyNoMoreInteractions(service);
    }
    @Test void saveRejectsExplanationFieldsAndReturnsOnlyReasonCode() throws Exception {
        var mvc=standaloneSetup(new RoutingSettingsController(mock(RoutingSettingsService.class),mock(RoutingProfileResolver.class),null,null)).build();
        mvc.perform(post("/api/settings/routing/save").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":0,\"profile\":{},\"observedValue\":\"PRIVATE\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reasonCode").value("invalid_routing_request"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE"))));
    }
    @Test void revisionConflictIs409WithoutAutomaticResave() throws Exception {
        var service=mock(RoutingSettingsService.class);var resolver=mock(RoutingProfileResolver.class);
        when(service.save(any(),eq(0L),isNull())).thenThrow(new RoutingSettingsService.Conflict());
        var mvc=standaloneSetup(new RoutingSettingsController(service,resolver,null,null)).build();
        mvc.perform(post("/api/settings/routing/save").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":0,\"expectedProfileHash\":null,\"profile\":{\"schemaVersion\":1,\"enabled\":false,\"bindings\":{},\"additionalPaidAllowed\":false,\"additionalCostCapUsd\":0}}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.reasonCode").value("revision_conflict"));
        verify(service,times(1)).save(any(),eq(0L),isNull());
    }
}
