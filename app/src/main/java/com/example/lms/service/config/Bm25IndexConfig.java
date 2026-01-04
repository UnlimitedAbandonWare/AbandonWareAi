package com.example.lms.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.lms.service.service.rag.bm25.Bm25Index;
import com.example.lms.service.service.rag.handler.Bm25Handler;

@Configuration
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.config.Bm25IndexConfig
 * Role: config
 * Dependencies: com.example.lms.service.service.rag.bm25.Bm25Index, com.example.lms.service.service.rag.handler.Bm25Handler
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.config.Bm25IndexConfig
role: config
*/
public class Bm25IndexConfig {
    @Bean public Bm25Index bm25Index() { return new Bm25Index(); }
    @Bean public Bm25Handler bm25Handler(Bm25Index index) {
        Bm25Handler h=new Bm25Handler(index); h.setEnabled(true); h.setTopK(8); return h; }
}