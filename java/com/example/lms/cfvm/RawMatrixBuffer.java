
package com.example.lms.cfvm;

import java.util.concurrent.ConcurrentLinkedQueue;



public class RawMatrixBuffer {
    private final ConcurrentLinkedQueue<long[]> q = new ConcurrentLinkedQueue<>();
    public void add(long id, long a, long b) { q.add(new long[]{id,a,b}); }
    public int size() { return q.size(); }
}