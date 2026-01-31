package com.example.lms.util;


/** Simple retry guard to prevent infinite critic/repair loops. */
public class CircuitBreaker {
    private final int maxRetries;
    private int tries = 0;
    public CircuitBreaker(int maxRetries){ this.maxRetries = maxRetries; }
    public boolean allow(){ return tries++ < maxRetries; }
    public int tries(){ return tries; }
    public int maxRetries(){ return maxRetries; }
}