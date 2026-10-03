package com.example.lms.service.rag.chain.impl;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.chain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChainRunnerAttachmentFailureBoundaryTest {
    @AfterEach void clear(){TraceStore.clear();}
    @Test void imageFailureReachesExistingRunnerDiagnosticsInsteadOfSilentPass(){
        LocationInterceptHandler location=mock(LocationInterceptHandler.class);
        when(location.handle(any(),any())).thenAnswer(call->((Chain)call.getArgument(1)).proceed(call.getArgument(0)));
        ImagePromptGroundingHandler image=mock(ImagePromptGroundingHandler.class);
        when(image.handle(any(),any())).thenThrow(new IllegalStateException("synthetic"));
        DebugEventStore events=new DebugEventStore();
        DefaultListableBeanFactory beans=new DefaultListableBeanFactory();beans.registerSingleton("debugEvents",events);
        ChainRunner runner=new ChainRunner(location,new AttachmentContextHandler(null),image,beans.getBeanProvider(DebugEventStore.class),null,null);
        assertEquals(ChainOutcome.PASS,runner.run("synthetic-session","synthetic-user","synthetic question",null));
        verify(image,times(1)).handle(any(),any());
        assertTrue(events.list(5).stream().anyMatch(e->"ChainRunner.run".equals(e.where())));
        assertNotEquals("attachment_context_error",TraceStore.get("cihRag.iqrDisabledReason"));
    }
}
