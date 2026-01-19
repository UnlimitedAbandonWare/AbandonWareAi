package com.abandonware.ai.agent.tool;

import com.abandonware.ai.agent.integrations.WebSearchGateway;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.errors.ValidationException;
import org.junit.jupiter.api.Test;
import java.util.Map;




import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

public class WebSearchToolValidationTest {

    @Test
    void webSearch_requiresQuery() {
        var gateway = mock(WebSearchGateway.class);
        var tool = new WebSearchTool(gateway);
        assertThatThrownBy(() -> tool.execute(Map.of(), ToolContext.of("s")))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void webSearch_callsGateway() {
        var gateway = mock(WebSearchGateway.class);
        when(gateway.searchAndRank("hello", 6, "ko"))
            .thenReturn(java.util.List.of(Map.of("title","A")));
        var tool = new WebSearchTool(gateway);

        var out = tool.execute(Map.of("q","hello","topK",6,"lang","ko"), ToolContext.of("s"));
        assertThat(out).isNotNull();
        verify(gateway).searchAndRank("hello", 6, "ko");
    }
}