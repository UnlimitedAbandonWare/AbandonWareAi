package com.example.lms.service.rag.retriever;

import java.util.*;
public class OcrRetriever /* implements RagHandler */ {
    public static class Candidate {
        public String id;
        public String title;
        public String snippet;
        public String source;
        public double score;
        public int rank;
        public Candidate(String text, double confidence){
            this.id = Integer.toHexString(text.hashCode());
            this.title = text.length()>64? text.substring(0,64): text;
            this.snippet = text;
            this.source = "ocr";
            this.score = confidence;
            this.rank = 0;
        }
    }
    public List<Candidate> retrieve(Object input /* Query q, TraceContext tx */){
        // placeholder: actual OCR service is wired elsewhere
        return Collections.emptyList();
    }
}