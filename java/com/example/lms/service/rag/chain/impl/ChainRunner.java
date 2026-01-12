package com.example.lms.service.rag.chain.impl;

import com.example.lms.service.rag.chain.*;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.prompt.PromptContext;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.util.Arrays;




@Component
@RequiredArgsConstructor
public class ChainRunner {

    private final com.example.lms.service.rag.chain.LocationInterceptHandler locationInterceptHandler;
    private final com.example.lms.service.rag.chain.AttachmentContextHandler attachmentContextHandler;
    private final com.example.lms.service.rag.chain.ImagePromptGroundingHandler imagePromptGroundingHandler;

    public ChainOutcome run(String sessionId, String userId, String userMessage, ChatStreamEmitter emitter) {
        PromptContext ctx = com.example.lms.prompt.PromptContext.builder()
                .userQuery(userMessage)
                .build();
        DefaultChainContext dctx = new DefaultChainContext(sessionId, userId, userMessage, ctx, emitter);
        DefaultChain chain = new DefaultChain(Arrays.asList(
                locationInterceptHandler,
                attachmentContextHandler,
                imagePromptGroundingHandler
        ));
        try {
            return chain.proceed(dctx);
        } catch (Exception e) {
            return ChainOutcome.PASS;
        }
    }
}