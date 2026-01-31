package com.example.lms.guard;

import com.example.lms.trace.TraceContext;

public class AnswerSanitizer {
    private final TraceContext trace;
    public AnswerSanitizer(){
        this.trace = new TraceContext();
    }
    public AnswerSanitizer(TraceContext trace){
        this.trace = trace == null ? new TraceContext() : trace;
    }
    public String sanitize(String text){
        if (text == null) return "";
        String out = text.replaceAll("\\s+", " ").trim();
        if (trace.isZeroBreak()) {
            out = "【주의: 확장 탐색(Zero Break) 모드 적용】\n" + out;
        }
        return out;
    }
}