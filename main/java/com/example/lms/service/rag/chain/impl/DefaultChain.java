package com.example.lms.service.rag.chain.impl;

import com.example.lms.service.rag.chain.*;
import java.util.List;




public class DefaultChain implements Chain {
    private final List<ChainLink> links;
    private int idx = 0;
    public DefaultChain(List<ChainLink> links) { this.links = links; }
    @Override
    public ChainOutcome proceed(ChainContext ctx) {
        if (idx >= links.size()) return ChainOutcome.PASS;
        ChainLink link = links.get(idx++);
        return link.handle(ctx, this);
    }
}