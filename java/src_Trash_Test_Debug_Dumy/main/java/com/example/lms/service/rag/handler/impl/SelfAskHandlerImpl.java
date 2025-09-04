package com.example.lms.service.rag.handler.impl;

import com.example.lms.service.rag.handler.SelfAskHandler;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;

/**
 * Legacy adapter for {@link SelfAskHandler}. This class is retained for binary compatibility
 * but is no longer registered as a Spring component. Prefer injecting {@link SelfAskHandler}
 * directly.
 */
@Deprecated
public class SelfAskHandlerImpl extends SelfAskHandler {

    public SelfAskHandlerImpl(SelfAskWebSearchRetriever retriever) {
        super(retriever);
    }
}
