package com.abandonwareai.resilience.cfvm;

import org.springframework.stereotype.Component;

@Component
public class RawMatrixBuffer {
    private final java.util.List<String[]> buf = new java.util.ArrayList<>();
    public void push(String[] slot){ buf.add(slot); }

}