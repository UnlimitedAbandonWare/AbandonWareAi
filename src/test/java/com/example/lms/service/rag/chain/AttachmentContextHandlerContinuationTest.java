package com.example.lms.service.rag.chain;

import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.AttachmentService;
import com.example.lms.dto.AttachmentDto;
import com.example.lms.service.rag.chain.impl.DefaultChain;
import com.example.lms.service.rag.chain.impl.DefaultChainContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentContextHandlerContinuationTest {
    static final AttachmentOwnerIdentity OWNER=AttachmentOwnerIdentity.forAnonymous("synthetic-owner");
    @AfterEach void clear(){TraceStore.clear();}
    private static DefaultChainContext context(){
        return new DefaultChainContext("synthetic-session","user","question",PromptContext.builder().build(),null,null,OWNER);
    }
    @Test void statefulChainPropagatesOriginalFailureWithoutSkippingToPass(){
        RuntimeException original=new IllegalStateException("synthetic");
        AtomicInteger calls=new AtomicInteger();
        DefaultChain chain=new DefaultChain(List.of(new AttachmentContextHandler(null),(ctx,next)->{
            calls.incrementAndGet();throw original;
        }));
        assertSame(original,assertThrows(IllegalStateException.class,()->chain.proceed(context())));
        assertEquals(1,calls.get());
        assertNotEquals("attachment_context_error",TraceStore.get("cihRag.iqrDisabledReason"));
    }
    @Test void downstreamFailureDoesNotOverwriteAttachmentDiagnosticsOrRepeatContinuation(){
        AttachmentService service=mock(AttachmentService.class);
        when(service.findBySession("synthetic-session",OWNER)).thenReturn(List.of(new AttachmentDto("a","doc.txt",1L,"text/plain","/synthetic")));
        RuntimeException original=new IllegalArgumentException("synthetic");
        AtomicInteger calls=new AtomicInteger();
        Chain next=ctx->{calls.incrementAndGet();throw original;};
        assertSame(original,assertThrows(IllegalArgumentException.class,()->new AttachmentContextHandler(service).handle(context(),next)));
        assertEquals(1,calls.get());assertEquals(1,TraceStore.get("cihRag.activeFileCount"));
        assertNotEquals("attachment_context_error",TraceStore.get("cihRag.iqrDisabledReason"));
    }
    @Test void lookupFailureStillContinuesOnce(){
        AttachmentService service=mock(AttachmentService.class);
        when(service.findBySession("synthetic-session",OWNER)).thenThrow(new IllegalStateException("synthetic"));
        AtomicInteger calls=new AtomicInteger();
        assertEquals(ChainOutcome.SUCCESS_PASS,new AttachmentContextHandler(service).handle(context(),ctx->{calls.incrementAndGet();return ChainOutcome.SUCCESS_PASS;}));
        assertEquals(1,calls.get());assertEquals("attachment_context_error",TraceStore.get("cihRag.iqrDisabledReason"));
    }
    @ParameterizedTest @EnumSource(ChainOutcome.class)
    void continuationOutcomeIsUnchanged(ChainOutcome expected){
        AtomicInteger calls=new AtomicInteger();
        assertEquals(expected,new AttachmentContextHandler(null).handle(context(),ctx->{calls.incrementAndGet();return expected;}));
        assertEquals(1,calls.get());
    }
}
