package com.example.moe;


public final class GateVector {

    private final double web;
    private final double rag;
    private final double mem;
    private final double uncertainty;
    private final long ttlMillis;

    public GateVector(double web, double rag, double mem, double uncertainty, long ttlMillis) { this.web=web; this.rag=rag; this.mem=mem; this.uncertainty=uncertainty; this.ttlMillis=ttlMillis; }

    public double web(){ return this.web; }
    public double rag(){ return this.rag; }
    public double mem(){ return this.mem; }
    public double uncertainty(){ return this.uncertainty; }
    public long ttlMillis(){ return this.ttlMillis; }

    
    public double[] normalized(double minFloor) {
        double w=web, r=rag, m=mem;
        double sum = w+r+m; 
        if (sum <= 0) { w=r=m=1.0; sum=3.0; 
}

        w/=sum; r/=sum; m/=sum;
        w = Math.max(w, minFloor); 
        r = Math.max(r, minFloor); 
        m = Math.max(m, minFloor);
        double s2 = w+r+m; 
        return new double[]{w/s2, r/s2, m/s2};
    }
}