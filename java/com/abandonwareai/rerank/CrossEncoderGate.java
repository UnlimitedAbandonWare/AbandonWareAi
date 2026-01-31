package com.abandonwareai.rerank;

import org.springframework.stereotype.Component;

@Component
public class CrossEncoderGate {
    private int permits=4; public synchronized boolean acquire(){ return permits-->0; } public synchronized void release(){ permits++; }

}