package com.example.lms.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.lms.service.service.rag.bm25.Bm25Index;
import com.example.lms.service.service.rag.handler.Bm25Handler;

@Configuration
public class Bm25IndexConfig {
    @Bean public Bm25Index bm25Index() { return new Bm25Index(); }
    @Bean public Bm25Handler bm25Handler(Bm25Index index) {
        Bm25Handler h=new Bm25Handler(index); h.setEnabled(true); h.setTopK(8); return h; }
}